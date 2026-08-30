package auth

import (
	"bytes"
	"testing"
)

func TestValidateKDFParams(t *testing.T) {
	cases := []struct {
		name    string
		params  KDFParams
		wantErr bool
	}{
		{"default is valid", DefaultKDFParams, false},
		{"memory too low", KDFParams{MemoryKiB: 1024, Iterations: 3, Parallelism: 4}, true},
		{"memory too high", KDFParams{MemoryKiB: 1024 * 1024, Iterations: 3, Parallelism: 4}, true},
		{"zero iterations", KDFParams{MemoryKiB: 64 * 1024, Iterations: 0, Parallelism: 4}, true},
		{"zero parallelism", KDFParams{MemoryKiB: 64 * 1024, Iterations: 3, Parallelism: 0}, true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateKDFParams(tc.params)
			if (err != nil) != tc.wantErr {
				t.Fatalf("ValidateKDFParams(%+v) error = %v, wantErr %v", tc.params, err, tc.wantErr)
			}
		})
	}
}

func TestFakeSalt_DeterministicPerEmail(t *testing.T) {
	pepper := []byte("test-pepper")

	a1 := FakeSalt(pepper, "nobody@example.com")
	a2 := FakeSalt(pepper, "nobody@example.com")
	if !bytes.Equal(a1, a2) {
		t.Fatal("expected FakeSalt to be deterministic for the same email+pepper")
	}
}

func TestFakeSalt_DiffersByEmail(t *testing.T) {
	pepper := []byte("test-pepper")

	a := FakeSalt(pepper, "one@example.com")
	b := FakeSalt(pepper, "two@example.com")
	if bytes.Equal(a, b) {
		t.Fatal("expected FakeSalt to differ across different emails")
	}
}

func TestFakeSalt_CaseAndWhitespaceInsensitive(t *testing.T) {
	pepper := []byte("test-pepper")

	a := FakeSalt(pepper, "Someone@Example.com")
	b := FakeSalt(pepper, "  someone@example.com  ")
	if !bytes.Equal(a, b) {
		t.Fatal("expected FakeSalt to normalize case/whitespace like real email lookups do")
	}
}

func TestFakeSalt_DiffersByPepper(t *testing.T) {
	a := FakeSalt([]byte("pepper-one"), "same@example.com")
	b := FakeSalt([]byte("pepper-two"), "same@example.com")
	if bytes.Equal(a, b) {
		t.Fatal("expected FakeSalt to differ across different peppers")
	}
}
