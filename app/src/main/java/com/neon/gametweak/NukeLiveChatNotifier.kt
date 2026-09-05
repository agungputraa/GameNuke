package com.neon.gametweak

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * NukeLiveChatNotifier — Enterprise notification dispatcher for developer live chat replies.
 * Displays high-priority heads-up notifications with sound, vibration, and 1-tap launcher.
 */
object NukeLiveChatNotifier {

    const val CHANNEL_ID = "nuke_live_chat_channel"
    const val NOTIFICATION_ID = 2002
    const val EXTRA_OPEN_LIVE_CHAT = "EXTRA_OPEN_LIVE_CHAT"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live Chat Support",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifikasi balasan pesan langsung dari Agung Developer"
                    enableLights(true)
                    lightColor = Color.parseColor("#00FF88")
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 180, 80, 180)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showDevReplyNotification(context: Context, msg: NukeChatMessage) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // PendingIntent to launch MainActivity and automatically open Live Chat overlay
        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            action = "com.neon.gametweak.ACTION_OPEN_LIVE_CHAT"
            putExtra(EXTRA_OPEN_LIVE_CHAT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            launchIntent,
            flags
        )

        val displayTitle = "🛡️ Agung Developer"
        val cmd = msg.commandText
        val displayText = if (!cmd.isNullOrBlank()) {
            "⚡ Shell Command: $cmd"
        } else {
            msg.text.ifBlank { "Mengirim balasan pesan baru." }
        }

        val actionOpen = NotificationCompat.Action.Builder(
            R.drawable.ic_game_booster_notification,
            "💬 Buka Live Chat",
            contentPendingIntent
        ).build()

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#00FF88"))
            .setContentIntent(contentPendingIntent)
            .addAction(actionOpen)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 180, 80, 180))

        runCatching {
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun cancelNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching {
            manager?.cancel(NOTIFICATION_ID)
        }
    }
}
