package com.neon.gametweak

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * NukeMacroRepository — Red Corner TouchMappingCoordinatorFeature storage layer for Game Nuke.
 *
 * Persists the per-game MacroProfile database in SharedPreferences JSON:
 *  - profiles_json : JSON array of MacroProfile (each holding its own pin list).
 *  - active_profile_id : the profile currently bound by the coordinator.
 *  - Legacy "active_pins_v2" (NukeMacroStudioPrefs) is imported into the new default
 *    "ALL GAMES" profile on first run, so old configs are never orphaned.
 *
 * The daemon CSV contract (TOUCH_SET_PINS / IShellService.setMacroPins) is:
 *   id,index,xRatio,yRatio,radiusPx,mode,repeatCount,intervalMs,holdDurationMs,
 *   targetX,targetY,invertX,invertY,sensX,sensY,startDelayMs,tapDurationMs,
 *   enabled,label,swipeDurationMs   (entries joined by ';')
 */
object NukeMacroRepository {

    private const val TAG = "NukeMacroRepository"
    private const val PREFS = "NukeMacroProfilesV3"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    private const val KEY_SEEDED = "default_seeded_v3"

    // ── Legacy v2 prefs (migration source) ──────────────────────────────────
    private const val LEGACY_PREFS = "NukeMacroStudioPrefs"
    private const val LEGACY_KEY_PINS = "active_pins_v2"

    // ─────────────────────────────────────────────────────────────────────────
    // Profile CRUD
    // ─────────────────────────────────────────────────────────────────────────

    fun loadProfiles(ctx: Context): MutableList<MacroProfile> {
        return try {
            val raw = sp(ctx).getString(KEY_PROFILES, null) ?: return seedDefaultProfile(ctx)
            val arr = JSONArray(raw)
            val list = mutableListOf<MacroProfile>()
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i).toProfile() }.getOrNull()?.let { list.add(it) }
            }
            if (list.isEmpty()) seedDefaultProfile(ctx) else list
        } catch (e: Exception) {
            Log.w(TAG, "loadProfiles failed: ${e.message}")
            seedDefaultProfile(ctx)
        }
    }

    fun saveProfiles(ctx: Context, profiles: List<MacroProfile>) {
        try {
            val arr = JSONArray()
            profiles.sortedBy { it.packageKey }.forEach { arr.put(it.toJson()) }
            sp(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
            Log.d(TAG, "Saved ${profiles.size} macro profiles")
        } catch (e: Exception) {
            Log.w(TAG, "saveProfiles failed: ${e.message}")
        }
    }

    /** Applies an already-mutated profile back into storage. */
    fun updateProfile(ctx: Context, profile: MacroProfile) {
        val profiles = loadProfiles(ctx)
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        saveProfiles(ctx, profiles)
    }
/**
     * Ensures the universal "ALL GAMES" profile exists and seeds it from any legacy v2
     * pin list on the very first launch. Fresh installs get a couple of starter pins.
     */
    fun seedDefaultProfile(ctx: Context): MutableList<MacroProfile> {
        val defaultProfile = MacroProfile(
            name = "ALL GAMES",
            packageKey = "",
            useMapping = true,
            pins = mutableListOf() // Initial setup has ZERO pins; user creates them explicitly.
        )
        val list = mutableListOf(defaultProfile)
        saveProfiles(ctx, list)
        sp(ctx).edit().putBoolean(KEY_SEEDED, true).apply()
        return list
    }

    fun activeProfileId(ctx: Context): String {
        return sp(ctx).getString(KEY_ACTIVE_PROFILE, null)
            ?: (loadProfiles(ctx).firstOrNull()?.id ?: "")
    }

    fun setActiveProfileId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun activeProfile(ctx: Context): MacroProfile {
        val profiles = loadProfiles(ctx)
        val id = activeProfileId(ctx)
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    /**
     * Red Corner coordinator resolution: if a dedicated profile exists for [packageKey]
     * it becomes active; otherwise the "ALL GAMES" profile is active.
     */
    fun resolveProfileForPackage(ctx: Context, packageKey: String?): MacroProfile {
        val profiles = loadProfiles(ctx)
        val target = packageKey.orEmpty().trim()
        val profile = if (target.isBlank()) {
            profiles.firstOrNull { it.packageKey.isEmpty() } ?: profiles.first()
        } else {
            profiles.firstOrNull { it.packageKey == target }
                ?: profiles.firstOrNull { it.packageKey.isEmpty() }
        }
        return profile ?: seedDefaultProfile(ctx).first()
    }

    fun createProfile(ctx: Context, name: String, packageKey: String = ""): MacroProfile {
        val profiles = loadProfiles(ctx)
        var key = packageKey
        if (key.isNotBlank()) {
            val existing = profiles.firstOrNull { it.packageKey == key }
            if (existing != null) return existing
        } else {
            key = ""
        }
        val profile = MacroProfile(
            name = name.ifBlank { if (key.isBlank()) "ALL GAMES" else key },
            packageKey = key
        )
        profiles.add(profile)
        saveProfiles(ctx, profiles)
        setActiveProfileId(ctx, profile.id)
        return profile
    }

    fun deleteProfile(ctx: Context, profileId: String) {
        val profiles = loadProfiles(ctx)
        val target = profiles.firstOrNull { it.id == profileId } ?: return
        if (target.packageKey.isEmpty() && profiles.count { it.packageKey.isEmpty() } <= 1) {
            // Never delete the last universal profile — reset it instead.
            target.pins.clear()
            saveProfiles(ctx, profiles)
            return
        }
        profiles.remove(target)
        saveProfiles(ctx, profiles)
        if (activeProfileId(ctx) == profileId) {
            setActiveProfileId(ctx, profiles.firstOrNull()?.id ?: "")
        }
    }

    fun renameProfile(ctx: Context, profileId: String, newName: String) {
        val profiles = loadProfiles(ctx)
        val target = profiles.firstOrNull { it.id == profileId } ?: return
        target.name = newName.ifBlank { if (target.packageKey.isEmpty()) "ALL GAMES" else target.packageKey }
        saveProfiles(ctx, profiles)
    }
// ─────────────────────────────────────────────────────────────────────────
    // Daemon sync + CSV contract
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serialises the enabled pins of [profile] into the Red Corner CSV contract and pushes
     * them to the kernel touch router. Honours the Red Corner master "use_mapping" switch.
     */
    fun pushProfileToDaemon(ctx: Context, profile: MacroProfile) {
        val density = ctx.resources.displayMetrics.density
        // Only TOUCH_SCREEN pins go to the kernel touch router — volume key pins are dispatched
        // separately by NukeMacroEngine and must NOT absorb physical touches at their coordinates.
        val csv = if (profile.useMapping) {
            pinsDaemonCsv(
                profile.pins.filter { it.enabled && it.triggerSource == MacroTriggerSource.TOUCH_SCREEN },
                density
            )
        } else {
            ""
        }
        NukeConnectionManager.syncMacroPins(csv)
        Log.d(TAG, "Daemon pins synced (useMapping=${profile.useMapping}, pins=${profile.pins.size}, density=$density)")
    }

    /** Builds the ';'-joined CSV of [pins], exactly matching the daemon parser fields. */
    fun pinsDaemonCsv(pins: List<MacroPinConfig>, density: Float = 3f): String {
        return pins.joinToString(";") { pin ->
            val rPx = (pin.radiusDp * density).coerceAtLeast(24f)
            val isTargetMode = pin.mode == MacroTriggerMode.SWIPE || pin.mode == MacroTriggerMode.MIRROR || pin.mode == MacroTriggerMode.COMBO
            val effTargetX = if (isTargetMode) pin.targetXRatio else pin.xRatio
            val effTargetY = if (isTargetMode) pin.targetYRatio else pin.yRatio
            pin.id + "," +
                pin.index + "," +
                pin.xRatio + "," +
                pin.yRatio + "," +
                rPx + "," +
                pin.mode.daemonCode() + "," +
                pin.repeatCount + "," +
                pin.intervalMs + "," +
                pin.holdDurationMs + "," +
                effTargetX + "," +
                effTargetY + "," +
                pin.invertX + "," +
                pin.invertY + "," +
                pin.sensX + "," +
                pin.sensY + "," +
                pin.startDelayMs + "," +
                pin.tapDurationMs + "," +
                pin.enabled + "," +
                pin.sanitizedLabel() + "," +
                pin.swipeDurationMs + "," +
                (if (pin.linkedPinIds.isEmpty()) "none" else pin.linkedPinIds.joinToString("|")) + "," +
                pin.multiPinDelayMs
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON serialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun MacroProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packageKey", packageKey)
        put("useMapping", useMapping)
        put("sensX", sensX.toDouble())
        put("sensY", sensY.toDouble())
        val arr = JSONArray()
        pins.forEach { arr.put(it.toJson()) }
        put("pins", arr)
    }

    private fun JSONObject.toProfile(): MacroProfile {
        val profile = MacroProfile(
            id = optString("id", java.util.UUID.randomUUID().toString()),
            name = optString(
                "name",
                if (optString("packageKey", "").isBlank()) "ALL GAMES" else optString("packageKey")
            ),
            packageKey = optString("packageKey", ""),
            useMapping = optBoolean("useMapping", true),
            sensX = optDouble("sensX", 1.0).toFloat().coerceIn(0.1f, 5.0f),
            sensY = optDouble("sensY", 1.0).toFloat().coerceIn(0.1f, 5.0f)
        )
        val arr = optJSONArray("pins")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i).toPinConfig() }.getOrNull()?.let { profile.pins.add(it) }
            }
        }
        return profile
    }
    private fun MacroPinConfig.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("index", index)
        put("label", label)
        put("enabled", enabled)
        put("triggerSource", triggerSource.name)
        put("xRatio", xRatio.toDouble())
        put("yRatio", yRatio.toDouble())
        put("mode", mode.name)
        put("startDelayMs", startDelayMs)
        put("repeatCount", repeatCount)
        put("intervalMs", intervalMs)
        put("tapDurationMs", tapDurationMs)
        put("holdDurationMs", holdDurationMs)
        put("swipeDurationMs", swipeDurationMs)
        put("targetXRatio", targetXRatio.toDouble())
        put("targetYRatio", targetYRatio.toDouble())
        put("invertX", invertX)
        put("invertY", invertY)
        put("sensX", sensX.toDouble())
        put("sensY", sensY.toDouble())
        put("radiusDp", radiusDp.toDouble())
        put("color", color)
        put("isLocked", isLocked)
        val linkedArr = JSONArray()
        linkedPinIds.forEach { linkedArr.put(it) }
        put("linkedPinIds", linkedArr)
        put("multiPinDelayMs", multiPinDelayMs)
    }

    private fun JSONObject.toPinConfig(): MacroPinConfig {
        val modeStr = optString("mode", MacroTriggerMode.REPEAT_TAP.name)
        val mode = runCatching { MacroTriggerMode.valueOf(modeStr) }.getOrDefault(MacroTriggerMode.REPEAT_TAP)
        val triggerSourceStr = optString("triggerSource", MacroTriggerSource.TOUCH_SCREEN.name)
        val triggerSource = runCatching { MacroTriggerSource.valueOf(triggerSourceStr) }.getOrDefault(MacroTriggerSource.TOUCH_SCREEN)
        val linkedList = mutableListOf<String>()
        val linkedArr = optJSONArray("linkedPinIds")
        if (linkedArr != null) {
            for (i in 0 until linkedArr.length()) {
                val item = linkedArr.optString(i, "")
                if (item.isNotBlank()) linkedList.add(item)
            }
        }
        val multiPinDelay = optLong("multiPinDelayMs", 0L)

        return MacroPinConfig(
            id = optString("id", java.util.UUID.randomUUID().toString()),
            index = optInt("index", 1),
            label = optString("label", ""),
            enabled = optBoolean("enabled", true),
            triggerSource = triggerSource,
            xRatio = optDouble("xRatio", 0.5).toFloat().coerceIn(0.0f, 1.0f),
            yRatio = optDouble("yRatio", 0.5).toFloat().coerceIn(0.0f, 1.0f),
            mode = mode,
            startDelayMs = optLong("startDelayMs", 0L),
            repeatCount = optInt("repeatCount", 0),
            intervalMs = optLong("intervalMs", 20L),
            tapDurationMs = optLong("tapDurationMs", 16L),
            holdDurationMs = optLong("holdDurationMs", 300L),
            swipeDurationMs = optLong("swipeDurationMs", 120L),
            targetXRatio = optDouble("targetXRatio", 0.65).toFloat().coerceIn(0.0f, 1.0f),
            targetYRatio = optDouble("targetYRatio", 0.5).toFloat().coerceIn(0.0f, 1.0f),
            invertX = optBoolean("invertX", false),
            invertY = optBoolean("invertY", false),
            sensX = optDouble("sensX", 1.0).toFloat().coerceIn(0.1f, 5.0f),
            sensY = optDouble("sensY", 1.0).toFloat().coerceIn(0.1f, 5.0f),
            radiusDp = optDouble("radiusDp", 36.0).toFloat(),
            color = optInt("color", 0xFF00E5FF.toInt()),
            isLocked = optBoolean("isLocked", false),
            linkedPinIds = linkedList,
            multiPinDelayMs = multiPinDelay
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy v2 migration
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadLegacyPins(ctx: Context): List<MacroPinConfig> {
        return try {
            val sp = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val json = sp.getString(LEGACY_KEY_PINS, null) ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { runCatching { arr.getJSONObject(it).toPinConfig() }.getOrNull() }
        } catch (e: Exception) {
            Log.w(TAG, "loadLegacyPins failed: ${e.message}")
            emptyList()
        }
    }
}