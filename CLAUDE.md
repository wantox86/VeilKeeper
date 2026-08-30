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
  APK artifact upload), `security.yml` (gitleaks secret scanning + `govulncheck`). Not yet
  confirmed green on GitHub itself (see note below) — locally, all equivalent commands
  (`gofmt`, `go vet`, `go test`, `go build`, `docker build`, and the full Android Gradle build
  including `assembleDebug`/`testDebugUnitTest`/`lintDebug` via a locally-provisioned SDK) were
  run and pass.
- `README.md` at repo root: quickstart, repo layout, local dev instructions for both backend and
  Android, CI overview.

**Note for next session/sprint**: push to `main` and the first live GitHub Actions run were
expected to happen right after this Sprint 0 work — check the Actions tab / `gh run list` to
confirm the workflows are actually green on GitHub's runners (local verification is thorough but
GitHub-hosted Android SDK provisioning via `android-actions/setup-android` was not itself
exercised locally, only a manually-provisioned local SDK was used to validate the Gradle build
logic).

Not started: Sprint 1 (Authentication) onward — no auth/vault/encryption code exists yet.
