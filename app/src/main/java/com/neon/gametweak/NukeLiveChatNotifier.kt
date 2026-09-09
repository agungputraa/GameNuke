package com.neon.gametweak

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * NukeLiveChatNotifier — Enterprise notification dispatcher for live developer chat replies.
 *
 * Features:
 * - High-priority heads-up notifications with sound + vibration when developer replies
 * - RemoteInput direct reply action — user can reply from notification shade
 * - Tap notification → launches MainActivity → opens Live Chat panel
 * - Optional persistent sticky notification while waiting for a reply (survives app clear)
 * - "Reply Sent" confirmation notification (auto-dismisses after 6s)
 */
object NukeLiveChatNotifier {

    const val CHANNEL_ID          = "nuke_live_chat_channel"
    const val CHANNEL_PERSISTENT  = "nuke_live_chat_persistent"
    const val NOTIFICATION_ID     = 2002
    const val NOTIFICATION_STICKY = 2003
    const val EXTRA_OPEN_LIVE_CHAT = "EXTRA_OPEN_LIVE_CHAT"
    const val KEY_TEXT_REPLY      = "key_live_chat_text_reply"
    const val ACTION_DIRECT_REPLY = "com.neon.gametweak.ACTION_LIVE_CHAT_DIRECT_REPLY"

    // ── Channel Setup ────────────────────────────────────────────────────────────

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // High-priority reply channel
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Chat — Developer Replies",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when the developer replies to your message"
                enableLights(true)
                lightColor = Color.parseColor("#10B981")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 80, 180)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }

        // Silent persistent "waiting" channel
        if (manager.getNotificationChannel(CHANNEL_PERSISTENT) == null) {
            val sticky = NotificationChannel(
                CHANNEL_PERSISTENT,
                "Live Chat — Active Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while waiting for a developer reply — enables background polling"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(sticky)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun openChatPendingIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.neon.gametweak.ACTION_OPEN_LIVE_CHAT"
            putExtra(EXTRA_OPEN_LIVE_CHAT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, NOTIFICATION_ID, launchIntent, flags)
    }

    private fun replyPendingIntent(context: Context, chatId: String?): PendingIntent {
        val replyIntent = Intent(context, NukeLiveChatReplyReceiver::class.java).apply {
            action = ACTION_DIRECT_REPLY
            putExtra("EXTRA_CHAT_ID", chatId ?: "")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, NOTIFICATION_ID + 10, replyIntent, flags)
    }

    // ── Developer Reply Notification ─────────────────────────────────────────────

    /**
     * Show a high-priority heads-up notification when the developer sends a reply.
     * Includes RemoteInput (direct reply) and a tap-to-open action.
     */
    fun showDevReplyNotification(context: Context, msg: NukeChatMessage) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openIntent = openChatPendingIntent(appContext)

        // Direct inline-reply action
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to developer...")
            .build()

        val actionReply = NotificationCompat.Action.Builder(
            R.drawable.ic_game_booster_notification,
            "Quick Reply",
            replyPendingIntent(appContext, msg.id)
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val actionOpen = NotificationCompat.Action.Builder(
            R.drawable.ic_game_booster_notification,
            "Open Chat",
            openIntent
        ).build()

        val cmd = msg.commandText
        val bodyText = when {
            !cmd.isNullOrBlank() -> "Shell command from developer: $cmd"
            msg.text.isNotBlank() -> msg.text
            else -> "New message from developer"
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle("Developer Support")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#10B981"))
            .setContentIntent(openIntent)
            .addAction(actionReply)
            .addAction(actionOpen)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 180, 80, 180))
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }

        // Dismiss sticky "waiting" notification now that we have a reply
        runCatching { manager.cancel(NOTIFICATION_STICKY) }
    }

    /**
     * Sticky waiting notification disabled to prevent notification tray flooding.
     * Background polling via AlarmManager and WorkManager reliably handles reply detection.
     */
    fun updateStickyWaitingNotification(context: Context, show: Boolean) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(NOTIFICATION_STICKY) }
    }

    // ── Reply Sent Confirmation ───────────────────────────────────────────────────

    /**
     * Clear notification upon successful reply instead of spawning a new confirmation card.
     */
    fun showReplySentNotification(context: Context, sentText: String) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    // ── Dismiss Helpers ──────────────────────────────────────────────────────────

    fun cancelReplyNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { manager?.cancel(NOTIFICATION_ID) }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { manager?.cancel(NOTIFICATION_ID) }
        runCatching { manager?.cancel(NOTIFICATION_STICKY) }
    }

    /** @deprecated Use cancelReplyNotification */
    fun cancelNotification(context: Context) = cancelReplyNotification(context)
}
