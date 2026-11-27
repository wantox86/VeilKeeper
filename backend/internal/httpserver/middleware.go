package httpserver

import (
	"net"
	"net/http"
	"strings"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// rateLimited wraps next with a per-IP sliding-window rate limiter
// (SPEC-BASE.md Section 30, "Rate-limit authentication endpoints"). On
// rejection it returns 429 without hitting the store or auth logic at all,
// so a flood of requests never reaches Argon2id hashing.
//
// Client IP determination trusts CF-Connecting-IP only when the direct TCP
// peer (r.RemoteAddr) falls within trustedProxies (e.g. local cloudflared /
// Docker bridge). Untrusted peers or missing/invalid/multi-value headers
// always fall back to r.RemoteAddr. IPv6 addresses are normalized to /64
// prefixes so an attacker rotating addresses within one /64 shares a single
// bucket; IPv4 addresses remain /32.
func rateLimited(limiter *auth.IPLimiter, trustedProxies []*net.IPNet, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r, trustedProxies)
		if !limiter.Allow(ip) {
			writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, slow down")
			return
		}
		next(w, r)
	}
}

func clientIP(r *http.Request, trustedProxies []*net.IPNet) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	host = strings.TrimPrefix(strings.TrimSuffix(strings.TrimSpace(host), "]"), "[")
	peerIP := net.ParseIP(host)

	if peerIP != nil && isTrustedProxy(peerIP, trustedProxies) {
		if cfIP := parseCFConnectingIP(r.Header); cfIP != nil {
			return normalizeRateLimitIP(cfIP)
		}
	}

	if peerIP != nil {
		return normalizeRateLimitIP(peerIP)
	}
	return host
}

func isTrustedProxy(ip net.IP, trustedProxies []*net.IPNet) bool {
	for _, cidr := range trustedProxies {
		if cidr != nil && cidr.Contains(ip) {
			return true
		}
	}
	return false
}

// parseCFConnectingIP extracts a single valid IP from the CF-Connecting-IP
// header. Multiple header values, comma-separated lists, empty strings, or
// unparseable values are rejected (returns nil).
func parseCFConnectingIP(h http.Header) net.IP {
	vals := h.Values("CF-Connecting-IP")
	if len(vals) != 1 {
		return nil
	}
	raw := strings.TrimSpace(vals[0])
	if raw == "" || strings.Contains(raw, ",") {
		return nil
	}
	return net.ParseIP(raw)
}

var ipv6RateLimitMask = net.CIDRMask(64, 128)

// normalizeRateLimitIP returns a canonical rate-limit bucket key: IPv4
// addresses remain /32 (plain dotted-quad), while IPv6 addresses are masked
// to their /64 prefix.
func normalizeRateLimitIP(ip net.IP) string {
	if v4 := ip.To4(); v4 != nil {
		return v4.String()
	}
	v6 := ip.To16()
	if v6 == nil {
		return ip.String()
	}
	return v6.Mask(ipv6RateLimitMask).String() + "/64"
}
