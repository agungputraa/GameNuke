# Game Nuke 3.4.0 — Migration / Delete Old Files

This file is intentionally written as a cleanup instruction for a local coding agent or for manual migration.
When copying this release over an older Game Nuke tree, **delete the paths below first**. Do not keep stale copies beside the new files.

## 1. Delete accidental project-inside-assets content
Delete these if they exist:

- `app/src/main/assets/src/` — delete the entire directory recursively.
- `app/src/main/assets/build.gradle.kts`
- `app/src/main/assets/proguard-rules.pro`
- `app/src/main/assets/labels.json`
- `app/src/main/assets/vocab.json`
- `app/src/main/assets/SPLASH_ASSET_README.txt`

**KEEP:** `app/src/main/assets/splash.mp4`.
The real splash video belongs exactly at this path.

## 2. Delete superseded / dead runtime sources
Delete these old files if present:

- `app/src/main/java/com/neon/gametweak/AdGuardDnsDetector.kt`
- `app/src/main/java/com/neon/gametweak/AdGuardDnsDialog.kt`
- `app/src/main/java/com/neon/gametweak/OverlayPermissionBypass.kt`
- `app/src/main/java/com/neon/gametweak/ScreenRecordActivity.kt`
- `app/src/main/java/com/neon/gametweak/ScreenRecordService.kt`
- `app/src/main/java/com/neon/gametweak/VoiceLabActivity.kt`
- `app/src/main/java/com/neon/gametweak/FloatingBoosterPanel.kt`
- `app/src/main/java/com/neon/gametweak/FloatingOverlayState.kt`
- `app/src/main/java/com/neon/gametweak/NukeFloatingHudComponents.kt`
- `app/src/main/java/com/neon/gametweak/NukeFloatingTheme.kt`
- `app/src/main/java/com/neon/gametweak/PluginModuleRepository.kt`
- `app/src/main/java/com/neon/gametweak/ui/components/PluginModulesTab.kt`
- `app/src/main/java/com/neon/gametweak/ui/components/GamingComponents.kt`
- `app/src/main/java/com/neon/gametweak/ui/components/TweakComponents.kt`
- `app/src/main/java/com/neon/gametweak/ui/screens/NukeCoreScreens.kt`

The remote module downloader was removed because it could download and execute shell scripts outside the verified Game Nuke operation gateway. It is not part of the enterprise runtime.

## 3. Delete older experimental sources if they still exist locally
Older trees may still contain the following files. They are not part of 3.4.0 and should be removed instead of kept as duplicate implementations:

- `AiAdvisor.kt`
- `AiAdvisorCard.kt`
- `AiAutoTuner.kt`
- `AiGameBoostDaemonController.kt`
- `AiGameBoostLiveNotification.kt`
- `AiNukeControlPanel.kt`
- `AiRealtimeBoostService.kt`
- `AiRealtimeNotificationCenter.kt`
- `BoosterPanelActivity.kt`
- `BootKeepAliveReceiver.kt`
- `DaemonProcess.kt`
- `DaemonServer.kt`
- `FloatingAppsPanel.kt`
- `FloatingDragShotPanel.kt`
- `FloatingHudOverlay.kt`
- `FloatingMacroPanel.kt`
- `FloatingOverlayTelemetry.kt`
- `FloatingVoicePanel.kt`
- `GameBoostRestoreManager.kt`
- `KeepAliveAlarmReceiver.kt`
- `KeepAliveJobService.kt`
- `NukeQuickControlManager.kt`
- `NukeTelemetryReader.kt`
- `OverlayKeepAliveManager.kt`
- `SafeAdbCommandPolicy.kt`
- `SafeNukeTorchModel.kt`

Search the whole `app/src/main/java/` tree and delete any duplicate class with the same purpose as the active 3.4.0 classes.

## 4. Files that must exist after migration
Do not delete these new core files:

- `GameCatalogRepository.kt` — Android 11+ package-visibility-safe game detection and launcher resolver.
- `NukeSessionNotification.kt` — user-visible foreground gaming session notification.
- `FloatingBoosterService.kt` — native XML/View multi-window cockpit.
- `NukeHudFrameLayout.kt`
- `NukeGaugeView.kt`
- `NukeSparklineView.kt`
- `NukeAdbOrchestrator.kt`
- `AdbManager.kt`
- `NukePerformanceEngine.kt`
- `NukeDpiController.kt`
- `GameLaunchSplashActivity.kt`
- `app/src/main/assets/splash.mp4`

## 5. After cleanup
1. Sync Gradle.
2. Run `Clean Project` / delete local `build/` folders.
3. Rebuild the app.
4. Uninstall the previous test APK once before testing package-visibility and manifest changes.
5. Install 3.4.0 fresh and grant only the permissions/special access used by the selected features.
