package bridge

import (
	"errors"
	"sync"
	"time"

	"aiusage.local/bridge/internal/codex"
	"aiusage.local/bridge/internal/discover"
	"aiusage.local/bridge/internal/redact"
)

// Error classes. Spec §53 rule 19 requires "the computer is offline" and "the
// authorisation expired" to be distinguishable, so they are separate values
// rather than one error string.
const (
	ClassNotFound          = "CODEX_NOT_FOUND"
	ClassMethodUnavailable = "CODEX_METHOD_UNAVAILABLE"
	ClassAuthRequired      = "CODEX_AUTH_REQUIRED"
	ClassTimeout           = "CODEX_TIMEOUT"
	// ClassUnknown is the fifth class, not in the plan's four: an error we
	// cannot name must not be dressed up as one we can.
	ClassUnknown = "CODEX_UNKNOWN"
)

// FailureError is what Usage returns when there is nothing at all to serve: the
// refresh failed and no older reading exists to fall back on.
//
// It carries the class as a field rather than only as a prefix in a string
// because the HTTP layer has to name the class to the caller, and an empty View
// gives it nowhere to read one from - which is how every cold failure ended up
// reported as CODEX_UNKNOWN (docs/PHASE-5-REVIEW.md §2). The message is the one
// that was *stored*, already masked: a second copy of the upstream string is a
// second chance to leak it (§1).
type FailureError struct {
	Class   string
	Message string
}

func (e FailureError) Error() string { return e.Class + ": " + e.Message }

// refreshRequestMethod is the request Codex sends when it wants the client to
// supply refreshed ChatGPT tokens. Refusing it is what an expired login looks
// like from the Bridge's point of view.
const refreshRequestMethod = "account/chatgptAuthTokens/refresh"

// Reading is one trip to Codex.
type Reading struct {
	Snapshot codex.Snapshot
	// CodexVersion is what the app server reported at handshake. It is filled in
	// even when the read itself fails, because "the server answered us at all" is
	// information the health endpoint should keep.
	CodexVersion string
	// RefusedServerRequests lists what Codex asked us for and we declined.
	RefusedServerRequests []string
}

// Fetcher gets a fresh reading. Split out so the cache and the failure rules can
// be tested without a Codex installation.
type Fetcher interface {
	Fetch() (Reading, error)
}

// View is what the HTTP layer serves.
type View struct {
	State  State  `json:"state"`
	Source string `json:"source"`
	// DataTimestamp is when this payload's numbers were last confirmed by Codex.
	DataTimestamp time.Time `json:"dataTimestamp"`
	// SourceTimestamp is Codex's own time for those numbers; it is the same
	// instant here because the rate-limits payload carries no timestamp field.
	SourceTimestamp time.Time `json:"sourceTimestamp"`
	FromCache       bool      `json:"fromCache"`
	// Degraded is set when the refresh failed but older numbers are still being
	// served. It is not an HTTP error; the caller decides how loud to be.
	Degraded *Failure `json:"degraded,omitempty"`
}

// Service caches readings, persists them, and never lets a failure erase the
// last good numbers.
type Service struct {
	fetch Fetcher
	store *Store
	ttl   time.Duration
	// now is injected so cache-age tests do not sleep for five minutes.
	now func() time.Time
	// tx holds one refresh transaction - load, fetch, save - end to end.
	//
	// Without it a request that loaded an empty state, waited in Codex, and then
	// failed wrote back the state it had read *before* a concurrent request's
	// success landed, erasing numbers that had already been saved
	// (docs/PHASE-5-REVIEW.md §3). That is rule 18 lost through an interleaving
	// rather than through a wrong branch, so no single-path test could see it.
	// The cache-hit path takes the lock too: it is a read of the same file, and
	// queueing behind a fetch is cheaper than reasoning about a torn one.
	tx sync.Mutex
}

// NewService wires the pieces. A ttl of 0 means "always fetch".
func NewService(fetch Fetcher, store *Store, ttl time.Duration) *Service {
	return &Service{fetch: fetch, store: store, ttl: ttl, now: time.Now}
}

// Usage returns the newest numbers available. When force is false and the
// cached reading is younger than the TTL, Codex is not contacted at all
// (Spec L1792-1806); force ignores the cache.
//
// It returns an error only when there is nothing to serve at all. A failed
// refresh with older numbers is reported through View.Degraded.
func (s *Service) Usage(force bool) (View, error) {
	s.tx.Lock()
	defer s.tx.Unlock()

	state, err := s.store.Load()
	if err != nil {
		// An unreadable state file must not be treated as empty: the previous
		// numbers may still be on disk and overwriting them is the loss we are
		// guarding against. Report and stop.
		return View{}, err
	}

	if !force && state.HasData() && state.Age(s.now()) <= s.ttl {
		return s.view(state, "cache"), nil
	}

	reading, fetchErr := s.fetch.Fetch()
	if fetchErr != nil {
		class, detail := classify(fetchErr, reading.RefusedServerRequests)
		// The message is text produced upstream - an app-server error string -
		// and it is stored and served, so it goes through the mask on the way in
		// rather than on the way out. One call site cannot be forgotten later.
		failure := &Failure{Class: class, Message: redact.Text(detail), At: s.now()}
		state.LastFailure = failure
		if reading.CodexVersion != "" {
			state.CodexVersion = reading.CodexVersion
		}
		if err := s.store.Save(state); err != nil {
			return View{}, err
		}
		if !state.HasData() {
			return View{}, FailureError{Class: failure.Class, Message: failure.Message}
		}
		return s.view(state, "cache"), nil
	}

	state.CodexVersion = reading.CodexVersion
	state.PlanType = reading.Snapshot.PlanType
	state.CreditsBalance = reading.Snapshot.CreditsBalance
	state.Windows = publicWindows(reading.Snapshot.Windows)
	state.SourceFetchedAt = s.now()
	state.LastFailure = nil
	if err := s.store.Save(state); err != nil {
		return View{}, err
	}
	return s.view(state, "codex"), nil
}

func (s *Service) view(state State, source string) View {
	v := View{
		State:           state,
		Source:          source,
		DataTimestamp:   state.SourceFetchedAt,
		SourceTimestamp: state.SourceFetchedAt,
		FromCache:       source == "cache",
		Degraded:        state.LastFailure,
	}
	return v
}

// classify maps a failure onto one of the classes the user gets to see.
func classify(err error, refused []string) (string, string) {
	switch {
	case contains(refused, refreshRequestMethod):
		return ClassAuthRequired, "Codex asked this client to refresh its login tokens, and the Bridge never handles tokens"
	case errors.Is(err, discover.ErrNotFound):
		return ClassNotFound, err.Error()
	case errors.Is(err, codex.ErrTimeout), errors.Is(err, codex.ErrClosed):
		return ClassTimeout, err.Error()
	default:
		if codex.IsMethodNotFound(err) {
			return ClassMethodUnavailable, err.Error()
		}
		return ClassUnknown, err.Error()
	}
}

func contains(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

// CodexFetcher is the production Fetcher: discover Codex, talk to its app
// server, decode the answer.
type CodexFetcher struct {
	// ExplicitPath is the --codex value; empty means "search for it".
	ExplicitPath string
	Timeout      time.Duration
}

// Fetch reads one quota snapshot from a Codex app server.
//
// The named results exist for one reason: the refusals have to travel with every
// path out of here, not only with the successful one. Codex asks for refreshed
// login tokens *before* its own read fails or never answers at all, and a failure
// that arrives without that record gets classified as a generic error instead of
// CODEX_AUTH_REQUIRED (docs/PHASE-5-REVIEW.md §4).
func (f *CodexFetcher) Fetch() (reading Reading, err error) {
	cand, err := discover.Find(f.ExplicitPath, discover.System())
	if err != nil {
		return Reading{}, err
	}
	c := codex.NewClient(codex.Command{Path: cand.Path, Args: []string{"app-server"}}, f.Timeout)
	defer c.Close()
	defer func() { reading.RefusedServerRequests = c.RefusedRequests() }()

	init, err := c.Initialize()
	if err != nil {
		return Reading{}, err
	}
	reading.CodexVersion = codex.VersionFromUserAgent(init.UserAgent)
	raw, err := c.Call("account/rateLimits/read", map[string]any{})
	if err != nil {
		// The version travels with the failure: a server that answered the
		// handshake is not an absent server.
		return reading, err
	}
	snap, err := codex.ParseRateLimits(raw)
	if err != nil {
		return reading, err
	}
	reading.Snapshot = snap
	return reading, nil
}

// Latest reports the stored state without contacting Codex. The health endpoint
// uses it so a liveness check never spawns an app server.
func (s *Service) Latest() (State, error) { return s.store.Load() }
