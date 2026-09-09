package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * NukeAntivirusNotificationReceiver — Handles click on Antivirus Safe/Alert notifications.
 * Automatically displays the NukeAntivirusFloatingOverlay above the current game/screen.
 */
class NukeAntivirusNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            // If overlay permission not granted, launch MainActivity
            val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            appContext.startActivity(launchIntent)
            return
        }

        // Open floating antivirus panel directly on top of active screen/game!
        NukeAntivirusFloatingOverlay.getInstance(appContext).show()
    }
}
