package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Public Android overlay-consent flow; no hidden grant/bypass path. */
object OverlayPermissionController {
    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestManualGrant(context: Context): Boolean {
        val app = context.applicationContext
        if (hasOverlayPermission(app) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            NukeToast.unsupported(
                app,
                Tx.t(
                    "Izin 'Tampil di atas aplikasi lain' diperlukan. Aktifkan lalu kembali ke GAME NUKE.",
                    "Display-over-other-apps permission is required. Enable it, then return to GAME NUKE.",
                ),
                long = true,
            )
            true
        }.onFailure {
            NukeToast.error(app, Tx.t("Pengaturan izin overlay gagal dibuka.", "Overlay permission settings could not be opened."), long = true)
        }.getOrDefault(false)
    }
}
