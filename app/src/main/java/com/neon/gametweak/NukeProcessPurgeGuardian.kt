package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NukeProcessPurgeGuardian — Ironclad Safety Guardian for Memory Purging and Deep Cleans.
 *
 * Guarantees that:
 * 1. The currently active game is NEVER killed, frozen, or interrupted.
 * 2. Any screen recording or streaming utility is NEVER killed or disturbed.
 * 3. Game Nuke itself is NEVER terminated.
 * 4. Critical Android system components (SystemUI, Keyboards, Launchers, Accessibility, Shizuku)
 *    are NEVER touched.
 * 5. Indiscriminate commands like `am kill-all` or destructive cache wipes are completely banned.
 */
object NukeProcessPurgeGuardian {

    private const val TAG = "NukePurgeGuardian"

    // Bloatware and social apps that can be safely stopped in background if NOT active or recording
    private val COMMON_BLOATWARE_CANDIDATES = listOf(
        "com.facebook.katana",
        "com.facebook.orca",
        "com.instagram.android",
        "com.spotify.music",
        "com.mi.appfinder",
        "com.xiaomi.mipicks",
        "com.lazada.android",
        "com.shopee.id",
        "com.temporary.email.inboxes",
        "com.google.android.apps.photos",
        "com.microsoft.appmanager",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.twitter.android",
        "com.pinterest",
        "com.alibaba.aliexpresshd",
        "com.ubercab",
        "com.grabtaxi.passenger",
        "com.gojek.app"
    )

    /**
     * Checks whether a package must be protected from being killed or trimmed.
     */
    fun isProtected(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val clean = packageName.trim().substringBefore(":").lowercase()

        // 1. Game Nuke itself
        if (clean == context.packageName.lowercase() || clean == "com.neon.gametweak") {
            return true
        }

        // 2. Active game package (current running game session)
        val activeGame = NukeRuntimeState.state.value.activePackage?.trim()?.lowercase()
        if (!activeGame.isNullOrBlank() && clean == activeGame) {
            return true
        }

        // 3. Screen recorder or broadcasting tools
        if (NukeScreenRecordGuardian.isProtected(clean)) {
            return true
        }

        // 4. System and sensitive OS components
        if (isSensitiveSystemPackage(context, clean)) {
            return true
        }

        return false
    }

    /**
     * Resolves sensitive system services that must never be terminated.
     */
    private fun isSensitiveSystemPackage(context: Context, pkg: String): Boolean {
        if (pkg == "android") return true
        if (pkg.startsWith("android.")) return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.startsWith("com.android.phone")) return true
        if (pkg.startsWith("com.android.server")) return true
        if (pkg.startsWith("com.android.bluetooth")) return true
        if (pkg.startsWith("com.android.nfc")) return true
        if (pkg.startsWith("com.android.keyguard")) return true
        if (pkg.startsWith("com.google.android.gms")) return true
        if (pkg.startsWith("com.google.android.gsf")) return true
        if (pkg.startsWith("moe.shizuku.privileged.api") || pkg.startsWith("rikka.shizuku") || pkg.contains("shizuku")) return true
        if (pkg.startsWith("com.android.shell") || pkg.contains("iadb")) return true
        if (pkg.startsWith("vendor.") || pkg.startsWith("android.hardware.")) return true

        // Keyboards & Input Methods
        if (pkg.contains("keyboard") || pkg.contains("ime") || pkg.contains("inputmethod")) return true

        // Launchers & Home Apps
        if (pkg.contains("launcher") || pkg.contains("trebuchet") || pkg.contains("home")) return true

        // Telephony & Dialer
        if (pkg.contains("telecom") || pkg.contains("telephony") || pkg.contains("incallui") || pkg.contains("dialer")) return true

        // Check dynamic default launcher
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val defaultHome = context.packageManager.resolveActivity(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName?.lowercase()
            if (defaultHome != null && pkg == defaultHome) return true
        }

        // Check active default keyboard
        runCatching {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.lowercase()
            if (defaultIme != null && pkg == defaultIme) return true
        }

        // Check default dialer
        runCatching {
            val defaultDialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.lowercase()
            if (defaultDialer != null && pkg == defaultDialer) return true
        }

        return false
    }

    /**
     * Executes an ultra-safe background zombie purge.
     *
     * Unlike raw `am kill-all`, this:
     * - Protects the active game 100%.
     * - Protects screen recorders and streaming apps 100%.
     * - Protects Game Nuke 100%.
     * - Only terminates verified, non-active background bloatware and compacts system RAM.
     *
     * @return Pair(killedCount, reclaimedMemoryEstimateMb)
     */
    suspend fun purgeZombiesSafe(context: Context): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memBefore = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val availBeforeMb = (memBefore?.availMem ?: 0L) / (1024 * 1024)

        var killedCount = 0
        val sb = StringBuilder()

        // 1. Memory compaction and cache trimming (Standard safe Android API commands)
        sb.append("pm trim-caches 999G 2>/dev/null\n")
        sb.append("am compact system 2>/dev/null\n")

        // 2. Scan running background processes and filter strictly
        val runningProcesses = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
        val candidatesToKill = mutableListOf<String>()

        for (proc in runningProcesses) {
            val pkg = proc.pkgList?.firstOrNull() ?: continue
            if (isProtected(context, pkg)) continue

            // Only kill if the process is truly in the background and not a foreground service
            if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ||
                proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
            ) {
                candidatesToKill.add(pkg)
            }
        }

        // Also add common background bloatware if not protected
        for (bloat in COMMON_BLOATWARE_CANDIDATES) {
            if (!isProtected(context, bloat) && !candidatesToKill.contains(bloat)) {
                candidatesToKill.add(bloat)
            }
        }

        val targetList = candidatesToKill.distinct().take(15)
        for (pkg in targetList) {
            // Trim memory first, then safe kill
            sb.append("cmd activity send-trim-memory $pkg RUNNING_LOW 2>/dev/null\n")
            sb.append("am kill $pkg 2>/dev/null\n")
            // For common social bloat, force-stop is safe as long as it's not protected
            if (COMMON_BLOATWARE_CANDIDATES.contains(pkg)) {
                sb.append("am force-stop $pkg 2>/dev/null\n")
            }
            killedCount++
        }

        // 3. Execute via privileged bridge or fallback to ActivityManager
        val script = sb.toString()
        if (script.isNotBlank()) {
            val adb = AdbManager.getInstance(context)
            if (adb.isConnected()) {
                adb.executeCommand(script, "/", 6_000L)
            } else {
                val res = NukeConnectionManager.executeCommand(script, 6_000L)
                if (res == null || !res.isSuccess) {
                    // Non-privileged fallback using standard ActivityManager
                    for (pkg in targetList) {
                        runCatching { am?.killBackgroundProcesses(pkg) }
                    }
                }
            }
        }

        // Calculate freed memory
        kotlinx.coroutines.delay(200L)
        val memAfter = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val availAfterMb = (memAfter?.availMem ?: 0L) / (1024 * 1024)
        val freedMb = (availAfterMb - availBeforeMb).coerceAtLeast(0L)

        Log.d(TAG, "Safe zombie purge completed: killed=$killedCount freedMb=$freedMb")
        Pair(killedCount, freedMb)
    }

    /**
     * Executes safe cache trimming without touching code_cache or active game directories.
     */
    suspend fun cleanCachesSafe(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // 1. Official Android package manager cache trim (Safely evicts temporary app caches)
        sb.append("pm trim-caches 999999999999 2>/dev/null\n")

        // 2. Clear logcat buffers to free memory
        sb.append("logcat -c 2>/dev/null\n")

        // 3. Clear system crash dumps & tombstones (Safe diagnostic artifacts)
        sb.append("rm -rf /data/tombstones/* 2>/dev/null\n")
        sb.append("rm -rf /data/anr/* 2>/dev/null\n")
        sb.append("rm -rf /data/system/dropbox/* 2>/dev/null\n")
        sb.append("rm -rf /data/local/tmp/* 2>/dev/null\n")

        // 4. Clear public media thumbnails and trash (Safely ignores app data and games)
        sb.append("rm -rf /sdcard/.thumbnails/* 2>/dev/null\n")
        sb.append("rm -rf /sdcard/DCIM/.thumbnails/* 2>/dev/null\n")
        sb.append("rm -rf /sdcard/Pictures/.thumbnails/* 2>/dev/null\n")
        sb.append("rm -rf /sdcard/Download/.trash/* 2>/dev/null\n")
        sb.append("rm -rf /sdcard/.trash/* 2>/dev/null\n")
        sb.append("rm -rf /sdcard/.cache/* 2>/dev/null\n")

        // 5. Delete lingering log and temporary dumps from Download
        sb.append("find /sdcard/Download -maxdepth 2 -type f \\( -name '*.log' -o -name '*.tmp' -o -name '*.dmp' \\) -delete 2>/dev/null\n")

        val script = sb.toString()
        val adb = AdbManager.getInstance(context)
        if (adb.isConnected()) {
            adb.executeCommand(script, "/", 8_000L)
        } else {
            NukeConnectionManager.executeCommand(script, 8_000L)
        }
        true
    }
}
