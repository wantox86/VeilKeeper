package main

import (
	"net/http"
	"testing"
	"time"
)

func TestNewHTTPServerTimeoutsAndLimits(t *testing.T) {
	mux := http.NewServeMux()
	srv := newHTTPServer(":8080", mux)

	if srv.Addr != ":8080" {
		t.Errorf("Addr = %q, want %q", srv.Addr, ":8080")
	}
	if srv.Handler != mux {
		t.Error("Handler was not set to provided mux")
	}

	checks := []struct {
		name string
		got  time.Duration
		want time.Duration
	}{
		{"ReadHeaderTimeout", srv.ReadHeaderTimeout, 10 * time.Second},
		{"ReadTimeout", srv.ReadTimeout, 120 * time.Second},
		{"WriteTimeout", srv.WriteTimeout, 120 * time.Second},
		{"IdleTimeout", srv.IdleTimeout, 120 * time.Second},
	}

	for _, tc := range checks {
		if tc.got <= 0 {
			t.Errorf("%s = %v, want > 0", tc.name, tc.got)
		}
		if tc.got != tc.want {
			t.Errorf("%s = %v, want %v", tc.name, tc.got, tc.want)
		}
	}

	if srv.MaxHeaderBytes <= 0 {
		t.Errorf("MaxHeaderBytes = %d, want > 0", srv.MaxHeaderBytes)
	}
	if srv.MaxHeaderBytes != 1<<20 {
		t.Errorf("MaxHeaderBytes = %d, want %d", srv.MaxHeaderBytes, 1<<20)
	}
}
