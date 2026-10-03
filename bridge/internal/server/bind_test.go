package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"aiusage.local/bridge/internal/identity"
	"aiusage.local/bridge/internal/pairing"
)

// These checks run over real sockets because the finding they discharge was about
// a startup path, not about a handler: an offer naming an address nobody listened
// on, and an operator left with no reachable way to pair. httptest would have
// agreed with either mistake.

func serveAll(t *testing.T, s *Server, listeners []net.Listener) {
	t.Helper()
	var wg sync.WaitGroup
	for _, ln := range listeners {
		wg.Add(1)
		go func(ln net.Listener) {
			defer wg.Done()
			_ = s.Serve(ln)
		}(ln)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = s.Shutdown(ctx)
		done := make(chan struct{})
		go func() { wg.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Errorf("Serve did not return after Shutdown")
		}
		for _, ln := range listeners {
			_ = ln.Close()
		}
	})
}

func postJSON(t *testing.T, client *http.Client, url string, body any) (int, string) {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	request, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		// A refused handshake is one of the answers under test, so it comes back as
		// text rather than ending the run.
		return 0, err.Error()
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		t.Fatalf("reading the response from %s: %v", url, err)
	}
	return response.StatusCode, string(data)
}

func getURL(t *testing.T, client *http.Client, url string, token string) (int, string) {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := client.Do(request)
	if err != nil {
		return 0, err.Error()
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		t.Fatalf("reading the response from %s: %v", url, err)
	}
	return response.StatusCode, string(data)
}

// A Bridge bound to a network address must answer at the address it offers, pin
// the same certificate there, stay administrable from loopback, and let a paired
// device read its numbers over that address. That is the whole P2 claim checked
// with sockets rather than with a constructed struct.
func TestANetworkBindAnswersAtTheAddressItOffers(t *testing.T) {
	lan := lanAddress(t)
	if lan == "" {
		t.Skip("no non-loopback unicast address on this machine")
	}
	stubAddresses(t, []net.Addr{v4(lan)}, nil)
	addr := net.JoinHostPort(lan, "0")
	s, store, id, err := secured(t, addr, nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	listeners, err := s.ListenAll(addr)
	if err != nil {
		t.Fatalf("ListenAll: %v", err)
	}
	if len(listeners) != 2 {
		t.Fatalf("listeners = %d, want the network bind and the loopback one", len(listeners))
	}
	serveAll(t, s, listeners)

	port := strconv.Itoa(s.Port())
	networkURL := "https://" + net.JoinHostPort(lan, port)
	loopbackURL := "https://" + net.JoinHostPort("127.0.0.1", port)
	client := pinnedClient(id.Fingerprint)

	code, body := getURL(t, client, networkURL+"/v1/health", "")
	if code != http.StatusOK || !strings.Contains(body, `"bridgeId"`) {
		t.Fatalf("health over the offered address = %d %s, want 200 with the identity", code, body)
	}

	// The pin is checked on that socket, not only on loopback: a certificate from
	// another Bridge must not connect even though the address is this machine's.
	other, err := identity.Load(t.TempDir())
	if err != nil {
		t.Fatalf("second identity: %v", err)
	}
	if code, refused := getURL(t, pinnedClient(other.Fingerprint), networkURL+"/v1/health", ""); code != 0 {
		t.Fatalf("a mismatched fingerprint was accepted at %s (%d %s)", networkURL, code, refused)
	}

	// The operator's half: --add-device dials loopback, which a LAN-only bind used
	// to have no way to answer.
	code, body = postJSON(t, client, loopbackURL+"/v1/admin/pair", map[string]string{})
	if code != http.StatusOK {
		t.Fatalf("admin pair over loopback = %d %s, want 200: a network bind must stay administrable", code, body)
	}
	var offer struct {
		PairToken string `json:"pairToken"`
		Payload   string `json:"payload"`
	}
	if err := json.Unmarshal([]byte(body), &offer); err != nil {
		t.Fatalf("decoding the offer: %v (%s)", err, body)
	}
	parsed, err := pairing.ParsePayload(offer.Payload)
	if err != nil {
		t.Fatalf("the offer a phone reads is not parseable: %v", err)
	}
	if got := strings.Join(parsed.Hosts, ","); got != lan+",127.0.0.1" {
		t.Errorf("the offer names %q, want the address it was bound to and loopback", got)
	}
	if parsed.Port != s.Port() {
		t.Errorf("the offer carries port %d, want %d", parsed.Port, s.Port())
	}
	if parsed.Fingerprint != id.Fingerprint {
		t.Errorf("the offer carries fingerprint %q, want %s", parsed.Fingerprint, id.Fingerprint)
	}

	// A phone redeeming that offer over the network address, then reading usage.
	code, body = postJSON(t, client, networkURL+"/v1/pair", map[string]string{
		"pairToken": offer.PairToken, "deviceName": "Pixel",
	})
	if code != http.StatusOK {
		t.Fatalf("pair over the network address = %d %s", code, body)
	}
	var exchanged struct {
		DeviceToken string `json:"deviceToken"`
	}
	if err := json.Unmarshal([]byte(body), &exchanged); err != nil {
		t.Fatalf("decoding the exchange: %v (%s)", err, body)
	}
	code, body = getURL(t, client, networkURL+usagePath, exchanged.DeviceToken)
	if code != http.StatusOK {
		t.Fatalf("a paired read over the network address = %d %s, want 200", code, body)
	}
	if _, err := store.Validate(exchanged.DeviceToken); err != nil {
		t.Errorf("the registry does not know the device it just issued: %v", err)
	}

	// And the routes that mint credentials stay closed to a peer that is not this
	// machine. Whether a self-connect arrives from the LAN address or from loopback
	// is the operating system's choice, so the peer is measured before it is
	// asserted - otherwise this check would pass by accident or fail by surprise.
	if source, ok := selfConnectSourceIsLoopback(lan, s.Port()); !ok {
		t.Errorf("connecting to this machine's own address %s failed: a listener should answer there", lan)
	} else if !source {
		code, _ = postJSON(t, client, networkURL+"/v1/admin/pair", map[string]string{})
		if code != http.StatusNotFound {
			t.Errorf("admin pair from a network peer = %d, want 404", code)
		}
	} else {
		t.Logf("note: this machine reaches its own LAN address from loopback, so the network-peer refusal is not asserted here")
	}
}

func selfConnectSourceIsLoopback(lan string, port int) (loopback bool, connected bool) {
	connection, err := net.Dial("tcp", net.JoinHostPort(lan, strconv.Itoa(port)))
	if err != nil {
		return false, false
	}
	defer connection.Close()
	host, _, err := net.SplitHostPort(connection.LocalAddr().String())
	if err != nil {
		return false, true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback(), true
}

// A wildcard bind answers on every address the machine has, so naming all of them
// in an offer is honest - and the administrative routes are reachable on loopback
// without a second listener, because the wildcard already covers it.
func TestAWildcardBindAnswersOnEveryAddressItOffers(t *testing.T) {
	lan := lanAddress(t)
	if lan == "" {
		t.Skip("no non-loopback unicast address on this machine")
	}
	stubAddresses(t, []net.Addr{v4("127.0.0.1"), v4(lan)}, nil)
	addr := net.JoinHostPort("0.0.0.0", "0")
	s, _, id, err := secured(t, addr, nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	if got := strings.Join(s.Advertised(), ","); got != "127.0.0.1,"+lan {
		t.Fatalf("advertised = %q, want loopback and %s", got, lan)
	}
	listeners, err := s.ListenAll(addr)
	if err != nil {
		t.Fatalf("ListenAll: %v", err)
	}
	if len(listeners) != 1 {
		t.Fatalf("listeners = %d, want one wildcard bind", len(listeners))
	}
	serveAll(t, s, listeners)

	port := strconv.Itoa(s.Port())
	client := pinnedClient(id.Fingerprint)
	for _, host := range s.Advertised() {
		code, body := getURL(t, client, "https://"+net.JoinHostPort(host, port)+"/v1/health", "")
		if code != http.StatusOK {
			t.Errorf("health at the offered address %s = %d %s, want 200", host, code, body)
		}
	}
}
