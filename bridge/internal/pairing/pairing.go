// Package pairing turns a one-time introduction into a long-lived device.
//
// Spec §20 hands the phone a short-lived Pair Token and §21 exchanges it for a
// Device Token that outlives it. Everything here rests on one rule: the server
// never needs to read those tokens again, so it never stores them - only digests.
// A copied pairing.json can therefore neither be replayed against the Bridge nor
// read as a credential (docs/PHASE-7-PLAN.md A3/A4, §4 rule 4).
//
// Tokens are hex so that a phone typing one cannot introduce a case difference,
// and every entry point normalises before hashing: a lookup keyed on an
// un-normalised secret would reject a legitimate device, and rejecting a real
// pairing is the kind of bug that gets "fixed" by loosening the check.
package pairing

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"strings"
	"sync"
	"time"

	"aiusage.local/bridge/internal/filestore"
)

const (
	// DefaultTTL is how long an issued pairing stays exchangeable. Spec §20 L839-842
	// asks for "short-lived" and "invalid immediately after success"; 120 seconds is
	// long enough to unlock a phone and walk through one dialog.
	DefaultTTL = 120 * time.Second

	// MaxFailedExchanges burns every outstanding pairing after this many wrong
	// attempts. A short code is ~40 bits, which is not a secret worth an unlimited
	// number of guesses: the cap is the honest mitigation, and the QR channel (a
	// full 256-bit token) is the recommended path.
	MaxFailedExchanges = 20

	// Alphabet for human-typed codes, without the characters that get
	// transcribed differently over a phone or a screen (0/o, 1/l/i, 2/z).
	codeAlphabet = "abcdefghjkmnpqrstuvwxyz3456789"
	codeChars    = 8

	tokenBytes = 32
)

// Errors the HTTP layer maps onto responses. ErrUnknownSecret is what an outsider
// gets; the distinct ones stay distinct for the operator's own diagnosis and for
// tests, and the server must not turn them into a richer answer for someone who
// has no credential to begin with (docs/PHASE-7-PLAN.md §5.1).
var (
	ErrUnknownSecret = errors.New("unknown pairing code")
	ErrExpired       = errors.New("the pairing code expired")
	ErrUsed          = errors.New("the pairing code was already used")
	ErrLockedOut     = errors.New("too many failed pairing attempts; add the device again on the computer")
	ErrUnknownDevice = errors.New("no such device token")
	// ErrNotPersisted wraps a write failure: the request was understood, but the
	// result cannot be trusted to survive.
	ErrNotPersisted = errors.New("the pairing state could not be written")
	ErrRevoked      = errors.New("this device's pairing was revoked")
)

// Device is one paired phone. Name is what the operator typed or the phone
// reported; it is display text and never an authority.
type Device struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	TokenHash  string    `json:"tokenHash"`
	CreatedAt  time.Time `json:"createdAt"`
	LastSeenAt time.Time `json:"lastSeenAt"`
	Revoked    bool      `json:"revoked,omitempty"`
}

// Pairing is an outstanding introduction. Both halves are stored as digests: the
// long token goes into the QR payload, the short code is what a person reads off
// the screen and types on the phone (docs/PHASE-7-PLAN.md A7).
type Pairing struct {
	TokenHash string    `json:"tokenHash"`
	CodeHash  string    `json:"codeHash"`
	ExpiresAt time.Time `json:"expiresAt"`
	Consumed  bool      `json:"consumed,omitempty"`
	DeviceID  string    `json:"deviceId,omitempty"`
}

type persisted struct {
	Devices        []Device  `json:"devices"`
	Pairings       []Pairing `json:"pairings"`
	FailedAttempts int       `json:"failedAttempts"`
}

// Store holds the device registry and the outstanding pairings, persisted to one
// 0600 JSON file. Every method takes the lock: the HTTP server runs one goroutine
// per request, and the Phase 5 review's §3 finding - a late writer committing a
// state it read earlier - applies just as much here as it did to the quota cache.
type Store struct {
	mu    sync.Mutex
	path  string
	state persisted
	// refused is set when a guessing attempt could not be persisted. It is
	// deliberately in memory only: the alternative is to keep exchanging while the
	// disk refuses to record that the codes were burned.
	refused bool
	// now is injected so expiry tests do not sleep for two minutes.
	now func() time.Time
}

// NewStore loads path, treating a *missing* file as an empty registry: first run
// is normal. Anything else that is not usable is reported rather than discarded,
// because starting over silently revokes every paired phone and the phone reads
// that as a network problem. That includes a zero-length file: a truncation or a
// half-restored backup is exactly what a "treat empty as new" rule mistakes for a
// clean slate (docs/PHASE-7-REVIEW.md P2).
func NewStore(path string) (*Store, error) {
	s := &Store{path: path, now: time.Now}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("reading %s: %w", path, err)
	}
	if len(data) == 0 {
		return nil, fmt.Errorf("pairing store %s is empty; refusing to start with no devices when the alternative is that a paired phone simply stops working", path)
	}
	if err := json.Unmarshal(data, &s.state); err != nil {
		return nil, fmt.Errorf("pairing store %s is not valid JSON: %w", path, err)
	}
	return s, nil
}

// Issue opens a new introduction and returns the plaintext halves exactly once -
// the store keeps only their digests. Any earlier outstanding pairing is dropped:
// one QR on screen at a time is what the operator can reason about, and a stale
// code that still works is a code nobody intended to leave open.
// commit writes a candidate registry and adopts it only after the bytes are on
// disk. Every mutation goes through it, because the alternative - change the
// memory, then try to save - leaves a Bridge that believes a device is revoked
// while the file still says it is not, so the next restart hands the token back.
// A failed commit is therefore "nothing happened", which is also what the caller
// is told.
func (s *Store) commit(next persisted) error {
	data, err := json.MarshalIndent(next, "", "  ")
	if err != nil {
		return fmt.Errorf("encoding the pairing store: %w", err)
	}
	if err := filestore.Write(s.path, data, 0o600); err != nil {
		return err
	}
	s.state = next
	return nil
}

// copyState snapshots the slices so a candidate can be built without touching
// what is live until commit succeeds. Device and Pairing are flat value structs,
// so copying the slices is a deep enough copy.
func copyState(p persisted) persisted {
	return persisted{
		Devices:        append([]Device(nil), p.Devices...),
		Pairings:       append([]Pairing(nil), p.Pairings...),
		FailedAttempts: p.FailedAttempts,
	}
}

func (s *Store) Issue(ttl time.Duration) (token string, code string, expiresAt time.Time, err error) {
	if ttl <= 0 {
		ttl = DefaultTTL
	}
	token, err = randomHex(tokenBytes)
	if err != nil {
		return "", "", time.Time{}, err
	}
	code, err = randomCode(codeChars)
	if err != nil {
		return "", "", time.Time{}, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	at := s.now().Add(ttl)
	// One live pairing at a time: an earlier code that nobody typed is not
	// something the operator intended to leave open. Recently consumed ones are
	// kept for one cycle so a phone replaying the code it just used hears
	// "already used" rather than the vaguer "unknown".
	kept := make([]Pairing, 0, 2)
	next := copyState(s.state)
	for _, p := range next.Pairings {
		if p.Consumed && p.ExpiresAt.After(at.Add(-ttl)) {
			kept = append(kept, p)
		}
	}
	next.Pairings = append(kept, Pairing{
		TokenHash: digest(normalize(token)),
		CodeHash:  digest(normalize(code)),
		ExpiresAt: at,
	})
	next.FailedAttempts = 0
	if err := s.commit(next); err != nil {
		return "", "", time.Time{}, err
	}
	// A fresh issue lifts the local refusal from an earlier un-persistable guessing
	// attempt - but only once that issue is what the disk holds. Clearing the flag
	// before the write meant an issuance that failed left the old, still-live code
	// exchangeable again, which is the state the refusal exists to prevent
	// (docs/PHASE-0-7-REVIEW.md §2.5).
	s.refused = false
	return token, code, at, nil
}

// Exchange trades a presented secret for a device. It returns the device and the
// plaintext token once; the store keeps only the digest, so this is the moment the
// phone must save it (Spec §21).
func (s *Store) Exchange(secret, deviceName string) (Device, string, error) {
	presented := normalize(secret)
	if presented == "" {
		return Device{}, "", ErrUnknownSecret
	}
	// Looked up by digest, never by the text itself: the store holds digests, and
	// a comparison against them would make every legitimate phone hear
	// "unknown pairing code" while leaving the file useless as a credential.
	presentedHash := digest(presented)
	token, err := randomHex(tokenBytes)
	if err != nil {
		return Device{}, "", err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	index := -1
	for i, p := range s.state.Pairings {
		if p.TokenHash == presentedHash || p.CodeHash == presentedHash {
			index = i
			break
		}
	}
	if s.refused {
		// The last guessing attempt could not be persisted; see Store.refused.
		return Device{}, "", ErrLockedOut
	}
	if index < 0 {
		return Device{}, "", s.rejectGuess(ErrUnknownSecret)
	}
	pairing := s.state.Pairings[index]
	switch {
	case pairing.Consumed:
		return Device{}, "", s.rejectGuess(ErrUsed)
	case !pairing.ExpiresAt.After(s.now()):
		return Device{}, "", s.rejectGuess(ErrExpired)
	}

	next := copyState(s.state)
	device := Device{
		ID:         newDeviceID(),
		Name:       cleanName(deviceName),
		TokenHash:  digest(token),
		CreatedAt:  s.now(),
		LastSeenAt: s.now(),
	}
	next.Devices = append(next.Devices, device)
	next.Pairings[index].Consumed = true
	next.Pairings[index].DeviceID = device.ID
	// The record is good, so the attempt counter is no longer interesting.
	next.FailedAttempts = 0
	if err := s.commit(next); err != nil {
		return Device{}, "", err
	}
	return device, token, nil
}

// Validate answers whether a device token belongs to a live pairing.
func (s *Store) Validate(secret string) (Device, error) {
	presented := normalize(secret)
	if presented == "" {
		return Device{}, ErrUnknownDevice
	}
	presentedHash := digest(presented)
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.state.Devices {
		if d.TokenHash == presentedHash {
			if d.Revoked {
				return Device{}, ErrRevoked
			}
			return d, nil
		}
	}
	return Device{}, ErrUnknownDevice
}

// Touch records that a validated device was seen, for the device list. A failure
// to persist that is not a reason to deny the reading the device already earned,
// so the caller logs it and serves the data (see the server's authorize).
func (s *Store) Touch(deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := copyState(s.state)
	found := false
	for i := range next.Devices {
		if next.Devices[i].ID == deviceID {
			next.Devices[i].LastSeenAt = s.now()
			found = true
			break
		}
	}
	if !found {
		return ErrUnknownDevice
	}
	return s.commit(next)
}

// Revoke marks a device as no longer paired. The record is kept rather than
// deleted so the token that was revoked keeps answering "revoked" instead of
// "unknown": the phone can then say "re-pair on the computer" instead of
// "the computer is offline", which is a different problem with a different fix
// (Spec §53 rule 19 extended to pairing, docs/PHASE-7-PLAN.md §4 rule 6).
func (s *Store) Revoke(deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := copyState(s.state)
	found := false
	for i := range next.Devices {
		if next.Devices[i].ID == deviceID {
			next.Devices[i].Revoked = true
			found = true
			break
		}
	}
	if !found {
		return ErrUnknownDevice
	}
	// ErrNotPersisted distinguishes "there is no such device" from "this device
	// exists and the revocation could not be written", which the admin API has to
	// answer differently: one is a 404 for a typo, the other is a 503 for a
	// failure the operator has to know about because the device still works after
	// a restart (docs/PHASE-7-REVIEW.md P2).
	if err := s.commit(next); err != nil {
		return fmt.Errorf("%w: %v", ErrNotPersisted, err)
	}
	return nil
}

// List reports every device, revoked included, oldest first: the operator's
// device management screen needs to show what was revoked until it is cleared.
func (s *Store) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, len(s.state.Devices))
	copy(out, s.state.Devices)
	return out
}

// Active reports the devices that are not revoked, newest first - the count the
// health endpoint advertises.
func (s *Store) Active() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, d := range s.state.Devices {
		if !d.Revoked {
			n++
		}
	}
	return n
}

// Outstanding reports how many pairings can still be exchanged, so the UI can say
// "a pairing is open, it expires at …" instead of silently leaving one live.
func (s *Store) Outstanding() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	n := 0
	for _, p := range s.state.Pairings {
		if !p.Consumed && p.ExpiresAt.After(now) {
			n++
		}
	}
	return n
}

// rejectGuess counts a failed attempt against the store and persists what that
// counting did. The counter cannot live per record: a wrong guess matches nothing,
// so there is no record to attach it to, and an uncapped 40-bit short code is a
// guessable secret. Burning the outstanding offers is what makes the cap real, and
// persisting the burn is what stops a restart from handing the codes back
// (docs/PHASE-7-REVIEW.md P2).
func (s *Store) rejectGuess(reason error) error {
	next := copyState(s.state)
	next.FailedAttempts++
	burned := next.FailedAttempts >= MaxFailedExchanges
	if burned {
		live := make([]Pairing, 0, len(next.Pairings))
		for _, p := range next.Pairings {
			if p.Consumed {
				live = append(live, p)
			}
		}
		next.Pairings = live
		// Stay at the cap rather than resetting it: until a fresh code is issued on
		// the computer, everything presented here should hear "add the device
		// again", including the code that was live a moment ago.
		next.FailedAttempts = MaxFailedExchanges
	}
	if err := s.commit(next); err != nil {
		// The burn could not be written. Refuse exchanges anyway rather than
		// carrying on as if guessing were free; on restart the counter comes from
		// disk, so what remains open is bounded by the code's own TTL, which is
		// written down here rather than left implied.
		s.refused = true
		return err
	}
	if burned {
		// The cap is the more useful answer than the reason: whatever this code
		// was, nothing on the screen works any more.
		return ErrLockedOut
	}
	return reason
}

// normalize is deliberately not a general-purpose cleanup: trimming and folding
// case is what makes a hex token and a typed code match the digest that was stored,
// and nothing else is changed.
func normalize(secret string) string {
	return strings.ToLower(strings.TrimSpace(secret))
}

func digest(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

func randomHex(n int) (string, error) {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("reading randomness: %w", err)
	}
	return hex.EncodeToString(buf), nil
}

func randomCode(n int) (string, error) {
	// rand.Int rather than a byte modulo: 31 does not divide 256, and a biased
	// alphabet makes a 40-bit code measurably easier to guess than it looks.
	out := make([]byte, n)
	for i := range out {
		k, err := rand.Int(rand.Reader, big.NewInt(int64(len(codeAlphabet))))
		if err != nil {
			return "", fmt.Errorf("reading randomness: %w", err)
		}
		out[i] = codeAlphabet[k.Int64()]
	}
	return string(out), nil
}

func newDeviceID() string {
	var raw [8]byte
	if _, err := rand.Read(raw[:]); err != nil {
		panic(fmt.Sprintf("reading randomness for a device ID: %v", err))
	}
	return "dev_" + strings.ToLower(base32.HexEncoding.WithPadding(base32.NoPadding).EncodeToString(raw[:]))
}

// cleanName bounds what a phone can put into the operator's list. It is display
// text: the Bridge must not interpret it, and a 64-byte cap keeps a pathological
// client from filling the file.
func cleanName(name string) string {
	cleaned := strings.Join(strings.Fields(name), " ")
	if cleaned == "" {
		return "device"
	}
	if len(cleaned) > 64 {
		return cleaned[:64]
	}
	return cleaned
}
