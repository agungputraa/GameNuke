package com.neon.gametweak

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * NukeLiveChatScheduler — Dual-layer background polling coordinator.
 *
 * Combines AlarmManager (fast, short-interval) + WorkManager (reliable on OEM devices)
 * to ensure developer replies are detected and notified even when the app is fully cleared
 * from recent tasks.
 *
 * Also manages the persistent "waiting for reply" notification to keep OS-level
 * background processes alive on aggressive battery optimizers (MIUI/HyperOS).
 */
object NukeLiveChatScheduler {

    private const val TAG = "NukeLiveChatScheduler"
    private const val PREFS_NAME = "NukeLiveChatPrefs"
    private const val KEY_WAITING = "chat_waiting_for_dev_reply"
    private const val REQUEST_CODE = 9110

    // ── State Management ─────────────────────────────────────────────────────────

    fun setWaitingForReply(context: Context, waiting: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WAITING, waiting).apply()

        if (waiting) {
            // Arm both AlarmManager (fast) and WorkManager (reliable)
            arm(context, 20_000L)
            NukeLiveChatWorker.schedule(context)
            NukeLiveChatNotifier.updateStickyWaitingNotification(context, true)
        } else {
            disarm(context)
            NukeLiveChatWorker.cancel(context)
            NukeLiveChatNotifier.updateStickyWaitingNotification(context, false)
        }
    }

    fun isWaitingForReply(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WAITING, false)
    }

    // ── AlarmManager (Short-interval fast polling) ───────────────────────────────

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
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                else ->
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "AlarmManager poll armed in ${delayMs / 1000}s")
        } catch (e: Exception) {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            Log.w(TAG, "AlarmManager fallback armed", e)
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
            Log.d(TAG, "AlarmManager poll disarmed")
        }
    }
}
