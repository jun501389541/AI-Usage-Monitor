package redact

import (
	"strings"
	"testing"
)

const fakeJWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ.dozjgNryP4J3jVmNHl0w5NXgL0n3I9PlFUP0THsR8U"

func TestMasksJWTShapedText(t *testing.T) {
	in := "refresh failed: " + fakeJWT + " rejected"
	out := Text(in)
	if HasSecret(out) {
		t.Fatalf("token survived the mask: %s", out)
	}
	if !strings.Contains(out, "[redacted]") {
		t.Fatalf("no trace of masking: %s", out)
	}
	if !strings.Contains(out, "refresh failed:") {
		t.Fatalf("the surrounding message was lost: %s", out)
	}
}

func TestMasksSecretKeysAndBearers(t *testing.T) {
	cases := []string{
		"stored key sk-proj-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
		"header Authorization: Bearer " + fakeJWT,
		"bearer=AbCdEf0123456789abcdefghij",
	}
	for _, in := range cases {
		out := Text(in)
		if HasSecret(out) {
			t.Errorf("%q was not masked: %s", in, out)
		}
	}
}

// The Bridge's own labels and error text must not be damaged by the mask, or a
// future "nothing is redacted" assertion would pass for the wrong reason.
func TestLeavesOrdinaryTextAlone(t *testing.T) {
	for _, in := range []string{
		"5 小时",
		"7 天",
		"codex app-server error -32601: method not found",
		"no spawnable Codex installation found (tried: PATH: C:\\npm\\codex.cmd)",
		"",
	} {
		if got := Text(in); got != in {
			t.Errorf("Text(%q) changed it to %q", in, got)
		}
	}
}

func TestMaskingIsIdempotent(t *testing.T) {
	once := Text("see " + fakeJWT)
	if twice := Text(once); twice != once {
		t.Fatalf("second pass changed the output: %q vs %q", twice, once)
	}
}

func TestMaskKeepsOnlyAFingerprint(t *testing.T) {
	got := Text(fakeJWT)
	want := fakeJWT[:6] + "...[redacted]"
	if got != want {
		t.Fatalf("Text(jwt) = %q, want %q", got, want)
	}
	// The kept prefix must not itself be a usable credential.
	if strings.Contains(got, fakeJWT[10:]) {
		t.Fatal("too much of the token was retained")
	}
}
