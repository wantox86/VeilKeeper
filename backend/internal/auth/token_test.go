package auth

import "testing"

func TestNewSessionToken_UniqueAndHashConsistent(t *testing.T) {
	token1, hash1, err := NewSessionToken()
	if err != nil {
		t.Fatalf("NewSessionToken: %v", err)
	}
	token2, hash2, err := NewSessionToken()
	if err != nil {
		t.Fatalf("NewSessionToken: %v", err)
	}

	if token1 == token2 {
		t.Fatal("expected two distinct session tokens")
	}
	if hash1 == hash2 {
		t.Fatal("expected two distinct token hashes")
	}
	if HashSessionToken(token1) != hash1 {
		t.Fatal("HashSessionToken(token1) must match the hash returned alongside it")
	}
	if HashSessionToken(token2) != hash2 {
		t.Fatal("HashSessionToken(token2) must match the hash returned alongside it")
	}
}

func TestHashSessionToken_Deterministic(t *testing.T) {
	if HashSessionToken("some-token") != HashSessionToken("some-token") {
		t.Fatal("expected HashSessionToken to be deterministic for the same input")
	}
}
