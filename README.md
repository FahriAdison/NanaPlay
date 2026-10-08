<p align="center">
  <img src="nanaplay-icon.png" width="120" alt="NanaPlay icon">
</p>

<h1 align="center">🎮 NanaPlay</h1>

<p align="center">
  <strong>GeForce NOW on Android, with a console feel.</strong><br>
  <em>An open-source Android client for GeForce NOW — reimagined.</em>
</p>

<p align="center">
  <a href="https://github.com/FahriAdison/NanaPlay/releases/latest">
    <img src="https://img.shields.io/github/v/release/FahriAdison/NanaPlay?style=for-the-badge&label=DOWNLOAD&color=3DDC84" alt="Download">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Beta-orange?style=flat-square" alt="Beta">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/github/stars/FahriAdison/NanaPlay?style=flat-square" alt="stars">
</p>

> **NanaPlay is in BETA.** Tested on real devices and working — but it's still beta, so expect some rough edges. Bug reports are always welcome!

NanaPlay is a community fork of [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) by [Zortos](https://github.com/OpenCloudGaming), rebuilt for Android by **Papah Chan** ([FahriAdison](https://github.com/FahriAdison)).

It keeps OpenNOW's solid GeForce NOW streaming, and adds a console-style experience, smarter queue handling, and features for players on slower networks.

---

## 📸 Screenshots

<p align="center">
  <img src="screenshot-console.png" width="720" alt="NanaPlay Console Mode">
</p>
<p align="center"><em>Console Mode — PS5-style interface, immersive landscape</em></p>

---

## ✨ Features

| | Feature | Details |
|---|---|---|
| 🎮 | **Console Mode** | PS5-style UI: game artwork backdrops, horizontal card strips, D-pad navigation, immersive fullscreen landscape |
| 📋 | **Queue Redesign** | Route picker with ping bars & ETAs, fullscreen queue position screen with live countdown, matching minimized bar |
| 🔁 | **Persistent Reconnect** | Optional unlimited auto-reconnect with backoff for poor networks, plus automatic fallback to a low-bandwidth profile (great for < 4 Mbps) |
| 🎵 | **Music Player** | Local files + online streaming (JioSaavn), playable outside and inside the stream |
| 🌐 | **Floating Browser** | Lightweight WebView over the stream — for maps & guides |
| ⏺️ | **Stream Recording** | Record to MP4 via Storage Access Framework |
| 📸 | **Screenshot** | Capture the stream with PixelCopy, saved as PNG |
| 🔊 | **Low-Latency Audio** | Toggle for reduced audio delay |
| 📢 | **Announcements** | In-app popups for important updates |

---

## 📦 Package Info

- **Package:** `com.papahchan.nanaplay` — different from upstream OpenNOW, so it can be installed **side-by-side**
- **Label:** NanaPlay
- ⚠️ If you previously installed a NanaPlay build with the old package (`com.opencloudgaming.opennow`), uninstall it first — your login is server-side, just sign in again.

---

## 🗂️ Repository Layout

```
.
├── android/                        # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── java/com/opencloudgaming/opennow/
│       │   ├── Nana*.kt            # NanaPlay features (music, browser, announcements…)
│       │   └── OpenNow*.kt         # Upstream base (streaming, queue, auth…)
│       └── res/                    # Resources
├── announcements.json              # In-app announcement feed
├── nanaplay-icon.png               # App icon
├── screenshot-console.png          # Console Mode screenshot
├── README.md
└── LICENSE
```

---

## 🔨 Building

Requirements: **JDK 17**, Android SDK (compileSdk 37), Gradle 9.x.

```bash
cd android
./gradlew assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. Release builds use R8 minification + resource shrinking (~46 MB).

> **Signing:** release builds expect a keystore configured as the `nanaplay` signing config. Never commit keystores — see `.gitignore`.

---

## 🗒️ Changelog

| Version | Code | Highlights |
|---------|------|------------|
| 1.0.37 | 92 | Touch session recovery (ported from upstream); recovery fail-fast |
| 1.0.36 | 91 | Music player rework: instant switching, auto-retry, persistent playlist |
| 1.0.35 | 90 | Gamepad overlap fix; browser URL fix; music replay fix |
| 1.0.34 | 89 | In-app announcement popup via GitHub JSON |
| 1.0.33 | 88 | Search state persistence; 50 music results; gamepad restyle; floating drawer |
| 1.0.32 | 87 | JioSaavn parser fix |
| 1.0.31 | 86 | Online music backend switched to **JioSaavn** |
| 1.0.26 | 81 | First online music (YouTube InnerTube) |
| 1.0.25 | 80 | Local music player; NanaPlay home identity; instant search |
| 1.0.24 | 79 | Queue stuck warning + retry; session auto-retry; Low Latency preset |
| 1.0.23 | 78 | Compact recommended card; slim status bar |
| 1.0.22 | 77 | Update checker reads own GitHub releases |
| 1.0.21 | 76 | Critical launch-crash fix |
| 1.0.20 | 75 | **New package** `com.papahchan.nanaplay`; first GitHub release |
| 1.0.19 | 74 | **Persistent reconnect** for poor networks |
| 1.0.17 | 72 | First optimized release build (~46 MB) |
| 1.0.10 | 65 | First **Console Mode** |

---

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=FahriAdison/NanaPlay&type=Date)](https://star-history.com/#FahriAdison/NanaPlay&Date)

---

## 🙏 Credits

- **Upstream:** [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) by [Zortos](https://github.com/OpenCloudGaming) — the foundation of everything here. Android contributions by [Kief5555](https://github.com/Kief5555).
- **Recode & redesign:** [Papah Chan](https://github.com/FahriAdison)
- **Online music:** [JioSaavn](https://www.jiosaavn.com) catalog via the ShnwazDev public API wrapper (based on [sumitkolhe/jiosaavn-api](https://github.com/sumitkolhe/jiosaavn-api), MIT).

## 📄 License

MIT License — see [LICENSE](LICENSE). Original copyright (c) 2025 Zortos, retained as required by the license.
