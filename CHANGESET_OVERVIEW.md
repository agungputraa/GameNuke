# Game Nuke Premium — Changeset Overview

Package: `com.neon.gametweak`  
Min SDK: 30  
Target / Compile SDK: 36

## Scope completed

### 1. HUD / Quick Tools cleanup
- Removed the built-in Screenshot action from the Floating HUD / Quick Tools surface.
- Removed Palm Shield / anti-mistouch action from the HUD and module catalog.
- Removed Translate module UI/catalog references and its unused vector icon resource.
- Replaced freed compact action slots with Live FPS / Crosshair actions.

### 2. Crosshair Studio
- Crosshair overlay supports: Dot, Classic Cross, Circle Dot, Chevron, Sniper T, Box Bracket, Dynamic Gap.
- Added neon palette choices: Neon Red, Cyber Green, Electric Cyan, Gold, White, Hot Pink.
- Added opacity, size (8–64dp), line thickness (1–6dp), and calibrated X/Y position controls.
- Fine adjustment uses physical-pixel nudging for 1-pixel reticle calibration.
- Preferences persist through SharedPreferences.

### 3. Persistent Macro Studio / pins
- Macro pins are persisted with `NukeMacroPersistence` and restored when the accessibility macro service reconnects.
- Closing the Macro Studio editor no longer removes placed pins.
- Added lock-position state, per-pin size, opacity, label, color, action, and auto-tap interval.
- Added pin actions: Tap, Fast Gloo, Weapon Switch, Auto Tap.
- Auto Tap supports configurable 5–50ms intervals at the data/persistence layer.
- Fast Gloo executes the calibrated four-step sequence: Gloo → Crouch → Place → Weapon.

### 4. Touch injection / anti-ghosting architecture
- Privileged shell input is preferred when Shizuku/iADB/root backend is connected.
- Accessibility `dispatchGesture()` remains a fallback rather than pretending public Android APIs can force an independent physical pointer ID.
- Macro gesture state prevents overlapping Accessibility gestures from fighting each other.
- Game Nuke injected swipe vectors can apply X/Y drag-assist calibration without falsely claiming to rewrite physical finger events.

### 5. Magic Touch
- Added `NukeTouchTuningEngine`.
- Applies Android pointer speed through a verified Settings/shell path when permission is available.
- Probes known OEM game-touch nodes and writes them only if the node exists and is writable.
- Removed reliance on fictitious/unverified touch-sampling system properties.
- Added adjustable injected X/Y drag profile and anti-jitter damping.

### 6. Macro UI redesign
- Compact Obsidian-style floating panel with neon cyan / emerald accents.
- Reduced tile/control footprint for landscape use.
- Per-pin editor exposes lock, action, size, opacity, color, label and timing controls.

### 7. Game Dock telemetry
- Refresh-rate badge now reflects the active display mode instead of a hard-coded 120Hz value.
- FPS/telemetry presentation is retained and tightened for the top HUD rail.
- Ping indicator uses gamer thresholds (<50ms green, <100ms yellow, >150ms red).
- Network probe now keeps a rolling success/jitter window and exposes a stability state instead of showing only a single ping number.

### 8. AI Sentinel / CPU hog control
- Refactored Sentinel decisions around measured process CPU/RAM information rather than fabricated/random status values.
- Protects critical packages/processes including the active game, launcher, IME, dialer/phone, Shizuku/ADB-related services, and Game Nuke itself.
- Attempts process priority / governor actions only through the active privileged backend and only when permission/path support exists.

## Mandatory build fixes preserved

1. `FloatingHudCompose.kt` explicitly imports `androidx.compose.runtime.collectAsState`.
2. `NukeHudTools.kt` no longer uses wildcard Android View / Compose layout imports that can shadow `android.view.WindowInsets`; system-bar inset calls use the Android class explicitly where applicable.
3. `nuke-fonts.gradle.kts` no longer calls `extensions.configure<AppExtension>`; generated font resources are registered from `app/build.gradle.kts` inside `android {}`.
4. Release signing expects local `agwallpaper84.jks`, alias `agwallpaper`, with secrets in local `release.properties` or `NUKE_RELEASE_*` Gradle properties. Real signing secrets/keystore are intentionally not embedded in this ZIP.

## New files
- `app/src/main/java/com/neon/gametweak/NukeMacroPersistence.kt`
- `app/src/main/java/com/neon/gametweak/NukeTouchTuningEngine.kt`
- `release.properties.example`
- `CHANGESET_OVERVIEW.md`

## Removed resource
- `app/src/main/res/drawable/nuke_ic_translate.xml`

## Validation performed in this workspace
- `python3 tools/validate_refactor.py` → passed; 118 Kotlin files checked.
- `git diff --check` → passed.
- Placeholder scan for `TODO: implement later` / `rest of code unchanged` → clean.
- `NukeMacroEngine.kt` compiles independently with `kotlinc` + kotlinx-coroutines.

## Environment limitation
A full Android Gradle build could not be truthfully certified in this execution environment because the Gradle 8.13 wrapper distribution is not cached and external Gradle download / Android SDK access is unavailable here. The ZIP therefore contains the complete modified source and validation artifacts, but does **not** claim a locally observed `BUILD SUCCESSFUL` for the full Android app.

For a release build on the development machine, place the real `agwallpaper84.jks` beside `settings.gradle.kts`, copy `release.properties.example` to `release.properties`, fill the actual passwords, then run the normal Gradle release task.
