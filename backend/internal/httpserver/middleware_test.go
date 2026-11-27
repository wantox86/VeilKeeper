package httpserver

import (
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

func mustParseCIDRs(t *testing.T, cidrs ...string) []*net.IPNet {
	t.Helper()
	out := make([]*net.IPNet, 0, len(cidrs))
	for _, c := range cidrs {
		_, ipNet, err := net.ParseCIDR(c)
		if err != nil {
			t.Fatalf("parse CIDR %q: %v", c, err)
		}
		out = append(out, ipNet)
	}
	return out
}

func okHandler(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
}

func TestRateLimited_TrustedProxy_CFConnectingIP_SeparateBuckets(t *testing.T) {
	limiter := auth.NewIPLimiter(1, time.Minute)
	defer limiter.Close()

	trusted := mustParseCIDRs(t, "127.0.0.0/8", "::1/128", "172.16.0.0/12")
	h := rateLimited(limiter, trusted, okHandler)

	// First request from client A via trusted Docker bridge peer.
	reqA1 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	reqA1.RemoteAddr = "172.18.0.2:40001"
	reqA1.Header.Set("CF-Connecting-IP", "203.0.113.10")
	recA1 := httptest.NewRecorder()
	h(recA1, reqA1)
	if recA1.Code != http.StatusOK {
		t.Fatalf("client A 1st request: expected 200, got %d", recA1.Code)
	}

	// Second request from client A is rate-limited.
	reqA2 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	reqA2.RemoteAddr = "172.18.0.2:40002"
	reqA2.Header.Set("CF-Connecting-IP", "203.0.113.10")
	recA2 := httptest.NewRecorder()
	h(recA2, reqA2)
	if recA2.Code != http.StatusTooManyRequests {
		t.Fatalf("client A 2nd request: expected 429, got %d", recA2.Code)
	}

	// Request from client B through the SAME proxy peer has its own bucket.
	reqB := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	reqB.RemoteAddr = "172.18.0.2:40003"
	reqB.Header.Set("CF-Connecting-IP", "203.0.113.11")
	recB := httptest.NewRecorder()
	h(recB, reqB)
	if recB.Code != http.StatusOK {
		t.Fatalf("client B 1st request: expected 200, got %d", recB.Code)
	}
}

func TestRateLimited_UntrustedPeer_SpoofedCFHeaderIgnored(t *testing.T) {
	limiter := auth.NewIPLimiter(1, time.Minute)
	defer limiter.Close()

	trusted := mustParseCIDRs(t, "127.0.0.0/8", "::1/128", "172.16.0.0/12")
	h := rateLimited(limiter, trusted, okHandler)

	// Untrusted LAN peer tries to spoof CF-Connecting-IP with different IPs.
	req1 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	req1.RemoteAddr = "192.168.50.99:55555"
	req1.Header.Set("CF-Connecting-IP", "198.51.100.1")
	rec1 := httptest.NewRecorder()
	h(rec1, req1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("1st request: expected 200, got %d", rec1.Code)
	}

	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	req2.RemoteAddr = "192.168.50.99:55556"
	req2.Header.Set("CF-Connecting-IP", "198.51.100.2")
	rec2 := httptest.NewRecorder()
	h(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("2nd request from untrusted peer with spoofed header: expected 429, got %d", rec2.Code)
	}
}

func TestRateLimited_IPv6SameSlash64SharesBucket(t *testing.T) {
	limiter := auth.NewIPLimiter(1, time.Minute)
	defer limiter.Close()

	trusted := mustParseCIDRs(t, "127.0.0.0/8", "::1/128", "172.16.0.0/12")
	h := rateLimited(limiter, trusted, okHandler)

	req1 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	req1.RemoteAddr = "[::1]:12345"
	req1.Header.Set("CF-Connecting-IP", "2001:db8:abcd:1234::1")
	rec1 := httptest.NewRecorder()
	h(rec1, req1)
	if rec1.Code != http.StatusOK {
		t.Fatalf("1st IPv6 request: expected 200, got %d", rec1.Code)
	}

	// Different host bits in the same /64 must share the same bucket.
	req2 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	req2.RemoteAddr = "[::1]:12346"
	req2.Header.Set("CF-Connecting-IP", "2001:db8:abcd:1234:ffff:ffff:ffff:ffff")
	rec2 := httptest.NewRecorder()
	h(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("2nd IPv6 request in same /64: expected 429, got %d", rec2.Code)
	}

	// Different /64 gets its own bucket.
	req3 := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
	req3.RemoteAddr = "[::1]:12347"
	req3.Header.Set("CF-Connecting-IP", "2001:db8:abcd:1235::1")
	rec3 := httptest.NewRecorder()
	h(rec3, req3)
	if rec3.Code != http.StatusOK {
		t.Fatalf("IPv6 request in different /64: expected 200, got %d", rec3.Code)
	}
}

func TestClientIP_InvalidAndMultiValueCFHeaderFallbackToRemoteAddr(t *testing.T) {
	trusted := mustParseCIDRs(t, "127.0.0.0/8")

	cases := []struct {
		name   string
		setup  func(h http.Header)
		expect string
	}{
		{
			name:   "empty",
			setup:  func(h http.Header) { h.Set("CF-Connecting-IP", "") },
			expect: "127.0.0.1",
		},
		{
			name:   "invalid IP",
			setup:  func(h http.Header) { h.Set("CF-Connecting-IP", "not-an-ip") },
			expect: "127.0.0.1",
		},
		{
			name:   "comma-separated multi-value",
			setup:  func(h http.Header) { h.Set("CF-Connecting-IP", "203.0.113.1, 203.0.113.2") },
			expect: "127.0.0.1",
		},
		{
			name: "multiple header lines",
			setup: func(h http.Header) {
				h.Add("CF-Connecting-IP", "203.0.113.1")
				h.Add("CF-Connecting-IP", "203.0.113.2")
			},
			expect: "127.0.0.1",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
			req.RemoteAddr = "127.0.0.1:43210"
			tc.setup(req.Header)
			if got := clientIP(req, trusted); got != tc.expect {
				t.Fatalf("expected fallback %q, got %q", tc.expect, got)
			}
		})
	}
}

func TestRateLimited_ParallelRaceSafe(t *testing.T) {
	limiter := auth.NewIPLimiter(100, time.Minute)
	defer limiter.Close()

	trusted := mustParseCIDRs(t, "127.0.0.0/8", "172.16.0.0/12")
	h := rateLimited(limiter, trusted, okHandler)

	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 50; i++ {
				req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/prelogin", nil)
				req.RemoteAddr = "172.18.0.2:12345"
				req.Header.Set("CF-Connecting-IP", "203.0.113.1")
				rec := httptest.NewRecorder()
				h(rec, req)
			}
		}(g)
	}
	wg.Wait()
}
