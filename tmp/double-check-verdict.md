# Double-Check Verdict — race `retryQueue()` vs `pollUntilReady()`

Dokumen ini adalah review independen kedua. Saya **tidak** memakai `tmp/review-verdict.md`
sebagai sumber kebenaran; semua klaim di bawah diverifikasi ulang langsung ke kode, ke
`git show HEAD:...`, dan lewat tes runtime coroutines kecil.

## Verdict

**PASS** — fix #1 (3 lokasi) dan fix #2 (guard generasi) benar, semantik fallback identik
dengan `runCatching`, urutan catch benar, dan guard terpasang tepat sebelum `_state.update`
pertama. Ada 5 temuan tambahan yang terlewat reviewer pertama, tetapi **tidak ada** yang
membatalkan fix atau menyebabkan korupsi state pada jalur `retryQueue()` → resume/claim →
`pollUntilReady` yang dituju. Fix aman dilanjutkan ke commit + build (build tetap harus
diverifikasi di mesin ber-SDK).

- Working tree, belum di-commit.
- 1 file berubah: `android/app/src/main/java/com/opencloudgaming/opennow/OpenNowViewModel.kt`
  (+31 / −8), 4 hunk. `git diff --check` bersih (exit 0). `git status` hanya file ini + `tmp/`.

## Verifikasi independen

### 1. Diff & konteks "before"

- `git diff -U15 -- .../OpenNowViewModel.kt` → hanya 4 hunk: `pollKnownSessionDirectly`,
  `refreshActiveSession`, `resumeKnownActiveSession`, `pollUntilReady`.
- `git show HEAD:...OpenNowViewModel.kt` baris 3056–3070 / 3586–3592 / 3610–3622 →
  blok "before" persis seperti yang diklaim reviewer pertama (`getOrNull()`,
  `getOrDefault(emptyList())`, `getOrElse { log; pending }`).

### 2. Tiga lokasi fix #1

| Lokasi | Baris sekarang | Fallback lama → baru | Hasil |
|---|---|---|---|
| `pollKnownSessionDirectly` | 3060–3077 | `getOrNull()` → `null` | identik |
| `refreshActiveSession` | 3592–3598 | `getOrDefault(emptyList())` → `emptyList<ActiveSessionInfo>()` | identik |
| `resumeKnownActiveSession` | 3622–3641 | `getOrElse { log; pending }` → `catch(Throwable) { log; pending }` | identik (pesan debug + `pending.shortDebugId()` sama) |

- `catch (e: Throwable)` tepat: `runCatching` memang menangkap `Throwable`, jadi perilaku
  error non-cancellation tidak berubah (termasuk `Error`).
- Tidak ada sisa `runCatching`/`getOrElse`/`getOrNull` di ketiga fungsi (dicek per rentang
  baris dengan `awk`), jadi tidak ada jalur penelan cancellation yang tertinggal di situ.

### 3. Import & urutan catch

- `import kotlinx.coroutines.CancellationException` ada di **baris 18** (sudah ada di HEAD;
  diff tidak menambah import, jadi tidak ada import yang hilang).
- Urutan di 3071→3075, 3594→3596, 3633→3638: `CancellationException` **dulu**, baru
  `Throwable`. Ini wajib dan benar: `kotlinx.coroutines.CancellationException` adalah
  typealias `java.util.concurrent.CancellationException`
  (`IllegalStateException` → `Exception` → `Throwable`). Urutan terbalik akan membuat catch
  pertama unreachable.
- Karena typealias JVM, catch ini juga menangkap `CancellationException` milik
  `java.util.concurrent` yang mungkin dilempar library (bukan hanya `JobCancellationException`).

### 4. Guard fix #2

- `pollUntilReady` (3736): capture `pollingGeneration` di **3737**,
  `initialPosition` 3744, guard **3749–3752**, `_state.update` pertama **3753**.
  Hanya ada satu `_state.update` di rentang 3736–3755 (dicek dengan awk).
- Klaim "tidak ada suspend point antara capture dan write pertama" **benar**:
  `recordDebugEvent` (418, non-suspend), `queueDisplayPosition`
  (`QueueLaunchStatus.kt:46/52`, non-suspend), `SessionInfo.debugSummary()` (4311),
  `SystemClock.elapsedRealtime()`, `MutableStateFlow.update`.
- Guard tidak bisa false-positive: `pollingGeneration` hanya dinaikkan di `retryQueue()`
  (**4018**), tidak pernah didekreasi/di-reset (deklarasi 303; pemakaian lain hanya
  capture/compare 3737–3935).
- Semua badan launch memakai `viewModelScope` default (`Dispatchers.Main.immediate`);
  tidak ada `withContext(Dispatchers.IO)` yang membungkus badan polling/guard, jadi asumsi
  "single-threaded ordering" valid.
- `return null` dari guard aman: keempat call site `pollUntilReady` (1904, 2275, 3665, 3703)
  memperlakukan `null` sebagai "tidak ada sesi siap", bukan error.

### 5. Layer repository tidak membatalkan fix

- `GfnApi.awaitText` (595–623) = `withContext(Dispatchers.IO) { blocking OkHttp }` dengan
  `catch (error: Throwable) { ...; throw error }` → **tidak** menelan cancellation.
- `pollSession` (2430+), `getActiveSessions` (2486+), `claimSession` (2531+) tidak membungkus
  suspend call dengan `runCatching`; `runCatching` hanya untuk JSON parsing non-suspend
  (mis. 2496, 2566). Jadi `CancellationException` benar-benar keluar dari repository.

### 6. Tes runtime coroutines (bukti premis + mekanisme fix)

Dijalankan di `/tmp/opencode/ktcheck` memakai `kotlinx-coroutines-core-jvm 1.9.0` dan
`kotlin-compiler-embeddable 2.0.21` dari cache Gradle (JDK 21). Bukan build app; ini tes
semantik terisolasi yang meniru bentuk kode app.

`Test.kt` (inti):

```kotlin
// Meniru GfnApi.awaitText: withContext(IO) membungkus panggilan blocking.
private suspend fun blockingIo(): String = withContext(Dispatchers.IO) {
    Thread.sleep(400); "value"
}

// A) pola lama
val j1 = launch {
    val r = runCatching { blockingIo() }
    println("A) runCatching: isSuccess=${r.isSuccess} cause=${r.exceptionOrNull()?.javaClass?.simpleName} isActive=${isActive}")
    println("A) coroutine CONTINUED after swallowing")
    delay(1)
}
delay(50); j1.cancel(); j1.join()

// B) pola fix #1
val j2 = launch {
    try {
        val v = try { blockingIo() } catch (e: CancellationException) { throw e } catch (e: Throwable) { "fallback" }
        println("B) continued with $v (unexpected)")
    } catch (e: CancellationException) {
        println("B) cancellation propagated through fixed pattern (expected)")
        throw e
    }
}
delay(50); j2.cancel(); j2.join()
```

Output aktual:

```
A) runCatching: isSuccess=false cause=JobCancellationException isActive=false
A) coroutine CONTINUED after swallowing (bug premise confirmed)
A) job done, cancelled=true
B) cancellation propagated through fixed pattern (expected)
B) job done, cancelled=true
```

Interpretasi:

- Premis bug terbukti: `runCatching` menelan `JobCancellationException`, coroutine **tetap
  menjalankan statement setelahnya** (termasuk `_state.update` bila ada). `delay(1)`
  berikutnya baru melempar cancellation, jadi write non-suspend setelah swallow benar-benar
  terjadi. Ini persis mekanisme korupsi state di `resumeKnownActiveSession` versi lama.
- Pola fix #1 terbukti: cancellation **propagasi keluar** meski blok IO-nya blocking
  (prompt cancellation). Jadi coroutine basi tidak bisa lanjut ke `pollUntilReady` dengan
  generasi baru.
- `_state.update` sendiri non-suspend, jadi write setelah swallow tidak akan terhenti
  sendiri — memperkuat perlunya fix #1, bukan hanya guard.

### 7. Tes kompilasi snippet (bukan build app)

`Check.kt` meniru ketiga bentuk persis, termasuk `try { ... } catch ... \
  .firstOrNull { ... }` (lokasi 2) dan smart-cast `polled != null` (lokasi 1), dikompilasi
dengan `K2JVMCompiler` 2.0.21 → **berhasil**, menghasilkan `out/CheckKt.class` dkk.
Artinya bentuk sintaks/type-inference yang dipakai tidak bermasalah (mis. `.firstOrNull`
setelah try-expression memang mengikat ke seluruh try-expression).

### 8. Grep yang dijalankan (ringkas)

- `grep -n "runCatching\|CancellationException\|getOrElse\|getOrNull\|getOrDefault"` di
  `OpenNowViewModel.kt` → memetakan semua sisa pola.
- `grep -n "pollingGeneration"` → 303 (deklarasi), 3737 (capture), 3749–3935 (guard),
  4018 (satu-satunya increment).
- `grep -n "onFailure"` → menemukan wrapper `return@onFailure` untuk cancellation.
- `grep -n "viewModelScope.launch\|Dispatchers\."` → memastikan jalur launch/polling di Main.
- `grep -rn "suspend fun pollSession\|suspend fun getActiveSessions\|suspend fun claimSession\|fun .*awaitText"`
  di `GfnApi.kt` → cek layer repository.

## Temuan tambahan (terlewat reviewer pertama)

Semua di bawah adalah **sisa pola yang sama** (`runCatching` menelan cancellation), bukan
regresi dari perubahan ini. Tidak ada yang membatalkan fix #1/#2.

1. **`recoverStreamSession` — `runCatching { withTimeout { ... } }` dengan
   `.onFailure { if (error is CancellationException) return@onFailure }`**
   (3002 / 3021). Cancellation yang sekarang di-rethrow fix #1 ditelan di wrapper terluar.
   - Risiko: **rendah**. Setelah rantai `runCatching` tidak ada statement (fungsi berakhir di
     3044–3049), jadi tidak ada stale write. Efeknya hanya coroutine berhenti sebagai
     "normal" alih-alih unwind.
2. **Empat wrapper launch yang memanggil `resumeKnownActiveSession`/`pollUntilReady`**
   memakai pola `runCatching { ... }.onFailure { if (error is CancellationException)
   return@onFailure }`: **1908–1909** (`play`), **2279–2280**
   (`terminateActiveSessionAndStartNew`), **2380–2381** (`resumeActiveSession`),
   **2457–2458** (`resumePendingActiveSession`).
   - Risiko: **rendah sekarang, footgun nanti**. Saya cek ekor tiap blok (1929–1931,
     2300–2302, 2408–2410, 2478–2480): tidak ada statement setelah rantai, jadi tidak ada
     stale write. Tapi konsekuensinya komentar fix #1 ("must unwind") tidak akurat secara
     literal: exception ditelan di wrapper, coroutine selesai normal. Kalau nanti ada kode
     ditambahkan setelah rantai itu, bug yang sama bisa muncul lagi. Saran: `throw error`
     untuk cancellation (atau `ensureActive()` setelah blok).
3. **`loadPrintedWasteQueue` (3534, `onFailure` 3577–3584) menulis state setelah
   cancellation**: `printedWasteLoading = false` dan
   `printedWasteError = error.message ?: ...` (akan berbunyi "Job was cancelled").
   - Risiko: **rendah–sedang**, tergantung reachability. Fungsi ini jalan di `launchJob`
     lewat `play()` → `showPrintedWasteSelector` hanya saat `skipPrintedWaste = false`;
     `retryQueue()` sendiri memanggil `play(..., skipPrintedWaste = true)`, jadi job baru
     tidak menyentuhnya. Tapi `retryQueue()` bisa membatalkan job lama yang sedang di sini
     (secara teori; UI selector biasanya tidak menampilkan tombol retry). Ini satu-satunya
     sisa "swallow + write" yang jelas-jelas satu kelas dengan bug asli. Layak diperbaiki.
4. **`terminateActiveSessionAndStartNew` 2254–2262**: `runCatching { stopActiveSession }`
   dengan `onFailure { log saja }` menelan cancellation, lalu baris berikutnya menulis
   `_state.update { activeSession = null; launchPhase = "Creating session" }` (2262).
   - Risiko: **rendah**. Satu stale write setelah reset `retryQueue()` (fase UI bisa
     tertimpa). Suspension berikutnya (`createSessionWithRetry`) langsung melempar lagi
     sehingga eksekusi berhenti. Reachability juga sempit (butuh tombol retry aktif saat
     penggantian sesi berjalan).
5. **`reportQueueAd` 2607 + `onSuccess` 2624–2642 / `onFailure` 2643–2660**: `runCatching`
   tanpa rethrow cancellation, dan `onSuccess` menulis `streamSession` hasil merge walau
   `updated.sessionId` tidak sama dengan sesi saat ini (fallback ke `session` lama).
   - Risiko: **rendah untuk cancellation** (job ini bukan `launchJob`, hanya batal saat
     ViewModel cleared), **tetapi** stale-session merge-nya pre-existing dan berdekatan
     dengan polling iklan queue; bukan bagian dari perubahan ini.
6. Catatan kecil: `resolveFallbackLaunchAppId` di recovery (2929–2936, `getOrNull`) juga
   menelan cancellation, tapi tidak ada write sebelum suspension berikutnya
   (`pollKnownSessionDirectly`) yang langsung melempar lagi → aman secara praktis.

## Akurasi dokumen reviewer pertama

**Benar (diverifikasi):**

- 1 file, +31/−8; import `CancellationException` baris 18; guard 3749; capture 3737;
  fallback ketiga lokasi identik; baris 3534 memang milik `loadPrintedWasteQueue`;
  `queueDisplayPosition` di `QueueLaunchStatus.kt:52`; klaim "tidak ada suspend point
  sebelum write pertama" terbukti benar; `runCatching` menelan cancellation terbukti lewat
  tes runtime; mekanisme race (stale coroutine menangkap generasi baru → guard lumpuh)
  logis dan konsisten dengan kode.
- Kesimpulan "kompilasi belum diverifikasi" jujur dan tepat.

**Melenceng minor (nomor baris, tidak material):**

- `chooseQueueAdActiveId` sebenarnya **4129**, bukan 4121.
- `loadingPhaseFor` sebenarnya **4135**, bukan 4127.
- `SessionInfo.debugSummary()` sebenarnya **4311**, bukan 4303.
- Dokumen mengklaim "semua referensi memakai nomor baris terkini", jadi angka-angka di atas
  adalah drift dokumentasi, bukan kesalahan analisis.
- Dokumen hanya menyebut baris 3534 "di luar lingkup" tanpa menilai bahwa `onFailure`-nya
  menulis state saat cancellation — seharusnya minimal dicatat sebagai risiko.

## Yang TIDAK dicek

- **Kompilasi/build app penuh** (`:app:compileDebugKotlin`, Gradle/AGP): tidak bisa —
  tidak ada Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` kosong, tidak ada
  `local.properties`).
- Uji runtime di perangkat/emulator, dan uji UI (tombol retry) — tidak ada environment.
- Yang **sudah** dijalankan sebagai pengganti terbatas: kompilasi snippet terisolasi
  (K2JVMCompiler 2.0.21) dan tes runtime coroutines 1.9.0 (bagian 6–7).

## Kesimpulan

- Fix #1: **benar** — fallback identik, `Throwable` sesuai semantik `runCatching`,
  `CancellationException` di-rethrow dengan urutan catch yang valid, dan repository tidak
  menelan cancellation. Terbukti secara runtime bahwa pola lama melanjutkan eksekusi
  setelah cancellation sedangkan pola baru mempropagasikannya.
- Fix #2: **benar dan aman** — guard berada sebelum `_state.update` pertama, hanya bisa
  true saat `retryQueue()` menaikkan generasi, dan `return null` konsisten dengan kontrak
  pemanggil.
- Sisa 5 temuan adalah technical debt satu kelas yang sama, bukan blocker; dua di antaranya
  (item 2 dan 3) sebaiknya dijadikan follow-up kecil karena berpotensi menjadi bug yang
  sama di masa depan.
- **Aman dilanjutkan ke commit + build.** Build tetap wajib dijalankan di mesin dengan
  Android SDK sebelum merge, sesuai catatan reviewer pertama.
