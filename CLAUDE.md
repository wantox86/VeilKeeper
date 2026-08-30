# VeilKeeper — Claude Code Guide

> `SPEC-BASE.md` in this repo is the original product spec (source: `spec.md`, written for a
> parallel implementation being built with Qoder + Kimi K3 under the name "Veil Keepers"). This
> repo is an **independent implementation of the same product spec, built by Claude Code, for
> comparison purposes** — same features, same phases, different codebase/repo name
> (`VeilKeeper`, no space, to avoid any confusion with the Qoder build). Do not coordinate with
> or read from the Qoder build's repo/containers; this must stay a clean-room comparison.
>
> This file holds decisions that resolve ambiguities the base spec left open (see "Resolved
> Design Decisions" below), plus "Current State" once sprints start landing. Update "Current
> State" as sprints complete.

## Why this repo exists

The user is comparing two coding agents (Qoder+Kimi K3 vs Claude Code) building the *same* spec
independently, to evaluate output quality/approach. The Qoder build already exists and runs in
Docker on this same host (MACMINI) under compose project `vk-sprint3`
(`vk-sprint3-veilkeepers-api-1`, host port `18080`; `vk-sprint3-veilkeepers-mysql-1`, no host
port). **This project's Docker Compose must never collide with that** — see "Docker naming"
below.

## Resolved Design Decisions (read before implementing anything crypto-related)

The base spec (`SPEC-BASE.md`) deliberately left several things ambiguous and instructed the
coding agent to stop and ask rather than improvise (see its own Section 56, Rule 2). These were
resolved with the user before Sprint 0 started. **These decisions are authoritative — they
override/clarify SPEC-BASE.md Sections 8-11, 25, 26.**

### 1. Vault key architecture — password-derived, with a wrapped Data Key

The user explicitly chose "password-derived key" (SPEC-BASE.md Section 11's "if password-derived
key is required" — confirmed: yes). Refined design to keep password rotation and multi-device
login cheap without weakening zero-knowledge:

1. **Registration:**
   - Server generates `kdf_salt` (16 random bytes) and `kdf_params` (Argon2id: e.g.
     `memory=64MB, iterations=3, parallelism=4` — must be documented + versioned via a
     `kdf_version` int, so params can be upgraded later without breaking old accounts) at account
     creation. Stored server-side in `users.kdf_salt` / `users.kdf_params` / `users.kdf_version`.
   - Client derives `MasterKey = Argon2id(password, kdf_salt, kdf_params)` — 32 bytes, stays on
     device, **never transmitted**.
   - Client derives two domain-separated subkeys from `MasterKey` via HKDF-SHA256:
     - `AuthKey = HKDF(MasterKey, info="veilkeeper:auth:v1")` — sent to the server instead of the
       raw password (server never sees the actual password or `MasterKey`).
     - `WrapKey = HKDF(MasterKey, info="veilkeeper:wrap:v1")` — never leaves the device.
   - Client generates a random 32-byte `VaultDataKey` (VDK) — the key that actually encrypts every
     vault item and attachment (AES-256-GCM). Client wraps it: `wrapped_vdk =
     AES-256-GCM_encrypt(VDK, key=WrapKey)`.
   - Client sends `AuthKey` (server hashes it at rest with Argon2id/bcrypt, same as any password
     hash — this is standard "hash what you receive" practice, not a weakening) and `wrapped_vdk`
     (opaque ciphertext) to the server at registration.
2. **Login (any device):**
   - `POST /api/v1/auth/prelogin` (unauthenticated, input: email) returns `{kdf_salt, kdf_params,
     kdf_version}`. **Anti-enumeration**: for a non-existent email, return a deterministic fake
     salt (`HMAC-SHA256(server_pepper, email)` truncated to 16 bytes) with the current default
     params, so responses for real vs fake accounts are indistinguishable.
   - Client derives `MasterKey` → `AuthKey`/`WrapKey` locally (same math as registration).
   - Client sends `AuthKey` to `/api/v1/auth/login`; server verifies against the stored hash,
     issues a session, and returns `wrapped_vdk`.
   - Client unwraps `VDK` locally with `WrapKey`. **Any device with the correct password
     reconstructs the same VDK with zero extra sync/pairing step** — this is what makes
     multi-device "just work" under a password-derived design.
3. **Password change:**
   - Client re-derives the old `MasterKey`/`WrapKey` (from the current password) to unwrap the
     existing VDK.
   - Server issues a **new** `kdf_salt` (rotate salt on password change — good hygiene, prevents
     precomputation reuse).
   - Client derives new `MasterKey`/`WrapKey` from the new password + new salt, re-wraps the
     **same** VDK, uploads the new `wrapped_vdk` + new `AuthKey` hash.
   - **No vault item is ever re-encrypted on password change** — only the wrapping changes. This
     is the whole point of the VDK/WrapKey split over using a bare password-derived key directly
     on every item.

### 2. Forgot-password / account recovery — none in V0.1, by design

True zero-knowledge tradeoff: if the password is lost, the vault is unrecoverable (nobody, not
even the server operator, can reset it, because the server never had a usable key). This must be
disclosed explicitly in the Register screen UI copy (e.g. "If you forget your password, your
vault cannot be recovered — there is no backdoor, by design"). Do not build a recovery mechanism
in V0.1 (matches the spec's own "no premature overengineering" principle). The VDK/WrapKey split
above already leaves room for an optional V0.2+ "Recovery Key" (a second independent wrap of the
same VDK) without touching existing data or re-architecting.

### 3. Local device cache / biometric unlock

- After a successful login+unwrap, the app may cache the VDK **encrypted** using an Android
  Keystore-backed AES key (hardware-backed where available). The raw VDK lives in memory only
  while the vault is unlocked; on auto-lock/background timeout, the in-memory copy is cleared and
  only the Keystore-wrapped blob remains at rest.
- Biometric unlock (`BiometricPrompt`) authorizes the Keystore to decrypt the locally cached
  wrapped-VDK blob — this never touches the network, matching SPEC-BASE.md Section 25's
  requirement that biometric auth must not directly authenticate against the backend.

### 4. Local search cache

Decrypted vault items used for local search must not be persisted to disk in plaintext. Cache
them in a Room database encrypted at rest (SQLCipher or equivalent) keyed by a device-local key
that is itself Keystore-protected, OR keep the decrypted index in memory only for the unlocked
session (simplest for V0.1; revisit if offline search across app restarts becomes a real
requirement). Either way: nothing decrypted ever reaches disk unencrypted.

## Docker naming (must not collide with the Qoder build)

The Qoder build (`vk-sprint3-veilkeepers-api-1` / `vk-sprint3-veilkeepers-mysql-1`, API on host
port `18080`) already runs on this same MACMINI Docker host. This repo's `docker-compose.yml`
must set an explicit `name:` field distinct from that, e.g.:

```yaml
name: veilkeeper
services:
  api:
    ...
    ports:
      - "18091:8080"   # NOT 18080 -- that's the Qoder build's port
  mysql:
    ...
    # no host port published -- internal-only, same good practice the Qoder build already uses
```

Verify with `docker ps` before bringing the stack up that no container/port name actually
collides, in case the Qoder build's naming changes between sprints.

## Current State

**Sprint 0 (Project Bootstrap) — complete.**

Delivered:

- Repo structure: `backend/` (Go), `android/` (Kotlin/Compose), `infra/mysql/init/` (MySQL init
  scripts), `data/attachments/` (bind-mount target), `docs/` (empty, for later sprints),
  `.github/workflows/`.
- Go backend skeleton (`backend/cmd/api`, `internal/config`, `internal/db`,
  `internal/httpserver`): stdlib `net/http` + Go 1.22+ method-pattern `ServeMux`, no framework.
  Only dependency: `github.com/go-sql-driver/mysql`. `GET /health` (pure liveness) and
  `GET /ready` (checks MySQL reachability via `PingContext`, returns 503 if down, per
  SPEC-BASE.md Section 54). Unit tests cover both, including a test asserting `/ready` never
  leaks internal error details in the response body.
- Android skeleton (`android/app`): Kotlin + Jetpack Compose + Material 3, package
  `id.quezacolt.veilkeeper`, minSdk 26 / targetSdk 35 / compileSdk 35, AGP 8.6.1, Kotlin 2.0.21.
  Single bootstrap screen (no login/vault UI yet). Gradle wrapper (8.9) generated and committed.
  `android:allowBackup="false"` set intentionally (zero-knowledge vault, revisit with an
  explicit backup policy in a later sprint).
- Docker Compose (`docker-compose.yml` at repo root, `name: veilkeeper`): `api` (built from
  `backend/Dockerfile`, multi-stage Go build → non-root Alpine runtime, host port **18091**) +
  `mysql` (8.4, no host port published, healthcheck-gated). Verified end-to-end: fresh
  `docker compose up -d` succeeds, `/health` and `/ready` both respond correctly, `docker ps`
  confirms zero collision with the Qoder build's `vk-sprint3-veilkeepers-api-1` (port 18080) /
  `vk-sprint3-veilkeepers-mysql-1` (both stacks ran simultaneously during verification), and
  `docker compose down -v` cleans up fully.
- `.env.example` at repo root with placeholder values only; real `.env` is gitignored.
- GitHub Actions: `backend.yml` (gofmt check, `go vet`, `go test -race -cover`, `go build`,
  Docker image build), `android.yml` (Gradle `assembleDebug`, `testDebugUnitTest`, `lintDebug`,
  APK artifact upload), `security.yml` (gitleaks secret scanning + `govulncheck`). **Confirmed
  green on GitHub Actions** (`gh run list` on commit `ca76be7`): Backend CI, Android CI, Security
  CI all passed. One fix needed along the way: `govulncheck` initially failed because
  `setup-go@v5` pinned to `go-version: "1.23"` resolved to `go1.23.12`, which has several
  since-patched stdlib CVEs (crypto/tls, net/http, crypto/x509) — not a bug in our code, just an
  outdated toolchain patch level. Fixed by bumping `backend/go.mod`, `backend/Dockerfile`'s base
  image, and both CI workflows' `go-version` to `1.25`.
- `README.md` at repo root: quickstart, repo layout, local dev instructions for both backend and
  Android, CI overview.

**Sprint 1 (Authentication) — complete.**

Delivered:

- Backend (`backend/internal/auth`, `internal/store`, `internal/httpserver`): `POST
  /api/v1/auth/{prelogin,register,login,logout}` implementing the full CLAUDE.md Resolved Design
  Decision #1 flow. `auth_key_hash` is Argon2id (`golang.org/x/crypto/argon2`, OWASP-minimum
  params m=19MiB/t=2/p=1 — deliberately lighter than the client-side KDF params since the
  server only ever hashes an already-high-entropy AuthKey, not a raw password; see doc comment
  in `internal/auth/argon2id.go`). Session tokens are opaque 256-bit random values; only their
  SHA-256 hash is stored (`sessions.token_hash`). `/auth/prelogin` anti-enumeration uses
  `HMAC-SHA256(SERVER_PEPPER, email)` as a deterministic fake salt for nonexistent accounts;
  login additionally burns comparable CPU time on a "user not found" path via a fixed dummy
  Argon2id verify, so response timing doesn't leak account existence either. Rate limiting: a
  minimal in-memory per-IP sliding window (`internal/auth/ratelimit.go`, `IPLimiter`) on all
  `/api/v1/auth/*` routes, plus a per-email account lockout (5 failures / 15 min window → 5 min
  lockout) applied identically regardless of whether the email is a real account.
  **Deliberate deviation from CLAUDE.md's literal wording**: CLAUDE.md says the server
  "generates kdf_salt ... at account creation," but that's a chicken-and-egg problem for a
  single-round-trip `/register` call (the client needs the salt *before* it can derive AuthKey to
  send). Since `kdf_salt` isn't secret, the **client generates it** (CSPRNG) and sends it
  alongside `kdf_params`/`kdf_version`/`auth_key`/`wrapped_vdk` in one call; the server validates
  `kdf_params` are within a sane range and stores everything verbatim. Documented in detail in
  `handleRegister`'s doc comment (`backend/internal/httpserver/auth_handlers.go`). Migration:
  `infra/mysql/init/002-auth-schema.sql` (`users`/`devices`/`sessions`, matching SPEC-BASE.md
  Section 31 with the kdf_salt/kdf_params/kdf_version/wrapped_vdk/auth_key_hash fields CLAUDE.md
  requires). New env vars (see `.env.example`): `SERVER_PEPPER`, `SESSION_TTL_HOURS`,
  `AUTH_RATE_LIMIT_REQUESTS`. Unit tests (auth package + httpserver package, using an in-memory
  fake `store.AuthStore` — no MySQL needed) cover: hash round-trip, wrong-key rejection, unique
  salts per hash, KDF param validation, fake-salt determinism/uniqueness, rate limiter and
  account lockout behavior, and all four handlers including duplicate-email/wrong-auth-key/
  locked-account/idempotent-logout paths. `go test -race -cover ./...`: 44 tests, all passing.
  Manually verified end-to-end against a fresh `docker compose up -d` (register → prelogin →
  duplicate-register(409) → login(wrong key, 401) → login(correct) → logout → logout again
  (idempotent 204) via `curl`), confirmed via direct MySQL query that `auth_key_hash` is an
  Argon2id string (never the raw key), `wrapped_vdk` is opaque ciphertext, and `sessions.token_hash`
  is a hash (never the raw bearer token) — then `docker compose down -v` to tear down. `docker ps`
  reconfirmed zero collision with the Qoder build throughout.
- Android (`android/app/.../crypto`, `.../data`, `.../ui/auth`): real Login and Register screens
  (SPEC-BASE.md Section 18.1/18.2) wired to actual client-side crypto and the backend API —
  **not** a mock/stub. Key hierarchy exactly per CLAUDE.md Decision #1: Argon2id
  (`com.lambdapioneer.argon2kt:argon2kt` — an Android-specific JNI binding shipping prebuilt
  native libs; chosen because Argon2id has no JDK/Android-stdlib equivalent, unlike HKDF and
  AES-GCM below) → MasterKey → HKDF-SHA256 (hand-rolled RFC 5869 over stdlib
  `javax.crypto.Mac`, no extra dependency needed) → AuthKey/WrapKey → AES-256-GCM
  (`javax.crypto.Cipher`, stdlib) wrap/unwrap of a randomly-generated VaultDataKey. Retrofit +
  OkHttp + kotlinx.serialization for the network layer; Register screen shows the mandatory
  "no password recovery" disclosure (CLAUDE.md Decision #2) as a dedicated notice card, not fine
  print. Session token + unwrapped VDK are held in-memory only (`AuthSessionHolder`) — no disk
  persistence yet, since the Keystore-backed encrypted cache is explicitly Sprint 3 scope
  (Decision #3); this is a disclosed Sprint 1 simplification, not an oversight.
  **Known, disclosed testing limitation**: Argon2Kt's native `.so` libraries target Android
  device/emulator ABIs and cannot load in a plain host-JVM process — so the *real* Argon2id call
  (`Argon2idMasterKeyDeriver`) cannot run under `testDebugUnitTest` (which is also all that
  `android.yml` CI actually runs; no emulator step exists there, and none was available in this
  sprint's implementation environment either). To keep everything else genuinely unit-tested on
  the host JVM, `VaultCrypto` depends on a `MasterKeyDeriver` interface; tests substitute a
  deterministic `FakeMasterKeyDeriver` (real HKDF + real AES-GCM still exercised end-to-end). An
  instrumented test for the real Argon2id path exists
  (`androidTest/.../Argon2idMasterKeyDeriverInstrumentedTest.kt`) but could not be run here and
  should be run manually (`./gradlew connectedAndroidTest`) on a real device/emulator before this
  ships to end users. Unit tests added: `HkdfTest` (validated against a real RFC 5869 SHA-256
  test vector, not just self-consistency), `AesGcmTest` (SPEC-BASE.md Section 47 round-trip +
  unique-nonce-per-call + tamper/wrong-key detection), `VaultCryptoTest` (full key-hierarchy
  round trip with the fake KDF), `AuthRepositoryTest` and `LoginViewModelTest`/
  `RegisterViewModelTest` (against a fake `AuthApi`, covering success/401/409/429 paths).
  `./gradlew assembleDebug testDebugUnitTest lintDebug`: all green (34 unit tests passing, 0
  lint errors, pre-existing/unrelated lint warnings only e.g. obsolete BOM version from Sprint 0).
  Android SDK/build-tools were not pre-installed in this sprint's sandbox; installed via
  `brew install --cask android-commandlinetools` + `sdkmanager` to actually run these checks
  locally rather than only trusting CI.
- `.env.example` and this file both updated for the new auth-related config/state.

Not started: Sprint 2 (Vault Foundation) onward.
