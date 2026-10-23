# Double-Check Verdict — Fitur `ThermalWarningPill` (NanaPlay)

Review independen. **Tidak** memakai ringkasan implementor; semua klaim diverifikasi langsung ke
kode kerja (`git diff` + baca sumber) pada branch `nanaplay`, working tree **belum di-commit**.
Tidak ada kode yang diubah oleh dokumen ini.

Cakupan working tree (unstaged):

```
android/.../OpenNowScreens.kt    +48
android/.../OpenNowViewModel.kt   +92 / -1
android/.../res/values/strings.xml     +2
android/.../res/values-in/strings.xml  +2
```

Catatan: `AndroidThermalStatus`, `readThermalStatus`, dan `BugReportPreflight.kt` **sudah ada di
HEAD** dan tidak diubah fitur ini; hanya dipakai ulang.

## Verdict

**PASS dengan catatan** — logika inti (urutan enum, cabang trigger/clear/hysteresis) **benar**,
string & import **cocok**. Tidak ada bug yang membatalkan fitur. Ada **3 temuan nyata** yang
perlu diperbaiki (1 edge-case menampilkan angka suhu palsu, 1 tabrakan UI bersyarat, 1 auto-hide
yang tidak pernah jalan) dan beberapa titik reset lifecycle yang kelewat.

Tidak bisa di-compile: tidak ada Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` kosong, tanpa
`local.properties`). Semua kesimpulan dari pembacaan sumber.

---

## 1. Urutan enum `AndroidThermalStatus` — BENAR

`AndroidRuntimeDiagnostics.kt:56-65`:

```kotlin
internal enum class AndroidThermalStatus(val logValue: String) {
    Unknown, None, Light, Moderate, Severe, Critical, Emergency, Shutdown,
}
```

Ordinal: Unknown=0, None=1, Light=2, Moderate=3, Severe=4, Critical=5, Emergency=6, Shutdown=7.
Kotlin enum membandingkan via `Comparable` berdasarkan ordinal, jadi:

- `>= Moderate` → Moderate, Severe, Critical, Emergency, Shutdown ✅
- `< Moderate` → Unknown, None, Light ✅

`readThermalStatus` (`:125-138`) memetakan 1:1 dari `PowerManager.THERMAL_STATUS_*`; di bawah
API 29 atau nilai tak dikenal → `Unknown`. Tidak ada cacat.

## 2. Cabang `evaluateThermalWarning()` — BENAR (dengan catatan desain)

`OpenNowViewModel.kt:2737-2782`:

```kotlin
val isHighThermal = thermalStatus >= AndroidThermalStatus.Moderate ||
    (batteryTempC != null && batteryTempC >= 45.0f)
val isCleared = (batteryTempC == null || batteryTempC < 42.0f) &&
    thermalStatus < AndroidThermalStatus.Moderate
```

- **Trigger 2-tick** (`:2747-2762`): `consecutiveHighThermalTicks >= 2` → set `thermalWarning = true`.
  Benar; butir debounce berfungsi.
- **Clear** (`:2765-2775`): butuh `temp < 42` (atau null) **DAN** `thermalStatus < Moderate`.
- **Hysteresis 42–45°C** (`:2776-2780`): di band itu dengan status `< Moderate`, `isHighThermal`
  false dan `isCleared` false → warning **bertahan** dan `thermalWarningTempC` mengikuti suhu.
  Benar.

Catatan desain (bukan bug):
- Trigger bersifat **OR**: `thermalStatus >= Moderate` saja (walau `batteryTempC` 40°C) sudah
  memicu. Pill lalu menampilkan "hot (40°C)".
- Clear bersifat **AND**: selama `thermalStatus >= Moderate`, warning tidak pernah clear walau
  suhu turun. Ini wajar karena status termal bersifat otoritatif.

## 3. Titik reset — ADA YANG KELEWAT

Reset yang **ada** (kurang-lebih lengkap):

| Fungsi | `thermalWarning=false` | `consecutiveHighThermalTicks=0` |
|---|---|---|
| `play()` | 1846 | 1826 |
| `markSessionReadyForNativeStream()` | 2023 | 2011 |
| `stopStream()` | 2164 | 2095, 2136 |
| `markStreamError()` | 2804 | 2796 |
| `recoverStreamSession()` cabang offer-timeout | 2974 | 2958 |
| `retryQueue()` | 4123 | 4114 |
| `retryWithAnotherServer()` | 4169 | 4151 |

Yang **kelewat**:

- `thermalWarning`/`thermalWarningTempC` **tidak** di-reset di:
  `logout()` (1115), `logoutAll()` (1232), `dismissActiveSessionDecision()` (2212/2215),
  `resumePendingActiveSession()` (update awal 2448 + `onFailure` 2481),
  `recoverStreamSession()` jalur normal (2994), `onSuccess` (3073), `onFailure` timeout (3089),
  `onFailure` (3107).
- `consecutiveHighThermalTicks` **tidak** di-reset di:
  `terminateActiveSessionAndStartNew()` (2227), `resumeActiveSession()` (2322),
  `resumePendingActiveSession()` (2432), `recoverStreamSession()` (2922), `logout`, `logoutAll`,
  `dismissActiveSessionDecision`.

**Temuan paling konkret — `resumePendingActiveSession`:** `resumeActiveSession()` keluar lebih awal
di `:2327-2330` saat `pendingActiveSessionLaunch != null`, sehingga melewati reset thermal-nya
sendiri (`:2362`). `resumePendingActiveSession` tidak pernah me-reset thermal, dan `onFailure`
-nya (`:2481`) juga tidak. Jadi `thermalWarning` lama bisa bertahan setelah pending-resume gagal.
Pada sukses, `markSessionReadyForNativeStream` yang me-reset.

**Risiko `consecutiveHighThermalTicks`:** field ini tunggal di ViewModel (bukan per-sesi) dan
tidak di-reset di `terminateActiveSessionAndStartNew`/`resumeActiveSession`. Jika counter meninggalkan
nilai ≥2 di akhir sesi, sesi berikutnya bisa memicu warning **tanpa debounce 2-tick**.

**Severity:** sebagian besar *transient*, karena `play()` me-reset di awal setiap launch baru.
Jadi kebocoran state terbatas; `resumePendingActiveSession` adalah gap yang paling jelas.

## 4. `ThermalWarningPill` vs `ActiveStreamModePill` — 1 TABRAKAN UI

Kesamaan (disengaja, reuse): warna `Color(0xff4a2f0b).copy(alpha = 0.88f)`, teks
`Color(0xffffd38a)`, `RoundedCornerShape(999.dp)`, `labelSmall`/`SemiBold`, padding 10/5, dan
durasi auto-hide `ACTIVE_STREAM_MODE_NOTICE_DURATION_MS = 3000` (`OpenNowScreens.kt:328`). ✅

Posisi (`OpenNowScreens.kt`):
- `ActiveStreamModePill` (8016-8026): `TopCenter`, top = `base` (48.dp bila stats di TopCenter,
  else 8.dp).
- `ThermalWarningPill` (8028-8036): `TopCenter`, top = `base + 36.dp` bila `activeStreamMode != null`,
  else `base`.
- Recording pill (8037-8053): **selalu** `TopCenter` + `padding(top = 8.dp)`.
- Stats pill (7984-7998): `align(statsAlignment)` **tanpa** top padding → di tepi atas saat TopCenter.

**Tabrakan (bersyarat, nyata):** bila stats **tidak** di TopCenter dan `activeStreamMode == null`,
`baseTopPadding` thermal = 8.dp → **berimpit** dengan recording pill (8.dp). Karena recording
di-compose **setelah** thermal (8037 > 8028), recording digambar di atas dan bisa **menutupi**
peringatan thermal.

**Quirk offset:** `+36.dp` dipicu `activeStreamMode != null` (state), bukan visibilitas pill mode
(yang auto-hide setelah 3 dtk). Jadi setelah pill mode hilang, pill thermal tetap menjauh 36.dp.

## 5. String `thermal_warning_pill` — COCOK

- `values/strings.xml:779` — `Phone hot (%1$d°C) — cool it down to stop stuttering.`
- `values-in/strings.xml:778` — `HP panas (%1$d°C) — dinginkan HP biar nggak patah-patah.`

`%1$d` cocok dengan argumen `Int` `roundedTemp` (`(tempC ?: 45f).roundToInt()`). ✅

## 6. Import — LENGKAP

`OpenNowScreens.kt`:
- `com.papahchan.nanaplay.R` (baris 2) — cocok dengan `namespace = "com.papahchan.nanaplay"`
  di `android/app/build.gradle.kts:58`. ✅
- `kotlin.math.roundToInt` (310), `AnimatedVisibility` (31), `fadeIn`/`fadeOut` (33/34),
  `LaunchedEffect` (184), `mutableStateOf` (191), `remember` (193), `kotlinx.coroutines.delay` (280). ✅

## 7. Bug hunt

1. **Suhu palsu (nyata, edge case).** `ThermalWarningPill` memakai `(tempC ?: 45f).roundToInt()`
   (`:11804`). Saat pemicu adalah `thermalStatus >= Moderate` **dan** `batteryTempC == null`,
   `thermalWarningTempC` disimpan `null` (`:2758`), sehingga pill menampilkan
   **"Phone hot (45°C)"** — angka karangan yang kebetulan sama dengan ambang trigger.
   `batteryTemperatureC` null bila `EXTRA_TEMPERATURE` absen/`Int.MIN_VALUE` (`:115,121`), umum di
   Android TV / tablet tertentu. Menyesatkan; fallback 45 sebaiknya dibedakan dari "benar-benar 45".

2. **Auto-hide tidak pernah jalan.** `remember(roundedTemp)` + `LaunchedEffect(roundedTemp)`
   (`:11806-11812`) me-reset timer tiap `roundedTemp` berubah. Karena `thermalWarningTempC`
   diperbarui di hampir setiap tick selama warning aktif (`:2776-2779`), timer terus di-re-arm →
   pill cenderung **tetap terlihat selama masa panas**, bukan auto-hide 3 dtk. Efek samping: fade
   terpicu ulang saat suhu berosilasi di batas pembulatan.

3. **Performa.** `evaluateThermalWarning()` memanggil `AndroidRuntimeDiagnostics.snapshot()`
   di **setiap** stats tick (1 dtk) di main thread. `snapshot()` = `registerReceiver` battery +
   snapshot jaringan + baca termal, 60×/menit. Sebelumnya `updateStreamRuntimeStats` hanya
   `networkSnapshot` tiap 5 dtk dan `snapshot` tiap 30 dtk; fitur ini menaikkan `snapshot` penuh ke
   1 dtk. Sebaiknya di-cache/di-throttle (mis. hanya evaluasi termal bila berubah).

4. **Tidak ada race pada counter.** `onStats` didispatch via `scope.launch { ... }` dengan
   `scope = Dispatchers.Main.immediate` (`Streaming.kt:3610`, pemakaian `:5864-5873`), jadi
   `evaluateThermalWarning` jalan di main thread; `consecutiveHighThermalTicks` tidak dimutasi
   lintas-thread. ✅

5. **Guard `hasDebugValues`.** `updateStreamRuntimeStats` (`:2710`) return awal bila
   `!stats.hasDebugValues()`; pada kondisi itu thermal tidak dievaluasi maupun di-clear. Minor.

6. `BugReportPreflight.kt` memakai `thermalStatus in setOf(...)` — independen, tidak terpengaruh.

## Ringkasan aksi yang disarankan (tidak dilakukan)

| # | Lokasi | Masalah | Prioritas |
|---|---|---|---|
| 1 | `ThermalWarningPill` (`OpenNowScreens.kt:11804`) | fallback 45°C menampilkan angka palsu saat `batteryTempC == null` | sedang |
| 2 | `StreamScreen` (`OpenNowScreens.kt:8028-8036`) | tabrakan dengan recording pill (8.dp) bila stats bukan TopCenter & `activeStreamMode == null` | sedang |
| 3 | `ThermalWarningPill` (`:11806-11812`) | auto-hide di-re-arm tiap suhu berubah | rendah |
| 4 | `resumePendingActiveSession` (`OpenNowViewModel.kt:2432`) + `recoverStreamSession` normal/failure | `thermalWarning`/`consecutiveHighThermalTicks` tidak di-reset | rendah |
| 5 | `evaluateThermalWarning` (`:2738`) | `snapshot()` penuh tiap 1 dtk di main thread | rendah |

---

# Verifikasi Fix (putaran kedua)

Review independen ulang terhadap working tree terbaru (branch `nanaplay`, belum di-commit).
Tidak ada kode yang diubah oleh dokumen ini.

Diff terbaru:

```
android/.../OpenNowScreens.kt    +40
android/.../OpenNowViewModel.kt   +92 / -1
android/.../res/values/strings.xml     +3
android/.../res/values-in/strings.xml  +3
4 files changed, 137 insertions(+), 1 deletion(-)
```

Dibanding putaran pertama (Screens +48, ViewModel +92/-1, strings +2/+2): hanya `OpenNowScreens.kt`
yang berubah (−8) dan kedua file string (+1 masing-masing). **`OpenNowViewModel.kt` tidak berubah.**

## 1. Suhu palsu (`tempC == null`) — BENER ✅

`ThermalWarningPill` (`OpenNowScreens.kt:11801-11828`) sekarang:

```kotlin
val text = if (tempC != null) {
    stringResource(R.string.thermal_warning_pill, tempC.roundToInt())
} else {
    stringResource(R.string.thermal_warning_pill_no_temp)
}
```

- Saat `tempC == null` memakai `thermal_warning_pill_no_temp` (tanpa angka). Tidak ada lagi
  fallback `(tempC ?: 45f)`. ✅
- String baru ada dan bilingual:
  - `values/strings.xml:780` → `Phone hot — cool it down to stop stuttering.`
  - `values-in/strings.xml:779` → `HP panas — dinginkan HP biar nggak patah-patah.`
- Format argumen benar: `thermal_warning_pill` tetap `%1$d` + argumen `Int` `tempC.roundToInt()`;
  `thermal_warning_pill_no_temp` tanpa placeholder dan dipanggil tanpa argumen. ✅
- `tempC.roundToInt()` — `tempC` di-smart-cast ke `Float` di cabang `!= null`; `roundToInt`
  masih ter-import (`:310`). ✅
- Catatan: `locales/*.json` (Crowdin/Electron) memang tidak memuat nama string Android
  (isinya bertipe `app.actions.*`), jadi tidak ada yang hilang di sana.

## 2. Tabrakan UI — BENER ✅

`StreamScreen` (`OpenNowScreens.kt:8028-8038`):

```kotlin
if (state.thermalWarning) {
    val baseTopPadding = if (statsVisible && statsAlignment == Alignment.TopCenter) 48.dp else 8.dp
    val extraTopPadding = (if (activeStreamMode != null) 36.dp else 0.dp) +
        (if (recordingPhase == StreamRecorderPhase.Recording) 36.dp else 0.dp)
    ThermalWarningPill(
        tempC = state.thermalWarningTempC,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = baseTopPadding + extraTopPadding),
    )
}
```

Skenario yang dikeluhkan (stats **bukan** TopCenter + `activeStreamMode == null` + recording aktif):
`baseTopPadding` = 8.dp, `extraTopPadding` = 36.dp → pill di **44.dp**; recording tetap di **8.dp**.
Jarak 36.dp, **tidak berimpit**. ✅

Semua kombinasi terverifikasi tidak ada yang berimpit dengan pill thermal:

| stats | mode | recording | posisi thermal |
|---|---|---|---|
| bukan TopCenter | null | ya | 8 + 36 = 44.dp |
| bukan TopCenter | ada | ya | 8 + 72 = 80.dp |
| TopCenter | null | ya | 48 + 36 = 84.dp |
| TopCenter | ada | ya | 48 + 72 = 120.dp |
| TopCenter | null | tidak | 48.dp |
| bukan TopCenter | null | tidak | 8.dp (tidak ada pill lain di atas) |

Catatan (di luar 3 item, **pre-existing**): pill mode dan pill recording sama-sama di 8.dp bila
keduanya aktif bersamaan (mode `:8016`, recording `:8039`); itu di luar scope fix ini.

## 3. Auto-hide — BENER ✅

`ThermalWarningPill` kini hanya `Surface` + `Text` (`:11801-11828`). Tidak ada lagi
`remember`/`mutableStateOf`/`LaunchedEffect`/`delay`/`AnimatedVisibility`/`fadeIn`/`fadeOut`.
Visibilitas murni mengikuti `if (state.thermalWarning)` di `StreamScreen:8028`. ✅

Tidak ada import menggantung — semua masih dipakai di file:
`AnimatedVisibility` 13×, `LaunchedEffect` 62×, `mutableStateOf` 123×, `remember` 90×,
`fadeIn`/`fadeOut` 12×, `delay` 21×. ✅

## 4. Tidak ada perubahan di luar 3 item — BENER ✅

- `OpenNowViewModel.kt` tetap +92/-1, **identik** dengan versi putaran pertama. Logika
  trigger/hysteresis/reset tidak disentuh.
- Isi `evaluateThermalWarning()` (`:2734-2780`) diverifikasi identik dengan yang direview
  sebelumnya: `isHighThermal` (OR: `>= Moderate` / `temp >= 45`), `isCleared` (AND:
  `temp < 42` / null **dan** `< Moderate`), 2-tick, dan band 42–45°C.
- Tidak ada file lain yang berubah (hanya 4 file di diff).

## Kesimpulan

Ketiga temuan dari putaran pertama sudah **ditangani dengan benar**, dan tidak ada regresi baru
yang terlihat. Perubahan minimal (hanya UI pill + 2 string + offset padding). Build tetap harus
diverifikasi di mesin ber-SDK (tidak ada Android SDK di lingkungan ini).

Sisa catatan minor yang belum ditangani (bukan bagian dari 3 item): `snapshot()` penuh tiap 1 dtk
di main thread pada `evaluateThermalWarning` (`:2738`).
