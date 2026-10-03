package codex

import "testing"

func TestVersionFromUserAgent(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		// The exact shape initialize echoed back on this machine.
		{"aiusage-bridge/0.121.0 (Windows 10.0.26220; x86_64) xterm-256color (aiusage-bridge; 0.1.0)", "0.121.0"},
		{"codex-cli/1.2.3 (Linux)", "1.2.3"},
		{"no-version-here", ""},
		{"weird/0.121.0-beta (x)", ""},
		{"", ""},
		{"/1.0", "1.0"},
	}
	for _, c := range cases {
		if got := VersionFromUserAgent(c.in); got != c.want {
			t.Errorf("VersionFromUserAgent(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
