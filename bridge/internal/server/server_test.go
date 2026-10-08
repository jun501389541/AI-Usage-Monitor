package server

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"aiusage.local/bridge/internal/bridge"
	"aiusage.local/bridge/internal/codex"
	"aiusage.local/bridge/internal/discover"
)

type stubFetch struct {
	calls    int
	failWith error
	// succeedFirst lets a test model "read once, then the source breaks", which
	// is the only way the degraded path is reachable over HTTP.
	succeedFirst int
	// refuse travels with the failure the way the production fetcher reports it:
	// Codex asked for something we declined, then the read itself failed.
	refuse []string
}

func (f *stubFetch) Fetch() (bridge.Reading, error) {
	f.calls++
	if f.failWith != nil && f.calls > f.succeedFirst {
		return bridge.Reading{RefusedServerRequests: f.refuse}, f.failWith
	}
	snap, err := codex.ParseRateLimits([]byte(payload))
	if err != nil {
		panic(err)
	}
	return bridge.Reading{Snapshot: snap, CodexVersion: "0.121.0"}, nil
}

// rpcMethodNotFound satisfies the contract codex.IsMethodNotFound looks for, so
// a stub can produce that class without a child process answering -32601.
type rpcMethodNotFound struct{}

func (rpcMethodNotFound) Error() string { return "unknown method account/rateLimits/read" }
func (rpcMethodNotFound) RPCCode() int  { return -32601 }

const payload = `{
  "rateLimits": {
    "limitId": "codex",
    "primary":   { "usedPercent": 25, "windowDurationMins": 300,   "resetsAt": 1790936545 },
    "secondary": { "usedPercent": 50, "windowDurationMins": 10080, "resetsAt": 1791419454 },
    "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
    "planType":  "plus"
  }
}`

func newTestServer(t *testing.T) (*Server, *stubFetch) {
	t.Helper()
	stub := &stubFetch{}
	svc := bridge.NewService(stub, bridge.NewStore(filepath.Join(t.TempDir(), "state.json")), 5*time.Minute)
	s, err := New(svc, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return s, stub
}

func get(t *testing.T, s *Server, path string) (int, string) {
	t.Helper()
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec.Code, rec.Body.String()
}

// The host guard is the whole safety story of Phase 5's HTTP surface: a LAN bind
// would hand quota numbers, and eventually a pairing endpoint, to the network.
func TestNonLoopbackBindIsRefused(t *testing.T) {
	for _, addr := range []string{"0.0.0.0:38411", "192.168.1.5:38411", ":38411"} {
		svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
		_, err := New(svc, addr)
		if !errors.Is(err, ErrNotLoopback) {
			t.Errorf("New(%q) = %v, want ErrNotLoopback", addr, err)
		}
	}
	for _, addr := range []string{"127.0.0.1:38411", "localhost:38411", "[::1]:38411"} {
		svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
		if _, err := New(svc, addr); err != nil {
			t.Errorf("New(%q) should be allowed, got %v", addr, err)
		}
	}
}

func TestHealthAndProviders(t *testing.T) {
	s, _ := newTestServer(t)

	code, body := get(t, s, "/v1/health")
	if code != http.StatusOK || !strings.Contains(body, `"ok":true`) {
		t.Fatalf("health = %d %s", code, body)
	}

	code, body = get(t, s, "/v1/providers")
	if code != http.StatusOK {
		t.Fatalf("providers = %d %s", code, body)
	}
	var providers []providerEntry
	if err := json.Unmarshal([]byte(body), &providers); err != nil {
		t.Fatalf("decoding providers: %v (%s)", err, body)
	}
	if len(providers) != 1 || providers[0].ID != ProviderID {
		t.Fatalf("providers = %+v", providers)
	}
	// Codex reports no balance, only quota windows; the phone branches on this.
	if providers[0].ReportsBalance || !providers[0].ReportsQuotaWindows {
		t.Fatalf("capabilities wrong: %+v", providers[0])
	}
	if providers[0].AuthType != "bridge" {
		t.Fatalf("authType = %q", providers[0].AuthType)
	}
}

func TestUsageServesWindows(t *testing.T) {
	s, stub := newTestServer(t)

	code, body := get(t, s, "/v1/accounts/codex/usage")
	if code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", code, body)
	}
	var v bridge.View
	if err := json.Unmarshal([]byte(body), &v); err != nil {
		t.Fatalf("decoding view: %v (%s)", err, body)
	}
	if stub.calls != 1 {
		t.Fatalf("fetch calls = %d, want 1", stub.calls)
	}
	if len(v.State.Windows) != 2 {
		t.Fatalf("windows = %+v", v.State.Windows)
	}
	if v.FromCache {
		t.Fatal("a first read is not from cache")
	}
	if v.DataTimestamp.IsZero() || v.SourceTimestamp.IsZero() {
		t.Fatalf("timestamps missing: %+v", v)
	}
}

// A second request inside the TTL must not spawn a child; the count is the
// proof, because the JSON looks the same either way.
func TestSecondRequestIsServedFromCache(t *testing.T) {
	s, stub := newTestServer(t)
	if _, _ = get(t, s, "/v1/accounts/codex/usage"); stub.calls != 1 {
		t.Fatalf("first request: calls = %d", stub.calls)
	}
	_, body := get(t, s, "/v1/accounts/codex/usage")
	if stub.calls != 1 {
		t.Fatalf("calls = %d, the cache did not answer", stub.calls)
	}
	if !strings.Contains(body, `"fromCache":true`) {
		t.Fatalf("body should say it came from cache: %s", body)
	}
}

func TestRefreshParamGoesBackToCodex(t *testing.T) {
	s, stub := newTestServer(t)
	get(t, s, "/v1/accounts/codex/usage")
	get(t, s, "/v1/accounts/codex/usage?refresh=1")
	if stub.calls != 2 {
		t.Fatalf("calls = %d, want the refresh parameter to force a read", stub.calls)
	}
}

// Responses are assembled by the Bridge. If a handler ever starts forwarding the
// app server's document, the wire key names show up and this test says so.
func TestUpstreamWireKeysNeverAppearInResponses(t *testing.T) {
	s, _ := newTestServer(t)
	_, body := get(t, s, "/v1/accounts/codex/usage")
	for _, wireKey := range []string{"windowDurationMins", "used_percent", "resetsAt", "rateLimitsByLimitId"} {
		if strings.Contains(body, wireKey) {
			t.Fatalf("response leaked the upstream key %q: %s", wireKey, body)
		}
	}
}

func TestUnknownAccountAndBadRoutes(t *testing.T) {
	s, _ := newTestServer(t)
	if code, _ := get(t, s, "/v1/accounts/deepseek/usage"); code != http.StatusNotFound {
		t.Fatalf("status for an unknown account = %d, want 404", code)
	}
	if code, _ := get(t, s, "/v1/accounts/codex"); code != http.StatusNotFound {
		t.Fatalf("status for a truncated path = %d, want 404", code)
	}
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/accounts/codex/usage", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST = %d, want 405", rec.Code)
	}
}

// With nothing cached, a failed read is a 503 carrying the class, so the caller
// can tell "the computer is offline" from "the login expired" (rule 19).
//
// The class is read out of the parsed JSON. Searching the body for the class name
// proved nothing: the detail string is built from the same upstream text and the
// old code also prefixed it with the class, so the assertion stayed green while
// every cold failure was reported to clients as CODEX_UNKNOWN
// (docs/PHASE-5-REVIEW.md §2).
func TestFailureWithoutDataIsServiceUnavailable(t *testing.T) {
	svc := bridge.NewService(&stubFetch{failWith: discover.ErrNotFound},
		bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
	s, err := New(svc, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	code, body := get(t, s, "/v1/accounts/codex/usage")
	if code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (%s)", code, body)
	}
	if report := errorReport(t, body); report.Error != bridge.ClassNotFound {
		t.Fatalf("error field = %q, want %q (body %s)", report.Error, bridge.ClassNotFound, body)
	}
}

// Every class the Bridge can name has to arrive as a field on a *cold* failure,
// where there is no view to read one from. An unnamed error must not borrow a
// name it did not earn.
func TestEachFailureClassArrivesOnAColdFailure(t *testing.T) {
	cases := []struct {
		name string
		stub *stubFetch
		want string
	}{
		{"not found", &stubFetch{failWith: discover.ErrNotFound}, bridge.ClassNotFound},
		{"timeout", &stubFetch{failWith: codex.ErrTimeout}, bridge.ClassTimeout},
		{"stream closed", &stubFetch{failWith: codex.ErrClosed}, bridge.ClassTimeout},
		{"method unavailable", &stubFetch{failWith: rpcMethodNotFound{}}, bridge.ClassMethodUnavailable},
		{"refresh refused", &stubFetch{
			failWith: errors.New("app server closed the stream"),
			refuse:   []string{"account/chatgptAuthTokens/refresh"},
		}, bridge.ClassAuthRequired},
		{"unnamed", &stubFetch{failWith: errors.New("something with no category")}, bridge.ClassUnknown},
	}
	for _, tc := range cases {
		svc := bridge.NewService(tc.stub, bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
		s, err := New(svc, "127.0.0.1:0")
		if err != nil {
			t.Fatalf("%s: New: %v", tc.name, err)
		}
		code, body := get(t, s, "/v1/accounts/codex/usage")
		if code != http.StatusServiceUnavailable {
			t.Errorf("%s: status = %d, want 503 (%s)", tc.name, code, body)
			continue
		}
		if report := errorReport(t, body); report.Error != tc.want {
			t.Errorf("%s: error field = %q, want %q", tc.name, report.Error, tc.want)
		}
	}
}

// The cold path never passed the mask at all: nothing was stored, so the one
// place that redacted was skipped and the upstream string went out as-is. Three
// shapes, because asserting one only ever proves that one.
func TestColdFailureResponseAndDiskCarryNoCredential(t *testing.T) {
	secrets := []string{
		"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTYifQ.abcdefghijk_LMNOPqrst",
		"sk-syntheticReviewCredential0123456789",
		"Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.zzzzzzzzzzzzzzzzzzzzzzzzzz",
	}
	for _, secret := range secrets {
		dir := t.TempDir()
		store := bridge.NewStore(filepath.Join(dir, "s.json"))
		svc := bridge.NewService(&stubFetch{failWith: errors.New("upstream said: " + secret)}, store, time.Minute)
		s, err := New(svc, "127.0.0.1:0")
		if err != nil {
			t.Fatalf("New: %v", err)
		}
		code, body := get(t, s, "/v1/accounts/codex/usage")
		if code != http.StatusServiceUnavailable {
			t.Fatalf("status = %d, want 503 (%s)", code, body)
		}
		// The mask deliberately keeps six characters so a reader can see what
		// kind of thing was hidden, so the secret part is everything after that.
		if strings.Contains(body, secret[6:]) {
			t.Errorf("response still carries the credential: %s", body)
		}
		if !strings.Contains(body, "[redacted]") {
			t.Errorf("nothing was masked on the way out: %s", body)
		}
		st, err := store.Load()
		if err != nil {
			t.Fatalf("Load: %v", err)
		}
		if st.LastFailure == nil {
			t.Fatal("the cold failure was not recorded")
		}
		if strings.Contains(st.LastFailure.Message, secret[6:]) {
			t.Errorf("state file still carries the credential: %q", st.LastFailure.Message)
		}
	}
}

// errorReport parses the 503 body instead of searching it, so a class that only
// appears inside the free-text detail cannot be mistaken for the real field.
// The outlet mask exists for the errors that never become a Failure at all -
// an unreadable or unparsable state file. Their text quotes the path, and a path
// is exactly where a credential-shaped directory name would otherwise be served
// verbatim. This is also the only case where the mask at the outlet, rather than
// the one on the way into storage, is what protects the response.
func TestStateErrorsAreMaskedAtTheOutlet(t *testing.T) {
	const secret = "sk-syntheticReviewCredential0123456789"
	path := filepath.Join(t.TempDir(), secret+".json")
	if err := os.WriteFile(path, []byte("this is not json"), 0o600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	svc := bridge.NewService(&stubFetch{}, bridge.NewStore(path), time.Minute)
	s, err := New(svc, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	code, body := get(t, s, "/v1/accounts/codex/usage")
	if code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503 (%s)", code, body)
	}
	if !strings.Contains(body, "not valid JSON") {
		t.Fatalf("the useful part of the message was lost: %s", body)
	}
	if strings.Contains(body, secret[6:]) {
		t.Fatalf("the served message still carries the path: %s", body)
	}
}

func errorReport(t *testing.T, body string) struct {
	Error  string `json:"error"`
	Detail string `json:"detail"`
} {
	t.Helper()
	var report struct {
		Error  string `json:"error"`
		Detail string `json:"detail"`
	}
	if err := json.Unmarshal([]byte(body), &report); err != nil {
		t.Fatalf("body is not the JSON the caller parses: %v (%s)", err, body)
	}
	return report
}

// End-to-end over a real socket: the loopback bind answers, and Shutdown stops it.
func TestServeOnLoopbackAnswersAndShutsDown(t *testing.T) {
	s, _ := newTestServer(t)
	ln, err := s.Listen("127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- s.Serve(ln) }()

	url := "http://" + ln.Addr().String() + "/v1/health"
	resp, err := http.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), `"ok":true`) {
		t.Fatalf("response = %d %s", resp.StatusCode, body)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := s.Shutdown(ctx); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	select {
	case err := <-serveErr:
		if err != nil {
			t.Fatalf("Serve returned %v, want a clean stop", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Serve did not return after Shutdown")
	}
}

// A bind to the machine's own LAN address must be rejected even though the
// socket call itself would succeed - the guard is intent, not capability.
func TestLANAddressIsRejectedEvenWhenBindable(t *testing.T) {
	lan := lanAddress(t)
	if lan == "" {
		t.Skip("no non-loopback unicast address on this machine")
	}
	svc := bridge.NewService(&stubFetch{}, bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
	if _, err := New(svc, net.JoinHostPort(lan, "0")); !errors.Is(err, ErrNotLoopback) {
		t.Fatalf("New on LAN address %s = %v, want ErrNotLoopback", lan, err)
	}
}

func lanAddress(t *testing.T) string {
	t.Helper()
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Skipf("InterfaceAddrs: %v", err)
	}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() || ipnet.IP.IsLinkLocalUnicast() {
			continue
		}
		if ip4 := ipnet.IP.To4(); ip4 != nil {
			// Windows can enumerate stale virtual-adapter addresses that cannot
			// actually be bound. These tests need a usable LAN interface.
			listener, err := net.Listen("tcp", net.JoinHostPort(ip4.String(), "0"))
			if err != nil {
				continue
			}
			_ = listener.Close()
			return ip4.String()
		}
	}
	return ""
}

// The mask has to bite before anything is stored, so a degraded response built
// from an upstream error string is the case worth checking over HTTP.
func TestServedBodyCarriesNoCredentialShapes(t *testing.T) {
	const jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTYifQ.abcdefghijk_LMNOPqrst"
	stub := &stubFetch{failWith: errors.New("token rejected: " + jwt), succeedFirst: 1}
	svc := bridge.NewService(stub, bridge.NewStore(filepath.Join(t.TempDir(), "s.json")), 5*time.Minute)
	s, err := New(svc, "127.0.0.1:0")
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	if code, body := get(t, s, "/v1/accounts/codex/usage"); code != http.StatusOK {
		t.Fatalf("first read = %d %s", code, body)
	}
	code, body := get(t, s, "/v1/accounts/codex/usage?refresh=1")
	if code != http.StatusOK {
		t.Fatalf("degraded read = %d %s, want the cached numbers still served", code, body)
	}
	if !strings.Contains(body, `"degraded"`) {
		t.Fatalf("expected a degraded marker, got %s", body)
	}
	// The mask deliberately keeps a six-character fingerprint so a reader can
	// tell what was hidden, so the check is that the *secret part* is gone.
	if strings.Contains(body, jwt[10:]) {
		t.Fatalf("response still carries the credential: %s", body)
	}
	if !strings.Contains(body, "[redacted]") {
		t.Fatalf("nothing was masked: %s", body)
	}
}

func TestHealthReportsWhatItLearnedWithoutSpawning(t *testing.T) {
	s, stub := newTestServer(t)

	code, body := get(t, s, "/v1/health")
	if code != http.StatusOK {
		t.Fatalf("health before any read = %d %s", code, body)
	}
	if stub.calls != 0 {
		t.Fatalf("health spawned the child %d times, want 0", stub.calls)
	}
	if strings.Contains(body, "codexVersion") {
		t.Fatalf("health invented a version before contacting Codex: %s", body)
	}

	get(t, s, "/v1/accounts/codex/usage")
	_, body = get(t, s, "/v1/health")
	if !strings.Contains(body, `"codexVersion":"0.121.0"`) {
		t.Fatalf("health should report the handshake version, got %s", body)
	}
	if !strings.Contains(body, `"quotaWindows":true`) || !strings.Contains(body, `"balance":false`) {
		t.Fatalf("capabilities missing, got %s", body)
	}
	if strings.Contains(body, `"lastFailureClass"`) {
		t.Fatalf("a clean read should leave no failure marker: %s", body)
	}
}
