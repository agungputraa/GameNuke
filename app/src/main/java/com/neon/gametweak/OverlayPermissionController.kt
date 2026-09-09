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
        val vendor = when {
            systemProperty("ro.miui.ui.version.code").isNotEmpty() || manufacturer in setOf("xiaomi", "poco", "redmi") ->
                Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", context.packageName)
            systemProperty("ro.build.version.opporom").isNotEmpty() || manufacturer in setOf("oppo", "realme", "oneplus") ->
                Intent().setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            systemProperty("ro.vivo.os.version").isNotEmpty() || manufacturer in setOf("vivo", "iqoo") ->
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    .putExtra("packagename", context.packageName)
            else -> null
        }
        val candidates = listOfNotNull(vendor,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) {
                NukeToast.unsupported(context, "Enable Display over other apps or Floating windows, then return to Game Nuke. On HyperOS, also check background popup permission.", long = true)
                return true
            }
        }
        NukeToast.error(context, "Overlay settings could not be opened on this device.", long = true)
        return false
    }
}
