package codex

import (
	"strings"
	"testing"
)

// realResponse is the verbatim answer recorded from a live
// account/rateLimits/read (docs/PHASE-5-BRIDGE-FEASIBILITY.md §2.4).
const realResponse = `{
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
      "limitName": null,
      "primary":   { "usedPercent": 100, "windowDurationMins": 300,   "resetsAt": 1790936545 },
      "secondary": { "usedPercent": 40,  "windowDurationMins": 10080, "resetsAt": 1791419454 },
      "credits":   { "hasCredits": false, "unlimited": false, "balance": "0" },
      "planType":  "plus"
    }
  }
}`

func windowByLabel(t *testing.T, s Snapshot, label string) Window {
	t.Helper()
	for _, w := range s.Windows {
		if w.Label == label {
			return w
		}
	}
	t.Fatalf("no window labelled %q in %+v", label, s.Windows)
	return Window{}
}

func TestParseRealResponse(t *testing.T) {
	s, err := ParseRateLimits([]byte(realResponse))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	if len(s.Windows) != 2 {
		t.Fatalf("windows = %d, want 2: %+v", len(s.Windows), s.Windows)
	}
	if s.PlanType != "plus" {
		t.Fatalf("plan = %q, want plus", s.PlanType)
	}
	if s.CreditsBalance != "0" {
		t.Fatalf("credits balance = %q, want the string \"0\" (Codex sends it quoted)", s.CreditsBalance)
	}

	five := windowByLabel(t, s, "5 小时")
	week := windowByLabel(t, s, "7 天")

	if five.UsedPercent != 100 || five.RemainingPercent != 0 {
		t.Fatalf("5h = %+v", five)
	}
	if week.UsedPercent != 40 || week.RemainingPercent != 60 {
		t.Fatalf("weekly = %+v", week)
	}
	if five.WindowMinutes != 300 || week.WindowMinutes != 10080 {
		t.Fatalf("minutes = %d/%d, want 300/10080", five.WindowMinutes, week.WindowMinutes)
	}
	// Spec Phase 5's third requirement is the reset time, and this is the unit
	// trap: Codex says seconds, this project counts milliseconds.
	if five.ResetAtMillis != 1790936545000 {
		t.Fatalf("reset = %d, want 1790936545000 (seconds x 1000)", five.ResetAtMillis)
	}
	if week.ResetAtMillis != 1791419454000 {
		t.Fatalf("weekly reset = %d, want 1791419454000", week.ResetAtMillis)
	}
	if five.ID != "codex:300" || week.ID != "codex:10080" {
		t.Fatalf("ids = %q / %q, want them derived from the reported duration", five.ID, week.ID)
	}
}

// The offline session copy proved these slots really do arrive null, so a
// snapshot with nothing usable must be an error rather than an empty truth that
// would replace the last good reading in the cache.
func TestAllSlotsNullIsAnError(t *testing.T) {
	_, err := ParseRateLimits([]byte(`{"rateLimits":{"limitId":"codex","primary":null,"secondary":null,"plan_type":"plus"}}`))
	if err == nil {
		t.Fatal("expected an error when no window carries a percentage")
	}
	if !strings.Contains(err.Error(), "no usable quota window") {
		t.Fatalf("error = %v", err)
	}
}

func TestMissingPercentageDropsOnlyThatSlot(t *testing.T) {
	s, err := ParseRateLimits([]byte(`{
      "rateLimits": {
        "limitId": "codex",
        "primary":   { "windowDurationMins": 300, "resetsAt": 1790936545 },
        "secondary": { "usedPercent": 12, "windowDurationMins": 10080 }
      }
    }`))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	if len(s.Windows) != 1 {
		t.Fatalf("windows = %+v, want only the one with a percentage", s.Windows)
	}
	if s.Windows[0].WindowMinutes != 10080 {
		t.Fatalf("kept window = %+v", s.Windows[0])
	}
	if s.Windows[0].ResetAtMillis != 0 {
		t.Fatalf("absent reset must stay 0 (unknown), got %d", s.Windows[0].ResetAtMillis)
	}
}

// Go randomises map iteration; the script that compares runs cannot tolerate a
// window order that changes between identical inputs.
func TestMultiBucketOutputIsOrderStable(t *testing.T) {
	const payload = `{
      "rateLimits": { "limitId": "z", "primary": { "usedPercent": 1, "windowDurationMins": 60 } },
      "rateLimitsByLimitId": {
        "z": { "limitId": "z", "primary": { "usedPercent": 1, "windowDurationMins": 60 } },
        "a": { "limitId": "a", "primary": { "usedPercent": 2, "windowDurationMins": 120 } },
        "m": { "limitId": "m", "primary": { "usedPercent": 3, "windowDurationMins": 180 } }
      }
    }`
	first, err := ParseRateLimits([]byte(payload))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	if len(first.Windows) != 3 {
		t.Fatalf("windows = %d, want one per bucket", len(first.Windows))
	}
	for i := 0; i < 20; i++ {
		again, err := ParseRateLimits([]byte(payload))
		if err != nil {
			t.Fatalf("run %d: %v", i, err)
		}
		if fmtIDs(again) != fmtIDs(first) {
			t.Fatalf("run %d order = %s, first = %s", i, fmtIDs(again), fmtIDs(first))
		}
	}
	if got := fmtIDs(first); got != "a:120 m:180 z:60" {
		t.Fatalf("ids = %q, want sorted by limit id", got)
	}
}

func fmtIDs(s Snapshot) string {
	parts := make([]string, 0, len(s.Windows))
	for _, w := range s.Windows {
		parts = append(parts, w.ID)
	}
	return strings.Join(parts, " ")
}

func TestLabelDerivation(t *testing.T) {
	cases := []struct {
		minutes int64
		name    string
		want    string
	}{
		{300, "", "5 小时"},
		{10080, "", "7 天"},
		{45, "", "45 分钟"},
		{0, "", "codex 未说明时长"},
		{300, "Premium 5h", "Premium 5h"}, // Codex's own name wins when it gives one
	}
	for _, c := range cases {
		got := labelFor("codex", c.name, c.minutes)
		if got != c.want {
			t.Errorf("labelFor(%d, %q) = %q, want %q", c.minutes, c.name, got, c.want)
		}
	}
}

func TestSingleViewOnlyStillWorks(t *testing.T) {
	s, err := ParseRateLimits([]byte(`{
      "rateLimits": { "limitId": "codex", "primary": { "usedPercent": 7, "windowDurationMins": 300 } }
    }`))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	if len(s.Windows) != 1 || s.Windows[0].UsedPercent != 7 {
		t.Fatalf("windows = %+v", s.Windows)
	}
}

// A multi-bucket view that carries nothing usable must not hide the single
// view, which describes the same meter: without this the Bridge would report
// "no window" while Codex had actually answered.
func TestSparseMapFallsBackToSingleView(t *testing.T) {
	s, err := ParseRateLimits([]byte(`{
      "rateLimits": {
        "limitId": "codex",
        "primary": { "usedPercent": 22, "windowDurationMins": 300, "resetsAt": 1790936545 }
      },
      "rateLimitsByLimitId": { "codex": { "limitId": "codex" } }
    }`))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	if len(s.Windows) != 1 || s.Windows[0].UsedPercent != 22 {
		t.Fatalf("windows = %+v, want the single view's window", s.Windows)
	}
}

func TestGarbageIsRejected(t *testing.T) {
	if _, err := ParseRateLimits([]byte(`{"rateLimits": "not-an-object"}`)); err == nil {
		t.Fatal("expected a decode error")
	}
}

func TestStringRendersWhatWasReported(t *testing.T) {
	s, err := ParseRateLimits([]byte(realResponse))
	if err != nil {
		t.Fatalf("ParseRateLimits: %v", err)
	}
	got := s.String()
	for _, want := range []string{"plan=plus", "credits=0", "5 小时=100%", "7 天=40%"} {
		if !strings.Contains(got, want) {
			t.Fatalf("String() = %q, missing %q", got, want)
		}
	}
}
