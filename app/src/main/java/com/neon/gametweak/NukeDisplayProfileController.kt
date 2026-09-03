package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Legacy session-owned display profile controller.
 *
 * Safety rules:
 * New Boost sessions are native-display only. Older builds could stage and apply a display
 * override, so the journal and restore path remain here solely to recover those existing values.
 * No current launch path is allowed to write a new size or density override.
 *
 * Legacy safety rules:
 * - resolution is never increased above the user's effective pre-session size;
 * - the short edge is never pushed below 720 px when the source display is already >= 720 px;
 * - density is scaled with resolution so dp-space stays approximately stable and is clamped;
 * - exact pre-session overrides are journaled before any write and restored only while Game Nuke
 *   still owns the current value;
 * - a partial write is rolled back immediately; a crash leaves a persistent recovery journal.
 */
object NukeDisplayProfileController {
    private const val TAG = "NukeDisplayProfile"
    private const val PREFS = "NukePrefs"

    private const val K_STAGE = "display_profile_stage"
    private const val K_JOURNAL_ACTIVE = "display_profile_journal_active"
    private const val K_SIZE_OWNED = "display_profile_size_owned"
    private const val K_DENSITY_OWNED = "display_profile_density_owned"
    private const val K_TARGET_WIDTH = "display_profile_target_width"
    private const val K_TARGET_HEIGHT = "display_profile_target_height"
    private const val K_TARGET_DENSITY = "display_profile_target_density"
    private const val K_ORIGINAL_SIZE_HAD_OVERRIDE = "display_profile_original_size_had_override"
    private const val K_ORIGINAL_WIDTH = "display_profile_original_width"
    private const val K_ORIGINAL_HEIGHT = "display_profile_original_height"
    private const val K_ORIGINAL_DENSITY_HAD_OVERRIDE = "display_profile_original_density_had_override"
    private const val K_ORIGINAL_DENSITY = "display_profile_original_density"
    private const val K_PROFILE_APPLIED = "display_profile_applied_id"
    private const val K_APPLIED_AT = "display_profile_applied_at"

    private const val MIN_SAFE_SHORT_EDGE = 720
    private const val ABS_MIN_DIMENSION = 480
    private const val ABS_MAX_DIMENSION = 6000
    private const val MIN_DENSITY = 160
    private const val MAX_DENSITY = 720

    enum class Profile(
        val id: String,
        val title: String,
        val scalePercent: Int,
        val detail: String,
    ) {
        NATIVE("native", "NATIVE", 100, "Original display size and density"),
        BALANCED("balanced", "BALANCED", 90, "Mild render-load reduction"),
        PERFORMANCE("performance", "PERFORMANCE", 82, "Stronger render-load reduction"),
        ULTRA_PERFORMANCE("ultra_performance", "ULTRA PERFORMANCE", 75, "Lowest Game Nuke safe profile"),
    }

    enum class Outcome { SUCCESS, ERROR, UNSUPPORTED, UNCHANGED, DEFERRED }

    data class Result(
        val outcome: Outcome,
        val message: String,
        val changed: Boolean = false,
    ) {
        val isSuccess: Boolean get() = outcome == Outcome.SUCCESS || outcome == Outcome.UNCHANGED
    }

    data class DisplaySnapshot(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val overrideWidth: Int?,
        val overrideHeight: Int?,
        val physicalDensity: Int,
        val overrideDensity: Int?,
    ) {
        val effectiveWidth: Int get() = overrideWidth ?: physicalWidth
        val effectiveHeight: Int get() = overrideHeight ?: physicalHeight
        val effectiveDensity: Int get() = overrideDensity ?: physicalDensity
    }

    data class Preview(
        val profile: Profile,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sourceDensity: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val targetDensity: Int,
        val effectiveScalePercent: Int,
    )

    fun profiles(): List<Profile> = listOf(Profile.NATIVE)

    fun stagedProfile(context: Context): Profile {
        val id = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .safeString(K_STAGE, Profile.NATIVE.id)
        return Profile.values().firstOrNull { it.id == id } ?: Profile.NATIVE
    }

    /**
     * Display downscaling was retired because changing size/density during a game handoff causes
     * a device-wide configuration change on Android. Keep this API source-compatible while making
     * every new choice native-only.
     */
    fun stageProfile(context: Context, profile: Profile): Result {
        if (profile != Profile.NATIVE) {
            return Result(
                Outcome.UNSUPPORTED,
                "Display overrides are disabled; Boost always preserves native resolution and DPI",
            )
        }
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_STAGE, profile.id).apply()
            Result(
                Outcome.SUCCESS,
                if (profile == Profile.NATIVE) "Native display profile armed for the next game session"
                else "${profile.title} display profile armed for the next game session",
                changed = true,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to stage display profile", error)
            Result(Outcome.ERROR, "Unable to save display profile: ${error.message.orEmpty().take(120)}")
        }
    }

    fun hasOwnedOverride(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.safeBoolean(K_JOURNAL_ACTIVE, false) ||
            prefs.safeBoolean(K_SIZE_OWNED, false) ||
            prefs.safeBoolean(K_DENSITY_OWNED, false) ||
            prefs.safeBoolean("dpi_owned", false)
    }

    fun appliedProfile(context: Context): Profile? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasOwnedOverride(context)) return null
        val id = prefs.safeString(K_PROFILE_APPLIED, "")
        return Profile.values().firstOrNull { it.id == id }
    }

    /** Read-only preview. No setting is changed. */
    fun preview(context: Context, profile: Profile = stagedProfile(context)): ResultWithPreview {
        val app = context.applicationContext
        val gateway = NukeGamingShellGateway(AdbManager.getInstance(app))
        if (!gateway.connected()) {
            return ResultWithPreview(Result(Outcome.UNSUPPORTED, "Extended Device Control is required for display profiles"), null)
        }
        val snapshot = readSnapshot(gateway)
            ?: return ResultWithPreview(Result(Outcome.UNSUPPORTED, "Display override commands are unavailable on this device"), null)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previewSnapshot = if (prefs.safeBoolean(K_SIZE_OWNED, false) || prefs.safeBoolean(K_DENSITY_OWNED, false)) {
            val originalW = prefs.safeInt(K_ORIGINAL_WIDTH, snapshot.effectiveWidth)
            val originalH = prefs.safeInt(K_ORIGINAL_HEIGHT, snapshot.effectiveHeight)
            val originalD = prefs.safeInt(K_ORIGINAL_DENSITY, snapshot.effectiveDensity)
            snapshot.copy(
                overrideWidth = originalW.takeIf { it in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION },
                overrideHeight = originalH.takeIf { it in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION },
                overrideDensity = originalD.takeIf { it in 120..1000 },
            )
        } else snapshot
        return ResultWithPreview(Result(Outcome.SUCCESS, "Display profile preview ready"), buildPreview(previewSnapshot, profile))
    }

    data class ResultWithPreview(val result: Result, val preview: Preview?)

    /**
     * Compatibility entry point for older callers. It intentionally performs no shell I/O and
     * cannot execute `wm size` or `wm density`. Existing ownership is handled by the independent
     * stale-recovery path before a new session is launched.
     */
    fun applyStagedForSession(context: Context): Result {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(K_STAGE, Profile.NATIVE.id).apply()
        return Result(
            Outcome.UNCHANGED,
            "Native display guard active; Boost preserved resolution and DPI",
        )
    }

    /** Restore only values that still equal the exact values Game Nuke wrote. */
    fun restoreIfOwned(context: Context): Result {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasOwnedOverride(app)) return Result(Outcome.UNCHANGED, "No Game Nuke display override to restore")
        val gateway = NukeGamingShellGateway(AdbManager.getInstance(app))
        if (!gateway.connected()) return Result(Outcome.DEFERRED, "Extended Device Control offline; display restore deferred")

        return runCatching {
            hydrateOwnershipFromJournal(gateway, prefs)
            var legacyChanged = false
            if (prefs.safeBoolean("dpi_owned", false)) {
                val legacy = NukeDpiController.restoreIfOwned(app)
                legacyChanged = legacy.changed
                if (prefs.safeBoolean("dpi_owned", false)) {
                    return@runCatching Result(Outcome.DEFERRED, "Legacy DPI rollback deferred")
                }
            }
            val density = restoreDensityIfOwned(gateway, prefs)
            val size = restoreSizeIfOwned(gateway, prefs)
            clearJournalIfReleased(prefs)

            val stillOwned = hasOwnedOverride(app)
            when {
                stillOwned -> Result(Outcome.DEFERRED, listOfNotNull(density, size).joinToString(" • ").ifBlank { "Display restore deferred" })
                else -> Result(Outcome.SUCCESS, "Display size and density restored", changed = legacyChanged || density != null || size != null)
            }
        }.getOrElse { error ->
            Log.e(TAG, "Display restore failed", error)
            Result(Outcome.ERROR, "Display restore failed safely: ${error.message.orEmpty().take(120)}")
        }
    }

    /**
     * Fresh-process recovery helper. It never restores while an in-process overlay session is alive.
     * The caller should retry after the trusted core reconnects when DEFERRED is returned.
     */
    fun recoverStaleOverride(context: Context, overlayRunningInProcess: Boolean): Result {
        val app = context.applicationContext
        if (!hasOwnedOverride(app)) return Result(Outcome.UNCHANGED, "No stale display override")
        if (overlayRunningInProcess) return Result(Outcome.UNCHANGED, "Active session still owns the display profile")
        return restoreIfOwned(app)
    }

    private fun buildPreview(snapshot: DisplaySnapshot, profile: Profile): Preview {
        val sourceW = snapshot.effectiveWidth.coerceIn(ABS_MIN_DIMENSION, ABS_MAX_DIMENSION)
        val sourceH = snapshot.effectiveHeight.coerceIn(ABS_MIN_DIMENSION, ABS_MAX_DIMENSION)
        val shortEdge = minOf(sourceW, sourceH)

        val floorPercent = if (shortEdge >= MIN_SAFE_SHORT_EDGE) {
            ceil((MIN_SAFE_SHORT_EDGE * 100.0) / shortEdge.toDouble()).toInt().coerceIn(profile.scalePercent, 100)
        } else {
            100
        }
        val safePercent = maxOf(profile.scalePercent, floorPercent).coerceIn(75, 100)
        val targetW = ((sourceW * safePercent) / 100.0).roundToInt().coerceIn(ABS_MIN_DIMENSION, sourceW)
        val targetH = ((sourceH * safePercent) / 100.0).roundToInt().coerceIn(ABS_MIN_DIMENSION, sourceH)

        val sourceDensity = snapshot.effectiveDensity.coerceIn(MIN_DENSITY, MAX_DENSITY)
        val scaledDensity = ((sourceDensity * safePercent) / 100.0).roundToInt()
        val targetDensity = scaledDensity.coerceIn(MIN_DENSITY, sourceDensity)

        return Preview(
            profile = profile,
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            sourceDensity = sourceDensity,
            targetWidth = targetW,
            targetHeight = targetH,
            targetDensity = targetDensity,
            effectiveScalePercent = safePercent,
        )
    }

    private fun readSnapshot(gateway: NukeGamingShellGateway): DisplaySnapshot? {
        val size = readSize(gateway) ?: return null
        val density = readDensity(gateway) ?: return null
        return DisplaySnapshot(
            physicalWidth = size.physicalWidth,
            physicalHeight = size.physicalHeight,
            overrideWidth = size.overrideWidth,
            overrideHeight = size.overrideHeight,
            physicalDensity = density.physicalDensity,
            overrideDensity = density.overrideDensity,
        )
    }

    private data class SizeInfo(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val overrideWidth: Int?,
        val overrideHeight: Int?,
    )

    private data class DensityInfo(val physicalDensity: Int, val overrideDensity: Int?)

    private fun readSize(gateway: NukeGamingShellGateway): SizeInfo? {
        val result = gateway.readDisplaySize()
        if (!result.isSuccess) return null
        val physical = Regex("Physical size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
            .find(result.output)
        val fallback = Regex("(?:^|\\n)\\s*size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
            .find(result.output)
        val source = physical ?: fallback ?: return null
        val pw = source.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val ph = source.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (pw !in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION || ph !in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION) return null
        val override = Regex("Override size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(result.output)
        val ow = override?.groupValues?.getOrNull(1)?.toIntOrNull()
        val oh = override?.groupValues?.getOrNull(2)?.toIntOrNull()
        if ((ow == null) != (oh == null)) return null
        if (ow != null && oh != null && (ow !in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION || oh !in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION)) return null
        return SizeInfo(pw, ph, ow, oh)
    }

    private fun readDensity(gateway: NukeGamingShellGateway): DensityInfo? {
        val result = gateway.readDisplayDensity()
        if (!result.isSuccess) return null
        val physical = Regex("Physical density:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:^|\\n)\\s*density:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (physical !in 120..1000) return null
        val override = Regex("Override density:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (override != null && override !in 120..1000) return null
        return DensityInfo(physical, override)
    }

    /**
     * Recover the millisecond-wide crash window between an OEM shell write completing and the
     * ownership flag reaching disk. The pre-write journal is committed synchronously. We only
     * adopt ownership when the current override exactly equals Game Nuke's journaled target.
     */
    private fun hydrateOwnershipFromJournal(gateway: NukeGamingShellGateway, prefs: SharedPreferences) {
        if (!prefs.safeBoolean(K_JOURNAL_ACTIVE, false)) return
        var sizeOwned = prefs.safeBoolean(K_SIZE_OWNED, false)
        var densityOwned = prefs.safeBoolean(K_DENSITY_OWNED, false)

        if (!sizeOwned) {
            val targetW = prefs.safeInt(K_TARGET_WIDTH, -1)
            val targetH = prefs.safeInt(K_TARGET_HEIGHT, -1)
            val current = readSize(gateway)
            sizeOwned = targetW in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION &&
                targetH in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION &&
                current?.overrideWidth == targetW && current.overrideHeight == targetH
        }
        if (!densityOwned) {
            val targetDensity = prefs.safeInt(K_TARGET_DENSITY, -1)
            val current = readDensity(gateway)
            densityOwned = targetDensity in 120..1000 && current?.overrideDensity == targetDensity
        }

        prefs.edit()
            .putBoolean(K_SIZE_OWNED, sizeOwned)
            .putBoolean(K_DENSITY_OWNED, densityOwned)
            .commit()
        clearJournalIfReleased(prefs)
    }

    private fun restoreSizeIfOwned(gateway: NukeGamingShellGateway, prefs: SharedPreferences): String? {
        if (!prefs.safeBoolean(K_SIZE_OWNED, false)) return null
        val targetW = prefs.safeInt(K_TARGET_WIDTH, -1)
        val targetH = prefs.safeInt(K_TARGET_HEIGHT, -1)
        val current = readSize(gateway)
            ?: return "Resolution restore waiting for readable display state"

        if (current.overrideWidth != targetW || current.overrideHeight != targetH) {
            // Someone else changed the display after us. Do not clobber that newer choice.
            prefs.edit().putBoolean(K_SIZE_OWNED, false).apply()
            return "Resolution changed outside Game Nuke; external value preserved"
        }

        val hadOverride = prefs.safeBoolean(K_ORIGINAL_SIZE_HAD_OVERRIDE, false)
        val originalW = prefs.safeInt(K_ORIGINAL_WIDTH, -1)
        val originalH = prefs.safeInt(K_ORIGINAL_HEIGHT, -1)
        val result = if (hadOverride && originalW in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION && originalH in ABS_MIN_DIMENSION..ABS_MAX_DIMENSION) {
            gateway.setDisplaySize(originalW, originalH)
        } else {
            gateway.resetDisplaySize()
        }
        if (!result.isSuccess) return "Resolution rollback deferred"

        val verify = readSize(gateway)
        val restored = if (hadOverride) {
            verify?.overrideWidth == originalW && verify.overrideHeight == originalH
        } else {
            verify?.overrideWidth == null && verify?.overrideHeight == null
        }
        return if (restored) {
            prefs.edit().putBoolean(K_SIZE_OWNED, false).apply()
            "Resolution restored"
        } else {
            "Resolution rollback verification pending"
        }
    }

    private fun restoreDensityIfOwned(gateway: NukeGamingShellGateway, prefs: SharedPreferences): String? {
        if (!prefs.safeBoolean(K_DENSITY_OWNED, false)) return null
        val target = prefs.safeInt(K_TARGET_DENSITY, -1)
        val current = readDensity(gateway)
            ?: return "Density restore waiting for readable display state"

        if (current.overrideDensity != target) {
            prefs.edit().putBoolean(K_DENSITY_OWNED, false).apply()
            return "Density changed outside Game Nuke; external value preserved"
        }

        val hadOverride = prefs.safeBoolean(K_ORIGINAL_DENSITY_HAD_OVERRIDE, false)
        val original = prefs.safeInt(K_ORIGINAL_DENSITY, -1)
        val result = if (hadOverride && original in 120..1000) {
            gateway.setDisplayDensity(original)
        } else {
            gateway.resetDisplayDensity()
        }
        if (!result.isSuccess) return "Density rollback deferred"

        val verify = readDensity(gateway)
        val restored = if (hadOverride) verify?.overrideDensity == original else verify?.overrideDensity == null
        return if (restored) {
            prefs.edit().putBoolean(K_DENSITY_OWNED, false).apply()
            "Density restored"
        } else {
            "Density rollback verification pending"
        }
    }

    private fun clearJournalIfReleased(prefs: SharedPreferences) {
        if (!prefs.safeBoolean(K_SIZE_OWNED, false) && !prefs.safeBoolean(K_DENSITY_OWNED, false)) clearJournal(prefs)
    }

    private fun clearJournal(prefs: SharedPreferences) {
        prefs.edit()
            .remove(K_JOURNAL_ACTIVE)
            .remove(K_SIZE_OWNED)
            .remove(K_DENSITY_OWNED)
            .remove(K_TARGET_WIDTH)
            .remove(K_TARGET_HEIGHT)
            .remove(K_TARGET_DENSITY)
            .remove(K_ORIGINAL_SIZE_HAD_OVERRIDE)
            .remove(K_ORIGINAL_WIDTH)
            .remove(K_ORIGINAL_HEIGHT)
            .remove(K_ORIGINAL_DENSITY_HAD_OVERRIDE)
            .remove(K_ORIGINAL_DENSITY)
            .remove(K_PROFILE_APPLIED)
            .remove(K_APPLIED_AT)
            .apply()
    }
}
