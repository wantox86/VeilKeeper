package httpserver

import (
	"errors"
	"net/http"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// deviceResponse is one element of GET /api/v1/devices. Field naming/format
// mirrors categoryResponse (time.Time fields marshaled as-is, RFC3339Nano)
// for consistency with the rest of this API.
type deviceResponse struct {
	ID               int64      `json:"id"`
	DeviceIdentifier string     `json:"device_identifier"`
	DeviceName       string     `json:"device_name"`
	CreatedAt        time.Time  `json:"created_at"`
	LastSeenAt       time.Time  `json:"last_seen_at"`
	IsCurrent        bool       `json:"is_current"`
	RevokedAt        *time.Time `json:"revoked_at,omitempty"`
}

func toDeviceResponse(d store.Device, currentDeviceID int64, haveCurrent bool) deviceResponse {
	return deviceResponse{
		ID:               d.ID,
		DeviceIdentifier: d.DeviceIdentifier,
		DeviceName:       d.DeviceName,
		CreatedAt:        d.CreatedAt,
		LastSeenAt:       d.LastSeenAt,
		IsCurrent:        haveCurrent && d.ID == currentDeviceID,
		RevokedAt:        d.RevokedAt,
	}
}

// handleListDevices implements GET /api/v1/devices (plan.md Phase 1): lists
// every device belonging to the authenticated user (revoked ones included --
// see store.AuthStore.ListDevices's doc comment), marking whichever one's ID
// matches this request's own session device (deviceIDFromContext) as
// is_current.
func (d *vaultDeps) handleListDevices(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())
	currentDeviceID, haveCurrent := deviceIDFromContext(r.Context())

	devices, err := d.store.ListDevices(r.Context(), userID)
	if err != nil {
		d.logger.Error("list devices: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	out := make([]deviceResponse, 0, len(devices))
	for _, dev := range devices {
		out = append(out, toDeviceResponse(dev, currentDeviceID, haveCurrent))
	}
	writeJSON(w, http.StatusOK, out)
}

// handleRevokeDevice implements DELETE /api/v1/devices/{id} (plan.md Phase
// 1): revokes the device and cascades to every session tied to it
// (store.RevokeDeviceAndSessions). Ownership is enforced server-side by the
// store layer -- a device that doesn't exist or doesn't belong to the caller
// both map to the same 404, so one user can never learn about, let alone
// revoke, another user's device (SPEC-BASE.md Section 47).
func (d *vaultDeps) handleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	deviceID, ok := pathID(w, r)
	if !ok {
		return
	}

	err := d.store.RevokeDeviceAndSessions(r.Context(), userID, deviceID)
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "device not found")
	default:
		d.logger.Error("revoke device: store failed", "error", err.Error())
		writeInternalError(w)
	}
}
