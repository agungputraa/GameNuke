package com.neon.gametweak

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Tracks the selected game only through the already-paired ADB shell. No Usage Access permission.
 * If ADB is unavailable, tracking becomes unavailable and the HUD remains user-controlled.
 */
internal class OverlayGameSessionController(
    context: Context,
    private val targetPackage: String,
    private val startupGraceMs: Long = 35_000L,
    // Login, anti-cheat, permission and OEM game surfaces can temporarily own the resumed
    // Activity. A longer sustained-away window prevents false session teardown while still
    // allowing automatic cleanup after the user genuinely leaves the game.
    private val exitGraceMs: Long = 45_000L,
) {
    data class SessionEnd(val gamePackage: String, val trackingAvailable: Boolean, val gameWasObserved: Boolean)
    private val adb = AdbManager.getInstance(context.applicationContext)

    suspend fun awaitSessionEnd(): SessionEnd = withContext(Dispatchers.IO) {
        if (!adb.isConnected()) return@withContext SessionEnd(targetPackage, false, false)
        val started = SystemClock.elapsedRealtime()
        var observed = false
        var awaySince = 0L
        var failures = 0
        while (coroutineContext.isActive) {
            val foreground = foregroundPackage()
            val now = SystemClock.elapsedRealtime()
            if (foreground == null) {
                failures++
                if (failures >= 4 && !adb.isConnected()) return@withContext SessionEnd(targetPackage, false, observed)
                delay(1_400L)
                continue
            }
            failures = 0
            if (!observed) {
                if (foreground == targetPackage) {
                    NukeRuntimeState.clearExternalNavigationGrace()
                    observed = true; awaySince = 0L
                } else if (now - started >= startupGraceMs) {
                    return@withContext SessionEnd(targetPackage, true, false)
                }
            } else if (foreground == targetPackage) {
                NukeRuntimeState.clearExternalNavigationGrace()
                awaySince = 0L
            } else if (transientSystemSurface(foreground) ||
                (NukeRuntimeState.isExternalNavigationGraceActive() && externalSettingsSurface(foreground))
            ) {
                awaySince = 0L
            } else {
                if (awaySince == 0L) awaySince = now
                if (now - awaySince >= exitGraceMs) return@withContext SessionEnd(targetPackage, true, true)
            }
            delay(1_150L)
        }
        SessionEnd(targetPackage, adb.isConnected(), observed)
    }

    private fun foregroundPackage(): String? {
        val result = adb.executeCommand(
            "dumpsys activity activities",
            timeoutMs = 2_500L,
            maxOutputChars = 48_000,
        )
        if (!result.isSuccess || result.output.isBlank()) return null
        val resumed = result.output.lineSequence().firstOrNull {
            it.contains("mResumedActivity") || it.contains("topResumedActivity")
        } ?: return null
        return Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").find(resumed)?.groupValues?.getOrNull(1)
    }

    private fun transientSystemSurface(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")

    private fun externalSettingsSurface(pkg: String): Boolean =
        pkg == "com.android.settings" ||
            pkg.endsWith(".settings") ||
            pkg.contains("permissioncontroller") ||
            pkg.contains("rolecontroller")
}
