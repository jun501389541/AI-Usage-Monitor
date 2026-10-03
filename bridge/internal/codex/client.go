package codex

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Command is how to start an app-server process.
type Command struct {
	Path string
	Args []string
	// Env is appended to the inherited environment. Tests use it to make the
	// test binary behave as a scripted app-server.
	Env []string
}

// Start launches the child process and binds a session to its stdio pipes.
//
// Frames are sent without a "jsonrpc" member because that is what was verified
// against codex-cli 0.121.0 (docs/PHASE-5-BRIDGE-FEASIBILITY.md §2.4): the server
// answers them. Conformances beyond that are not assumed.
func Start(c Command) (*Session, error) {
	cmd := exec.Command(c.Path, c.Args...)
	cmd.Env = append(os.Environ(), c.Env...)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("stdout pipe: %w", err)
	}
	// Stderr is left attached to the parent so a crashing child is still visible
	// in the Bridge's own log; swallowing it would hide the reason for a death.
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("starting %s: %w", c.Path, err)
	}

	return NewSession(stdin, stdout, func() error {
		// Wait is called even when Kill fails, because the kill error is usually
		// "process already finished" and the zombie still needs reaping.
		killErr := cmd.Process.Kill()
		_ = cmd.Wait()
		return killErr
	}), nil
}

// errNotInitialized guards calls that arrive before the handshake.
var errNotInitialized = errors.New("Initialize must be called first")

// clientInfo names this Bridge to the app server. The version is deliberately
// static: Codex reports its own version back in `userAgent`, and that is the
// number step 6 probes.
var clientInfo = map[string]any{
	"clientInfo": map[string]any{
		"name":    "aiusage-bridge",
		"version": "0.1.0",
	},
	"capabilities": map[string]any{"experimentalApi": false},
}

// InitResult is the subset of the initialize reply the Bridge uses.
type InitResult struct {
	UserAgent string `json:"userAgent"`
	CodexHome string `json:"codexHome"`
}

// Client is an initialized conversation with one app-server process.
type Client struct {
	cmd     Command
	timeout time.Duration
	start   func(Command) (*Session, error)

	session *Session

	muRefused sync.Mutex
	refused   []string
}

// NewClient returns a client that will start c for each session.
func NewClient(c Command, timeout time.Duration) *Client {
	return &Client{cmd: c, timeout: timeout, start: Start}
}

// Initialize starts the child and completes the handshake.
func (c *Client) Initialize() (InitResult, error) {
	if c.session != nil {
		return InitResult{}, fmt.Errorf("already initialized")
	}
	s, err := c.start(c.cmd)
	if err != nil {
		return InitResult{}, err
	}
	s.refusedRequest = func(method string) { c.recordRefusal(method) }
	c.session = s

	raw, err := s.Call("initialize", clientInfo, c.timeout)
	if err != nil {
		c.Close()
		return InitResult{}, err
	}
	var out InitResult
	if err := json.Unmarshal(raw, &out); err != nil {
		c.Close()
		return InitResult{}, fmt.Errorf("decoding initialize result: %w", err)
	}
	// The handshake is only complete once the server knows we are ready; without
	// this frame the server holds its result stream open.
	if err := s.Notify("initialized", nil); err != nil {
		c.Close()
		return InitResult{}, err
	}
	return out, nil
}

// Call runs one request after the handshake.
func (c *Client) Call(method string, params any) (json.RawMessage, error) {
	if c.session == nil {
		return nil, errNotInitialized
	}
	return c.session.Call(method, params, c.timeout)
}

// RefusedRequests reports the requests Codex sent to us that we declined.
func (c *Client) RefusedRequests() []string {
	c.muRefused.Lock()
	defer c.muRefused.Unlock()
	return append([]string(nil), c.refused...)
}

func (c *Client) recordRefusal(method string) {
	c.muRefused.Lock()
	defer c.muRefused.Unlock()
	c.refused = append(c.refused, method)
}

// Close terminates the child.
func (c *Client) Close() error {
	s := c.session
	c.session = nil
	if s == nil {
		return nil
	}
	return s.Close()
}

// VersionFromUserAgent pulls the Codex version out of the userAgent that
// initialize echoes back, e.g. "aiusage-bridge/0.121.0 (Windows ...)" -> "0.121.0".
// An unrecognised shape yields "" rather than a guess, because the version is
// reported to callers as a fact.
func VersionFromUserAgent(userAgent string) string {
	slash := strings.Index(userAgent, "/")
	if slash < 0 {
		return ""
	}
	rest := userAgent[slash+1:]
	end := strings.IndexAny(rest, " (")
	if end <= 0 {
		end = len(rest)
	}
	candidate := rest[:end]
	if !looksLikeVersion(candidate) {
		return ""
	}
	return candidate
}

func looksLikeVersion(v string) bool {
	dots := strings.Count(v, ".")
	if dots < 1 || v == "" {
		return false
	}
	for _, part := range strings.Split(v, ".") {
		if part == "" {
			return false
		}
		for _, r := range part {
			if r < '0' || r > '9' {
				return false
			}
		}
	}
	return true
}
