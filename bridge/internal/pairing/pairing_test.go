package pairing

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// issue is a helper so the tests do not repeat the four-value signature.
func issue(s *Store) (string, string, time.Time, error) { return s.Issue(DefaultTTL) }

func newStore(t *testing.T, at *time.Time) *Store {
	t.Helper()
	s, err := NewStore(filepath.Join(t.TempDir(), "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	if at != nil {
		s.now = func() time.Time { return *at }
	}
	return s
}

func TestExchangeOnceThenReplayHeardUsed(t *testing.T) {
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	s := newStore(t, &at)

	token, code, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	device, issued, err := s.Exchange(token, "Pixel 7")
	if err != nil {
		t.Fatalf("Exchange: %v", err)
	}
	if device.Name != "Pixel 7" {
		t.Fatalf("name = %q, want what the phone reported", device.Name)
	}
	if len(issued) != 64 || issued == token {
		t.Fatalf("device token = %q, want a fresh 64-char hex", issued)
	}

	// Either half of the pairing is spent now, and the answer must say so rather
	// than the vaguer "unknown": a phone that retried after a dropped response
	// needs to know it is not looking at a network fault.
	if _, _, err := s.Exchange(token, "again"); !errors.Is(err, ErrUsed) {
		t.Errorf("replaying the token = %v, want %v", err, ErrUsed)
	}
	if _, _, err := s.Exchange(code, "again"); !errors.Is(err, ErrUsed) {
		t.Errorf("replaying the short code = %v, want %v", err, ErrUsed)
	}
	if got := s.Outstanding(); got != 0 {
		t.Errorf("Outstanding = %d after the exchange, want 0", got)
	}
}

func TestExpiredCodeIsRejected(t *testing.T) {
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	s := newStore(t, &at)
	token, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}

	at = at.Add(DefaultTTL + time.Second)
	if _, _, err := s.Exchange(token, "late"); !errors.Is(err, ErrExpired) {
		t.Fatalf("expired exchange = %v, want %v", err, ErrExpired)
	}
}

func TestIssuingAgainSupersedesThePreviousCode(t *testing.T) {
	s := newStore(t, nil)
	first, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	second, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue 2: %v", err)
	}
	if first == second {
		t.Fatal("two issues returned the same token")
	}
	if got := s.Outstanding(); got != 1 {
		t.Fatalf("Outstanding = %d, want exactly one live pairing", got)
	}
	if _, _, err := s.Exchange(first, "stale"); err == nil {
		t.Fatal("the superseded code still exchanged; one QR on screen must mean one live code")
	}
	if _, _, err := s.Exchange(second, "phone"); err != nil {
		t.Fatalf("the current code failed: %v", err)
	}
}

// The whole point of hashing at rest: a stolen or backed-up pairing.json must not
// read as a set of credentials, and must not be replayable.
func TestStoreNeverHoldsPlaintextSecrets(t *testing.T) {
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	s := newStore(t, &at)
	s.path = filepath.Join(t.TempDir(), "pairing.json")
	token, code, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	device, issued, err := s.Exchange(token, "Pixel")
	if err != nil {
		t.Fatalf("Exchange: %v", err)
	}
	if err := s.Touch(device.ID); err != nil {
		t.Fatalf("Touch: %v", err)
	}

	raw, err := os.ReadFile(s.path)
	if err != nil {
		t.Fatalf("read store: %v", err)
	}
	text := string(raw)
	for _, secret := range []string{token, code, issued} {
		if strings.Contains(text, secret) {
			t.Fatalf("the store holds a plaintext secret: %s", redactTail(secret))
		}
	}
	// And the replay is not possible from the file either: nothing in it can be
	// presented as a token.
	var dumped persisted
	if err := json.Unmarshal(raw, &dumped); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	for _, d := range dumped.Devices {
		if len(d.TokenHash) != 64 || d.TokenHash == issued {
			t.Fatalf("device %s stored %q, want a SHA-256 digest", d.ID, d.TokenHash)
		}
	}

	// A digest copied out of the file still must not validate: Validate hashes what
	// the caller presents, so presenting the stored digest is not the same thing.
	if _, err := s.Validate(dumped.Devices[0].TokenHash); err == nil {
		t.Fatal("a copied digest validated as a device token")
	}
}

func TestRegistrySurvivesARestart(t *testing.T) {
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	path := filepath.Join(t.TempDir(), "pairing.json")
	s, err := NewStore(path)
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	s.now = func() time.Time { return at }
	token, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	device, issued, err := s.Exchange(token, "Pixel")
	if err != nil {
		t.Fatalf("Exchange: %v", err)
	}

	// Spec §21: a Bridge restart must not ask anyone to re-pair.
	restarted, err := NewStore(path)
	if err != nil {
		t.Fatalf("restart NewStore: %v", err)
	}
	later := at.Add(2 * time.Hour)
	restarted.now = func() time.Time { return later }
	found, err := restarted.Validate(issued)
	if err != nil {
		t.Fatalf("Validate after restart: %v", err)
	}
	if found.ID != device.ID || found.Name != device.Name {
		t.Fatalf("device changed across restart: %+v vs %+v", found, device)
	}
	if !found.CreatedAt.Equal(at) {
		t.Fatalf("CreatedAt = %s, want it preserved as %s", found.CreatedAt, at)
	}
	if err := restarted.Touch(device.ID); err != nil {
		t.Fatalf("Touch: %v", err)
	}
	again, err := NewStore(path)
	if err != nil {
		t.Fatalf("third NewStore: %v", err)
	}
	seen, err := again.Validate(issued)
	if err != nil {
		t.Fatalf("Validate after Touch: %v", err)
	}
	if !seen.LastSeenAt.Equal(later) {
		t.Fatalf("LastSeenAt = %s after persisting a touch, want %s", seen.LastSeenAt, later)
	}
}

func TestRevokeIsDistinguishableFromUnknown(t *testing.T) {
	s := newStore(t, nil)
	token, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	device, issued, err := s.Exchange(token, "Pixel")
	if err != nil {
		t.Fatalf("Exchange: %v", err)
	}
	if err := s.Revoke(device.ID); err != nil {
		t.Fatalf("Revoke: %v", err)
	}
	if _, err := s.Validate(issued); !errors.Is(err, ErrRevoked) {
		t.Fatalf("revoked Validate = %v, want %v (the phone must be told to re-pair, not that the computer is offline)", err, ErrRevoked)
	}
	if _, err := s.Validate(strings.Repeat("f", 64)); !errors.Is(err, ErrUnknownDevice) {
		t.Fatalf("unknown Validate = %v, want %v", err, ErrUnknownDevice)
	}
	if n := s.Active(); n != 0 {
		t.Errorf("Active = %d after revoking the only device, want 0", n)
	}
	if n := len(s.List()); n != 1 {
		t.Errorf("List = %d, want the revoked record kept for the operator", n)
	}
	if err := s.Revoke("dev_nope"); !errors.Is(err, ErrUnknownDevice) {
		t.Errorf("Revoke of an unknown id = %v, want %v", err, ErrUnknownDevice)
	}
}

func TestGuessingBurnsEveryOutstandingCode(t *testing.T) {
	s := newStore(t, nil)
	token, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if got := s.Outstanding(); got != 1 {
		t.Fatalf("Outstanding = %d before the guessing, want 1", got)
	}

	for i := 0; i < MaxFailedExchanges; i++ {
		if _, _, err := s.Exchange(strings.Repeat("a", 64), "attacker"); err == nil {
			t.Fatal("a made-up secret exchanged")
		}
	}
	if got := s.Outstanding(); got != 0 {
		t.Fatalf("Outstanding = %d after %d wrong attempts, want 0: the short code is ~40 bits and an uncapped number of guesses makes it guessable",
			got, MaxFailedExchanges)
	}
	if _, _, err := s.Exchange(token, "the code that was open"); !errors.Is(err, ErrLockedOut) {
		t.Fatalf("the live code after the cap = %v, want %v", err, ErrLockedOut)
	}

	// A fresh issue is the way back, and it clears the counter.
	token2, _, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue after lockout: %v", err)
	}
	if _, _, err := s.Exchange(token2, "phone"); err != nil {
		t.Fatalf("exchange after a fresh issue = %v, want success", err)
	}
}

// A phone that types a code with a stray space or in caps must not be told the
// computer is unreachable; the stored digest is what has to match.
func TestSecretPresentationIsNormalised(t *testing.T) {
	s := newStore(t, nil)
	_, code, _, err := issue(s)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if _, _, err := s.Exchange("  "+strings.ToUpper(code)+"\n", "Pixel"); err != nil {
		t.Fatalf("upper-cased code with whitespace = %v, want it accepted", err)
	}
}

// A device token the server cannot persist is a device that will not be recognised
// after the next restart, so the exchange has to fail at the moment it happens
// rather than hand out a token nobody has recorded.
func TestSecretsAreNotIssuedWhenTheStoreCannotBeWritten(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed blocker: %v", err)
	}
	s, err := NewStore(filepath.Join(blocker, "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore on first run should not touch the disk: %v", err)
	}
	if _, _, _, err := s.Issue(DefaultTTL); err == nil {
		t.Fatal("Issue succeeded although the pairing could not be persisted")
	}

	// The same must hold for an exchange: the file it writes is the only record
	// that the device exists.
	dir := t.TempDir()
	live, err := NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	token, _, _, err := live.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	unwritable, err := NewStore(filepath.Join(blocker, "second.json"))
	if err != nil {
		t.Fatalf("NewStore second: %v", err)
	}
	if device, issued, err := unwritable.Exchange(token, "Pixel"); err == nil {
		t.Fatalf("Exchange handed out a token that was never stored: device=%s token=%s", device.ID, issued[:8])
	}
}

func TestCorruptStoreIsReportedNotDiscarded(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pairing.json")
	if err := os.WriteFile(path, []byte("{ not json"), 0o600); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if _, err := NewStore(path); err == nil {
		t.Fatal("an unparsable pairing store opened anyway; devices would silently vanish")
	}
	// The broken file must stay put: rewriting it would be the silent wipe.
	info, err := os.Stat(path)
	if err != nil || info.Size() != 10 {
		t.Fatalf("the corrupt file was touched (size=%v err=%v)", info, err)
	}
}

// The short code is the weakest link in pairing, so the alphabet it comes from is
// part of its security story: no characters that get transcribed differently when
// read aloud or copied by eye.
func TestTypedAlphabetAvoidsAmbiguousCharacters(t *testing.T) {
	if bad := strings.ContainsAny(codeAlphabet, "iIlLoO01"); bad {
		t.Fatalf("the alphabet %q contains characters people transcribe wrongly", codeAlphabet)
	}
	if n := len(codeAlphabet); n < 28 || n > 32 {
		t.Fatalf("alphabet size = %d; 8 characters should still be ~40 bits of guessing", n)
	}
}

func TestIdentifiersAreUniqueAndShaped(t *testing.T) {
	s := newStore(t, nil)
	seen := map[string]bool{}
	for i := 0; i < 5; i++ {
		token, code, _, err := issue(s)
		if err != nil {
			t.Fatalf("Issue %d: %v", i, err)
		}
		if len(token) != 64 {
			t.Fatalf("pair token length = %d, want 64 hex chars", len(token))
		}
		if len(code) != codeChars {
			t.Fatalf("short code = %q, want %d characters", code, codeChars)
		}
		for _, r := range code {
			if !strings.ContainsRune(codeAlphabet, r) {
				t.Fatalf("short code %q contains %q, which is not in the typed alphabet", code, r)
			}
		}
		device, issued, err := s.Exchange(token, "  Pixel   7  ")
		if err != nil {
			t.Fatalf("Exchange %d: %v", i, err)
		}
		if seen[device.ID] {
			t.Fatalf("device id %s repeated", device.ID)
		}
		seen[device.ID] = true
		if !strings.HasPrefix(device.ID, "dev_") {
			t.Fatalf("device id %q does not look like an id", device.ID)
		}
		if device.Name != "Pixel 7" {
			t.Fatalf("name = %q, want surrounding and repeated spaces collapsed", device.Name)
		}
		if len(issued) != 64 {
			t.Fatalf("device token length = %d, want 64", len(issued))
		}
	}
}

func redactTail(secret string) string {
	if len(secret) <= 6 {
		return "..."
	}
	return secret[:6] + "..."
}
