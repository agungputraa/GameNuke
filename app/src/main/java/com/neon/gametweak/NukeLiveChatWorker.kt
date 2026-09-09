package com.neon.gametweak

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * NukeLiveChatWorker — WorkManager-based periodic background poller for developer chat replies.
 *
 * Acts as a robust fallback alongside AlarmManager.
 * WorkManager survives app-clear from recents on most devices/OEMs including MIUI/HyperOS.
 * Runs every 15 minutes at minimum (OS constraint), but AlarmManager handles shorter intervals.
 */
class NukeLiveChatWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "NukeLiveChatWorker"
        const val WORK_NAME = "nuke_live_chat_poll"

        /**
         * Schedule periodic WorkManager polling.
         * Called once on first user message send and after reboot.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NukeLiveChatWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "WorkManager live chat poller scheduled (15-min interval)")
        }

        /**
         * Cancel WorkManager periodic polling — called when user has no active chat sessions.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "WorkManager live chat poller cancelled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "WorkManager poll triggered")
            val newMessages = NukeLiveChatRepository.pollOnce(appContext)
            if (newMessages.isNotEmpty()) {
                val latest = newMessages.last()
                Log.d(TAG, "WorkManager: new developer reply — ${latest.text.take(50)}")
                NukeLiveChatNotifier.showDevReplyNotification(appContext, latest)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager poll failed", e)
            Result.retry()
        }
    }
}
