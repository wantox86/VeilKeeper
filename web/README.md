# VeilKeeper Web

Web client for VeilKeeper (Vue 3 + TypeScript + Vite). See the repo root
[`CLAUDE.md`](../CLAUDE.md) for the full product context, resolved design
decisions, and the Web sprint roadmap.

**Sprint 2 status: authentication.** Login/Register/Dashboard (protected
route) + logout are implemented and wired to the real backend auth API and
the crypto module. Vault CRUD UI is still not implemented -- that's Sprint
3+.

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
  views/        Route-level pages: HealthCheckView, LoginView, RegisterView, DashboardView
  layouts/      Page shells/layouts for later sprints (empty so far)
  stores/       Pinia stores: health.ts, auth.ts (session/email/VDK, in-memory only)
  services/     API client + backend service calls: api.ts, health.ts, authApi.ts, device.ts
  crypto/       Client-side crypto: HKDF, AES-GCM, Argon2id, key hierarchy, base64
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
