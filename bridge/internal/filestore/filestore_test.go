package filestore

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestWriteReplacesAndLeavesNoResidue(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")

	if err := Write(path, []byte("first"), 0o600); err != nil {
		t.Fatalf("first Write: %v", err)
	}
	if err := Write(path, []byte("second"), 0o600); err != nil {
		t.Fatalf("second Write: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != "second" {
		t.Fatalf("content = %q, want the whole new value", got)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp") {
			t.Fatalf("a temporary file survived: %s", e.Name())
		}
	}
}

// The whole reason the file exists is so a reader never sees a partial write. A
// truncate-in-place implementation would pass every other test here and still
// lose the identity or the last good reading on a crash.
func TestExistingContentSurvivesAFailedWrite(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "pairing.json")
	if err := Write(path, []byte("good"), 0o600); err != nil {
		t.Fatalf("seed: %v", err)
	}

	// Two ways to make the placement fail: rename onto a directory that already
	// exists, or rename to a path beneath a regular file. Both prove the same
	// thing - the failure is reported, and the file that was already there is
	// untouched.
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := Write(filepath.Join(dir, "sub"), []byte("nope"), 0o600); err == nil {
		t.Fatal("creating a file named after an existing directory reported success")
	}
	blocked := Write(filepath.Join(path, "nested.json"), []byte("nope"), 0o600)
	if blocked == nil {
		t.Fatal("writing beneath a regular file reported success")
	}
	if still, err := os.ReadFile(path); err != nil || string(still) != "good" {
		t.Fatalf("the failed write disturbed the directory it was aimed at (err=%v)", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp") {
			t.Fatalf("the failed write left a temporary behind: %s", e.Name())
		}
	}
}

func TestModeIsRequestedOnTheTemporary(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not carry POSIX permission bits; the Chmod request is compiled out there")
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.key.pem")
	if err := Write(path, []byte("k"), 0o600); err != nil {
		t.Fatalf("Write: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Fatalf("mode = %o, want owner-only", perm)
	}
}
