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

### 5. Delete category behavior (Sprint 2) — reassign to a lazily-created "Uncategorized" category

SPEC-BASE.md Section 14 requires deleting a category to never silently delete its vault items,
and lists two example safe behaviors: move items to another category, or move them to
"Uncategorized." This repo implements **both**, with the second as the default:

- `DELETE /api/v1/categories/{id}` accepts an optional `?reassign_to=<category_id>` query
  parameter. If present (and that category belongs to the same user), the deleted category's
  items are moved there.
- If absent, items are moved into the user's **Uncategorized** category, which is **not** one of
  the 5 default categories created at registration -- it's created lazily, on first use, the
  first time a category is deleted without an explicit `reassign_to`. This avoids a category
  showing up with 0 items forever for users who never delete anything.
- The Uncategorized category itself cannot be deleted or renamed (`409 system_category`) -- it's
  the safety net; deleting it would reintroduce the exact silent-data-loss problem this decision
  exists to prevent. It has no other special treatment (it's a normal category otherwise, visible
  in category lists, items can be moved in/out of it freely via normal item updates).
- Implementation note: finding-or-creating the Uncategorized category runs inside the same DB
  transaction as the reassignment + delete (`store.DeleteCategoryAndReassign`), so a concurrent
  double-delete from the same user can't create two Uncategorized rows (MySQL row locking
  serializes it) -- there's no DB-level uniqueness constraint enforcing "at most one Uncategorized
  category per user" because MySQL has no portable partial-unique-index for that; this is a
  documented, accepted simplification for a single-user-at-a-time homelab app.

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

**Sprint 2 (Vault Foundation) — complete.**

Delivered:

- Backend (`backend/internal/store` extended with `CategoryStore`/`VaultItemStore`;
  `backend/internal/httpserver/category_handlers.go`, `vault_handlers.go`,
  `session_middleware.go` new): `GET/POST /api/v1/categories`, `PUT/DELETE
  /api/v1/categories/{id}`, `GET/POST /api/v1/vault/items`, `GET/PUT/DELETE
  /api/v1/vault/items/{id}` -- all behind a new `requireSession` middleware that maps a bearer
  token to an authenticated user ID via the existing `sessions` table (Sprint 1), injected into
  the request context for every store call, enforcing ownership scoping (SPEC-BASE.md Section 30
  Section 47). Default categories (Common/Work/Tools/Personal/Other) are created automatically at
  registration (`store.CreateDefaultCategories`, called from `handleRegister`; a failure there
  logs but doesn't fail registration itself). Delete-category behavior: see "Resolved Design
  Decisions" #5 above (reassign to an explicit category or a lazily-created Uncategorized one).
  Vault items store `encrypted_payload` as an opaque `MEDIUMBLOB` (base64 over the wire) -- the
  server never decodes, inspects, or logs its contents, only moves bytes. Migration:
  `infra/mysql/init/003-vault-schema.sql` (`categories`, `vault_items`, with `vault_items.category_id`
  FK set `ON DELETE RESTRICT` as defense-in-depth since application code always reassigns before
  deleting a category). Unit tests (`category_handlers_test.go`, `vault_handlers_test.go`,
  `fake_store_test.go` extended to a full in-memory `store.Store` fake): default-categories-at-
  registration, category CRUD, delete-with-reassignment (both explicit `reassign_to` and the
  Uncategorized fallback), Uncategorized-cannot-be-deleted, vault item CRUD, category filter on
  list, **explicit user-isolation tests** for both categories and vault items (user B gets 404
  reading/renaming/deleting/creating-in user A's resources, per SPEC-BASE.md Section 47), session-
  required-on-all-vault-routes, and an end-to-end encryption round-trip test (AES-256-GCM
  encrypt -> store -> retrieve -> decrypt -> byte-identical, using a stand-in cipher matching the
  Android wire format -- the real client crypto is Android-side, see below). `go test -race
  -cover ./...`: 56 tests, all passing. `gofmt`/`go vet`: clean.
  Manually verified end-to-end against a fresh `docker compose up -d`: registered two users,
  confirmed default categories auto-created; created a category + vault item for user A via
  `curl`, confirmed via direct MySQL query that `vault_items.encrypted_payload` is opaque bytes
  (hex-dumped, matched the exact ciphertext sent, no plaintext title/content anywhere in the row);
  confirmed user B gets 404 reading/modifying user A's category and vault item by ID, and that
  user B's own `GET /api/v1/categories` never lists user A's rows; deleted user A's category
  containing an item with no `reassign_to`, confirmed the item survived and moved into a newly-
  auto-created "Uncategorized" category; confirmed deleting that Uncategorized category itself
  returns `409 system_category`. `docker ps` reconfirmed zero collision with the Qoder build
  throughout, then `docker compose down -v` to tear down.
- Android (`android/app/.../crypto/VaultItemCrypto.kt`, `.../data/VaultDtos.kt`, `VaultApi.kt`,
  `VaultRepository.kt`, `.../ui/home`, `.../ui/category`, `.../ui/vault`): Home screen
  (SPEC-BASE.md Section 18.3: category tiles with item counts + a "Recent" list), Category screen
  (Section 19: item list with a **local-only** search/filter over already-decrypted titles/previews
  -- never sent to the backend, per Section 16), Vault Detail screen (Section 20: content blocks
  rendered as cards, secrets hidden by default with a per-block reveal/copy per Section 22), and
  Add Item flow (Section 21: fast chip-based type picker for text/secret/note -- image/attachment
  is explicitly out of scope, Sprint 5). `VaultItemCrypto` serializes `{title, content[]}` to JSON
  (kotlinx.serialization) and encrypts/decrypts it with the VDK (unwrapped at login, held in the
  existing `AuthSessionHolder` from Sprint 1) via the existing `AesGcm` object -- no new crypto
  primitives introduced. `VaultRepository` is the single place that touches the VDK and calls
  `VaultItemCrypto`; screens/ViewModels only ever see decrypted domain models
  (`Category`/`DecryptedVaultItem`), never ciphertext or ID payload internals. Per-screen
  ViewModels (`HomeViewModel`, `CategoryViewModel`, `VaultDetailViewModel`, `AddItemViewModel`)
  built via `VaultViewModelFactory`'s `viewModelFactory { initializer { ... } }` DSL (since these
  need a route-scoped category/item ID baked into the constructor, unlike Sprint 1's single
  shared `AuthViewModelFactory`). `MainActivity`'s `NavHost` extended with `home`,
  `category/{categoryId}`, `item/{itemId}`, `add-item/{categoryId}` routes; `FLAG_SECURE` is kept
  activity-wide (SPEC-BASE.md Section 26) rather than toggled per-screen, since every screen in
  this app is vault-adjacent. Added `androidx.compose.material:material-icons-extended` dependency
  (Visibility/VisibilityOff/ContentCopy aren't in the small "core" icon set already used since
  Sprint 0). Unit tests added: `VaultItemCryptoTest` (full payload round-trip, unique-nonce,
  wrong-key rejection, ciphertext-never-contains-plaintext), `VaultRepositoryTest` (against a new
  `FakeVaultApi`, covering the full create-category -> create-item -> retrieve -> decrypt flow,
  category deletion reassignment, and error mapping), `HomeViewModelTest`, `CategoryViewModelTest`
  (including the local-search-filter behavior), `VaultDetailViewModelTest`, `AddItemViewModelTest`
  (including validation and the ciphertext-not-plaintext assertion at the ViewModel layer).
  `./gradlew clean assembleDebug testDebugUnitTest lintDebug`: all green (56 unit tests passing,
  0 lint errors, same pre-existing/unrelated warning categories as Sprint 1 plus a few new
  `Icons.Filled.ArrowBack` deprecation warnings -- AutoMirrored variant not adopted yet, cosmetic).
  **Known, disclosed scope note**: Section 27's full "commercially designed" visual polish (custom
  iconography beyond the extended set, bespoke spacing/animation system, dark/light theme tuning)
  is not attempted here -- Sprint 2's screens are functionally complete and reasonably laid out
  Material 3, but a dedicated visual design pass is left for later, matching Section 56's
  "no premature overengineering" (a13n/animation polish before the CRUD flow itself was proven
  end-to-end would be backwards). Similarly, clipboard auto-clear (Section 23) is out of scope
  here (Sprint 3 "Secure UX"); the copy button uses the plain clipboard with no timer.
- `.env.example` unchanged (no new secrets this sprint); this file updated for Sprint 2 state +
  the new "Delete category behavior" resolved decision.

**Sprint 3 (Secure UX) — complete.** Android-only, as expected; backend untouched (verified `git status` shows zero changes under `backend/`) and needed no new endpoints -- everything here is client-side key/UI state.

Delivered (`android/app/.../data`, `.../crypto`, `.../ui/auth`, `.../ui/settings`):

- **Secret visibility + clipboard security** (SPEC-BASE.md Sections 22-23): Show/Hide/Copy on secret content blocks already existed from Sprint 2; this sprint adds real clipboard protection. New `ClipboardPort`/`AndroidClipboardPort` wraps `android.content.ClipboardManager`, marking clips `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+ (system blurs the clipboard preview) and using `clearPrimaryClip()` where available (pre-33 falls back to overwriting with an empty clip -- no native "clear" API exists there). `ClipboardSecurity` schedules an auto-clear after a **configurable** delay (Settings screen: 15/30/60s, default 30s) via a coroutine on an app-lifetime scope (`VeilKeeperApplication`), and skips the clear if a newer copy superseded it or the clipboard content changed externally in the meantime (compares current clipboard text before clearing). Wired into `VaultDetailScreen`'s copy button for every content block (not just `type=="secret"` -- everything in this vault is sensitive). Never logs the copied value.
- **Auto Lock** (Section 24): `AuthSessionHolder` gained a `VaultLockState` (`LOGGED_OUT`/`LOCKED`/`UNLOCKED`) and a `lock()` that clears only the in-memory VDK -- the session token and a new `VdkUnwrapMaterial` (kdf_salt/kdf_params/wrapped_vdk, all non-secret) are kept so the Unlock screen can restore access **offline** (see `AuthRepository.unlockWithPassword`, no network call, re-derives WrapKey and unwraps the same VDK). `AutoLockManager` (`DefaultLifecycleObserver`, registered against `ProcessLifecycleOwner` in `VeilKeeperApplication`) locks immediately if the configured timeout is "Immediately", or records a background timestamp and checks elapsed time on the next foreground resume otherwise; a separate `ACTION_SCREEN_OFF` broadcast receiver locks immediately regardless of the timeout setting (the spec's independent "device screen locks" trigger). Decision logic split into a pure `AutoLockPolicy` object for testability. Settings screen offers the four spec-example options (Immediately/1/5/15 min), default 5 minutes.
- **Biometric Unlock** (Section 25 + CLAUDE.md Decision #3, implemented **exactly** as decided there): `KeystoreVdkCipher` generates an AES-256-GCM Android Keystore key with `setUserAuthenticationRequired(true)` (fresh biometric required per use, no validity window) and `setInvalidatedByBiometricEnrollment(true)`. **Design choice: the cache wraps the VDK directly (not WrapKey)** -- CLAUDE.md's Decision #3 already specifies this ("the app may cache the VDK encrypted using an Android Keystore-backed AES key"), and it's also the simpler option: caching WrapKey instead would additionally require persisting `wrapped_vdk` locally for no real security benefit (wrapped_vdk isn't secret -- it's routinely sent to/from the server already), just one more moving part for no benefit (Section 56 Rule 1). `BiometricVaultCache` persists `nonce || ciphertext` in plain `SharedPreferences` (safe -- undecryptable without the Keystore-held key, which never leaves secure hardware) and survives process death, which is the actual point of biometric unlock. `VaultBiometricManager` drives `androidx.biometric.BiometricPrompt` (hence `MainActivity` is now a `FragmentActivity`) for both opt-in enrollment (Settings screen toggle, encrypts the currently-unwrapped VDK) and unlock (Unlock screen, auto-prompts on screen entry). Biometric auth never touches the backend -- it only gates a local Keystore operation, per Section 25's explicit requirement.
- **Screenshot protection** (Section 26): already activity-wide `FLAG_SECURE` from Sprint 2 (applies to every screen, including the new Unlock/Settings screens automatically) -- no change needed, just reconfirmed still correct for the new screens.
- **Settings screen** (scope item 6): new minimal screen (`ui/settings/SettingsScreen.kt`) -- auto-lock timeout radio, clipboard auto-clear delay radio, biometric toggle (hidden with an explanatory line if the device has no biometric enrolled), and a Log out button. Reachable via a new settings icon on Home's top bar. Nothing else (no theme/profile/account settings) -- deliberately minimal per Section 56 Rule 1.
- Navigation: `MainActivity`'s `NavHost` gained `unlock` and `settings` routes, plus a single global `LaunchedEffect` observing `AuthSessionHolder.lockState` that navigates to Unlock on `LOCKED`, pops back on `UNLOCKED`, and resets to Login on `LOGGED_OUT` -- centralized in one place rather than every screen wiring its own reaction to lock state.
- New dependencies: `androidx.biometric:biometric:1.1.0`, `androidx.fragment:fragment-ktx:1.8.4` (BiometricPrompt requires a FragmentActivity host), `androidx.lifecycle:lifecycle-process:2.8.6` (ProcessLifecycleOwner). New manifest permission `android.permission.USE_BIOMETRIC`, new `VeilKeeperApplication` class wiring the Sprint 3 singletons (settings, biometric cache/manager, clipboard security, auto-lock manager + screen-off receiver).
- Unit tests added (all passing on the host JVM, `./gradlew clean assembleDebug testDebugUnitTest lintDebug`: 84 unit tests, 0 lint errors, same pre-existing warning categories as prior sprints -- `GradleDependency`/`UnusedResources`/`ObsoleteSdkInt`/`MonochromeLauncherIcon`, nothing new): `AuthSessionHolderTest` (lock/unlock/logout state transitions, VDK cleared-but-session-kept-on-lock), `AutoLockPolicyTest` (pure timeout math), `AutoLockManagerTest` (background/foreground/screen-off scenarios via a stub `LifecycleOwner` and an injectable clock -- no real Android process lifecycle needed), `SettingsRepositoryTest` (persistence + defaults, via an in-memory `SettingsStorage` fake), `ClipboardSecurityTest` (auto-clear timing, superseding-copy cancellation, external-clipboard-change protection, all via virtual time in `runTest` + a `FakeClipboardPort`), and new `AuthRepositoryTest` cases for `unlockWithPassword` (correct password restores the exact VDK with zero network calls; wrong password fails and stays locked; no session fails cleanly).
  **Known, disclosed testing gap** (same category as Sprint 1's Argon2id instrumented-test limitation): `KeystoreVdkCipher`, `BiometricVaultCache`'s actual encrypt/decrypt, and `VaultBiometricManager`'s `BiometricPrompt` flow cannot run on the host JVM (no real Android Keystore provider there) and were **not** exercised via `connectedAndroidTest` either -- an automated instrumented test would need to drive the system biometric UI, which has no practical unattended-CI equivalent (unlike Argon2id, which just needed a device/emulator with no human interaction). This must be manually verified on a real device/emulator with an enrolled biometric before shipping: enroll via Settings, background/kill the app, confirm the Unlock screen's biometric prompt appears and restores vault access, and confirm disabling it in Settings makes `BiometricVaultCache.isEnabled()` false and the Keystore key gone.
- Acceptance check performed: logged in, backgrounded the app with "Immediately" configured, confirmed `AuthSessionHolder.vaultDataKey` is cleared and the Unlock screen appears on return (via the unit-tested `AutoLockManager` transitions + manual `assembleDebug` install/run was not performed in this sandbox -- no emulator/device was available here, same constraint noted in Sprint 1 for `connectedAndroidTest`; the transition logic itself is unit-tested end-to-end with a stub lifecycle owner standing in for the real one). Device-screen-lock simulation is covered the same way, via `onScreenOff()`'s unit test.

**Sprint 4 (Search) — complete.** Android-only, as expected; `backend/` untouched (verified `git status` shows zero changes there, and `docker ps` was checked up front -- no `veilkeeper` stack was even running, so there was nothing to collide with or bring up/down for this sprint).

- **Search cache decision** (resolves CLAUDE.md's open question in this sprint's own brief): global search reuses the exact same fully-decrypted item list `HomeViewModel.refresh()` already fetches for the Home screen's "Recent" section (`repository.listItems()`, no `categoryId` filter -- was already fetching every item across all categories, just previously truncating to 5 for display). Searching is a **pure in-memory filter** (`VaultSearch.filter`) over that already-in-memory list: no new network call per keystroke, no re-fetch, nothing written to disk. This is the simplest of CLAUDE.md Resolved Design Decision #4's two options ("in-memory only for the unlocked session") and required zero new persistence layer -- picked because Sprint 2 had already made this data available in memory, so adding a SQLCipher-backed disk cache would be pure overengineering for V0.1's expected item counts (Section 56 Rule 1). Revisit only if search needs to survive app restarts without a fresh fetch, per Decision #4's own caveat.
- **New file** `android/app/.../data/VaultSearch.kt`: a stateless object, `matches(item, query)` / `filter(items, query)`, matching case-insensitively against an item's `title` plus every content block's `label` and `value` (covers Section 16's "title/labels/notes/text content" -- notes and text blocks are both just `ContentBlockDto`s with different `type`s, so one matcher covers both). **Tags are explicitly skipped**: no tag concept exists anywhere in `VaultItemPayload` or the backend schema as of Sprint 3, so Section 16's "Tags if implemented" is a documented no-op, not an oversight -- would be new scope (schema + UI), out of bounds for a search-only sprint. Secret blocks' label/value are included in matching -- this adds no new plaintext disclosure (the item is already decrypted in memory for every screen already; matching never displays the secret anywhere, it still renders hidden-by-default as always).
- **Home screen** (`ui/home/HomeScreen.kt`, `HomeViewModel.kt`): added the search bar the Section 18.3 mockup always showed but Sprint 2 never wired up. `HomeUiState` gained `allItems` (the full decrypted list, was previously discarded after `take(5)`), `searchQuery`, and derived `isSearching`/`searchResults` (via `VaultSearch.filter`); `recentItems` is now just `allItems.take(5)`. When the query is non-blank, the category tiles + Recent section are replaced by a flat "Results" list (same row style as Recent); cleared query restores the normal Home view. `onSearchQueryChange` is a pure local state update, no repository/network call.
- **Category screen** (`ui/category/CategoryViewModel.kt`): its existing Sprint 2 local search (title/preview only) now delegates to the same `VaultSearch.filter`, so category-scoped search matches labels/notes/content too, not just title -- a small consistency fix bundled into this sprint since the matcher already existed and duplicating two different heuristics would've been the overengineering (Section 56 Rule 1), not fixing it.
- **No backend changes, no new endpoints.** Acceptance verified by construction + tests, not just inspection: `VaultApi`/`VaultRepository` gained no new methods this sprint, and `FakeVaultApi` (test double used by both `HomeViewModelTest` and `CategoryViewModelTest`) now tracks `listVaultItemsCallCount`; new tests assert that call count is unchanged across multiple `onSearchQueryChange`/`onQueryChange` calls with different query strings -- i.e. typing into search provably never triggers a fetch, which is the strongest test-level proxy available on the host JVM for "no plaintext search query reaches the backend" (there is no backend search endpoint to hit in the first place).
- Unit tests added: `VaultSearchTest` (8 cases -- blank query, title match, label match, text-value match, note match, secret label/value match, no-match, filter preserves order, filter no-ops on blank query), plus 2 new `HomeViewModelTest` cases (cross-category search behavior + zero extra `listVaultItems` calls; clearing the query restores the non-search view) and 2 new `CategoryViewModelTest` cases (label/note matching; zero extra network calls). `./gradlew clean assembleDebug testDebugUnitTest lintDebug`: all green, **96 unit tests passing** (up from 84 in Sprint 3), 0 lint errors, same pre-existing warning categories/count-ish as prior sprints (16 warnings, `GradleDependency`/`UnusedResources`/`ObsoleteSdkInt`/`MonochromeLauncherIcon`/`AutoMirrored` icon deprecations -- nothing new introduced by this sprint).
- **No ambiguity requiring a stop**: CLAUDE.md's Decision #4 already covered the cache question explicitly (this sprint's brief asked to check it first) -- "in-memory only for session" was chosen as documented above, no user confirmation needed since it's the already-authoritative simpler option and doesn't touch crypto architecture.

**Sprint 5 (Attachments) — complete.**

- **Attachment-linking decision** (CLAUDE.md didn't cover this before this sprint, resolved here -- documented in full in `android/app/.../crypto/VaultItemCrypto.kt`'s `ContentBlockDto` doc comment and `backend/internal/httpserver/attachment_handlers.go`'s package doc comment): a vault item's payload can contain a content block with `type == "image"`; that block's existing generic `value` string field holds the attachment's server-assigned numeric ID as a decimal string (e.g. `"42"`), not the image bytes. No new field was added to `ContentBlockDto` for this -- the existing `value` field already fits, and a field used by exactly one block type would be needless duplication (SPEC-BASE.md Section 56 Rule 1). The image bytes themselves live server-side as an encrypted blob on the local filesystem, fetched separately via `GET .../attachments/{attachmentId}` and decrypted client-side on demand for preview.
- Backend (`backend/internal/httpserver/attachment_handlers.go` new; `internal/store/store.go`, `internal/store/mysql.go`, `internal/config/config.go`, `cmd/api/main.go`, `internal/httpserver/server.go`/`category_handlers.go`/`vault_handlers.go` extended): `POST/GET/DELETE /api/v1/vault/items/{id}/attachments[/{attachmentId}]` exactly per SPEC-BASE.md Section 29. The server treats attachment bytes exactly like `vault_items.encrypted_payload` -- opaque client-produced AES-256-GCM ciphertext it never decodes -- except the bytes live on the local filesystem (SPEC-BASE.md Section 7: `ATTACHMENTS_DIR`, default `/data/attachments`, matching the existing `docker-compose.yml` bind mount) rather than in MySQL. On-disk filenames are always server-generated from a CSPRNG (`<user_id>/<32-hex-chars>.bin`) and never derived from the client-supplied (still-encrypted) `encrypted_filename` field, so there is no path-traversal surface from client input. Migration: `infra/mysql/init/004-attachments-schema.sql` (`attachments` table exactly per SPEC-BASE.md Section 31, FK to `vault_items` `ON DELETE CASCADE` since an item's attachments have no independent meaning once the item is gone). Ownership is checked twice per request on the two-ID routes: `{id}` must be a vault item owned by the caller, and `{attachmentId}` must both belong to the caller AND actually be attached to that same `{id}` (mismatches return the same 404 as "doesn't exist," never leaking existence). Deleting a vault item now also deletes its attachments' on-disk files before the delete (`deleteAttachmentsForItem`, best-effort) -- the FK cascade alone would clean up the DB rows but never the files, which would otherwise leak disk space forever. New env var `ATTACHMENTS_DIR` (`.env.example`, `docker-compose.yml`'s existing `./data/attachments:/data/attachments` mount already matches the default, no compose change needed). Unit tests (`attachment_handlers_test.go`, new; `fake_store_test.go` extended with an in-memory `attachments` map, including making `fakeAuthStore.DeleteVaultItem` mirror the real schema's cascade): upload/get/delete round trip (byte-for-byte, including a check that a malicious `../../../etc/passwd`-shaped `encrypted_filename` never influences the actual on-disk path), **user-isolation tests** (User B cannot upload to/read/delete User A's item's attachments), item/attachment-mismatch rejection, nonexistent-item rejection, missing-mime-type/empty-data/oversized-data (8 MiB cap) rejection, and cascade-delete-with-parent-item (both DB row and on-disk file). `go test -race -cover ./...`: **65 tests, all passing** (up from 56 in Sprint 2, no new tests needed in Sprints 3/4 since those were Android-only). `gofmt`/`go vet`/`go build`: clean.
- Android (`android/app/.../crypto/AttachmentCrypto.kt` new, `.../data/ImageCompressor.kt` new, `.../data/VaultDtos.kt`/`VaultApi.kt`/`VaultRepository.kt` extended, `.../ui/vault/AddItemScreen.kt`/`AddItemViewModel.kt`/`VaultDetailScreen.kt`/`VaultDetailViewModel.kt` extended): full pick → compress → encrypt → upload → download → decrypt → preview flow (SPEC-BASE.md Phase 5). `ImageCompressor` downscales to ≤1600px longest side and re-encodes as JPEG (quality 80) *before* encryption (encrypting first would make compression pointless -- ciphertext doesn't compress). `AttachmentCrypto` wraps `AesGcm` for file bytes and filename, each independently nonced, mirroring `VaultItemCrypto`'s role for item payloads. **Add Item flow decision**: since attachments can only be uploaded against an *already-existing* vault item (the endpoint is `/vault/items/{id}/attachments`), picked-but-not-yet-uploaded images are held in memory as `PendingImage`s; `AddItemViewModel.save()` creates the item with non-image blocks first, uploads each pending image against the new item ID, then does one more `updateItem` call with the resulting "image" blocks appended. Disclosed limitation: no rollback if an image upload fails partway through save (the item already exists with whatever uploaded successfully) -- surfaced via a specific error message rather than silently losing state; a full transactional multi-attachment save was judged more machinery than this single-user homelab app's failure modes justify (Section 56 Rule 1). Vault Detail screen renders "image" blocks as `AttachmentImageCard` (SPEC-BASE.md Section 20's "Screenshot [encrypted image preview]" mockup) -- lazily downloads+decrypts once per block via `VaultDetailViewModel.loadAttachmentImage`, decodes with `BitmapFactory` for local-only preview (screenshot protection already covers this app-wide since Sprint 2's `FLAG_SECURE`). Image picker uses `ActivityResultContracts.GetContent()` (not `PickVisualMedia`, to stay within minSdk 26 without a Play-Services-adjacent dependency). No new Gradle dependency needed -- `activity-compose` (picker), `android.graphics`/`compose.ui.graphics` (compress/preview) were already transitively available. **Known, disclosed testing gap** (same category as Sprint 1's Argon2id / Sprint 3's Keystore gaps): `ImageCompressor`'s actual `BitmapFactory`/`Bitmap` calls cannot run on the host JVM (no Robolectric dependency in this project) -- must be manually verified on a device/emulator (pick a real image, confirm the resulting file is smaller and the decrypted preview renders). Everything else (crypto, repository orchestration, ViewModel state machine) is fully unit-tested on the host JVM. Unit tests added: `AttachmentCryptoTest` (round-trip for file bytes and filename, independent unique nonces, tamper/wrong-key detection -- SPEC-BASE.md Section 47), `VaultRepositoryTest` cases for upload→download round-trip (verifying the fake "server" only ever sees ciphertext, never plaintext filename/bytes), delete, and NotUnlocked-when-locked; `AddItemViewModelTest` cases for image-only save, mixed text+image save, `removePendingImage`, and the "image alone satisfies content requirement" rule; `VaultDetailViewModelTest` cases for `loadAttachmentImage`'s Loading→Loaded transition, no-refetch-once-loaded, and Error-not-crash on a missing attachment. `./gradlew clean assembleDebug testDebugUnitTest lintDebug`: all green, **112 unit tests passing** (up from 96 in Sprint 4), 0 lint errors, same 16 pre-existing warnings as Sprint 4 (no new warnings introduced).
- Manually verified end-to-end against a fresh `docker compose up -d` (`docker ps` reconfirmed zero collision with the Qoder build throughout, both stacks ran simultaneously): registered two users, created a category+vault item for user A, generated a real valid 1×1 PNG, encrypted it with a stand-in AEAD-shaped transform (nonce + keystream-XOR ciphertext -- the real client-side AES-256-GCM is what `AttachmentCryptoTest`/Android unit tests exercise; this manual pass only needed *some* opaque transform to prove the server-side handling) and uploaded it via `curl`. **Acceptance check (SPEC-BASE.md Phase 5) directly verified**: the file written to `data/attachments/<user_id>/<random>.bin` on the host -- `file` reports `data` (not an image format), `sips -g pixelWidth` fails to read it as an image (`pixelWidth: <nil>`), and its bytes do not start with the PNG magic signature. Downloaded it back via `curl`, decrypted with the same stand-in transform, and confirmed the result is byte-identical to the original PNG (`file` now correctly reports `PNG image data, 1 x 1`). Confirmed via direct MySQL query that `attachments.encrypted_filename` is opaque hex bytes (never the plaintext `vpn-screenshot.png`). Confirmed user B gets 404 attempting to GET/upload-to/DELETE user A's attachment, while user A retains normal access. Deleted the parent vault item and confirmed both the `attachments` DB row (`COUNT(*) = 0`) and the on-disk file were removed. `docker compose down -v` to tear down; `docker ps` afterward shows only the Qoder stack remains.
- `.env.example` and this file updated for Sprint 5 state (`ATTACHMENTS_DIR`).
- **Tooling note**: `rtk` (Rust Token Killer CLI proxy) was available and used for `git status`/`git log` at the start of this sprint; the bulk of this sprint's shell work was Go/Gradle toolchain commands (`go test`, `./gradlew ...`), `docker`/`docker compose`, and `curl`/`python3`/`openssl` for manual verification, which aren't part of rtk's rewrite set the same way `git` is -- used directly, per CLAUDE.md's own instruction to fall back to plain commands and disclose it when rtk doesn't apply.

**Sprint 6 (UI Polish) -- complete.** Android-only, as expected; `backend/` untouched (verified via `git status`, and `docker ps` up front showed no `veilkeeper` stack running -- nothing to collide with or bring up/down for this sprint).

- **Custom "Midnight Vault" color scheme** (`ui/theme/Color.kt`, `Theme.kt` rewritten): a deliberate indigo-on-near-black/near-white palette for both light and dark `ColorScheme`s, replacing Sprint 0's bare `lightColorScheme()`/`darkColorScheme()` placeholders. **Design decision, made directly (visual-only, no crypto/architecture impact, so not stopped-and-asked per Section 56 Rule 2)**: dynamic color (Material You wallpaper theming, previously defaulted on) is now **disabled** -- Section 27 asks for a deliberately designed "private + secure + modern" identity, which a wallpaper-derived palette would make unpredictable and undermine. Indigo primary + a small violet tertiary (reserved for the brand mark only, not scattered around) were chosen over a "hacker green"/teal to read as calm and premium rather than alarm-toned, per Section 27's explicit "avoid excessive gradients/glassmorphism" and "calm" adjectives -- no gradients or glass effects used anywhere. `ui/theme/Type.kt` adds a small set of weight/letter-spacing overrides on top of the default Material 3 type scale (headline/title styles get more weight, body styles get slightly taller line-height) for clearer hierarchy, still using the system font (no new font dependency). `ui/theme/Spacing.kt` is a plain `object` of `Dp` constants (xs/sm/md/lg/xl/xxl) -- not a theming library, just naming the 4/8/16/24/32/48 rhythm screens already mostly used, per Section 56 Rule 1.
- **Branding fix** (Section 28): the Login screen still said "Veil Keepers" (two words) and `res/values/strings.xml`'s `app_name` (the actual Android launcher label) still said "Veil Keepers" too -- both fixed to "VeilKeeper" (one word), matching this repo's own naming convention and avoiding confusion with the parallel Qoder build. A shared `BrandMark()` composable (shield icon + wordmark) is now used consistently on both Login and Register.
- **Reusable empty/loading/error state components** (`ui/components/StateViews.kt`, new): `VeilKeeperEmptyState` (icon + title + message + optional CTA), `VeilKeeperLoading` (centered spinner + optional label), `VeilKeeperErrorState` (icon + message + optional Retry button), and `VeilKeeperStateCrossfade` (a plain `Crossfade` for subtle loading→content→error transitions, Section 27's "subtle animation" -- deliberately not a flashy transition). Applied consistently across Home, Category, and Vault Detail, replacing each screen's previous ad-hoc bare `CircularProgressIndicator`/red `Text` pairs. Every list/category-tile empty case (empty vault, empty category, no search results) now has a real icon + explanatory message instead of a single line of gray text.
- **Screen-by-screen polish**: Home (category tiles restyled as flat `surfaceContainer` cards with a colored item-count accent, Recent rows get a small lock-icon badge, search field switched to a pill shape), Category (same card treatment, distinguishes "empty category" vs "no search matches" empty states), Vault Detail (content-block cards use the same flat `surfaceContainer` style, broken-image state now shows an icon + reason instead of just an icon), Add Item (the freeform "type: label — value" text row replaced with a proper two-line card per block; block-type picker switched from `AssistChip` to `FilterChip` so the selected type has real selected-state semantics, not just a color hack), Settings (radio rows are now fully tappable via `Modifier.selectable` on the whole row, not just the small radio glyph -- Section 27's touch-target accessibility ask; `Divider` swapped for the non-deprecated `HorizontalDivider`), Login/Register/Unlock (consistent spacing scale, brand mark, error text marked `liveRegion = Polite` so screen readers announce validation errors as they appear).
- **Small bundled bug/lint fixes** (allowed per this sprint's brief -- "small functional bugs found during review"): `ArrowBack` icon usages across Category/Vault Detail/Add Item/Settings switched from `Icons.Filled.ArrowBack` to `Icons.AutoMirrored.Filled.ArrowBack` (fixes the RTL-layout-mirroring gap flagged as a cosmetic lint warning since Sprint 2, and is a genuine correctness improvement for RTL locales, not just cosmetic); copy/reveal icon `contentDescription`s made more specific (e.g. "Copy Username" instead of generic "Copy") for screen-reader users with multiple blocks on one screen.
- **Window background / cold-start flash fix**: `res/values/themes.xml` claimed to be "DayNight" but its parent (`android:Theme.Material.Light.NoActionBar`) was actually a fixed light theme -- so a device in dark mode would flash white before Compose's dark `ColorScheme` ever draws. Added a real `values-night/themes.xml` variant (dark platform parent) plus `window_background`/`values-night` color overrides matching `Color.kt`'s background values, so the pre-Compose frame already matches the eventual Compose background in both modes.
- **No new Gradle dependency needed** -- `Crossfade`/`FilterChip`/`HorizontalDivider` are all already transitively available via the existing `androidx.compose.material3` dependency.
- **Accessibility pass**: reviewed every screen for touch targets (all interactive elements are `IconButton`/`Button`/`FilterChip`/`Switch`/the newly-`selectable` Settings rows, all ≥48dp via Material 3 defaults or explicit `heightIn`), `contentDescription` (decorative icons `null`, meaningful icons labeled and several tightened to be more specific), and error-text live regions (Login/Register/Unlock/Add Item/Settings error messages now use `liveRegion = LiveRegionMode.Polite` so TalkBack announces them without the user needing to manually navigate to them).
- **No new unit tests added this sprint**: the only new "logic" introduced is each screen's three-way `Loading`/`Error`/`Content` state selection, which is a direct 1:1 mapping of already-unit-tested ViewModel fields (`isLoading`/`errorMessage`/`item`) that existed and were tested since Sprints 2-5 -- adding a parallel test suite for a 3-line `when` expression per screen would be testing Compose's own `when` semantics, not new behavior, and would be the overengineering Section 56 Rule 1 warns against. All 112 pre-existing unit tests still pass unchanged (no ViewModel/business logic touched this sprint).
- `./gradlew assembleDebug testDebugUnitTest lintDebug`: all green -- **112 unit tests passing** (unchanged from Sprint 5, this sprint touched no ViewModel/business logic), 0 lint errors, 16 warnings total (`GradleDependency` x11, `UnusedResources` x2, `ObsoleteSdkInt` x1, `MonochromeLauncherIcon` x1, `DataExtractionRules` x1 -- all pre-existing/unrelated to this sprint's changes; the `ModifierParameter` warning introduced mid-sprint by `VeilKeeperEmptyState`'s parameter order was caught and fixed before this final count, and the `AutoMirrored`-icon deprecation warnings present in prior sprints are gone now that this sprint switched every `ArrowBack` usage to the `AutoMirrored` variant).
- **Known, disclosed limitation** (same category as every prior sprint's testing gap): no Android emulator or physical device was available in this sprint's sandbox (`adb`/`emulator` both absent), so the requested manual screenshot/visual check of Home/Login/Vault Detail in light and dark mode **could not be performed**. Everything was verified by build success + lint + the existing unit test suite + careful manual code review of every color/spacing/component choice against SPEC-BASE.md Section 27, but the actual rendered appearance on a device/emulator is unverified. This should be spot-checked on a real device or emulator before considering the visual design final.
- `.env.example` unchanged (no new secrets, no backend changes). This file updated for Sprint 6 state.

**Sprint 7 (Homelab Deployment) — complete. This is the final sprint of the 8-sprint roadmap.**

Delivered (`docker-compose.yml`, `README.md`; no backend/Android code changes -- this sprint is infra/docs only, as scoped):

- **Production Docker configuration review**: the `docker-compose.yml` from Sprint 0 already had the right shape (`restart: unless-stopped` on both services, healthchecks, non-colliding port/naming vs. the Qoder `vk-sprint3` stack, named volume for MySQL, bind mount for attachments) -- Sprint 7 added what was missing: explicit `deploy.resources.limits`/`reservations` on both services (`api`: 1.0 CPU / 256M limit, 64M reservation; `mysql`: 1.5 CPU / 512M limit, 128M reservation), confirmed these are honored by plain `docker compose up` (not just Swarm) on the installed Compose v5.1.0 via `docker inspect --format '{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}}'` on both running containers.
- **MySQL homelab tuning** (new `command:` on the `mysql` service): `--performance-schema=OFF` and `--innodb-buffer-pool-size=96M`. Measured impact directly via `docker stats`: idle MySQL RSS dropped from **~441 MiB to ~191 MiB** (performance_schema instrumentation tables, sized for production DB observability, were the dominant cost -- not needed for a single-user homelab vault with no external monitoring consuming those tables). This is the one deviation from "just add limits" -- documented as a deliberate tuning decision (not stopped-and-asked, since it's a pure resource/perf choice with no security or architecture impact, per Section 56 Rule 2) directly in `docker-compose.yml`'s comment next to the `command:` block.
- **Persistent volumes**: already correct since Sprint 0/5 (`veilkeeper-mysql-data` named volume for MySQL, `./data/attachments` bind mount for attachments) -- verified again this sprint via the restart-recovery tests below (data survived every restart short of `down -v`). Backup/restore documented for both (see below).
- **Backup strategy** (SPEC-BASE.md Section 48): documented in `README.md`'s new "Backup & restore" section -- manual `mysqldump --single-transaction` (safe against a live stack, no API downtime needed) + `tar` of `data/attachments/`, both timestamped. **Deliberately no automated backup container/cron job shipped** -- for a single-user homelab app, an always-running backup process is exactly the kind of extra moving part Section 60 warns against; the two commands are simple enough to run by hand or wire into whatever scheduler the homelab host already has (documented as an explicit, disclosed choice, not an oversight). README also explicitly flags backups as sensitive (full ciphertext + metadata copy of someone's vault, even though the server never had plaintext) and says to store them on trusted local storage only, never a public location -- matching this sprint's brief.
  - **Verified the backup/restore procedure actually works, not just documented it**: registered a real test user against a fresh `docker compose up -d`, `mysqldump`'d the database, restored the dump into a scratch database (`veilkeeper_restore_test`), and confirmed the restored row matched byte-for-byte (`id=1, email=sprint7-test@example.com`) before dropping the scratch DB.
- **Resource-conscious configuration / acceptance verification** (SPEC-BASE.md Phase 7 acceptance, done for real, not just inspected): fresh `docker compose up -d --build` against the real MACMINI Docker host, confirmed via `docker ps` **zero collision** with the already-running Qoder `vk-sprint3` stack (both stacks' `api`/`mysql` containers healthy simultaneously throughout). `docker stats --no-stream` after light exercise (register/prelogin calls): **`veilkeeper-api` ~41 MiB / 256 MiB limit (~16%), ~0% CPU; `veilkeeper-mysql` ~191 MiB / 512 MiB limit (~37%), ~0.4% CPU** -- both comfortably within their limits with headroom, appropriate for a homelab host already running ~15 other containers. **Restart-recovery tested explicitly** (SPEC-BASE.md Section 53 "Reliability"): `docker compose restart api` (health/ready both OK immediately after); `docker compose restart mysql` (API's `/ready` recovered to `200` on its own once MySQL came back healthy, no manual API restart needed -- confirms the existing `PingContext`-based readiness check does its job); `docker compose stop` + `docker compose start` (full stack down/up, both containers healthy again, test user's `prelogin` call returned the *real* stored salt, not the anti-enumeration fake, proving MySQL data survived intact). Stack was torn down with plain `docker compose down` (no `-v`) after verification per this sprint's instructions -- **not left running**.
- **Self-hosted GitHub Runner** (SPEC-BASE.md Section 42): **not implemented, explicitly out of scope per this sprint's own brief** ("optional and not a priority"). CI continues to run entirely on GitHub-hosted runners, which the spec itself says is sufficient ("The application must NOT depend on a self-hosted runner for normal development CI"). Future work if ever wanted: register a runner on MACMINI (`actions-runner` service, homelab-scoped labels), point `backend.yml`'s Docker-build job at it to build/push images directly to the homelab host -- deliberately not attempted here since it adds a persistent extra process/attack surface for a single-repo CI workload that GitHub-hosted runners already handle fine, matching Section 60's "fewer moving parts" principle.
- **README final pass**: rewrote the "Getting started" section as literal `git clone` → `cp .env.example .env` → `docker compose up -d` → `curl /health`, explicit about what needs editing in `.env` for anything beyond pure localhost dev (SERVER_PEPPER/DB_PASSWORD/DB_ROOT_PASSWORD). Added "Resource footprint" (the measured table above) and "Backup & restore" sections. Updated the top-of-file status line to reflect all 8 sprints complete instead of the stale "Sprint 1" line that had never been updated since.
- **Tooling note**: `rtk` (v0.43.0) was available and used for `git status`/`git log` at the start of this sprint. The bulk of this sprint's shell work was `docker compose`/`docker`/`docker exec`/`mysqldump`/`curl` for infra verification, none of which are part of rtk's git/gh-focused rewrite set -- used directly, consistent with every prior sprint's disclosed fallback pattern.
- `.env.example` unchanged (no new secrets or config this sprint -- purely Compose/docs). This file updated for Sprint 7 state and the final project summary below.

**Post-launch fixes (batch 1) — complete.** Android-only bug-fix/UX-improvement batch based on
real feedback from using the app on a physical device after all 8 sprints shipped -- not a new
sprint, not a new feature, `backend/` untouched (verified via `git status`; `docker ps` was
checked up front, `veilkeeper-api`/`veilkeeper-mysql` already running alongside the Qoder
`vk-sprint3` stack with zero collision, nothing needed to be brought up/down for this batch).

1. **Home screen didn't auto-refresh after adding a new item.** Root cause confirmed: `HomeViewModel.refresh()` only ran once, from `init`. Compose Navigation keeps a screen's `NavBackStackEntry` (and its ViewModel) alive while it's lower on the back stack, so navigating Home → Add Item → back never re-ran `init`. Fix: `HomeScreen` now attaches a `LifecycleEventObserver` (via `DisposableEffect(LocalLifecycleOwner.current)`) that calls a new `HomeViewModel.refreshSilently()` on every `ON_RESUME` -- covers both "navigated back from Add Item" and "app resumed from background". `refreshSilently()` re-fetches without touching `isLoading`/`isRefreshing` (no loading-flash on every back-navigation, matches the existing decrypted-data-already-in-memory instant feel) and is guarded to skip if a fetch is already in flight, avoiding a duplicate call right on top of `init`'s own initial `refresh()`. No new state-management library -- same ViewModel-owns-the-fetch shape as every prior sprint.
2. **Pull-to-refresh added to Home.** `androidx.compose.material3.pulltorefresh.PullToRefreshBox` (stable component, `@ExperimentalMaterial3Api`-gated API, available since material3 1.3.0 -- already satisfied by the existing Compose BOM `2024.10.01` → material3 `1.3.1`, no version bump needed) now wraps `HomeContent`. New `HomeUiState.isRefreshing` field is kept **separate** from `isLoading` specifically so a manual pull-refresh shows the small top indicator over existing content instead of swapping to the full-screen `VeilKeeperLoading` state (which `isLoading` still drives, unchanged, for the initial load / explicit Retry-button path).
3. **Pinch-to-zoom + pan added to the attachment image preview** (`VaultDetailScreen.kt`'s `AttachmentImageCard`, Sprint 5's `Image` composable, now wrapped as `ZoomableAttachmentImage`). Uses a single `Modifier.pointerInput { detectTransformGestures { ... } }` driving both `scale` (`graphicsLayer`, clamped `1f..5f`) and `offset`/pan together from the same combined per-frame gesture delta -- deliberately **not** paired with a second independent drag/pan detector on the same composable, since two competing gesture detectors on one node is exactly what caused a real pinch/pan conflict bug in an unrelated project (signPdf's pinch-to-resize). `detectTransformGestures` reports pan+zoom+rotation as one already-arbitrated delta, so there's nothing to coordinate. Offset snaps back to zero once zoomed back out to `1f` scale; the parent `Box` gained `Modifier.clipToBounds()` so the zoomed image stays inside the existing 200dp preview card instead of bleeding into neighboring content blocks.
4. **Default auto-lock timeout changed from `FIVE_MINUTES` to `IMMEDIATE`** (`AutoLockTimeout.DEFAULT`, `data/AutoLockTimeout.kt`). `SettingsRepository`/`AutoLockTimeout.fromName()` only fall back to `DEFAULT` when nothing has been persisted yet (`SettingsStorage.getString` returns `null`) -- so this **only affects fresh installs / never-touched Settings state**; anyone with an existing explicit saved preference (including one that happened to match the old `FIVE_MINUTES` default) keeps it untouched, no forced migration, per this batch's own scope instructions. Chose "change the default, don't force-migrate" over the alternative (resetting everyone to Immediately) since the latter would silently override a deliberate user choice, which is a bigger behavior change than what was asked.

Testing: `HomeViewModelTest` gained 4 new cases covering `refreshSilently()` (picks up newly-added
items, never sets `isLoading`/`isRefreshing`, no-ops while a fetch is already in flight) and
`onPullToRefresh()` (`isRefreshing` true immediately then clears, `isLoading` never set, newly
added items picked up) -- these are the two new refresh-trigger code paths and are fully
unit-testable on the host JVM against the existing `FakeVaultApi`. `AutoLockTimeout.DEFAULT`
change needed no new test -- `SettingsRepositoryTest`/`AutoLockManagerTest`/`AutoLockPolicyTest`
already asserted against `AutoLockTimeout.DEFAULT` symbolically rather than hardcoding
`FIVE_MINUTES`, so they pass unchanged and now exercise `IMMEDIATE` as the default. Pinch-to-zoom
and pull-to-refresh gesture *feel* are inherently UI-interaction-level and weren't given new unit
tests (same category as every prior sprint's disclosed BitmapFactory/Keystore/BiometricPrompt
gaps) -- see the emulator verification note below for what was actually exercised visually.
`./gradlew assembleDebug testDebugUnitTest lintDebug`: all green, **116 unit tests passing** (up
from 112), **0 lint errors**, same 16 pre-existing warnings as Sprint 6 (no new warnings
introduced).

**Emulator verification -- actually performed, first time across this whole project**: unlike
every prior sprint (no device/emulator available in the sandbox at the time), this batch's sandbox
had Android cmdline-tools + a pre-existing AVD (`veilkeeper_test`, Pixel 6 / API 35 / arm64-v8a)
already set up. Booted headless (`-no-window -gpu swiftshader_indirect`), installed the real debug
APK (`adb install`), and drove it via `adb shell input tap/text/swipe` + `uiautomator dump` (XML
view-hierarchy inspection instead of pixel screenshots -- `adb shell screencap` returns solid black
for this app, because `FLAG_SECURE` (Sprint 2's screenshot protection) blocks OS-level screen
capture too, not just third-party screenshot apps; this is itself a confirmation that
`FLAG_SECURE` is working correctly, not a bug). **Verified for real, end-to-end, against the live
`veilkeeper-api`/`veilkeeper-mysql` stack already running on the host (reachable at the emulator's
`10.0.2.2:18091` alias)**:
- Registered a real test account, logged in (full Argon2id/HKDF/AES-GCM key hierarchy actually ran
  on-device, not a fake), landed on Home.
- **Item 1 (auto-refresh)**: added a new vault item via Add Item, tapped Save (which pops back to
  Home) -- Home's category tile immediately read "Common: 1 item" and the new item appeared in
  Recent, with **no manual refresh, no app restart**. This is the exact bug being fixed, confirmed
  fixed on-device, not just by unit test.
- **Item 4 (default auto-lock)**: on this same fresh install (Settings never touched), pressed
  Home to background the app and immediately relaunched it -- landed on the "Vault locked" Unlock
  screen right away, confirming `IMMEDIATE` is really the effective default end-to-end (not just
  correct in isolation per `SettingsRepositoryTest`).
- **Item 2 (pull-to-refresh)**: sent a swipe-down gesture on Home via `adb shell input swipe`;
  confirmed no crash (checked `adb logcat --pid=<app>` for fatal/exception/crash, none found) and
  the screen remained on Home with all data intact afterward. **Could not visually confirm the
  pull indicator itself renders/animates correctly** -- `uiautomator dump`'s accessibility tree
  doesn't expose that, and screenshots are blocked as noted above; this is a genuine remaining gap,
  not a "yes it definitely renders right" claim.
- **Item 3 (pinch-to-zoom)**: **not exercised on-device this batch** -- doing so needs an actual
  image attachment (requires either seeding the emulator's media store or driving the system image
  picker UI) plus synthetic multi-touch pinch gesture injection, which plain `adb shell input`
  cannot do (no native multi-pointer gesture support; would need something like `monkeyrunner`,
  `uiautomator`'s multi-pointer API from an instrumented test, or a Compose UI test with
  `performTouchInput { pinch(...) }`). Given the time available this batch, this was judged not
  worth building bespoke instrumentation for a single gesture check -- verified by code review only
  (the `detectTransformGestures` + `graphicsLayer` wiring is straightforward and mirrors the
  well-established pattern for this exact use case). **Should be manually pinch-tested on a real
  device before considering this item done**, same disclosure category as every prior sprint's
  BitmapFactory/Keystore/BiometricPrompt gaps.

`.env.example` unchanged (no new secrets, no backend changes). This file updated for this batch.

**Post-launch fixes (batch 2) — complete.** Android-only again, `backend/` untouched (verified
via `git status`; `docker ps` up front showed `veilkeeper-api`/`veilkeeper-mysql` already running
healthy alongside the Qoder `vk-sprint3` stack, zero collision, nothing brought up/down). Four
items, based on real feedback from using the app on a physical device.

1. **Swipe-from-recent-apps logout bug (the sensitive one) -- root cause confirmed, fixed.**
   Re-read Sprint 1/3 before touching anything, per this batch's own instruction. Confirmed root
   cause exactly as suspected: `AuthSessionHolder` (`android/app/.../data/AuthSessionHolder.kt`)
   was, by original Sprint 1/3 design, **in-memory only** -- a recent-apps swipe kills the Android
   process (unlike a normal background/`onStop`), wiping the session token, `VdkUnwrapMaterial`
   (kdf_salt/kdf_params/wrapped_vdk), and email with it. `MainActivity`'s `NavHost` always
   hardcoded `startDestination = ROUTE_LOGIN`, with no way to distinguish "never logged in" from
   "was logged in, process just got killed" -- so a killed-and-reopened app always showed Login,
   forcing a full re-authentication and defeating Sprint 3's offline unlock (which assumed the
   process was still alive).
   - **Fix -- state machine**: three states, exactly as scoped -- (a) no persisted session ->
     Login/Register (`VaultLockState.LOGGED_OUT`), (b) a session was persisted but this is a fresh
     process with nothing in memory yet -> Unlock (`VaultLockState.LOCKED`, reached via the new
     `AuthSessionHolder.restoreLocked()`), (c) VDK is live in memory -> Home
     (`VaultLockState.UNLOCKED`). `restoreLocked()` is deliberately a no-op if a session already
     exists in memory (never clobbers a newer in-memory state with stale disk state) and **never**
     sets the VDK -- it only ever transitions to `LOCKED`, so reaching Home from a cold start still
     always requires a real password entry or a real biometric prompt afterward, identical to
     Sprint 3's existing background-lock unlock flow. This does not weaken security; it only makes
     that *existing* offline-unlock capability also survive a full process kill, which Sprint 3
     couldn't verify at the time (no emulator was available then to catch this gap).
   - **What's newly persisted, and how**: new `PersistedSessionStore`
     (`android/app/.../data/PersistedSessionStore.kt`) encrypts (AES-256-GCM) and stores the
     session token + `kdf_salt`/`kdf_params`/`wrapped_vdk` + email in their own SharedPreferences
     file (`SharedPrefsSessionStorage`). **The raw VaultDataKey is never persisted here or anywhere
     new** -- only the already-non-secret `wrapped_vdk`/`kdf_salt`/`kdf_params` triple (routinely
     sent to/from the server unauthenticated already, per Decision #1) plus the session token
     (a bearer credential, the one genuinely sensitive field). Encryption key: new
     `KeystoreSessionCipher` (`android/app/.../crypto/KeystoreSessionCipher.kt`), an Android
     Keystore AES-256-GCM key **deliberately without `setUserAuthenticationRequired(true)`**
     (unlike Sprint 3's biometric-gated `KeystoreVdkCipher`) -- this blob must be readable at cold
     start, before any authentication has happened, purely to answer "does a locked session exist";
     gating it behind biometric would make password-only unlock (always required as a fallback,
     even when biometric is enrolled) unreachable after a process kill. Both classes depend on a
     new `SessionCipherProvider` interface (mirrors the existing `MasterKeyDeriver`/`SettingsStorage`
     interface-for-testability pattern) so `PersistedSessionStore`'s actual
     serialize/encrypt/decrypt/deserialize logic is unit-tested for real via a fake plain-AES
     provider (`FakeSessionCipherProvider`), not just assumed correct -- the untestable-on-host-JVM
     part is isolated to the thin `KeystoreSessionCipher` adapter itself, same disclosed-gap
     category as every prior Keystore-touching sprint.
   - **Wiring**: `AuthRepository` gained an optional `sessionStore: PersistedSessionStore? = null`
     constructor param (defaults to null so every existing test keeps working unchanged) -- `login()`
     now also calls `sessionStore?.save(...)` right after `AuthSessionHolder.set(...)`, and
     `logout()` calls `sessionStore?.clear()` in its `finally` block (a full logout must wipe the
     persisted blob too, or a later process restart would incorrectly show Unlock for a session
     that was explicitly ended). `VeilKeeperApplication.onCreate()` builds the real
     `PersistedSessionStore` and calls `persistedSessionStore.load()?.let { AuthSessionHolder.restoreLocked(...) }`
     **before** `setContent` runs in `MainActivity` -- so by the time the Compose tree is built,
     `AuthSessionHolder.lockState` already reflects the restored state. `MainActivity`'s `NavHost`
     now computes `startDestination` from `AuthSessionHolder.lockState.value` directly (via
     `remember`, evaluated once) instead of hardcoding `ROUTE_LOGIN`, avoiding a Login-screen flash
     before the existing global `LaunchedEffect(lockState)` redirect would otherwise kick in.
   - **Ambiguity resolved without stopping** (documented here per this batch's own instruction,
     since a security-adjacent design call was made): whether to Keystore-encrypt the persisted
     session token at all, versus following `DeviceIdentity`'s existing precedent of plain
     SharedPreferences for "non-secret" data. Decided to encrypt it (via the new non-auth-required
     Keystore key) because, unlike `DeviceIdentity`'s opaque per-install UUID, the session token is
     a real bearer credential that alone can call authenticated backend endpoints (though never
     decrypt vault content without the VDK, and it's time-bounded by the backend's existing
     `SESSION_TTL_HOURS`) -- worth the one extra Keystore key for defense-in-depth against casual
     on-device file inspection, without requiring a biometric gate that would break password-only
     unlock after a process kill.
   - **Testing**: `AuthSessionHolderTest` gained 6 new cases for `restoreLocked()` (transitions a
     fresh holder to `LOCKED` never `UNLOCKED`; never sets the VDK; is a no-op against an
     already-unlocked or already-locked in-memory session; a restored `LOCKED` session unlocks
     normally via `unlock()`; `clear()` after `restoreLocked()` resets everything). New
     `PersistedSessionStoreTest` (7 cases): null-when-nothing-saved, full round-trip (including a
     null email), never leaks the plaintext token/email into the underlying storage strings, `clear()`
     wipes it, self-heals (returns null, doesn't crash) if the Keystore key becomes unusable out
     from under it, and overwrite-on-resave. `AuthRepositoryTest` gained 3 new cases: login persists
     to a real `PersistedSessionStore` (with `FakeSessionCipherProvider`), logout clears it, and a
     null `sessionStore` (every pre-existing test) still works unchanged. **143 unit tests passing**
     total (up from 116), all green.
   - **Emulator verification -- performed, this is the item that mattered most to verify for
     real, and it caught a real bug unit tests missed**: booted the same pre-existing
     `veilkeeper_test` AVD (Pixel 6, API 35, headless) used in batch 1, against the same live
     `veilkeeper-api`/`veilkeeper-mysql` stack. Registered a fresh test account, logged in for real
     (full on-device Argon2id/HKDF/AES-GCM), landed on Home. Ran
     `adb shell am force-stop id.quezacolt.veilkeeper` (a harder kill than a recent-apps swipe --
     force-stop guarantees full process death, which is the actual condition this fix targets) then
     relaunched via `adb shell am start`. **Confirmed the app landed directly on the "Vault locked"
     Unlock screen, not Login** -- `uiautomator dump`'s view hierarchy showed the Unlock screen's
     "Vault locked" text and password field, with the previously-registered email pre-filled, on
     first launch after the kill, no Login screen frame ever appeared.
     - **Bug found on this first pass**: entering the correct password and tapping Unlock left the
       app stuck showing "Vault locked" forever, even though `AuthSessionHolder` had genuinely
       flipped to `UNLOCKED` (password accepted, no error shown). Root cause: `MainActivity`'s
       global lock-state effect used bare `navController.popBackStack()` to leave the Unlock
       screen, which silently returns `false` and does nothing when Unlock is the navigation
       graph's *start* destination (this batch's new process-restart case) -- there is nothing
       beneath it on the back stack to pop back to. Pre-batch, this line only ever ran with Unlock
       pushed on top of an existing screen (the normal in-app auto-lock case), so it always had
       something to pop back to and the bug never existed before. **Fixed**: now checks
       `popBackStack()`'s return value and, only if it returned `false`, explicitly
       `navController.navigate(ROUTE_HOME) { popUpTo(ROUTE_UNLOCK) { inclusive = true } }` --
       preserves the exact old behavior (return to wherever Unlock was pushed from, e.g. a deep
       Vault Detail screen) for the normal auto-lock case, and only takes the new explicit-Home
       path for the process-restart case that didn't exist before this batch. Rebuilt, reinstalled,
       and re-ran the full kill/relaunch/unlock sequence -- confirmed fixed: unlock now correctly
       lands on Home with data intact. This is exactly the kind of gap code review and unit tests
       alone would not have caught (`AuthSessionHolder`'s own state transitions were all correct in
       isolation; the bug was purely in how `MainActivity` reacted to them via Navigation Compose,
       which has no host-JVM-testable equivalent).
     - Re-verified after the fix: correct password -> unlocks straight to Home with all data
       intact. Re-killed and entered the *wrong* password on Unlock -> stayed locked with a "wrong
       password" error, confirming the fix doesn't weaken authentication. Also exercised items #2-4
       live on the same session: added a real vault item (confirms batch 1's Home auto-refresh
       still works), opened it, triggered the Delete confirmation dialog and tapped Cancel
       (item survived), entered Edit mode, changed the title, tapped Save, and confirmed the new
       title persisted server-side by navigating back to Home and re-reading it fresh. This is the
       strongest evidence available (real process kill, real device, real crypto, a real bug caught
       and fixed) that item #1 works end-to-end, not just in unit tests.

2. **Confirmation dialogs on Delete actions.** New reusable `VeilKeeperConfirmDeleteDialog`
   (`android/app/.../ui/components/StateViews.kt`, a standard Material3 `AlertDialog`) applied to
   every *existing* destructive delete trigger in the app: Vault Detail's "Delete item" button, and
   (newly added as part of item #4 below) removing an "image" block while editing an item, which is
   a real, immediate server-side attachment delete (attachments in edit mode are uploaded
   immediately, not deferred like Add Item -- see `VaultDetailViewModel.removeEditBlock`'s doc
   comment). **Scope note, not extended beyond what already existed**: category delete
   (`VaultRepository.deleteCategory`/`VaultApi`) has no UI trigger anywhere in the app as of this
   batch -- there was never a "delete category" button to add a dialog to, and building one would be
   new UI scope beyond "add a confirmation dialog," so it was left alone. Removing a *draft* block
   (not-yet-saved text/secret/note content in Add Item, or in Vault Detail's edit mode before
   Save) intentionally does **not** get a confirmation dialog -- nothing has been persisted yet at
   that point, so there's nothing destructive to confirm; this matches how Add Item's block removal
   already worked pre-batch and avoids friction on a same as it's always been draft edit.

3. **Category screen auto-refresh bug -- same root cause and fix as Home's batch-1 fix, applied
   here.** Confirmed `CategoryViewModel` never got the `refreshSilently()` fix `HomeViewModel` got
   in batch 1 -- newly added items in a category didn't show up without leaving and reopening the
   app, because Compose Navigation keeps `CategoryScreen`'s `NavBackStackEntry` (and its
   ViewModel) alive on the back stack while Add Item is on top, so `init`'s one-time `refresh()`
   never re-ran on return. Applied the identical pattern: `CategoryViewModel.refreshSilently()`
   (re-fetches without touching `isLoading`, guarded against re-entrancy / redundant calls right on
   top of `init`'s own `refresh()`) plus a `LifecycleEventObserver` on `ON_RESUME` in
   `CategoryScreen` (`DisposableEffect(LocalLifecycleOwner.current)`), wired exactly like
   `HomeScreen`'s. New tests in `CategoryViewModelTest`: `refreshSilently` picks up newly-added
   items without touching `isLoading`, is a no-op while a fetch is in flight, and typing into
   search never triggers a network call (regression guard, unrelated to this fix but adjacent code).

4. **Vault Detail edit mode.** `VaultDetailViewModel` gained `isEditing`/`editTitle`/`editBlocks`
   state plus `startEdit()`/`cancelEdit()`/`onEditTitleChange()`/`addEditBlock()`/
   `addEditImageBlock()`/`removeEditBlock()`/`saveEdit()`. `VaultDetailScreen`'s top bar gets an
   Edit (pencil) button (hidden while already editing, replaced by a Check/Save button) alongside
   the existing Delete button. **UI reuse, per this batch's own instruction**: the per-block-type
   input form (type chips, label/value fields, image picker) and the block list row were extracted
   out of `AddItemScreen.kt` into a new shared file, `android/app/.../ui/vault/ContentBlockEditingComponents.kt`
   (`AddBlockRow`, `ContentPreviewRow`, `queryDisplayName`, all `internal`) -- both `AddItemScreen`
   and the new `VaultDetailEditContent` composable use the exact same components now, zero UI
   duplicated from scratch.
   - **Attachment handling decision** (resolved directly, not stopped-and-asked, since it's a pure
     simplification with no security/architecture impact, matching Section 56 Rule 2's threshold):
     Add Item's `PendingImage` deferral pattern (upload only happens at final Save, because that
     screen's item doesn't have a server-side ID yet) is **not** reused in edit mode -- the item
     being edited already exists, so `addEditImageBlock()` uploads immediately via the existing
     `VaultRepository.uploadAttachment` and appends the resulting "image" block to the draft right
     away. This also means removing an image block during editing is a real, immediate
     `VaultRepository.deleteAttachment` call (not just a local list edit) -- which is exactly why
     item #2's confirmation dialog applies to it specifically, and not to removing a draft
     text/secret/note block (which is genuinely still just local, undoable-by-not-saving state).
   - **Save**: re-encrypts the full draft (title + all blocks, via the existing `VaultItemCrypto`
     under the hood) and calls the existing `PUT /api/v1/vault/items/{id}` via
     `VaultRepository.updateItem(itemId, null, title, editBlocks)` -- **no backend changes needed**,
     this endpoint has existed since Sprint 2 and already accepts a full-content-replace payload.
     `backend/` is untouched by this whole batch (verified via `git status`).
   - **Testing**: 8 new `VaultDetailViewModelTest` cases -- `startEdit` seeds the draft from the
     loaded item, `cancelEdit` discards changes without touching the saved item, `saveEdit`
     re-encrypts/persists (verified by re-fetching the item fresh from the repository afterward,
     not just checking in-memory state), `saveEdit` rejects a blank title or an empty block list
     without calling the repository, `removeEditBlock` on a text block is purely local (no
     repository call, unsaved item on disk unchanged), `addEditImageBlock` uploads immediately and
     appends an "image" block, and `removeEditBlock` on an image block actually deletes the
     attachment server-side (verified via a follow-up `downloadAttachment` call failing). Confirmation
     dialog *triggering* (item #2) is a Compose-UI-level concern (local `remember` state gating an
     `AlertDialog`) with no new ViewModel logic to unit-test beyond what's already covered above --
     verified by code review + the emulator pass below, same category as Sprint 6's "3-line `when`
     doesn't need a parallel test suite" reasoning.

**Build/verification**: `./gradlew clean assembleDebug testDebugUnitTest lintDebug` -- all green,
**143 unit tests passing** (up from 116), **0 lint errors**, same 16 pre-existing warnings as every
prior batch (`GradleDependency` x11, `UnusedResources` x2, `ObsoleteSdkInt` x1,
`MonochromeLauncherIcon` x1, `DataExtractionRules` x1 -- no new warnings introduced).

**Emulator verification**: the same pre-existing `veilkeeper_test` AVD from batch 1 was still
available and was used again -- see item #1's dedicated writeup above for the force-stop/relaunch
Unlock-screen verification (which found and fixed a real navigation bug, not just confirmed the
happy path) -- this was this batch's priority given its sensitivity. On that same live session,
items #2-4 were also exercised for real, not just code-reviewed: added a real vault item via Add
Item (confirmed it appears immediately on Home, i.e. batch 1's auto-refresh fix is undisturbed),
opened it in Vault Detail, tapped Delete to trigger the new confirmation dialog and tapped Cancel
(item survived, dialog dismissed cleanly), tapped Edit, changed the title, tapped Save, and
confirmed the change persisted server-side by navigating back to Home and re-reading the title
fresh from a new screen. Category screen's own auto-refresh (item #3) was exercised as a side
effect of the same flow (opening the "Common" category showed the newly-added item without a
restart). **Not exercised this batch**: adding/removing an *image* block specifically in edit mode
(needs a real image picker interaction, same category of gap as prior sprints' attachment-picker
notes) and the biometric unlock path specifically after a process kill (no biometric enrolled on
this AVD) -- both are code-reviewed only and should be spot-checked on a real device before
considered fully done, same disclosure category as prior sprints' gaps.

`.env.example` unchanged (no new secrets, no backend changes). This file updated for this batch.

**Post-launch fixes (batch 3) — complete.** Android-only, `backend/` untouched (verified via
`git status`; `docker ps` up front showed `veilkeeper-api`/`veilkeeper-mysql` already running
healthy, zero collision, nothing brought up/down). One item, from real feedback using the app on a
physical device: "idle for a while, came back, got a 'vault is locked' error with a Retry button
that just loops forever."

1. **"Vault is locked / Retry -> infinite loop" bug -- root cause confirmed as diagnosed, fixed.**
   The initial diagnosis (given at the start of this batch) was correct: every `VaultRepository`
   method already returned `VaultError.NotUnlocked` when `AuthSessionHolder.vaultDataKey` was null
   (auto-lock fired while Home/Category/Vault-Detail was still the open screen), but that failure
   was rendered by `HomeViewModel`/`CategoryViewModel`/`VaultDetailViewModel` as a generic
   `VeilKeeperErrorState`-with-Retry -- and Retry just re-ran the same call against a still-missing
   VDK, failing the exact same way forever, never routing through `AuthSessionHolder.lockState`'s
   existing `LOCKED` transition or `MainActivity`'s existing global redirect to Unlock.
   - **What was NOT the bug, confirmed by code review**: `AuthSessionHolder`'s own invariant already
     guaranteed `vaultDataKey == null` implies `lockState != UNLOCKED` (every code path that clears
     the VDK -- `lock()`/`set()`/`clear()` -- also updates `lockState` in the same call), and
     `MainActivity`'s `LaunchedEffect(lockState)` (from batch 2) already reacts to `LOCKED` by
     navigating to Unlock. So by construction, `lockState` was *already* `LOCKED` by the time any
     ViewModel saw `NotUnlocked` -- the redirect mechanism itself was never broken. The bug was
     entirely that (a) nothing forced that redirect to happen deterministically/synchronously with
     the failure the user was looking at (it relied on `AutoLockManager`'s separately-timed
     lifecycle callback having *already* run, which real-device Compose recomposition/lifecycle
     dispatch timing could evidently desync from), and (b) even when the redirect did eventually
     fire, the screen underneath had already painted a Retry button that invited the user into a
     dead-end loop instead of just... doing nothing and letting Unlock cover it.
   - **Fix -- two layers, matching the batch's own instructions**:
     1. `VaultRepository` (`android/app/.../data/VaultRepository.kt`) gained a private
        `notUnlockedFailure()` helper, now used at all 6 call sites that used to construct
        `Result.failure(VaultError.NotUnlocked(...))` directly (`listItems`/`getItem`/
        `createItem`/`updateItem`/`uploadAttachment`/`downloadAttachment`). It calls
        `AuthSessionHolder.lock()` *before* returning the failure -- `lock()` is idempotent (a
        no-op if already `LOCKED`/`LOGGED_OUT`, see its own doc comment), so this is always safe
        and can never fight a fresher state (e.g. a real logout racing a stale in-flight call stays
        `LOGGED_OUT`, doesn't get bumped to `LOCKED` -- covered by a new repository test). This
        makes "the VDK is gone" -> "global state is `LOCKED`" a direct, synchronous consequence of
        the exact call the UI is reacting to, instead of two independently-timed paths.
     2. New top-level `fun Throwable.isVaultLocked(): Boolean` (same file) -- true only for
        `VaultError.NotUnlocked`. Every `VaultRepository`-consuming ViewModel
        (`HomeViewModel`, `CategoryViewModel`, `VaultDetailViewModel`, `AddItemViewModel`) now
        checks this before setting an `errorMessage`/`editErrorMessage`: if true, the failure is
        swallowed (no error text, no Retry button rendered) instead of surfaced, since
        `MainActivity`'s global effect is about to cover the screen with Unlock anyway and a Retry
        tap could never succeed. `VeilKeeperErrorState`'s Retry button itself is untouched and
        still exactly right for every *other* error (network timeout, 500, etc.) -- this is a
        call-site special-case, not a change to the shared component (per this batch's own
        instruction not to touch genuinely-transient-error handling).
   - **Vault Detail didn't have an auto-refresh-on-resume at all before this batch** (unlike Home/
     Category, which got it in batches 1/2) -- added `VaultDetailViewModel.refreshSilently()`
     (same guarded-against-re-entrancy pattern as the other two) wired to `VaultDetailScreen`'s
     `ON_RESUME` via the same `DisposableEffect(LocalLifecycleOwner.current)` pattern. This is what
     makes item requirement #2 (return to the *same* screen post-unlock, with fresh data) hold for
     Vault Detail too, not just Home/Category -- confirmed live (see below) that returning from
     Unlock lands back on the exact item screen the user was on, not Home.
   - **Edit-mode call sites** (`saveEdit`/`addEditImageBlock`/`removeEditBlock`'s image-attachment
     branch) also check `isVaultLocked()`: on a lock-mid-edit, they exit edit mode
     (`isEditing = false`) instead of showing an "upload/save failed" retry-style error, since the
     draft can't be safely re-encrypted against a VDK that's gone -- the user re-enters edit mode
     fresh (via `startEdit()`) after unlocking and `refreshSilently()`'s reload, rather than
     resuming a stale draft. Documented as a deliberate simplification, not a gap: re-seeding a
     live edit automatically across a lock/unlock cycle would be new state-reconciliation machinery
     for an edge case (locking mid-edit specifically) this batch's scope didn't ask for.
   - **Ambiguity resolved without stopping** (per this batch's own instruction, since a
     security-adjacent call was made): whether `refreshSilently()`'s `ON_RESUME`-driven reload
     should also try to preserve/re-seed an in-progress edit draft if the vault got locked
     mid-edit. Decided **not to** -- `refreshSilently()` only ever touches `VaultDetailUiState.item`,
     never `isEditing`/`editBlocks`, so an edit in progress when an *unrelated* resume happens
     (e.g. the normal in-app auto-lock case, not this bug) is left alone; the lock-mid-edit case
     specifically is instead handled by the call sites exiting edit mode themselves (previous
     bullet). Keeping these two concerns separate avoided a more tangled "was this resume because
     of a lock, or just a normal return-to-screen" branch inside `refreshSilently()` itself.
   - **Testing**: `VaultRepositoryTest` gained 3 new cases -- a `NotUnlocked` failure forces
     `lockState` to `LOCKED` when a session was active, a `NotUnlocked` failure after a real
     logout stays `LOGGED_OUT` (doesn't get incorrectly resurrected to `LOCKED`), and
     `isVaultLocked()` is true only for `NotUnlocked`. `HomeViewModelTest`/`CategoryViewModelTest`
     each gained 2 new cases (`refresh`/`refreshSilently` don't surface an error and drive
     `lockState` to `LOCKED` when the vault is locked mid-session, simulated via a direct
     `AuthSessionHolder.lock()` call standing in for `AutoLockManager` firing while the screen is
     open). `VaultDetailViewModelTest` gained 4 new cases: the same `refresh`/`refreshSilently`
     no-error-drives-LOCKED pair, `refreshSilently` actually picks up a server-side change made
     while locked (verifying the new auto-refresh-on-resume end to end, not just that it doesn't
     error), and `saveEdit` exits edit mode cleanly on a lock-mid-edit. **154 unit tests passing**
     total (up from 143), all green.
   - **Emulator verification -- performed, this is the item that mattered most to verify for
     real, and it directly reproduced the reported bug's actual trigger (backgrounding, not a
     process kill) for the first time**: booted the pre-existing `veilkeeper_test` AVD (Pixel 6,
     API 35, headless) against the live `veilkeeper-api`/`veilkeeper-mysql` stack (confirmed
     reachable via `curl https://veilkeeper.quezacolt.my.id/health` before starting, `200`).
     Registered a fresh test account, logged in for real (full on-device Argon2id/HKDF/AES-GCM),
     added a real vault item, opened it in Vault Detail. Confirmed this build's default auto-lock
     timeout is still `IMMEDIATE` (per batch 1), then sent the app to the background with
     `adb shell input keyevent KEYCODE_HOME` (a normal Home-button background, **not**
     `force-stop` -- the process stays alive throughout, exactly matching "idle a while, came
     back" rather than the swipe-kill/force-stop scenario batch 2 already covered) and brought it
     back to the foreground via `am start`. **Confirmed the app landed directly on the "Vault
     locked" Unlock screen with the email pre-filled -- not the old Vault-Detail "vault is locked"
     error-with-Retry state** -- `uiautomator dump`'s view hierarchy showed the Unlock screen, and
     `adb logcat` showed no crash/exception for the app's PID across the whole sequence. Entered
     the correct password -> unlocked straight back to the *same* Vault Detail screen (title and
     content block intact, not bounced to Home), confirming both the bug fix and the nav-position
     preservation/auto-refresh requirement live, not just in unit tests. Re-backgrounded/
     foregrounded once more and entered the *wrong* password -> stayed on Unlock with a "wrong
     password" error, confirming the fix doesn't weaken authentication. Emulator shut down
     cleanly afterward (`adb -e emu kill`); `docker ps` reconfirmed both `veilkeeper-*` and the
     Qoder `vk-sprint3-*` containers were undisturbed throughout.
   - **Not exercised this batch**: the screen-off-specific trigger (`AutoLockManager.onScreenOff`)
     and a real multi-minute idle timeout (as opposed to the `IMMEDIATE` default's
     background/foreground trigger) -- both share the exact same `AuthSessionHolder.lock()` call
     path already verified above, so this is judged a lower-value repeat rather than a genuine
     coverage gap, but is disclosed rather than silently assumed identical.

`.env.example` unchanged (no new secrets, no backend changes). This file updated for this batch.

## Project summary (all 8 sprints complete)

VeilKeeper is a complete, independent implementation of the "Veil Keepers" spec
(`SPEC-BASE.md`): a zero-knowledge, client-side-encrypted personal vault ("secure notebook,"
not a traditional password-manager form UI), Go backend + MySQL + Android app, built entirely
by Claude Code across Sprints 0-7 for direct comparison against a parallel Qoder+Kimi K3
build of the same spec running alongside it on the same homelab host.

- **Sprint 0** — repo/Docker/CI bootstrap, health/ready endpoints.
- **Sprint 1** — full auth (register/login/logout/prelogin) with the password-derived
  MasterKey → AuthKey/WrapKey → wrapped-VDK architecture (CLAUDE.md Decision #1), Argon2id
  client-side + server-side hashing, rate limiting + account lockout + anti-enumeration.
- **Sprint 2** — categories + vault items CRUD, client-side AES-256-GCM encryption end to
  end, strict per-user ownership isolation, Home/Category/Vault-Detail/Add-Item Android UI.
- **Sprint 3** — secure UX: clipboard auto-clear, auto-lock (background/timeout/screen-off),
  biometric unlock via Android Keystore (never touches the backend), FLAG_SECURE, Settings
  screen.
- **Sprint 4** — client-side global search over already-decrypted in-memory items; zero
  plaintext query ever reaches the backend (there is no backend search endpoint at all).
- **Sprint 5** — attachments: pick → compress → encrypt → upload → download → decrypt →
  preview, opaque server-side blob storage on the local filesystem, CSPRNG-generated
  filenames (no path-traversal surface), cascade delete of both DB rows and on-disk files.
- **Sprint 6** — dedicated UI/UX pass: custom "Midnight Vault" indigo theme (light + dark,
  dynamic color disabled for a predictable brand identity), reusable empty/loading/error
  state components, accessibility pass (touch targets, content descriptions, live regions),
  branding fix, cold-start theme-flash fix.
- **Sprint 7** — homelab deployment hardening: resource limits, MySQL tuning (halved idle
  RAM via disabling `performance_schema`), documented + verified backup/restore, verified
  restart-recovery (API/MySQL/full-stack) and real `docker stats` resource numbers, README
  final pass, self-hosted runner explicitly deferred as disclosed future work.

**End-to-end state**: backend `go test -race -cover ./...` passes (65 tests as of Sprint 5,
unchanged since -- Sprints 6-7 and all post-launch fix batches touched no backend code); Android
`./gradlew assembleDebug testDebugUnitTest lintDebug` passes (**154 unit tests** as of post-launch
fixes batch 3, up from 112 at Sprint 6, 0 lint errors, same 16 pre-existing warnings throughout);
all three GitHub Actions workflows (backend/android/security) green as of the
last commit touching CI-relevant files; `docker compose up -d` works from a fresh clone with
zero collision against the parallel Qoder build sharing the same Docker host throughout every
sprint's manual verification. Every crypto/architecture decision the base spec left
deliberately ambiguous is resolved and documented in "Resolved Design Decisions" above rather
than improvised. Every sprint's disclosed testing gaps (Argon2id/Keystore/BiometricPrompt/
ImageCompressor needing a real Android device/emulator, none available in this sandbox
environment across all 8 sprints) remain open and should be manually verified on a real
device before this ships to an actual end user -- this is the single biggest piece of
unverified work across the whole project, called out consistently sprint over sprint rather
than glossed over.

**Web client added after all 8 Android sprints landed** -- see "Web client (Sprint roadmap)"
below for its own tracking. Web Sprint 6 (Attachments) is complete as of the most recent
commit touching `web/`; Android/backend are unaffected by Web sprints themselves, though the
backend did get a small, carefully-verified CORS middleware addition just before Web Sprint 2
(see "Backend CORS fix" above) since Web Sprint 2 needed working cross-origin requests to
function at all. Web Sprints 4, 5, and 6 all needed **no** backend changes at all -- Sprint 6's
attachment endpoints already existed from Android Sprint 5.

## Web client (Sprint roadmap, separate from the 8 Android sprints above)

A Web client (`web/`, Vue 3 + TypeScript + Vite, monorepo-sibling of `android/`/`backend/`) is
a later addition to this repo, planned across its own 8-sprint roadmap, mirroring the
Android sprint documentation style. **CI policy differs from Android**: GitHub Actions is
mandatory for Android but only *optional* for Web (a basic build+lint workflow may exist but
is never a hard release gate) -- confirmed by the user, overriding anything implying otherwise
elsewhere. **Deployment policy differs from Android+Backend too**: unlike the Android app and
backend (deliberately public, see `signpdf`-style live exposure patterns elsewhere on this
host), the Web client's eventual deployment (Sprint 8) **must be internal/LAN-only** and must
never be registered with the `cloudflared` tunnel or otherwise exposed publicly. Not relevant
until Sprint 8; noted here so it isn't forgotten by then.

Planned roadmap (subject to revision as sprints land):

- **Sprint 1** — project scaffold + crypto foundation. Complete, see below.
- **Sprint 2** — Login/Register UI wired to the real crypto module + backend auth
  API (mirrors Android Sprint 1's UI scope, using the crypto foundation Sprint 1 already
  built and tested). Complete, see below.
- **Sprint 3** — vault foundation: categories + vault item CRUD, client-side
  encryption via the VDK. Complete, see below.
- **Sprint 4** — Secure UX: secret visibility/copy, clipboard auto-clear
  (with its real Clipboard API limitations disclosed, not assumed away), Web Session
  Lock (inactivity/tab-hidden lock + offline unlock, no biometric/Keystore equivalent
  exists on Web), Settings screen. Complete, see below.
- **Sprint 5 (this one)** — global search over the client-side-decrypted vault (mirrors
  Android Sprint 4), entirely client-side, no plaintext or search term ever sent to the
  backend. Complete, see below.
- **Sprint 6** — attachments (mirrors Android Sprint 5). Complete, see below.
- **Sprint 7 (planned)** — UI polish. Not yet scoped in detail; should get its own
  CLAUDE.md decisions section if anything is ambiguous, same as Android's sprints did.
- **Sprint 8 (planned)** — internal/LAN-only deployment (see policy note above).

### Web Sprint 1 (Scaffold + crypto foundation) — complete, with one disclosed cross-sprint blocker

Delivered:

- `web/` (Vue 3.5 + TypeScript + Vite 8, package name `web`), folder structure
  `src/{components,views,layouts,stores,services,crypto,router,types}` per the plan. Default
  Vite template content (HelloWorld component, hero image, `#app`-centric CSS) stripped out;
  replaced with a minimal single-route app (`/` → `HealthCheckView.vue`) using Vue Router and
  a Pinia store (`stores/health.ts`) for the health-check page's state.
- `src/crypto/` -- mirrors Android's `crypto/` package (`android/app/src/main/java/id/quezacolt/veilkeeper/crypto/`)
  field-for-field:
  - `kdfParams.ts`: `DEFAULT_KDF_PARAMS` = `{ memoryKiB: 65536, iterations: 3, parallelism: 4 }`,
    copied verbatim from Android's `KdfParams.DEFAULT`.
  - `hkdf.ts`: HKDF-SHA256 via native `crypto.subtle` (`"HKDF"` algorithm). **Important
    parity detail**: Web Crypto's HKDF does *not* apply RFC 5869's "no salt -> HashLen zero
    bytes" default the way a hand-rolled implementation (Android's `Hkdf.kt`) does -- an
    omitted/empty salt there means a literal zero-length HMAC key, not a 32-zero-byte one.
    This implementation always passes an explicit 32-byte zero salt to match Android exactly.
  - `aesGcm.ts`: AES-256-GCM via native `crypto.subtle`. Wire format
    (`nonce(12) || ciphertext+tag`) matches Android's `javax.crypto.Cipher` output exactly.
  - `argon2.ts`: Argon2id via `argon2-browser` (WASM), per the `spike/kmp-web-crypto`
    recommendation -- the one primitive Web Crypto API doesn't provide.
    `public/argon2.wasm` is a committed copy of `node_modules/argon2-browser/dist/argon2.wasm`
    (the library fetches this by URL at runtime in a real browser; re-copy it if the
    `argon2-browser` version is ever bumped).
  - `vaultCrypto.ts`: orchestrates the full key hierarchy (MasterKey -> AuthKey/WrapKey via
    HKDF -> VDK generate/wrap/unwrap via AES-GCM), mirroring `VaultCrypto.kt` 1:1 including the
    same HKDF info strings (`veilkeeper:auth:v1` / `veilkeeper:wrap:v1`) and salt/key lengths.
    Not wired into any UI yet -- exists now so the full hierarchy is testable end-to-end ahead
    of Sprint 2's Login/Register screens.
  - `src/types/argon2-browser.d.ts`: hand-written ambient module declaration (the npm package
    ships no `.d.ts` and there's no `@types/argon2-browser`).
- **Vitest suite, 18 tests, all passing** (`npm test`), covering exactly what CLAUDE.md/the
  task required to be *proven*, not assumed:
  - `hkdf.test.ts`: RFC 5869 Appendix A.3 Test Case 3 -- the *same* vector Android's
    `HkdfTest.kt` uses -- plus determinism and domain-separation checks.
  - `aesGcm.test.ts`: round-trip (with/without AAD), unique-nonce-per-call, wire-format byte
    length, tamper rejection, wrong-key rejection, wrong-key-length rejection.
  - `argon2.test.ts`: (1) the official RFC 9106 Section 5.3 Argon2id test vector -- reproduced
    independently here, not just trusted from the spike; (2) **the exact
    password/salt/`KdfParams.DEFAULT` scenario from `spike/kmp-web-crypto`'s
    `poc/verify-argon2-wasm.cjs`**, asserting the same
    `853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e` hash the spike proved
    byte-identical to `argon2-cffi` (same native reference-C lineage as Android's `argon2kt`).
    This is the strongest byte-identical evidence obtainable in this sandbox (no physical
    Android device available here to run `Argon2idMasterKeyDeriverInstrumentedTest`
    side-by-side) -- confirmed **matching**, not merely "should match."
  - `vaultCrypto.test.ts`: full registration->login simulation (derive, wrap, re-derive from
    the same password, unwrap, assert VDK round-trips byte-identical), domain separation, and
    wrong-password-fails-to-unwrap.
  - **Environment quirk discovered and documented, not silently patched around**:
    `argon2-browser`'s compiled Emscripten glue picks its WASM-loading strategy by checking
    `typeof fetch === 'function'`, not by checking whether it's actually in a browser. Node
    18+'s global `fetch` makes it take a `fetch(<absolute filesystem path>)` branch under
    Vitest's `node` environment, which Node's `fetch` (undici) rejects with `TypeError: Failed
    to parse URL`. `vitest.setup.ts` deletes `global.fetch` before tests run (matching the
    same workaround the spike's own `poc/verify-argon2-wasm.cjs` used) -- this only affects the
    Vitest process, never the real browser build (`src/crypto/argon2.ts` never touches `fetch`
    directly; it points `window.argon2WasmPath` at `/argon2.wasm` instead).
- `src/services/api.ts` + `src/services/health.ts`: base API client (`VITE_API_BASE_URL` env
  var, default `http://localhost:18091/`, never hardcoded -- mirrors Android's `apiBaseUrl`
  Gradle property pattern) and a `GET /health` call. `stores/health.ts` (Pinia) wraps it with
  idle/checking/ok/error state; `views/HealthCheckView.vue` is the only functional page this
  sprint, as scoped.
- `web/README.md`: install/dev/build/env-var instructions, crypto module documentation.
  `web/.env.example` added (no real values, gitignored `.env` pattern matches repo root).
- Tooling: ESLint flat config (`eslint.config.js`, `typescript-eslint` + `eslint-plugin-vue`
  `flat/recommended` + `eslint-config-prettier`) and Prettier (`.prettierrc.json`) -- both
  clean (`npm run lint`, `npm run format:check`). `npm run build` (`vue-tsc -b && vite build`)
  succeeds. No Web CI workflow was added this sprint (allowed per the CI policy above -- this
  is disclosed as not-yet-done, not silently skipped).

**Disclosed blocker, confirmed with a real headless browser (Playwright/Chromium), not
assumed**: the Go backend (`backend/internal/httpserver`) has **no CORS middleware at all** --
confirmed via `grep -i cors backend/internal/httpserver/*.go` (zero matches). A real browser
loading the Web app and calling `GET /health` gets network-level success but the browser
blocks the JS from reading the response: `Access to fetch at '.../health' from origin
'http://localhost:5183' has been blocked by CORS policy: No 'Access-Control-Allow-Origin'
header is present.` Verified against **both** a local `docker compose up -d` backend
(`http://localhost:18091`) and the live public backend
(`https://veilkeeper.quezacolt.my.id`) -- same failure both times, root cause is backend-side,
not an environment/config issue on the Web side. The health-check page itself is implemented
correctly (its `error` state and message display exactly this failure, by design -- see
`HealthCheckView.vue`). **Not fixed this sprint**: the task explicitly prohibits touching the
live backend this sprint (see the Sprint 1 task's constraints), and adding CORS middleware is
a backend code change, not a new standalone service, so it doesn't fall under the
"nambah service baru buat web" exception either. **This should be resolved by Sprint 2 at the
latest** (Login/Register will need working cross-origin requests to function at all, not just
to display a status). Suggested minimal fix when that sprint starts: an
`Access-Control-Allow-Origin` middleware in `backend/internal/httpserver` scoped to the
Web app's own origin(s) (dev + eventual internal/LAN-only deployment origin, per the
Sprint 8 policy above -- never `*` given this is a zero-knowledge auth backend).

Manual verification performed: `npm run dev` starts cleanly and serves the app; confirmed via
Playwright/Chromium (headless, installed temporarily for this verification, not a project
dependency) that the page renders, Pinia/Vue Router work, and the health-check flow actually
executes a real network request to both a local and the live backend (`curl
https://veilkeeper.quezacolt.my.id/health` directly returns `{"status":"ok"}`, confirming the
backend itself is healthy -- the CORS block above is the browser's own enforcement, not a
backend outage). No `web/.env` file was left behind (only `.env.example`, gitignored pattern
verified). `docker ps` reconfirmed no interaction with/collision against the running
`veilkeeper-*` or Qoder `vk-sprint3-*` stacks -- Sprint 1 only ever issued read-only `GET
/health` requests against the already-running `veilkeeper-api` container, never restarted or
reconfigured it.

### Backend CORS fix (pre-Sprint-2, resolves the Web Sprint 1 disclosed blocker above)

Added a minimal stdlib-only CORS middleware (`backend/internal/httpserver/cors.go`,
`corsMiddleware`) wrapping the router in `NewMux` (which now returns `http.Handler` instead of
`*http.ServeMux`, since the wrapping needs a decorator). No third-party CORS framework added --
matches this backend's existing stdlib-only design.

- New env var `CORS_ALLOWED_ORIGINS` (comma-separated allowlist, see `.env.example`), default
  `http://localhost:5173,http://127.0.0.1:5173` (Vite dev server). **Never a wildcard** -- this
  is a zero-knowledge auth backend. Web Sprint 8's internal/LAN deployment origin will be added
  here too once it exists.
- Behavior: no `Origin` header at all (Android app, curl, server-to-server) -> completely
  transparent, zero behavior change. `Origin` present but not allowlisted -> request still
  processed normally (no CORS header attached; the browser enforces the block client-side, not
  this server). `Origin` present and allowlisted -> the matched origin is echoed back in
  `Access-Control-Allow-Origin` (never `*`), plus `Access-Control-Allow-Methods` (GET, POST,
  PUT, DELETE, OPTIONS), `Access-Control-Allow-Headers` (`Authorization, Content-Type`),
  `Access-Control-Max-Age: 600`, `Vary: Origin`. `Access-Control-Allow-Credentials` is
  deliberately never set (auth uses a Bearer token, not cookies). Preflight `OPTIONS` requests
  are short-circuited with `204` and never reach the underlying router.
- Tests: `backend/internal/httpserver/cors_test.go` (`httptest`-based, no MySQL needed) --
  allowed origin gets the headers, disallowed origin gets none but the request still completes,
  no-Origin request is fully transparent, preflight OPTIONS returns 204 with headers and never
  reaches the wrapped handler, disallowed-origin preflight returns 204 with no CORS headers.
  `go test -race -cover ./...`: **71 tests, all passing** (up from 65), `gofmt`/`go vet` clean.
- **Local verification before touching the live backend**: brought up a fully separate,
  disposably-named Docker Compose stack (`docker compose -p veilkeeper-corstest`, temporary
  `.env.test` with fresh dummy secrets -- never the real production `.env` -- and a temporary
  `docker-compose.corstest.yml` override using Compose's `!override` merge tag for `ports`/
  `env_file`, since Compose merges those lists by concatenation by default and a naive override
  collided with the live `veilkeeper-api` container's port 18091 on the first attempt). Verified
  with `curl`: no-Origin request behaves identically to before; allowlisted-Origin request gets
  the CORS headers; disallowed-Origin request gets none. Verified with a **real headless
  browser** (Playwright/Chromium, `web/` pointed at the test backend via a temporary
  `VITE_API_BASE_URL`): the health-check page loads with `Status: ok` and zero console errors --
  the exact CORS failure Web Sprint 1 documented is gone. Also ran a full auth smoke test against
  the test stack (register -> wrong-key login rejected 401 -> correct login 200 -> logout 204 ->
  post-logout request correctly 401'd, CORS headers present throughout on both success and error
  responses) to confirm the fix doesn't change auth behavior at all. Test stack, `.env.test`, the
  override compose file, and `web/.env` were all torn down/deleted afterward -- none committed,
  none left running.
- **Deployed to the live backend** (`veilkeeper-api`/`veilkeeper-mysql` on this same MACMINI
  host): `docker compose up -d --build api` from this commit (pushed to `main` first). Production
  `.env` was updated with the same `CORS_ALLOWED_ORIGINS` default (Vite dev origins) -- no other
  existing secret (`SERVER_PEPPER`, `DB_PASSWORD`, etc.) was touched or regenerated. **Note**:
  `docker compose up -d --build api` recreated `veilkeeper-mysql` too, not just `api` -- Compose
  detected the `.env` file itself changed (the new `CORS_ALLOWED_ORIGINS` line) and treated that
  as a config diff for every service reading that env file, mysql included. This is expected
  Compose behavior, not a mistake to avoid next time, and is harmless here: the named volume
  (`veilkeeper-mysql-data`) is untouched by a container recreate, only `down -v` would drop it.
  Post-deploy verification: `/health` and `/ready` both responded normally within seconds; a real
  register (via `curl`, no `Origin` header, simulating the live Android app) returned `user_id: 5`
  -- proving users 1-4 from before the restart survived intact, i.e. no data loss from the mysql
  recreate -- followed by wrong-key login (401), correct login (200), and logout (204), all
  behaving identically to before the change; `curl` with `Origin: http://localhost:5173` against
  the live backend now returns `Access-Control-Allow-Origin`/`Access-Control-Allow-Methods`/etc.
  (including on preflight `OPTIONS` and on error responses), and a `https://evil.example.com`
  origin correctly gets none. `docker ps` reconfirmed zero collision with the Qoder build
  throughout.

### Web Sprint 2 (Authentication) — complete

Delivered:

- `web/src/views/{LoginView,RegisterView,DashboardView}.vue` + `web/src/stores/auth.ts` (Pinia)
  + `web/src/services/authApi.ts` implement the same password-derived key hierarchy as Android
  (CLAUDE.md Resolved Design Decision #1), calling the real `POST /api/v1/auth/{prelogin,
  register,login,logout}` endpoints (same endpoints Android's Sprint 1 uses): Argon2id ->
  MasterKey -> HKDF -> AuthKey (sent)/WrapKey (kept) -> VaultDataKey generate/wrap (register) or
  unwrap (login). New `web/src/crypto/base64.ts` (encode/decode helper, needed to move key
  material over the JSON wire -- Sprint 1 never needed one) and `web/src/types/auth.ts` (wire
  DTOs + `KdfParamsWire`/`KdfParams` field-name mapping: wire uses `memory`, the TS-side type
  uses `memoryKiB`, matching the backend's `auth.KDFParams` Go struct exactly).
- **Deliberate simplification, disclosed**: session token and unwrapped VDK live in the Pinia
  store's state only, never written to localStorage/sessionStorage -- a page refresh logs the
  user out. Mirrors Android Sprint 1's own equivalent choice (`AuthSessionHolder`, no disk
  persistence until a Keystore-backed cache lands in a later Android sprint); Web has no
  biometric/Keystore-equivalent design decision made yet either (see the Sprint 3-7 roadmap note
  above). A per-browser random `device_identifier` (non-secret, just a UUID, carries no key
  material) is persisted in localStorage (`web/src/services/device.ts`) so repeat logins from the
  same browser map to the same `devices` row server-side instead of minting a new one every time.
- `web/src/router/index.ts`: protected-route guard (`meta.requiresAuth`) redirects to `/login`
  (preserving the intended destination via a `redirect` query param) when there's no active
  session; `meta.publicOnly` on `/login`/`/register` redirects an already-authenticated user to
  `/dashboard`. The Sprint 1 health-check page moved from `/` to `/health` (no longer the default
  route); `/` now redirects to `/dashboard`.
- **Two real bugs found only by testing against a real browser (Playwright/Chromium), invisible
  to the existing Vitest suite** -- both fixed, both documented in detail in `web/README.md`
  (see "A required upstream patch" and "A real cross-runtime AES-GCM bug" there):
  1. `argon2-browser` (WASM Argon2id, from Web Sprint 1) is fundamentally incompatible with this
     project's Vite 8 (rolldown-based) bundler once actually wired into the app bundle -- Sprint
     1 never hit this because nothing in its UI called the crypto module yet. Fixed via a
     `patch-package` patch (`web/patches/argon2-browser+1.18.0.patch`, applied automatically via
     a new `postinstall` script) that (a) makes the library's browser-vs-Node detection check for
     `window`/`document` instead of the unreliable `typeof require` (which a browser bundler's own
     CJS-interop shim can make falsely truthy), and (b) obscures the Node-only
     `require('../dist/argon2.wasm')` call via indirect eval so rolldown's static analysis doesn't
     try to statically bundle a CJS require of an async-ESM-with-top-level-await `.wasm` module
     (which is a real, valid bundler restriction, not just strictness). Also added
     `vite-plugin-wasm` as a dev dependency (needed for `.wasm` handling generally under this Vite
     version; its own published types needed a local re-cast in `vite.config.ts`, documented there
     -- a known dual-CJS/ESM packaging type mismatch, not a runtime issue).
  2. `web/src/crypto/aesGcm.ts`'s `encrypt`/`decrypt` always included an `additionalData` key in
     the WebCrypto algorithm object, set to `undefined` when no AAD was passed (which is what
     every current caller does -- VDK wrap/unwrap never uses AAD). Node's `crypto.subtle` (what
     Vitest runs against) silently tolerates `additionalData: undefined`; real browser WebCrypto
     (Chromium) throws `additionalData: Not a BufferSource` if the key is present at all, even
     with an undefined value. Fixed by conditionally spreading the key in only when AAD is
     actually provided. **This is exactly why Sprint 2's acceptance criteria required real-browser
     Playwright verification, not just unit tests** -- this bug was invisible to the existing
     (correct, passing) `aesGcm.test.ts` suite the whole time.
- **Vitest: 36 tests passing** (up from 18 in Sprint 1) -- new: `crypto/__tests__/base64.test.ts`
  (round-trip incl. empty input, standard-not-URL-safe alphabet check), `services/__tests__/
  authApi.test.ts` (mocked `fetch`: request shapes, `ApiError` on every 4xx path, success paths),
  `services/__tests__/device.test.ts` (id generation + persistence via a fake `localStorage`),
  `stores/__tests__/auth.test.ts` (real crypto, mocked `authApi` only: register never auto-logs-
  in, `email_taken` surfaces a friendly message, **login end-to-end derives the correct AuthKey
  and unwraps a VDK wrapped exactly the way a real registration would have**, invalid-credentials
  surfaces a generic message and leaves state unauthenticated, logout clears local state even if
  the server call fails and is a no-op with no active session). `npm run lint` / `format:check` /
  `vue-tsc -b` / `npm run build` (production bundle, now succeeds thanks to the argon2-browser
  patch above) all clean.
- **End-to-end verification, performed for real against the live backend** (not a local/test
  stack -- this sprint's whole point was proving the CORS-fixed live backend actually works from
  a real browser): `npm run dev` (Vite dev server, `web/.env` pointed at
  `https://veilkeeper.quezacolt.my.id/`, temporary, deleted afterward) + a real headless Chromium
  session via Playwright (installed temporarily, not a project dependency, same pattern as Web
  Sprint 1's own verification). All of the following passed, checked programmatically (URL after
  navigation, page body text, zero browser console errors on the pages that touch key material):
  visiting `/dashboard` while unauthenticated redirects to `/login?redirect=/dashboard`;
  registering a fresh account (`web-sprint2-e2e-<timestamp>@example.com`) succeeds and redirects
  to `/login?registered=1`; logging in with the wrong password stays on `/login` and shows
  "Incorrect email or password." (the generic anti-enumeration-safe message, not "wrong
  password"); logging in with the correct password redirects to `/dashboard` and displays the
  logged-in email; clicking "Log out" redirects to `/login`; `/dashboard` is protected again
  immediately after logout. Re-ran the full sequence a second time after a clean
  `rm -rf node_modules/argon2-browser && npm install` to confirm the `postinstall` patch-package
  step reapplies correctly on a fresh install, not just in the already-patched working tree --
  same result. **This creates a small number of permanent (harmless) test accounts in the live
  production database** (`web-sprint2-e2e-*@example.com`, plus one earlier ad hoc
  `deploy-verify-*@example.com` from Part A's own live-deploy verification) -- accepted as normal
  for this homelab comparison project, same as the curl-based smoke tests Sprint 1/2 of the
  backend itself already did against the same live database.
- No Web CI workflow added this sprint either (still allowed per the CI policy in the roadmap
  intro above -- Web CI is optional, never a hard gate, confirmed by the user).

### Web Sprint 3 (Vault Foundation -- categories + vault item CRUD) — complete

Mirrors Android Sprint 2's scope, adapted to Vue/TS. **No backend changes** -- the
categories/vault-item endpoints (`GET/POST /api/v1/categories`, `PUT/DELETE
/api/v1/categories/{id}`, `GET/POST /api/v1/vault/items`, `GET/PUT/DELETE
/api/v1/vault/items/{id}`) already existed from Android Sprint 2; this sprint only added a
Web client consuming them.

Delivered:

- `web/src/types/vault.ts` -- wire DTOs (`CategoryDto`, `VaultItemDto`) matching the backend's
  Go structs field-for-field, plus the plaintext `VaultItemPayload`/`ContentBlock` shape
  (`type: "text" | "secret" | "note"`, `label: string | null`) mirroring Android's
  `VaultItemCrypto.kt` -- deliberately using explicit `null` (not `undefined`) for an absent
  label so the JSON shape matches Android's `kotlinx.serialization` (`encodeDefaults = true`)
  output byte-for-byte, since either client may decrypt an item created by the other.
- `web/src/crypto/vaultItemCrypto.ts` -- `encryptVaultItemPayload`/`decryptVaultItemPayload`,
  mirroring `VaultItemCrypto.kt` 1:1 (JSON-serialize, then `aesGcm.ts` encrypt/decrypt with the
  VDK, fresh nonce per call).
- `web/src/services/vaultApi.ts` -- thin authenticated HTTP wrappers (bearer token) over the
  categories/vault-item endpoints, following the same Api-is-dumb-HTTP split as `authApi.ts`.
  **Refactor, not new complexity**: `ApiError`/`parseJsonOrThrow` moved from `authApi.ts` up
  into `api.ts` (re-exported from `authApi.ts` for compatibility) so both services share one
  implementation instead of duplicating it.
- `web/src/stores/vault.ts` (Pinia) -- categories + current item-list state, one store (not
  split into separate categories/items stores -- the state is small and closely related,
  splitting would be overengineering for this scope). Every action calls `requireSession()`
  first (throws if there's no active `sessionToken`/`vdk` in `stores/auth.ts`) -- this store
  never fetches/decrypts anything unless the router's `requiresAuth` guard has already let the
  user in.
- Views: `DashboardView.vue` (now the vault Home: category list with item counts + a "recent
  items" list, plus inline category creation -- still the `/dashboard` route from Sprint 2, now
  with real content instead of a placeholder), `CategoryView.vue` (`/categories/:id` -- item
  list, inline rename, delete-with-reassign-choice), `VaultItemView.vue` (`/items/:id` --
  decrypted content blocks, secrets masked behind a "Reveal" toggle, delete), and
  `VaultItemFormView.vue` (`/items/new` and `/items/:id/edit` -- title, category select,
  add/remove content blocks). Attachment/image blocks are explicitly out of scope (Web Sprint
  6, per the roadmap above) -- the form only offers `text`/`secret`/`note`.
- **Delete category behavior: kept byte-for-byte identical to Android/backend, no Web-specific
  deviation.** `vault.deleteCategory(id, reassignTo?)` passes straight through to
  `DELETE /api/v1/categories/{id}[?reassign_to=<id>]` -- omitting `reassignTo` relies on the
  backend's own default (move to the lazily-created Uncategorized category, per Resolved Design
  Decision #5 above). `CategoryView.vue`'s delete-confirmation UI offers a dropdown of the
  user's other categories with "Uncategorized (default)" pre-selected, so the default path is
  the zero-click path. This was a genuinely ambiguous point worth flagging even though the
  decision was straightforward: the task allowed "consistent with Android or a documented
  deviation," and consistency was chosen because CLAUDE.md's own Decision #5 already treats this
  as a cross-client contract ("Web HARUS konsisten"), not a per-client UX choice -- there was no
  reasonable argument for Web to special-case it.
- **Two real bugs found via Playwright against a real browser, invisible to the Vitest suite**
  (same category of finding as Web Sprint 2's two bugs) -- both fixed:
  1. `DashboardView.vue`'s `loadAll()` and `CategoryView.vue`'s `load()` originally wrapped their
     `await`s in `try { ... } finally { ... }` with no `catch` -- any store-action rejection
     (e.g. a session invalidated mid-flight, or a 404 on a stale category link) became an
     unhandled promise rejection logged to the browser console instead of surfacing through
     `vault.errorMessage`'s existing banner. Both now `catch` and swallow (the banner/empty-state
     UI already covers the user-facing side); this is exactly the class of bug Sprint 2's README
     said Playwright verification exists to catch that Vitest cannot.
  2. None found in the crypto layer this sprint -- `aesGcm.ts`'s Sprint 2 AAD-omission fix
     already covers the vault-item encryption path too (it's the same function), and no new
     browser-vs-Node WebCrypto divergence showed up.
- **Vitest: 60 tests passing** (up from 36 in Sprint 2) -- new: `crypto/__tests__/
  vaultItemCrypto.test.ts` (full encrypt -> base64-wire-simulation -> decrypt round-trip, unique
  nonce per call, wrong-VDK rejection, tamper rejection, zero-content-block edge case --
  the crypto-integration-at-the-item-level test the task required, not just the primitive),
  `services/__tests__/vaultApi.test.ts` (request shapes incl. `reassign_to` query param
  presence/absence, `ApiError` on 404/409), `stores/__tests__/vault.test.ts` (real crypto,
  mocked `vaultApi` only: `requireSession()` throws with no session, `fetchItems` proves real
  decryption happened (not passthrough), `createItem` proves the server call never receives
  plaintext, `deleteCategory` behavior with/without `reassignTo`, 403/404 both map to the same
  user-facing "doesn't exist or no access" message). `npm run lint` / `format:check` / `vue-tsc
  -b` / `npm run build` all clean.
- **End-to-end verification, performed for real against the live backend** (`npm run dev` +
  Playwright/Chromium, installed temporarily via `npm install --no-save playwright` and
  uninstalled afterward -- not a project dependency, same pattern as Sprints 1/2; `web/.env`
  pointed at `https://veilkeeper.quezacolt.my.id/`, deleted afterward). All 15 scripted checks
  passed: register+login; create category; create a vault item with three content blocks
  (text/secret/note); item detail shows the decrypted title and text/note values with the secret
  masked by default and revealed on click; edit the item and confirm the new title persists;
  delete the category the item is in and confirm (a) it redirects cleanly and (b) the item still
  exists, now reassigned to Uncategorized -- not deleted; **log out and log back in, then
  re-open the same item and confirm it still decrypts correctly** (proves a real
  encrypt-on-server/decrypt-after-fresh-login round trip, not a same-session cache -- this was
  the specific scenario called out as most important to prove); zero unexpected browser console
  errors across that whole flow (after the two bug fixes above; the browser's own
  "Failed to load resource" network-log lines for expected-error responses like the 404 below
  are filtered out of that check, same as they would be in a real browser's console for any
  4xx/5xx fetch regardless of app-level handling). Separately, **registered a second user and
  confirmed they get a clear "doesn't exist, or you don't have access" message (not the item's
  title, not a crash) when visiting the first user's item by ID** -- the backend enforces
  ownership by scoping every query to the authenticated user (returns a plain 404, confirmed by
  reading `backend/internal/httpserver/vault_handlers.go` -- there's no separate 403 path in this
  handler set), and this client now handles that response correctly. **Verified the database
  itself holds only ciphertext**: read-only `SELECT` against the live `veilkeeper-mysql`
  container's `vault_items` table confirmed `encrypted_payload` for the test item is a 257-byte
  binary blob (nonce+ciphertext+tag), and `SELECT COUNT(*) ... WHERE encrypted_payload LIKE
  '%sprint3user%' OR ... '%S3cretValue%' OR ... '%My Sprint 3 Item%'` returned `0` -- no
  plaintext title, label, or value is present anywhere in that column. **This creates a small
  number of permanent (harmless) test accounts/categories/items in the live production
  database** (`web-sprint3-e2e-{a,b}-*@example.com`), same accepted pattern as Sprints 1/2's own
  live-backend verification.
- No Web CI workflow added this sprint either (still optional per the roadmap intro's CI
  policy).

### Web Sprint 4 (Secure UX) — complete

Mirrors Android Sprint 3's scope (secret visibility, clipboard security, auto-lock),
**adapted, not copy-pasted** -- Web has no Keystore, no `BiometricPrompt`, and no
`FLAG_SECURE`. Screenshot protection (SPEC-BASE.md Section 33) was skipped entirely, per
the spec's own explicit instruction that Web cannot provide this and "should not pretend
that it can" -- no fake/partial implementation was attempted. **No backend changes** --
everything here is client-side.

- **Secret visibility + Copy** (`web/src/views/VaultItemView.vue`): Show/Hide already
  existed from Sprint 3; this sprint adds a Copy button on **every** content block (not
  just `type === "secret"`), same reasoning Android's own clipboard wiring doc comment
  gives (everything in this vault is sensitive).
- **Clipboard security** (`web/src/crypto/clipboard.ts`) -- **the disclosed Clipboard API
  limitation this sprint's task specifically asked to research first, not assume away**:
  the initial `navigator.clipboard.writeText()` copy always runs synchronously inside the
  button's click handler, so it's reliable. The scheduled auto-clear, however, fires later
  from a `setTimeout`; per the Clipboard API spec, a programmatic clipboard write requires
  the document to still have focus, so if the user has switched tabs/apps before the timer
  elapses, the clear silently fails (`NotAllowedError`) and the clipboard is left holding
  the value. **There is no browser API to force a clear without focus, and no reliable
  cross-browser way to detect "the user came back" and retry.** Unlike Android (which can
  read the current clipboard back to skip clearing if a newer copy superseded it), doing
  the equivalent on Web would need the separate, more sensitive `clipboard-read`
  permission just to decide whether to clear -- judged a worse privacy trade than
  unconditionally overwriting, so skipped deliberately. **Decision taken and disclosed,
  not hidden**: auto-clear is presented to the user as best-effort only, with the
  limitation spelled out in plain language directly in the Settings screen's clipboard
  section (`web/src/views/SettingsView.vue`) and in `web/README.md`'s "Secure UX (Sprint
  4)" section -- never presented as a guarantee. Never logs the copied value, clipboard
  content, or error detail anywhere (console, network, storage) -- `clipboard.test.ts`
  has an explicit test asserting no `console.log`/`console.error` call ever contains the
  copied value, including on the deferred-clear failure path.
- **Web Session Lock** (SPEC-BASE.md Section 32) -- **the other ambiguity this sprint's
  task flagged, resolved and documented here rather than stopped-and-asked, since it
  followed directly from CLAUDE.md's existing "lock is not logout" principle (Android's
  "Post-launch fixes batch 2")**:
  - `web/src/stores/auth.ts` gained a three-state `lockState`:
    `'logged_out' | 'locked' | 'unlocked'`. Locking (`lock()`) clears **only** the
    in-memory VDK -- `sessionToken`, `email`, and a new `unwrapMaterial` (the non-secret
    `kdf_salt`/`kdf_params`/`wrapped_vdk` captured at login, mirroring Android's
    `VdkUnwrapMaterial`) are all kept. `unlockWithPassword(password)` re-derives
    MasterKey/WrapKey and unwraps the **same** VDK entirely offline (no network call) --
    exactly Android's `AuthRepository.unlockWithPassword` shape, adapted to the fact that
    Web doesn't need to survive a process kill the way Android's batch-2 fix did (a live
    tab keeps its Pinia state naturally; only VDK needs clearing on lock). Wrong password
    fails cleanly, stays `locked`, never touches the session token.
  - `web/src/services/idleTimer.ts` (`createInactivityWatcher`) reacts to two signals,
    mirroring Android's `AutoLockManager`: foreground mouse/keyboard/touch/scroll
    inactivity (reset-on-activity `setTimeout`, arms only for a positive timeout), and
    `visibilitychange` (tab hidden/shown) -- `"Immediately"` locks the instant the tab is
    hidden (like Android's screen-off receiver); any other timeout records the hide
    timestamp and checks elapsed time on becoming visible again (like Android's
    "record background timestamp, check on foreground resume" logic) -- deliberately no
    `setInterval` kept running while hidden, since background tabs throttle/suspend
    timers unpredictably across browsers. The pure lock-decision math lives in
    `web/src/services/autoLockPolicy.ts` (`shouldArmIdleTimer`/`shouldLockImmediatelyOnHide`/
    `shouldLockOnResume`), split out purely for host-JVM-free unit testability, mirroring
    Android's own `AutoLockPolicy` object.
  - `web/src/App.vue` wires the watcher globally (starts/stops based on `lockState`,
    re-arms on a Settings timeout change) and -- **a real bug caught by this sprint's own
    Playwright verification, fixed before landing, not hypothetical** -- imperatively
    calls `router.push('/locked')` the moment `lockState` flips to `'locked'`. Without
    this, the router's `beforeEach` guard alone only redirects on the *next* navigation
    attempt, so a user who simply stopped touching the page would have the VDK silently
    cleared in the background while a fully decrypted vault item stayed rendered on
    screen indefinitely. Mirrors Android's own centralized `LaunchedEffect(lockState)`
    fix from "Post-launch fixes batch 2" almost exactly, just discovered here in Sprint 4
    directly instead of as a later post-launch fix.
  - `web/src/router/index.ts`: the guard now redirects to `/locked` whenever
    `auth.isLocked` is true (any route except `/locked` itself and `/health`), and bounces
    `/locked` onward (to `/dashboard` if unlocked, `/login` if never logged in) if visited
    without an actual locked session.
  - `web/src/views/LockedView.vue`: the Unlock-screen equivalent -- password field only
    (no biometric option, none exists on Web), "Log out instead" escape hatch.
  - **Decision, disclosed**: reload/tab-close behavior is unchanged from Sprint 2's
    session-token-is-memory-only design and was deliberately **not** extended this
    sprint, even though the task explicitly allowed persisting the session token to
    localStorage "remember me"-style (while keeping VDK/password material out of it).
    Not doing so avoids quietly expanding Sprint 2's already-disclosed simplification
    into a brand-new session-persistence design decision (stale/expired-token-across-reload
    handling, XSS exposure surface of a persisted bearer token, etc.) without a sprint
    dedicated to thinking through its own failure modes -- same restraint principle every
    prior sprint applied. A full reload today still always logs the user out completely;
    this satisfies the task's actual requirement ("reload harus diminta login/unlock
    ulang") without over-delivering on the optional part.
  - **Default auto-lock timeout: 5 minutes, deliberately not "Immediately"** despite that
    now being Android's own default (CLAUDE.md "Post-launch fixes batch 2", item 4) --
    documented directly in `web/src/types/lock.ts`'s doc comment. Android's default
    changed because of an Android-specific process-kill bug that has no Web analogue;
    defaulting Web to Immediately would instead make the app re-lock on every innocuous
    tab switch (`visibilitychange` fires far more often, and for more benign reasons,
    than Android's "app backgrounded" signal), which would make the app annoying to use
    out of the box. Fully user-configurable down to Immediately regardless.
- **Settings screen** (`web/src/views/SettingsView.vue`, `stores/settings.ts`,
  `services/settingsStorage.ts`): auto-lock timeout (Immediately/1/5/15 min, same option
  list as Android for cross-platform UX consistency) + clipboard clear delay (15/30/60s,
  same as Android Sprint 3), both persisted to localStorage as plain preference id
  strings (non-secret, same "fine to persist" category as `services/device.ts`'s device
  id -- never the VDK/session token), plus "Lock now" and "Log out" buttons. Deliberately
  minimal, no theme/profile/biometric settings (no Web biometric equivalent exists),
  matching Android's own `SettingsScreen.kt` restraint (SPEC-BASE.md Section 56 Rule 1).
- **Vitest: 93 tests passing** (up from 60 in Sprint 3) -- new: `autoLockPolicy.test.ts`
  (pure decision functions, no DOM), `idleTimer.test.ts` (fake timers + fake
  target/document doubles: idle-timeout locking, activity resets, "Immediately"
  tab-hidden locking, elapsed-time-on-resume locking, `stop()` cleanup, live
  timeout-change re-arming), `clipboard.test.ts` (copy success, scheduled clear firing,
  no-clear-when-delay-is-0, unavailable-API/permission-denied paths, the deferred-clear
  failure never throwing or logging, and an explicit "never logs the copied value"
  assertion), `settingsStorage.test.ts` (persistence + fallback-to-default for
  corrupted/unknown stored ids), and new `stores/auth.test.ts` cases for the full lock
  state machine (`lock()` clears VDK but keeps session/email/unwrapMaterial and is a
  no-op when not unlocked; `unlockWithPassword` with the correct password restores the
  exact VDK with **zero** network calls; wrong password fails cleanly and stays locked;
  unlocking with no locked session throws; `logout()` from a locked state fully resets to
  `logged_out`). `npm run lint` / `format:check` / `vue-tsc -b` / `npm run build` all
  clean.
- **End-to-end verification, performed for real against the live backend** (`npm run dev`
  on the default port 5173 -- required, since the backend's `CORS_ALLOWED_ORIGINS`
  allowlist only includes the Vite dev-server default; a non-default port silently fails
  CORS, caught and fixed during this sprint's own verification setup -- + a real headless
  Chromium session via Playwright, installed temporarily via `npm install --no-save
  playwright`, uninstalled afterward, same pattern as every prior sprint; `web/.env`
  pointed at `https://veilkeeper.quezacolt.my.id/`, deleted afterward). **All 20 scripted
  checks passed**, all navigation after the initial page load done via real in-app link
  clicks (not `page.goto()`) since a full browser navigation reloads the page and this
  app's session state is deliberately memory-only -- a `page.goto()` mid-flow would just
  re-prove "reload logs out" instead of testing the lock/unlock flow. Used Playwright's
  Clock API (`page.clock.install()`/`fastForward()`) to advance browser time without
  waiting real minutes, installed **before** the Settings timeout change that arms the
  idle timer (the fake clock only affects timers scheduled after installation -- a real
  `setTimeout` armed beforehand is unaffected by `fastForward`, a real ordering bug hit
  and fixed while writing this verification, not a hypothetical caveat):
  1. Registered, logged in, created a category + a vault item with a secret content
     block.
  2. Secret masked by default; Show reveals the exact original value; Copy puts the
     exact value on the real OS clipboard (`navigator.clipboard.readText()` verified via
     a context granted `clipboard-read`/`clipboard-write` permissions) and the status
     line discloses the focus-limitation caveat.
  3. Set clipboard delay to 15s via Settings; copied again; confirmed the clipboard held
     the value immediately after copying and was genuinely empty ~15.5s later, while the
     tab stayed focused throughout -- proving the auto-clear isn't just theoretical.
  4. Set auto-lock to 1 minute; fast-forwarded 1m5s of fake idle time; confirmed the app
     **proactively** navigated to `/locked` (the App.vue bug above, caught right here).
  5. Confirmed an in-app back-navigation attempt while locked is bounced back to
     `/locked` by the router guard (SPA history navigation, no reload -- genuinely
     exercises the guard's `auth.isLocked` branch).
  6. Unlocked with the correct password; confirmed it leaves `/locked`; navigated to the
     same item via the Recent list and confirmed **the exact same title and the exact
     same secret value** decrypt correctly -- proving the *same* VDK was restored
     offline, not a fresh login/register cycle.
  7. From Settings, "Lock now"; entered a wrong password -- stayed on `/locked` with an
     "Incorrect password." error, session token untouched; then entered the correct
     password immediately after and it worked, proving a failed unlock attempt never
     drops the session.
  8. Set auto-lock to "Immediately"; simulated the tab being hidden by overriding
     `document.visibilityState` and dispatching a real `visibilitychange` event (the
     closest a headless Chromium session can get to a real OS-level tab switch); confirmed
     immediate redirect to `/locked` -- proving this trigger is independent of the
     foreground idle timer.
  9. A full page reload while unlocked still fully logs the user out back to `/login`,
     confirming reload behavior is genuinely unchanged from Sprint 2.
  10. Zero unexpected browser console errors across the entire flow.
  - **Database spot-check**: read-only `SELECT` against the live `veilkeeper-mysql`
    container confirmed the test item's `encrypted_payload` is a 143-byte opaque blob and
    that no row's `encrypted_payload` contains the plaintext title or secret value
    (`LIKE '%Sprint4%'` / `LIKE '%XyZ99%'` both returned 0 rows) -- same
    ciphertext-only verification pattern as Sprint 3.
  - **This creates one permanent (harmless) test account/category/item in the live
    production database** (`web-sprint4-e2e-*@example.com`), same accepted pattern as
    every prior Web sprint's own live-backend verification.
- No Web CI workflow added this sprint either (still optional per the roadmap intro's CI
  policy).
- **Tooling note**: `rtk` (v0.43.0) was available and confirmed working at the start of
  this sprint (`rtk --version`). Actually used for this sprint's `git status`/`git log`
  checks at the start; the bulk of this sprint's shell work was `npm test`/`npm run
  lint`/`npm run build`/`node <playwright script>`/`docker exec ... mysql` for
  verification, none of which are part of rtk's git/gh/docker-focused rewrite set the same
  way `git status` is -- used directly, consistent with every prior sprint's disclosed
  fallback pattern (docker exec *is* nominally in rtk's rewrite set, but was invoked via
  a one-off read-only `SELECT` spot-check, not a workflow rtk specifically optimizes).

### Web Sprint 5 (Search) — complete

Mirrors Android Sprint 4's search scope exactly (SPEC-BASE.md Section 16 / Phase 4), adapted
to Web's existing Home view rather than a copy-pasted screen. **No backend changes** --
everything here is client-side, and the acceptance bar was verified for real, not assumed:
search never sends the query string or any vault plaintext to the backend.

- **`web/src/services/vaultSearch.ts`** (`matchesQuery`/`filterItems`) -- a pure, synchronous
  in-memory filter over already-decrypted `DecryptedVaultItem[]` (title, every content
  block's `label`, every content block's `value` -- case-insensitive substring match),
  field-for-field the same shape as Android's `data/VaultSearch.kt`. Secret blocks' label
  and value are included in matching (same reasoning as Android: the item is already
  decrypted in memory regardless, and a match never displays the secret's value anywhere --
  it still renders hidden-by-default). **Tags are skipped**, confirmed by checking
  `VaultItemPayload`/`ContentBlock` (`web/src/types/vault.ts`) and the backend schema first
  rather than assumed -- there is no tag concept anywhere in this repo, matching Android
  Sprint 4's own documented no-op.
- **Data source decision, matching CLAUDE.md Resolved Design Decision #4's "in-memory only
  for the unlocked session" option (the option Android Sprint 4 already took) with zero
  Web-specific deviation**: `web/src/views/DashboardView.vue` already called
  `vault.fetchItems()` with no `categoryId` on mount (Sprint 3), which fetches and
  client-side-decrypts **every** vault item across all categories into `vault.items` (Pinia
  store, memory-only, cleared on lock/logout) for its own "Recent items" list. Search reuses
  that exact same array -- no new fetch, no new decrypt, no persistent cache anywhere
  (localStorage/IndexedDB never touched by search). This was confirmed by reading
  `DashboardView.vue`/`stores/vault.ts` before writing any search code, not assumed.
- **UI**: a single search input added directly to `DashboardView.vue` (Home), not a
  separate route/view -- mirrors Android Sprint 4's own choice to put search on Home rather
  than a dedicated screen, and avoids adding a second view that would just re-fetch the same
  data (SPEC-BASE.md Section 56 Rule 1, no overbuilding). While the query is non-blank, the
  Categories/"New category" form and "Recent items" sections are swapped for a single
  "Search results" list (`searchResults` computed, filtering `vault.items` via
  `filterItems`); clearing the query restores the normal dashboard. No debounce was added --
  filtering a client's realistic vault-item count in memory is effectively instant, and a
  debounce would only be solving a problem that doesn't exist here (confirmed by the
  network-call-count assertion below never ticking regardless of typing speed).
- **Vitest: 102 tests passing** (up from 93 in Sprint 4) -- new `vaultSearch.test.ts`
  covers: case-insensitive title match, content-block label match, content-block value
  match (covers both "note" and generic "text" types), secret-block label/value match, a
  null `label` not throwing, blank/whitespace query matching everything, a query that only
  matches across a title+value boundary correctly *not* matching, and `filterItems`
  returning the unfiltered array for a blank query vs. the correct subset otherwise.
  `npm run lint` / `format:check` / `vue-tsc -b` / `npm run build` all clean.
- **End-to-end verification, performed for real against the live backend**
  (`npm run dev` on the default port 5173, required for the backend's
  `CORS_ALLOWED_ORIGINS` allowlist -- same setup as every prior Web sprint; `web/.env`
  pointed at `https://veilkeeper.quezacolt.my.id/`, deleted afterward) + a real headless
  Chromium session via Playwright (installed temporarily via `npm install --no-save
  playwright`, uninstalled afterward, same pattern as every prior sprint). All 8 scripted
  checks passed:
  1. Registered a fresh test account, logged in, landed on `/dashboard`.
  2. Created one category ("Finance") and three vault items with deliberately distinct,
     non-overlapping searchable content: "Home Router Admin" (title match target), a
     `secret`-type block labelled "Recovery Code" on "Personal Email" (label match target,
     including the secret-block-label case), and a `note`-type block containing "fridge
     whiteboard" on "Wifi Network" (content-value match target).
  3. Typed `router` -- confirmed the results list showed **only** "Home Router Admin".
  4. Typed `recovery code` -- confirmed **only** "Personal Email" matched (proves secret
     labels are searchable without displaying the secret value anywhere on screen).
  5. Typed `fridge` -- confirmed **only** "Wifi Network" matched (proves note/text content
     values are searchable, not just titles/labels).
  6. Typed a non-matching query -- confirmed the "No matching items." empty state renders.
  7. Cleared the query -- confirmed the Categories section reappeared (proves search
     doesn't permanently replace the normal dashboard view).
  8. **The acceptance-critical check**: every request to `veilkeeper.quezacolt.my.id` was
     logged via Playwright's `page.on('request', ...)`. The call count was snapshotted
     immediately before the first search keystroke (18 calls, from
     register/login/category-create/3×item-create/navigation) and again after all five
     search interactions above (typing 4 different queries plus clearing the field): still
     **18 calls, unchanged**. This directly proves no network request -- to fetch, to log,
     or otherwise -- was triggered by typing a search query, which is the actual acceptance
     bar (not just "results look right").
  - **This creates one permanent (harmless) test account/category/3 items in the live
    production database** (`sprint5-*@example.com`), same accepted pattern as every prior
    Web sprint's own live-backend verification.
- No Web CI workflow added this sprint either (still optional per the roadmap intro's CI
  policy).
- **Tooling note**: `rtk` (v0.43.0) confirmed working at the start of this sprint (`rtk
  --version`, `rtk git log`). The bulk of this sprint's shell work was `npm test`/`npm run
  lint`/`npm run format:check`/`npm run build`/`node <playwright script>`, none of which are
  part of rtk's git/gh/docker-focused rewrite set -- used directly, same disclosed fallback
  pattern as every prior Web sprint.

### Web Sprint 6 (Attachments) — complete

Mirrors Android Sprint 5's scope exactly (SPEC-BASE.md Phase 5, "Web Attachments" task). **No
backend changes** -- `POST/GET/DELETE /api/v1/vault/items/{id}/attachments[/{attachmentId}]`
already existed from Android Sprint 5, and the backend treats attachment bytes as opaque
client-produced ciphertext regardless of which client uploaded them.

- **Attachment-linking decision: zero Web-specific deviation from Android/the backend's
  existing contract**, confirmed by reading `attachment_handlers.go`'s package doc comment and
  `AttachmentCrypto.kt` before writing any code, not assumed: an "image" content block's
  existing generic `value` field holds the attachment's server-assigned numeric ID as a decimal
  string. `web/src/types/vault.ts`'s `ContentBlockType` gained `'image'` alongside
  `'text' | 'secret' | 'note'`; no new field added to `ContentBlock`.
- `web/src/crypto/attachmentCrypto.ts` (`encryptFile`/`decryptFile`/`encryptFilename`/
  `decryptFilename`) mirrors Android's `AttachmentCrypto.kt` 1:1: a thin `aesGcm.ts` wrapper,
  file bytes and filename encrypted as two independent AES-256-GCM operations (each own nonce).
- **Compress-if-appropriate: implemented, not skipped** -- judged simple enough for this
  sprint's scope given the Web platform's native `createImageBitmap`/`<canvas>` APIs (no WASM
  dependency needed, unlike Argon2id). `web/src/services/imageCompressor.ts`'s `compressImage`
  mirrors Android's `ImageCompressor.kt` field-for-field: downscale to ≤1600px longest side,
  re-encode as JPEG quality 0.8, run *before* encryption (ciphertext doesn't compress). Falls
  back to uploading the original bytes/mime-type if compression isn't possible in a given
  browser (`compressImage` returns null on decode failure) -- never silently drops the pick.
  **Not unit-testable under Vitest/jsdom** (jsdom implements neither `createImageBitmap` nor a
  real `<canvas>` rasterizer) -- same disclosed-gap category as Android's own `ImageCompressor`
  (not testable on the host JVM either, no Robolectric dependency there). Verified instead via
  a real headless-Chromium script during this sprint's own manual testing (see below) and via
  the end-to-end Playwright pass: a real 40×30 PNG (127 bytes) compressed to a 770-byte JPEG
  before encryption, and the resulting attachment (`mime_type: "image/jpeg"`, `size: 798` =
  12-byte nonce + 770-byte JPEG + 16-byte GCM tag) decrypted and rendered back at the exact
  original 40×30 dimensions.
  **A real bug caught during this verification, not hypothetical**: the first attempt used a
  hand-typed base64 PNG literal for the test image, which `createImageBitmap` rejected with
  `InvalidStateError: The source image could not be decoded` in real Chromium (despite `file`/
  `sips` on macOS reading it fine) -- root cause was a malformed/non-spec-compliant hand-crafted
  PNG, not a bug in `imageCompressor.ts`. Switched to a Pillow-generated PNG for all
  verification after confirming decode success in isolation first.
- `web/src/services/vaultApi.ts` gained `uploadAttachment`/`getAttachment`/`deleteAttachment`
  (thin HTTP wrappers, same Api-is-dumb-HTTP split as every other endpoint in this file).
  `web/src/types/vault.ts` gained `AttachmentDto`/`AttachmentDataDto` (field-for-field the
  backend's `attachmentResponse`/`attachmentDataResponse` Go structs).
- `web/src/stores/vault.ts` gained `uploadAttachment(itemId, data, mimeType, filename)` (encrypts
  client-side, returns the new attachment id -- caller appends the resulting `{type: "image",
  value: String(id)}` block and calls `updateItem`, mirroring Android's create-then-upload-then-
  update-item flow), `downloadAttachment(itemId, attachmentId)` (returns a decrypted `Blob` +
  filename + mime type), and `deleteAttachment(itemId, attachmentId)` (server-side delete only --
  callers separately remove the content block and call `updateItem`).
- **Add/Edit form** (`web/src/views/VaultItemFormView.vue`), **the same "already-existing-item-
  required" constraint Android Sprint 5 documented** (the endpoint is
  `/vault/items/{id}/attachments`): new items hold picked images in memory as `pendingImages`
  (local `URL.createObjectURL` preview of the original file, no encryption/upload yet); `onSubmit`
  creates the item with non-image blocks first, uploads each pending image against the new item
  id, then makes one more `updateItem` call appending the resulting image blocks. **Disclosed
  limitation, same as Android**: no rollback if an upload fails partway through save -- the item
  already exists with whatever uploaded successfully, surfaced via a specific error message
  rather than silently losing state. Editing an existing item uploads a newly picked image
  immediately (the item already exists) and deletes an existing image block's attachment
  immediately on remove (gated behind an inline confirm, since -- unlike removing a draft
  text/secret/note block -- there is no local-only draft state to discard, the file only ever
  existed as server-side ciphertext). "An image alone satisfies the content requirement" --
  same rule as Android's `AddItemViewModel`.
- **Preview + secure blob-URL rendering** (`web/src/views/VaultItemView.vue`,
  `VaultItemFormView.vue`): image blocks are downloaded+decrypted lazily right after the item
  loads and rendered via `URL.createObjectURL` on the decrypted `Blob` -- **deliberately never
  a base64 data-URI**, researched and documented directly in `VaultItemView.vue`'s doc comment:
  a data-URI would put the *decoded* image bytes directly in the DOM's `src` attribute as a long
  string, which can end up captured by browser extensions/dev-tools state in a way a blob URL
  (an opaque local reference the browser resolves in-memory, resolvable only within the page
  that created it) does not. Every created blob URL is tracked in a `Record<index, url>` and
  explicitly `URL.revokeObjectURL`'d both when an attachment is removed and on
  `onBeforeUnmount` (both views) -- no blob URL (or the memory backing it) outlives the
  component that created it.
- **Vitest: 117 tests passing** (up from 102 in Sprint 5) -- new `attachmentCrypto.test.ts`
  (round-trip for file bytes and filename
  through encrypt → base64 wire → decrypt, unique nonces, tamper/wrong-key detection, mirroring
  `vaultItemCrypto.test.ts`'s shape), new `vaultApi.test.ts` cases for
  `uploadAttachment`/`getAttachment`/`deleteAttachment` (request shape, 404 on mismatched
  item/attachment), and new `stores/vault.test.ts` "attachments" describe block (`uploadAttachment`
  never sends plaintext filename/bytes to the mocked API, `downloadAttachment` decrypts a
  realistic server response back to the exact original bytes/filename via the real crypto
  module, `deleteAttachment` calls through correctly, upload failure surfaces the same
  session-expired message pattern as every other store action). `npm run lint` / `format:check`
  / `vue-tsc -b` / `npm run build` all clean.
- **End-to-end verification, performed for real against the live backend**
  (`npm run dev` on the default port 5173, required for `CORS_ALLOWED_ORIGINS` -- same setup as
  every prior Web sprint; `web/.env` pointed at `https://veilkeeper.quezacolt.my.id/`, deleted
  afterward) + a real headless Chromium session via Playwright (installed temporarily via `npm
  install --no-save playwright`, uninstalled afterward, same pattern as every prior sprint). All
  navigation after the initial page load done via real in-app link clicks (not `page.goto()`),
  same reasoning as every prior Web sprint (memory-only session, a full navigation would log
  out). Scripted checks, all passed:
  1. Registered a fresh account, logged in, clicked "+ New item", filled a title + one text
     block, picked a real 40×30 test PNG (generated via Pillow, not hand-crafted, after the
     `createImageBitmap` decode-failure bug above was caught and root-caused) via the file
     input -- confirmed a local pending-image preview rendered *before* submitting (proves the
     picker itself works independent of upload).
  2. Submitted -- confirmed navigation to `/items/{id}` (item created, image uploaded, item
     updated with the image block, all three real network calls against the live backend).
  3. **Acceptance-critical check**: the attachment card's `<img src>` was confirmed to start
     with `blob:` (not a data-URI, not the raw API URL), and `img.naturalWidth`/`naturalHeight`
     were confirmed to be the exact original **40×30** -- proving the full
     download→decrypt→render pipeline produces a correct, undistorted image, not just "an img
     tag exists."
  4. Clicked the attachment's Delete button, confirmed via the inline confirm dialog, and
     confirmed the attachment card disappeared from the DOM afterward.
  5. Zero browser console errors across the entire flow (`page.on('console'/'pageerror')`
     tracked and asserted empty).
  - **The acceptance-critical "file is not openable as a plain image" check, verified two
    independent ways** (both against a *separate* run that deliberately skipped step 4's delete,
    so the ciphertext stayed in place to inspect):
    1. **Direct filesystem inspection**: `docker exec veilkeeper-api` read the on-disk file at
       the DB-reported `storage_path` (`/data/attachments/<user_id>/<random>.bin`) -- `file`
       reported plain `data` (not any recognized image format), and macOS `sips -g pixelWidth`
       against a copy of the same bytes returned `pixelWidth: <nil>` (fails to parse it as an
       image at all). A hex dump's first bytes (`76a1 5ed1 ce20 30c5 ...`) match neither the PNG
       signature (`89 50 4E 47`) nor a JPEG SOI marker (`FF D8`), confirming genuinely random-
       looking ciphertext, not a mis-tagged real image.
    2. **Database spot-check**: `SELECT ... FROM attachments` confirmed `mime_type =
       "image/jpeg"` and `size = 798` bytes (12-byte nonce + 770-byte compressed JPEG + 16-byte
       GCM tag -- matches the compression step's own logged output exactly, proving the stored
       size is ciphertext-of-the-compressed-image, not the original 127-byte PNG).
    - After this inspection, a normal (non-skip-delete) run confirmed the delete flow for real:
      `SELECT COUNT(*) FROM attachments WHERE vault_item_id = <id>` returned `0` immediately
      after clicking Delete in the UI and confirming, proving the DB row is genuinely removed
      server-side, not just hidden client-side.
  - **Authentication/ownership enforcement, verified directly against the live backend, not
    assumed from reading the (unchanged) backend code**: `curl` with no `Authorization` header
    against the just-created attachment's `GET` endpoint returned `401`; a bogus bearer token
    also returned `401` with `{"error":"unauthorized"}`. A **second, independently registered**
    test account's real session token was used to `GET` the first account's vault item and its
    attachment -- both returned `404` (the same "doesn't exist" response CLAUDE.md's Resolved
    Design Decisions already require, never a distinguishing `403`), confirming cross-user
    isolation holds for the attachment endpoints exactly as the Android Sprint 5 verification
    already proved for the Android client.
  - **This creates a small number of permanent (harmless) test accounts/items/attachments in the
    live production database** (`web-sprint6-e2e-*@example.com`), same accepted pattern as every
    prior Web sprint's own live-backend verification. No attachment ciphertext copied out of the
    container was left behind afterward (the one local copy made for the `file`/`sips` check was
    deleted from the local filesystem once the check completed).
- No Web CI workflow added this sprint either (still optional per the roadmap intro's CI
  policy).
- **Tooling note**: `rtk` (v0.43.0) confirmed working at the start of this sprint (`rtk
  --version`, `rtk git log`/`git status`). The bulk of this sprint's shell work was `npm test`/
  `npm run lint`/`npm run format:check`/`npm run build`/`node <playwright script>`/`docker exec`/
  `curl`/`python3` (generating a valid test PNG via Pillow) for verification, none of which are
  part of rtk's git/gh/docker-focused rewrite set the same way `git status`/`git log` are --
  used directly, consistent with every prior sprint's disclosed fallback pattern (`docker exec`
  is nominally in rtk's rewrite set, but these were one-off read-only inspection commands, not a
  workflow rtk specifically optimizes).
