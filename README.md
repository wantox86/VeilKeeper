# VeilKeeper

Secure personal vault (secure-notebook style, not a traditional form-oriented password
manager). Client-side (zero-knowledge) encrypted vault items, Go backend, MySQL, Android app.

> This repo is an independent Claude Code implementation of the product spec in
> [`SPEC-BASE.md`](./SPEC-BASE.md), built for comparison against a parallel Qoder+Kimi K3
> implementation of the same spec ("Veil Keepers"). See [`CLAUDE.md`](./CLAUDE.md) for
> resolved design decisions and current sprint status.

**Status:** All 8 planned Android sprints (0-7) complete — full auth + vault CRUD + categories +
attachments + secure UX (auto-lock/biometric/clipboard/screenshot protection) + search + UI
polish + homelab deployment hardening. A separate 8-sprint Web client (`web/`, Vue 3) is also
fully built and deployed (see "Web app" below) — its final deployment sprint's disclosed
blocker (LAN access needed HTTPS for the app's crypto to work from any device other than the
host itself) is now **resolved** via a self-signed TLS certificate. See
[`CLAUDE.md`](./CLAUDE.md#current-state) for the full history and final project summary.

## Repository structure

```text
backend/    Go API (auth, vault CRUD, categories, attachments)
android/    Kotlin + Jetpack Compose + Material 3 app
web/        Vue 3 + TypeScript web client (LAN-only deployment, see below)
infra/      MySQL init scripts mounted into the mysql container
data/       Local bind-mount for encrypted attachment storage (gitignored contents)
docs/       Architecture / security / API docs (grows with later sprints)
```

## Getting started (fresh clone to running stack)

Requires **Docker + Docker Compose** only. No local Go or MySQL install needed to run the
backend — Android development additionally needs the normal Android toolchain (see below).

```bash
git clone <this-repo-url> veilkeeper && cd veilkeeper
cp .env.example .env
# Edit .env: defaults work for local dev, but for anything reachable outside your own
# machine (even just your homelab LAN) change SERVER_PEPPER/DB_PASSWORD/DB_ROOT_PASSWORD
# to real random values — see the comments in .env.example for what each one protects.
docker compose up -d
curl http://localhost:18091/health   # {"status":"ok"}          -- pure liveness
curl http://localhost:18091/ready    # {"status":"ready"}       -- once MySQL is reachable
```

That's it — `api` and `mysql` are the only two services (SPEC-BASE.md Section 33), both with
healthchecks, `restart: unless-stopped`, and resource limits appropriate for a homelab host
(see "Resource footprint" below). To stop without losing data: `docker compose stop` /
`docker compose start`. To tear down containers but keep the volume (data survives):
`docker compose down`. To wipe everything including vault data: `docker compose down -v`
(only do this if you mean it — see Backup below first).

The API listens on host port **18091** (not 18080 — that's used by an unrelated project on
the same shared Docker host; see `CLAUDE.md` for why). If you're deploying somewhere without
that constraint, feel free to remap it in `docker-compose.yml`.

## Resource footprint

Measured with `docker stats` after a fresh `docker compose up -d` plus light exercise
(register/login/prelogin calls), on a MACMINI-class host (see `CLAUDE.md`):

| Container | RAM used | RAM limit | CPU (idle) |
|---|---|---|---|
| `veilkeeper-api` | ~41 MiB | 256 MiB | ~0% |
| `veilkeeper-mysql` | ~191 MiB | 512 MiB | ~0.4% |

MySQL's default `performance_schema` instrumentation (aimed at production DB observability,
not a single-user homelab vault) accounted for roughly half of MySQL's idle memory by itself
(~440 MiB → ~190 MiB after disabling it) — see `docker-compose.yml`'s `command:` block on the
`mysql` service for the exact tuning (`--performance-schema=OFF`,
`--innodb-buffer-pool-size=96M`) and the reasoning comment next to it. Both containers also
carry explicit `deploy.resources.limits`/`reservations` so a runaway query or request storm
can't starve the many other unrelated containers sharing this Docker host.

## Backup & restore

**What gets backed up is two things, both of which must be captured together for a
consistent restore:**

1. The MySQL database (`veilkeeper` by default) — schema + all rows, including
   `encrypted_payload`/`wrapped_vdk`/attachment metadata.
2. The `data/attachments/` directory — the actual encrypted attachment file bytes on disk
   (referenced by path from the `attachments` table's `storage_path` column; the DB backup
   alone is not enough, it just points at files that must also exist).

**Important — treat backups as sensitive, even though the contents are encrypted.**
SPEC-BASE.md Section 48 and this project's zero-knowledge design mean the server (and thus
any backup taken from it) never has the plaintext password, `MasterKey`, `WrapKey`, or
`VaultDataKey` — only `AuthKey`'s hash and an opaque `wrapped_vdk`/`encrypted_payload`/
attachment ciphertext. **That said, a backup is still a full copy of someone's vault
metadata and ciphertext** (email addresses, item/category counts and timestamps, encrypted
blobs an attacker could brute-force offline at their leisure if the underlying password is
weak). Store backups locally on trusted storage only (e.g. HPMINI's file storage, not a
public cloud bucket or anything world-readable), and apply the same "don't commit/upload
this anywhere" discipline as for `.env`.

### Manual backup

```bash
# 1. Dump the database (run from the repo root; needs the stack running)
set -a; source .env; set +a
docker exec veilkeeper-mysql sh -c \
  "mysqldump -uroot -p'$DB_ROOT_PASSWORD' --single-transaction --routines --triggers $DB_NAME" \
  > veilkeeper_db_$(date +%Y%m%d_%H%M%S).sql

# 2. Archive the attachments directory
tar -czf veilkeeper_attachments_$(date +%Y%m%d_%H%M%S).tar.gz -C data attachments

# Move both files somewhere safe (outside the repo/git working tree) and delete the
# repo-root copies once confirmed moved -- they're gitignored but no reason to leave
# plaintext-adjacent ciphertext dumps sitting around either.
```

`--single-transaction` takes a consistent InnoDB snapshot without locking tables, so this is
safe to run against a live stack (no need to stop the API first).

### Scheduling it (optional)

This project deliberately does **not** ship an automated backup container/cron job — for a
single-user homelab app, one more always-running process is exactly the kind of moving part
Section 60 says to avoid unless it earns its keep. The two-line script above is simple enough
to run by hand periodically, or wire into whatever job scheduler your homelab already has
(e.g. a `cron` entry on the Docker host calling a small wrapper script that runs the two
commands above and prunes old backups) — deliberately left as an environment-specific choice
rather than baked into this repo.

### Restore

```bash
# 1. Make sure the stack (at least mysql) is up, then load the dump into a running DB:
set -a; source .env; set +a
cat veilkeeper_db_YYYYMMDD_HHMMSS.sql | docker exec -i veilkeeper-mysql mysql -uroot -p"$DB_ROOT_PASSWORD" "$DB_NAME"

# 2. Restore the attachments directory (stack can be running or stopped for this step)
tar -xzf veilkeeper_attachments_YYYYMMDD_HHMMSS.tar.gz -C data
```

Verified during Sprint 7: dumped a test user's row, restored it into a scratch database, and
confirmed the restored row matched byte-for-byte (`id`/`email`/etc.) — see `CLAUDE.md`'s
Sprint 7 entry for the exact verification steps performed.

### Backend development (without Docker)

```bash
cd backend
go build ./...
go test ./...
gofmt -l .
go vet ./...
```

## Web app

`web/` (Vue 3 + TypeScript + Vite) is a static single-page app, built into a
Docker image (`web/Dockerfile`) and run as the `web` service in this same
`docker-compose.yml` (`docker compose up -d --build web`). **Deliberately
LAN-only** -- unlike the Android app and backend above, it is never
registered with this host's `cloudflared` tunnel or exposed publicly.

**HTTPS, self-signed cert.** Browsers only expose the Web Crypto API
(`crypto.subtle`, which this app's whole encryption path depends on) in a
*secure context* (`https:`, or `localhost`/`127.0.0.1`) -- a plain-HTTP LAN
IP does not qualify, which used to mean register/login/vault CRUD only
worked from the MACMINI itself. This is now resolved: `web`'s nginx
terminates TLS with a self-signed certificate (SAN = this host's LAN IP),
generated locally (never committed -- see `.gitignore`) and mounted into
the container. **Generate the cert before first bringing the service up**
(and again any time you regenerate/rotate it):

```bash
web/nginx/certs/generate-cert.sh          # defaults to 192.168.50.131
# or: web/nginx/certs/generate-cert.sh <your-LAN-IP>
docker compose up -d --build web
curl -k https://localhost:18092/          # served by nginx, host port 18092, HTTPS only
```

Reachable from any device on the same local network at
`https://<MACMINI-LAN-IP>:18092` (e.g. `https://192.168.50.131:18092`).
Because the certificate is self-signed (no public CA can issue one for a
private LAN IP), **every device must manually trust it once** -- the
browser will show a warning first. See `web/README.md`'s "Deployment
(Sprint 8)" section for step-by-step accept instructions per browser
(Chrome, Safari, Firefox), and for the full verification writeup (Playwright
confirmed `window.isSecureContext === true` and a full
register/login/create-category/create-item flow succeeding over this HTTPS
LAN origin with zero console errors).

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
