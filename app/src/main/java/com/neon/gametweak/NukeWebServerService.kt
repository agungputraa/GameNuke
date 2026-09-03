package com.neon.gametweak

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps LocalWebServer (REST API & Web UI) alive even when
 * the user clears the main app from the recent-apps list.
 *
 * Mechanism:
 *  - Runs as ForegroundService with a silent notification so the OS cannot kill it
 *    without explicit user action in battery settings.
 *  - Returns START_STICKY so the system auto-restarts it after any OOM kill.
 *  - Implements onTaskRemoved() to immediately restart the server socket on swipe-away
 *    and schedules a 1-second AlarmManager restart as a safety net.
 *  - Uses a periodic watchdog (every 15 s) to verify the socket is still accepting
 *    connections and restart it if it has silently closed.
 */
class NukeWebServerService : Service() {

    companion object {
        private const val CHANNEL_ID           = "nuke_api_server"
        private const val NOTIF_ID             = 8801
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }

    private val handler  = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            runCatching { ensureServerRunning() }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        ensureServerRunning()
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        ensureServerRunning()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // User swiped the app from Recents.
        runCatching { ensureServerRunning() }
        // Schedule an AlarmManager restart so that even if the OS kills the process
        // we come back within 1 second.
        runCatching {
            val restartIntent = Intent(applicationContext, NukeWebServerService::class.java)
            val pi = PendingIntent.getService(
                applicationContext, 1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(ALARM_SERVICE) as? AlarmManager
            am?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1_000L,
                pi,
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureServerRunning() {
        val server = LocalWebServer.getInstance(applicationContext)
        if (!server.isApiRunning || server.isApiSocketClosed()) {
            server.startApiServer()
        }
    }

    private fun startForegroundSafely() {
        runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_game_booster_notification)
            .setContentTitle("Game Nuke API active")
            .setContentText("Local REST API is running in the background")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "API Server",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Silent indicator that the Game Nuke local REST API is running."
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }
}
