package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"aiusage.local/bridge/internal/bridge"
	"aiusage.local/bridge/internal/identity"
	"aiusage.local/bridge/internal/pairing"
)

// stubAddresses describes a machine whose addresses are not this test runner's, so
// the offer a bind produces can be asserted exactly rather than "whatever this
// laptop happens to have".
func stubAddresses(t *testing.T, addrs []net.Addr, err error) {
	t.Helper()
	original := interfaceAddrs
	interfaceAddrs = func() ([]net.Addr, error) { return addrs, err }
	t.Cleanup(func() { interfaceAddrs = original })
}

func v4(text string) *net.IPNet {
	return &net.IPNet{IP: net.ParseIP(text).To4(), Mask: net.CIDRMask(24, 32)}
}

// secured wires a real identity and a real pairing store to a bind, and returns
// the error rather than failing: half of these checks are about refusals.
func secured(t *testing.T, addr string, advertise []string) (*Server, *pairing.Store, identity.Identity, error) {
	t.Helper()
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
	s, err := NewWithOptions(svc, addr, Options{Pairing: store, ID: &id, Advertise: advertise})
	return s, store, id, err
}

// The bug this pins: a default --pair bound 127.0.0.1 while the offer handed the
// phone this machine's LAN address, which nothing was listening on. The offered
// list is derived from the bind, so a loopback bind offers loopback and nothing
// else - even on a machine that has a LAN address to detect.
func TestALoopbackBindOffersOnlyLoopback(t *testing.T) {
	stubAddresses(t, []net.Addr{v4("192.168.1.50"), v4("169.254.9.9")}, nil)
	s, _, _, err := secured(t, "127.0.0.1:38411", nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	if got := strings.Join(s.Advertised(), ","); got != "127.0.0.1" {
		t.Fatalf("advertised = %q, want only the loopback address the bind serves", got)
	}
}

// A bind to one network address serves that address and the loopback listener the
// administrative routes need, so those are the two the offer may name.
func TestANetworkBindOffersItselfAndLoopback(t *testing.T) {
	stubAddresses(t, []net.Addr{v4("192.168.1.50"), v4("10.20.30.40")}, nil)
	s, _, _, err := secured(t, "10.20.30.40:38411", nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	if got := strings.Join(s.Advertised(), ","); got != "10.20.30.40,127.0.0.1" {
		t.Fatalf("advertised = %q, want the bound address and loopback", got)
	}
}

// A wildcard bind really does answer on every address the machine holds, so the
// offer names those - and a link-local address included by mistake would be
// something a phone on the Wi-Fi cannot dial, so it is left out.
func TestAWildcardBindOffersTheMachinesOwnAddresses(t *testing.T) {
	stubAddresses(t, []net.Addr{
		&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)},
		v4("192.168.1.50"),
		v4("169.254.9.9"),
		v4("10.20.30.40"),
	}, nil)
	s, _, _, err := secured(t, "0.0.0.0:38411", nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	if got := strings.Join(s.Advertised(), ","); got != "127.0.0.1,192.168.1.50,10.20.30.40" {
		t.Fatalf("advertised = %q, want loopback and this machine's unicast addresses", got)
	}
}

// An explicit list is checked against the bind rather than trusted, in both
// directions: an address the listener does not have is refused, and one it does
// have is passed through unchanged.
func TestAnAdvertisedAddressMustBeOneTheBindServes(t *testing.T) {
	stubAddresses(t, []net.Addr{v4("192.168.1.50")}, nil)

	cases := []struct {
		addr       string
		advertise  []string
		wantRefuse bool
		mentions   string
		want       string
	}{
		{"127.0.0.1:38411", []string{"192.168.1.50"}, true, "192.168.1.50", ""},
		{"10.20.30.40:38411", []string{"198.51.100.7"}, true, "198.51.100.7", ""},
		{"10.20.30.40:38411", []string{"10.20.30.40"}, false, "", "10.20.30.40"},
		{"10.20.30.40:38411", []string{"127.0.0.1"}, false, "", "127.0.0.1"},
		// The loopback spellings mean the same listener; refusing an offer because
		// someone spelled it differently would be checking a formality.
		{"localhost:38411", []string{"127.0.0.1"}, false, "", "127.0.0.1"},
		// A wildcard bind serves the machine's addresses only, so an address from
		// somewhere else is still unreachable and still refused.
		{"0.0.0.0:38411", []string{"198.51.100.7"}, true, "198.51.100.7", ""},
		{"0.0.0.0:38411", []string{"192.168.1.50"}, false, "", "192.168.1.50"},
	}
	for _, tc := range cases {
		s, _, _, err := secured(t, tc.addr, tc.advertise)
		if tc.wantRefuse {
			if err == nil {
				t.Errorf("%s advertise %v: accepted, want a refusal naming %q", tc.addr, tc.advertise, tc.mentions)
				continue
			}
			if !strings.Contains(err.Error(), tc.mentions) {
				t.Errorf("%s advertise %v: refusal should name %q: %v", tc.addr, tc.advertise, tc.mentions, err)
			}
			if !strings.Contains(err.Error(), "--host") {
				t.Errorf("%s advertise %v: the refusal should say what to change: %v", tc.addr, tc.advertise, err)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s advertise %v: %v, want it accepted", tc.addr, tc.advertise, err)
			continue
		}
		if got := strings.Join(s.Advertised(), ","); got != tc.want {
			t.Errorf("%s advertise %v: advertised = %q, want %q", tc.addr, tc.advertise, got, tc.want)
		}
	}
}

// When the addresses cannot be listed, a wildcard bind cannot promise a phone can
// reach it, so it says so at startup instead of issuing an offer a phone will fail
// on later.
func TestAWildcardBindWithoutAddressInfoRefuses(t *testing.T) {
	stubAddresses(t, nil, errors.New("probe is not available on this machine"))
	if _, _, _, err := secured(t, "0.0.0.0:38411", nil); err == nil {
		t.Fatal("a wildcard bind that cannot list this machine's address was accepted")
	} else if !strings.Contains(err.Error(), "listing this machine") {
		t.Errorf("refusal should say why: %v", err)
	}
}

// A bind asked for with port 0 gets whatever the operating system handed out, and
// the pairing offer has to carry that number or the phone dials a closed port.
func TestListenAllAdoptsThePortTheSocketGot(t *testing.T) {
	stubAddresses(t, []net.Addr{v4("192.168.1.50")}, nil)
	s, _, _, err := secured(t, "127.0.0.1:0", nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	listeners, err := s.ListenAll("127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenAll: %v", err)
	}
	defer func() {
		for _, ln := range listeners {
			_ = ln.Close()
		}
	}()
	if len(listeners) != 1 {
		t.Fatalf("listeners = %d, want the one loopback bind", len(listeners))
	}
	if s.Port() == 0 || s.Port() != portOf(listeners[0].Addr()) {
		t.Fatalf("Port() = %d, want the bound port %d", s.Port(), portOf(listeners[0].Addr()))
	}

	// The payoff is in what a phone reads back, not in the field alone.
	rec := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/v1/admin/pair", strings.NewReader("{}"))
	request.RemoteAddr = "127.0.0.1:5000"
	s.Handler().ServeHTTP(rec, request)
	if rec.Code != http.StatusOK {
		t.Fatalf("admin pair = %d %s", rec.Code, rec.Body.String())
	}
	var offer struct {
		Payload string `json:"payload"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &offer); err != nil {
		t.Fatalf("decoding the offer: %v (%s)", err, rec.Body)
	}
	parsed, err := pairing.ParsePayload(offer.Payload)
	if err != nil {
		t.Fatalf("the Bridge printed an offer a phone cannot parse: %v", err)
	}
	if parsed.Port != s.Port() {
		t.Errorf("the offer carries port %d, want the listener's %d", parsed.Port, s.Port())
	}
}

// A network bind without a loopback listener would serve the phone and lock the
// operator out: the administrative routes answer only a loopback peer, so the
// second listener is what keeps --add-device and revocation reachable.
func TestListenAllAddsALoopbackListenerForANetworkBind(t *testing.T) {
	lan := lanAddress(t)
	if lan == "" {
		t.Skip("no non-loopback unicast address on this machine")
	}
	stubAddresses(t, []net.Addr{v4(lan)}, nil)
	s, _, _, err := secured(t, net.JoinHostPort(lan, "0"), nil)
	if err != nil {
		t.Fatalf("NewWithOptions: %v", err)
	}
	listeners, err := s.ListenAll(net.JoinHostPort(lan, "0"))
	if err != nil {
		t.Fatalf("ListenAll: %v", err)
	}
	defer func() {
		for _, ln := range listeners {
			_ = ln.Close()
		}
	}()
	if len(listeners) != 2 {
		t.Fatalf("listeners = %d, want the network bind and a loopback one", len(listeners))
	}
	if !strings.HasPrefix(listeners[1].Addr().String(), "127.0.0.1:") {
		t.Errorf("second listener = %v, want loopback", listeners[1].Addr())
	}
	if portOf(listeners[0].Addr()) != s.Port() || portOf(listeners[1].Addr()) != s.Port() {
		t.Errorf("both listeners must share the port the offer carries: %v %v and %d",
			listeners[0].Addr(), listeners[1].Addr(), s.Port())
	}
	if got := strings.Join(s.Advertised(), ","); got != lan+",127.0.0.1" {
		t.Errorf("advertised = %q, want %s and loopback", got, lan)
	}
}
