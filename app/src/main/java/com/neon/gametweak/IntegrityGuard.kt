package com.neon.gametweak

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import androidx.annotation.Keep
import java.io.File
import java.security.MessageDigest

@Keep
object IntegrityGuard {

    @Volatile private var checked = false
    @Volatile private var compromised = false
    @Volatile private var reasonStr: String = ""

    /**
     * SHA-256 fingerprint of the official Game Nuke release signing certificate.
     * This value is intentionally obfuscated by R8 in production builds.
     * Any APK re-signed with a different key will fail this check, blocking core features.
     */
    private val RELEASE_SIGNATURE_SHA256: String? =
        if (BuildConfig.DEBUG) null
        else "20933dbcfd10965b35ab1f16415bc0281c7dd1b7f8f4d5400e8bc0424bbe890f"

    private val FRIDA_INDICATORS = listOf(
        "frida-server", "frida-agent", "linjector", "re.frida.server", "frida-gadget",
        "xposed", "lspatch", "lsplant", "objection",
    )

    private val FRIDA_PORTS = intArrayOf(27042, 27043)

    private val UNTRUSTED_PACKAGES = listOf(
        "com.gmail.heagoo.apkeditor",
        "com.gmail.heagoo.apkeditor.pro",
        "com.gmail.heagoo.pmaster",
        "com.smartworld.apkeditorpro",
        "com.zane.apkeditor",
        "lihuandsj.com.apkeditor",
        "com.xmodgame",
        "org.sbtools.gamehack",
        "com.android.vending.billing.InAppBillingService.LACK",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.forpda.lp",
        "com.android.vending.billing.InAppBillingService.LUCK",
        "com.android.vending.billing.InAppBillingService.CRAC",
        "com.android.vending.billing.InAppBillingService.CLON",
        "com.android.protips",
        "com.zhangkun",
        "uret.jasi2169.patcher",
        "com.cigstudio.editor.no_root",
        "com.cigstudio.editor.root",
        "org.creeplays.hack",
        "com.gameguardian",
        "com.fingersoft.hcr",
        "com.cih.game_cih",
        "cih.gamecih",
        "cih.gamecih2",
        "cih.game_cih_pro",
        "com.cih.gamecih",
        "com.cih.gamecih2",
        "com.charles.lpoqasert",
        "catch_.me_.if_.you_.can_",
        "idv.aqua.bulldog",
        "com.touchsense.gameguardian",
        "com.android.vending.billing.InAppBillingService.LOCK",
        "com.zune.gamekiller",
        "com.aag.killer",
        "com.killerapp.gamekiller",
        "com.cih.game_killer",
        "com.cih.gamecih_killer",
        "com.cmplay.gamekiller",
    )

    fun check(context: Context): Result {
        if (checked) return Result(compromised, reasonStr)
        checked = true
        val flags = mutableListOf<String>()

        if (isDebuggable(context)) flags += "debuggable_flag"
        if (isDebuggerAttached()) flags += "debug_attached"
        if (isInstalledFromUntrustedSource(context)) flags += "side_load"
        if (isFridaPresent()) flags += "instrumentation"
        if (hasUntrustedPackages(context)) flags += "tamper_apps"
        if (isApkRepacked(context)) flags += "apk_repack"
        if (isEmulator()) flags += "emulator"
        if (!verifySignatureHash(context, RELEASE_SIGNATURE_SHA256)) flags += "invalid_signature"

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

    private fun isInstalledFromUntrustedSource(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        return try {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
            val trusted = setOf(
                "com.android.vending",
                "com.google.android.feedback",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.huawei.appmarket",
                "com.sec.android.app.samsungapps",
                "com.miui.packageinstaller",
                "com.amazon.venezia",
                "com.oppo.market",
                "com.heytap.market",
                "com.bbk.appstore",
                "com.xiaomi.market",
            )
            installer != null && installer !in trusted
        } catch (_: Throwable) { false }
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
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 200)
                    true
                }
            }.getOrDefault(false)
        }
    }

    private fun hasUntrustedPackages(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val pm = context.packageManager
        return try {
            UNTRUSTED_PACKAGES.any { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        } catch (_: Throwable) { false }
    }

    private fun isApkRepacked(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        return try {
            val pm = context.packageManager
            val apkPath = context.applicationInfo.sourceDir
            val file = File(apkPath)
            if (!file.exists()) return false
            val zip = java.util.zip.ZipFile(file)
            try {
                val expectedEntries = listOf("AndroidManifest.xml", "classes.dex", "META-INF/")
                val hasMeta = zip.entries().toList().any { it.name.startsWith("META-INF/") &&
                        (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }
                !hasMeta
            } finally {
                zip.close()
            }
        } catch (_: Throwable) { false }
    }

    private fun isEmulator(): Boolean {
        if (BuildConfig.DEBUG) return false
        val product = (Build.PRODUCT ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val device = (Build.DEVICE ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val hardware = (Build.HARDWARE ?: "").lowercase()
        val fingerprint = (Build.FINGERPRINT ?: "").lowercase()
        return (fingerprint.contains("generic") || fingerprint.contains("unknown")) ||
            model.contains("google_sdk") || model.contains("emulator") || model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") || brand.startsWith("generic") && device.startsWith("generic") ||
            product == "google_sdk" || hardware.contains("ranchu") || hardware.contains("goldfish")
    }

    fun verifySignatureHash(context: Context, expectedSha256Hex: String?): Boolean {
        if (expectedSha256Hex.isNullOrBlank()) return true
        return try {
            val pm = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()?.toByteArray()
            } ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(sig).joinToString("") { "%02x".format(it) }
            hash.equals(expectedSha256Hex, ignoreCase = true)
        } catch (_: Throwable) { false }
    }

    data class Result(val compromised: Boolean, val reason: String)
}
