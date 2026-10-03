package bridge

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"aiusage.local/bridge/internal/codex"
	"aiusage.local/bridge/internal/discover"
	"aiusage.local/bridge/internal/redact"
)

type fakeFetch struct {
	calls    int
	results  []func() (Reading, error)
	fallback func() (Reading, error)
}

func (f *fakeFetch) Fetch() (Reading, error) {
	f.calls++
	if f.calls <= len(f.results) {
		return f.results[f.calls-1]()
	}
	if f.fallback != nil {
		return f.fallback()
	}
	return Reading{}, errors.New("fakeFetch has no result for call " + string(rune('0'+f.calls)))
}

func goodReading() (Reading, error) {
	snap, err := codex.ParseRateLimits([]byte(realPayload))
	if err != nil {
		panic(err)
	}
	return Reading{Snapshot: snap}, nil
}

const realPayload = `{
  "rateLimits": {
    "limitId": "codex",
    "primary":   { "usedPercent": 20, "windowDurationMins": 300,   "resetsAt": 1790936545 },
    "secondary": { "usedPercent": 40, "windowDurationMins": 10080, "resetsAt": 1791419454 },
    "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
    "planType":  "plus"
  }
}`

func newService(t *testing.T, f Fetcher, ttl time.Duration, clock *time.Time) *Service {
	t.Helper()
	svc := NewService(f, NewStore(filepath.Join(t.TempDir(), "state.json")), ttl)
	svc.now = func() time.Time { return *clock }
	return svc
}

func TestCacheHitSkipsTheChild(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){goodReading, goodReading}}
	svc := newService(t, f, 5*time.Minute, &at)

	first, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("first Usage: %v", err)
	}
	if first.FromCache || first.Source != "codex" {
		t.Fatalf("first call should come from Codex: %+v", first)
	}

	second, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("second Usage: %v", err)
	}
	if f.calls != 1 {
		t.Fatalf("fetcher called %d times, want the cache to have answered the second request", f.calls)
	}
	if !second.FromCache || second.Source != "cache" {
		t.Fatalf("second call should be served from cache: %+v", second)
	}
}

// Spec L1792-1806: a forced refresh must ignore the cache. The count is the
// proof; a timestamp comparison alone would not notice a cache that ignored it.
func TestForceRefreshIgnoresTheCache(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){goodReading, goodReading}}
	svc := newService(t, f, 5*time.Minute, &at)

	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}
	if _, err := svc.Usage(true); err != nil {
		t.Fatalf("forced Usage: %v", err)
	}
	if f.calls != 2 {
		t.Fatalf("fetcher called %d times, want a forced refresh to go through", f.calls)
	}
}

func TestExpiredCacheRefetches(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){goodReading, goodReading}}
	svc := newService(t, f, 5*time.Minute, &at)

	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}
	at = at.Add(5*time.Minute + time.Second)
	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage after TTL: %v", err)
	}
	if f.calls != 2 {
		t.Fatalf("fetcher called %d times, want a refetch once the reading is older than the TTL", f.calls)
	}
}

// The rule this project has been burned by twice: a failed refresh may change
// the status line, but it must not erase the last successful numbers.
func TestFailureKeepsTheLastGoodWindows(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{
		results: []func() (Reading, error){
			goodReading,
			func() (Reading, error) { return Reading{}, codex.ErrTimeout },
		},
	}
	svc := newService(t, f, 5*time.Minute, &at)

	first, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("Usage: %v", err)
	}
	if len(first.State.Windows) != 2 {
		t.Fatalf("expected two windows, got %+v", first.State.Windows)
	}

	at = at.Add(6 * time.Minute)
	second, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("Usage after a failed fetch must still serve data, got error: %v", err)
	}
	if len(second.State.Windows) != 2 {
		t.Fatalf("windows were lost on failure: %+v", second.State.Windows)
	}
	if second.Degraded == nil || second.Degraded.Class != ClassTimeout {
		t.Fatalf("degraded = %+v, want %s", second.Degraded, ClassTimeout)
	}

	// And it must survive a restart of the Bridge, not just live in memory.
	reloaded, err := svc.store.Load()
	if err != nil {
		t.Fatalf("store.Load: %v", err)
	}
	if len(reloaded.Windows) != 2 {
		t.Fatalf("persisted windows = %+v", reloaded.Windows)
	}
	if reloaded.LastFailure == nil || reloaded.LastFailure.Class != ClassTimeout {
		t.Fatalf("persisted failure = %+v", reloaded.LastFailure)
	}
}

func TestFirstFailureIsAnError(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		func() (Reading, error) { return Reading{}, discover.ErrNotFound },
	}}
	svc := newService(t, f, 5*time.Minute, &at)

	_, err := svc.Usage(false)
	if err == nil {
		t.Fatal("with no data at all, Usage must report the failure")
	}
	if !strings.Contains(err.Error(), ClassNotFound) {
		t.Fatalf("err = %v, want the class name in it", err)
	}
}

func TestClassification(t *testing.T) {
	cases := []struct {
		name    string
		err     error
		refused []string
		want    string
	}{
		{"not found", discover.ErrNotFound, nil, ClassNotFound},
		{"timeout", codex.ErrTimeout, nil, ClassTimeout},
		{"child died", codex.ErrClosed, nil, ClassTimeout},
		// Spec §53 rule 19: offline and authorisation expiry are not the same
		// thing, and the refused reverse request is how expiry shows up here.
		{"auth via refused request", codex.ErrClosed, []string{"account/chatgptAuthTokens/refresh"}, ClassAuthRequired},
		{"unknown", errors.New("something else"), nil, ClassUnknown},
	}
	for _, c := range cases {
		got, _ := classify(c.err, c.refused)
		if got != c.want {
			t.Errorf("%s: classify = %q, want %q", c.name, got, c.want)
		}
	}
}

// Codex answering "no such method" is a capability statement, not a transport
// failure, and must not be reported as the machine being offline. The wire path
// that produces this error is covered in internal/codex (a real -32601 reply);
// here the classification is what matters, so any coded error stands in.
type codedErr struct{ code int }

func (e codedErr) Error() string { return "codex app-server error" }
func (e codedErr) RPCCode() int  { return e.code }

func TestMethodNotFoundClassifiesAsUnavailable(t *testing.T) {
	if !codex.IsMethodNotFound(codedErr{-32601}) {
		t.Fatal("the probe does not recognise a coded -32601 error")
	}
	class, _ := classify(codedErr{-32601}, nil)
	if class != ClassMethodUnavailable {
		t.Fatalf("class = %q, want %q", class, ClassMethodUnavailable)
	}
	if class, _ := classify(codedErr{-32000}, nil); class != ClassUnknown {
		t.Fatalf("an unrelated code should not be read as a capability claim, got %q", class)
	}
}

func TestStateRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := NewStore(path)
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	want := State{
		PlanType: "plus", CreditsBalance: "0",
		Windows: []PublicWindow{{ID: "codex:300", Label: "5 小时", UsedPercent: 20,
			RemainingPercent: 80, WindowMinutes: 300, ResetAtMillis: 1790936545000, Kind: "primary"}},
		SourceFetchedAt: at,
		LastFailure:     &Failure{Class: ClassTimeout, Message: "boom", At: at},
	}
	if err := store.Save(want); err != nil {
		t.Fatalf("Save: %v", err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(got.Windows) != 1 || got.Windows[0].ResetAtMillis != 1790936545000 {
		t.Fatalf("windows round-trip wrong: %+v", got.Windows)
	}
	if got.LastFailure == nil || got.LastFailure.Class != ClassTimeout {
		t.Fatalf("failure round-trip wrong: %+v", got.LastFailure)
	}
	if !got.SourceFetchedAt.Equal(at) {
		t.Fatalf("timestamp = %v, want %v", got.SourceFetchedAt, at)
	}
}

// Windows rename semantics: saving twice replaces the file rather than failing
// because it already exists. This is the platform the Bridge ships on, so it is
// tested rather than assumed.
func TestSaveReplacesExistingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	store := NewStore(path)
	if err := store.Save(State{PlanType: "plus"}); err != nil {
		t.Fatalf("first Save: %v", err)
	}
	if err := store.Save(State{PlanType: "pro"}); err != nil {
		t.Fatalf("second Save over an existing file: %v", err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.PlanType != "pro" {
		t.Fatalf("plan = %q, want the second write to win", got.PlanType)
	}
}

// A corrupt state file must not be silently treated as "no data", because the
// next write would then throw away whatever was recoverable on disk.
func TestCorruptStateFileIsReportedAndLeftAlone(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	if err := os.WriteFile(path, []byte("{ not json"), 0o600); err != nil {
		t.Fatalf("setup write: %v", err)
	}
	f := &fakeFetch{results: []func() (Reading, error){goodReading}}
	svc := NewService(f, NewStore(path), time.Minute)

	if _, err := svc.Usage(false); err == nil {
		t.Fatal("Usage should surface the unreadable state file")
	}
	kept, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("re-read: %v", err)
	}
	if string(kept) != "{ not json" {
		t.Fatalf("the corrupt file was overwritten: %q", kept)
	}
	if f.calls != 0 {
		t.Fatalf("fetcher ran (%d calls) although the state was unreadable", f.calls)
	}
}

func TestMissingStateFileIsJustEmpty(t *testing.T) {
	store := NewStore(filepath.Join(t.TempDir(), "absent.json"))
	st, err := store.Load()
	if err != nil {
		t.Fatalf("first run should not error: %v", err)
	}
	if st.HasData() {
		t.Fatalf("empty state should report no data: %+v", st)
	}
}

// Two paths can carry upstream text into what the Bridge stores and serves: an
// error message, and a free-text limit name. Both are masked on the way in, so
// no later call site has to remember to do it.
func TestUpstreamTextIsMaskedBeforeItIsStored(t *testing.T) {
	const jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.abcdefghijk_LMNOP"
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		goodReading,
		func() (Reading, error) { return Reading{}, errors.New("app server said: " + jwt) },
	}}
	svc := newService(t, f, 5*time.Minute, &at)
	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}

	at = at.Add(6 * time.Minute)
	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage after failure should still serve data: %v", err)
	}
	st, err := svc.store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if st.LastFailure == nil {
		t.Fatal("expected the failure to be recorded")
	}
	if redact.HasSecret(st.LastFailure.Message) {
		t.Fatalf("stored message still carries a credential: %q", st.LastFailure.Message)
	}
	if !strings.Contains(st.LastFailure.Message, "[redacted]") {
		t.Fatalf("stored message shows no sign of masking: %q", st.LastFailure.Message)
	}
	if !strings.Contains(st.LastFailure.Message, "app server said:") {
		t.Fatalf("the useful part of the message was lost: %q", st.LastFailure.Message)
	}
}

// A cold failure has nothing stored to fall back on, so its error is the only
// thing the caller gets. It used to be built from the *raw* upstream string with
// the class only embedded in the text - a credential that never met the mask, and
// a class nobody could read as a field (docs/PHASE-5-REVIEW.md §1 and §2). The
// error must be the same masked failure that went to disk.
func TestColdFailureReturnsTheMaskedFailureItStored(t *testing.T) {
	const jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.abcdefghijk_LMNOP"
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		func() (Reading, error) { return Reading{}, errors.New("app server said: " + jwt) },
	}}
	svc := newService(t, f, 5*time.Minute, &at)

	_, err := svc.Usage(true)
	if err == nil {
		t.Fatal("with nothing cached, a failed read must be an error")
	}
	var failure FailureError
	if !errors.As(err, &failure) {
		t.Fatalf("Usage returned %T (%v); want a FailureError whose class the caller can read", err, err)
	}
	if failure.Class != ClassUnknown {
		t.Errorf("class = %q, want %q", failure.Class, ClassUnknown)
	}
	if redact.HasSecret(failure.Message) || redact.HasSecret(err.Error()) {
		t.Fatalf("the returned error still carries the credential: %q", err)
	}
	if !strings.Contains(err.Error(), "[redacted]") {
		t.Fatalf("nothing was masked on the way out: %q", err)
	}

	st, err := svc.store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if st.LastFailure == nil {
		t.Fatal("the cold failure was not recorded at all")
	}
	if st.LastFailure.Class != failure.Class || st.LastFailure.Message != failure.Message {
		t.Fatalf("served %q/%q but stored %q/%q - two copies of this message can diverge",
			failure.Class, failure.Message, st.LastFailure.Class, st.LastFailure.Message)
	}
}

func TestLimitNameIsMaskedInServedWindows(t *testing.T) {
	const jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxImV9.abcdefghijk_LMNOPqrstu"
	poisoned := strings.Replace(realPayload, `"limitId": "codex",`, `"limitId": "codex", "limitName": "`+jwt+`",`, 1)
	f := &fakeFetch{results: []func() (Reading, error){
		func() (Reading, error) {
			snap, err := codex.ParseRateLimits([]byte(poisoned))
			if err != nil {
				panic(err)
			}
			return Reading{Snapshot: snap}, nil
		},
	}}
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	svc := newService(t, f, 5*time.Minute, &at)

	view, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("Usage: %v", err)
	}
	for _, w := range view.State.Windows {
		if redact.HasSecret(w.Label) {
			t.Fatalf("served label carries a credential: %q", w.Label)
		}
	}
	body, err := json.Marshal(view)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	// The fingerprint prefix is kept on purpose; the secret tail must not be.
	if strings.Contains(string(body), jwt[10:]) {
		t.Fatalf("serialised view still carries the token: %s", body)
	}
}

// Spec §53 rule 19 wants "the computer is offline" and "the login expired" to be
// tellable apart. Expired login reaches the Bridge as a refused reverse request
// plus a dead stream, so the two must not collapse into one class - while the
// numbers on screen stay put either way.
func TestRefusedRefreshIsAuthRequiredAndKeepsWindows(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		goodReading,
		func() (Reading, error) {
			return Reading{RefusedServerRequests: []string{"account/chatgptAuthTokens/refresh"}}, codex.ErrClosed
		},
	}}
	svc := newService(t, f, 5*time.Minute, &at)
	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}

	at = at.Add(6 * time.Minute)
	view, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("a login problem must still serve the last good numbers: %v", err)
	}
	if view.Degraded == nil || view.Degraded.Class != ClassAuthRequired {
		t.Fatalf("degraded = %+v, want %s", view.Degraded, ClassAuthRequired)
	}
	if len(view.State.Windows) != 2 {
		t.Fatalf("windows lost: %+v", view.State.Windows)
	}

	// And the offline case, from the same code path, stays a different class.
	offline := &fakeFetch{results: []func() (Reading, error){
		goodReading,
		func() (Reading, error) { return Reading{}, codex.ErrTimeout },
	}}
	svc2 := newService(t, offline, 5*time.Minute, &at)
	if _, err := svc2.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}
	at = at.Add(6 * time.Minute)
	view2, err := svc2.Usage(false)
	if err != nil {
		t.Fatalf("Usage: %v", err)
	}
	if view2.Degraded == nil || view2.Degraded.Class != ClassTimeout {
		t.Fatalf("degraded = %+v, want %s", view2.Degraded, ClassTimeout)
	}
	if view2.Degraded.Class == view.Degraded.Class {
		t.Fatal("offline and expired login collapsed into one class")
	}
}

// A server that answers the handshake but fails the read is not an absent
// server. The version it reported must survive the failure, because that is what
// tells a later reader which build the Bridge was talking to.
func TestVersionSurvivesAFailedRead(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		func() (Reading, error) {
			return Reading{CodexVersion: "0.121.0"}, codex.ErrClosed
		},
	}}
	svc := newService(t, f, 5*time.Minute, &at)

	if _, err := svc.Usage(false); err == nil {
		t.Fatal("with no data the failure must be reported")
	}
	st, err := svc.store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if st.CodexVersion != "0.121.0" {
		t.Fatalf("codexVersion = %q, want the handshake value kept", st.CodexVersion)
	}
	if st.LastFailure == nil || st.LastFailure.Class != ClassTimeout {
		t.Fatalf("lastFailure = %+v", st.LastFailure)
	}
}

// The capability claim is only as good as the classification behind it: a server
// that says "no such method" must be recorded as unavailable, not as offline,
// and the previous numbers must still be there.
func TestMethodUnavailableKeepsEarlierWindows(t *testing.T) {
	at := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	f := &fakeFetch{results: []func() (Reading, error){
		goodReading,
		func() (Reading, error) {
			return Reading{CodexVersion: "0.99.0"}, codedErr{-32601}
		},
	}}
	svc := newService(t, f, 5*time.Minute, &at)
	if _, err := svc.Usage(false); err != nil {
		t.Fatalf("Usage: %v", err)
	}

	at = at.Add(6 * time.Minute)
	view, err := svc.Usage(false)
	if err != nil {
		t.Fatalf("Usage: %v", err)
	}
	if view.Degraded == nil || view.Degraded.Class != ClassMethodUnavailable {
		t.Fatalf("degraded = %+v, want %s", view.Degraded, ClassMethodUnavailable)
	}
	if len(view.State.Windows) != 2 {
		t.Fatalf("windows were dropped: %+v", view.State.Windows)
	}
	if view.State.CodexVersion != "0.99.0" {
		t.Fatalf("codexVersion = %q, want the newest handshake value", view.State.CodexVersion)
	}
}
