package identity

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
)

func TestLoadCreatesThenReusesTheSameIdentity(t *testing.T) {
	dir := t.TempDir()

	first, err := Load(dir)
	if err != nil {
		t.Fatalf("first Load: %v", err)
	}
	keyBytes, err := os.ReadFile(filepath.Join(dir, KeyFile))
	if err != nil {
		t.Fatalf("key file: %v", err)
	}
	certBytes, err := os.ReadFile(filepath.Join(dir, CertFile))
	if err != nil {
		t.Fatalf("cert file: %v", err)
	}

	second, err := Load(dir)
	if err != nil {
		t.Fatalf("second Load: %v", err)
	}
	if second.BridgeID != first.BridgeID || second.Fingerprint != first.Fingerprint {
		t.Fatalf("identity changed across restarts: %+v then %+v", first, second)
	}
	// And the advertised digest must still be the served certificate's SPKI after
	// a reload, not merely what the generating path remembered.
	spki, err := x509.MarshalPKIXPublicKey(second.cert.PublicKey)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey: %v", err)
	}
	sum := sha256.Sum256(spki)
	if got := hex.EncodeToString(sum[:]); got != second.Fingerprint {
		t.Fatalf("reloaded fingerprint = %s, want the SPKI digest %s", second.Fingerprint, got)
	}
	// Bytes, not just values: pairing pins the fingerprint, so a silent re-mint
	// here would break every phone while still returning a working-looking ID.
	if again, err := os.ReadFile(filepath.Join(dir, KeyFile)); err != nil || string(again) != string(keyBytes) {
		t.Fatalf("the key file was rewritten: err=%v same=%t", err, string(again) == string(keyBytes))
	}
	if again, err := os.ReadFile(filepath.Join(dir, CertFile)); err != nil || string(again) != string(certBytes) {
		t.Fatalf("the certificate file was rewritten: err=%v same=%t", err, string(again) == string(certBytes))
	}
}

func TestFingerprintIsTheServedCertificatesSPKI(t *testing.T) {
	dir := t.TempDir()
	id, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	data, err := os.ReadFile(filepath.Join(dir, CertFile))
	if err != nil {
		t.Fatalf("cert file: %v", err)
	}
	block, _ := pem.Decode(data)
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("ParseCertificate: %v", err)
	}
	spki, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey: %v", err)
	}
	sum := sha256.Sum256(spki)
	if got := hex.EncodeToString(sum[:]); got != id.Fingerprint {
		t.Fatalf("fingerprint = %s, want the certificate's SPKI digest %s", id.Fingerprint, got)
	}

	// What a client actually sees on the wire must be the same certificate.
	served := id.TLSCertificate()
	if len(served.Certificate) != 1 {
		t.Fatalf("expected one certificate, got %d", len(served.Certificate))
	}
	wire, err := x509.ParseCertificate(served.Certificate[0])
	if err != nil {
		t.Fatalf("served cert: %v", err)
	}
	if wire.Subject.CommonName != id.BridgeID {
		t.Fatalf("served certificate identifies %q, expected the Bridge ID %q", wire.Subject.CommonName, id.BridgeID)
	}
}

func TestCorruptKeyIsReportedInsteadOfReplaced(t *testing.T) {
	dir := t.TempDir()
	if _, err := Load(dir); err != nil {
		t.Fatalf("seed Load: %v", err)
	}
	keyPath := filepath.Join(dir, KeyFile)
	junk := []byte("not a private key")
	if err := os.WriteFile(keyPath, junk, 0o600); err != nil {
		t.Fatalf("corrupt: %v", err)
	}

	_, err := Load(dir)
	if err == nil {
		t.Fatal("a corrupt key produced an identity; it should refuse rather than mint a new one")
	}
	if still, readErr := os.ReadFile(keyPath); readErr != nil || string(still) != string(junk) {
		t.Fatalf("the corrupt key was overwritten (err=%v): %q", readErr, still)
	}
}

func TestCorruptCertificateIsReportedInsteadOfReplaced(t *testing.T) {
	dir := t.TempDir()
	first, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	certPath := filepath.Join(dir, CertFile)
	junk := []byte("not a certificate")
	if err := os.WriteFile(certPath, junk, 0o644); err != nil {
		t.Fatalf("corrupt: %v", err)
	}

	_, err = Load(dir)
	if err == nil {
		t.Fatal("a corrupt certificate produced an identity; a new one would outdate every pinned fingerprint")
	}
	if still, readErr := os.ReadFile(certPath); readErr != nil || string(still) != string(junk) {
		t.Fatalf("the corrupt certificate was overwritten (err=%v): %q", readErr, still)
	}
	if second, statErr := os.Stat(filepath.Join(dir, KeyFile)); statErr != nil {
		t.Fatalf("the key was removed while repairing: %v", statErr)
	} else if second.Size() == 0 {
		t.Fatal("the key file was truncated")
	}
	_ = first
}

func TestKeyWithoutCertificateRefusesToRemint(t *testing.T) {
	dir := t.TempDir()
	if _, err := Load(dir); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if err := os.Remove(filepath.Join(dir, CertFile)); err != nil {
		t.Fatalf("remove cert: %v", err)
	}

	_, err := Load(dir)
	if err == nil {
		t.Fatal("a missing certificate was repaired by minting a new identity")
	}
	if _, statErr := os.Stat(filepath.Join(dir, CertFile)); statErr == nil {
		t.Fatal("Load created a certificate behind the pairing state")
	}
	// The error has to say what the operator can act on: the fingerprint phones
	// pinned belongs to the certificate that went missing.
	if !strings.Contains(strings.ToLower(err.Error()), "paired") {
		t.Fatalf("error should name the consequence for paired devices: %v", err)
	}
}

func TestSwappedKeyFailsThePairCheck(t *testing.T) {
	dirA := t.TempDir()
	idA, err := Load(dirA)
	if err != nil {
		t.Fatalf("Load A: %v", err)
	}
	dirB := t.TempDir()
	idB, err := Load(dirB)
	if err != nil {
		t.Fatalf("Load B: %v", err)
	}
	if idA.BridgeID == idB.BridgeID {
		t.Fatal("two fresh Bridges share an ID")
	}

	// Copy B's key under A's certificate: the pair check is what stops this from
	// serving a chain whose fingerprint differs from what the Bridge advertises.
	bKey, err := os.ReadFile(filepath.Join(dirB, KeyFile))
	if err != nil {
		t.Fatalf("read B key: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dirA, KeyFile), bKey, 0o600); err != nil {
		t.Fatalf("swap: %v", err)
	}

	if _, err := Load(dirA); err == nil {
		t.Fatal("a key swapped under a certificate was accepted")
	}
}

func TestBridgeIDShape(t *testing.T) {
	id, err := Load(t.TempDir())
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !bridgeIDPattern.MatchString(id.BridgeID) {
		t.Fatalf("Bridge ID %q does not look like the documented form br_<20 chars>", id.BridgeID)
	}
}

func TestKeyFileIsOwnerOnly(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not carry POSIX permission bits; the mode request is compiled out here")
	}
	dir := t.TempDir()
	if _, err := Load(dir); err != nil {
		t.Fatalf("Load: %v", err)
	}
	info, err := os.Stat(filepath.Join(dir, KeyFile))
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Fatalf("key file mode = %o, want no group/other access", perm)
	}
}

var bridgeIDPattern = regexp.MustCompile(`^br_[a-z0-9]{20}$`)

// --add-device reads the identity of a Bridge that is already running. Minting a
// key in a directory that has none would leave it pinning a fingerprint nobody
// serves, which reads as a certificate problem rather than as the missing
// --data-dir it is (docs/PHASE-7-REVIEW.md P2).
func TestOpenExistingNeverMints(t *testing.T) {
	dir := t.TempDir()
	if _, err := OpenExisting(dir); err == nil {
		t.Fatal("OpenExisting accepted a directory with no identity")
	} else if !errors.Is(err, ErrNoIdentity) {
		t.Errorf("OpenExisting = %v, want ErrNoIdentity so the caller can name the fix", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("a refused OpenExisting left %d files behind", len(entries))
	}

	id, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	reopened, err := OpenExisting(dir)
	if err != nil {
		t.Fatalf("OpenExisting after Load: %v", err)
	}
	if reopened.BridgeID != id.BridgeID || reopened.Fingerprint != id.Fingerprint {
		t.Errorf("reopened = %+v, want the identity on disk (%+v)", reopened, id)
	}
	corrupt := filepath.Join(dir, KeyFile)
	if err := os.WriteFile(corrupt, []byte("not a key"), 0o600); err != nil {
		t.Fatalf("corrupt: %v", err)
	}
	if _, err := OpenExisting(dir); err == nil {
		t.Error("OpenExisting accepted a key it cannot parse")
	}
}
