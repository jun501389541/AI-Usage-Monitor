package codex

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"testing"
	"time"
)

// fakeEnvKey makes the test binary behave as a scripted app-server when it is
// re-executed by the subprocess tests. This keeps the real os/exec + pipe path
// under test without spawning a real Codex, which would depend on this machine's
// login and could disturb it.
const fakeEnvKey = "AIUSAGE_FAKE_APPSERVER"

// goldenRateLimits is the response recorded from a real `account/rateLimits/read`
// on this machine (docs/PHASE-5-BRIDGE-FEASIBILITY.md §2.4), reproduced verbatim so
// the fake speaks the same dialect as the real thing.
const goldenRateLimits = `{
  "rateLimits": {
    "limitId": "codex",
    "limitName": null,
    "primary":   { "usedPercent": 100, "windowDurationMins": 300,   "resetsAt": 1790936545 },
    "secondary": { "usedPercent": 40,  "windowDurationMins": 10080, "resetsAt": 1791419454 },
    "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
    "planType":  "plus"
  },
  "rateLimitsByLimitId": {
    "codex": {
      "limitId": "codex",
      "primary":   { "usedPercent": 100, "windowDurationMins": 300,   "resetsAt": 1790936545 },
      "secondary": { "usedPercent": 40,  "windowDurationMins": 10080, "resetsAt": 1791419454 },
      "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
      "planType":  "plus"
    }
  }
}`

func TestMain(m *testing.M) {
	if scenario := os.Getenv(fakeEnvKey); scenario != "" {
		runFakeAppServer(scenario)
		return
	}
	os.Exit(m.Run())
}

type fakeFrame struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
}

// runFakeAppServer answers the handshake and then behaves per scenario. It exits
// when stdin closes, which is how a killed parent tears it down.
func runFakeAppServer(scenario string) {
	dec := json.NewDecoder(os.Stdin)
	enc := json.NewEncoder(os.Stdout)

	handshakeDone := false
	sentRequest := false

	for {
		var f fakeFrame
		if err := dec.Decode(&f); err != nil {
			return
		}
		switch f.Method {
		case "initialize":
			enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "result": map[string]any{
				"userAgent": "codex-cli/0.121.0 (Windows 10.0.26220; x86_64) fake",
				"codexHome": `C:\fake\.codex`,
			}})
		case "initialized":
			handshakeDone = true
		case "account/rateLimits/read":
			if !handshakeDone {
				// Proves the client completed the handshake before asking anything
				// else; a client that skips `initialized` gets this instead.
				enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "error": map[string]any{
					"code": -32001, "message": "handshake incomplete",
				}})
				return
			}
			switch scenario {
			case "method_error":
				enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "error": map[string]any{
					"code": -32601, "message": "method not found",
				}})
			case "reverse":
				// Codex asks US for something. The Bridge must refuse, and the
				// refusal must reach us before the answer goes out.
				if !sentRequest {
					sentRequest = true
					enc.Encode(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(`99`),
						"method": "account/chatgptAuthTokens/refresh", "params": map[string]any{}})
					var ack map[string]any
					_ = dec.Decode(&ack)
					refused := false
					if e, ok := ack["error"].(map[string]any); ok {
						refused = e["code"] == float64(-32601)
					}
					result := json.RawMessage(goldenRateLimits[:len(goldenRateLimits)-1] + `,"sawRefusal":` +
						jsonString(refused) + `}`)
					enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "result": result})
				}
			default:
				enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "result": json.RawMessage(goldenRateLimits)})
			}
		}
	}
}

func jsonString(v bool) string {
	if v {
		return "true"
	}
	return "false"
}

// fakeCommand re-executes this test binary as the app-server.
func fakeCommand(t *testing.T, scenario string) Command {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}
	return Command{Path: exe, Env: []string{fakeEnvKey + "=" + scenario}}
}

func TestHandshakeThenCallOverRealPipes(t *testing.T) {
	c := NewClient(fakeCommand(t, "ok"), 5*time.Second)
	defer c.Close()

	init, err := c.Initialize()
	if err != nil {
		t.Fatalf("Initialize: %v", err)
	}
	if init.UserAgent == "" || init.CodexHome == "" {
		t.Fatalf("initialize result missing fields: %+v", init)
	}

	raw, err := c.Call("account/rateLimits/read", map[string]any{})
	if err != nil {
		t.Fatalf("Call: %v", err)
	}
	// "handshake incomplete" from the fake would mean the client never sent the
	// `initialized` notification before asking for data.
	snap, err := ParseRateLimits(raw)
	if err != nil {
		t.Fatalf("the payload the client fetched did not parse: %v (%s)", err, raw)
	}
	if len(snap.Windows) != 2 || snap.PlanType != "plus" {
		t.Fatalf("snapshot = %+v, want two windows on plan plus", snap)
	}
	if got := snap.Windows[0].ResetAtMillis; got < 1_000_000_000_000 {
		t.Fatalf("reset millis = %d, still looks like seconds", got)
	}
}

func TestServerErrorBecomesTypedRPCError(t *testing.T) {
	c := NewClient(fakeCommand(t, "method_error"), 5*time.Second)
	defer c.Close()
	if _, err := c.Initialize(); err != nil {
		t.Fatalf("Initialize: %v", err)
	}

	_, err := c.Call("account/rateLimits/read", map[string]any{})
	var rerr *rpcError
	if !errors.As(err, &rerr) {
		t.Fatalf("err = %v (%T), want *rpcError", err, err)
	}
	if rerr.Code != -32601 {
		t.Fatalf("code = %d, want -32601", rerr.Code)
	}
	// The Bridge reads this as "this Codex build has no such method", which is a
	// capability claim rather than an outage.
	if !IsMethodNotFound(err) {
		t.Fatalf("a wire -32601 should be recognised as method-not-found, got %v", err)
	}
	if IsMethodNotFound(ErrTimeout) {
		t.Fatal("a timeout must not read as a capability claim")
	}
}

// The Bridge never serves Codex's reverse requests, but it must answer them: an
// unanswered request blocks the child, and the quota read would hang instead of
// failing. This is the same wire path the real app-server uses.
func TestReverseRequestIsRefusedAndReadContinues(t *testing.T) {
	c := NewClient(fakeCommand(t, "reverse"), 5*time.Second)
	defer c.Close()
	if _, err := c.Initialize(); err != nil {
		t.Fatalf("Initialize: %v", err)
	}

	raw, err := c.Call("account/rateLimits/read", map[string]any{})
	if err != nil {
		t.Fatalf("Call after a reverse request: %v", err)
	}
	if !strings.Contains(string(raw), `"sawRefusal":true`) {
		t.Fatalf("fake did not see a -32601 refusal, got: %s", raw)
	}
	refused := c.RefusedRequests()
	if len(refused) != 1 || refused[0] != "account/chatgptAuthTokens/refresh" {
		t.Fatalf("refused = %v, want the refresh method recorded once", refused)
	}
}

// --- in-process tests: framing, timeouts and kill accounting without a
// subprocess, so a hang cannot be blamed on the OS.
//
// Each pipe is named for who uses the ends: `toPeer` is the session's stdin,
// `fromPeer` is the session's stdout.

func TestCallTimeoutClosesAndKills(t *testing.T) {
	toPeerR, toPeerW := io.Pipe()
	fromPeerR, fromPeerW := io.Pipe()

	kills := make(chan struct{}, 4)
	s := NewSession(toPeerW, fromPeerR, func() error {
		kills <- struct{}{}
		return nil
	})
	defer toPeerR.Close()
	defer fromPeerW.Close()

	// The peer consumes every frame and answers nothing.
	go func() {
		dec := json.NewDecoder(toPeerR)
		for {
			var f map[string]any
			if err := dec.Decode(&f); err != nil {
				return
			}
		}
	}()

	start := time.Now()
	_, err := s.Call("account/rateLimits/read", map[string]any{}, 60*time.Millisecond)
	if !errors.Is(err, ErrTimeout) {
		t.Fatalf("err = %v, want ErrTimeout", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("timeout took %v, should return promptly", elapsed)
	}
	select {
	case <-kills:
	case <-time.After(time.Second):
		t.Fatal("the child was not killed after the timeout")
	}
}

func TestDeadStreamReleasesWaiterAsClosed(t *testing.T) {
	toPeerR, toPeerW := io.Pipe()
	fromPeerR, fromPeerW := io.Pipe()
	defer toPeerR.Close()

	s := NewSession(toPeerW, fromPeerR, func() error { return nil })

	// A live child reads the request before it dies, so drain the pipe. Without
	// this the write would block forever inside Call and prove nothing about the
	// stream death.
	go func() {
		dec := json.NewDecoder(toPeerR)
		for {
			var f map[string]any
			if err := dec.Decode(&f); err != nil {
				return
			}
		}
	}()

	errCh := make(chan error, 1)
	go func() {
		_, err := s.Call("account/rateLimits/read", map[string]any{}, 5*time.Second)
		errCh <- err
	}()

	// The child exiting is EOF on the session's stdout.
	fromPeerW.Close()

	select {
	case err := <-errCh:
		if !errors.Is(err, ErrClosed) {
			t.Fatalf("err = %v, want ErrClosed", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("waiter was never released when the stream died")
	}
}

func TestNotifyOmitsParamsWhenNil(t *testing.T) {
	toPeerR, toPeerW := io.Pipe()
	fromPeerR, fromPeerW := io.Pipe()
	defer toPeerR.Close()
	defer fromPeerW.Close()

	s := NewSession(toPeerW, fromPeerR, func() error { return nil })

	got := make(chan map[string]any, 1)
	go func() {
		dec := json.NewDecoder(toPeerR)
		var f map[string]any
		if err := dec.Decode(&f); err == nil {
			got <- f
		}
	}()

	if err := s.Notify("initialized", nil); err != nil {
		t.Fatalf("Notify: %v", err)
	}
	select {
	case f := <-got:
		if _, present := f["params"]; present {
			t.Fatalf("initialized must not carry params, sent: %v", f)
		}
		if f["method"] != "initialized" {
			t.Fatalf("method = %v", f["method"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("peer never received the notification")
	}
}

func TestCallAfterCloseFailsImmediately(t *testing.T) {
	toPeerR, toPeerW := io.Pipe()
	fromPeerR, fromPeerW := io.Pipe()
	defer toPeerR.Close()
	defer fromPeerW.Close()

	s := NewSession(toPeerW, fromPeerR, func() error { return nil })
	s.Close()

	if _, err := s.Call("account/rateLimits/read", map[string]any{}, time.Second); !errors.Is(err, ErrClosed) {
		t.Fatalf("err = %v, want ErrClosed", err)
	}
}
