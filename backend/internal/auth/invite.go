package auth

import "crypto/subtle"

// ValidateInviteCode reports whether submitted matches any code in the
// configured allowlist (SPEC-BASE.md Section 43: registration is gated by an
// operator-distributed invite code, closing the previously-open
// /auth/register endpoint). Comparison is constant-time per candidate and
// checks every candidate rather than returning early on the first match, so
// response timing doesn't leak how many codes exist or which one (if any)
// was "close." An empty codes list always returns false -- callers must
// separately fail closed on that case with a distinct, non-generic message
// ("registration is currently closed") per CLAUDE.md/SPEC-BASE.md's
// fail-clearly-on-missing-config principle; this function only answers
// "does submitted match a configured code," not "is registration open."
func ValidateInviteCode(codes []string, submitted string) bool {
	if submitted == "" {
		return false
	}

	match := 0
	for _, c := range codes {
		if subtle.ConstantTimeCompare([]byte(c), []byte(submitted)) == 1 {
			match = 1
		}
	}
	return match == 1
}
