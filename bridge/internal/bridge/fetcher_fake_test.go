package bridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeEnvKey makes this test binary impersonate a Codex app server when it is
// started as one: the production CodexFetcher spawns a child with
// `argv[1] == "app-server"`, so the same binary runs the fake instead of the
// suite. That is what keeps docs/PHASE-5-REVIEW.md §4 honest - discovery, the
// JSON-RPC client, the timeout and the refusal bookkeeping all run for real,
// rather than a Fetcher faked into having the right answer.
const fakeEnvKey = "AIUSAGE_FAKE_APPSERVER"

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
	Result json.RawMessage `json:"result"`
	Error  struct {
		Code int `json:"code"`
	} `json:"error"`
}

// syntheticSecrets are credential-shaped strings with no meaning behind them. The
// fake puts them in the upstream error text so the acceptance run can check what
// the Bridge serves and stores, never that a real token moved.
const syntheticSecrets = "sk-syntheticAcceptanceCredential0123456789 and " +
	"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTYifQ.abcdefghijk_LMNOPqrst"

func writeError(enc *json.Encoder, id json.RawMessage, message string) {
	_ = enc.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "error": map[string]any{
		"code": -32603, "message": message,
	}})
}

func runFakeAppServer(scenario string) {
	dec := json.NewDecoder(os.Stdin)
	enc := json.NewEncoder(os.Stdout)
	handshook := false

	for {
		var f fakeFrame
		if err := dec.Decode(&f); err != nil {
			return
		}
		switch f.Method {
		case "initialize":
			_ = enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "result": map[string]any{
				"userAgent": "codex-cli/0.121.0 (Windows 10.0.26220; x86_64) fake",
				"codexHome": `C:\fake\.codex`,
			}})
		case "initialized":
			handshook = true
		case "account/rateLimits/read":
			if !handshook {
				_ = enc.Encode(map[string]any{"jsonrpc": "2.0", "id": f.ID, "error": map[string]any{
					"code": -32001, "message": "handshake incomplete",
				}})
				return
			}
			// Codex asks *us* for refreshed login tokens, then the read itself goes
			// wrong. The refusal arrives first, so the failure has to carry it.
			if scenario == "secret-error-no-refusal" {
				// No reverse request at all: the upstream string is everything the
				// classifier has, so the mask is the only thing standing between it
				// and the response. A recognised refusal *replaces* the upstream
				// text, which is why the other scenario cannot show the marker.
				writeError(enc, f.ID, "failed to fetch codex rate limits: "+syntheticSecrets)
				return
			}
			_ = enc.Encode(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(`99`),
				"method": "account/chatgptAuthTokens/refresh", "params": map[string]any{}})

			var ack fakeFrame
			if err := dec.Decode(&ack); err != nil {
				return
			}
			// What the client answered with: a -32601 and no payload. A Bridge that
			// ever supplied a token would show up here as a different code or a
			// non-empty result, so this is the "no token was offered" proof.
			proof := fmt.Sprintf("refusal-code=%d refusal-payload-bytes=%d", ack.Error.Code, len(ack.Result))

			switch scenario {
			case "refuse-then-error":
				writeError(enc, f.ID, "failed to fetch codex rate limits: "+proof)
			case "refuse-then-secret-error":
				writeError(enc, f.ID, "failed to fetch codex rate limits: "+proof+" ("+syntheticSecrets+")")
			case "refuse-then-silent":
				// Never answers. The client's own timeout is what the Bridge has to
				// classify, with the refusal still in hand. The sleep outlives the
				// test's timeout; the parent kills this process.
				time.Sleep(30 * time.Second)
			}
			return
		}
	}
}

// The refusal record used to be attached only on the success path, so a read that
// failed - or never answered - after Codex asked for tokens was reported as a
// generic error instead of CODEX_AUTH_REQUIRED (§4). Both shapes are covered: an
// RPC error and silence.
func TestRefusalTravelsWithEveryFailurePath(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}

	cases := []struct {
		name     string
		scenario string
		timeout  time.Duration
		// proof is what only the "answered with a bare refusal" path can carry.
		proof string
	}{
		{"rpc error after the refusal", "refuse-then-error", 10 * time.Second, "refusal-code=-32601 refusal-payload-bytes=0"},
		{"no answer after the refusal", "refuse-then-silent", 900 * time.Millisecond, ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv(fakeEnvKey, tc.scenario)
			fetcher := &CodexFetcher{ExplicitPath: exe, Timeout: tc.timeout}

			reading, err := fetcher.Fetch()
			if err == nil {
				t.Fatal("the fake app server was built to fail")
			}
			if !contains(reading.RefusedServerRequests, refreshRequestMethod) {
				t.Fatalf("the refusal was dropped on the way out: %#v (err %v)", reading, err)
			}
			if reading.CodexVersion == "" {
				t.Errorf("the version did not travel with the failure: %+v", reading)
			}
			class, detail := classify(err, reading.RefusedServerRequests)
			if class != ClassAuthRequired {
				t.Fatalf("class = %q, want %q (detail %q)", class, ClassAuthRequired, detail)
			}
			// The proof lives in the child's own error text. Note that the class
			// sentence replaces it: once a refusal is recognised, the upstream
			// string is not what gets served.
			if tc.proof != "" && !strings.Contains(err.Error(), tc.proof) {
				t.Fatalf("the client did not refuse with a bare -32601: %v", err)
			}

			// And what the HTTP layer would serve: the class has to survive the
			// transaction, not just the fetch.
			svc := NewService(fetcher, NewStore(filepath.Join(t.TempDir(), "s.json")), time.Minute)
			_, usageErr := svc.Usage(true)
			var failure FailureError
			if !errors.As(usageErr, &failure) {
				t.Fatalf("Usage returned %v, want a FailureError", usageErr)
			}
			if failure.Class != ClassAuthRequired {
				t.Fatalf("served class = %q, want %q (%q)", failure.Class, ClassAuthRequired, failure.Message)
			}
		})
	}
}
