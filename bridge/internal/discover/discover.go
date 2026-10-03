// Package discover locates a Codex installation on this machine.
//
// The order matters and is based on what this machine actually looks like:
// `where codex` returns npm's two shims (`codex` and `codex.cmd`) and no native
// binary, because the real executable sits three directories deeper inside the
// package. A batch shim is not something we want to spawn through a shell, so
// finding the shim is never the same as finding Codex.
package discover

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// ErrNotFound means no usable Codex was found. Callers map it to
// CODEX_NOT_FOUND and must keep serving the last good reading (Spec §53 rule 18).
var ErrNotFound = errors.New("no spawnable Codex installation found")

// Candidate is a found Codex executable.
type Candidate struct {
	Path string
	// Source records which rule matched, so a wrong guess is visible in logs and
	// tests instead of looking like a plain path.
	Source string
}

// Probe is the outside world as seen by Find. It is injected so the ordering can
// be tested without touching a real installation.
type Probe struct {
	Exists   func(path string) bool
	LookPath func(name string) (string, error)
	Glob     func(pattern string) ([]string, error)
	Env      func(key string) string
	// GOOS defaults to runtime.GOOS; tests set it to exercise the Windows-only
	// ".cmd cannot be spawned" rule on any platform.
	GOOS string
}

func (p Probe) goos() string {
	if p.GOOS != "" {
		return p.GOOS
	}
	return runtime.GOOS
}

// System returns a Probe backed by the real filesystem and environment. Find is
// written against the injected Probe so the ordering rules stay testable; this is
// what production uses.
func System() Probe {
	return Probe{
		Exists: func(path string) bool {
			st, err := os.Stat(path)
			return err == nil && !st.IsDir()
		},
		LookPath: exec.LookPath,
		Glob:     filepath.Glob,
		Env:      os.Getenv,
		GOOS:     runtime.GOOS,
	}
}

// Find returns the first usable Codex. An explicit path is honoured or it is an
// error: falling back to a different Codex than the one the user pointed at
// would silently change which account the numbers describe.
func Find(explicit string, probe Probe) (Candidate, error) {
	var tried []string

	if explicit != "" {
		if probe.Exists(explicit) {
			return Candidate{Path: explicit, Source: "explicit"}, nil
		}
		return Candidate{}, fmt.Errorf("%w: --codex path %q does not exist", ErrNotFound, explicit)
	}

	if path, err := probe.LookPath("codex"); err == nil && path != "" {
		tried = append(tried, "PATH: "+path)
		if spawnable(path, probe.goos()) {
			return Candidate{Path: path, Source: "path"}, nil
		}
	}

	// npm installs: %APPDATA%\npm\node_modules\@openai\codex\node_modules\
	//   @openai\codex-<platform>\vendor\<target-triple>\codex\codex.exe
	appdata := probe.Env("APPDATA")
	if appdata != "" {
		pattern := filepath.Join(appdata, "npm", "node_modules", "@openai", "codex",
			"node_modules", "@openai", "codex-*", "vendor", "*", "codex", "codex.exe")
		tried = append(tried, "npm: "+pattern)
		if matches, err := probe.Glob(pattern); err == nil {
			for _, m := range matches {
				if probe.Exists(m) {
					return Candidate{Path: m, Source: "npm"}, nil
				}
			}
		}
	}

	// Standalone installs.
	for _, key := range []string{"LOCALAPPDATA", "PROGRAMFILES"} {
		root := probe.Env(key)
		if root == "" {
			continue
		}
		path := filepath.Join(root, "codex", "codex.exe")
		tried = append(tried, key+": "+path)
		if probe.Exists(path) {
			return Candidate{Path: path, Source: strings.ToLower(key)}, nil
		}
	}

	return Candidate{}, fmt.Errorf("%w (tried: %s)", ErrNotFound, strings.Join(tried, "; "))
}

// spawnable reports whether os/exec can run this file directly. On Windows the
// npm shim is a batch file, and spawning it requires a shell we refuse to
// introduce, so it does not count as a find.
func spawnable(path string, goos string) bool {
	if goos != "windows" {
		return true
	}
	lower := strings.ToLower(path)
	return !strings.HasSuffix(lower, ".cmd") && !strings.HasSuffix(lower, ".bat")
}
