package com.neon.gametweak

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

class NotificationHelper(private val context: Context) {
    companion object {
        const val ACTION_PAIR = "com.neon.gametweak.ACTION_PAIR"
        const val EXTRA_REPLY = "pairing_code_reply"
        private const val CHANNEL_ID = "neon_pairing_channel"
        private const val NOTIF_ID = 1001
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Neon Game Tweak", NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            runCatching { manager?.createNotificationChannel(channel) }
        }
    }

    fun updateNotification(title: String, text: String, isOngoing: Boolean, showInputAction: Boolean, pairingTarget: String?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (title.isEmpty() && text.isEmpty()) {
            runCatching { manager.cancel(NOTIF_ID) }
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (showInputAction && pairingTarget != null) {
            val pairIntent = Intent(context, PairingReceiver::class.java).apply {
                setAction(ACTION_PAIR)
                putExtra("pairing_target", pairingTarget)
            }
            var actionFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) actionFlags = actionFlags or PendingIntent.FLAG_MUTABLE
            val actionPendingIntent = PendingIntent.getBroadcast(context, 0, pairIntent, actionFlags)

            val remoteInput = RemoteInput.Builder(EXTRA_REPLY).setLabel(Tx.t("Masukkan Kode 6 Digit", "Enter 6-Digit Code")).build()
            val action = NotificationCompat.Action.Builder(R.drawable.ic_game_booster_notification, Tx.t("INPUT KODE", "ENTER CODE"), actionPendingIntent)
                .addRemoteInput(remoteInput).build()

            builder.addAction(action)
            builder.setOnlyAlertOnce(false)
        }
        runCatching { manager.notify(NOTIF_ID, builder.build()) }
    }
}
