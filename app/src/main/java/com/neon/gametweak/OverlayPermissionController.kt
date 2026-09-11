package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Vendor setup is best-effort; Android's standard grant remains authoritative. */
object OverlayPermissionController {
    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    }.getOrDefault("")
    fun requestManualGrant(context: Context): Boolean {
        if (hasOverlayPermission(context)) return true
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val isXiaomiFamily = manufacturer in setOf("xiaomi", "poco", "redmi") || systemProperty("ro.miui.ui.version.code").isNotEmpty()

        // 1. Android Standard Overlay Permission (Direct package URI) - authoritative across all OEMs
        val directOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        // 2. Android Standard Overlay Permission (Generic list fallback)
        val genericOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

        // 3. OEM-specific permission editors for background popup / overlay permissions
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

        // 4. App details settings as universal fail-safe
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

        val candidates = listOfNotNull(directOverlay, genericOverlay, vendor, appDetails)
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) {
                val message = if (isXiaomiFamily) {
                    "Aktifkan 'Display over other apps'. Pada HyperOS/MIUI, pastikan juga izinkan 'Display pop-up windows while running in the background'."
                } else {
                    "Aktifkan izin 'Display over other apps' agar floating cockpit Game Nuke dapat muncul."
                }
                NukeToast.unsupported(context, message, long = true)
                return true
            }
        }
        NukeToast.error(context, "Overlay settings could not be opened on this device.", long = true)
        return false
    }
}
