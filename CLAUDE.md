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
unchanged since -- Sprints 6-7 touched no backend code); Android
`./gradlew assembleDebug testDebugUnitTest lintDebug` passes (112 unit tests as of Sprint 6,
0 lint errors); all three GitHub Actions workflows (backend/android/security) green as of the
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
