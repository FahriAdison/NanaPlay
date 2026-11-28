# 🔍 Laporan Code Review Menyeluruh — NanaPlay (main @ ba83319)

Dilakukan oleh 6 agen paralel: Streaming, Musik, UI/Input, CI/CD, Keamanan, Kualitas/Test.
Tanggal: 27 Nov 2026

---

## Ringkasan Angka

| Area | Tinggi | Sedang | Rendah | Status fix lama |
|------|--------|--------|--------|-----------------|
| Streaming WebRTC | 1 | 4 | 5 | ✅ Sebagian besar fix lama SUDAH diterapkan (F-05, F-06, generation guard, signaling hop) |
| Subsystem Musik | 2 | 5 | 2 | (area baru, review pertama kali) |
| UI & Input | 1 | 3 | 6 | ✅ Banyak fix lama terverifikasi (stuck-button, releaseAll, R3, pointer capture) |
| CI/CD & Build | 3 | 4 | 3 | ❌ Semua temuan lama MASIH TERBUKA (pin SHA, checksum GStreamer, CI Android) |
| Keamanan | 0 | 2 | 5 | 🟡 Campuran: proxy plaintext → DIPERBAIKI; fallback plaintext & http proxy → MASIH ADA |
| Kualitas/Test | 3 | 3 | 3 | — (CI test gagal: penyebab DITEMUKAN, sudah diperbaiki commit bc4f973) |

Total: 10 Tinggi, 21 Sedang, 24 Rendah ≈ 55 temuan.

---

## 🔴 PRIORITAS TINGGI (10)

### Streaming
1. **First-frame watchdog mati oleh false-positive** — `updateTransportRecoveryProgress` (Streaming.kt:6424) memanggil `markRendered()` hanya karena bytesReceived meningkat, padahal skenario "paket datang tapi frame tidak pernah render" jadi tak pernah dipulihkan. → Panggil markRendered hanya dari onFirstFrameRendered.

### Musik
2. **Race clearQueue vs playInternal** — clearQueue tidak ambil playMutex; play in-flight bisa "membangkitkan" track ghost setelah queue dihapus (NanaMusicPlayer.kt:1104). → Serialisasi dengan mutex + re-check generation.
3. **URL http:// JioSaavn gagal pasti** — resolveAudioUrl menerima http, tapi cleartext diblokir network config → error playback + retry sia-sia (NanaSaavnApi.kt:126). → Paksa https:// atau upgrade otomatis.

### UI/Input
4. **Potensi double-dispatch touch** — jalur forward-before-views + fallback di MainActivity.dispatchTouchEvent bisa mengirim event yang sama 2x ke host (perlu verifikasi semantik shouldCaptureTouchBeforeViews).

### CI/CD (semua masih terbuka dari review lama)
5. **Actions tidak di-pin SHA** — release.yml pakai contents:write + PAT; kompromi action eksternal = eksekusi kode. → Pin ke commit SHA.
6. **GStreamer PKG/MSI tanpa verifikasi checksum** — diunduh dari freedesktop.org dan dibundle ke runtime. → Hardcode sha256 per versi.
7. **Android masih tanpa CI** — build/test APK 100% bergantung repo eksternal nanaplay-builder. → Tambah job gradle di auto-build.yml untuk PR android/**.

### Kualitas
8. **Penyebab CI test gagal teridentifikasi** — type mismatch `appliedSettings` (AppSettings? vs StreamSettings) di OpenNowScreens.kt:9129. **Sudah diperbaiki** oleh commit bc4f973. Perlu 1x verifikasi compile di CI.
9. **God-files** — OpenNowScreens.kt 16.535 baris (197 @Composable!), Streaming.kt 8.549, OpenNowViewModel.kt 4.872, GfnApi.kt 3.260. → Pecah per domain (<1000 baris/file).
10. **Coverage test kosong di area kritis** — 564 test JVM tapi 0 Robolectric/Compose test, tidak ada test ViewModel/MusicPlayer; regression CI terakhir justru di area tanpa test. → Tambah Robolectric + Compose test + coverage plugin (kover).

---

## 🟡 PRIORITAS SEDANG (21) — ringkas per area

**Streaming (4):**
- NetworkConditionAdaptor = dead code (isNetworkDegraded tak pernah dikonsumsi; "adaptasi dinamis" hanyalah ilusi — tuning statis di SDP)
- Race use-after-dispose sendInput (cek state lalu send asinkron; dispose bisa menyela) → generation guard di coroutine
- DataChannel kedua berlabel sama menimpa referensi tanpa unregister/dispose → leak native
- Kandidat ICE sebelum PeerConnection siap dibuang permanen → buffer & replay di onSetSuccess

**Musik (5):**
- Retry path tidak cek `player === retryExo` → bisa menyentuh ExoPlayer yang sudah di-release
- Fallback JioSaavn 3 host × 35s = buffering hingga ~105s → bungkus withTimeoutOrNull(~20s)
- saveState hanya saat pause/seek → posisi hilang sampai menitan kalau proses mati → update posisi tiap ~5s di progressTicker
- AUDIO_BECOMING_NOISY ditangani dua kali (ExoPlayer + receiver manual service) → pilih satu
- Tombol next/prev selalu "tersedia" di notifikasi walau queue kosong → cermin kondisi queue

**UI (3):**
- Race decay executor keymap: UP bisa mendarat di sesi/stream client lama → generation guard
- releaseMouseButtons() publik tanpa @Synchronized → bisa clear diluar lock
- Key list pakai index posisi → shuffle/hapus memicu full rebind → pakai key identitas stabil

**CI (4):**
- versionCode/versionName Android hardcoded manual → sinkronkan via sync-release-version.mjs
- finalize job hanya edit package.json tanpa lock → drift lockfile
- gradle-wrapper tanpa distributionSha256Sum
- Desktop build tanpa codesign/notarization/attestation

**Keamanan (2):**
- Fallback plaintext token saat Keystore gagal (Persistence.kt:495) → fail-closed, minta re-login
- Proxy http:// masih diterima dengan kredensial Basic cleartext (GfnApi.kt:563) → peringatkan/tolak

**Kualitas (3):**
- Duplikasi helper test (session() 3x, readySession() 3x) → TestFixtures.kt
- Tidak ada coverage plugin (kover/jacoco) + coroutines-test
- runBlocking alih-alih runTest di beberapa test → rawan flake

---

## 🟢 RENDAH (24) — highlight terpenting
- FPS palsu: gameFps diacak (-1..0).random() saat stats channel tidak aktif → tampilkan null, jangan angka bohong (Streaming.kt:6274)
- Regex SdpTools.fixServerEndpoint menuntut trailing whitespace → kandidat di akhir SDP lolos tanpa fix
- RemoteIceLog trim non-atomik → torn read / log hilang saat crash
- preferCodec memproses semua m=video section sekaligus → rapuh untuk multi-section
- progress ticker Compose tanpa derivedStateOf → recomposition berlebihan di musik UI
- collectAsState → collectAsStateWithLifecycle di semua flow musik
- postDelayed 500ms di onConfigurationChanged tidak dibatalkan → leak singkat activity mati
- Polling enforcer system UI 500ms → ganti insets listener (hemat battery saat streaming)
- Kursor jump setelah ACTION_POINTER_UP (lastX/lastY basi)
- Pointer capture bisa hilang permanen jika Alt ditahan saat kehilangan fokus
- Kredensial proxy sebaiknya pindah ke EncryptedSharedPreferences
- SafeBrowsing WebView belum diaktifkan eksplisit
- JioSaavn: paksa https + allowlist host CDN
- dst. (detail di transkrip agen)

---

## ✅ Yang Sudah BAGUS (patut dipertahankan)
- Tidak ada hardcoded secrets/API keys baru
- Self-update APK kini dua-lapis: SHA-256 wajib + verifikasi sertifikat signer identik, dinonaktifkan di build Play
- Kredensial proxy pindah dari settings eksternal ke internal MODE_PRIVATE
- WebView: file://, content://, intent:// diblokir; tidak ada addJavascriptInterface
- Crash reporter meredaksi secret
- Musik: playMutex, playbackGeneration, crash-net guard sudah matang
- Fix input lama (stuck button, R3, recenter Alt, pointer capture) terverifikasi diterapkan
- EncryptedSharedPreferences untuk token auth + network config ketat

---

## 💡 Usulan Urutan Eksekusi

**Batch 1 — stabilisasi (cepat, dampak besar):**
1. Verifikasi 1x CI hijau pasca bc4f973 (re-run test.yml)
2. Fix http:// JioSaavn → https (1 baris-ish)
3. Race clearQueue vs play (tambah mutex + generation check)
4. First-frame watchdog: markRendered hanya dari onFirstFrameRendered
5. Generation guard di sendInput + decay keymap

**Batch 2 — keamanan CI (sekali jalan):**
6. Pin actions ke SHA + checksum GStreamer/gradle wrapper
7. Tambah CI Android (compile + test) di auto-build.yml
8. Sinkronkan versioning Android via script

**Batch 3 — kesehatan jangka panjang:**
9. Pecah god-files (mulai OpenNowScreens.kt)
10. Robolectric/Compose test + coverage untuk area kritis
11. Keputusan: NetworkConditionAdaptor — kawatkan atau hapus

---
*Detail penuh per temuan ada di transkrip agen: ~/.hermes/cache/delegation/live/deleg_9169f94b/task-{0..5}.log*
