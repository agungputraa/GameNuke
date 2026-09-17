package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log

/**
 * NukeVolumeKeyTriggerManager — Captures physical volume key presses (Volume Up / Down)
 * to fire assigned gaming macro pins (e.g. Scope Auto-Drag Headshot, Gloo Wall Spam).
 *
 * ZERO-OVERLAY ARCHITECTURE:
 * Previous implementations used an invisible WindowManager overlay view that stole window focus,
 * causing system navigation gestures to freeze and third-party apps to stop scrolling.
 * This modern implementation uses the system VOLUME_CHANGED_ACTION broadcast combined with
 * AudioManager tracking. It requires ZERO windows, NEVER steals focus, and CANNOT cause touch freezes.
 */
object NukeVolumeKeyTriggerManager {

    private const val TAG = "NukeVolKeyTrigger"
    private var isListening = false
    val isListeningActive: Boolean get() = isListening
    private var appContext: Context? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var lastTriggerTime = 0L
    private const val DEBOUNCE_MS = 50L
    private var lastVolume: Int = -1

    fun start(context: Context) {
        if (isListening) return
        appContext = context.applicationContext
        val ctx = appContext ?: return

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        if (streamType == AudioManager.STREAM_MUSIC || streamType == AudioManager.STREAM_VOICE_CALL || streamType == -1) {
                            val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                            val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                            val now = System.currentTimeMillis()
                            if (now - lastTriggerTime < DEBOUNCE_MS) return
                            lastTriggerTime = now

                            val source = when {
                                newVol > prevVol -> MacroTriggerSource.VOLUME_UP
                                newVol < prevVol -> MacroTriggerSource.VOLUME_DOWN
                                else -> {
                                    val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val diff = current - lastVolume
                                    lastVolume = current
                                    if (diff > 0) MacroTriggerSource.VOLUME_UP
                                    else if (diff < 0) MacroTriggerSource.VOLUME_DOWN
                                    else return
                                }
                            }
                            lastVolume = if (newVol >= 0) newVol else am.getStreamVolume(AudioManager.STREAM_MUSIC)
                            Log.d(TAG, "Hardware volume key detected: ${source.displayName()}")
                            dispatchTrigger(ctx, source)
                        }
                    }
                }
            }

            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ctx.registerReceiver(receiver, filter)
            volumeReceiver = receiver
            isListening = true
            Log.i(TAG, "Volume key trigger active via system audio broadcast — zero window overhead")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register volume key listener: ${e.message}")
        }
    }

    private fun dispatchTrigger(context: Context, source: MacroTriggerSource) {
        Log.d(TAG, "Hardware key triggered: ${source.displayName()}")
        NukeMacroEngine.triggerPinsForSource(context, source)
    }

    fun stop() {
        if (!isListening) return
        try {
            val ctx = appContext
            val receiver = volumeReceiver
            if (ctx != null && receiver != null) {
                ctx.unregisterReceiver(receiver)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error unregistering volume receiver: ${e.message}")
        }
        volumeReceiver = null
        appContext = null
        isListening = false
        Log.i(TAG, "Volume key trigger stopped")
    }
}
