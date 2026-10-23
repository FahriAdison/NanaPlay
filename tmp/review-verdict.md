# Review Verdict — race `retryQueue()` vs `pollUntilReady()`

## Verdict

**PASS** — perbaikan #1 dan #2 sudah diterapkan, konsisten, dan tidak mengubah perilaku
penanganan error non-konfigurasi. Catatan: **kompilasi/build belum diverifikasi** karena
tidak ada Android SDK di environment ini.

## Temuan

`runCatching { ... }` menangkap **semua** `Throwable`, termasuk `CancellationException`.
Kemudian `getOrElse` / `getOrNull` / `getOrDefault` mengembalikan nilai fallback, sehingga
pembatalan yang dilakukan `retryQueue()` **ditelan**. Akibatnya coroutine yang sudah basi
(telah dibatalkan) tetap berjalan:

- Di `resumeKnownActiveSession`, coroutine basi itu lanjut ke `pollUntilReady(...)`.
- `pollUntilReady` menangkap `pollingGeneration` **sekali** di awal fungsi.
- Karena coroutine basi itu masuk `pollUntilReady` **setelah** `retryQueue()` menaikkan
  generasi, ia menangkap generasi **baru**, sehingga semua guard
  `currentGeneration != pollingGeneration` di dalam loop membandingkan nilai yang sama →
  **guard lumpuh**.
- Poll basi itu lalu menulis `streamSession` / `queuePosition` / field stuck-detection dan
  menimpa reset milik `retryQueue()` (baris ~4007). Karena keduanya jalan di
  `Dispatchers.Main.immediate`, ini murni masalah **urutan** (last-writer-wins).

## Perubahan

File: `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`

### Fix #1 — jangan telan `CancellationException` (3 lokasi)

1. **`pollKnownSessionDirectly`** — sebelum ~baris 3060 → sesudah ~baris 3060–3077
   - Sebelum: `val polled = runCatching { sessionRepository.pollSession(...) }.getOrNull()`
   - Sesudah:
     ```kotlin
     val polled = try {
         sessionRepository.pollSession(...)
     } catch (e: CancellationException) {
         throw e
     } catch (e: Throwable) {
         null
     }
     ```

2. **`refreshActiveSession`** — sebelum ~baris 3586 → sesudah ~baris 3592–3598
   - Sebelum:
     ```kotlin
     val active = runCatching { sessionRepository.getActiveSessions(token, effectiveStreamingBaseUrl(auth), settings) }
         .getOrDefault(emptyList())
         .firstOrNull { it.status in setOf(1, 2, 3) && it.matchesStreamSettings(settings) }
     ```
   - Sesudah:
     ```kotlin
     val active = try {
         sessionRepository.getActiveSessions(token, effectiveStreamingBaseUrl(auth), settings)
     } catch (e: CancellationException) {
         throw e
     } catch (e: Throwable) {
         emptyList<ActiveSessionInfo>()
     }.firstOrNull { it.status in setOf(1, 2, 3) && it.matchesStreamSettings(settings) }
     ```

3. **`resumeKnownActiveSession`** — sebelum ~baris 3612 → sesudah ~baris 3622–3641
   - Sebelum:
     ```kotlin
     val hydrated = runCatching {
         sessionRepository.pollSession(...)
     }.getOrElse { error ->
         recordDebugEvent("queue", "Resume hydrate failed session=${pending.shortDebugId()} error=${error.debugMessage()}")
         pending
     }
     ```
   - Sesudah:
     ```kotlin
     val hydrated = try {
         sessionRepository.pollSession(...)
     } catch (e: CancellationException) {
         throw e
     } catch (e: Throwable) {
         recordDebugEvent("queue", "Resume hydrate failed session=${pending.shortDebugId()} error=${e.debugMessage()}")
         pending
     }
     ```

Catatan fix #1:
- `catch (e: Throwable)` dipilih (bukan `catch (e: Exception)`) agar setara dengan
  `runCatching`, yang memang menangkap `Throwable` — tidak ada perubahan perilaku untuk
  error non-`CancellationException`.
- Fallback (`null`, `emptyList<ActiveSessionInfo>()`, `pending`) dan pesan debug identik.
- `CancellationException` di-rethrow **sebelum** catch lain (urutan penting karena
  `CancellationException` adalah subclass `Exception`/`Throwable`).

### Fix #2 — guard generasi sebelum `_state.update` pertama di `pollUntilReady`

- Sebelum ~baris 3745 → sesudah ~baris 3745–3752
- Disisipkan tepat sebelum `_state.update` pertama (isi `_state.update` **tidak diubah**):
  ```kotlin
  // Defense-in-depth, not the primary fix: there is no suspend point between the
  // generation capture above and this first state write, so retryQueue() cannot
  // interleave here on the single-threaded main dispatcher. The real fix is #1
  // (stop swallowing CancellationException) plus the guard at the top of the loop below.
  if (currentGeneration != pollingGeneration) {
      recordDebugEvent("queue", "Polling cancelled by newer generation before initial state update (current=$currentGeneration, active=$pollingGeneration)")
      return null
  }
  ```

## Checks yang dijalankan + hasilnya

- **Cek suspend call antara capture generasi (baris ~3737) dan `_state.update` pertama
  (baris ~3745):** TIDAK ada suspend call. Yang dipanggil hanya:
  - `recordDebugEvent` (baris 418) → `private fun`, non-suspend
  - `queueDisplayPosition` (`QueueLaunchStatus.kt:52`) → `internal fun`, non-suspend
  - `SessionInfo.debugSummary()` (baris 4303) → `private fun`, non-suspend
  - `android.os.SystemClock.elapsedRealtime()` → non-suspend
  - `MutableStateFlow.update { ... }` → non-suspend
  - `loadingPhaseFor` (4127) / `chooseQueueAdActiveId` (4121) di dalam lambda → non-suspend
  → Karena itu fix #2 diberi komentar jujur sebagai **proteksi lapis kedua**, bukan fix utama.
- **Cek import:** `import kotlinx.coroutines.CancellationException` ada di baris 18 → `throw e`
  valid.
- **Cek return type / tipe fallback:**
  - `pollSession` → `SessionInfo`; fallback `pending` (`SessionInfo`) cocok.
  - `getActiveSessions` → `List<ActiveSessionInfo>`; fallback `emptyList<ActiveSessionInfo>()` cocok.
  - `pollKnownSessionDirectly` mengembalikan `ActiveSessionInfo?`; fallback `null` cocok.
- **Cek kecocokan before/after:** diff hanya mengubah blok `runCatching` → `try/catch`;
  fallback & pesan debug identik; isi `_state.update` tidak berubah.
- **Cek sisa pola:** tidak ada lagi `runCatching` / `getOrElse` / `getOrNull` di ketiga
  fungsi target. Satu `runCatching` di baris ~3534 milik `loadPrintedWasteQueue` sengaja
  tidak disentuh (di luar lingkup).
- **Cek nomor baris:** nomor bergeser akibat penambahan baris fix #1; semua referensi di
  dokumen ini memakai nomor baris **terkini**.

## Yang TIDAK dicek

- **Kompilasi/build Gradle** (`:app:compileDebugKotlin` atau build Android): tidak bisa
  dijalankan — `ANDROID_HOME` / `ANDROID_SDK_ROOT` kosong dan tidak ada `local.properties`
  di environment ini.

## Status

- **Working tree, belum di-commit.**
- 1 file diubah: `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`
  (+31 / −8).
- File verdict ini dibuat baru karena `tmp/review-verdict.md` sebelumnya tidak ditemukan.
