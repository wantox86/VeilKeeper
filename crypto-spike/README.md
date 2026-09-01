# Spike: Cross-platform Argon2id for Web client (KMP / Kotlin-Wasm feasibility)

**Status:** Spike complete. Recommendation: proceed to Web client build, but NOT via a
pure-Kotlin-Wasm Argon2id implementation — see recommendation below.

**Scope:** This is research only. No Android production code was touched. No backend/Docker
was touched. Nothing here is wired into the app build.

## Question

Can Argon2id be implemented once in Kotlin (KMP), compiled to both `jvm`/Android and
`wasmJs`, so Android and a future Web client derive byte-identical keys from the same
password?

## What was checked

1. **`ionspin/kotlin-multiplatform-crypto`** — pure-Kotlin Argon2id implementation.
   - No `jvm`/Android or `wasmJs` target in its supported-platforms table (native/Linux/
     macOS/iOS/watchOS/tvOS/mingw only).
   - README explicitly says **not production-ready**: "under heavy development... not peer
     reviewed, not guaranteed to be bug free, and not guaranteed to be secure."
   - Last commit: 2021-11-30. Abandoned for ~5 years.
   - **Verdict: not usable.** Wrong targets, unaudited, unmaintained.

2. **`ionspin/kotlin-multiplatform-libsodium`** — actively maintained (last push 2026-06-09),
   partial community audit for v0.9.2, supports `jvm`/Android + native + legacy `js`
   (via libsodium.js), but **no confirmed `wasmJs`/Kotlin-Wasm target**. Also still says "not
   recommended for production until reviewed by the community" more broadly.
   - **Verdict: doesn't solve the wasmJs requirement**, and Android side would mean
     replacing argon2kt with a different native binding — a bigger, separate migration than
     what's being asked here.

3. **No other pure-Kotlin, wasmJs-targeting, audited Argon2id implementation was found.**
   This matches general KMP-crypto-ecosystem maturity as of 2026: crypto libraries lag
   language target support, and Argon2id specifically (memory-hard, needs raw memory
   access) is a harder Kotlin/Wasm port than stream ciphers/hashes.

## What was actually proven to work

Instead of "one Kotlin source → two targets," the viable path is: **keep Android's
existing `argon2kt` (native binding to the unmodified P-H-C reference C implementation,
https://github.com/P-H-C/phc-winner-argon2) exactly as-is, and use a WASM build of the
*same* unmodified reference C implementation for the Web client** — `argon2-browser`
(npm, MIT license, https://github.com/antelle/argon2-browser). Its README states
plainly: "Is Argon2 modified? No, it's used as a submodule from upstream." Since both
sides trace to the same unmodified C source and Argon2id is a deterministic, standardized
algorithm (RFC 9106), matching parameters guarantee matching output — this doesn't
depend on Kotlin/Wasm at all, and is lower-risk than any custom/ported Kotlin
implementation.

### Proof (`poc/verify-argon2-wasm.cjs`, `poc/verify-argon2-native.py`)

Using VeilKeeper's actual production `KdfParams.DEFAULT` (memory = 64 MiB / 65536 KiB,
iterations = 3, parallelism = 4, hash length = 32 bytes, Argon2 version 0x13, mode
Argon2id — see `android/.../crypto/KdfParams.kt` and `Argon2idMasterKeyDeriver.kt`):

| Source | Result |
|---|---|
| `argon2-browser` (WASM, unmodified P-H-C reference C, run in Node as browser-equivalent) | `853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e` |
| `argon2-cffi` (Python binding to the native reference C impl — same lineage as `argon2kt`'s `.so`) | `853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e` |

**Byte-identical.**

Also validated `argon2-browser` against the **official RFC 9106 §5.3 Argon2id test
vector** (p=4, T=32, m=32 KiB, t=3, v=0x13, password/salt/secret/AD all fixed patterns):

```
computed: 0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659
expected: 0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659
```

**Match.** This confirms the WASM build is a correct, spec-compliant Argon2id
implementation, not just "consistent with itself."

### Reproduce

```bash
cd crypto-spike/poc
npm install argon2-browser
node verify-argon2-wasm.cjs

python3 -m venv venv && ./venv/bin/pip install argon2-cffi
./venv/bin/python3 verify-argon2-native.py
```

## Why no real KMP `wasmJs` Gradle module was built

Given the above, building a full Kotlin/Wasm Gradle module (downloading the Kotlin/Wasm
compiler backend + Binaryen toolchain, etc.) would only be proving that Kotlin/Wasm's
standard, well-established JS-interop feature (`external` declarations calling into JS)
can call `argon2-browser`'s exported functions — glue code, not a research risk. That
interop mechanism is a documented, stable Kotlin/Wasm capability, not something in
question here. The actual open question — "does a trustworthy Argon2id implementation
exist for the browser that's compatible with Android's" — is answered above with
byte-identical, RFC-vector-verified proof. Building the Gradle scaffolding was judged
not worth the time given this is a spike, but it's a small, low-risk follow-up if the
team wants the artifact literally inside a KMP module.

## Recommendation

**Proceed to Web client build, with this crypto approach:**

- **Android: NO CHANGE.** Keep `Argon2idMasterKeyDeriver` / `argon2kt` exactly as
  shipped. Zero migration, zero breaking change, zero risk to existing users' vaults.
- **Web:** derive Argon2id via `argon2-browser` (or an equivalent WASM build of the
  same unmodified P-H-C reference source — `hash-wasm` is another maintained option
  worth a quick look, not evaluated here) called directly from TypeScript. Use the
  browser's native **Web Crypto API** for everything Web Crypto already supports well
  — HKDF and AES-256-GCM (both natively available) — per repo spec section 9's
  explicit requirement to use native Web Crypto API and not hand-roll primitives.
  Argon2id is the one primitive Web Crypto API doesn't provide, so it's the one place
  an external (WASM) library is unavoidable regardless of language choice.
- **Kotlin/Wasm is not required to solve the compatibility problem.** The
  cross-platform guarantee comes from "same unmodified reference C source, same
  parameters, standardized deterministic algorithm" — not from sharing Kotlin source
  code. If the team still wants shared *orchestration* logic (the HKDF-derivation
  chain / key-hierarchy bookkeeping in `VaultCrypto.kt`) across Android and Web via
  KMP for maintainability, that's a separate, much lower-risk KMP use case (HKDF and
  AES-GCM don't need memory-hard native code and are more portable) — but it's an
  optional nicety, not a blocker, and out of scope for this spike.

**No re-derivation of existing users' keys is needed under this recommendation** —
Android's Argon2id path, parameters, and salts are untouched. A Web login for an
existing user would run the *same* Argon2id parameters (fetched from the server's
stored `kdf_params`/`kdf_salt`, exactly as Android already does per
`AuthRepository`) through `argon2-browser` and arrive at the same MasterKey, because
the algorithm is proven byte-identical above.

## Migration complexity estimate

- Android: **none.**
- Backend: **none** (already stores/serves `kdf_salt` + `kdf_params` generically, per
  `KdfParams.kt` doc comment — this was already designed for exactly this).
- Web (new code, not migration): implement `crypto/` module using `argon2-browser` (or
  `hash-wasm`) for Argon2id + native Web Crypto API for HKDF/AES-GCM, mirroring
  `VaultCrypto.kt`'s key hierarchy (MasterKey → AuthKey / WrapKey via HKDF info
  strings `veilkeeper:auth:v1` / `veilkeeper:wrap:v1` → VDK wrap/unwrap). This is
  ordinary Phase 4 web-crypto implementation work, not a research risk anymore.
