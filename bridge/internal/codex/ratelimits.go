package codex

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
)

// Snapshot is the decoded answer to account/rateLimits/read, in the units the
// rest of the Bridge uses: minutes for window length, milliseconds for reset.
type Snapshot struct {
	PlanType       string
	LimitID        string
	Windows        []Window
	HasCredits     bool
	Unlimited      bool
	CreditsBalance string
}

// Window is one quota meter.
type Window struct {
	// ID is derived from what Codex reported (its own limit id plus the window
	// length), never from a hard-coded "5 小时" assumption - the schema allows any
	// number of buckets and the spec says primary is not necessarily the 5-hour
	// one (QuotaWindow.java:6-8).
	ID               string
	Label            string
	UsedPercent      int
	RemainingPercent int
	WindowMinutes    int64
	// ResetAtMillis is Unix millis, 0 when Codex did not say.
	ResetAtMillis int64
	// Kind is the slot Codex placed it in: "primary" or "secondary". Kept only
	// so a bucket order can be reproduced when Codex itself gives no names.
	Kind string
}

// wire types mirror the camelCase JSON-RPC shape. Every field except
// usedPercent is nullable in the schema, and the offline session copy proved
// they really do come back null, so nothing here may assume presence.
type wireCredits struct {
	HasCredits *bool   `json:"hasCredits"`
	Unlimited  *bool   `json:"unlimited"`
	Balance    *string `json:"balance"`
}

type wireWindow struct {
	UsedPercent        *int   `json:"usedPercent"`
	WindowDurationMins *int64 `json:"windowDurationMins"`
	ResetsAt           *int64 `json:"resetsAt"`
}

type wireSnapshot struct {
	LimitID   *string      `json:"limitId"`
	LimitName *string      `json:"limitName"`
	PlanType  *string      `json:"planType"`
	Primary   *wireWindow  `json:"primary"`
	Secondary *wireWindow  `json:"secondary"`
	Credits   *wireCredits `json:"credits"`
}

type wireResponse struct {
	RateLimits          wireSnapshot            `json:"rateLimits"`
	RateLimitsByLimitID map[string]wireSnapshot `json:"rateLimitsByLimitId"`
}

// ParseRateLimits decodes the response. It fails when no usable window survives,
// because an empty snapshot would otherwise be stored as "the truth" and shown
// instead of the last good reading.
func ParseRateLimits(raw []byte) (Snapshot, error) {
	var resp wireResponse
	if err := json.Unmarshal(raw, &resp); err != nil {
		return Snapshot{}, fmt.Errorf("decoding rate limits response: %w", err)
	}

	buckets := map[string]wireSnapshot{}
	// Prefer the multi-bucket view. If it yields nothing usable, fall back to the
	// single view: they describe the same meter, and the older field is the one
	// that actually carries data in that case.
	for id, snap := range resp.RateLimitsByLimitID {
		buckets[id] = snap
	}
	if len(windowsFromAll(buckets)) == 0 {
		id := deref(resp.RateLimits.LimitID, "codex")
		buckets = map[string]wireSnapshot{id: resp.RateLimits}
	}

	ids := make([]string, 0, len(buckets))
	for id := range buckets {
		ids = append(ids, id)
	}
	// Go randomises map iteration; sorting keeps output byte-stable so the
	// acceptance script can compare runs.
	sort.Strings(ids)

	var out Snapshot
	for _, id := range ids {
		snap := buckets[id]
		if out.PlanType == "" {
			out.PlanType = deref(snap.PlanType, "")
		}
		if out.LimitID == "" {
			out.LimitID = deref(snap.LimitID, id)
		}
		if snap.Credits != nil {
			out.HasCredits = deref(snap.Credits.HasCredits, false)
			out.Unlimited = deref(snap.Credits.Unlimited, false)
			out.CreditsBalance = deref(snap.Credits.Balance, "")
		}
		out.Windows = append(out.Windows, windowsFor(id, snap)...)
	}

	if len(out.Windows) == 0 {
		return Snapshot{}, fmt.Errorf("response carried no usable quota window (all buckets missing usedPercent)")
	}
	return out, nil
}

// windowsFor turns one bucket's primary/secondary slots into windows, dropping
// any slot that did not report a percentage.
func windowsFor(bucketID string, snap wireSnapshot) []Window {
	name := deref(snap.LimitName, "")
	lim := deref(snap.LimitID, bucketID)

	var out []Window
	for _, slot := range []struct {
		kind string
		w    *wireWindow
	}{{"primary", snap.Primary}, {"secondary", snap.Secondary}} {
		if slot.w == nil || slot.w.UsedPercent == nil {
			continue
		}
		minutes := deref(slot.w.WindowDurationMins, 0)
		id := fmt.Sprintf("%s:%d", lim, minutes)
		out = append(out, Window{
			ID:               id,
			Label:            labelFor(lim, name, minutes),
			UsedPercent:      *slot.w.UsedPercent,
			RemainingPercent: 100 - *slot.w.UsedPercent,
			WindowMinutes:    minutes,
			// Codex reports epoch seconds; every consumer in this project counts
			// in milliseconds (QuotaWindow.getResetAt()).
			ResetAtMillis: secondsToMillis(deref(slot.w.ResetsAt, 0)),
			Kind:          slot.kind,
		})
	}
	return out
}

// windowsFromAll flattens every bucket. It is used only to decide whether the
// multi-bucket view carries anything at all, so order is irrelevant here.
func windowsFromAll(buckets map[string]wireSnapshot) []Window {
	var out []Window
	for id, snap := range buckets {
		out = append(out, windowsFor(id, snap)...)
	}
	return out
}

// labelFor derives a human label from the reported duration rather than naming
// the slot, so a 4-hour or a 28-day window is labelled correctly.
func labelFor(limitID, limitName string, minutes int64) string {
	if limitName != "" {
		return limitName
	}
	switch {
	case minutes <= 0:
		return fmt.Sprintf("%s 未说明时长", limitID)
	case minutes%(24*60) == 0:
		return fmt.Sprintf("%d 天", minutes/(24*60))
	case minutes%60 == 0:
		return fmt.Sprintf("%d 小时", minutes/60)
	default:
		return fmt.Sprintf("%d 分钟", minutes)
	}
}

func secondsToMillis(sec int64) int64 {
	if sec <= 0 {
		return 0
	}
	return sec * 1000
}

func deref[T any](p *T, fallback T) T {
	if p == nil {
		return fallback
	}
	return *p
}

// String renders the snapshot for logs. It lists what Codex said without
// inventing values for the fields it left out.
func (s Snapshot) String() string {
	parts := make([]string, 0, len(s.Windows))
	for _, w := range s.Windows {
		parts = append(parts, fmt.Sprintf("%s=%d%%", w.Label, w.UsedPercent))
	}
	return fmt.Sprintf("plan=%s credits=%s %s", s.PlanType, orDash(s.CreditsBalance), strings.Join(parts, " "))
}

func orDash(v string) string {
	if v == "" {
		return "-"
	}
	return v
}
