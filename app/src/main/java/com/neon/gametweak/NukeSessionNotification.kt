package com.neon.gametweak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Notification for the user-started in-game HUD foreground session. */
object NukeSessionNotification {
    const val CHANNEL_ID = "nuke_game_session"
    const val NOTIFICATION_ID = 3301

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Game Nuke gaming session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the user-started in-game performance HUD active during gameplay."
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    fun build(context: Context, gameLabel: String): Notification {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            3301,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            3302,
            Intent(context, FloatingBoosterService::class.java).setAction(FloatingBoosterService.ACTION_STOP_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle("Game Nuke session active")
            .setContentText("HUD active for $gameLabel")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "STOP", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
