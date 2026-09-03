# 🤖 GAME NUKE PREMIUM — AI & DEVELOPER BLUEPRINT

> **IMPORTANT NOTICE FOR FUTURE AI ASSISTANTS & DEVELOPERS:**
> Read this document thoroughly before performing any modifications, builds, or releases. It documents the core architecture, security boundaries, monetization rules, and automation pipeline of **Game Nuke Premium Edition**.

---

## 1. Executive Architecture Overview

Game Nuke is divided into two distinct repositories/zones:

| Component | Physical Location | Git Remote Policy | Description |
| :--- | :--- | :--- | :--- |
| **Android Core App** | `c:\ProyekAndroid\GameNukePrem` | **STRICTLY PRIVATE / LOCAL ONLY** | Proprietary Kotlin/Compose Android app (`com.neon.gametweak`). **NEVER** push to public GitHub! |
| **Web & Releases** | `c:\ProyekAndroid\GameNukePrem\gamenukeweb` | **PUBLIC GITHUB** (`agungputraa/GameNuke`) | Hosts the Cyberpunk Landing Page (Tailwind+Alpine.js), `version.json` (Edge CDN), and GitHub Releases (.apk). |

---

## 2. Critical Rules & Guardrails

### ⚠️ RULE 1: Never Push Android Source to GitHub
The remote repository `https://github.com/agungputraa/GameNuke.git` is **PUBLIC**. It must **ONLY** contain:
- Landing Page (`index.html`, `style.css`, `app.js`)
- Update metadata (`version.json`)
- Public `README.md`
- Releases (.apk binary assets)

**NEVER** run `git push origin main` from the root directory `c:\ProyekAndroid\GameNukePrem`. Always use the automated publisher script: `tools\publish_release.bat`.

### ⚠️ RULE 2: Never Expose GitHub Token
The GitHub personal access token (`ghp_...`) is stored in `tools/github_token.env`. This file is gitignored (`.gitignore`). Never hardcode or commit tokens to any git branch or public file.

### ⚠️ RULE 3: Never Change Package Name or Keystore
- **Package Name:** `com.neon.gametweak`
- **Signing Keystore:** `agwallpaper84.jks` (Key alias: `agwallpaper`)
- **Reason:** Preserves the existing **Liftoff Monetize (Vungle Ads)** publisher account and placement IDs (`6a8dd7d372786c9ba4de1adc`, etc.) so ad revenue continues flowing without requiring app re-verification.

---

## 3. Android Core Engines Explained

### A. Dual-Engine Macro System
- **Files:**
  - [`NukeMacroService.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/NukeMacroService.kt): Extends `AccessibilityService`. Dispatches touch taps and swipes using Android's `dispatchGesture()` API.
  - [`NukeMacroController.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/NukeMacroController.kt): Coordinates execution between two modes:
    1. **Privileged Mode (Shizuku):** If Shizuku / privileged ADB connection is detected (`NukeConnectionManager.isConnected()`), it injects input directly via root/shell (`/system/bin/input tap X Y`) with **~0.1ms latency**, bypassing UI thread queue limits.
    2. **Accessibility Fallback:** If Shizuku is not running, it gracefully falls back to `NukeMacroService.dispatchTap()`.
  - Includes coordinate clamping to screen bounds (`clampX`, `clampY`), loop intervals, and combo macro profiles.

### B. VPN Ping Booster 1ms (Local Loopback Responder)
- **File:** [`NukeVpnService.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/NukeVpnService.kt)
- **How it works:**
  1. Establishes an Android `VpnService` with a TUN interface (MTU 1400, address `10.255.0.2/32`).
  2. Sets DNS to Cloudflare (`1.1.1.1`) and Google (`8.8.8.8`) for low latency gaming DNS routing.
  3. Spawns a background thread reading IP packets from the TUN interface file descriptor.
  4. Intercepts incoming ICMP Echo Requests (pings) and UDP probe packets sent by Mobile Legends / game lobbies.
  5. Instantly constructs an ICMP Echo Reply / loopback response and writes it back into the TUN descriptor in **< 1ms**, locking the lobby ping display to 1ms green.
  6. Handles `onRevoke()` and clean resource shutdown.

### C. Unlimited Edge CDN In-App Updater
- **Files:**
  - [`NukeAppUpdater.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/NukeAppUpdater.kt)
  - [`AppUpdateController.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/AppUpdateController.kt)
- **Rate Limit Bypass Technique:**
  Directly calling the GitHub REST API (`https://api.github.com/repos/.../releases/latest`) from thousands of app instances triggers the **60 requests/hour IP rate limit** (HTTP 403).
  **Solution:** The app queries static metadata `https://agungputraa.github.io/GameNuke/version.json` cached across Cloudflare edge servers. This provides **unlimited hits** with 0ms rate limiting.
  When an update is detected:
  1. Compares `versionCode` (e.g. 15 vs 14).
  2. Streams APK download with progress callback.
  3. Checks `canRequestPackageInstalls()`.
  4. Launches `FileProvider` (`com.neon.gametweak.fileprovider`) `ACTION_VIEW` intent with `application/vnd.android.package-archive` MIME type.

### D. Floating Cockpit HUD Deck
- **Files:**
  - [`FloatingHudCompose.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/FloatingHudCompose.kt)
  - [`FloatingBoosterService.kt`](file:///c:/ProyekAndroid/GameNukePrem/app/src/main/java/com/neon/gametweak/FloatingBoosterService.kt)
- The Right Wing contains the **PRO GAMING MATRIX**:
  - `MACRO`: Toggles dual-engine touch macro.
  - `PING 1MS`: Toggles `NukeVpnService`.
  - `120 HZ`: Overrides dynamic display refresh rate.
  - `MISTOUCH`: Activates edge touch suppression for 4-finger claw grip.
  - `AIM HUD`: Toggles customizable floating crosshair overlay.
  - `UPDATE`: Triggers on-demand in-app Edge CDN update check.

---

## 4. 1-Click Release & Publishing Workflow

The entire build and release lifecycle is fully automated:

### How to Run Release:
```powershell
# From project root:
tools\publish_release.bat
```

### What `tools\publish_release.ps1` does automatically:
1. **Compiles Release APK:** Runs `gradlew.bat assembleRelease` and verifies signed output in `release-apk/GameNuke-Premium-vX.X.X.apk`.
2. **Calculates Integrity:** Hashes APK with SHA-256 and records exact file size in MB.
3. **Updates Metadata:** Writes new `versionCode`, `versionName`, `sha256`, `apkSizeMb`, and `downloadUrl` into `gamenukeweb/version.json`.
4. **Isolates Distribution Files:** Bundles **ONLY** `gamenukeweb/*`, `version.json`, and public `README.md` into an isolated git tree.
5. **Pushes to GitHub:**
   - Force pushes web bundle to `origin/main`.
   - Force pushes web bundle to `origin/gh-pages`.
   - **Crucial:** Root Android Studio source files are **NEVER** pushed!
6. **Creates GitHub Release:** Calls GitHub REST API with token from `tools/github_token.env` to create tag `vX.X.X` and release notes.
7. **Uploads APK Binary Asset:** Streams the compiled APK binary directly to the release asset endpoint.

---

## 5. Directory Structure Reference

```
c:\ProyekAndroid\GameNukePrem\
├── app\                                # [PRIVATE] Android Kotlin/Compose source code
│   └── src\main\
│       ├── java\com\neon\gametweak\   # Core engines (Macro, VPN, HUD, Updater, Shizuku)
│       └── res\                        # Layouts, drawables, strings, XML configs
├── gamenukeweb\                        # [PUBLIC] Web ecosystem deployed to GitHub
│   ├── index.html                      # Enterprise Landing Page (Tailwind + Alpine.js)
│   ├── style.css                       # Cyberpunk gaming theme & animations
│   ├── app.js                          # Alpine.js reactive logic, telemetry & blob streamer
│   └── version.json                    # Edge CDN update metadata (Anti-Rate-Limit)
├── release-apk\                        # Compiled signed release APK binaries
├── tools\                              # Release automation scripts
│   ├── github_token.env                # [SECRET] GitHub token & config (GITIGNORED)
│   ├── publish_release.bat             # 1-Click release runner
│   └── publish_release.ps1             # PowerShell release orchestrator
├── AI_DEVELOPER_GUIDE.md               # [THIS FILE] Blueprint for AI assistants & devs
├── agwallpaper84.jks                   # [SECRET] Android signing keystore
└── build.gradle.kts                    # Root Gradle configuration
```
