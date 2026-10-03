// Package identity gives the Bridge a name and a key that survive restarts.
//
// Pairing (Spec §21) promises that a phone does not have to pair again when the
// Bridge restarts, so the identity must be persisted, stable, and impossible to
// regenerate silently: if the key were minted again on a half-written file, every
// paired device's pinned fingerprint would stop matching and the failure would
// look like a network problem on the phone.
//
// Only the standard library is used. Spec L22 forbids third-party dependencies,
// and crypto/x509 plus crypto/rsa cover what pairing needs: a self-signed
// certificate whose SPKI digest is the fingerprint the phone pins.
package identity

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base32"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"aiusage.local/bridge/internal/filestore"
)

// Names of the two files inside --data-dir. Keeping them here means a partial
// write can be recognised by what is missing.
const (
	KeyFile  = "identity.key.pem"
	CertFile = "identity.crt.pem"

	// certValidity is long on purpose. Rotating the key changes the fingerprint,
	// and every paired device pins the fingerprint - so rotation means everyone
	// re-pairs. Spec §21 lists the occasions for re-pairing, and "the Bridge
	// reached the end of a calendar" is not one of them.
	certValidity = 10 * 365 * 24 * time.Hour
)

// ErrNotWritable says the directory could not hold the identity, in a form the
// caller can print without quoting a secret.
var ErrNotWritable = errors.New("the Bridge identity directory is not usable")

// ErrNoIdentity says this directory holds no Bridge identity at all.
var ErrNoIdentity = errors.New("no Bridge identity in this directory")

// Identity is a loaded Bridge identity: its ID, its certificate, and the digest
// a phone pins.
type Identity struct {
	BridgeID    string
	Fingerprint string // hex SHA-256 of the certificate's SubjectPublicKeyInfo

	cert *x509.Certificate
	key  *rsa.PrivateKey
}

// OpenExisting reads an identity that must already be on disk. It exists for
// --add-device: a client that minted a key of its own would then pin a fingerprint
// the running Bridge never had, and the operator would read a certificate error
// where the real mistake was a missing --data-dir (docs/PHASE-7-REVIEW.md P2).
func OpenExisting(dir string) (Identity, error) {
	id, err := read(dir)
	if errors.Is(err, ErrNoIdentity) {
		return Identity{}, fmt.Errorf("%w: %s. Pass the --data-dir the running Bridge was started with; its startup line prints it", ErrNoIdentity, dir)
	}
	return id, err
}

// Load reads the identity from dir, creating it on first run. A directory that
// holds one of the two files, a file that cannot be parsed, or a key that does
// not match its certificate all produce an error rather than a fresh identity.
func Load(dir string) (Identity, error) {
	id, err := read(dir)
	if errors.Is(err, ErrNoIdentity) {
		return generate(dir)
	}
	if err != nil {
		return Identity{}, err
	}
	return id, nil
}

// read is Load without the first-run branch: either both files are there and
// consistent, or something is wrong with this directory.
func read(dir string) (Identity, error) {
	if strings.TrimSpace(dir) == "" {
		return Identity{}, fmt.Errorf("%w: no directory given", ErrNotWritable)
	}
	keyPath := filepath.Join(dir, KeyFile)
	certPath := filepath.Join(dir, CertFile)

	keyBytes, keyErr := os.ReadFile(keyPath)
	certBytes, certErr := os.ReadFile(certPath)
	switch {
	case errors.Is(keyErr, os.ErrNotExist) && errors.Is(certErr, os.ErrNotExist):
		return Identity{}, ErrNoIdentity
	case keyErr != nil:
		// A present-but-unreadable key must not be replaced silently: the
		// certificate next to it would then pin a fingerprint nobody can serve.
		return Identity{}, fmt.Errorf("reading %s: %w", keyPath, keyErr)
	case certErr != nil:
		return Identity{}, fmt.Errorf("certificate %s is missing while the key exists; refusing to mint a new identity that would break every paired device: %w", certPath, certErr)
	}

	key, err := parseKey(keyBytes)
	if err != nil {
		return Identity{}, err
	}
	cert, err := parseCert(certBytes)
	if err != nil {
		return Identity{}, err
	}
	// The pair is checked rather than trusted: a key swapped under a leftover
	// certificate would serve a chain whose fingerprint differs from what the
	// Bridge advertises.
	certSPKI, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
	if err != nil {
		return Identity{}, fmt.Errorf("reading the certificate's public key: %w", err)
	}
	keyDER, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return Identity{}, fmt.Errorf("encoding the private key's public half: %w", err)
	}
	if string(certSPKI) != string(keyDER) {
		return Identity{}, fmt.Errorf("%s and %s are not a key pair", KeyFile, CertFile)
	}

	return Identity{
		BridgeID:    cert.Subject.CommonName,
		Fingerprint: fingerprint(certSPKI),
		cert:        cert,
		key:         key,
	}, nil
}

// TLSCertificate serves the identity over net/http's TLS support.
func (i Identity) TLSCertificate() tls.Certificate {
	return tls.Certificate{
		Certificate: [][]byte{i.cert.Raw},
		PrivateKey:  i.key,
	}
}

// TLSConfig is the server-side minimum: the certificate we serve, and nothing
// about client authentication, which pairing does with tokens rather than
// client certificates.
func (i Identity) TLSConfig() *tls.Config {
	return &tls.Config{Certificates: []tls.Certificate{i.TLSCertificate()}, MinVersion: tls.VersionTLS12}
}

func parseKey(data []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("%s is not PEM", KeyFile)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parsing %s: %w", KeyFile, err)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("%s holds a %T, expected an RSA key", KeyFile, parsed)
	}
	return key, nil
}

func parseCert(data []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("%s is not PEM", CertFile)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parsing %s: %w", CertFile, err)
	}
	return cert, nil
}

// generate writes the key first, then the certificate. Order matters: a crash
// between the two leaves a key without a certificate, which Load refuses rather
// than papering over with a new identity.
func generate(dir string) (Identity, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return Identity{}, fmt.Errorf("%w: creating %s: %v", ErrNotWritable, dir, err)
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return Identity{}, fmt.Errorf("generating the Bridge key: %w", err)
	}
	id := newBridgeID()
	now := time.Now()

	// No SAN entries for LAN addresses: they change, and a certificate that omits
	// the address the client actually used would fail hostname verification on
	// every network but this one. Pairing does not rely on hostname verification;
	// the phone pins this digest (docs/PHASE-7-PLAN.md A8).
	template := x509.Certificate{
		SerialNumber:          big.NewInt(now.UnixNano()),
		Subject:               pkix.Name{CommonName: id, Organization: []string{"AI Usage Bridge"}},
		NotBefore:             now.Add(-time.Minute),
		NotAfter:              now.Add(certValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return Identity{}, fmt.Errorf("self-signing the Bridge certificate: %w", err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return Identity{}, fmt.Errorf("parsing the certificate we just made: %w", err)
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return Identity{}, fmt.Errorf("encoding the public key: %w", err)
	}

	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY",
		Bytes: mustMarshal(key)})
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	// The key is written first: a crash between the two leaves a key without a
	// certificate, which Load refuses rather than papering over.
	if err := filestore.Write(filepath.Join(dir, KeyFile), keyPEM, 0o600); err != nil {
		return Identity{}, err
	}
	if err := filestore.Write(filepath.Join(dir, CertFile), certPEM, 0o644); err != nil {
		return Identity{}, err
	}

	return Identity{
		BridgeID:    id,
		Fingerprint: fingerprint(spki),
		cert:        cert,
		key:         key,
	}, nil
}

func mustMarshal(key *rsa.PrivateKey) []byte {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		panic(fmt.Sprintf("marshalling a fresh RSA key: %v", err))
	}
	return der
}

// newBridgeID is a random identity, not a hostname and not an address: Spec §22
// forbids pinning an IP as the Bridge's permanent identity, and §54 repeats it.
func newBridgeID() string {
	var raw [12]byte
	if _, err := rand.Read(raw[:]); err != nil {
		panic(fmt.Sprintf("reading randomness for the Bridge ID: %v", err))
	}
	// base32 without padding, upper-cased letters folded down: something a human
	// can read off a screen and type without ambiguous characters.
	return "br_" + strings.ToLower(base32.HexEncoding.WithPadding(base32.NoPadding).EncodeToString(raw[:]))
}

func fingerprint(spki []byte) string {
	sum := sha256.Sum256(spki)
	return hex.EncodeToString(sum[:])
}
