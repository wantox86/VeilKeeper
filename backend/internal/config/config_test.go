package config

import (
	"net"
	"testing"
	"time"
)

func TestLoad_DefaultTrustedProxyCIDRs(t *testing.T) {
	t.Setenv("TRUSTED_PROXY_CIDRS", "")
	t.Setenv("ARGON2_MAX_CONCURRENT", "")
	t.Setenv("ARGON2_ACQUIRE_TIMEOUT_MS", "")

	cfg, err := Load(nil)
	if err != nil {
		t.Fatalf("Load returned unexpected error: %v", err)
	}

	if len(cfg.Auth.TrustedProxyCIDRs) != 3 {
		t.Fatalf("expected 3 default CIDRs, got %d", len(cfg.Auth.TrustedProxyCIDRs))
	}

	contains := func(ipStr string) bool {
		ip := net.ParseIP(ipStr)
		for _, c := range cfg.Auth.TrustedProxyCIDRs {
			if c.Contains(ip) {
				return true
			}
		}
		return false
	}

	for _, trusted := range []string{"127.0.0.1", "::1", "172.18.0.2"} {
		if !contains(trusted) {
			t.Errorf("expected default trusted CIDRs to include %s", trusted)
		}
	}
	for _, untrusted := range []string{"192.168.1.10", "10.0.0.5", "203.0.113.1"} {
		if contains(untrusted) {
			t.Errorf("expected default trusted CIDRs NOT to include %s", untrusted)
		}
	}

	if cfg.Auth.Argon2MaxConcurrent != DefaultArgon2MaxConcurrent {
		t.Errorf("expected default Argon2MaxConcurrent=%d, got %d", DefaultArgon2MaxConcurrent, cfg.Auth.Argon2MaxConcurrent)
	}
	if cfg.Auth.Argon2AcquireTimeout != DefaultArgon2AcquireTimeout {
		t.Errorf("expected default Argon2AcquireTimeout=%s, got %s", DefaultArgon2AcquireTimeout, cfg.Auth.Argon2AcquireTimeout)
	}
}

func TestLoad_CustomTrustedProxyCIDRs(t *testing.T) {
	t.Setenv("TRUSTED_PROXY_CIDRS", "10.0.0.0/8, fd00::/8")

	cfg, err := Load(nil)
	if err != nil {
		t.Fatalf("Load returned unexpected error: %v", err)
	}
	if len(cfg.Auth.TrustedProxyCIDRs) != 2 {
		t.Fatalf("expected 2 CIDRs, got %d", len(cfg.Auth.TrustedProxyCIDRs))
	}
	if !cfg.Auth.TrustedProxyCIDRs[0].Contains(net.ParseIP("10.1.2.3")) {
		t.Error("expected 10.1.2.3 to match 10.0.0.0/8")
	}
	if !cfg.Auth.TrustedProxyCIDRs[1].Contains(net.ParseIP("fd00::1")) {
		t.Error("expected fd00::1 to match fd00::/8")
	}
}

func TestLoad_InvalidTrustedProxyCIDRs(t *testing.T) {
	cases := []string{
		"not-a-cidr",
		"127.0.0.1",
		"127.0.0.0/8,invalid",
		"127.0.0.0/8,",
		",",
		"127.0.0.0/99",
	}

	for _, raw := range cases {
		t.Run(raw, func(t *testing.T) {
			t.Setenv("TRUSTED_PROXY_CIDRS", raw)
			if _, err := Load(nil); err == nil {
				t.Fatalf("expected error for invalid TRUSTED_PROXY_CIDRS=%q, got nil", raw)
			}
		})
	}
}

func TestLoad_Argon2MaxConcurrentValidation(t *testing.T) {
	t.Run("valid positive", func(t *testing.T) {
		t.Setenv("ARGON2_MAX_CONCURRENT", "6")
		cfg, err := Load(nil)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.Auth.Argon2MaxConcurrent != 6 {
			t.Fatalf("expected 6, got %d", cfg.Auth.Argon2MaxConcurrent)
		}
	})

	for _, raw := range []string{"0", "-1", "-100", "abc", "1.5"} {
		t.Run("invalid_"+raw, func(t *testing.T) {
			t.Setenv("ARGON2_MAX_CONCURRENT", raw)
			if _, err := Load(nil); err == nil {
				t.Fatalf("expected error for ARGON2_MAX_CONCURRENT=%q, got nil", raw)
			}
		})
	}
}

func TestLoad_Argon2AcquireTimeoutValidation(t *testing.T) {
	t.Run("valid positive", func(t *testing.T) {
		t.Setenv("ARGON2_ACQUIRE_TIMEOUT_MS", "250")
		cfg, err := Load(nil)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if cfg.Auth.Argon2AcquireTimeout != 250*time.Millisecond {
			t.Fatalf("expected 250ms, got %s", cfg.Auth.Argon2AcquireTimeout)
		}
	})

	for _, raw := range []string{"0", "-1", "-500", "abc", "1.5"} {
		t.Run("invalid_"+raw, func(t *testing.T) {
			t.Setenv("ARGON2_ACQUIRE_TIMEOUT_MS", raw)
			if _, err := Load(nil); err == nil {
				t.Fatalf("expected error for ARGON2_ACQUIRE_TIMEOUT_MS=%q, got nil", raw)
			}
		})
	}
}
