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
 * NukeAntivirusNotifier — System notification dispatcher for Cyber Shield Antivirus & Threat Sentinel.
 *
 * Developer: Agung Developer
 */
object NukeAntivirusNotifier {

    private const val CHANNEL_SAFE_ID = "nuke_antivirus_safe_channel"
    private const val CHANNEL_ALERT_ID = "nuke_antivirus_alert_channel"
    const val NOTIFICATION_SAFE_ID = 3001
    const val NOTIFICATION_ALERT_ID = 3002

    const val ACTION_OPEN_SECURITY_OVERLAY = "com.neon.gametweak.action.OPEN_SECURITY_OVERLAY"
    const val ACTION_OPEN_ANTIVIRUS_OVERLAY = ACTION_OPEN_SECURITY_OVERLAY

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Channel 1: Safe Status (Ongoing & quiet)
            if (manager.getNotificationChannel(CHANNEL_SAFE_ID) == null) {
                val safeChannel = NotificationChannel(
                    CHANNEL_SAFE_ID,
                    "App Security: Integrity Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Displays real-time application security and integrity status"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(safeChannel)
            }

            // Channel 2: Threat Alert (High priority & heads-up alarm)
            if (manager.getNotificationChannel(CHANNEL_ALERT_ID) == null) {
                val alertChannel = NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "App Security: Risk Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when packages with high-risk permissions or suspicious behavior are detected"
                    enableLights(true)
                    lightColor = Color.RED
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 100, 250)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(alertChannel)
            }
        }
    }

    fun showSafeNotification(context: Context, scannedCount: Int) {
        val appContext = context.applicationContext
        ensureChannels(appContext)
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Dismiss any old alert & safe notifs
        manager.cancel(NOTIFICATION_ALERT_ID)
        manager.cancel(NOTIFICATION_SAFE_ID)
    }

    fun showThreatAlertNotification(context: Context, threats: List<NukeAntivirusEngine.DetectedThreat>) {
        val appContext = context.applicationContext
        ensureChannels(appContext)
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Dismiss safe notif
        manager.cancel(NOTIFICATION_SAFE_ID)

        val clickIntent = Intent(appContext, NukeAntivirusNotificationReceiver::class.java).apply {
            action = ACTION_OPEN_ANTIVIRUS_OVERLAY
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPendingIntent = PendingIntent.getBroadcast(appContext, 302, clickIntent, flags)

        val topThreat = threats.firstOrNull()?.title ?: "Suspicious Application"
        val count = threats.size

        val notif = NotificationCompat.Builder(appContext, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle("⚠️ App Security: Risk Detected")
            .setContentText("$count package(s) flagged with high-risk permissions. Tap to review.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "⚠️ APPLICATION SECURITY ALERT\n" +
                            "Detected $count package(s) with high-risk permissions or untrusted behavior.\n" +
                            "Item: $topThreat\n" +
                            "👉 Tap this notification to review and manage package permissions."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setColor(Color.parseColor("#EF4444"))
            .build()

        manager.notify(NOTIFICATION_ALERT_ID, notif)
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_SAFE_ID)
        manager.cancel(NOTIFICATION_ALERT_ID)
    }
}
