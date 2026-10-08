<p align="center">
  <img src="logo.png" width="120" alt="NanaPlay icon">
</p>

<h1 align="center">🎮 NanaPlay</h1>

<p align="center">
  <strong>GeForce NOW di Android, dengan rasa konsol.</strong><br>
  <em>An open-source Android client for GeForce NOW — reimagined by Papah Chan.</em>
</p>

<p align="center">
  <a href="https://github.com/FahriAdison/NanaPlay/releases/latest">
    <img src="https://img.shields.io/github/v/release/FahriAdison/NanaPlay?style=for-the-badge&label=📥%20DOWNLOAD&color=3DDC84" alt="Download">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Beta-orange?style=flat-square" alt="Beta">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/github/stars/FahriAdison/NanaPlay?style=flat-square" alt="stars">
</p>

> **NanaPlay lagi BETA.** Udah dites di HP beneran dan jalan — tapi namanya juga beta, kadang ada kurang-kurangnya. Laporan bug selalu welcome!

NanaPlay adalah fork komunitas dari [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) (oleh [Zortos](https://github.com/OpenCloudGaming)), yang dirombak total buat Android oleh **Papah Chan** ([FahriAdison](https://github.com/FahriAdison)).

Yang dipertahankan: streaming GeForce NOW yang solid. Yang ditambah: tampilan ala konsol, antrian yang lebih pinter, dan fitur-fitur buat yang jaringannya pas-pasan.

---

## 📸 Penampakan

<p align="center">
  <img src="screenshot-console.png" width="720" alt="NanaPlay Console Mode">
</p>
<p align="center"><em>Console Mode — ala PS5, landscape fullscreen</em></p>

---

## ✨ Fitur Unggulan

| | Fitur | Keterangan |
|---|---|---|
| 🎮 | **Console Mode** | UI ala PS5: backdrop artwork game, strip kartu horizontal, navigasi D-pad, landscape fullscreen imersif |
| 📋 | **Antrian Baru** | Pilih rute pakai ping bar + ETA, layar posisi antrian fullscreen dengan countdown, mini bar yang serasi |
| 🔁 | **Persistent Reconnect** | Auto-reconnect tanpa batas buat jaringan jelek, plus fallback otomatis ke profil hemat bandwidth (< 4 Mbps) |
| 🎵 | **Music Player** | File lokal + streaming online (JioSaavn), bisa diputar di dalam stream bareng suara game |
| 🌐 | **Floating Browser** | WebView ringan di atas stream — buat buka map & guide |
| ⏺️ | **Rekam Stream** | Rekam ke MP4 via Storage Access Framework |
| 📸 | **Screenshot** | Tangkap layar stream pakai PixelCopy, simpan PNG |
| 🔊 | **Low-Latency Audio** | Opsi audio delay rendah |
| 📢 | **Announcements** | Popup pengumuman penting langsung di aplikasi |

---

## 📦 Info Paket

- **Package:** `com.papahchan.nanaplay` — beda dari upstream OpenNOW, jadi bisa **diinstal berdampingan**
- **Label:** NanaPlay
- ⚠️ Kalau sebelumnya pasang NanaPlay paket lama (`com.opencloudgaming.opennow`), uninstall dulu — login aman karena tersimpan di server, tinggal sign in lagi.

---

## 🗂️ Isi Repo

```
.
├── android/                        # Aplikasi Android (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── java/com/opencloudgaming/opennow/
│       │   ├── Nana*.kt            # Fitur NanaPlay (musik, browser, pengumuman…)
│       │   └── OpenNow*.kt         # Basis upstream (streaming, antrian, auth…)
│       └── res/                    # Resources
├── announcements.json              # Feed pengumuman in-app
├── logo.png                        # Ikon aplikasi
├── screenshot-console.png          # Screenshot Console Mode
├── README.md
└── LICENSE
```

---

## 🔨 Cara Build

Butuh: **JDK 17**, Android SDK (compileSdk 37), Gradle 9.x.

```bash
cd android
./gradlew assembleRelease
```

APK jadi di `android/app/build/outputs/apk/release/`. Build release pakai R8 + resource shrinking (~46 MB).

> **Signing:** butuh keystore dengan config `nanaplay`. Jangan commit keystore — lihat `.gitignore`.

---

## 🗒️ Changelog

| Versi | Code | Sorotan |
|-------|------|---------|
| 1.0.37 | 92 | Touch session recovery (dari upstream); recovery fail-fast |
| 1.0.36 | 91 | Music player ditulis ulang: ganti lagu instan, auto-retry, playlist awet |
| 1.0.35 | 90 | Fix gamepad numpuk; URL browser bisa dihapus; replay musik |
| 1.0.34 | 89 | Popup pengumuman via GitHub JSON |
| 1.0.33 | 88 | Search state awet; 50 hasil musik; gamepad restyle; floating drawer |
| 1.0.32 | 87 | Fix parser JioSaavn |
| 1.0.31 | 86 | Musik online pindah ke **JioSaavn** |
| 1.0.26 | 81 | Musik online pertama (YouTube InnerTube) |
| 1.0.25 | 80 | Music player lokal; identitas home NanaPlay; search instan |
| 1.0.24 | 79 | Peringatan antrian macet + retry; auto-retry sesi; preset Low Latency |
| 1.0.23 | 78 | Kartu rekomendasi kompak; status bar ramping |
| 1.0.22 | 77 | Update checker baca release GitHub sendiri |
| 1.0.21 | 76 | Fix crash saat dibuka (manifest) |
| 1.0.20 | 75 | **Paket baru** `com.papahchan.nanaplay`; rilis GitHub pertama |
| 1.0.19 | 74 | **Persistent reconnect** buat jaringan jelek |
| 1.0.17 | 72 | Build release teroptimasi pertama (~46 MB) |
| 1.0.10 | 65 | **Console Mode** pertama |

---

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=FahriAdison/NanaPlay&type=Date)](https://star-history.com/#FahriAdison/NanaPlay&Date)

---

## 🙏 Terima Kasih

- **Fondasi:** [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) oleh [Zortos](https://github.com/OpenCloudGaming) — tanpa ini nggak ada NanaPlay. Kontribusi Android oleh [Kief5555](https://github.com/Kief5555).
- **Recode & desain ulang:** [Papah Chan](https://github.com/FahriAdison)
- **Musik online:** katalog [JioSaavn](https://www.jiosaavn.com) via wrapper API publik ShnwazDev (basis [sumitkolhe/jiosaavn-api](https://github.com/sumitkolhe/jiosaavn-api), MIT).

## 📄 Lisensi

MIT — lihat [LICENSE](LICENSE). Hak cipta asli (c) 2025 Zortos tetap dipertahankan sesuai lisensi.
