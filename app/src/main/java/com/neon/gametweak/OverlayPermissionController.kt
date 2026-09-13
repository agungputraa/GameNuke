package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.concurrent.TimeUnit

/** Vendor setup is best-effort; Android's standard grant remains authoritative. */
object OverlayPermissionController {
    private const val TAG = "NukeOverlayPermission"

    fun hasOverlayPermission(context: Context): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        if (NukeConnectionManager.isConnected()) {
            tryAutoGrantViaBridge(context, silent = true)
            return Settings.canDrawOverlays(context)
        }
        return false
    }

    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    }.getOrDefault("")

    /** Checks whether the system enforces Low-RAM / Android Go or Android 13+ restricted settings. */
    fun isLowRamOrRestricted(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true ||
            systemProperty("ro.config.low_ram") == "true" ||
            systemProperty("ro.build.version.go").isNotEmpty()
        return isLowRam || Build.VERSION.SDK_INT >= 33
    }

    /**
     * Bypasses the system restriction using privileged Nuke Bridge (Shizuku / iAdb / ADB / Root).
     * Works on all Android versions (including Android Go & Android 16 Low-RAM profiles).
     */
    fun tryAutoGrantViaBridge(context: Context, silent: Boolean = false): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        val pkg = context.packageName
        val cmd = "appops set $pkg SYSTEM_ALERT_WINDOW allow; pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW"

        var granted = false

        // 1. Privileged backend via NukeConnectionManager (iAdb, Shizuku, Daemon, Native ADB)
        if (NukeConnectionManager.isConnected()) {
            runCatching {
                NukeConnectionManager.executeCommand(cmd, timeoutMs = 2_500L)
                Thread.sleep(120)
                if (Settings.canDrawOverlays(context)) {
                    granted = true
                }
            }
        }

        // 2. Direct AdbManager instance check
        if (!granted) {
            val adb = runCatching { AdbManager.getInstance(context) }.getOrNull()
            if (adb != null && adb.isConnected()) {
                runCatching {
                    adb.executeCommandDirect(cmd, timeoutMs = 2_500L)
                    Thread.sleep(120)
                    if (Settings.canDrawOverlays(context)) {
                        granted = true
                    }
                }
            }
        }

        // 3. Fallback: Root shell (su) if available
        if (!granted) {
            runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                proc.waitFor(1_500L, TimeUnit.MILLISECONDS)
                Thread.sleep(100)
                if (Settings.canDrawOverlays(context)) {
                    granted = true
                }
            }
        }

        if (granted) {
            if (!silent) {
                NukeToast.success(context, "Floating HUD overlay granted automatically via Nuke Bridge!")
            }
            return true
        }
        return false
    }

    fun requestManualGrant(context: Context): Boolean {
        if (hasOverlayPermission(context)) return true

        // 1. Attempt one-tap auto-grant via active privileged bridge first
        if (tryAutoGrantViaBridge(context, silent = false)) {
            return true
        }

        // 2. If device is Low-RAM / Android Go / Android 14-16 restricted, route to Enterprise Guidance Activity
        if (isLowRamOrRestricted(context)) {
            val bypassIntent = Intent(context, NukeOverlayBypassActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(bypassIntent); true }.getOrDefault(false)) {
                return false
            }
        }

        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val isXiaomiFamily = manufacturer in setOf("xiaomi", "poco", "redmi") || systemProperty("ro.miui.ui.version.code").isNotEmpty()

        // 3. Android Standard Overlay Permission (Direct package URI)
        val directOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        // 4. Android Standard Overlay Permission (Generic list fallback)
        val genericOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

        // 5. OEM-specific permission editors
        val vendor = when {
            isXiaomiFamily ->
                Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", context.packageName)
            systemProperty("ro.build.version.opporom").isNotEmpty() || manufacturer in setOf("oppo", "realme", "oneplus") ->
                Intent().setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            systemProperty("ro.vivo.os.version").isNotEmpty() || manufacturer in setOf("vivo", "iqoo") ->
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    .putExtra("packagename", context.packageName)
            else -> null
        }

        // 6. App details settings as universal fail-safe
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

        val candidates = listOfNotNull(directOverlay, genericOverlay, vendor, appDetails)
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) {
                val message = if (isXiaomiFamily) {
                    "Enable 'Display over other apps'. On HyperOS/MIUI, also allow 'Display pop-up windows while running in the background'."
                } else {
                    "Enable 'Display over other apps'. If disabled on Android 13+, open App Info -> 3 dots -> 'Allow restricted settings'."
                }
                NukeToast.unsupported(context, message, long = true)
                return true
            }
        }
        NukeToast.error(context, "Overlay settings could not be opened on this device.", long = true)
        return false
    }
}
