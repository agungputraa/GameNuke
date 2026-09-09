package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NukeLiveChatReplyReceiver — Handles direct inline reply from notification shade (RemoteInput).
 * Sends the reply directly to the developer backend and updates notification status without opening the app.
 */
class NukeLiveChatReplyReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NukeLiveChatReply"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NukeLiveChatNotifier.ACTION_DIRECT_REPLY) return

        val results: Bundle? = RemoteInput.getResultsFromIntent(intent)
        val replyText = results?.getCharSequence(NukeLiveChatNotifier.KEY_TEXT_REPLY)?.toString()?.trim()

        if (!replyText.isNullOrBlank()) {
            val appContext = context.applicationContext
            Log.d(TAG, "Direct reply received: $replyText")
            val pendingResult = goAsync()
            scope.launch {
                try {
                    NukeLiveChatRepository.sendMessage(appContext, replyText)
                    NukeLiveChatNotifier.showReplySentNotification(appContext, replyText)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send direct reply", e)
                } finally {
                    runCatching { pendingResult.finish() }
                }
            }
        }
    }
}
