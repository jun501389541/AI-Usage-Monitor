package pairing

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The four regressions docs/PHASE-7-REVIEW.md asked for. Each one is about the
// difference between "the answer changed" and "the answer is what the disk says":
// a registry that mutates in memory and fails to persist tells its caller one
// thing and boots next time as another.

func TestLockoutSurvivesARestart(t *testing.T) {
	at := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	path := filepath.Join(t.TempDir(), "pairing.json")
	s, err := NewStore(path)
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	s.now = func() time.Time { return at }

	pairToken, code, _, err := s.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	for i := 0; i < MaxFailedExchanges; i++ {
		if _, _, err := s.Exchange(strings.Repeat("9", 64), "attacker"); err == nil {
			t.Fatal("a made-up secret exchanged")
		}
	}
	if got := s.Outstanding(); got != 0 {
		t.Fatalf("Outstanding = %d in-process, want 0", got)
	}

	// The bug the review named: the burn lived in memory only, so a restart
	// reloaded the old file and the unexpired code worked again.
	restarted, err := NewStore(path)
	if err != nil {
		t.Fatalf("restart NewStore: %v", err)
	}
	restarted.now = func() time.Time { return at }
	if got := restarted.Outstanding(); got != 0 {
		t.Fatalf("Outstanding after restart = %d, want still 0", got)
	}
	if _, _, err := restarted.Exchange(pairToken, "the code that was live"); !errors.Is(err, ErrLockedOut) {
		t.Errorf("live code after restart = %v, want %v", err, ErrLockedOut)
	}
	if _, _, err := restarted.Exchange(code, "short form after restart"); !errors.Is(err, ErrLockedOut) {
		t.Errorf("short code after restart = %v, want %v", err, ErrLockedOut)
	}

	// A fresh issue is still the way back, and it clears the persisted counter.
	if _, _, _, err := restarted.Issue(DefaultTTL); err != nil {
		t.Fatalf("Issue after the restart: %v", err)
	}
	third, err := NewStore(path)
	if err != nil {
		t.Fatalf("third NewStore: %v", err)
	}
	third.now = func() time.Time { return at }
	token2, _, _, err := third.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue on third: %v", err)
	}
	if _, _, err := third.Exchange(token2, "phone"); err != nil {
		t.Errorf("exchange after a fresh issue = %v, want success", err)
	}
}

// brokenPath points the store at a location it cannot write: the parent is a
// regular file. The registry stays readable, so the test can prove what a failed
// write leaves behind rather than what a missing file does.
func brokenPath(t *testing.T) string {
	t.Helper()
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed blocker: %v", err)
	}
	return filepath.Join(blocker, "pairing.json")
}

func TestFailedWriteIsReportedAndChangesNothing(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "pairing.json")
	s, err := NewStore(path)
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	token, _, _, err := s.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	device, issued, err := s.Exchange(token, "Pixel")
	if err != nil {
		t.Fatalf("Exchange: %v", err)
	}
	if _, err := s.Validate(issued); err != nil {
		t.Fatalf("Validate before the broken write: %v", err)
	}

	// Same package, so the path can be moved out from under the store; this is
	// the "disk refuses" branch no amount of black-box testing can reach.
	s.mu.Lock()
	s.path = brokenPath(t)
	s.mu.Unlock()

	err = s.Revoke(device.ID)
	if err == nil {
		t.Fatal("a revocation that could not be written reported success")
	}
	if !errors.Is(err, ErrNotPersisted) {
		t.Fatalf("Revoke = %v, want it to wrap %v", err, ErrNotPersisted)
	}
	// Fail closed: the memory must not keep a revocation the disk never got, or
	// the restart would hand the token back after telling the operator it worked.
	if _, err := s.Validate(issued); err != nil {
		t.Errorf("the device was revoked in memory only: %v", err)
	}
	untouched, err := NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if _, err := untouched.Validate(issued); err != nil {
		t.Errorf("the file on disk disagrees with what the API reported: %v", err)
	}

	// An exchange must not adopt a device either.
	live, _, _, err := untouched.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue on the reopened store: %v", err)
	}
	broken, err := NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	broken.mu.Lock()
	broken.path = brokenPath(t)
	broken.mu.Unlock()
	if _, _, err := broken.Exchange(live, "orphan"); err == nil {
		t.Fatal("an exchange whose write failed reported success")
	}
	devices, err := NewStore(filepath.Join(dir, "pairing.json"))
	if err != nil {
		t.Fatalf("final reopen: %v", err)
	}
	for _, d := range devices.List() {
		if d.Name == "orphan" {
			t.Fatal("a device the disk never recorded exists anyway")
		}
	}
}

func TestEmptyPairingFileIsDamageNotAFreshStart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pairing.json")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatalf("seed empty: %v", err)
	}
	if _, err := NewStore(path); err == nil {
		t.Fatal("a zero-length registry opened as an empty one; a truncation would silently revoke every phone")
	}
	// The file is left alone: "start over" is a decision for the operator, not a
	// side effect of opening.
	info, err := os.Stat(path)
	if err != nil || info.Size() != 0 {
		t.Fatalf("the empty file was replaced (size=%v err=%v)", info, err)
	}

	// A missing file is still a normal first run.
	fresh, err := NewStore(filepath.Join(t.TempDir(), "pairing.json"))
	if err != nil {
		t.Fatalf("first run: %v", err)
	}
	if n := len(fresh.List()); n != 0 {
		t.Errorf("first run reported %d devices, want 0", n)
	}
}

// The refusal is the only thing between a guesser and the codes the disk still
// holds, so a second failure must not lift it. Issue() cleared the flag before
// committing: an issuance whose write was refused left the old live pairing
// exchangeable again, with the counter and the burn both never persisted
// (docs/PHASE-0-7-REVIEW.md §2.5).
func TestIssueCannotLiftTheRefusalWithoutWriting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pairing.json")
	s, err := NewStore(path)
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	token, code, _, err := s.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}

	s.mu.Lock()
	s.path = brokenPath(t)
	s.mu.Unlock()
	for i := 0; i < MaxFailedExchanges; i++ {
		if _, _, err := s.Exchange(strings.Repeat("9", 64), "attacker"); err == nil {
			t.Fatal("a made-up secret exchanged")
		}
	}
	// The state this test is built on: guessing is refused because the burn could
	// not be written, while the issued code is still live in memory and on disk.
	if _, _, err := s.Exchange(token, "the live code"); !errors.Is(err, ErrLockedOut) {
		t.Fatalf("before the failed Issue: %v, want %v", err, ErrLockedOut)
	}
	if _, _, _, err := s.Issue(DefaultTTL); err == nil {
		t.Fatal("Issue reported success against an unwritable registry")
	}
	if _, _, err := s.Exchange(token, "after the failed Issue"); !errors.Is(err, ErrLockedOut) {
		t.Errorf("a failed Issue lifted the refusal and let the old code back in: %v", err)
	}
	if _, _, err := s.Exchange(code, "short form"); !errors.Is(err, ErrLockedOut) {
		t.Errorf("the short code came back after a failed Issue: %v", err)
	}

	// The way out stays the same: an issuance that really reaches the disk.
	s.mu.Lock()
	s.path = path
	s.mu.Unlock()
	fresh, _, _, err := s.Issue(DefaultTTL)
	if err != nil {
		t.Fatalf("Issue once the disk works: %v", err)
	}
	if _, _, err := s.Exchange(fresh, "Pixel"); err != nil {
		t.Errorf("a persisted issue did not lift the refusal: %v", err)
	}
	// And the store that reloads agrees: the old code is gone from the file too.
	reopened, err := NewStore(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if _, _, err := reopened.Exchange(token, "the old code"); err == nil {
		t.Error("the code from before the burn exchanged again after a restart")
	}
}
