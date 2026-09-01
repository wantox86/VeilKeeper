# VeilKeeper Web

Web client for VeilKeeper (Vue 3 + TypeScript + Vite). See the repo root
[`CLAUDE.md`](../CLAUDE.md) for the full product context, resolved design
decisions, and the Web sprint roadmap.

**Sprint 1 status: scaffold + crypto foundation only.** There is no
Login/Register/Vault UI yet -- just a health-check page that confirms the
app can reach the backend, and a fully tested `src/crypto/` module. Those
land in Sprint 2+.

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
  components/   Shared, reusable Vue components (empty in Sprint 1)
  views/        Route-level pages (HealthCheckView.vue is the only one so far)
  layouts/      Page shells/layouts for later sprints (empty in Sprint 1)
  stores/       Pinia stores for shared state (health.ts)
  services/     API client + backend service calls (api.ts, health.ts)
  crypto/       Client-side crypto: HKDF, AES-GCM, Argon2id, key hierarchy
  router/       Vue Router setup
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
and display the result. This is the only functional page in Sprint 1.
