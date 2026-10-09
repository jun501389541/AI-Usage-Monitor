package bridge

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"aiusage.local/bridge/internal/codex"
)

// The interleaving the review found (docs/PHASE-5-REVIEW.md §3) is not something
// a single-path test can see: a request that loaded the state before a concurrent
// success and saved after it erased numbers that were already on disk. These
// tests latch the fetch calls so the order is forced rather than hoped for.

// latchFetch parks its first call inside the fetcher until the test releases it,
// then fails; the second call announces itself and succeeds. Those two signals
// are what make the interleaving a fact rather than a luck of scheduling.
type latchFetch struct {
	mu       sync.Mutex
	calls    int
	inFirst  chan struct{}
	goFirst  chan struct{}
	inSecond chan struct{}
	once     sync.Once
}

func (f *latchFetch) Fetch() (Reading, error) {
	f.mu.Lock()
	f.calls++
	first := f.calls == 1
	f.mu.Unlock()

	if first {
		close(f.inFirst)
		<-f.goFirst
		return Reading{}, errors.New("latched fetch released, then failed")
	}
	f.once.Do(func() { close(f.inSecond) })
	snap, err := codex.ParseRateLimits([]byte(realPayload))
	if err != nil {
		panic(err)
	}
	return Reading{Snapshot: snap, CodexVersion: "0.121.0"}, nil
}

// The interleaving the review found: a request loads an empty state, waits inside
// Codex, and a second request succeeds and saves meanwhile. The first then commits
// the state it read *before* that success, and the numbers are gone - rule 18 lost
// through an ordering rather than a wrong branch (docs/PHASE-5-REVIEW.md §3,
// reproduced as `after_late_failure has_data=false`).
func TestLateFailureCannotEraseAConcurrentSuccess(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := NewStore(path)
	f := &latchFetch{inFirst: make(chan struct{}), goFirst: make(chan struct{}), inSecond: make(chan struct{})}
	svc := NewService(f, store, time.Minute)

	first := make(chan error, 1)
	go func() { _, err := svc.Usage(true); first <- err }()
	select {
	case <-f.inFirst:
	case err := <-first:
		t.Fatalf("first request failed before fetching: %v", err)
	case <-time.After(5 * time.Second):
		t.Fatal("first request did not reach the fetcher")
	}

	second := make(chan View, 1)
	go func() { v, _ := svc.Usage(true); second <- v }()

	// The second fetch must not start while the first is parked. If it does, the
	// rest of this test would only be watching the race that the transaction lock
	// exists to prevent, so it is reported right here.
	select {
	case <-f.inSecond:
		close(f.goFirst)
		<-first
		<-second
		t.Fatal("the second refresh reached Codex while the first was still inside it")
	case <-time.After(300 * time.Millisecond):
	}

	close(f.goFirst)
	if err := <-first; err == nil {
		t.Fatal("the latched fetch was meant to fail")
	}
	// A storage error can return before entering the second fetch. Waiting on
	// inSecond alone would hang the suite rather than report that failure.
	var view View
	select {
	case view = <-second:
	case <-time.After(5 * time.Second):
		t.Fatal("second request did not complete")
	}
	if !view.State.HasData() {
		t.Fatal("the second request served no numbers")
	}
	st, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !st.HasData() {
		t.Fatal("a late failure erased a concurrent success (rule 18)")
	}
}

// barrierFetch announces that it is inside Codex and waits for permission to
// finish, so a test can ask whether a second refresh started meanwhile.
type barrierFetch struct {
	mu      sync.Mutex
	active  int
	maxSeen int
	inside  chan struct{}
	proceed chan struct{}
}

func (f *barrierFetch) Fetch() (Reading, error) {
	f.mu.Lock()
	f.active++
	if f.active > f.maxSeen {
		f.maxSeen = f.active
	}
	f.mu.Unlock()

	select {
	case f.inside <- struct{}{}:
	default:
	}
	<-f.proceed

	f.mu.Lock()
	f.active--
	f.mu.Unlock()

	snap, err := codex.ParseRateLimits([]byte(realPayload))
	if err != nil {
		panic(err)
	}
	return Reading{Snapshot: snap}, nil
}

func (f *barrierFetch) peakConcurrency() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.maxSeen
}

// Two force refreshes at the same moment must not both be talking to Codex: a
// second app server per tap is expensive, and their writes are what overwrote
// each other.
func TestSecondRefreshWaitsForTheFirst(t *testing.T) {
	f := &barrierFetch{inside: make(chan struct{}, 4), proceed: make(chan struct{})}
	svc := NewService(f, NewStore(filepath.Join(t.TempDir(), "state.json")), time.Minute)

	done := make(chan struct{})
	go func() { defer close(done); _, _ = svc.Usage(true) }()

	select {
	case <-f.inside:
	case <-time.After(2 * time.Second):
		t.Fatal("the first refresh never reached the fetcher")
	}

	second := make(chan struct{})
	go func() { _, _ = svc.Usage(true); close(second) }()

	// An absence check: with the transaction serialised the second fetch cannot
	// start, so nothing arrives here. Only the release below unblocks it.
	select {
	case <-f.inside:
		close(f.proceed)
		t.Fatal("two refreshes were inside Codex at the same time")
	case <-second:
		close(f.proceed)
		t.Fatal("the second refresh finished before the first was released")
	case <-time.After(300 * time.Millisecond):
	}

	close(f.proceed)
	<-done
	<-second
	if peak := f.peakConcurrency(); peak != 1 {
		t.Fatalf("peak concurrent fetches = %d, want 1", peak)
	}
}

// A state file that cannot be written must not be presented as a reading: the
// caller would believe the numbers survived.
func TestUnwritableStateFileIsReported(t *testing.T) {
	dir := t.TempDir()
	blocker := filepath.Join(dir, "not-a-directory")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed blocker: %v", err)
	}
	svc := NewService(&fakeFetch{results: []func() (Reading, error){goodReading}},
		NewStore(filepath.Join(blocker, "state", "state.json")), time.Minute)

	if _, err := svc.Usage(true); err == nil {
		t.Fatal("an unwritable state file was reported as success")
	}
}

// Saves used to share one temporary name (`path + ".tmp"`), so two writers
// overwrote each other's bytes and then renamed whatever mixture was left - a
// silently corrupt state file. The check is therefore not "every save succeeds":
// on Windows a rename onto a target another rename is mid-flight is refused with
// "Access is denied", which is a loud, reported failure. The file that does land
// has to be one complete state, and no temporary may survive.
func TestConcurrentSavesNeverMixAState(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	store := NewStore(path)

	var wg sync.WaitGroup
	var landed int
	var mu sync.Mutex
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			st := State{
				PlanType:        fmt.Sprintf("plan-%d", n),
				Windows:         []PublicWindow{{ID: "codex:300", UsedPercent: n * 5, RemainingPercent: 100 - n*5}},
				SourceFetchedAt: time.Now(),
			}
			if err := store.Save(st); err != nil {
				t.Logf("save %d refused: %v", n, err)
				return
			}
			mu.Lock()
			landed++
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	if landed == 0 {
		t.Fatal("not one save landed, so the state file proves nothing")
	}
	st, err := store.Load()
	if err != nil {
		t.Fatalf("state unreadable after concurrent saves: %v", err)
	}
	if !st.HasData() {
		t.Fatalf("concurrent saves produced a state with no windows: %+v", st)
	}
	// A mixed write would break either the pairing of the two percentages or the
	// plan name that was written in the same payload.
	if w := st.Windows[0]; w.UsedPercent+w.RemainingPercent != 100 {
		t.Fatalf("the surviving state mixes two writes: %+v", st)
	}
	if !strings.HasPrefix(st.PlanType, "plan-") {
		t.Fatalf("the surviving state is not one that was written: %q", st.PlanType)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp") {
			t.Errorf("a temporary file survived the saves: %s", e.Name())
		}
	}
}
