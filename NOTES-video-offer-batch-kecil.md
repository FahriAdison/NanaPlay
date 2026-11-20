# NOTES — video-offer batch kecil 1.0.106

## Basis

- Branch: `fix/video-offer-batch-kecil-1.0.106`
- Base: local `main` fast-forwarded to `origin/main` @ `0ad368e8e758f056b1815480455b5babf8b6e9a5` before branching.
- Upstream bundle ada: `D:\NanaPlay-upgrade-upstream-2.4.x.bundle` (`Test-Path` = True).
- Skill wajib tidak ditemukan di `D:\dev\opencode-skills`: `verification-before-completion`, `writing-plans`, `executing-plans`, `antislop`. Disiplin dijalankan manual: plan doc dibuat dulu, RED/GREEN dicatat, verifikasi hanya dari output perintah/artifact, dan diff di-review. Skill pendukung yang tersedia dan dipakai: `tdd-workflow`, `verification-loop`, `kotlin-testing`.

## Item 1 — Instrumentasi identity sesi

- File diubah: `android/app/src/main/java/com/opencloudgaming/opennow/NativeTouchGames.kt`, `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`, `android/app/src/test/java/com/opencloudgaming/opennow/NativeTouchGamesTest.kt`.
- Sebelum: `appLaunchModeFor(game)` private di ViewModel menghitung mode tanpa seam tes dan tanpa log identity.
- Sesudah: seam pure `appLaunchModeFor(nativeTouchMode, game, androidTvProfile)` ditambahkan. `OpenNowViewModel.appLaunchModeFor(game)` memanggilnya lalu mencatat satu `recordDebugEvent("launch", ...)` berisi `title`, `id`, `nativeTouchMode`, `nativeTouch`, `appLaunchMode`, dan `identity=browser|desktop`. Dipanggil dari tiga jalur klaim/create yang sudah ada (`OpenNowViewModel.kt` sekitar 2008, 2529, 4063), bukan logger baru.
- RED: tes resolver baru dijalankan sebelum implementasi; `./gradlew :app:testDebugUnitTest --tests *NativeTouchGamesTest*` compile failed, exit 1.
- GREEN: `NativeTouchGamesTest` 16/16, failures=0, errors=0, exit 0. Kasus baru: HSR+Auto=GAMEPAD_FRIENDLY/desktop, NTE+Auto=TOUCH_FRIENDLY/browser, Always=TOUCH_FRIENDLY, Off=GAMEPAD_FRIENDLY.
- Tidak bisa dites otomatis: Rendering/UI tidak disentuh; perilaku log diverifikasi dari kode `recordDebugEvent` karena ViewModel butuh konteks Android.

## Item 2 — Jangan paksa H264 di timeout offer pertama

- File diubah: `android/app/src/main/java/com/opencloudgaming/opennow/Streaming.kt`.
- Sebelum: `startOfferTimeout` memanggil `requestSafeVideoFallback` sehingga timeout pertama memaksa H264.
- Sesudah: `requestSelectedVideoProfileRetry(message, diagnosticReason)` memakai `settings` apa adanya, flag `selectedProfileRetryApplied` direset di `start`, dan memakai `bumpReconnectAttemptsForFallback()` sama persis. Call site offer timeout memakai pesan `Timed out waiting for video offer; retrying the selected profile` dan `diagnosticReason = "offer timeout"`; bila return false tetap `restartTransport("Timed out waiting for video offer", videoFailure = true)`.
- `requestSafeVideoFallback` tidak dihapus/diubah untuk jalur lain. `OFFER_TIMEOUT_MS`, `MAX_TRANSPORT_RECONNECT_ATTEMPTS`, dialog, dan circuit breaker tidak berubah (terverifikasi dari diff).
- Bukti: `./gradlew :app:compileDebugUnitTestKotlin` → `BUILD SUCCESSFUL`, exit 0.
- Tidak teruji otomatis: perilaku runtime `Streaming`/WebRTC tidak punya seam unit test tanpa dependency baru; dikunci lewat compile + full suite, bukan klaim device.

## Item 3 — Teruskan `appLaunchMode` di 8 call site CloudMatch

- File diubah: `android/app/src/main/java/com/opencloudgaming/opennow/GfnApi.kt`, `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`, `android/app/src/test/java/com/opencloudgaming/opennow/GfnApiTest.kt`.
- Wrapper yang belum punya parameter kini punya default `GfnAppLaunchMode.GAMEPAD_FRIENDLY`: `pollSession`, `stopSession`, `getActiveSessions`, `stopActiveSession`, `reportSessionAd`. `claimSession` sudah punya.
- Delapan call site `cloudMatchHeaders` meneruskan `appLaunchMode = appLaunchMode`: `GfnApi.kt` sekarang sekitar 2523, 2546, 2573, 2605, 2663, 2676, 2737, 2824. Default `cloudMatchHeaders` tetap `GAMEPAD_FRIENDLY`.
- Caller ViewModel meneruskan `currentAppLaunchMode(game)` saat konteks game ada. Sengaja dibiarkan default: `OpenNowViewModel.refreshActiveSession` (`OpenNowViewModel.kt:3931`, refresh latar tanpa game terpilih) dan `AndroidStreamKeepAliveNotifier.terminateCloudSessionAfterTaskRemoval` (`AndroidStreamKeepAliveNotifier.kt:198`, request hanya punya session/settings, tanpa `GameInfo`; tidak membuat lookup baru).
- Tes: `cloudMatchUsesAndroidTouchIdentityForTouchFriendly` tetap ada; ditambah `explicitGamepadLaunchModeMatchesDefaultDesktopHeaders`. Narrow: `GfnApiTest` 42/42, failures=0, errors=0; `NativeTouchGamesTest` 16/16; exit 0.
- Catatan tes: percobaan mengunci signature wrapper via Kotlin reflection gagal karena `kotlin-reflect` tidak ada di test classpath; tes itu dihapus, bukan menambah dependency. Forwarding call site diverifikasi lewat compile dan header contract test; tes HTTP repository tidak bisa dijalankan tanpa mock server/Robolectric baru.

## Verifikasi penuh

- Perintah dari `D:\NanaPlay\android`: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Hasil: `BUILD SUCCESSFUL`, exit 0.
- Unit test XML: 58 suite, 513 tests, 0 failures, 0 errors, 0 skipped.
- Artifact: `android/app/build/outputs/apk/debug/app-debug.apk`, 118.700.120 byte.
- Diff review: 6 file source/test terubah; plan doc baru. Tidak ada perubahan ke `main`, tidak ada merge/push/release/workflow. Tidak ada secret/key yang ditulis.

## Round F-1 + F-2 (review Nanally 20 Nov ~17:14 WIB)

- F-2: `Streaming.kt` offer-timeout message diubah persis menjadi `Timed out waiting for video offer; retrying the selected profile`; `diagnosticReason = "offer timeout"` tidak diubah. Kutipan sama di plan doc dan NOTES ini ikut disamakan. Tidak ada perubahan fungsi retry/fallback/timeout/dialog/circuit breaker.
- Tes terdampak: `./gradlew :app:testDebugUnitTest --tests *NativeTouchGamesTest* --tests *GfnApiTest*` → `GfnApiTest` 42/42, `NativeTouchGamesTest` 16/16, total 58/58, failures=0, errors=0, exit 0.
- F-1: `video-offer-batch-kecil-1.0.106.patch` diregenerasi dari base `0ad368e` hanya LF: 38.281 byte, 592 baris, `PATCH_HAS_CR=False`. Verifikasi `git apply --check` di worktree sementara pada base `0ad368e`: PASS, exit 0.
- Skill wajib `verification-before-completion`, `writing-plans`, `executing-plans`, `antislop` tetap tidak terpasang; disiplin dijalankan manual dan hasil verifikasi dicatat di atas.
