// Package bridge turns a Codex reading into something that can be served:
// cached, timestamped, and never emptied by a failed refresh.
package bridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"aiusage.local/bridge/internal/codex"
	"aiusage.local/bridge/internal/redact"
)

// PublicWindow is the stable JSON shape of one quota meter. It exists so the
// wire struct is not also the external contract.
type PublicWindow struct {
	ID               string `json:"id"`
	Label            string `json:"label"`
	UsedPercent      int    `json:"usedPercent"`
	RemainingPercent int    `json:"remainingPercent"`
	WindowMinutes    int64  `json:"windowMinutes"`
	// ResetAtMillis is Unix milliseconds; 0 means Codex did not say.
	ResetAtMillis int64  `json:"resetAtMillis"`
	Kind          string `json:"kind"`
}

// Failure is the last refresh problem, kept alongside - not instead of - the
// last good windows (Spec §53 rule 18).
type Failure struct {
	Class   string    `json:"class"`
	Message string    `json:"message"`
	At      time.Time `json:"at"`
}

// State is what survives a restart of the Bridge.
type State struct {
	// CodexVersion is the app server's own reported version, kept so a caller can
	// tell which build produced these numbers (docs/PHASE-5-PLAN.md A7).
	CodexVersion   string         `json:"codexVersion,omitempty"`
	PlanType       string         `json:"planType"`
	CreditsBalance string         `json:"creditsBalance"`
	Windows        []PublicWindow `json:"windows"`
	// SourceFetchedAt is when Codex answered. Codex gives no timestamp of its
	// own in this payload, so "when we asked" is the only honest source time.
	SourceFetchedAt time.Time `json:"sourceFetchedAt"`
	LastFailure     *Failure  `json:"lastFailure,omitempty"`
}

// HasData reports whether there is anything worth serving.
func (s State) HasData() bool { return len(s.Windows) > 0 }

// Age of the stored reading, relative to now. A state that never fetched
// reports an unreachably large age so "older than the TTL" is the natural
// consequence instead of a special case at every call site.
func (s State) Age(now time.Time) time.Duration {
	if s.SourceFetchedAt.IsZero() {
		return time.Duration(1 << 62)
	}
	return now.Sub(s.SourceFetchedAt)
}

func publicWindows(ws []codex.Window) []PublicWindow {
	out := make([]PublicWindow, 0, len(ws))
	for _, w := range ws {
		out = append(out, PublicWindow{
			ID: w.ID,
			// limitName is free text from Codex; it is the one field that could
			// carry something credential-shaped into a served response.
			Label:            redact.Text(w.Label),
			UsedPercent:      w.UsedPercent,
			RemainingPercent: w.RemainingPercent,
			WindowMinutes:    w.WindowMinutes,
			ResetAtMillis:    w.ResetAtMillis,
			Kind:             w.Kind,
		})
	}
	return out
}

// Store persists State as a single JSON file.
type Store struct {
	path string
}

// NewStore points at the file to use. Tests pass a temporary path; the binary
// passes %LOCALAPPDATA%\AIUsageBridge\state.json.
func NewStore(path string) *Store { return &Store{path: path} }

// Load returns the stored state. A file that does not exist yet is an empty
// state, not an error: first run is normal. A file that exists but cannot be
// parsed is reported, because silently ignoring it would hide a real problem.
func (s *Store) Load() (State, error) {
	var st State
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return st, nil
	}
	if err != nil {
		return st, fmt.Errorf("reading %s: %w", s.path, err)
	}
	if len(data) == 0 {
		return st, nil
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return State{}, fmt.Errorf("state file %s is not valid JSON: %w", s.path, err)
	}
	return st, nil
}

// Save writes atomically enough for our purpose: a temporary file in the same
// directory, then a rename over the target. A half-written state file would look
// like "no data" to the next read, which is the failure we are trying to avoid.
//
// The temporary name is unique per call, not a fixed `path + ".tmp"`. Two writers
// sharing one temporary file write into each other's bytes and then race the
// rename; the Service mutex keeps this process honest, and the unique name keeps
// a second Bridge started against the same --data-dir from corrupting the first
// one's file (docs/PHASE-5-REVIEW.md §3).
func (s *Store) Save(st State) error {
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("creating directory for %s: %w", s.path, err)
	}
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return fmt.Errorf("encoding state: %w", err)
	}
	// CreateTemp is 0600 already, which is what this file needs: it holds one
	// user's account quota numbers.
	tmp, err := os.CreateTemp(dir, ".state-*.tmp")
	if err != nil {
		return fmt.Errorf("creating a temporary file in %s: %w", dir, err)
	}
	name := tmp.Name()
	// Nothing should survive this call: on every error path the half-written
	// temporary is removed, so a failed save cannot leave a file that a later
	// reader mistakes for state.
	defer func() { _ = os.Remove(name) }()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("writing %s: %w", name, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("closing %s: %w", name, err)
	}
	if err := os.Rename(name, s.path); err != nil {
		return fmt.Errorf("replacing %s: %w", s.path, err)
	}
	return nil
}
