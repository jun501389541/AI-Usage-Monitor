// Command aiusage-bridge reads the local Codex account's quota through the Codex
// App Server and serves it over an HTTP API.
//
// Two modes, and the difference between them is a security boundary rather than a
// convenience:
//
//	default      loopback only, no device tokens - what Phase 5 shipped, and what
//	             an emulator on this machine still uses (10.0.2.2 is host loopback)
//	--pair       TLS with the Bridge's own certificate, every /v1/accounts request
//	             behind a device token, and a pairing offer a phone can redeem.
//	             A non-loopback bind is accepted only in this mode.
//
// --add-device is the operator's half of pairing: it asks a running Bridge for one
// introduction over loopback and prints what to give the phone. It is a client
// rather than a second writer of the pairing file, because two processes holding
// the same registry in memory would each overwrite the other's device list.
//
// The addresses a phone is told about come from the bind rather than from a guess
// at this machine's LAN address: an offer naming an address nothing listens on is
// something a phone can only fail on, and the operator has already left the room.
package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"aiusage.local/bridge/internal/bridge"
	"aiusage.local/bridge/internal/identity"
	"aiusage.local/bridge/internal/pairing"
	"aiusage.local/bridge/internal/server"
)

const (
	defaultPort     = 38411
	defaultTimeout  = 8 * time.Second
	defaultTTL      = 5 * time.Minute
	defaultPairTTL  = 120 * time.Second
	adminDialTimout = 5 * time.Second
)

func main() {
	port := flag.Int("port", defaultPort, "TCP port to bind")
	host := flag.String("host", "127.0.0.1", "listen host; a non-loopback address requires --pair")
	codexPath := flag.String("codex", "", "path to the codex executable; searched for when empty")
	dataDir := flag.String("data-dir", defaultDataDir(), "directory holding state.json, pairing.json and the identity key")
	timeout := flag.Duration("timeout", defaultTimeout, "how long to wait for the app server before giving up")
	ttl := flag.Duration("ttl", defaultTTL, "serve from cache while the reading is younger than this")
	pair := flag.Bool("pair", false, "require a paired device token and serve TLS; needed to bind a network address")
	pairTTL := flag.Duration("pair-ttl", defaultPairTTL, "how long a pairing offer stays exchangeable")
	advertise := flag.String("advertise", "", "comma-separated addresses to put in a pairing offer; defaults to the addresses this bind actually serves")
	addDevice := flag.Bool("add-device", false, "ask a running Bridge for one pairing offer, print it, and exit")
	flag.Parse()

	if *addDevice {
		// The CLI talks to the server that owns the registry; it does not open
		// pairing.json itself.
		if err := runAddDevice(*dataDir, *port); err != nil {
			fmt.Fprintln(os.Stderr, "add-device:", err)
			os.Exit(1)
		}
		return
	}

	addr := net.JoinHostPort(*host, strconv.Itoa(*port))
	svc := bridge.NewService(
		&bridge.CodexFetcher{ExplicitPath: *codexPath, Timeout: *timeout},
		bridge.NewStore(filepath.Join(*dataDir, "state.json")),
		*ttl,
	)

	options := server.Options{}
	scheme := "http"
	if *pair {
		id, err := identity.Load(*dataDir)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		registry, err := pairing.NewStore(filepath.Join(*dataDir, "pairing.json"))
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		options = server.Options{Pairing: registry, ID: &id, PairTTL: *pairTTL, Advertise: splitList(*advertise)}
		scheme = "https"
	}

	s, err := server.NewWithOptions(svc, addr, options)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	// A paired Bridge bound to a network address also listens on loopback, because
	// that is the only place the administrative routes answer. Print the listeners
	// that exist rather than the address that was asked for.
	listeners, err := s.ListenAll(addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	for _, ln := range listeners {
		fmt.Printf("AI Usage Bridge listening on %s://%s (state: %s)\n", scheme, ln.Addr(), filepath.Join(*dataDir, "state.json"))
	}
	if *pair {
		fmt.Printf("  mode: paired (TLS, device tokens required)\n  bridge id: %s\n  fingerprint: %s\n",
			options.ID.BridgeID, options.ID.Fingerprint)
		fmt.Printf("  addresses offered to phones: %s\n", strings.Join(s.Advertised(), ", "))
		// The line has to run against *this* instance: --data-dir selects the
		// identity whose fingerprint the offer is pinned with, and the port is the
		// one the socket got. Both paths are quoted because a directory name with a
		// space in it is pasted into a shell, and Go's own quoting would have
		// escaped every backslash in a Windows path.
		fmt.Printf("  to pair a phone: %s --port %d --data-dir %s --add-device\n",
			quoteArgument(os.Args[0]), s.Port(), quoteArgument(*dataDir))
	} else {
		fmt.Printf("  mode: loopback debug (no pairing); add --pair to serve a phone over the network\n")
	}

	serveErr := make(chan error, len(listeners))
	for _, ln := range listeners {
		go func(ln net.Listener) { serveErr <- s.Serve(ln) }(ln)
	}
	if err := <-serveErr; err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// addDevice redeems nothing: it asks the running Bridge for an offer and prints it.
// It pins the certificate fingerprint from the identity file rather than trusting
// any TLS handshake, which is the same rule the phone will follow - a local tool
// that skips verification is how a verification scheme quietly becomes optional.
//
// It always dials loopback. The Bridge listens there whatever --host it was started
// with, because the administrative routes refuse any other peer, so an operator who
// had bound a network address would otherwise have been told to connect to an
// address that answers 404 at best (docs/PHASE-7-REVIEW.md P2).
func runAddDevice(dataDir string, port int) error {
	id, err := identity.OpenExisting(dataDir)
	if err != nil {
		return err
	}
	url := fmt.Sprintf("https://%s", net.JoinHostPort("127.0.0.1", strconv.Itoa(port))) + "/v1/admin/pair"

	client := &http.Client{
		Timeout: adminDialTimout,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{
			// Verification is by digest: a self-signed certificate has no chain to
			// build and, for a service addressed by number, no name to match.
			InsecureSkipVerify:    true,
			VerifyPeerCertificate: pinnedVerifier(id.Fingerprint),
		}},
	}
	request, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte("{}")))
	if err != nil {
		return err
	}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("asking the Bridge at %s: %w (is it running with --pair?)", url, err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<16))
	if err != nil {
		return err
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("the Bridge answered %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	var offer struct {
		Code      string `json:"code"`
		ExpiresAt string `json:"expiresAt"`
		Payload   string `json:"payload"`
	}
	if err := json.Unmarshal(body, &offer); err != nil {
		return err
	}
	// Read it back through the same parser a phone will use: printing something the
	// receiving end cannot parse is the kind of bug that survives a one-sided test.
	parsed, err := pairing.ParsePayload(offer.Payload)
	if err != nil {
		return err
	}
	fmt.Printf("Pair the phone with the short code, or paste the whole line below.\n\n")
	fmt.Printf("  code:      %s\n", offer.Code)
	fmt.Printf("  expires:   %s\n", offer.ExpiresAt)
	fmt.Printf("  addresses: %s\n", strings.Join(parsed.Hosts, ", "))
	fmt.Printf("  offer:     %s\n", offer.Payload)
	return nil
}

func sum(spki []byte) []byte {
	digest := sha256.Sum256(spki)
	return digest[:]
}

func pinnedVerifier(fingerprint string) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) != 1 {
			return errors.New("expected exactly one certificate from the Bridge")
		}
		cert, err := x509.ParseCertificate(rawCerts[0])
		if err != nil {
			return err
		}
		spki, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
		if err != nil {
			return err
		}
		if hex.EncodeToString(sum(spki)) != fingerprint {
			return errors.New("the Bridge's certificate does not match the pinned fingerprint")
		}
		return nil
	}
}

// quoteArgument wraps a path for the shell the operator is typing into. It doubles
// an embedded quote rather than backslash-escaping it, because Windows paths are
// made of backslashes and Go's %q would print something that pastes wrong.
func quoteArgument(value string) string {
	return `"` + strings.ReplaceAll(value, `"`, `""`) + `"`
}

// splitList turns a --advertise value into addresses. An empty result is not an
// error here: the server derives the list from the bind in that case, and refuses
// an entry the bind does not serve.
func splitList(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// defaultDataDir keeps the state file out of the repository and out of the program
// directory, which on Windows is often not writable.
func defaultDataDir() string {
	if local := os.Getenv("LOCALAPPDATA"); local != "" {
		return filepath.Join(local, "AIUsageBridge")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "."
	}
	return filepath.Join(home, ".aiusage-bridge")
}
