package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import kotlin.math.roundToInt

/**
 * Read-only pressure radar plus explicit user-triggered safe background kill.
 * It never automatically kills a process and never force-stops the active game or Game Nuke.
 */
class NukeResourceRadar(
    context: Context,
    private val shell: NukeGamingShellGateway,
    private val gamePackage: String,
) {
    enum class Severity { NORMAL, ELEVATED, HIGH }

    data class ProcessLoad(
        val packageName: String,
        val cpuPercent: Float?,
        val rssMb: Long?,
        val protected: Boolean,
    )

    data class Snapshot(
        val items: List<ProcessLoad>,
        val thermalStatus: Int,
        val severity: Severity,
        val note: String,
    )

    private val appContext = context.applicationContext
    private val protectedExact = setOf(
        appContext.packageName,
        gamePackage,
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.google.android.gms",
        "com.google.android.gsf",
    )

    fun scan(): Snapshot {
        val cpu = if (shell.connected()) parseCpu(shell.readCpuProcessSnapshot()) else emptyMap()
        val rss = if (shell.connected()) parseRss(shell.readRssProcessSnapshot()) else emptyMap()
        val names = LinkedHashSet<String>().apply { addAll(cpu.keys); addAll(rss.keys) }
        val localRss = LinkedHashMap<String, Long>()
        if (names.isEmpty()) {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val running = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
            val pids = running.map { it.pid }.toIntArray()
            val info = if (pids.isNotEmpty()) runCatching { am?.getProcessMemoryInfo(pids).orEmpty() }.getOrDefault(emptyArray()) else emptyArray()
            running.forEachIndexed { index, proc ->
                val pkg = proc.processName.substringBefore(':')
                if (validPackage(pkg)) {
                    names += pkg
                    localRss[pkg] = info.getOrNull(index)?.totalPss?.div(1024L) ?: 0L
                }
            }
        }
        val items = names.mapNotNull { pkg ->
            if (!validPackage(pkg)) return@mapNotNull null
            ProcessLoad(
                packageName = pkg,
                cpuPercent = cpu[pkg],
                rssMb = rss[pkg] ?: localRss[pkg],
                protected = isProtected(pkg) || !shell.connected(),
            )
        }.sortedWith(
            compareByDescending<ProcessLoad> { it.cpuPercent ?: 0f }
                .thenByDescending { it.rssMb ?: 0L },
        ).take(12)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching { pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        val topCpu = items.maxOfOrNull { it.cpuPercent ?: 0f } ?: 0f
        val topRam = items.maxOfOrNull { it.rssMb ?: 0L } ?: 0L
        val severity = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE || topCpu >= 45f || topRam >= 1_500L -> Severity.HIGH
            thermal >= PowerManager.THERMAL_STATUS_MODERATE || topCpu >= 25f || topRam >= 800L -> Severity.ELEVATED
            else -> Severity.NORMAL
        }
        return Snapshot(
            items = items,
            thermalStatus = thermal,
            severity = severity,
            note = when (severity) {
                Severity.HIGH -> "High device pressure detected"
                Severity.ELEVATED -> "Elevated background pressure"
                Severity.NORMAL -> "No major background pressure detected"
            },
        )
    }

    fun kill(packageName: String): NukeCommandResult {
        if (!validPackage(packageName) || isProtected(packageName)) {
            return NukeCommandResult(-1, "Protected package")
        }
        return shell.killBackgroundPackage(packageName)
    }

    private fun parseCpu(result: NukeCommandResult): Map<String, Float> {
        if (!result.isSuccess) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        // Common dumpsys cpuinfo row: "  12% 1234/com.example: 8% user + 4% kernel"
        val regex = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+\\d+/([^: ]+)", RegexOption.IGNORE_CASE)
        result.output.lineSequence().forEach { line ->
            val m = regex.find(line) ?: return@forEach
            val pct = m.groupValues[1].toFloatOrNull() ?: return@forEach
            val pkg = m.groupValues[2].substringBefore(':')
            if (validPackage(pkg)) out[pkg] = maxOf(out[pkg] ?: 0f, pct.coerceIn(0f, 100f))
        }
        return out
    }

    private fun parseRss(result: NukeCommandResult): Map<String, Long> {
        if (!result.isSuccess) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        result.output.lineSequence().drop(1).forEach { raw ->
            val cols = raw.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@forEach
            val rssKb = cols[cols.size - 2].toLongOrNull() ?: return@forEach
            val pkg = cols.last().substringBefore(':')
            if (validPackage(pkg)) out[pkg] = maxOf(out[pkg] ?: 0L, (rssKb / 1024.0).roundToInt().toLong())
        }
        return out
    }

    private fun isProtected(pkg: String): Boolean {
        if (pkg in protectedExact || pkg == "android" || pkg.startsWith("android.") || pkg.startsWith("com.android.")) return true
        val lower = pkg.lowercase()
        if (lower.contains("systemui") || lower.contains("launcher") || lower.contains("keyboard") ||
            lower.contains("inputmethod") || lower.contains("dialer") || lower.contains("telecom")) return true
        if (pkg in dynamicProtectedPackages()) return true

        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") appContext.packageManager.getApplicationInfo(pkg, 0)
            }
        }.getOrNull()
        return info == null ||
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun dynamicProtectedPackages(): Set<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION") appContext.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.packageName?.let(result::add)
        }
        runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf(::validPackage)?.let(result::add)
        }
        runCatching {
            (appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.takeIf(::validPackage)?.let(result::add)
        }
        return result
    }

    private fun validPackage(value: String): Boolean =
        value.length in 3..255 && value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
}
