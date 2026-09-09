package com.neon.gametweak

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Tactical Game Audio Equalizer & Footstep Enhancer.
 *
 * Hooks into Android system AudioSession 0 (global mixed audio) to amplify
 * high-frequency auditory cues (1kHz - 4kHz) such as enemy footsteps, grass rustling,
 * and weapon reloads in FPS/Battle Royale games (PUBG Mobile, Free Fire, COD Mobile).
 *
 * Runs 100% natively without Root.
 */
object NukeAudioBooster {
    private const val TAG = "NukeAudioBooster"
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    var isFootstepBoostEnabled: Boolean = false
        private set

    /**
     * Toggles the Footstep Enhancer on or off.
     */
    fun toggleFootstepBoost(context: Context): Boolean {
        return if (isFootstepBoostEnabled) {
            disableBoost()
            false
        } else {
            enableFootstepBoost(context)
            true
        }
    }

    /**
     * Activates high-frequency footstep & tactical sound enhancement.
     */
    fun enableFootstepBoost(context: Context): Boolean {
        return try {
            if (equalizer == null) {
                // AudioSession 0 applies to global system audio stream
                equalizer = Equalizer(0, 0).apply {
                    enabled = true
                }
            }

            equalizer?.let { eq ->
                val numBands = eq.numberOfBands
                val bandLevelRange = eq.bandLevelRange
                val maxBoost = bandLevelRange[1].coerceAtMost(800) // max +8dB
                val lowCut = (bandLevelRange[0] / 2).coerceAtLeast(-600) // -6dB low rumble cut

                for (i in 0 until numBands) {
                    val centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000
                    when {
                        // Bass & sub-bass rumble (< 300Hz) - reduce to stop explosions from masking footsteps
                        centerFreqHz < 300 -> eq.setBandLevel(i.toShort(), lowCut.toShort())
                        // Footstep impact and weapon reload frequencies (1kHz - 4kHz) - boost
                        centerFreqHz in 1000..5000 -> eq.setBandLevel(i.toShort(), maxBoost.toShort())
                        // High-frequency crystal clarity & audio cues (up to 16kHz)
                        centerFreqHz >= 8000 -> eq.setBandLevel(i.toShort(), maxBoost.toShort())
                        // Mid voice clarity (500Hz - 1000Hz)
                        else -> eq.setBandLevel(i.toShort(), (maxBoost / 2).toShort())
                    }
                }
                eq.enabled = true
            }

            // Optional loudness enhancer to bring distant quiet footsteps up
            try {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(0).apply {
                        setTargetGain(300) // +3dB target compression
                        enabled = true
                    }
                } else {
                    loudnessEnhancer?.enabled = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "LoudnessEnhancer optional fallback: ${e.message}")
            }

            isFootstepBoostEnabled = true
            Log.i(TAG, "Footstep Audio Booster activated successfully.")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize AudioEffect Equalizer: ${e.message}")
            disableBoost()
            false
        }
    }

    /**
     * Disables audio enhancement and restores default audio pipeline.
     */
    fun disableBoost() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
            equalizer = null

            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null

            isFootstepBoostEnabled = false
            Log.i(TAG, "Footstep Audio Booster disabled and audio effects released.")
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing audio effects: ${e.message}")
        }
    }
}
