package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * NukeLiveChatReceiver — BroadcastReceiver for background live chat polling.
 *
 * Triggered by:
 *  1. AlarmManager — short-interval polling (20-60s) when waiting for a reply
 *  2. BOOT_COMPLETED — re-arms both AlarmManager and WorkManager after device restart
 *
 * Survives app being cleared from Recent Tasks since BroadcastReceivers are
 * process-independent and do not require the app to be running.
 */
class NukeLiveChatReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POLL_TELEGRAM = "com.neon.gametweak.ACTION_POLL_TELEGRAM"
        private const val TAG = "NukeLiveChatReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        // Handle device boot — re-arm polling if there's an active chat session
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed — checking if live chat re-arm needed")
            if (NukeLiveChatScheduler.isWaitingForReply(context)) {
                NukeLiveChatScheduler.arm(context, 30_000L)
                NukeLiveChatWorker.schedule(context)
                NukeLiveChatNotifier.updateStickyWaitingNotification(context, true)
                Log.d(TAG, "Live chat polling re-armed after boot")
            }
            return
        }

        if (action != ACTION_POLL_TELEGRAM) return

        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GameNuke:LiveChatPollWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Background poll triggered via AlarmManager")
                val newMessages = NukeLiveChatRepository.pollOnce(appContext)

                if (newMessages.isNotEmpty()) {
                    val latest = newMessages.last()
                    Log.d(TAG, "Developer replied: ${latest.text.take(60)}")
                    NukeLiveChatNotifier.showDevReplyNotification(appContext, latest)
                    // Keep polling with active interval for follow-up messages
                    NukeLiveChatScheduler.arm(appContext, 30_000L)
                } else if (NukeLiveChatScheduler.isWaitingForReply(appContext)) {
                    // Still waiting — check again in 25 seconds
                    NukeLiveChatScheduler.arm(appContext, 25_000L)
                } else {
                    // Active chat history continuity — check every 60s
                    NukeLiveChatScheduler.arm(appContext, 60_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background poll failed", e)
                NukeLiveChatScheduler.arm(appContext, 45_000L)
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                runCatching { pendingResult.finish() }
            }
        }
    }
}
