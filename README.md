<p align="center">
  <img src="nanaplay-icon.png" width="120" alt="NanaPlay icon">
</p>

<h1 align="center">NanaPlay</h1>

<p align="center">
  <strong>GeForce NOW on Android, with a console feel.</strong><br>
  An open-source Android client for GeForce NOW, rebuilt around how people actually play on phones.
</p>

<p align="center">
  <a href="https://github.com/FahriAdison/NanaPlay/releases/latest">Downloads</a> ·
  <a href="#why-nanaplay">Why NanaPlay</a> ·
  <a href="#features">Features</a> ·
  <a href="#building">Build from source</a> ·
  <a href="#changelog-recent">Changelog</a> ·
  <a href="#community">Community</a>
</p>

<p align="center">
  <a href="https://github.com/FahriAdison/NanaPlay/releases/latest">
    <img src="https://img.shields.io/github/v/release/FahriAdison/NanaPlay?style=for-the-badge&label=Download&color=3DDC84" alt="Download latest release">
  </a>
</p>

<p align="center">
  <a href="https://github.com/codexgpt497-ai/nanaplay-builder/actions/workflows/test.yml">
    <img src="https://github.com/codexgpt497-ai/nanaplay-builder/actions/workflows/test.yml/badge.svg?branch=main" alt="Tests">
  </a>
  <img src="https://img.shields.io/github/downloads/FahriAdison/NanaPlay/total?style=flat-square&label=Downloads" alt="Total downloads">
  <img src="https://img.shields.io/github/stars/FahriAdison/NanaPlay?style=flat-square" alt="GitHub stars">
  <img src="https://img.shields.io/badge/Status-Beta-orange?style=flat-square" alt="Beta">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License">
  <a href="https://discord.gg/TyBrNKnavR">
    <img src="https://img.shields.io/badge/Discord-Community-5865F2?style=flat-square&logo=discord&logoColor=white" alt="Discord community">
  </a>
</p>

---

## Contents

- [Screenshots](#screenshots)
- [Why NanaPlay](#why-nanaplay)
- [Features](#features)
- [Download](#download)
- [FAQ](#faq)
- [Building](#building)
- [Changelog](#changelog-recent)
- [Roadmap](#roadmap)
- [Community](#community)
- [Support](#support)
- [Credits](#credits)
- [Disclaimer](#disclaimer)
- [License](#license)

---

NanaPlay is a community fork of [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) by [Zortos](https://github.com/OpenCloudGaming), recoded for Android by **Papah Chan** ([FahriAdison](https://github.com/FahriAdison)).

It keeps OpenNOW's GeForce NOW streaming core and builds a console-style experience on top: a PS5-inspired interface, touch controls you can rearrange freely, smarter queue handling, and tuning for players on slower or unstable networks.

> [!WARNING]
> **Beta status.** NanaPlay is tested on real devices and works, but it's still beta — expect some rough edges. Bug reports are welcome in [Issues](https://github.com/FahriAdison/NanaPlay/issues) or on Discord.

> [!IMPORTANT]
> **You need your own GeForce NOW account.** Your subscription tier and region determine which games and stream settings are available to you.

---

## Screenshots

<p align="center">
  <img src="screenshot-console.png" width="720" alt="NanaPlay Console Mode">
</p>
<p align="center"><em>Console Mode — PS5-style interface in landscape</em></p>

---

## Why NanaPlay

Same GeForce NOW streaming core as OpenNOW — a different experience on top:

|  | NanaPlay | OpenNOW (Android) |
|---|---|---|
| Interface | PS5-style Console Mode, D-pad navigation | Standard layout |
| Queue | Redesigned queue with route picker, ping bars, live countdown | Standard queue |
| Poor networks | Persistent reconnect with backoff, low-bandwidth fallback under ~4 Mbps | Standard reconnect |
| Touch controls | Fully rearrangable layout, Quick Bar, remappable overlay shortcut | Fixed touch layout |
| Updates | In-app updater via GitHub Releases + in-app announcements | GitHub Releases |

---

## Features

**Play your way**
- **Console Mode** — PS5-style interface: game artwork backdrops, horizontal card strips, D-pad navigation, immersive fullscreen landscape and portrait layouts.
- **Custom touch layout** — drag buttons and sticks anywhere in edit mode, lock the layout to prevent accidents, reset with confirmation.
- **Quick Bar** — floating in-stream menu for screenshots, recording, the floating browser, music, keyboard, and touch-control toggles.
- **Custom overlay shortcut** — remap the overlay trigger (START/SELECT, Guide, L1+R1, L3+R3…) with adjustable hold timing.

**Built for real networks**
- **Queue redesign** — route picker with ping bars, fullscreen queue position screen with live countdown.
- **Persistent reconnect** — optional unlimited auto-reconnect with backoff, plus automatic fallback to a low-bandwidth profile for connections under ~4 Mbps.
- **Adaptive network adjustment** — automatic quality/latency tuning based on measured conditions.

**Extras**
- **Music player** — local files plus online streaming, with notification and lock-screen controls; works outside and inside the stream.
- **Floating browser** — lightweight WebView over the stream for maps and guides.
- **Stream recording & screenshots** — record to MP4, capture PNGs.
- **Low-latency audio toggle**, **maintenance/patching banners** with pre-play confirmation, in-app announcements.

---

## Download

Grab the latest APK from the [Releases page](https://github.com/FahriAdison/NanaPlay/releases/latest). The in-app updater will notify you when a new version is out.

- **Package:** `com.papahchan.nanaplay` — different from upstream OpenNOW, so both can be installed side-by-side.
- If you previously installed a NanaPlay build under the old package (`com.opencloudgaming.opennow`), uninstall it first. Your GeForce NOW login is server-side, so just sign in again.
- **Minimum Android:** 6.0 (API 23).

### APK Info

Verify your download is really from Papah Chan. The release APK is signed with the NanaPlay key — check the certificate fingerprint:

```
SHA-256: EF:0A:A2:68:4A:45:F5:3C:4E:98:C7:7E:B1:AB:E8:01:53:7A:DB:BA:81:77:30:B6:B6:B8:6C:4C:EE:4F:D6:E2
```

```bash
apksigner verify --print-certs NanaPlay-1.0.106.apk
```

If the fingerprint does not match, do not install the file — get the APK from the Releases page above.

---

## FAQ

**Do I need a GeForce NOW account?**
Yes — your own account. Your subscription tier and region decide which games and stream settings you can use.

**Can I install NanaPlay alongside OpenNOW?**
Yes. NanaPlay uses the package `com.papahchan.nanaplay`, different from OpenNOW, so both live side-by-side.

**What Android version do I need?**
Android 6.0 (API 23) or newer.

**I keep getting "Server tidak merespons" / "Server not responding". Is it the app?**
Usually not. Since late October 2026 this has mostly been a server-side GeForce NOW issue affecting third-party clients (including OpenNOW) — the official GFN app sometimes still works while it lasts. End the stream normally and try again later. If NanaPlay itself misbehaves, report it (see below) — the 1.0.106 update also added launch diagnostics to help trace these incidents.

**How do I report a bug?**
In the app: Settings → Share diagnostics, then send the link to Papah. Or open an [Issue](https://github.com/FahriAdison/NanaPlay/issues) / ask on [Discord](https://discord.gg/TyBrNKnavR). Include your build, device, and steps to reproduce — and the diagnostics link for streaming bugs.

**Is NanaPlay on the Play Store?**
No. Download the APK from the [Releases page](https://github.com/FahriAdison/NanaPlay/releases/latest); the in-app updater will tell you when a new version is out.

---

## Building

Requirements: **JDK 17**, Android SDK (compileSdk 37), Gradle 9.x.

```bash
cd android
./gradlew assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. Release builds use R8 minification and resource shrinking.

> **Signing:** release builds expect a keystore configured as the `nanaplay` signing config. Never commit keystores — see `.gitignore`.

Repository layout:

```
.
├── android/                  # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       ├── java/com/opencloudgaming/opennow/
│       │   ├── Nana*.kt      # NanaPlay features (music, browser, announcements…)
│       │   └── OpenNow*.kt   # Upstream base (streaming, queue, auth…)
│       └── res/              # Resources
├── announcements.json        # In-app announcement feed
├── nanaplay-icon.png         # App icon
├── screenshot-console.png    # Console Mode screenshot
├── README.md
└── LICENSE
```

---

## Changelog (recent)

| Version | Highlights |
|---------|------------|
| 1.0.106 | Launch identity diagnostics; retry selected video profile on first video-offer timeout; CloudMatch launch-mode headers |
| 1.0.105 | Queue retry no longer races the session stop (no more false "Active session found") |
| 1.0.80 | Fixed touch-layout edits being wiped when dragging the quick bar, keyboard bar, or control panel |
| 1.0.79 | Layout lock toggle + reset confirmation; analog stick auto-recenter |
| 1.0.78 | Fixed false APK signature-mismatch error on Android 9 |
| 1.0.77 | Customizable overlay shortcut; updated About page |
| 1.0.76 | Maintenance/patching warning banner with pre-play confirmation |
| 1.0.75 | Session management (ported from upstream) |

Full history: [Releases](https://github.com/FahriAdison/NanaPlay/releases).

---

## Roadmap

- Music player hardening pass
- Per-game settings memory
- Smarter low-power Stream warning (no more false alarms on wide 720p)
- "Tombol Bebas" — free-place keyboard overlay

---

## Community

Chat with other players, share feedback, and get help on the **NanaPlay Community** Discord:

<p align="center">
  <a href="https://discord.gg/TyBrNKnavR">
    <img src="https://img.shields.io/badge/Discord-Join%20the%20NanaPlay%20Community-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Join our Discord">
  </a>
</p>

---

## Support

NanaPlay is free and open-source, built in spare time with the community. If it makes your cloud gaming better, consider supporting development:

<p align="center">
  <a href="https://sociabuzz.com/papahchan/tribe">
    <img src="https://img.shields.io/badge/Support_via-Sociabuzz-FF6B6B?style=for-the-badge&logo=heart&logoColor=white" alt="Support via Sociabuzz">
  </a>
</p>

---

## Credits

- **Upstream:** [OpenNOW](https://github.com/OpenCloudGaming/OpenNOW) by [Zortos](https://github.com/OpenCloudGaming) — the foundation of everything here. Android contributions by [Kief5555](https://github.com/Kief5555).
- **Recode & redesign:** [Papah Chan](https://github.com/FahriAdison)
- **Online music:** [JioSaavn](https://www.jiosaavn.com) catalog via the ShnwazDev public API wrapper (based on [sumitkolhe/jiosaavn-api](https://github.com/sumitkolhe/jiosaavn-api), MIT).

## Disclaimer

NanaPlay is an independent community project. It is not affiliated with, endorsed by, or sponsored by NVIDIA. GeForce NOW is a trademark of NVIDIA Corporation.

## License

MIT License — see [LICENSE](LICENSE). Original copyright (c) 2025 Zortos, retained as required by the license.
