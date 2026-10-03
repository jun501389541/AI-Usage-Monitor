package server

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"aiusage.local/bridge/internal/bridge"
	"aiusage.local/bridge/internal/identity"
	"aiusage.local/bridge/internal/pairing"
)

const usagePath = "/v1/accounts/codex/usage"

// paired wires the whole stack the way main.go will: a real identity, a real
// pairing store, the same quota service.
func paired(t *testing.T) (*Server, *stubFetch, *pairing.Store, identity.Identity) {
	t.Helper()
	dir := t.TempDir()
	id, err := identity.Load(dir)
	if err != nil {
		t.Fatalf("identity.Load: %v", err)
	}
	store, err := pairing.NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("pairing.NewStore: %v", err)
	}
	stub := &stubFetch{}
	svc := bridge.NewService(stub, bridge.NewStore(filepath.Join(dir, "state.json")), time.Minute)
	s, err := NewWithOptions(svc, "127.0.0.1:0", Options{Pairing: store, ID: &id, Advertise: []string{"127.0.0.1"}})
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	return s, stub, store, id
}

func pair(t *testing.T, s *Server, store *pairing.Store, name string) (deviceID, token string) {
	t.Helper()
	pairToken, _, _, err := store.Issue(pairing.DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	body, err := json.Marshal(map[string]string{"pairToken": pairToken, "deviceName": name})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/pair", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair = %d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		DeviceID    string `json:"deviceId"`
		DeviceToken string `json:"deviceToken"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("pair body: %v (%s)", err, rec.Body)
	}
	if out.DeviceID == "" || len(out.DeviceToken) != 64 {
		t.Fatalf("pair returned %+v, want an id and a 64-char token", out)
	}
	return out.DeviceID, out.DeviceToken
}

// The bind guard is the whole safety story for reaching a phone. It has to refuse
// half-configured network service, not just unconfigured service: pairing without
// a certificate would send device tokens in cleartext, and a certificate without
// pairing would publish quota to anyone who can connect.
func TestNonLoopbackBindRequiresBothTLSandPairing(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.Load(dir)
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	store, err := pairing.NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("pairing: %v", err)
	}
	svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(dir, "state.json")), time.Minute)
	lan := "192.168.1.55:38411"

	cases := []struct {
		name       string
		opts       Options
		wantRefuse bool
		mentions   string
	}{
		{"nothing configured", Options{}, true, ""},
		{"pairing without a certificate", Options{Pairing: store}, true, "TLS"},
		{"a certificate without pairing", Options{ID: &id}, true, "pairing"},
		{"both", Options{Pairing: store, ID: &id, Advertise: []string{"192.168.1.55"}}, false, ""},
	}
	for _, tc := range cases {
		_, err := NewWithOptions(svc, lan, tc.opts)
		if tc.wantRefuse {
			if !errors.Is(err, ErrNotLoopback) {
				t.Errorf("%s: NewWithOptions = %v, want ErrNotLoopback", tc.name, err)
				continue
			}
			if tc.mentions != "" && !strings.Contains(err.Error(), tc.mentions) {
				t.Errorf("%s: refusal should name what is missing (%q): %v", tc.name, tc.mentions, err)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s: NewWithOptions = %v, want it to accept a secured bind", tc.name, err)
		}
	}

	// Loopback keeps working with nothing configured, so the debug channel that
	// Phase 6 acceptance depends on is not what this guard broke.
	if _, err := NewWithOptions(svc, "127.0.0.1:0", Options{}); err != nil {
		t.Errorf("plain loopback bind = %v, want success", err)
	}
}

func TestAccountDataRequiresALiveDeviceToken(t *testing.T) {
	s, stub, store, _ := paired(t)
	deviceID, token := pair(t, s, store, "Pixel")

	get := func(header string) (int, string) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, usagePath, nil)
		if header != "" {
			req.Header.Set("Authorization", header)
		}
		s.Handler().ServeHTTP(rec, req)
		return rec.Code, rec.Body.String()
	}

	if code, body := get(""); code != http.StatusUnauthorized {
		t.Fatalf("no header = %d %s, want 401", code, body)
	}
	if code, body := get("Bearer deadbeef"); code != http.StatusUnauthorized {
		t.Fatalf("wrong token = %d %s, want 401", code, body)
	}
	if code, body := get("Bearer " + token); code != http.StatusOK {
		t.Fatalf("valid token = %d %s, want 200", code, body)
	}
	if stub.calls != 1 {
		t.Errorf("the rejected requests should not reach Codex; calls=%d", stub.calls)
	}

	if err := store.Revoke(deviceID); err != nil {
		t.Fatalf("Revoke: %v", err)
	}
	code, body := get("Bearer " + token)
	if code != http.StatusUnauthorized {
		t.Fatalf("revoked token = %d %s, want 401", code, body)
	}
	// A revoked device must not be answered with anything that reads as
	// "the computer is offline"; it is the same class the phone maps onto
	// "re-pair", and the body must never repeat the secret.
	if !strings.Contains(body, ClassUnpaired) {
		t.Errorf("revoked body should carry the unpaired class: %s", body)
	}
	if strings.Contains(body, token) {
		t.Errorf("the response echoed the device token: %s", body)
	}

	// And without pairing configured the same handler stays open on loopback:
	// Phase 5/6 behaviour must not have been changed by this file.
	plainDir := t.TempDir()
	plain := &stubFetch{}
	plainSvc := bridge.NewService(plain, bridge.NewStore(filepath.Join(plainDir, "state.json")), time.Minute)
	plainServer, err := New(plainSvc, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	rec := httptest.NewRecorder()
	plainServer.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, usagePath, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("unpaired loopback = %d, want 200 (Phase 6 acceptance depends on it)", rec.Code)
	}
}

func TestPairIsOneTimeAndRejectsTheWrongShapes(t *testing.T) {
	s, _, store, _ := paired(t)
	pairToken, _, _, err := store.Issue(pairing.DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	post := func(body string) (int, string) {
		rec := httptest.NewRecorder()
		s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/pair", strings.NewReader(body)))
		return rec.Code, rec.Body.String()
	}

	ok, okBody := post(`{"pairToken":"` + pairToken + `","deviceName":"Pixel"}`)
	if ok != http.StatusOK {
		t.Fatalf("first pair = %d %s", ok, okBody)
	}
	if code, body := post(`{"pairToken":"` + pairToken + `","deviceName":"again"}`); code != http.StatusUnauthorized {
		t.Fatalf("replayed pair = %d %s, want 401", code, body)
	}
	if code, body := post(`{"pairToken":"` + strings.Repeat("a", 64) + `"}`); code != http.StatusUnauthorized {
		t.Fatalf("unknown pair token = %d %s, want 401", code, body)
	}
	// The rejection must not repeat what was presented: an attacker probing codes
	// learns nothing from the answer.
	if _, body := post(`{"pairToken":"` + strings.Repeat("b", 64) + `"}`); strings.Contains(body, strings.Repeat("b", 64)) {
		t.Fatalf("the rejection echoed the presented secret: %s", body)
	}
	if code, body := post(`{ not json`); code != http.StatusBadRequest {
		t.Fatalf("malformed body = %d %s, want 400", code, body)
	}
	if code, body := post(strings.Repeat("x", 9000)); code != http.StatusBadRequest {
		t.Fatalf("oversized body = %d %s, want 400 without reading it all", code, body)
	}
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/v1/pair", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET /v1/pair = %d, want 405", rec.Code)
	}
}

// Health must keep telling the operator what it knows without spawning an app
// server, and in paired mode that includes the identity a phone pins - the fact
// that answers "did the Bridge keep the same key after the restart?".
func TestHealthAdmitsIdentityWithoutContactingCodex(t *testing.T) {
	s, stub, store, id := paired(t)
	_, _ = pair(t, s, store, "Pixel")

	code, body := get(t, s, "/v1/health")
	if code != http.StatusOK {
		t.Fatalf("health = %d %s", code, body)
	}
	var info map[string]any
	if err := json.Unmarshal([]byte(body), &info); err != nil {
		t.Fatalf("health JSON: %v (%s)", err, body)
	}
	if info["bridgeId"] != id.BridgeID {
		t.Errorf("bridgeId = %v, want %s", info["bridgeId"], id.BridgeID)
	}
	if info["fingerprint"] != id.Fingerprint {
		t.Errorf("fingerprint = %v, want %s", info["fingerprint"], id.Fingerprint)
	}
	if info["tls"] != true {
		t.Errorf("tls = %v, want true in paired mode", info["tls"])
	}
	if n, ok := info["pairedDevices"].(float64); !ok || n != 1 {
		t.Errorf("pairedDevices = %v, want 1", info["pairedDevices"])
	}
	if n, ok := info["outstandingPairings"].(float64); !ok || n != 0 {
		t.Errorf("outstandingPairings = %v, want 0 after the exchange", info["outstandingPairings"])
	}
	if stub.calls != 0 {
		t.Errorf("health reached Codex (%d calls); it may only report what is stored", stub.calls)
	}
}

// The fingerprint is only worth anything if the certificate on the wire matches it
// and a different one does not connect. This is P-2 from the plan, run at the
// Go level so the behaviour is covered even when no phone is attached.
func TestServedCertificateMatchesThePinnedFingerprint(t *testing.T) {
	s, _, store, id := paired(t)
	_, token := pair(t, s, store, "Pixel")

	ln, err := s.Listen("127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- s.Serve(ln) }()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = s.Shutdown(ctx)
		<-serveErr
	})

	url := "https://" + ln.Addr().String() + usagePath
	client := pinnedClient(id.Fingerprint)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("pinned request over TLS: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}

	// A pin copied from a different Bridge must fail the handshake rather than
	// fall back to trusting something else.
	other, err := identity.Load(t.TempDir())
	if err != nil {
		t.Fatalf("second identity: %v", err)
	}
	if _, err := pinnedClient(other.Fingerprint).Get(url); err == nil {
		t.Fatal("a certificate that does not match the pinned fingerprint was accepted")
	}

	// Plain HTTP against a TLS listener must not serve data. Go's server answers
	// this one with a 400 that says which scheme is missing rather than handing
	// over anything, so the assertion is about the answer, not about the error.
	plainResp, err := http.Get("http://" + ln.Addr().String() + usagePath)
	if err == nil {
		defer plainResp.Body.Close()
		if plainResp.StatusCode == http.StatusOK {
			t.Fatalf("a cleartext request returned 200; pairing must not be able to run without TLS")
		}
	}
	if plainResp != nil && plainResp.StatusCode == http.StatusOK {
		t.Fatal("cleartext served data")
	}
}

func pinnedClient(fingerprint string) *http.Client {
	return &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				// Verification is done by digest comparison below, so the chain of
				// trust and the name in the certificate are not consulted at all:
				// a self-signed Bridge certificate has neither.
				InsecureSkipVerify: true,
				VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
					if len(rawCerts) != 1 {
						return errors.New("expected one certificate")
					}
					cert, err := x509.ParseCertificate(rawCerts[0])
					if err != nil {
						return err
					}
					spki, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
					if err != nil {
						return err
					}
					sum := sha256.Sum256(spki)
					if hex.EncodeToString(sum[:]) != fingerprint {
						return errors.New("the certificate does not match the pinned fingerprint")
					}
					return nil
				},
			},
		},
	}
}

// The admin routes mint credentials, so they answer the machine, not the network.
// The check is on the peer address, which a client cannot choose, rather than on
// the Host header, which it can.
func TestAdminRoutesAnswerOnlyLoopbackPeers(t *testing.T) {
	s, _, store, _ := paired(t)
	_, token := pair(t, s, store, "Pixel")

	for _, remote := range []string{"192.168.1.55:54321", "10.0.2.2:54321", "127.0.0.1:54321", "[::1]:54321", "garbage"} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/v1/admin/pair", strings.NewReader("{}"))
		req.RemoteAddr = remote
		s.Handler().ServeHTTP(rec, req)

		fromNetwork := !strings.HasPrefix(remote, "127.") && !strings.HasPrefix(remote, "[::1]")
		want := http.StatusOK
		if fromNetwork {
			want = http.StatusNotFound
		}
		if rec.Code != want {
			t.Errorf("POST /v1/admin/pair from %s = %d, want %d", remote, rec.Code, want)
		}
	}

	// Device listing and revocation follow the same rule.
	rec := httptest.NewRecorder()
	list := httptest.NewRequest(http.MethodGet, "/v1/admin/devices", nil)
	list.RemoteAddr = "192.168.1.55:1"
	s.Handler().ServeHTTP(rec, list)
	if rec.Code != http.StatusNotFound {
		t.Errorf("GET /v1/admin/devices from the network = %d, want 404", rec.Code)
	}

	// And the pairing a phone itself uses stays open on the network: that is the
	// whole point of it.
	rec = httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, usagePath, nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated account read = %d, want 401 (proves the admin guard is not doing the authorising)", rec.Code)
	}
	rec = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, usagePath, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.RemoteAddr = "192.168.1.55:1"
	s.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("paired read from the network = %d, want 200", rec.Code)
	}
}

// An offer that cannot be stored must not be printed. A pairing code the disk
// refused is one the next restart does not know about, so the operator would be
// handed something that stops working at an arbitrary moment - and the answer has
// to be a service error, not a quiet success and not an "unknown device".
func TestAdminReportsWhatItCouldNotStore(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.Load(dir)
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	blocker := filepath.Join(dir, "not-a-directory")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed blocker: %v", err)
	}
	// The registry starts empty (a missing file is a first run) but cannot write.
	store, err := pairing.NewStore(filepath.Join(blocker, "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(dir, "state.json")), time.Minute)
	s, err := NewWithOptions(svc, "127.0.0.1:0", Options{Pairing: store, ID: &id, Advertise: []string{"127.0.0.1"}})
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/v1/admin/pair", strings.NewReader("{}"))
	req.RemoteAddr = "127.0.0.1:5000"
	s.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("admin pair with an unwritable registry = %d %s, want 503", rec.Code, rec.Body.String())
	}

	// A typo in a revocation is still a 404, not a service error.
	rec = httptest.NewRecorder()
	revoke := httptest.NewRequest(http.MethodPost, "/v1/admin/devices/revoke", strings.NewReader(`{"deviceId":"dev_missing"}`))
	revoke.RemoteAddr = "127.0.0.1:5000"
	s.Handler().ServeHTTP(rec, revoke)
	if rec.Code != http.StatusNotFound {
		t.Errorf("revoking an unknown device = %d %s, want 404", rec.Code, rec.Body.String())
	}
}

// The revocation of a device that exists is only a 404 when there is no such
// device. When the file refuses the write, the answer has to be a service error:
// otherwise the operator believes the phone was cut off while the next restart
// reads an unrevised registry.
func TestAdminDistinguishesRevocationFailures(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.Load(dir)
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	store, err := pairing.NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(dir, "state.json")), time.Minute)
	s, err := NewWithOptions(svc, "127.0.0.1:0", Options{Pairing: store, ID: &id, Advertise: []string{"127.0.0.1"}})
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	deviceID, _ := pair(t, s, store, "Pixel")

	revoke := func(id string) (int, string) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/v1/admin/devices/revoke",
			strings.NewReader(`{"deviceId":"`+id+`"}`))
		req.RemoteAddr = "127.0.0.1:5000"
		s.Handler().ServeHTTP(rec, req)
		return rec.Code, rec.Body.String()
	}

	if code, body := revoke("dev_missing"); code != http.StatusNotFound {
		t.Fatalf("unknown device = %d %s, want 404", code, body)
	}

	// Make the registry's destination unusable: the file becomes a directory, so
	// the atomic rename is refused while reads of the old content still work.
	registry := filepath.Join(dir, "pairing.json")
	if err := os.Remove(registry); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if err := os.Mkdir(registry, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	code, body := revoke(deviceID)
	if code != http.StatusServiceUnavailable {
		t.Fatalf("a revocation that could not be written = %d %s, want 503", code, body)
	}
	if !strings.Contains(body, "could not be stored") {
		t.Errorf("the answer should say what failed: %s", body)
	}
	// And the device still authenticates, because nothing was persisted: memory
	// and disk must agree, which is the pair the pairing package tests directly.
	rec := httptest.NewRecorder()
	usage := httptest.NewRequest(http.MethodGet, usagePath, nil)
	usage.Header.Set("Authorization", "Bearer "+strings.Repeat("0", 64))
	s.Handler().ServeHTTP(rec, usage)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("a bogus token = %d, want 401 regardless of the failed revoke", rec.Code)
	}
}
