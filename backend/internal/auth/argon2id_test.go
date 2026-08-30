package auth

import "testing"

func TestHashAndVerifyAuthKey_RoundTrip(t *testing.T) {
	key := []byte("a-32-byte-ish-authkey-value-123")

	encoded, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}

	ok, err := VerifyAuthKey(key, encoded)
	if err != nil {
		t.Fatalf("VerifyAuthKey: %v", err)
	}
	if !ok {
		t.Fatal("expected correct auth key to verify")
	}
}

func TestVerifyAuthKey_WrongKeyRejected(t *testing.T) {
	encoded, err := HashAuthKey([]byte("correct-key"))
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}

	ok, err := VerifyAuthKey([]byte("wrong-key"), encoded)
	if err != nil {
		t.Fatalf("VerifyAuthKey: %v", err)
	}
	if ok {
		t.Fatal("expected wrong auth key to be rejected")
	}
}

func TestHashAuthKey_DistinctSaltsPerCall(t *testing.T) {
	// Same input key, hashed twice, must produce different encoded output
	// (unique per-hash salt) -- otherwise identical AuthKeys would be
	// visibly identical in the DB.
	key := []byte("same-key-both-times")

	h1, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}
	h2, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}
	if h1 == h2 {
		t.Fatal("expected distinct encoded hashes for two calls with the same key (salt reuse?)")
	}
}

func TestHashAuthKey_RejectsEmptyKey(t *testing.T) {
	if _, err := HashAuthKey(nil); err == nil {
		t.Fatal("expected error hashing an empty auth key")
	}
}

func TestVerifyAuthKey_MalformedHashRejected(t *testing.T) {
	if _, err := VerifyAuthKey([]byte("anything"), "not-a-real-hash"); err == nil {
		t.Fatal("expected error verifying against a malformed hash string")
	}
}

func TestVerifyAgainstDummyHash_DoesNotPanic(t *testing.T) {
	// Just exercising the timing-safety helper: it must never panic
	// regardless of input, since it runs on the "user not found" login path.
	VerifyAgainstDummyHash([]byte("anything"))
	VerifyAgainstDummyHash(nil)
}
