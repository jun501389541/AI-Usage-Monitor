package pairing

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
)

// PayloadScheme is the prefix a phone recognises as "this is a pairing offer".
const PayloadScheme = "aiusage://pair"

// Payload is what the QR code or the pasted text carries. The field set is Spec
// §20's list, and the rule that goes with it: a one-time Pair Token belongs here
// and nothing long-lived does (docs/PHASE-7-PLAN.md §0.3, D3).
type Payload struct {
	Version     int      `json:"v"`
	BridgeID    string   `json:"bridgeId"`
	Hosts       []string `json:"hosts"`
	Port        int      `json:"port"`
	PairToken   string   `json:"pairToken"`
	Fingerprint string   `json:"fingerprint"`
}

// BuildPayload encodes an offer as `aiusage://pair#<base64url(json)>`.
//
// Hosts is a list rather than one address on purpose. Spec §20 asks for "the LAN
// address", but the address a phone must dial depends on where the phone is: a
// real phone on the Wi-Fi needs the machine's LAN address, while an emulator
// reaches the same server through 10.0.2.2, which is an alias for host loopback
// and not an address this machine holds. Offering both keeps one printed code
// usable in both places, and the phone still asks before trusting either.
func BuildPayload(id string, hosts []string, port int, pairToken, fingerprint string) (string, error) {
	if len(hosts) == 0 {
		return "", fmt.Errorf("a pairing offer needs at least one address a phone can reach")
	}
	body, err := json.Marshal(Payload{
		Version:     1,
		BridgeID:    id,
		Hosts:       hosts,
		Port:        port,
		PairToken:   pairToken,
		Fingerprint: fingerprint,
	})
	if err != nil {
		return "", fmt.Errorf("encoding the pairing offer: %w", err)
	}
	return PayloadScheme + "#" + base64.RawURLEncoding.EncodeToString(body), nil
}

// ParsePayload is the mirror image, exported because the acceptance script reads
// back what the Bridge printed and a one-sided encoding would hide a mismatch.
func ParsePayload(text string) (Payload, error) {
	if len(text) <= len(PayloadScheme)+1 || text[:len(PayloadScheme)+1] != PayloadScheme+"#" {
		return Payload{}, fmt.Errorf("a pairing offer starts with %s#", PayloadScheme)
	}
	raw, err := base64.RawURLEncoding.DecodeString(text[len(PayloadScheme)+1:])
	if err != nil {
		return Payload{}, fmt.Errorf("decoding the pairing offer: %w", err)
	}
	var out Payload
	if err := json.Unmarshal(raw, &out); err != nil {
		return Payload{}, fmt.Errorf("the pairing offer is not the shape this Bridge writes: %w", err)
	}
	if out.Version != 1 || out.PairToken == "" || out.Fingerprint == "" || out.Port <= 0 || len(out.Hosts) == 0 {
		return Payload{}, fmt.Errorf("the pairing offer is missing a field (v=%d port=%d hosts=%d)", out.Version, out.Port, len(out.Hosts))
	}
	return out, nil
}
