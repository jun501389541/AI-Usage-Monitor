// Package redact masks credential-shaped text before the Bridge writes anything
// out. The Bridge never reads Codex tokens, but two paths can still carry text
// produced upstream: an error message from the app server, and a free-text limit
// name. This is where those get scrubbed.
package redact

import (
	"regexp"
	"strings"
)

// Keep a short, unmistakable prefix so a log reader can tell what was masked
// without being able to reconstruct it.
const keep = 6

var patterns = []*regexp.Regexp{
	// JWT-shaped values: ChatGPT access and id tokens start with "eyJ"
	// (base64 for "{").
	regexp.MustCompile(`eyJ[A-Za-z0-9_-]{4,}(?:\.[A-Za-z0-9_-]{2,}){2}`),
	// OpenAI-style secret keys.
	regexp.MustCompile(`sk-[A-Za-z0-9_-]{8,}`),
	// Anything presented as a bearer credential.
	regexp.MustCompile(`(?i)bearer[ =]+[A-Za-z0-9._~+/=-]{8,}`),
}

const marker = "...[redacted]"

// Text returns s with credential-shaped runs masked. Ordinary text, including
// the Chinese labels the Bridge produces, is returned unchanged.
func Text(s string) string {
	if s == "" {
		return s
	}
	for _, re := range patterns {
		s = re.ReplaceAllStringFunc(s, maskOne)
	}
	return s
}

// HasSecret reports whether s still contains credential-shaped text. Used by the
// tests to prove the mask actually fired rather than trusting the output shape.
func HasSecret(s string) bool {
	for _, re := range patterns {
		if re.MatchString(s) {
			return true
		}
	}
	return false
}

func maskOne(match string) string {
	// For "Bearer <token>" the word itself is not secret; keeping it means the
	// log still says what kind of credential was involved.
	if strings.HasPrefix(strings.ToLower(match), "bearer") {
		return "Bearer " + marker
	}
	if len(match) <= keep {
		return match
	}
	return match[:keep] + marker
}
