package com.neon.gametweak

import android.content.Context
import android.util.Log

/**
 * Legacy rollback-only compatibility layer for Game Nuke builds that used a standalone DPI stage.
 * New builds never create new DPI ownership here; [NukeDisplayProfileController] owns size+density
 * together so they cannot fight each other or leave a partial display state behind.
 */
object NukeDpiController {
    private const val TAG = "NukeDpiLegacy"
    private const val PREFS = "NukePrefs"
    private const val K_OWNED = "dpi_owned"
    private const val K_TARGET = "dpi_target"
    private const val K_ORIGINAL_OVERRIDE = "dpi_original_override"
    private const val K_ORIGINAL_HAD_OVERRIDE = "dpi_original_had_override"
    private const val MIN_DPI = 120
    private const val MAX_DPI = 1000

    data class Result(val changed: Boolean, val message: String)

    fun restoreIfOwned(context: Context): Result {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.safeBoolean(K_OWNED, false)) return Result(false, "No legacy DPI ownership")
        val gateway = NukeGamingShellGateway(AdbManager.getInstance(app))
        if (!gateway.connected()) return Result(false, "Extended control offline; legacy DPI rollback deferred")

        return runCatching {
            val target = prefs.safeInt(K_TARGET, -1)
            val current = parseDensity(gateway.readDisplayDensity().output)
                ?: return@runCatching Result(false, "Unable to verify legacy DPI")
            if (current.override != target) {
                clearOwnership(prefs)
                return@runCatching Result(false, "Legacy DPI changed outside Game Nuke; external value preserved")
            }

            val hadOverride = prefs.safeBoolean(K_ORIGINAL_HAD_OVERRIDE, false)
            val original = prefs.safeInt(K_ORIGINAL_OVERRIDE, -1)
            val result = if (hadOverride && original in MIN_DPI..MAX_DPI) {
                gateway.setDisplayDensity(original)
            } else {
                gateway.resetDisplayDensity()
            }
            if (!result.isSuccess) return@runCatching Result(false, "Legacy DPI rollback deferred")

            val verified = parseDensity(gateway.readDisplayDensity().output)?.let { density ->
                if (hadOverride) density.override == original else density.override == null
            } == true
            if (!verified) return@runCatching Result(false, "Legacy DPI rollback verification pending")

            clearOwnership(prefs)
            Result(true, "Legacy DPI restored")
        }.getOrElse { error ->
            Log.w(TAG, "Legacy DPI rollback failed", error)
            Result(false, "Legacy DPI rollback failed safely")
        }
    }

    private data class Density(val physical: Int, val override: Int?)

    private fun parseDensity(output: String): Density? {
        val physical = Regex("Physical density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:^|\\n)\\s*density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val override = Regex("Override density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (physical !in MIN_DPI..MAX_DPI || (override != null && override !in MIN_DPI..MAX_DPI)) return null
        return Density(physical, override)
    }

    private fun clearOwnership(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(K_OWNED)
            .remove(K_TARGET)
            .remove(K_ORIGINAL_OVERRIDE)
            .remove(K_ORIGINAL_HAD_OVERRIDE)
            .remove("next_launch_dpi_percent")
            .apply()
    }
}
