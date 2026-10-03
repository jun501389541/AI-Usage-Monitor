package discover

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newProbe wires a Probe to a real temporary directory tree so the tests exercise
// actual existence checks rather than a mock that could drift from os.Stat.
func newProbe(t *testing.T, files []string, lookPathResult string, lookPathErr error, env map[string]string) Probe {
	t.Helper()
	for _, f := range files {
		if err := os.MkdirAll(filepath.Dir(f), 0o755); err != nil {
			t.Fatalf("mkdir for %s: %v", f, err)
		}
		if err := os.WriteFile(f, []byte("@echo off\r\n"), 0o644); err != nil {
			t.Fatalf("write %s: %v", f, err)
		}
	}
	return Probe{
		Exists: func(p string) bool {
			st, err := os.Stat(p)
			return err == nil && !st.IsDir()
		},
		LookPath: func(string) (string, error) { return lookPathResult, lookPathErr },
		Glob:     func(pattern string) ([]string, error) { return filepath.Glob(pattern) },
		Env:      func(key string) string { return env[key] },
		GOOS:     "windows",
	}
}

func TestExplicitPathIsHonoured(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "codex.exe")
	probe := newProbe(t, []string{bin}, "", errors.New("not found"), nil)

	got, err := Find(bin, probe)
	if err != nil {
		t.Fatalf("Find(%q) = %v, want the explicit path", bin, err)
	}
	if got.Path != bin || got.Source != "explicit" {
		t.Fatalf("got %+v, want %+v", got, Candidate{Path: bin, Source: "explicit"})
	}
}

// The mutation this guards: if the explicit path were allowed to fall back, the
// Bridge would report the quota of a different Codex than the one the user named.
func TestMissingExplicitPathDoesNotFallBack(t *testing.T) {
	dir := t.TempDir()
	other := filepath.Join(dir, "other", "codex.exe")
	missing := filepath.Join(dir, "chosen", "codex.exe")
	probe := newProbe(t, []string{other}, other, nil, nil)

	_, err := Find(missing, probe)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("Find with a missing explicit path = %v, want ErrNotFound", err)
	}
}

// The npm shim is a batch file. Reporting it as "found" would make the very next
// step - spawning it - fail with a confusing OS error.
func TestWindowsShimOnPathIsNotSpawnable(t *testing.T) {
	dir := t.TempDir()
	shim := filepath.Join(dir, "npm", "codex.cmd")
	native := filepath.Join(dir, "npm", "node_modules", "@openai", "codex",
		"node_modules", "@openai", "codex-win32-x64", "vendor", "x86_64-pc-windows-msvc",
		"codex", "codex.exe")
	probe := newProbe(t, []string{shim, native}, shim, nil, map[string]string{"APPDATA": dir})

	got, err := Find("", probe)
	if err != nil {
		t.Fatalf("Find = %v", err)
	}
	if got.Source != "npm" {
		t.Fatalf("source = %q, want npm (the .cmd shim must be skipped)", got.Source)
	}
	if got.Path != native {
		t.Fatalf("path = %q, want %q", got.Path, native)
	}
}

func TestPathHitWinsWhenItIsABinary(t *testing.T) {
	dir := t.TempDir()
	real := filepath.Join(dir, "tools", "codex.exe")
	probe := newProbe(t, []string{real}, real, nil, map[string]string{"APPDATA": dir})

	got, err := Find("", probe)
	if err != nil {
		t.Fatalf("Find = %v", err)
	}
	if got.Source != "path" || got.Path != real {
		t.Fatalf("got %+v, want path/%s", got, real)
	}
}

func TestNothingFoundListsWhatWasTried(t *testing.T) {
	dir := t.TempDir()
	probe := newProbe(t, nil, "", errors.New("not found"), map[string]string{
		"APPDATA": dir, "LOCALAPPDATA": filepath.Join(dir, "Local"),
	})

	_, err := Find("", probe)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("Find = %v, want ErrNotFound", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "npm:") || !strings.Contains(msg, "LOCALAPPDATA:") {
		t.Fatalf("error should list the locations it checked, got: %s", msg)
	}
}
