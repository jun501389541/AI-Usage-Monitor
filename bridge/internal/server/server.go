// Package server exposes the Bridge over HTTP, on the loopback interface only.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"aiusage.local/bridge/internal/bridge"
	"aiusage.local/bridge/internal/identity"
	"aiusage.local/bridge/internal/pairing"
	"aiusage.local/bridge/internal/redact"
)

// ProviderID is the single provider Phase 5 serves. Codex is the only thing the
// Bridge can read today; adding a second one is a later phase, not a flag.
const ProviderID = "codex"

// ClassUnpaired is the one answer every rejected credential gets. Its name is
// deliberately CODEX_AUTH_REQUIRED: that is the class the Android client already
// maps onto "电脑端授权已失效，请重新配对", so a revoked or unknown device is told to
// re-pair rather than being reported as an offline computer (Spec §53 rule 19).
const ClassUnpaired = "CODEX_AUTH_REQUIRED"

// ErrNotLoopback means someone asked the Bridge to listen somewhere other than
// this machine's own loopback address.
var ErrNotLoopback = errors.New("the Bridge refuses to bind a non-loopback address")

// allowedHosts are the addresses that mean "this machine only". A LAN address is
// deliberately absent: binding one would hand account quota numbers, and later a
// pairing endpoint, to everyone on the network.
var allowedHosts = map[string]bool{
	"127.0.0.1": true,
	"localhost": true,
	"::1":       true,
}

// Options turns the Bridge from a loopback convenience into a network service.
// The zero value is exactly what Phase 5 shipped: no pairing, no token, and
// therefore no non-loopback bind.
type Options struct {
	// Pairing is the device registry. Setting it alone is not enough to serve a
	// network address: without a certificate the traffic, and the tokens inside
	// it, are readable by anyone on the segment.
	Pairing *pairing.Store
	// ID is the Bridge's identity: its name, its certificate, and the fingerprint
	// a phone pins.
	ID *identity.Identity
	// PairTTL bounds how long an issued introduction stays exchangeable; zero
	// means the pairing package's default.
	PairTTL time.Duration
	// Advertise is the address list put into a pairing offer. Empty means derive it
	// from the bind, which is what makes an offer a phone can actually dial; a
	// non-empty list is the operator's own choice and is still checked against the
	// bind rather than trusted.
	Advertise []string
}

// Secured reports whether both halves of network service are present. Anything
// less binds loopback only.
func (o Options) Secured() bool { return o.Pairing != nil && o.ID != nil }

// missing names what an operator has to add, because "refused" without a reason
// sends them to read source code.
func (o Options) missing() string {
	switch {
	case o.Pairing == nil && o.ID == nil:
		return "pairing (--pair) and TLS identity"
	case o.Pairing == nil:
		return "pairing (--pair)"
	default:
		return "TLS identity"
	}
}

// Server serves the /v1 API from a bridge.Service.
type Server struct {
	svc  *bridge.Service
	srv  *http.Server
	opts Options
	// port is the numeric part of the bind address, needed to build a pairing
	// offer a phone can dial. It is re-read once the socket exists, because a
	// request for port 0 gets whatever the operating system handed out.
	port int
	// alsoLoopback says a second listener on 127.0.0.1 is needed: the
	// administrative routes answer only a loopback peer, so a Bridge bound to one
	// network address would otherwise have no way left to mint or revoke anything
	// from its own machine (docs/PHASE-7-REVIEW.md P2).
	alsoLoopback bool
}

// New builds a loopback-only server: today's behaviour, and the constructor every
// Phase 5 test drives.
func New(svc *bridge.Service, addr string) (*Server, error) {
	return NewWithOptions(svc, addr, Options{})
}

// NewWithOptions builds the server for an address. The host must be loopback, or
// the options must supply both a device registry and a certificate: the guard is
// not a formality, because an unauthenticated /v1 on a LAN address publishes
// account quota to everyone on the segment (docs/PHASE-7-PLAN.md A6, and
// docs/PHASE-6-PLAN.md R4 which it discharges).
func NewWithOptions(svc *bridge.Service, addr string, opts Options) (*Server, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("bad address %q: %w", addr, err)
	}
	if !allowedHosts[host] && !opts.Secured() {
		return nil, fmt.Errorf("%w: %q, and %s are missing; a non-loopback bind also needs --pair",
			ErrNotLoopback, host, opts.missing())
	}
	if port == "" {
		return nil, fmt.Errorf("bad address %q: a port is required", addr)
	}
	number, err := strconv.Atoi(port)
	if err != nil {
		return nil, fmt.Errorf("bad address %q: the port must be a number: %w", addr, err)
	}
	// In paired mode the loopback address is always served, whatever else was
	// asked for: the administrative routes refuse a non-loopback peer, so a
	// Bridge bound only to a LAN address would have no way left to mint or revoke
	// anything from its own machine (docs/PHASE-7-REVIEW.md P2).
	alsoLoopback := false
	if opts.Secured() {
		served, err := servedAddresses(host)
		if err != nil {
			return nil, err
		}
		if len(opts.Advertise) == 0 {
			// Failing here beats offering an address a phone cannot dial, which is
			// what a silently empty list would have produced.
			opts.Advertise = served
		}
		if err := checkAdvertised(opts.Advertise, served, host); err != nil {
			return nil, err
		}
		alsoLoopback = !isWildcardHost(host) && !allowedHosts[host]
	}
	if opts.PairTTL <= 0 {
		opts.PairTTL = pairing.DefaultTTL
	}
	s := &Server{
		svc:          svc,
		srv:          &http.Server{ReadHeaderTimeout: 5 * time.Second},
		opts:         opts,
		port:         number,
		alsoLoopback: alsoLoopback,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", s.handleHealth)
	mux.HandleFunc("/v1/providers", s.handleProviders)
	mux.HandleFunc("/v1/accounts/", s.handleUsage)
	if opts.Secured() {
		mux.HandleFunc("/v1/pair", s.handlePair)
		mux.HandleFunc("/v1/admin/pair", s.requireLoopback(s.handleAdminPair))
		mux.HandleFunc("/v1/admin/devices", s.requireLoopback(s.handleAdminDevices))
		mux.HandleFunc("/v1/admin/devices/revoke", s.requireLoopback(s.handleAdminRevoke))
		// The certificate is the identity's, so serving plain HTTP with these
		// options configured is not a thing: the pairing state and the tokens
		// would be walking around unencrypted.
		s.srv.TLSConfig = opts.ID.TLSConfig()
	}
	s.srv.Handler = mux
	return s, nil
}

// TLS reports whether this server serves encrypted traffic.
func (s *Server) TLS() bool { return s.srv.TLSConfig != nil }

// Port is the port the server serves on, once a listener exists: a request for
// port 0 is answered with whatever the operating system handed out, and the
// operator's --add-device line has to carry that number.
func (s *Server) Port() int { return s.port }

// interfaceAddrs is a variable so a test can describe a machine holding addresses
// the one running the test does not have.
var interfaceAddrs = net.InterfaceAddrs

// isWildcardHost reports whether a bind to host covers every address this machine
// has rather than one named address.
func isWildcardHost(host string) bool {
	return host == "" || host == "*" || host == "0.0.0.0" || host == "::"
}

// servedAddresses lists the addresses a bind to host answers on, in the shape a
// pairing offer needs. A specific address is served on its own (plus loopback,
// which ListenAll binds because the administrative routes need it); a wildcard is
// every address this machine holds.
//
// Deriving the offer from the bind is the fix for the case where a phone was
// handed an address the listener never had: auto-detecting a LAN address while
// binding loopback produced exactly such an offer (docs/PHASE-7-REVIEW.md P2).
func servedAddresses(host string) ([]string, error) {
	if isWildcardHost(host) {
		return machineAddresses()
	}
	if allowedHosts[host] {
		return []string{host}, nil
	}
	return []string{host, "127.0.0.1"}, nil
}

// machineAddresses is the honest answer to "what may a phone dial to reach this
// machine": its own unicast addresses, plus loopback for a client on the machine
// itself (an emulator reaches host loopback through 10.0.2.2). Link-local
// addresses are left out because a phone on the Wi-Fi cannot dial one.
func machineAddresses() ([]string, error) {
	addrs, err := interfaceAddrs()
	if err != nil {
		return nil, fmt.Errorf("listing this machine's addresses, which a wildcard bind would offer to a phone: %w", err)
	}
	out := []string{"127.0.0.1"}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip := ipnet.IP
		if ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		if text := ip.String(); !containsAddress(out, text) {
			out = append(out, text)
		}
	}
	return out, nil
}

func containsAddress(list []string, want string) bool {
	for _, have := range list {
		if have == want {
			return true
		}
	}
	return false
}

// checkAdvertised refuses an offer the listener cannot honour. The operator's own
// --advertise list is checked against the bind rather than trusted, because a
// pairing code naming an unreachable address is something a phone can only fail
// on, several seconds after the operator watched the Bridge say it was ready.
func checkAdvertised(advertise, served []string, host string) error {
	for _, want := range advertise {
		found := false
		for _, have := range served {
			if sameAddress(want, have) {
				found = true
				break
			}
		}
		if !found {
			return fmt.Errorf("the pairing offer would name %q, which a bind to %q does not serve; bind it (--host %s) or advertise only %v",
				want, host, want, served)
		}
	}
	return nil
}

// An IPv4 loopback listener does not also serve IPv6 loopback. Only advertise
// the address actually served; localhost resolution is not a listener contract.
func sameAddress(a, b string) bool {
	return a == b
}

// Advertised is the address list a pairing offer carries, after it has been
// checked against the bind. The operator is shown exactly this at startup, so the
// printed words and the payload a phone reads cannot drift apart.
func (s *Server) Advertised() []string { return s.opts.Advertise }

// ListenAll binds every address this server needs: the requested one, and
// loopback as well when pairing is on and the requested one is elsewhere.
func (s *Server) ListenAll(addr string) ([]net.Listener, error) {
	ln, err := s.Listen(addr)
	if err != nil {
		return nil, err
	}
	// A pairing offer carries one port, so it has to be the port the socket
	// actually got: an operator who asked for port 0 would otherwise hand the phone
	// a number nobody listens on.
	s.port = portOf(ln.Addr())
	listeners := []net.Listener{ln}
	if !s.alsoLoopback {
		return listeners, nil
	}
	extra, err := s.Listen(net.JoinHostPort("127.0.0.1", strconv.Itoa(s.port)))
	if err != nil {
		ln.Close()
		return nil, fmt.Errorf("also listening on loopback, which is where the administrative routes answer: %w", err)
	}
	return append(listeners, extra), nil
}

func portOf(addr net.Addr) int {
	_, port, err := net.SplitHostPort(addr.String())
	if err != nil {
		return 0
	}
	number, err := strconv.Atoi(port)
	if err != nil {
		return 0
	}
	return number
}

// Handler exposes the routes so tests can drive them without binding a socket.
func (s *Server) Handler() http.Handler { return s.srv.Handler }

// Listen binds the socket. Split from Serve so a caller can learn the port when
// it asked for a random one.
func (s *Server) Listen(addr string) (net.Listener, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("listening on %s: %w", addr, err)
	}
	return ln, nil
}

// Serve runs until Shutdown. With pairing configured it serves TLS: the device
// tokens exist to be sent over a network, and sending them in cleartext would
// make the whole scheme decorative.
func (s *Server) Serve(ln net.Listener) error {
	var err error
	if s.srv.TLSConfig != nil {
		// The certificate and key are already loaded in memory by the identity
		// package, so ServeTLS gets no file names.
		err = s.srv.ServeTLS(ln, "", "")
	} else {
		err = s.srv.Serve(ln)
	}
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown stops the server, waiting for in-flight requests.
func (s *Server) Shutdown(ctx context.Context) error { return s.srv.Shutdown(ctx) }

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	// A failure here means the client hung up; there is nothing left to report.
	_ = json.NewEncoder(w).Encode(body)
}

func methodNotAllowed(w http.ResponseWriter) {
	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only GET is served"})
}

type providerEntry struct {
	ID                  string `json:"id"`
	Name                string `json:"name"`
	AuthType            string `json:"authType"`
	ReportsQuotaWindows bool   `json:"reportsQuotaWindows"`
	ReportsBalance      bool   `json:"reportsBalance"`
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	if r.URL.Path != "/v1/health" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown path"})
		return
	}
	// Health never spawns the app server: it reports what the last real contact
	// taught us. A liveness probe that starts a child per request would make the
	// cache useless and the polling expensive.
	state, err := s.svc.Latest()
	info := map[string]any{
		"ok":           true,
		"provider":     ProviderID,
		"capabilities": map[string]bool{"quotaWindows": true, "balance": false},
	}
	if s.opts.Secured() {
		// The ID and the fingerprint are not secrets: the fingerprint is what a
		// phone compares against the certificate, and it reaches the phone through
		// the pairing payload anyway. Advertising them here is what lets an
		// operator confirm the Bridge kept the same identity after a restart, which
		// is the promise docs/PHASE-7-PLAN.md A2 makes.
		info["bridgeId"] = s.opts.ID.BridgeID
		info["fingerprint"] = s.opts.ID.Fingerprint
		info["tls"] = s.TLS()
		info["pairedDevices"] = s.opts.Pairing.Active()
		info["outstandingPairings"] = s.opts.Pairing.Outstanding()
	}
	if err != nil {
		info["stateReadable"] = false
	} else {
		info["stateReadable"] = true
		if state.CodexVersion != "" {
			info["codexVersion"] = state.CodexVersion
		}
		if state.LastFailure != nil {
			info["lastFailureClass"] = state.LastFailure.Class
		}
	}
	writeJSON(w, http.StatusOK, info)
}

// handleProviders mirrors what an Android CodexProvider will report: no balance,
// quota windows instead, and bridge-token auth. Keeping those two descriptions
// in one place is the point - the phone reads exactly this shape.
func (s *Server) handleProviders(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	if r.URL.Path != "/v1/providers" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown path"})
		return
	}
	writeJSON(w, http.StatusOK, []providerEntry{{
		ID:                  ProviderID,
		Name:                "OpenAI Codex",
		AuthType:            "bridge",
		ReportsQuotaWindows: true,
		ReportsBalance:      false,
	}})
}

// accountID extracts the {id} from /v1/accounts/{id}/usage.
func accountID(path string) (string, string) {
	const prefix = "/v1/accounts/"
	if !strings.HasPrefix(path, prefix) {
		return "", ""
	}
	rest := strings.TrimPrefix(path, prefix)
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 {
		return rest, ""
	}
	return parts[0], parts[1]
}

func (s *Server) handleUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w)
		return
	}
	id, tail := accountID(r.URL.Path)
	if id == "" || tail != "usage" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "expected /v1/accounts/{id}/usage"})
		return
	}
	if id != ProviderID {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "the Bridge serves " + ProviderID + " only"})
		return
	}

	if s.opts.Secured() && !s.authorize(w, r) {
		return
	}
	force := r.URL.Query().Get("refresh") == "1"
	view, err := s.svc.Usage(force)
	if err != nil {
		// 503, not 500: the Bridge is up, Codex is what is unavailable. The
		// class travels in the body so the caller can tell offline from
		// authorisation expiry (Spec §53 rule 19).
		//
		// The class is read from the structured failure, not from the view: on a
		// cold failure there is no view to read it from, and falling back to
		// CODEX_UNKNOWN there threw away the one fact the caller branches on
		// (docs/PHASE-5-REVIEW.md §2).
		class, detail := bridge.ClassUnknown, err.Error()
		var failure bridge.FailureError
		switch {
		case errors.As(err, &failure):
			class, detail = failure.Class, failure.Message
		case view.Degraded != nil:
			class = view.Degraded.Class
		}
		// Every message that leaves the process goes through the mask, including
		// the errors that never became a Failure at all - an unreadable state
		// file, for instance. One outlet is one place to remember; two outlets is
		// where a raw upstream string got out (§1).
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error":  class,
			"detail": redact.Text(detail),
		})
		return
	}
	writeJSON(w, http.StatusOK, view)
}

// authorize admits only a request carrying a live device token. Every rejection
// answers the same way, because the distinction between "unknown", "expired" and
// "used" is exactly what an outsider guessing at codes is trying to learn; the
// pairing store keeps that detail for the operator's own diagnosis.
func (s *Server) authorize(w http.ResponseWriter, r *http.Request) bool {
	token := bearer(r)
	if token == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": ClassUnpaired})
		return false
	}
	device, err := s.opts.Pairing.Validate(token)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": ClassUnpaired})
		return false
	}
	// Last-seen is bookkeeping for the device list; a failure to write it must not
	// deny a reading the device is entitled to.
	if err := s.opts.Pairing.Touch(device.ID); err != nil {
		log.Printf("bridge: recording a visit from device %s failed: %v", device.ID, redact.Text(err.Error()))
	}
	return true
}

// handlePair trades a one-time introduction for a device token. The response is
// the only place the token appears in plaintext, and the request body is capped:
// the exchange takes a fixed-shape secret, so a megabyte of it is an attack on the
// Bridge's memory rather than a client.
func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only POST is served here"})
		return
	}
	var request struct {
		PairToken  string `json:"pairToken"`
		DeviceName string `json:"deviceName"`
	}
	body := http.MaxBytesReader(w, r.Body, 4096)
	if err := json.NewDecoder(body).Decode(&request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "expected {pairToken, deviceName}"})
		return
	}
	device, token, err := s.opts.Pairing.Exchange(request.PairToken, request.DeviceName)
	if err != nil {
		if errors.Is(err, pairing.ErrNotPersisted) {
			// The code was right and the device could not be recorded. A 401 here
			// would tell the phone its own code was wrong while the code is still
			// live on disk, inviting a retry that fails the same way.
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "the pairing could not be stored"})
			return
		}
		// One answer for every other reason, and that reason never echoes the
		// secret that was presented.
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": ClassUnpaired})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"deviceId":    device.ID,
		"deviceToken": token,
		"bridgeId":    s.opts.ID.BridgeID,
	})
}

// bearer pulls the device token out of the Authorization header. It is the only
// place a token is read, and the URL is deliberately not one: request lines end up
// in proxies, logs and crash reports (docs/PHASE-6-PLAN.md A4).
func bearer(r *http.Request) string {
	const prefix = "Bearer "
	value := strings.TrimSpace(r.Header.Get("Authorization"))
	if value == "" {
		return ""
	}
	if len(value) > len(prefix) && strings.EqualFold(value[:len(prefix)], prefix) {
		return strings.TrimSpace(value[len(prefix):])
	}
	// A token presented without the scheme is still the caller's only credential;
	// accepting it keeps a client with a header-shaping bug from being read as an
	// unpaired device. It is normalised, never logged.
	return value
}
