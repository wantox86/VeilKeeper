# Plan: Revamp UI VeilKeeper (legacy) + fitur Devices & Sessions

## Goal

User nunjukin screenshot (`revamp VeilKeeper.jpg`, di-drop ke `vilo-room`
HPMINI) yang isinya tampilan app **VeilKeepers** (final/plural gen, repo
`~/Documents/Github/VeilKeepers`) — dashboard "the vault behind the veil",
palet violet+amber, kartu kategori, search bar, empty state, plus menu
**"Devices & sessions"** yang nampilin device/session aktif.

Goal-nya: **bawa tampilan & fitur itu ke VeilKeeper (legacy, singular gen-1,
repo `~/Documents/Github/VeilKeeper`)** — bukan bikin ulang di VeilKeepers
(itu udah ada semua di situ). Dua app ini beda brand/identitas (jangan
sampai legacy ikut nyebut dirinya "VEIL KEEPERS" — itu nama app LAIN),
yang di-port cuma **bahasa visual & fitur**, bukan nama brand.

## Temuan penting (kenapa scope-nya lebih kecil dari perkiraan awal)

Awalnya dikira backend legacy nggak punya konsep device/session sama
sekali. Setelah dicek lebih dalam, ternyata **fondasinya udah ada**,
cuma nggak lengkap:

- **Schema DB udah ADA** (`infra/mysql/init/002-auth-schema.sql`): tabel
  `devices` dan `sessions` PERSIS sama strukturnya kayak VeilKeepers
  (`device_identifier`, `device_name`, `last_seen_at`, `revoked_at`, FK ke
  `sessions.device_id`). Ini bukan migration baru yang perlu dibuat — tabel
  ini kemungkinan besar udah ada di database production
  (`veilkeeper` @ `consolidated-mysql`) karena login udah jalan normal
  sekarang (login butuh insert ke `devices`+`sessions`).
- **Login request udah nerima `device_identifier`/`device_name` dari client**
  (`loginRequest` di `auth_handlers.go`) dan **`Store.UpsertDevice()` udah
  dipanggil** tiap login (`mysql.go:80`). Jadi device SUDAH tercatat di DB
  tiap kali user login — cuma belum ada cara buat MELIHAT/REVOKE dari sisi
  app.
- **Session struct udah punya `DeviceID`** (`store.go:53`), tapi
  `session_middleware.go` cuma expose `userID` ke context request, BELUM
  expose `deviceID` — perlu ditambah biar API tau device MANA yang lagi
  request (buat nandain "device saat ini" di list).

**Yang bener-bener belum ada** (gap sebenarnya):
1. `Store.ListDevices(ctx, userID)` — belum ada method-nya (bandingkan
   VeilKeepers `store.go:266`).
2. `Store.RevokeDeviceAndSessions(ctx, userID, deviceID)` — belum ada
   (VeilKeepers `store.go:308`, revoke device + cascade revoke semua
   session device itu).
3. HTTP handler + route `GET /api/v1/devices` dan
   `DELETE /api/v1/devices/{id}` — belum terdaftar di `server.go` legacy
   sama sekali (VeilKeepers punya di `devices.go`).
4. `deviceID` belum di-propagate ke request context di
   `session_middleware.go` legacy.
5. Android: nggak ada `DevicesScreen`/state/API call — semua itu cuma ada
   di app VeilKeepers (`ui/DevicesScreen.kt`, `auth/AccountViewModel.kt`,
   `data/AuthApi.kt`), belum di-port ke legacy (`id.quezacolt.veilkeeper`).
6. Android: tema warna (`Midnight Vault`, indigo-on-near-black) dan layout
   dashboard (`HomeScreen.kt`) legacy beda total dari `VaultHomeScreen.kt`
   VeilKeepers yang ada di screenshot.

## Referensi source-of-truth (baca sebelum eksekusi tiap phase)

- Backend VeilKeepers (contoh yang mau diikutin polanya):
  `~/Documents/Github/VeilKeepers/backend/internal/store/store.go`
  (§`Device`, `ListDevices`, `RevokeDeviceAndSessions`),
  `~/Documents/Github/VeilKeepers/backend/internal/server/devices.go`
  (handler HTTP), `~/Documents/Github/VeilKeepers/backend/internal/auth/middleware.go`
  (§`DeviceID(ctx)`).
- Backend VeilKeeper (legacy, yang mau diedit):
  `~/Documents/Github/VeilKeeper/backend/internal/store/store.go` (interface
  `AuthStore`), `~/Documents/Github/VeilKeeper/backend/internal/store/mysql.go`
  (implementasi), `~/Documents/Github/VeilKeeper/backend/internal/httpserver/server.go`
  (route registration), `~/Documents/Github/VeilKeeper/backend/internal/httpserver/session_middleware.go`.
- Android VeilKeepers (contoh UI/tema yang mau di-port):
  `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt`,
  `ui/theme/Shape.kt`, `ui/VaultHomeScreen.kt`, `ui/DevicesScreen.kt`,
  `auth/AccountViewModel.kt`, `data/AuthApi.kt`.
- Android VeilKeeper legacy (yang mau diedit):
  package `id.quezacolt.veilkeeper`, `ui/theme/*.kt`, `ui/home/HomeScreen.kt`,
  `ui/home/HomeViewModel.kt`, `ui/settings/SettingsScreen.kt`,
  `data/AuthApi.kt`, `data/AuthRepository.kt`, `data/DeviceIdentity.kt`.
- **WAJIB dibaca dulu sebelum nyentuh backend legacy**: `CLAUDE.md` dan
  `SPEC-BASE.md` di root repo `VeilKeeper` — project ini punya aturan
  eksplisit (misal "no migration tool", "no unnecessary dependencies",
  Section 27/28/30/31/56 dst yang disebut di komentar kode). Ikutin gaya &
  batasan yang udah ditetapkan di situ, jangan introduce pattern baru yang
  bertentangan.
- Screenshot referensi visual: `/tmp/vilo-review/revamp VeilKeeper.jpg`
  (udah di-copy dari `vilo-room` HPMINI ke lokal MACMINI).

## Deployment context

- Backend legacy jalan sebagai container `veilkeeper-api`
  (compose project `veilkeeper`, folder `~/Documents/Github/VeilKeeper`),
  DB `veilkeeper` di `consolidated-mysql`, expose lewat tunnel
  `veilkeeper.quezacolt.my.id` (port lokal `18091`).
- Android app: perlu di-build APK baru (debug dulu buat testing, baru
  release kalau semua oke) — cek `android/app/build.gradle.kts` buat
  signing config & versionCode/versionName yang perlu di-bump.

## Phases

### Phase 0 — Backend: store layer + context propagation
- Tambah struct `Device` di `store.go` legacy (kalau belum ada bentuk yang
  cocok) + method `ListDevices(ctx, userID) ([]Device, error)` dan
  `RevokeDeviceAndSessions(ctx, userID, deviceID) error` di interface
  `AuthStore` + implementasi `MySQLStore` (`mysql.go`) — port logic dari
  VeilKeepers, adaptasi ke gaya kode legacy (lihat `UpsertDevice`/
  `CreateSession` yang udah ada sebagai referensi gaya).
- Tambah `deviceID` ke context di `session_middleware.go` (persis pola
  `userIDContextKey`, tambah `deviceIDContextKey` + getter
  `deviceIDFromContext`).
- Test: cek ada test store existing (`fake_store_test.go` — ini fake/mock,
  bukan integration test ke DB beneran) buat pola unit test di repo ini.
  Tambah unit test buat `ListDevices`/`RevokeDeviceAndSessions` pakai pola
  yang sama. Kalau mau verifikasi ke DB production beneran
  (`veilkeeper` @ `consolidated-mysql`), WAJIB pakai user/device dummy
  (BUKAN akun user asli), dan cleanup abis test — sama disiplin kayak
  waktu kerjain database layer translateidbot-go.
- **Exit criteria**: `go build`/`go test` sukses, `ListDevices` &
  `RevokeDeviceAndSessions` kebukti jalan bener (baik via fake store test
  maupun spot-check manual ke DB beneran pakai data dummy).

### Phase 1 — Backend: HTTP endpoints + deploy
- Buat handler `GET /api/v1/devices` (list device milik user, tandain mana
  yang `device_id` dari context = device request ini) dan
  `DELETE /api/v1/devices/{id}` (revoke, tolak kalau device itu bukan
  milik user yang login — ownership check server-side, bukan cuma client).
  Port pola response JSON dari `devices.go` VeilKeepers.
- Register kedua route itu di `server.go` legacy (guard pakai
  `requireSession` yang udah ada, sama kayak route vault/category lain).
- Rebuild image `veilkeeper-api`, redeploy (`docker compose up -d --build`
  atau sesuai pola build yang dipakai project ini — cek `docker-compose.yml`
  & `README.md`/`CLAUDE.md` buat command yang benar), verifikasi container
  `healthy`, test endpoint manual (`curl` pakai token session dummy/test
  akun, BUKAN akun production asli kalau bisa dihindari — kalau harus pakai
  akun asli buat test end-to-end, koordinasi dulu, jangan revoke device
  aktif beneran tanpa sadar).
- **Ini perubahan ADDITIVE** (nambah endpoint baru, nggak ubah endpoint
  lama) — resiko regresi ke fitur existing (login/vault/category) rendah,
  tapi tetap verifikasi endpoint lama masih jalan normal abis redeploy.
- **Exit criteria**: `curl` ke kedua endpoint baru balikin response yang
  bener, endpoint lama (login, vault items, categories) tetap jalan normal
  pasca redeploy.

### Phase 2 — Android: port tema visual
- Adaptasi `ui/theme/Color.kt` legacy ke palet "behind the veil"
  (violet-hitam + amber candlelight) dari VeilKeepers — ambil value warna
  yang sama/setara, TAPI nama variabel/komentar tetap disesuaikan brand
  VeilKeeper (jangan copy-paste komentar yang nyebut "VeilKeepers").
  Sesuaikan juga `Type.kt`/`Shape.kt`/`Theme.kt` kalau ada penyesuaian
  shape kartu (rounded corner, border) yang beda dari sekarang.
- **Exit criteria**: app legacy bisa di-build (`./gradlew assembleDebug`),
  compile preview/screenshot test (kalau ada) nunjukin warna baru
  ke-apply, TANPA rename package/brand "VeilKeeper" jadi "VeilKeepers".

### Phase 3 — Android: port layout dashboard
- Adaptasi `ui/home/HomeScreen.kt` (+ `HomeViewModel.kt` kalau perlu state
  baru) supaya struktur visual match screenshot: search bar + subtitle
  "Local only..." (sesuaikan copy VeilKeeper), section "CATEGORIES" dengan
  kartu accent-bar per kategori (jumlah item), tombol "New category",
  section "RECENT", empty state ala "Nothing behind the veil yet...",
  FAB "+" pojok kanan bawah, top bar (judul + tagline, ikon settings +
  lock).
- Perhatiin: state/data shape `HomeViewModel.kt` legacy mungkin beda dari
  `VaultHomeScreen.kt`'s ViewModel (`loaded`/`seedWarning`/dst) — JANGAN
  asal copy paste, adaptasi ke struktur data yang emang ada di legacy,
  tambahin field baru di ViewModel kalau state yang dibutuhin belum ada.
- **Exit criteria**: build sukses, jalanin di emulator/device, visual
  dashboard match screenshot referensi (kartu kategori, search bar, empty
  state, FAB, top bar).

### Phase 4 — Android: screen Devices & Sessions
- Port `ui/DevicesScreen.kt` VeilKeepers ke `ui/settings/DevicesScreen.kt`
  (atau folder baru `ui/devices/`) legacy — sesuaikan nama package/import.
- Port logic state (`DevicesState`, `DeviceEntry` dari VeilKeepers'
  `auth/AccountViewModel.kt`) ke ViewModel legacy yang sesuai (kemungkinan
  perlu bikin baru, atau extend `SettingsViewModel.kt` kalau itu lebih pas).
- Tambah API call di `data/AuthApi.kt` legacy: `GET /api/v1/devices`,
  `DELETE /api/v1/devices/{id}` (port dari `AuthApi.kt` VeilKeepers).
- Wire menu "Devices & sessions" ke `ui/settings/SettingsScreen.kt` legacy
  (item baru di menu settings, sama posisi/pola kayak VeilKeepers nempelin
  di dialog "Vault settings").
- **Exit criteria**: dari Settings, bisa buka screen Devices & Sessions,
  nampilin device yang match sama data dari Phase 1 endpoint, device
  saat ini ke-tandain, revoke device lain kebukti jalan (device yang
  di-revoke otomatis logout di device itu pas request berikutnya).

### Phase 5 — Build, verifikasi end-to-end, serah-terima APK
- Build APK debug lengkap (semua phase di atas udah nyatu), install &
  test manual end-to-end: login → lihat dashboard baru → buka Devices &
  Sessions → login dari device/emulator lain → cek device kedua muncul →
  revoke salah satu → device yang di-revoke ke-logout.
- Kalau semua lolos, build APK release (perlu signing config — cek dulu
  `build.gradle.kts` & keystore yang dipakai, JANGAN generate keystore
  baru kalau udah ada yang existing, itu bakal bikin user nggak bisa
  update app yang udah keinstall).
- Serahkan APK ke user (`SendUserFile` atau taruh di lokasi yang biasa
  dipakai buat serah-terima APK, sesuai catatan kerja HPMINI kalau ada).
- **Exit criteria**: APK ke-generate, user konfirmasi tampilan & fitur
  Devices & Sessions jalan sesuai ekspektasi di device fisik.

## Catatan risiko

- Backend Phase 0-1 nyentuh database production (`veilkeeper` @
  `consolidated-mysql`) dan container yang lagi dipakai app production
  (`veilkeeper-api`) — WAJIB backup/dump dulu sebelum redeploy, dan test
  pakai data dummy dulu sebelum yakin aman ke akun asli.
- Perubahan tema (Phase 2) murni kosmetik/CSS-level, resiko rendah.
- Perubahan layout dashboard (Phase 3) nyentuh komponen yang paling sering
  dipakai user — worth extra hati-hati di testing manual sebelum ship.
- JANGAN campur adukkan istilah/brand "VeilKeeper" vs "VeilKeepers" di
  commit message, UI string, atau komentar kode — ini 2 app/brand
  terpisah yang emang disengaja beda (lihat catatan kerja: legacy gen-1
  vs final/production gen).
