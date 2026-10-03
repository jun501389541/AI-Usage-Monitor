package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"time"

	"aiusage.local/bridge/internal/pairing"
)

// The operator-facing half of pairing: mint an offer, look at the devices, take
// one's access away. Mounted only in secured mode and answered only for a loopback
// peer, because the device token exists to be the thing a stranger on the segment
// does not have - an admin route open to the network would let any device there
// enrol itself and the rest of the design would be decoration.

// requireLoopback wraps an admin handler. It checks the connecting address rather
// than the requested host: Host headers are trivially forged, the peer is not.
func (s *Server) requireLoopback(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !loopbackPeer(r) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown path"})
			return
		}
		next(w, r)
	}
}

func loopbackPeer(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		// A peer address that is not host:port is not something this server will
		// call administrative.
		return false
	}
	ip := net.ParseIP(host)
	// zone/percent forms (fe80::1%eth0) still parse to a usable address; only a
	// genuinely unparseable one fails here.
	return ip != nil && ip.IsLoopback()
}

// handleAdminPair issues one introduction and returns everything needed to hand it
// to a phone: the two secrets, when they die, and the encoded offer.
func (s *Server) handleAdminPair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only POST is served here"})
		return
	}
	token, code, expires, err := s.opts.Pairing.Issue(s.opts.PairTTL)
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "the pairing offer could not be stored"})
		return
	}
	payload, err := pairing.BuildPayload(s.opts.ID.BridgeID, s.opts.Advertise, s.port, token, s.opts.ID.Fingerprint)
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "the pairing offer could not be built"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pairToken":   token,
		"code":        code,
		"expiresAt":   expires.Format(time.RFC3339),
		"bridgeId":    s.opts.ID.BridgeID,
		"fingerprint": s.opts.ID.Fingerprint,
		"payload":     payload,
	})
}

func (s *Server) handleAdminDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only GET is served here"})
		return
	}
	type entry struct {
		ID         string `json:"id"`
		Name       string `json:"name"`
		Revoked    bool   `json:"revoked"`
		CreatedAt  string `json:"createdAt"`
		LastSeenAt string `json:"lastSeenAt"`
	}
	out := make([]entry, 0)
	for _, d := range s.opts.Pairing.List() {
		out = append(out, entry{
			ID:         d.ID,
			Name:       d.Name,
			Revoked:    d.Revoked,
			CreatedAt:  d.CreatedAt.Format(time.RFC3339),
			LastSeenAt: d.LastSeenAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleAdminRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only POST is served here"})
		return
	}
	var request struct {
		DeviceID string `json:"deviceId"`
	}
	body := http.MaxBytesReader(w, r.Body, 1024)
	if err := json.NewDecoder(body).Decode(&request); err != nil || request.DeviceID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "expected {deviceId}"})
		return
	}
	if err := s.opts.Pairing.Revoke(request.DeviceID); err != nil {
		if errors.Is(err, pairing.ErrNotPersisted) {
			// "I could not take this device's access away" is not "there is no such
			// device": the operator has to know the revocation did not land, since
			// the device keeps working after the next restart (PHASE-7-REVIEW P2).
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "the revocation could not be stored"})
			return
		}
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no such device"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"revoked": true})
}
