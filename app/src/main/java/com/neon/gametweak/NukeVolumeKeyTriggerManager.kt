package com.neon.gametweak

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.util.Log

/**
 * NukeVolumeKeyTriggerManager — Captures physical volume key presses (Volume Up / Down)
 * to fire assigned gaming macro pins (e.g. Scope Auto-Drag Headshot, Gloo Wall Spam).
 *
 * Uses MediaSession with Remote VolumeProvider:
 * 1. Zero Latency: Directly intercepts volume keys from Android AudioService.
 * 2. Does NOT change device volume (neither increases nor mutes).
 * 3. Does NOT display the system volume slider on screen.
 * 4. Never gets stuck at 100% or 0%: fires continuously and reliably.
 */
object NukeVolumeKeyTriggerManager {

    private const val TAG = "NukeVolKeyTrigger"
    private var isListening = false
    private var appContext: Context? = null

    private var mediaSession: MediaSession? = null
    private var lastTriggerTime = 0L
    private const val DEBOUNCE_MS = 35L

    fun start(context: Context) {
        if (isListening) return
        appContext = context.applicationContext
        val ctx = appContext ?: return

        try {
            val session = MediaSession(ctx, "NukeVolumeMacroSession")
            val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTime < DEBOUNCE_MS) return
                    lastTriggerTime = now
                    if (direction > 0) {
                        dispatchTrigger(ctx, MacroTriggerSource.VOLUME_UP)
                    } else if (direction < 0) {
                        dispatchTrigger(ctx, MacroTriggerSource.VOLUME_DOWN)
                    }
                }
            }
            session.setPlaybackToRemote(volumeProvider)
            session.isActive = true
            mediaSession = session
            isListening = true
            Log.i(TAG, "MediaSession Remote VolumeProvider active for hardware volume key macros")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize MediaSession volume provider: ${e.message}")
        }
    }

    private fun dispatchTrigger(context: Context, source: MacroTriggerSource) {
        Log.d(TAG, "Hardware key triggered: ${source.displayName()}")
        NukeMacroEngine.triggerPinsForSource(context, source)
    }

    fun stop() {
        if (!isListening) return
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing MediaSession: ${e.message}")
        }
        mediaSession = null
        isListening = false
        Log.i(TAG, "Volume key trigger listener stopped")
    }
}
