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
 * NukeLiveChatReceiver — BroadcastReceiver triggered by AlarmManager to poll for replies.
 * Executes even if the app process was swiped away from recent tasks.
 */
class NukeLiveChatReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POLL_TELEGRAM = "com.neon.gametweak.ACTION_POLL_TELEGRAM"
        private const val TAG = "NukeLiveChatReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_POLL_TELEGRAM && intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameNuke:LiveChatPollWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(12_000L)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Background polling triggered for developer replies")
                val newDevMessages = NukeLiveChatRepository.pollOnce(appContext)

                if (newDevMessages.isNotEmpty()) {
                    val latest = newDevMessages.last()
                    Log.d(TAG, "Received new developer reply: ${latest.text}")
                    NukeLiveChatNotifier.showDevReplyNotification(appContext, latest)

                    // Keep polling armed with a wider 60s interval for follow-ups
                    NukeLiveChatScheduler.arm(appContext, 60_000L)
                } else if (NukeLiveChatScheduler.isWaitingForReply(appContext)) {
                    // Still waiting for reply, schedule next check in 25 seconds
                    NukeLiveChatScheduler.arm(appContext, 25_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background poll failed", e)
                if (NukeLiveChatScheduler.isWaitingForReply(appContext)) {
                    NukeLiveChatScheduler.arm(appContext, 45_000L)
                }
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        }
    }
}
