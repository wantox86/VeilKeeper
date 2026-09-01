"""
Independent cross-check for the KMP/Web-crypto spike (see crypto-spike/FINDINGS.md).

Uses argon2-cffi (Python binding to the SAME P-H-C reference C implementation,
https://github.com/P-H-C/phc-winner-argon2, that Android's argon2kt native .so
also wraps) to compute Argon2id with VeilKeeper's actual production KdfParams
(see android/app/src/main/java/id/quezacolt/veilkeeper/crypto/KdfParams.kt).

Compare the printed hash hex against the output of verify-argon2-wasm.cjs
(same password/salt/params, computed via the WASM build). They must match
byte-for-byte.

Setup:
    python3 -m venv venv && ./venv/bin/pip install argon2-cffi
    ./venv/bin/python3 verify-argon2-native.py
"""
from argon2.low_level import hash_secret_raw, Type

# Matches KdfParams.DEFAULT in the Android app exactly:
# memory=64*1024 KiB, iterations=3, parallelism=4, hashLen=32 bytes
# (AesGcm.KEY_LENGTH_BYTES), version=0x13 (Argon2Version.V13), Argon2id.
password = b"correct horse battery staple"
salt = bytes.fromhex("000102030405060708090a0b0c0d0e0f")  # 16 bytes, matches KDF_SALT_LENGTH_BYTES

tag = hash_secret_raw(
    secret=password,
    salt=salt,
    time_cost=3,
    memory_cost=64 * 1024,
    parallelism=4,
    hash_len=32,
    type=Type.ID,
    version=19,
)
print("python argon2-cffi (native reference C impl):", tag.hex())
print("expected (from verify-argon2-wasm.cjs)       : 853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
print("MATCH:", tag.hex() == "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
