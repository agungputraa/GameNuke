package com.neon.gametweak

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * NukeLiveChatScheduler — Background polling scheduler for developer replies.
 * Ensures the app checks for new messages even if the app was cleared from Recent Apps.
 */
object NukeLiveChatScheduler {

    private const val TAG = "NukeLiveChatScheduler"
    private const val PREFS_NAME = "NukeLiveChatPrefs"
    private const val KEY_WAITING = "chat_waiting_for_dev_reply"
    private const val REQUEST_CODE = 9110

    fun setWaitingForReply(context: Context, waiting: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WAITING, waiting).apply()
        if (waiting) {
            arm(context, 15_000L)
        } else {
            disarm(context)
        }
    }

    fun isWaitingForReply(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WAITING, false)
    }

    fun arm(context: Context, delayMs: Long = 20_000L) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(appContext, NukeLiveChatReceiver::class.java).apply {
            action = NukeLiveChatReceiver.ACTION_POLL_TELEGRAM
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

        val pi = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)

        val triggerAt = System.currentTimeMillis() + delayMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Live Chat background poll armed in ${delayMs / 1000}s")
        } catch (e: Exception) {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            Log.w(TAG, "Failed exact alarm, armed inexact fallback", e)
        }
    }

    fun disarm(context: Context) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(appContext, NukeLiveChatReceiver::class.java).apply {
            action = NukeLiveChatReceiver.ACTION_POLL_TELEGRAM
        }

        val flags = PendingIntent.FLAG_NO_CREATE or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

        val pi = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
            Log.d(TAG, "Live Chat background poll disarmed")
        }
    }
}
