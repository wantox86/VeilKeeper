# VeilKeeper Web

Web client for VeilKeeper (Vue 3 + TypeScript + Vite). See the repo root
[`CLAUDE.md`](../CLAUDE.md) for the full product context, resolved design
decisions, and the Web sprint roadmap.

**Status: all 8 Web sprints delivered** (scaffold+crypto, auth, vault CRUD,
secure UX, search, attachments, UI polish, and homelab deployment). See
root [`CLAUDE.md`](../CLAUDE.md#web-client-sprint-roadmap-separate-from-the-8-android-sprints-above)
for the full sprint-by-sprint history. Sprint 8 (deployment)'s disclosed
multi-device-LAN blocker is now **resolved** via a self-signed TLS
certificate -- read "Deployment (Sprint 8)" below for how to generate the
cert and what each device needs to do once (`https://<LAN-IP>:18092`, plus
a one-time manual trust of the certificate warning).

## Requirements

- Node.js 20+ (developed/tested against Node 22)
- The VeilKeeper backend running somewhere reachable (see root
  `README.md` -- `docker compose up -d` at the repo root gives you
  `http://localhost:18091`)

## Setup

```bash
cd web
npm install
cp .env.example .env   # edit VITE_API_BASE_URL if your backend isn't on :18091
```

## Common commands

```bash
npm run dev              # start the Vite dev server (default: http://localhost:5173)
npm run build             # type-check (vue-tsc) + production build to dist/
npm run preview           # serve the production build locally
npm test                  # run the Vitest suite once
npm run test:watch        # Vitest in watch mode
npm run test:coverage     # Vitest with v8 coverage (src/crypto, src/services)
npm run lint               # ESLint (flat config, Vue + TypeScript rules)
npm run lint:fix           # ESLint with --fix
npm run format             # Prettier --write
npm run format:check       # Prettier --check (CI-friendly)
```

## Environment variables

| Variable            | Required                                   | Description                                                                                                       |
| ------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------- |
| `VITE_API_BASE_URL` | No (defaults to `http://localhost:18091/`) | Base URL of the backend API, trailing slash. Never hardcode a backend URL in source -- see `src/services/api.ts`. |

Copy `.env.example` to `.env` (gitignored) and adjust. To manually verify
connectivity against the **live** backend without touching it, point
`VITE_API_BASE_URL` at `https://veilkeeper.quezacolt.my.id/` temporarily --
the health-check page only ever issues a `GET /health` request, nothing
that mutates state.

## Project layout

```text
src/
  components/   Shared, reusable Vue components (empty so far)
  views/        Route-level pages: Health/Login/Register/Dashboard/Category/VaultItem(Form)/Locked/Settings
  layouts/      Page shells/layouts for later sprints (empty so far)
  stores/       Pinia stores: health.ts, auth.ts (session/email/VDK/lockState, in-memory only), vault.ts, settings.ts (localStorage-backed prefs)
  services/     API client + backend service calls: api.ts, health.ts, authApi.ts, vaultApi.ts, device.ts, idleTimer.ts, autoLockPolicy.ts, settingsStorage.ts
  crypto/       Client-side crypto: HKDF, AES-GCM, Argon2id, key hierarchy, base64, vaultItemCrypto, clipboard
  router/       Vue Router setup, incl. the protected-route auth guard
  types/        Shared TypeScript types + hand-written ambient declarations
```

## Crypto module (`src/crypto/`)

Mirrors the Android client's key hierarchy
(`android/app/src/main/java/id/quezacolt/veilkeeper/crypto/`) exactly, per
repo `CLAUDE.md` Resolved Design Decision #1 and the `spike/kmp-web-crypto`
feasibility research:

- **`argon2.ts`** -- Argon2id via `argon2-browser` (WASM build of the
  unmodified P-H-C reference C implementation -- the same lineage Android's
  `argon2kt` binds to natively). This is the one primitive the Web Crypto
  API doesn't provide. `public/argon2.wasm` (copied from
  `node_modules/argon2-browser/dist/argon2.wasm`) is what the browser
  fetches at runtime; if you ever bump the `argon2-browser` version, re-copy
  that file.
- **`hkdf.ts`** -- HKDF-SHA256 (RFC 5869) via the native `crypto.subtle`
  `"HKDF"` algorithm. Always uses an explicit 32-byte zero salt (matching
  Android's hand-rolled implementation) so output is byte-identical for the
  same inputs.
- **`aesGcm.ts`** -- AES-256-GCM via native `crypto.subtle`. Wire format
  (`nonce (12 bytes) || ciphertext+tag`) matches Android exactly.
- **`vaultCrypto.ts`** -- orchestrates the full key hierarchy (MasterKey ->
  AuthKey/WrapKey via HKDF -> VaultDataKey wrap/unwrap via AES-GCM), mirroring
  `VaultCrypto.kt`. Not wired into any UI yet (no Login/Register screens
  until Sprint 2) -- exists now so it's testable end-to-end today.

All of the above are unit-tested (`src/crypto/__tests__/`) against official
test vectors:

- HKDF: RFC 5869 Appendix A.3 Test Case 3 (same vector as Android's
  `HkdfTest.kt`).
- Argon2id: the official RFC 9106 Section 5.3 test vector, **and** the exact
  password/salt/`KdfParams.DEFAULT` scenario proven byte-identical to
  Android's crypto lineage in the `spike/kmp-web-crypto` research (see that
  branch's `crypto-spike/README.md` for the full chain of evidence).
- AES-GCM: round-trip, unique-nonce-per-call, and tamper/wrong-key rejection.

## Health check

`src/services/health.ts` + `src/stores/health.ts` + `src/views/HealthCheckView.vue`
call the backend's `GET /health` (pure liveness, no auth, no side effects)
and display the result. Reachable at `/health` (no longer the default route
as of Sprint 2).

## Authentication (Sprint 2)

`LoginView.vue` / `RegisterView.vue` / `DashboardView.vue` (protected) +
`stores/auth.ts` (Pinia) + `services/authApi.ts` implement the same
password-derived key hierarchy as Android (CLAUDE.md Resolved Design
Decision #1): Argon2id -> MasterKey -> HKDF -> AuthKey (sent)/WrapKey
(kept) -> VaultDataKey generate/wrap (register) or unwrap (login).

**Deliberate simplification, disclosed**: the session token and unwrapped
VDK live in the Pinia store's in-memory state only -- never written to
localStorage/sessionStorage. A page refresh logs the user out (the router
guard in `router/index.ts` redirects `/dashboard` back to `/login`).
Mirrors Android Sprint 1's own equivalent choice (`AuthSessionHolder`, no
disk persistence until a Keystore-backed cache lands) -- Web has no
biometric/Keystore-equivalent design decision made yet either. A per-browser
random `device_identifier` (non-secret, fine to persist) is kept in
localStorage (`services/device.ts`) so repeat logins map to the same
`devices` row server-side.

### A required upstream patch: `argon2-browser` + Vite 8 (rolldown)

`patches/argon2-browser+1.18.0.patch` (applied automatically via
`postinstall` -> `patch-package`) fixes a real incompatibility, not a
config quirk: `argon2-browser`'s `lib/argon2.js` decides whether to
`require('../dist/argon2.wasm')` (Node path) or `fetch()` it (browser path)
by checking `typeof require === 'function'`. Two problems surfaced only
once Sprint 2 actually wired the crypto module into the app bundle (Sprint
1 never did -- nothing in its UI called it):

1. This project's Vite 8 uses the rolldown-based bundler, which refuses to
   let a CJS `require()` statically resolve to a `.wasm` file compiled as
   an async ESM module (`[REQUIRE_TLA] This require call is not allowed...`)
   -- both in `npm run dev`'s dependency optimizer and in `npm run build`.
2. Separately, Vite's own CJS-interop machinery injects a local `require`
   shim into the bundled module scope for unrelated reasons, which makes
   `typeof require === 'function'` evaluate `true` even in a real browser,
   wrongly taking the Node branch instead of the intended `fetch()` one.

The patch: (a) makes the environment check `isRealBrowser` (checks for
`window`/`document`, not the polluted `typeof require`) so the intended
`fetch()`-based browser path is reliably taken, and (b) obscures the
Node-only `require('../dist/argon2.wasm')` call via indirect eval
(`((0, eval)('require'))(...)`) so rolldown's static analysis doesn't try
to bundle it at all -- it's genuinely unreachable in a browser anyway.
Verified: `npm run dev` + a real Chromium session (register/login/logout)
and `npm run build` (production bundle) both work; `npm test` (Vitest,
real Node, unaffected by this patch's `isRealBrowser` branch) still passes.
If `argon2-browser` is ever upgraded, re-run `npx patch-package
argon2-browser` and diff the new patch against this one.

### A real cross-runtime AES-GCM bug this patch's testing also caught

`crypto/aesGcm.ts` used to always include an `additionalData` key in the
`crypto.subtle.encrypt`/`decrypt` algorithm object, set to `undefined` when
no AAD was passed. Node's `crypto.subtle` (used under Vitest) tolerates
this silently; real browser WebCrypto (Chromium, verified via Playwright)
throws `Failed to execute 'encrypt': additionalData: Not a BufferSource` --
the key must be omitted entirely, not just left `undefined`. Fixed by
conditionally spreading the key in only when AAD is actually provided. This
is exactly why Sprint 2's acceptance criteria required real-browser
Playwright verification, not just Vitest: this bug was invisible to the
existing (correct, passing) unit test suite.

## Secure UX (Sprint 4)

Mirrors Android Sprint 3's scope (secret visibility, clipboard security,
auto-lock), adapted to what a browser can actually do -- **not** a
copy-paste of the Android design, since Web has no Keystore, no
BiometricPrompt, and no `FLAG_SECURE`. See CLAUDE.md's Web Sprint roadmap
for the full sprint writeup; this section covers what a developer touching
this code needs to know.

### Secret visibility + Copy (`VaultItemView.vue`)

Every content block (not just `type === "secret"`) has a Copy button now,
next to secrets' existing Show/Hide toggle -- everything in this vault is
sensitive, same reasoning Android's clipboard wiring uses.

### Clipboard auto-clear -- a real, disclosed browser limitation (`crypto/clipboard.ts`)

**The auto-clear is best-effort, not a guarantee, and this is a genuine
constraint of the browser Clipboard API, not a bug or an oversight:**

- The initial copy (`navigator.clipboard.writeText()`) always runs
  synchronously inside the button's click handler, so it's reliable.
- The scheduled clear runs later, from a `setTimeout`. By then the user may
  have switched tabs/apps. Per the Clipboard API spec, a programmatic
  clipboard write requires the document to still have focus -- if it
  doesn't, the clear call rejects (`NotAllowedError`) and the clipboard is
  silently left holding the copied value. There is no browser API to force
  a clear without focus.
- Unlike Android (which can read the current clipboard back to check it
  wasn't superseded before clearing it), doing the equivalent on Web would
  require the separate, more sensitive `clipboard-read` permission just to
  decide whether to clear -- judged a worse privacy trade than
  unconditionally overwriting, so it isn't done.

This is surfaced honestly in the Settings screen's clipboard section copy,
not silently assumed to work. Verified end-to-end with Playwright: the
clear does fire reliably while the tab stays focused (see the Sprint 4 E2E
run in CLAUDE.md's Current State); losing focus before the timer elapses
was not separately re-tested in an automated way (would require simulating
real OS-level focus loss, which Playwright can't do headlessly) but follows
directly from the Clipboard API spec regardless.

### Web Session Lock (`stores/auth.ts`, `services/idleTimer.ts`, `services/autoLockPolicy.ts`, `views/LockedView.vue`)

`stores/auth.ts` now has a three-state `lockState`:
`'logged_out' | 'locked' | 'unlocked'`. Locking (idle timeout, tab hidden,
or the Settings "Lock now" button) clears only the in-memory VDK -- the
session token, email, and the non-secret `unwrapMaterial`
(kdf_salt/kdf_params/wrapped_vdk, captured at login) are kept, so
`unlockWithPassword()` re-derives and unwraps the exact same VDK **offline,
with no network call**. This mirrors Android's "lock is not logout"
principle (CLAUDE.md "Post-launch fixes batch 2") as closely as the two
platforms' constraints allow.

`services/idleTimer.ts`'s `createInactivityWatcher` reacts to two signals:
foreground mouse/keyboard/touch/scroll inactivity (a plain reset-on-activity
`setTimeout`), and `visibilitychange` (tab hidden/shown) -- `"Immediately"`
locks the instant the tab is hidden; any other timeout records the hide
time and checks elapsed time when the tab becomes visible again (no
`setInterval` kept running while hidden -- background tabs throttle timers
unpredictably in every major browser). The pure lock/no-lock decisions live
in `services/autoLockPolicy.ts`, split out purely for unit testability
(mirrors Android's own `AutoLockPolicy`).

`App.vue` wires this globally and, importantly, **imperatively navigates to
`/locked`** the moment `lockState` flips to `'locked'` -- the router's
`beforeEach` guard alone only redirects on the _next_ navigation attempt,
which would leave a decrypted vault item rendered on screen indefinitely if
the user just stopped touching the page. This was a real bug caught during
this sprint's own Playwright verification (fixed before landing), not a
hypothetical.

Default auto-lock timeout is **5 minutes**, deliberately not "Immediately"
despite that now being Android's own default (see CLAUDE.md's Web Sprint 4
notes for the full reasoning -- Web's `visibilitychange` fires for far more
benign reasons than Android's "app backgrounded").

**Reload/tab-close behavior is unchanged from Sprint 2 and deliberately not
extended this sprint**: the session token and VDK are still Pinia
in-memory state only, never localStorage/sessionStorage, so a full page
reload always logs the user out completely (there is no persisted-locked
state to resume across a reload). The task allowed persisting the session
token to localStorage "remember me"-style while keeping the VDK/password
material out of it; this was deliberately **not** done here, to avoid
expanding Sprint 2's already-disclosed simplification into a new
session-persistence design decision without a dedicated sprint to think
through its own failure modes (stale/expired tokens across reloads, XSS
exposure surface of a persisted bearer token, etc.) -- same "don't
overengineer / don't silently expand scope" principle every prior sprint
followed.

### Settings (`views/SettingsView.vue`, `stores/settings.ts`, `services/settingsStorage.ts`)

Auto-lock timeout (Immediately/1/5/15 min) and clipboard clear delay
(15/30/60s) -- both persisted to localStorage as plain preference ids
(non-secret, same category as `services/device.ts`'s device id), plus
"Lock now" and "Log out" buttons. Deliberately minimal, no
theme/profile/biometric settings (no Web biometric equivalent exists).

## Deployment (Sprint 8)

`web/Dockerfile` is a two-stage build: `node:22-alpine` runs `npm ci` (which
also runs the `argon2-browser` `patch-package` postinstall step, see
"A required upstream patch" above) + `npm run build`, then the static
`dist/` output is copied into `nginxinc/nginx-unprivileged:1.27-alpine`
(non-root by default, no manual UID/permission wrangling needed) serving on
port 8080 internally, with an SPA fallback (`try_files ... /index.html`) for
`vue-router`'s history mode -- see `web/web.nginx.conf`.

`VITE_API_BASE_URL` is a **build-time** arg (`ARG`/`ENV` in the Dockerfile),
not a runtime env var -- Vite inlines `VITE_*` vars into the static bundle
at `npm run build` time, so there is no way to change it after the image is
built without rebuilding. The root `docker-compose.yml`'s `web` service
build args point it at the live public backend
(`https://veilkeeper.quezacolt.my.id/`), same as every real client of this
API (Android, and this Web app whenever it's actually used) -- there is no
separate "LAN-only backend," only a LAN-only *frontend* that still talks to
the one real backend.

Registered as the `web` service in the root `docker-compose.yml`
(`docker compose up -d --build web`), host port **18092** (not 80 --
`beacon_frontend` on the same MACMINI host; not 18091/18080 -- this
project's own API / the Qoder build's API), `restart: unless-stopped`,
light resource limits (0.5 CPU / 64M, measured actual idle usage ~12MiB).
**LAN-only by explicit policy** -- this service must never be added to
`~/.cloudflared/config.yml` or otherwise exposed publicly (unlike the
Android app and backend, which are deliberately public); it is reachable
only as `https://<MACMINI-LAN-IP>:18092` (HTTPS, see below) from devices on
the same local network. See root `README.md` for the equivalent
user-facing summary.

### HTTPS via self-signed certificate (resolves the former secure-context blocker)

The Web Crypto API (`crypto.subtle`, used throughout `src/crypto/hkdf.ts`
and `src/crypto/aesGcm.ts` -- every key-derivation and encrypt/decrypt step
this whole app depends on) is only available in a browser [secure
context](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts)
-- `https:`, or an origin the spec special-cases as "potentially
trustworthy" (`localhost`, `127.0.0.1`, `[::1]`). A plain private-network IP
like `192.168.50.131` served over plain `http://` is **not** a secure
context in any current browser, which used to mean register/login/vault
CRUD only worked when the app was opened from the MACMINI itself.

**Fix**: `web`'s nginx (`web/web.nginx.conf`) now terminates HTTPS directly
on the same internal port (8080, still mapped to host 18092 -- no second
port, no plain-HTTP fallback to redirect from, so there was nothing to gain
from a dual-mode setup) using a self-signed certificate with a `subjectAltName`
matching the host's LAN IP (required -- modern browsers reject a cert whose
only identity is a bare CN with no matching SAN).

**Generate the cert before first bringing the service up** (and again any
time it needs regenerating/rotating -- e.g. the LAN IP changes, or the
10-year validity eventually lapses):

```bash
web/nginx/certs/generate-cert.sh                # defaults to 192.168.50.131
# or, for a different homelab LAN IP:
web/nginx/certs/generate-cert.sh 192.168.1.50
docker compose up -d --build web
```

The script (`openssl req -x509 -newkey rsa:2048 ... -addext
"subjectAltName=IP:<your-ip>"`, 3650-day validity) writes `cert.pem` and
`key.pem` into `web/nginx/certs/` -- **gitignored, never committed** (see
repo root `.gitignore`), mounted read-only into the container via
`docker-compose.yml`'s `web.volumes` (mounted rather than baked into the
image, so regenerating/rotating the cert never requires a rebuild, only
`docker compose up -d web`). A 10-year validity was chosen deliberately:
since every device has to manually trust this cert once anyway, a
short-lived cert with a renewal/re-trust story would add real recurring
friction for no real security benefit in a homelab, single-operator
context.

**Trusting the certificate (once per device)**, the first time you open
`https://<MACMINI-LAN-IP>:18092` (e.g. `https://192.168.50.131:18092`) from
a browser that hasn't seen this cert before, you'll get a warning -- this
is expected, not a sign of a misconfiguration:

- **Chrome / Edge**: click "Advanced" -> "Proceed to `192.168.50.131`
  (unsafe)".
- **Firefox**: click "Advanced..." -> "Accept the Risk and Continue".
- **Safari (macOS/iOS)**: click "Show Details" -> "visit this website" (may
  need to confirm again in a system dialog on iOS) -- or, for a persistent
  trust that survives across sessions/apps, install the cert's public half
  (`cert.pem`) into the device's Keychain/System trust store and mark it
  "Always Trust" for SSL.

Only `cert.pem` (the public certificate) is ever needed on a client device
for manual installation -- never share or transfer `key.pem`.

**Verified with a real headless browser (Playwright/Chromium,
`ignoreHTTPSErrors: true` -- the closest simulation of "a device that has
already manually trusted the cert") against the actual deployed container**
at `https://192.168.50.131:18092`:

```js
await page.evaluate(() => window.isSecureContext)      // -> true  (was false)
await page.evaluate(() => typeof window.crypto.subtle) // -> "object" (was "undefined")
```

A full register -> login -> create category -> create vault item flow was
run end-to-end against this HTTPS LAN origin and completed successfully
with **zero console errors** -- proving this isn't just "the padlock shows
up," but that the actual blocker (crypto.subtle being unavailable) is
resolved and the app's core purpose works from a non-host device's
perspective (the LAN origin, not `localhost`).

This is a browser-platform constraint that required a real infrastructure
decision (self-signed cert distribution/trust, no rotation automation) --
consistent with how this project's Sprint 1 CORS blocker was resolved once
a decision was made, this was previously left unresolved and reported
rather than decided unilaterally; it's been implemented now per an explicit
decision to accept the self-signed-cert/manual-trust trade-off.
