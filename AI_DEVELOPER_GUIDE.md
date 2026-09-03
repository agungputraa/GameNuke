# 🤖 GAME NUKE PREMIUM — ULTIMATE AI & DEVELOPER BLUEPRINT

> **CRITICAL MANDATE FOR ALL AI ASSISTANTS & FUTURE ENGINEERS:**
> Read this document from top to bottom before touching, editing, building, or committing any code in this repository.
> It documents the exact architecture, security rules, anti-leak protocols, monetization bindings, and automation pipelines of **Game Nuke Premium Edition**.

---

## ⚡ 1. Emergency 30-Second Quickstart

* **What is Game Nuke?** A high-performance Android gaming cockpit with sub-millisecond touch macro, 1ms MLBB loopback ping responder, floating wiki guides, tactical audio equalizer, and hardware FPS overlay.
* **Where does the code live?**
  * `c:\ProyekAndroid\GameNukePrem\` ➔ **STRICTLY PRIVATE / LOCAL ONLY**. Contains proprietary Android Kotlin source code, signing keystore (`agwallpaper84.jks`), and Gradle build system. **NEVER push this directory to public GitHub!**
  * `c:\ProyekAndroid\GameNukePrem\gamenukeweb\` ➔ **PUBLIC REPOSITORY** (`https://github.com/agungputraa/GameNuke.git`). Contains the Tailwind CSS + Alpine.js landing page, `version.json` (Edge CDN update metadata), `.github/workflows/`, and compiled APK releases.
* **How do I release a new version?**
  1. Bump `versionCode` (e.g., `16`) and `versionName` (e.g., `"2.3.0-prem"`) in [`app/build.gradle.kts`](file:///c:/ProyekAndroid/GameNukePrem/app/build.gradle.kts).
  2. Run `tools\publish_release.bat`.
  3. Everything else (compile, SHA256 hash, update `version.json`, deploy web to GitHub Pages, create GitHub release, upload binary APK) is **100% automated**.

---

## 🔒 2. Cardinal Security & Monetization Invariants ("Anti-Bongkar-Dapur")

### 🔴 INVARIANT 1: Strict Source Code Isolation
Under NO circumstances should the Android source code (`app/`, `build.gradle.kts`, `gradle/`, `*.kt`, `*.xml`) be committed or pushed to the GitHub repository `agungputraa/GameNuke`.
- The remote repository is public and is reserved **exclusively** for the Landing Page web files, update metadata, and GitHub Releases.
- The automation script [`tools/publish_release.ps1`](file:///c:/ProyekAndroid/GameNukePrem/tools/publish_release.ps1) isolates `gamenukeweb` into an independent git tree and pushes only web files.
- The root Android project folder has its remote origin removed to prevent accidental leakage.

### 🔴 INVARIANT 2: Confidentiality of GitHub Token
- The GitHub personal access token (`ghp_...`) is kept strictly inside [`tools/github_token.env`](file:///c:/ProyekAndroid/GameNukePrem/tools/github_token.env).
- This file is ignored by `.gitignore`. Never output, commit, or push this token.

### 🔴 INVARIANT 3: Preserve Package Name and Signing Keystore
- **Package Name:** `com.neon.gametweak`
- **Signing Keystore:** `agwallpaper84.jks` (Key alias: `agwallpaper`)
- **Reason:** The app's Liftoff Monetize (Vungle Ads) publisher account is bound to `com.neon.gametweak`. Changing the package name or signing certificate will break ad fill rates and invalidate monetization revenues (`6a8dd7d372786c9ba4de1adc`, etc.).

---

## 🏗️ 3. Core Engine Architecture & File Mapping

```
c:\ProyekAndroid\GameNukePrem\
├── app\src\main\java\com\neon\gametweak\
│   ├── NukeMacroController.kt       ── Dual-Engine Macro (Shizuku 0ms privileged + Accessibility fallback)
│   ├── NukeMacroService.kt          ── Android AccessibilityService touch gesture injector
│   ├── NukeVpnService.kt            ── Local Loopback 1ms ICMP/UDP TUN responder + Gaming DNS
│   ├── NukeAudioBooster.kt          ── Native AudioEffect Equalizer (Footstep & Gunshot Enhancer)
│   ├── NukeWikiOverlayView.kt       ── Floating PiP Mini Browser with transparency slider & bookmarks
│   ├── NukeFpsOverlayView.kt        ── Real Choreographer live FPS & thermal floating chip
│   ├── NukeAppUpdater.kt            ── Edge CDN in-app updater (Bypasses GitHub 60 req/hr rate limit)
│   ├── AppUpdateController.kt       ── Unified update orchestrator (CDN + Google Play fallback)
│   ├── FloatingHudCompose.kt        ── Jetpack Compose Floating Cockpit UI (Pro Gaming Matrix Deck)
│   ├── FloatingBoosterService.kt    ── Foreground Service managing floating window overlays
│   └── NukeModuleCatalog.kt         ── Central registry of all active tools and modules
│
├── gamenukeweb\                     ── Deployed to GitHub main & gh-pages
│   ├── index.html                   ── Cyberpunk Landing Page (Tailwind CSS CDN, Alpine.js, Font Awesome)
│   ├── app.js                       ── Alpine.js reactive app, telemetry simulation, masked blob streamer
│   ├── style.css                    ── Scanline textures and cyber glow utility classes
│   ├── version.json                 ── Static JSON metadata served via Cloudflare Global Edge CDN
│   ├── README.md                    ── Public GitHub documentation for visitors
│   └── .github\workflows\           ── GitHub Actions
│       └── release_sync.yml         ── Automatic sync: updates version.json if release is made on GitHub
│
├── tools\
│   ├── github_token.env             ── [SECRET] Holds GITHUB_TOKEN (Gitignored)
│   ├── publish_release.bat          ── 1-Click release runner for Windows
│   └── publish_release.ps1          ── PowerShell release orchestrator with strict web isolation
└── release-apk\                     ── Destination folder for signed APK outputs
```

---

## 🎮 4. Deep Dive: The 5 Pro Gaming Engines

### 1. Dual-Engine Macro (`NukeMacroController.kt` & `NukeMacroService.kt`)
* **Shizuku Mode (~0.1ms Latency):** When privileged access is detected (`NukeConnectionManager.isConnected()`), touch injection executes directly via root/shell `/system/bin/input tap X Y`, bypassing Android framework touch throttling.
* **Accessibility Mode (~35ms Latency):** When Shizuku is not running, falls back to `AccessibilityService.dispatchGesture()`. Zero setup required for casual users.
* Includes coordinate bounds clamping (`clampX`, `clampY`), delay intervals, and multi-tap loops.

### 2. 1ms VPN Ping Booster (`NukeVpnService.kt`)
* **The Problem:** In Mobile Legends, high ping causes match delay and ping jitter.
* **The Solution:** Creates an on-device virtual TUN interface (`10.255.0.2/32`). When the game sends ICMP ping probe packets, `NukeVpnService` intercepts them on the local TUN interface and responds immediately in `< 1ms`.
* **Real Game Routing:** Real game match traffic is forwarded through high-speed gaming DNS resolvers (**Cloudflare 1.1.1.1** and **Google 8.8.8.8**) with MTU 1400 tuning.

### 3. Tactical Footstep Equalizer (`NukeAudioBooster.kt`)
* Uses Android's native `android.media.audiofx.Equalizer` and `LoudnessEnhancer` on AudioSession 0 (global system mix).
* Cuts sub-bass rumble (<300Hz) from explosions and amplifies 1kHz - 4kHz frequencies where enemy footsteps, grass rustling, and weapon reload clicks reside.
* Runs 100% natively without Root.

### 4. Floating PiP Wiki Browser (`NukeWikiOverlayView.kt`)
* Spawns a floating, draggable `WebView` overlay with opacity control (slider from 20% to 100%).
* Gamers can check counter item builds (e.g. Athena's Shield vs Radiant Armor in MLBB) without leaving the game or risking AFK disconnects.
* Includes quick bookmark buttons and minimize bubble.

### 5. Hardware-Accurate FPS Chip (`NukeFpsOverlayView.kt`)
* Hooks into Android's `Choreographer.postFrameCallback()` to calculate genuine rendered frame rate every second.
* Displays current FPS, color-coded stability (Neon Green >= 90, Cyan >= 55, Red < 55), and real-time battery thermal data.

---

## 🛰️ 5. Edge CDN In-App Updater & Dual-Sync Automation

### The 60 Requests/Hour Rate Limit Problem
If an app directly queries GitHub's REST API endpoint (`https://api.github.com/repos/.../releases/latest`), users will quickly hit GitHub's IP rate limit of **60 requests per hour**, resulting in `403 Forbidden` errors and broken in-app updates.

### The Solution: Cloudflare Edge CDN Metadata
1. The app queries `https://agungputraa.github.io/GameNuke/version.json` with a cache-buster query.
2. Because GitHub Pages is edge-cached by Cloudflare, this endpoint supports **millions of concurrent hits** with zero rate-limit restrictions.
3. The app compares `versionCode` (e.g. 16 > 15). If an update exists, it downloads the APK binary with progress tracking and launches the official `PackageInstaller` intent via `FileProvider`.

### Dual-Sync Release Automation
* **Method 1 (Recommended):** Run `tools\publish_release.bat`. It reads the version from `build.gradle.kts`, compiles the APK, hashes it with SHA-256, updates `version.json`, force-pushes web files to `main` & `gh-pages`, creates the GitHub release, and uploads the APK binary asset.
* **Method 2 (GitHub Web UI):** If a release is created manually on GitHub, the GitHub Actions workflow [`.github/workflows/release_sync.yml`](file:///c:/ProyekAndroid/GameNukePrem/gamenukeweb/.github/workflows/release_sync.yml) automatically extracts the release asset, updates `version.json`, and commits to `gh-pages` and `main`.

---

## 🛠️ 6. How to Add a New Feature in 3 Steps

If a future developer or AI wants to add a new tool to the floating HUD:

1. **Step 1: Create the Controller/Engine**
   Create a new file in `app/src/main/java/com/neon/gametweak/` (e.g., `NukeNewTool.kt`).
2. **Step 2: Register in Catalog**
   Add a new entry to `NukeModuleCatalog.modules` in [`NukeModuleCatalog.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/NukeModuleCatalog.kt).
3. **Step 3: Connect to UI & Action Dispatcher**
   - Add a `SquareMiniCard` in [`FloatingHudCompose.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/FloatingHudCompose.kt) under `PRO GAMING MATRIX`.
   - Add the action handler in `FloatingBoosterService.kt` under `onQuickAction(...)`.

---

## 📋 7. Common Troubleshooting Checklist

| Issue | Cause | Fix |
| :--- | :--- | :--- |
| **`assembleRelease` fails with Keystore error** | Missing signing credentials in `release.properties` | Verify `storePassword`, `keyAlias=agwallpaper`, and `keyPassword` exist in `release.properties`. |
| **Shizuku Macro shows "Permission Denied"** | Shizuku app is not running on device | Guide user to launch Shizuku and start service via Wireless Debugging, or let the app automatically fallback to `AccessibilityService`. |
| **VPN Ping Booster doesn't activate** | User has not accepted Android VPN dialog | `NukeVpnService.prepare(context)` returns an Intent; start it with `FLAG_ACTIVITY_NEW_TASK` to show system prompt. |
| **Source code leaked to GitHub** | Someone ran `git push origin main` from root | Immediately run `tools\publish_release.bat` to overwrite remote `main` with the isolated web directory. |
