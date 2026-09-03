package com.neon.gametweak

import kotlin.math.roundToInt

/**
 * Structured ADB gateway for gaming operations.
 *
 * The floating core never accepts arbitrary shell text from UI or remote callers. Every operation here is
 * deliberately narrow, validates user-controlled values, and returns the remote shell exit code.
 */
class NukeGamingShellGateway(private val adb: AdbManager) {
    private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val numericSetting = Regex("-?\\d+(?:\\.\\d+)?")
    private val overlayTokenRegex = Regex("[A-Za-z0-9_.+\\-]+")
    private val overlayConfigRegex = Regex("[A-Za-z0-9_.,:=+\\-]+")
    private data class CpuSample(val total: Long, val idle: Long)
    private data class CachedSupport(val value: Boolean, val at: Long)
    @Volatile private var lastCpuSample: CpuSample? = null
    private val supportCache = java.util.concurrent.ConcurrentHashMap<String, CachedSupport>()

    private inline fun cachedSupport(key: String, probe: () -> Boolean): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        supportCache[key]?.takeIf { now - it.at in 0..300_000L }?.let { return it.value }
        return probe().also { supportCache[key] = CachedSupport(it, now) }
    }

    fun connected(): Boolean = adb.isConnected()

    fun readDisplaySize(): NukeCommandResult = adb.executeCommand(
        "wm size", "/", 4_000L, 8_192,
    )

    fun setDisplaySize(width: Int, height: Int): NukeCommandResult {
        if (width !in 480..6000 || height !in 480..6000) return denied("Display size outside safety bounds")
        return adb.executeCommand("wm size ${width}x${height}", "/", 5_000L, 8_192)
    }

    fun resetDisplaySize(): NukeCommandResult = adb.executeCommand(
        "wm size reset", "/", 5_000L, 8_192,
    )

    fun readDisplayDensity(): NukeCommandResult = adb.executeCommand(
        "wm density", "/", 4_000L, 8_192,
    )

    fun setDisplayDensity(density: Int): NukeCommandResult {
        if (density !in 120..1000) return denied("Display density outside safety bounds")
        return adb.executeCommand("wm density $density", "/", 5_000L, 8_192)
    }

    fun resetDisplayDensity(): NukeCommandResult = adb.executeCommand(
        "wm density reset", "/", 5_000L, 8_192,
    )

    fun supportsDisplayOverrides(): Boolean {
        if (!connected()) return false
        val size = readDisplaySize()
        val density = readDisplayDensity()
        return size.isSuccess && density.isSuccess &&
            size.output.contains("size", ignoreCase = true) &&
            density.output.contains("density", ignoreCase = true)
    }

    fun readSetting(namespace: String, key: String): String? {
        if (namespace !in setOf("system", "global", "secure")) return null
        if (!key.matches(Regex("[a-zA-Z0-9_.-]{1,96}"))) return null
        val result = adb.executeCommand("settings get $namespace $key", "/", 4_000L, 4_096)
        if (!result.isSuccess) return null
        return result.output.trim().takeIf { it.isNotBlank() && it != "null" }
    }

    fun writeNumericSetting(namespace: String, key: String, value: String): NukeCommandResult {
        if (namespace !in setOf("system", "global")) return denied("Unsupported settings namespace")
        if (!key.matches(Regex("[a-zA-Z0-9_.-]{1,96}"))) return denied("Invalid settings key")
        if (!numericSetting.matches(value)) return denied("Invalid numeric setting")
        return adb.executeCommand("settings put $namespace $key $value", "/", 5_000L, 8_192)
    }

    fun deleteSetting(namespace: String, key: String): NukeCommandResult {
        if (namespace !in setOf("system", "global")) return denied("Unsupported settings namespace")
        if (!key.matches(Regex("[a-zA-Z0-9_.-]{1,96}"))) return denied("Invalid settings key")
        return adb.executeCommand("settings delete $namespace $key", "/", 5_000L, 8_192)
    }

    fun readGameDefaultFrameRateGuard(): String? {
        val result = adb.executeCommand(
            "getprop debug.graphics.game_default_frame_rate.disabled",
            "/",
            4_000L,
            4_096,
        )
        return result.takeIf { it.isSuccess }?.output?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setGameDefaultFrameRateGuard(disabled: Boolean): NukeCommandResult {
        // Exact debug property only. Never expose generic setprop through the floating UI.
        return adb.executeCommand(
            "setprop debug.graphics.game_default_frame_rate.disabled ${if (disabled) "true" else "false"}",
            "/",
            5_000L,
            8_192,
        )
    }

    fun setPowerSaver(enabled: Boolean): NukeCommandResult = adb.executeCommand(
        "cmd power set-mode ${if (enabled) 1 else 0}",
        "/",
        6_000L,
        8_192,
    )

    fun compactSystem(): NukeCommandResult = adb.executeCommand(
        "am compact system",
        "/",
        15_000L,
        32_768,
    )

    /**
     * Global `am kill-all` is deliberately disabled. Even though ActivityManager normally targets
     * cached processes, a game booster should never issue an indiscriminate kill. Use
     * [killBackgroundPackage] on vetted user-app candidates instead.
     */
    fun killSafeBackground(): NukeCommandResult = denied("Global background kill disabled")

    fun readCpuProcessSnapshot(): NukeCommandResult = adb.executeCommand(
        "dumpsys cpuinfo",
        "/",
        4_500L,
        65_536,
    )

    fun readRssProcessSnapshot(): NukeCommandResult = adb.executeCommand(
        "ps -A -o PID,RSS,NAME",
        "/",
        4_500L,
        131_072,
    )

    data class ProcessMemoryRow(val packageName: String, val rssKb: Long)

    /** Parse only package-looking process names from the read-only process snapshot. */
    fun readProcessMemoryRows(): List<ProcessMemoryRow> {
        val result = readRssProcessSnapshot()
        if (!result.isSuccess) return emptyList()
        val rows = LinkedHashMap<String, Long>()
        result.output.lineSequence().drop(1).forEach { raw ->
            val cols = raw.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@forEach
            val rssKb = cols.getOrNull(cols.size - 2)?.toLongOrNull() ?: return@forEach
            val pkg = cols.lastOrNull()?.substringBefore(':') ?: return@forEach
            val safe = safePackage(pkg) ?: return@forEach
            rows[safe] = maxOf(rows[safe] ?: 0L, rssKb.coerceAtLeast(0L))
        }
        return rows.entries.map { ProcessMemoryRow(it.key, it.value) }
            .sortedByDescending { it.rssKb }
    }

    /** Android ActivityManager background kill. This is intentionally not force-stop. */
    fun killBackgroundPackage(packageName: String): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        return adb.executeCommand("am kill $pkg", "/", 6_000L, 16_384)
    }

    fun supportsSafeBackgroundSweep(): Boolean = cachedSupport("background_sweep") {
        val result = adb.executeCommand("am help", "/", 5_000L, 24_576)
        if (!result.isSuccess || isUnsupportedOutput(result.output.lowercase())) return@cachedSupport false
        // We only use package-scoped `am kill`, never global `kill-all`.
        Regex("(?m)^\\s*kill\\s+", RegexOption.IGNORE_CASE).containsMatchIn(result.output) ||
            result.output.contains("kill <PACKAGE>", ignoreCase = true)
    }

    fun trimCaches(targetFreeBytes: Long): NukeCommandResult {
        val safeTarget = targetFreeBytes.coerceIn(256L * MIB, 32L * GIB)
        return adb.executeCommand(
            "pm trim-caches $safeTarget",
            "/",
            90_000L,
            32_768,
        )
    }

    fun compileSpeedProfile(packageName: String): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        return adb.executeCommand(
            "cmd package compile -f -m speed-profile $pkg",
            "/",
            120_000L,
            65_536,
        )
    }

    fun compileSpeed(packageName: String): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        return adb.executeCommand(
            "cmd package compile -f -m speed $pkg",
            "/",
            120_000L,
            65_536,
        )
    }

    fun gameModes(packageName: String): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        return adb.executeCommand("cmd game list-modes $pkg", "/", 8_000L, 32_768)
    }

    /** Read the per-game OEM intervention config without inventing a profile. */
    fun readGameOverlayConfig(packageName: String): String? {
        val pkg = safePackage(packageName) ?: return null
        val result = adb.executeCommand("device_config get game_overlay $pkg", "/", 6_000L, 16_384)
        if (!result.isSuccess) return null
        return result.output.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    /**
     * Build a conservative 90% Performance-mode backbuffer patch while preserving every other
     * valid field/mode already supplied by Android or the OEM. Returning null is intentional: if
     * an unfamiliar config grammar cannot be parsed safely, Game Nuke refuses to overwrite it.
     */
    fun buildGameOverlayGpuReliefConfig(baseConfig: String?): String? =
        patchGameOverlayMode(baseConfig, mode = "2", key = "downscaleFactor", value = "0.9")

    /** Explicit FPS intervention in OEM Performance mode. Any positive cap is treated as unsafe
     * for automatic Game Nuke mode switching because it may be lower than the game's native path. */
    fun performanceProfileFpsCap(baseConfig: String?): Int? {
        if (baseConfig.isNullOrBlank() || baseConfig.length > 2_048 || !overlayConfigRegex.matches(baseConfig)) return null
        val segment = baseConfig.split(':').firstOrNull { part ->
            part.split(',').any { it.equals("mode=2", ignoreCase = true) }
        } ?: return null
        return segment.split(',').firstNotNullOfOrNull { token ->
            val pair = token.split('=', limit = 2)
            if (pair.size == 2 && pair[0].equals("fps", ignoreCase = true)) pair[1].toIntOrNull()?.takeIf { it > 0 } else null
        }
    }

    /**
     * Conservative GPU relief preset. 0.90 is intentionally mild and rollback-safe. The game must
     * be restarted before the intervention can take effect, and the game/platform can opt out.
     */
    fun setGameOverlayGpuRelief(packageName: String, baseConfig: String?): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        val patched = buildGameOverlayGpuReliefConfig(baseConfig)
            ?: return denied("OEM game overlay grammar not safely patchable")
        return adb.executeCommand(
            "device_config put game_overlay $pkg $patched",
            "/",
            8_000L,
            16_384,
        )
    }

    fun restoreGameOverlayConfig(packageName: String, config: String?): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        if (config == null) {
            return adb.executeCommand("device_config delete game_overlay $pkg", "/", 8_000L, 16_384)
        }
        // This value is a snapshot read from Android itself, never raw UI text. Still keep a strict
        // grammar so a corrupted preference can never become shell input.
        val safeConfig = config.takeIf {
            it.length in 1..2_048 && overlayConfigRegex.matches(it)
        } ?: return denied("Invalid stored game overlay config")
        return adb.executeCommand("device_config put game_overlay $pkg $safeConfig", "/", 8_000L, 16_384)
    }

    fun supportsGameOverlayConfig(packageName: String): Boolean {
        val pkg = safePackage(packageName) ?: return false
        val probe = adb.executeCommand("device_config get game_overlay $pkg", "/", 6_000L, 8_192)
        if (!probe.isSuccess) return false
        val text = probe.output.lowercase()
        return !text.contains("permission denied") && !text.contains("unknown command")
    }

    fun setGameMode(packageName: String, mode: String): NukeCommandResult {
        val pkg = safePackage(packageName) ?: return denied("Invalid package")
        val safeMode = mode.lowercase().takeIf { it in setOf("standard", "performance", "battery", "custom") }
            ?: return denied("Invalid game mode")
        return adb.executeCommand("cmd game mode $safeMode $pkg", "/", 8_000L, 16_384)
    }



    fun readGameMode(packageName: String): String? {
        val result = gameModes(packageName)
        if (!result.isSuccess || isUnsupportedOutput(result.output.lowercase())) return null
        val text = result.output.lowercase()
        Regex("(?:current(?:\\s+game)?\\s+mode|mode)\\s*[:=]\\s*(standard|performance|battery|custom)")
            .find(text)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("(?:current(?:\\s+game)?\\s+mode|mode)\\s*[:=]\\s*([1-4])")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { numeric ->
                return when (numeric) { 1 -> "standard"; 2 -> "performance"; 3 -> "battery"; 4 -> "custom"; else -> null }
            }
        return listOf("performance", "battery", "custom", "standard").firstOrNull { candidate ->
            text.lineSequence().any { line -> line.contains("current", true) && line.contains(candidate, true) }
        }
    }

    fun supportsGameMode(packageName: String): Boolean {
        val result = gameModes(packageName)
        if (!result.isSuccess) return false
        val text = result.output.lowercase()
        return !isUnsupportedOutput(text) && text.contains("mode")
    }

    fun readDataSaverEnabled(): Boolean? {
        if (!connected()) return null
        val result = adb.executeCommand("cmd netpolicy get restrict-background", "/", 5_000L, 8_192)
        if (!result.isSuccess || isUnsupportedOutput(result.output.lowercase())) return null
        val text = result.output.lowercase()
        return when {
            text.contains("enabled") || Regex("\\btrue\\b").containsMatchIn(text) -> true
            text.contains("disabled") || Regex("\\bfalse\\b").containsMatchIn(text) -> false
            else -> null
        }
    }

    fun setDataSaver(enabled: Boolean): NukeCommandResult = adb.executeCommand(
        "cmd netpolicy set restrict-background ${if (enabled) "true" else "false"}",
        "/", 6_000L, 8_192,
    )

    fun supportsDataSaver(): Boolean = cachedSupport("data_saver") {
        val result = adb.executeCommand("cmd netpolicy help", "/", 6_000L, 32_768)
        result.isSuccess && !isUnsupportedOutput(result.output.lowercase()) &&
            result.output.contains("restrict-background", ignoreCase = true)
    }

    fun supportsPowerSaver(): Boolean = cachedSupport("power_saver") {
        val read = readSetting("global", "low_power")
        if (read == "0" || read == "1") return@cachedSupport true
        val result = adb.executeCommand("cmd power help", "/", 5_000L, 16_384)
        result.isSuccess && !isUnsupportedOutput(result.output.lowercase()) &&
            result.output.contains("set-mode", ignoreCase = true)
    }

    /**
     * Whole-device CPU utilisation. /proc/stat delta is preferred because it is lightweight and
     * consistent when shell can read it. dumpsys cpuinfo is a truthful OEM fallback.
     */
    @Synchronized
    fun readCpuLoadPercent(): Int? {
        if (!connected()) return null

        fun procSample(): CpuSample? {
            val result = adb.executeCommand("cat /proc/stat", "/", 1_500L, 8_192)
            if (!result.isSuccess) return null
            val aggregate = result.output.lineSequence().firstOrNull { line -> line.trimStart().startsWith("cpu ") }
                ?: return null
            val parts = aggregate.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.firstOrNull() != "cpu" || parts.size < 5) return null
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null
            val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
            return CpuSample(total = values.sum(), idle = idle)
        }

        fun fromDelta(previous: CpuSample, current: CpuSample): Int? {
            val totalDelta = current.total - previous.total
            val idleDelta = current.idle - previous.idle
            if (totalDelta <= 0L || idleDelta < 0L) return null
            return (((totalDelta - idleDelta).toDouble() / totalDelta.toDouble()) * 100.0)
                .roundToInt().coerceIn(0, 100)
        }

        val first = procSample()
        if (first != null) {
            val previous = lastCpuSample
            lastCpuSample = first
            if (previous != null) fromDelta(previous, first)?.let { return it }
            try { Thread.sleep(120L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            val second = procSample()
            if (second != null) {
                lastCpuSample = second
                fromDelta(first, second)?.let { return it }
            }
        }

        val result = adb.executeCommand("dumpsys cpuinfo", "/", 3_500L, 32_768)
        if (!result.isSuccess) return null
        val patterns = listOf(
            Regex("([0-9]+(?:\\.[0-9]+)?)%\\s+TOTAL:", RegexOption.IGNORE_CASE),
            Regex("TOTAL:\\s*([0-9]+(?:\\.[0-9]+)?)%", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(result.output)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
                return it.roundToInt().coerceIn(0, 100)
            }
        }
        return null
    }

    fun beginFrameTimestats(): NukeCommandResult = adb.executeCommand(
        "dumpsys SurfaceFlinger --timestats -clear -enable",
        "/",
        8_000L,
        32_768,
    )

    fun dumpFrameTimestats(): NukeCommandResult = adb.executeCommand(
        "dumpsys SurfaceFlinger --timestats -dump",
        "/",
        12_000L,
        1_048_576,
    )

    fun endFrameTimestats(): NukeCommandResult = adb.executeCommand(
        "dumpsys SurfaceFlinger --timestats -disable",
        "/",
        8_000L,
        32_768,
    )


    fun supportsFrameTimestats(): Boolean {
        val result = dumpFrameTimestats()
        if (!result.isSuccess) return false
        val text = result.output.lowercase()
        return !text.contains("unknown option") &&
            !text.contains("unknown command") &&
            !text.contains("permission denied") &&
            !text.contains("not supported")
    }

    fun supportsSystemCompaction(): Boolean {
        val r=adb.executeCommand("am help", "/", 6_000L, 32_768)
        return r.isSuccess && r.output.contains("compact system", ignoreCase = true)
    }

    fun supportsCacheTrim(): Boolean {
        val r=adb.executeCommand("pm help", "/", 6_000L, 32_768)
        return r.isSuccess && r.output.contains("trim-caches", ignoreCase = true)
    }

    fun supportsPackageCompile(): Boolean {
        val r=adb.executeCommand("cmd package help", "/", 7_000L, 32_768)
        return r.isSuccess && r.output.contains("compile", ignoreCase = true)
    }

    /**
     * AOSP Android 11 protects these Wi-Fi shell commands as privileged/root-only. Some OEM/newer
     * builds expose them to the paired shell UID, so this method is capability-driven only.
     */
    fun forceWifiLowLatency(enabled: Boolean): NukeCommandResult = adb.executeCommand(
        "cmd wifi force-low-latency-mode ${if (enabled) "enabled" else "disabled"}",
        "/",
        6_000L,
        16_384,
    )

    data class CpuClock(val core: Int, val khz: Long) {
        val mhz: Long get() = khz / 1_000L
    }

    /** Read-only per-core clock telemetry. Never writes a cpufreq/governor sysfs node. */
    fun readCpuClocks(): List<CpuClock> {
        if (!connected()) return emptyList()
        // One fixed, read-only shell batch replaces up to 32 sequential ADB round-trips. No user
        // input reaches this command and no sysfs node is ever opened for writing.
        val result = adb.executeCommand(
            NUKE_CPU_CLOCK_BATCH,
            "/", 2_800L, 16_384,
        )
        if (!result.isSuccess) return emptyList()
        return result.output.lineSequence().mapNotNull { raw ->
            val match = Regex("^(\\d+):(\\d+)$").matchEntire(raw.trim()) ?: return@mapNotNull null
            val core = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return@mapNotNull null
            val khz = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            CpuClock(core, khz)
        }.sortedBy { it.core }.toList()
    }

    fun supportsCpuClocks(): Boolean = readCpuClocks().isNotEmpty()

    private fun patchGameOverlayMode(
        baseConfig: String?,
        mode: String,
        key: String,
        value: String,
    ): String? {
        if (!overlayTokenRegex.matches(mode) || !overlayTokenRegex.matches(key) || !overlayTokenRegex.matches(value)) return null

        val segments = mutableListOf<LinkedHashMap<String, String>>()
        if (!baseConfig.isNullOrBlank()) {
            if (baseConfig.length > 2_048 || !overlayConfigRegex.matches(baseConfig)) return null
            for (segment in baseConfig.split(':')) {
                if (segment.isBlank()) continue
                val fields = linkedMapOf<String, String>()
                for (token in segment.split(',')) {
                    val cut = token.indexOf('=')
                    if (cut <= 0 || cut == token.lastIndex) return null
                    val k = token.substring(0, cut)
                    val v = token.substring(cut + 1)
                    if (!overlayTokenRegex.matches(k) || !overlayTokenRegex.matches(v)) return null
                    fields[k] = v
                }
                if (fields.isNotEmpty()) segments += fields
            }
        }

        val performance = segments.firstOrNull { it["mode"] == mode }
            ?: linkedMapOf<String, String>("mode" to mode).also { segments.add(it) }
        performance[key] = value

        return segments.joinToString(":") { fields ->
            fields.entries.joinToString(",") { (k, v) -> "$k=$v" }
        }.takeIf { it.isNotBlank() && it.length <= 2_048 && overlayConfigRegex.matches(it) }
    }

    private fun isUnsupportedOutput(text: String): Boolean =
        text.contains("permission denied") || text.contains("unknown command") ||
            text.contains("not supported") || text.contains("securityexception")

    private fun safePackage(value: String): String? = value.takeIf {
        it.length in 3..255 && packageRegex.matches(it)
    }

    private fun denied(message: String) = NukeCommandResult(exitCode = -1, output = message)

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
