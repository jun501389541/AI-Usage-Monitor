// Package codex speaks the local Codex App Server protocol: line-delimited
// JSON-RPC 2.0 over the child process' stdin and stdout.
//
// Nothing in this package reads Codex credentials. The child process uses its
// own login; the Bridge only ever sees the result documents
// (docs/PHASE-5-PLAN.md A2).
package codex

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

// ErrTimeout means the child accepted our request and never answered it. The
// session is closed and the process killed before this is returned.
var ErrTimeout = errors.New("codex app-server did not answer in time")

// ErrClosed means the child's stream ended (it exited or crashed) mid-flight.
var ErrClosed = errors.New("codex app-server stream closed")

// methodNotFoundError is the JSON-RPC code we reply with when Codex asks us to
// do something we refuse.
const methodNotFoundError = -32601

// rpcError is a JSON-RPC error object.
type rpcError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *rpcError) Error() string {
	return fmt.Sprintf("codex app-server error %d: %s", e.Code, e.Message)
}

// RPCCode exposes the JSON-RPC error code without exporting the type, so callers
// can branch on "Codex has no such method" without string matching.
func (e *rpcError) RPCCode() int { return e.Code }

// CodedError is any error that carries a JSON-RPC error code.
type CodedError interface {
	error
	RPCCode() int
}

// IsMethodNotFound reports that Codex answered "no such method". Phase 5 treats
// that as a capability signal: this Codex build does not offer the method the
// Bridge asked for (docs/PHASE-5-PLAN.md A7: probe, do not pin a version).
func IsMethodNotFound(err error) bool {
	var coded CodedError
	return errors.As(err, &coded) && coded.RPCCode() == methodNotFoundError
}

// frame is every JSON-RPC shape at once: response (ID + Result/Error), request
// (ID + Method) and notification (Method, no ID). The stream carries all three
// interleaved, so a frame can only be classified once all three fields are read.
type frame struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *rpcError       `json:"error,omitempty"`
}

// reply is what a waiting Call receives. Errors travel as typed errors so that
// callers can tell a timeout from a dead child from a server-side error with
// errors.Is, instead of matching on message text.
type reply struct {
	result json.RawMessage
	err    error
}

// Session is one live conversation with an app-server process.
type Session struct {
	writeMu sync.Mutex
	enc     *json.Encoder
	nextSeq int

	mu      sync.Mutex
	pending map[string]chan reply
	closed  bool

	// refusedRequest observes requests Codex sent to us that we declined. The
	// Bridge holds no credential, so a refusal is information, not a bug.
	refusedRequest func(method string)

	kill func() error
}

// NewSession wraps a child's stdin/stdout. kill must terminate the process; it
// runs when the stream breaks, when a call times out, and on Close.
func NewSession(stdin io.Writer, stdout io.Reader, kill func() error) *Session {
	s := &Session{
		enc:     json.NewEncoder(stdin),
		pending: map[string]chan reply{},
		kill:    kill,
	}
	go s.readLoop(stdout)
	return s
}

// readLoop owns the child's stdout for the lifetime of the session.
func (s *Session) readLoop(stdout io.Reader) {
	dec := json.NewDecoder(stdout)
	for {
		var f frame
		if err := dec.Decode(&f); err != nil {
			// A process that exits mid-read looks like EOF here; that is the same
			// failure the caller needs to know about as any other closed stream.
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrClosedPipe) {
				err = ErrClosed
			}
			s.failAll(err)
			return
		}
		switch {
		case f.Method == "" && f.ID != nil:
			s.resolve(f)
		case f.ID != nil:
			// A request from Codex to us. Refusing is the only safe answer, and
			// answering at all is required: the child blocks until it gets one.
			s.replyError(f.ID, &rpcError{
				Code:    methodNotFoundError,
				Message: "the Bridge does not serve " + f.Method,
			})
			if s.refusedRequest != nil {
				s.refusedRequest(f.Method)
			}
		default:
			// Notification. Phase 5 has no use for these, but they must keep
			// flowing or the child stalls on a full pipe.
		}
	}
}

func (s *Session) resolve(f frame) {
	key := string(f.ID)
	s.mu.Lock()
	ch := s.pending[key]
	delete(s.pending, key)
	s.mu.Unlock()
	if ch == nil {
		return
	}
	if f.Error != nil {
		ch <- reply{err: f.Error}
		return
	}
	ch <- reply{result: f.Result}
}

func (s *Session) failAll(err error) {
	s.releaseAll(reply{err: err})
	s.killQuietly()
}

// releaseAll hands every waiting caller the same failure. Draining the table
// makes it safe to call twice (Close, then readLoop) and never leaves a caller
// blocked until its timeout.
func (s *Session) releaseAll(r reply) {
	s.mu.Lock()
	s.closed = true
	waiters := make([]chan reply, 0, len(s.pending))
	for _, ch := range s.pending {
		waiters = append(waiters, ch)
	}
	s.pending = map[string]chan reply{}
	s.mu.Unlock()

	for _, ch := range waiters {
		ch <- r
	}
}

func (s *Session) send(f frame) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.enc.Encode(f)
}

func (s *Session) replyError(id json.RawMessage, e *rpcError) {
	// A write failure is ignored on purpose: if we cannot write, the child is
	// already gone and readLoop is failing the session.
	_ = s.send(frame{ID: id, Error: e})
}

// Call sends one request and waits for its answer, up to timeout. Timing out
// closes the session, because a child that ignores one request cannot be
// trusted to answer the next.
func (s *Session) Call(method string, params any, timeout time.Duration) (json.RawMessage, error) {
	raw, err := json.Marshal(params)
	if err != nil {
		return nil, fmt.Errorf("encoding params for %s: %w", method, err)
	}

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, ErrClosed
	}
	s.nextSeq++
	id := fmt.Sprintf("%q", fmt.Sprintf("bridge-%d", s.nextSeq))
	ch := make(chan reply, 1)
	s.pending[id] = ch
	s.mu.Unlock()

	if err := s.send(frame{ID: json.RawMessage(id), Method: method, Params: raw}); err != nil {
		s.dropPending(id)
		return nil, err
	}

	select {
	case r := <-ch:
		return r.result, r.err
	case <-time.After(timeout):
		s.dropPending(id)
		s.releaseAll(reply{err: ErrTimeout})
		s.killQuietly()
		return nil, ErrTimeout
	}
}

func (s *Session) dropPending(id string) {
	s.mu.Lock()
	delete(s.pending, id)
	s.mu.Unlock()
}

// Notify sends a frame with no id, so nothing waits for it. A nil params keeps
// the member out of the payload, which is what the handshake's `initialized`
// frame requires.
func (s *Session) Notify(method string, params any) error {
	f := frame{Method: method}
	if params != nil {
		raw, err := json.Marshal(params)
		if err != nil {
			return err
		}
		f.Params = raw
	}
	return s.send(f)
}

func (s *Session) killQuietly() {
	if s.kill != nil {
		_ = s.kill()
	}
}

// Close ends the session and terminates the child.
func (s *Session) Close() error {
	s.releaseAll(reply{err: ErrClosed})
	s.killQuietly()
	return nil
}
