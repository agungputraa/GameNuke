package com.neon.gametweak

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * IntegrityGuard — Multi-layer anti-tamper, anti-repackaging, and anti-mod verification.
 *
 * Enforces:
 *  1. Dual-Path Signature Verification (PackageInfo + PackageArchiveInfo disk audit)
 *  2. XOR-Obfuscated Certificate Hash (resistant to smali string search/replace)
 *  3. Automated Signature Killer Detection (MT Manager, Lucky Patcher injection artifacts)
 *  4. In-Memory Dynamic Instrumentation Detection (Frida, Xposed, Substrate, TracerPid)
 *  5. Dex Container Integrity Verification
 *
 * Balanced & Non-Aggressive Policy:
 *  - Legitimate user sideloads from browsers/files are 100% permitted without false positives.
 *  - Root / Shizuku / iADB privileged gaming tools are fully respected.
 *  - Never causes OS crashes or device freezes; gracefully neutralizes tuning engines if tampered.
 */
object IntegrityGuard {

    @Volatile private var checked = false
    @Volatile private var compromised = false
    @Volatile private var reasonStr: String = ""

    // Masked SHA-256 fingerprint of the official Game Nuke release signing certificate.
    // Obfuscated to prevent plaintext string grep/replaces in disassembled smali.
    private const val CERT_MASK: Byte = 0x5C
    private val MASKED_CERT_HASH = byteArrayOf(
        110, 108, 101, 111, 111, 56, 62, 63, 58, 56, 109, 108, 101, 106, 105, 62,
        111, 105, 61, 62, 109, 58, 109, 106, 104, 109, 105, 62, 63, 108, 110, 100,
        109, 63, 107, 56, 56, 109, 62, 107, 58, 100, 58, 104, 56, 105, 104, 108,
        108, 57, 100, 62, 63, 108, 104, 110, 104, 62, 62, 57, 100, 101, 108, 58
    )

    private fun getExpectedCertHash(): String {
        return MASKED_CERT_HASH.map { (it.toInt() xor CERT_MASK.toInt()).toByte() }
            .toByteArray().toString(Charsets.UTF_8)
    }

    private val FRIDA_INDICATORS = listOf(
        "frida-server", "frida-agent", "re.frida.server", "frida-gadget",
        "xposed", "lspatch", "lsplant", "objection", "substrate"
    )

    private val FRIDA_PORTS = intArrayOf(27042, 27043)

    // Known automated signature bypassers & repack patcher artifacts
    private val SUSPICIOUS_APK_ENTRIES = listOf(
        "assets/bin/mt/",
        "assets/kill-signature",
        "lib/arm64-v8a/libmt.so",
        "lib/armeabi-v7a/libmt.so"
    )

    fun isCompromised(): Boolean = compromised
    fun getReason(): String = reasonStr

    fun check(context: Context): Result {
        if (checked) return Result(compromised, reasonStr)
        checked = true
        val flags = mutableListOf<String>()

        // 1. Debuggable check (release builds only)
        if (isDebuggable(context)) flags += "debuggable_flag"

        // 2. Debugger attached check (release builds only)
        if (isDebuggerAttached()) flags += "debug_attached"

        // 3. Dynamic instrumentation check (Frida / Xposed / Substrate)
        if (isFridaPresent()) flags += "instrumentation"

        // 4. Repackaging & Automated Signature Killer artifact check
        if (hasSuspiciousApkArtifacts(context)) flags += "apk_repack_injected"

        // 5. Verification of classes.dex container presence
        if (!verifyDexIntegrity(context)) flags += "dex_integrity_failure"

        // 6. Dual-Path signature verification against official certificate
        if (!verifySignatureHash(context)) flags += "invalid_signature"

        compromised = flags.isNotEmpty()
        reasonStr = flags.joinToString(",")
        return Result(compromised, reasonStr)
    }

    private fun isDebuggable(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun isDebuggerAttached(): Boolean {
        if (BuildConfig.DEBUG) return false
        return try { Debug.isDebuggerConnected() } catch (_: Throwable) { false }
    }

    private fun isFridaPresent(): Boolean {
        if (scanProcMaps()) return true
        if (scanTracerPid()) return true
        if (scanFridaPorts()) return true
        return false
    }

    private fun scanProcMaps(): Boolean {
        return try {
            val maps = File("/proc/self/maps")
            if (!maps.canRead()) return false
            maps.useLines { seq ->
                seq.any { line ->
                    val low = line.lowercase()
                    FRIDA_INDICATORS.any { low.contains(it) }
                }
            }
        } catch (_: Throwable) { false }
    }

    private fun scanTracerPid(): Boolean {
        if (BuildConfig.DEBUG) return false
        return try {
            val status = File("/proc/self/status")
            if (!status.canRead()) return false
            status.useLines { seq ->
                seq.firstOrNull { it.startsWith("TracerPid:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull()
                    ?.let { it > 0 } ?: false
            }
        } catch (_: Throwable) { false }
    }

    private fun scanFridaPorts(): Boolean {
        return FRIDA_PORTS.any { port ->
            runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 150)
                    true
                }
            }.getOrDefault(false)
        }
    }

    private fun hasSuspiciousApkArtifacts(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        return try {
            // Check if wrapper application class was injected
            val appClassName = context.applicationContext?.javaClass?.name ?: ""
            if (appClassName.contains("bin.mt") || appClassName.contains("kill.signature") || appClassName.contains("chelpus")) {
                return true
            }

            val apkPath = context.applicationInfo.sourceDir
            val file = File(apkPath)
            if (!file.exists()) return false
            val zip = ZipFile(file)
            try {
                val entries = zip.entries().toList()
                SUSPICIOUS_APK_ENTRIES.any { sus -> entries.any { it.name.startsWith(sus) } }
            } finally {
                zip.close()
            }
        } catch (_: Throwable) { false }
    }

    private fun verifyDexIntegrity(context: Context): Boolean {
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val file = File(apkPath)
            if (!file.exists()) return true
            val zip = ZipFile(file)
            try {
                val dexEntry = zip.getEntry("classes.dex")
                dexEntry != null && dexEntry.size > 0
            } finally {
                zip.close()
            }
        } catch (_: Throwable) { true }
    }

    fun verifySignatureHash(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val expectedSha256Hex = getExpectedCertHash()
        return try {
            val pm = context.packageManager
            // 1. Primary verification via active PackageInfo
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
            } ?: return false

            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(sigBytes).joinToString("") { "%02x".format(it) }
            if (!hash.equals(expectedSha256Hex, ignoreCase = true)) {
                return false
            }

            // 2. Secondary verification directly against APK file archive to bypass in-memory hooks
            val apkPath = context.applicationInfo.sourceDir
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
            }
            val archiveSigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo?.signatures?.firstOrNull()?.toByteArray()
            }

            if (archiveSigBytes != null) {
                val archiveHash = md.digest(archiveSigBytes).joinToString("") { "%02x".format(it) }
                if (!archiveHash.equals(expectedSha256Hex, ignoreCase = true)) {
                    return false
                }
            }

            true
        } catch (_: Throwable) { false }
    }

    data class Result(val compromised: Boolean, val reason: String)
}
