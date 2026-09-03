package com.neon.gametweak

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen, control-free landscape splash played immediately before the selected game. */
class GameLaunchSplashActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_GAME_PACKAGE = "game_package"
        const val EXTRA_LAUNCH_TOKEN = "launch_token"
        private const val STATE_LAUNCH_TOKEN = "state_launch_token"
        private const val PREFS = "NukePrefs"
        private const val K_LAST_TOKEN = "last_game_launch_token"
        private const val K_LAST_TOKEN_AT = "last_game_launch_token_at"
        private const val TOKEN_GUARD_MS = 30_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            android.util.Log.w("NukeLaunch", "Launch coroutine contained: ${error.javaClass.simpleName}")
            handler.post { if (!isFinishing) finishNoAnimation() }
        },
    )
    private val handedOff = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private lateinit var surface: SurfaceView
    private var targetPackage: String? = null
    private var launchToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        overridePendingTransition(0, 0)
        targetPackage = intent.getStringExtra(EXTRA_GAME_PACKAGE)?.takeIf { validPackage(it) }
        val launchPackage = targetPackage
        if (launchPackage == null || GameCatalogRepository.resolveLaunchIntent(this, launchPackage) == null) {
            finishNoAnimation(); return
        }

        launchToken = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)?.takeIf { it.length in 8..120 }
            ?: "${targetPackage}:${android.os.SystemClock.elapsedRealtime()}"
        val launchPrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = android.os.SystemClock.elapsedRealtime()
        val sameSavedToken = savedInstanceState?.getString(STATE_LAUNCH_TOKEN) == launchToken
        val recentlySeenToken = launchPrefs.safeString(K_LAST_TOKEN, "") == launchToken &&
            now - launchPrefs.safeLong(K_LAST_TOKEN_AT, 0L) in 0L..TOKEN_GUARD_MS
        if (sameSavedToken || recentlySeenToken) {
            // Orientation/window recreation must never replay the cinematic and relaunch in a loop.
            handler.post { handoff("recreation-guard") }
            return
        }
        launchPrefs.edit().putString(K_LAST_TOKEN, launchToken).putLong(K_LAST_TOKEN_AT, now).apply()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { holder.addCallback(this@GameLaunchSplashActivity) }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        handler.postDelayed({ handoff("watchdog") }, 8_500L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val afd = runCatching { assets.openFd("splash.mp4") }.getOrNull()
        if (afd == null) {
            handler.postDelayed({ handoff("asset-missing") }, 120L)
            return
        }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setDisplay(holder)
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mp.setScreenOnWhilePlaying(true)
            mp.isLooping = false
            mp.setOnPreparedListener { p ->
                runCatching { p.playbackParams = PlaybackParams().setSpeed(1.80f).setPitch(1.0f) }
                p.start()
            }
            mp.setOnCompletionListener { handoff("complete") }
            mp.setOnErrorListener { _, _, _ -> handoff("decode-error"); true }
            mp.prepareAsync()
        }.onFailure {
            runCatching { afd.close() }
            handler.postDelayed({ handoff("prepare-error") }, 80L)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { player?.setDisplay(null) } }

    private fun handoff(reason: String) {
        android.util.Log.d("NukeLaunch", "Cinematic handoff: $reason")
        if (!handedOff.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        val pkg = targetPackage ?: return finishNoAnimation()
        val launch = GameCatalogRepository.resolveLaunchIntent(this, pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return finishNoAnimation()

        scope.launch {
            // Mark the short activity -> foreground-service handoff as active. The launch path is
            // deliberately display-geometry-free: no staged display apply, wm size or wm density.
            NukeRuntimeState.setLaunchHandoffActive(true)
            NukeRemoteConfigRepository.refreshInBackground("boost-launch")
            val hudIntent = Intent(applicationContext, FloatingBoosterService::class.java).apply {
                action = FloatingBoosterService.ACTION_SHOW_OVERLAY
                putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                putExtra(FloatingBoosterService.EXTRA_TARGET_PACKAGE, pkg)
                putExtra(FloatingBoosterService.EXTRA_REVEAL_DELAY_MS, 850L)
            }
            // User starts the session from this visible activity, satisfying Android 12+ FGS start rules.
            val hudStarted = runCatching { ContextCompat.startForegroundService(this@GameLaunchSplashActivity, hudIntent) }.isSuccess
            val gameStarted = runCatching { startActivity(launch) }.isSuccess
            if (!hudStarted || !gameStarted) {
                // Roll back only legacy ownership if a previous version left a journal behind.
                NukeRuntimeState.setLaunchHandoffActive(false)
                if (hudStarted) runCatching {
                    startService(Intent(this@GameLaunchSplashActivity, FloatingBoosterService::class.java)
                        .setAction(FloatingBoosterService.ACTION_STOP_OVERLAY))
                }
                withContext(Dispatchers.IO) { runCatching { NukeDisplayProfileController.restoreIfOwned(applicationContext) } }
                if (!hudStarted) NukeToast.error(applicationContext, "Floating session could not start safely", true)
                if (!gameStarted) NukeToast.error(applicationContext, "Game launch failed; session changes restored", true)
            }
            finishNoAnimation()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LAUNCH_TOKEN, launchToken)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }; player = null
        scope.cancel()
        super.onDestroy()
    }

    private fun finishNoAnimation() { finish(); overridePendingTransition(0, 0) }
    private fun validPackage(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))
}
