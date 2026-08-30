# VeilKeeper

Secure personal vault (secure-notebook style, not a traditional form-oriented password
manager). Client-side (zero-knowledge) encrypted vault items, Go backend, MySQL, Android app.

> This repo is an independent Claude Code implementation of the product spec in
> [`SPEC-BASE.md`](./SPEC-BASE.md), built for comparison against a parallel Qoder+Kimi K3
> implementation of the same spec ("Veil Keepers"). See [`CLAUDE.md`](./CLAUDE.md) for
> resolved design decisions and current sprint status.

**Status:** Sprint 1 (Authentication) complete — register/login/logout with client-side
Argon2id/HKDF/AES-256-GCM crypto on Android and Argon2id-hashed sessions on the backend. No
vault/attachment features yet — see [`CLAUDE.md`](./CLAUDE.md#current-state).

## Repository structure

```text
backend/    Go API skeleton (health/readiness endpoints only, for now)
android/    Kotlin + Jetpack Compose + Material 3 app skeleton
infra/      MySQL init scripts mounted into the mysql container
data/       Local bind-mount for encrypted attachment storage (gitignored contents)
docs/       Architecture / security / API docs (grows with later sprints)
```

## Running the backend locally

Requires Docker + Docker Compose. No local Go or MySQL install needed.

```bash
cp .env.example .env   # edit values if you want, defaults are fine for local dev
docker compose up -d
curl http://localhost:18091/health   # {"status":"ok"}
curl http://localhost:18091/ready    # {"status":"ready"} once MySQL is reachable
docker compose down -v               # stop and remove the stack + volumes
```

The API listens on host port **18091** (not 18080 — that's used by an unrelated project on
the same shared Docker host; see `CLAUDE.md` for why).

### Backend development (without Docker)

```bash
cd backend
go build ./...
go test ./...
gofmt -l .
go vet ./...
```

## Android app

Requires the normal Android development environment (Android Studio or the SDK + JDK 17).

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The app has working Login/Register screens wired to real client-side crypto (Argon2id via
Argon2Kt, HKDF, AES-256-GCM) and the backend auth API. See `CLAUDE.md`'s Current State entry for
Sprint 1 for a disclosed limitation: the real Argon2id path can only be exercised by an
instrumented test on a device/emulator, not by `testDebugUnitTest`/CI.

## Configuration

All configuration is via environment variables (see [`.env.example`](./.env.example)). Never
commit a real `.env` file — secrets belong there and nowhere else.

## Security design decisions

Crypto/key-architecture decisions that resolve ambiguities left open in `SPEC-BASE.md` are
documented in [`CLAUDE.md`](./CLAUDE.md#resolved-design-decisions-read-before-implementing-anything-crypto-related).
Read that before touching anything auth/vault/crypto related.

## CI

- `.github/workflows/backend.yml` — Go format check, vet, unit tests, build, Docker image build.
- `.github/workflows/android.yml` — Gradle assemble, unit tests, lint.
- `.github/workflows/security.yml` — secret scanning (gitleaks) + Go vulnerability check
  (govulncheck).
