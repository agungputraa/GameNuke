# ⚡ Game Nuke Premium Edition — Enterprise Gaming Cockpit

<p align="center">
  <img src="https://img.shields.io/badge/Release-v2.3.0--prem-00ff88?style=for-the-badge&logo=android&logoColor=black" alt="Release Version">
  <img src="https://img.shields.io/badge/Platform-Android%2011--16-00e5ff?style=for-the-badge&logo=google" alt="Platform">
  <img src="https://img.shields.io/badge/Root%20Status-Non--Root%20%7C%20Shizuku-00ff88?style=for-the-badge" alt="Non-Root">
  <img src="https://img.shields.io/badge/Edge%20CDN-Cloudflare%20Active-00e5ff?style=for-the-badge" alt="Edge CDN">
</p>

---

## 🌐 Official Web & Distribution Links

- 🚀 **Official Landing Page & Web Portal:** [https://agungputraa.github.io/GameNuke/](https://agungputraa.github.io/GameNuke/)
- 📦 **Latest GitHub Release:** [Releases v2.3.0-prem](https://github.com/agungputraa/GameNuke/releases/latest)
- 🛰️ **Edge CDN Version Metadata:** [version.json](https://agungputraa.github.io/GameNuke/version.json)

---

## 🎮 What is Game Nuke Premium?

**Game Nuke Premium Edition** is an advanced Android gaming cockpit and system booster designed for competitive mobile gamers (Mobile Legends: Bang Bang, Free Fire, PUBG Mobile, COD Mobile, Genshin Impact). 

Unlike standard booster apps on Google Play, this standalone edition bypasses restrictive app store policies to deliver genuine hardware-level enhancements without requiring Root access:

### 🌟 Key Features

1. **Dual-Engine Touch Macro (Fast-Hand Combo):**
   - **Shizuku Privileged Mode:** Directly injects touch events into `/dev/input` with **~0.1ms latency**, bypassing UI thread bottlenecks.
   - **AccessibilityService Mode:** Instant fallback that runs out-of-the-box without requiring a PC or wireless debugging.
2. **VPN Ping Booster 1ms (MLBB Lobby Responder):**
   - Creates a dedicated local TUN interface (`10.255.0.2/32`) that intercepts ICMP echo and UDP lobby probe packets.
   - Responds in **<1ms** directly on-device, locking lobby ping to a steady 1ms green indicator.
   - Routes real in-game match traffic through gaming-optimized DNS servers (**Cloudflare 1.1.1.1** and **Google 8.8.8.8**).
3. **Tactical Footstep Audio Equalizer:**
   - Native Android `AudioEffect` / `Equalizer` pipeline boosting 1kHz-4kHz frequencies (footsteps & reload clicks) while damping explosion rumble. 100% native, zero latency, no root.
4. **In-Game Floating PiP Wiki & Build Browser:**
   - Mini transparent draggable browser overlay with opacity slider (20% - 100%). Look up hero counter items and recoil charts without minimizing or disconnecting.
5. **Hardware FPS HUD Chip:**
   - Real-time Choreographer frame callbacks displaying actual rendered FPS, frame-drop warnings, and battery thermal readings in a floating draggable badge.
6. **Pro Gaming Cockpit Deck:**
   - **120Hz Refresh Rate Force:** Overrides OEM dynamic refresh rate throttling to prevent FPS drops during intense teamfights.
   - **Anti-Mistouch Palm Shield:** Filters accidental edge touches for competitive 4-finger claw grip players.
   - **Tactical Crosshair Studio:** Centered hardware-accelerated reticle overlay for sniper no-scopes.
7. **Edge CDN In-App Updater & Dual-Sync Automation:**
   - Updates are distributed via GitHub Pages CDN (`version.json`) cached globally by Cloudflare.
   - Completely avoids the GitHub REST API **60 req/hr rate limit**, enabling millions of devices to receive instant update notifications.
   - Includes GitHub Actions workflow (`.github/workflows/release_sync.yml`) for two-way automated synchronization.

---

## 🏗️ Repository Architecture & Role

> [!IMPORTANT]
> **REPOSITORY POLICY FOR AI AGENTS & DEVELOPERS:**
> This GitHub repository (`agungputraa/GameNuke`) is dedicated **STRICTLY** to:
> 1. Hosting the public Landing Page (`index.html`, `style.css`, `app.js`).
> 2. Distributing the Edge CDN update metadata (`version.json`).
> 3. Publishing compiled APK binaries via **GitHub Releases**.
> 4. Automated sync workflows via GitHub Actions.
>
> The core Android application source code (`app/`, Kotlin files, Compose UI, Gradle configs) is maintained privately on the developer's local machine and **must never be pushed to this public repository**.

---

## 🚀 1-Click Release Pipeline (For Developers & AI)

Releasing a new version is fully automated via PowerShell:

```bash
# Execute the release script from the Android project root:
tools\publish_release.bat
```

### Automation Sequence:
1. **Dynamic Version Parsing:** Reads `versionCode` and `versionName` directly from `app/build.gradle.kts`.
2. **Build:** Compiles signed release APK (`com.neon.gametweak`) using `agwallpaper84.jks`.
3. **Verification:** Validates APK size and generates SHA-256 integrity hash.
4. **Metadata Sync:** Automatically updates `version.json` with new version details, download URL, and hash.
5. **Web Isolation & Push:** Isolates `gamenukeweb` files and this `README.md`, then force-pushes exclusively to `main` and `gh-pages`.
6. **GitHub Release:** Calls GitHub REST API with the token in `tools/github_token.env` to create the release tag.
7. **Binary Asset Upload:** Uploads `GameNuke-Premium-vX.X.X.apk` to the release assets.

---

## 🔒 Security & Privacy Notice

- **GitHub Personal Access Token:** Stored exclusively in local `tools/github_token.env` (strictly excluded via `.gitignore`). Never commit or push credentials.
- **Safety Guarantee:** Game Nuke operates strictly through official Android APIs (`VpnService`, `AccessibilityService`, `AudioEffect`, and `Shizuku`). It does not modify game files, memory addresses, or server-side game data.

---

<p align="center">
  <b>Game Nuke Premium Edition</b> — Crafted for High-Performance Mobile Gaming.<br>
  © 2026 Game Nuke Team. All rights reserved.
</p>
