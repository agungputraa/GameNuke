package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * NukeExternalApiReceiver — Exported Broadcast API Receiver for External Apps & Automation.
 *
 * Allows third-party apps, Tasker, MacroDroid, Termux, and custom scripts to control
 * Game Nuke floating HUD and child panels even when the main app is closed or cleared
 * from Recent Apps.
 *
 * Supported Actions:
 *  - com.neon.gametweak.ACTION_OPEN_HUD
 *  - com.neon.gametweak.ACTION_CLOSE_HUD
 *  - com.neon.gametweak.ACTION_TOGGLE_HUD
 *  - com.neon.gametweak.ACTION_OPEN_PANEL  (extra: "panel" = "phone_health"|"magic_touch"|"gpu_tuner"|"ai_sentinel"|"wiki_pip")
 *  - com.neon.gametweak.ACTION_KILL_HOGS
 *  - com.neon.gametweak.ACTION_NET_TURBO
 */
class NukeExternalApiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NukeExternalApi"

        const val ACTION_OPEN_HUD   = "com.neon.gametweak.ACTION_OPEN_HUD"
        const val ACTION_CLOSE_HUD  = "com.neon.gametweak.ACTION_CLOSE_HUD"
        const val ACTION_TOGGLE_HUD = "com.neon.gametweak.ACTION_TOGGLE_HUD"
        const val ACTION_OPEN_PANEL = "com.neon.gametweak.ACTION_OPEN_PANEL"
        const val ACTION_KILL_HOGS  = "com.neon.gametweak.ACTION_KILL_HOGS"
        const val ACTION_NET_TURBO  = "com.neon.gametweak.ACTION_NET_TURBO"

        const val EXTRA_PANEL       = "panel"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            val action = intent?.action ?: return
            Log.d(TAG, "External API Broadcast received: $action")

            val appContext = context.applicationContext
            val targetPkg = intent.getStringExtra(FloatingBoosterService.EXTRA_TARGET_PACKAGE)
                ?.trim()?.takeIf { it.length in 3..220 && it.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")) }
                ?: appContext.packageName

            when (action) {
                ACTION_OPEN_HUD -> {
                    val svcIntent = Intent(appContext, FloatingBoosterService::class.java).apply {
                        this.action = FloatingBoosterService.ACTION_SHOW_OVERLAY
                        putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                        putExtra(FloatingBoosterService.EXTRA_TARGET_PACKAGE, targetPkg)
                    }
                    startServiceSafely(appContext, svcIntent)
                }

                ACTION_CLOSE_HUD -> {
                    val svcIntent = Intent(appContext, FloatingBoosterService::class.java).apply {
                        this.action = FloatingBoosterService.ACTION_STOP_OVERLAY
                    }
                    runCatching { appContext.startService(svcIntent) }
                }

                ACTION_TOGGLE_HUD -> {
                    val isRunning = NukeRuntimeState.state.value.overlayRunning
                    val svcIntent = Intent(appContext, FloatingBoosterService::class.java).apply {
                        this.action = if (isRunning) FloatingBoosterService.ACTION_STOP_OVERLAY else FloatingBoosterService.ACTION_SHOW_OVERLAY
                        putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                        if (!isRunning) {
                            putExtra(FloatingBoosterService.EXTRA_TARGET_PACKAGE, targetPkg)
                        }
                    }
                    if (isRunning) runCatching { appContext.startService(svcIntent) } else startServiceSafely(appContext, svcIntent)
                }

                ACTION_OPEN_PANEL -> {
                    val panel = intent.getStringExtra(EXTRA_PANEL)?.lowercase() ?: "phone_health"
                    when (panel) {
                        "phone_health", "health" -> NukePhoneHealthOverlay.getInstance(appContext).show()
                        "task_manager", "tasks"  -> NukeTaskManagerPanelOverlay.getInstance(appContext).show()
                        "magic_touch", "touch"   -> NukeMagicTouchPanelOverlay.getInstance(appContext).show()
                        "gpu_tuner", "gpu"       -> NukeGpuGraphicsPanelOverlay.getInstance(appContext).show()
                        "wiki_pip", "wiki"       -> NukeWikiOverlayView.getInstance(appContext).show()
                        "ai_sentinel", "sentinel" -> NukeAiSentinel.setEnabled(appContext, true)
                        "live_chat", "chat", "telegram" -> NukeLiveChatOverlay.getInstance(appContext).show()
                        "terminal", "console", "shell" -> NukeTerminalOverlay.getInstance(appContext).show()
                        else -> {
                            val svcIntent = Intent(appContext, FloatingBoosterService::class.java).apply {
                                this.action = FloatingBoosterService.ACTION_SHOW_OVERLAY
                                putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                                putExtra(FloatingBoosterService.EXTRA_TARGET_PACKAGE, targetPkg)
                            }
                            startServiceSafely(appContext, svcIntent)
                        }
                    }
                }

                ACTION_KILL_HOGS -> {
                    NukeAiSentinel.triggerManualSweep(appContext)
                }

                ACTION_NET_TURBO -> {
                    val active = NukeNetPacer.isRunning
                    if (active) NukeNetPacer.stopBoost(appContext)
                    else NukeNetPacer.startBoost(appContext, NukeNetPacer.BoostMode.PING_BOOST)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Error handling external API broadcast: ${e.message}", e)
        }
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to start FloatingBoosterService from External API: ${e.message}")
        }
    }
}
