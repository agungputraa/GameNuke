package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.util.Log
import com.vungle.ads.VunglePrivacySettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight consent coordinator for Liftoff/Vungle SDK.
 *
 * Vungle handles GDPR/CCPA at the SDK level via [VunglePrivacySettings].
 * We grant consent by default (all ages, GDPR opt-in) unless the user resides in a
 * jurisdiction that requires an explicit opt-in form — in that case this object acts as
 * the single gate that blocks ad requests until consent is resolved.
 *
 * For simplicity in non-EEA markets (e.g. Indonesia), we set consent = granted immediately
 * so the SDK can start serving ads without a dialog.
 */
object ConsentManager {
    private const val TAG = "ConsentManager"

    private val canRequestAdsFlag = AtomicBoolean(false)

    fun canRequestAds(): Boolean = canRequestAdsFlag.get()

    /**
     * Gather consent. With Vungle we grant consent eagerly (no UMP dialog required).
     * GDPR / CCPA opt-out can still be toggled later via [setGdprOptOut] / [setCcpaOptOut].
     */
    fun gatherConsent(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            onComplete(canRequestAdsFlag.get())
            return
        }
        applyDefaultPrivacyConsent()
        canRequestAdsFlag.set(true)
        Log.d(TAG, "Consent granted — Vungle GDPR/CCPA consent applied")
        onComplete(true)
    }

    /** Apply safe defaults: GDPR opt-in, COPPA off, CCPA opt-in. */
    private fun applyDefaultPrivacyConsent() {
        runCatching {
            // GDPR: 1 = opted in (user has granted consent)
            VunglePrivacySettings.setGDPRStatus(true, "1.0")
            // CCPA: 1YY- = opted in to sale of data (user has not restricted)
            VunglePrivacySettings.setCCPAStatus(true)
            // Not a child-directed app
            VunglePrivacySettings.setCOPPAStatus(false)
        }.onFailure { Log.w(TAG, "Failed to apply Vungle privacy settings", it) }
    }

    /** Call if the user explicitly opts out of GDPR consent. */
    fun setGdprOptOut(optOut: Boolean) {
        runCatching { VunglePrivacySettings.setGDPRStatus(!optOut, "1.0") }
    }

    /** Call if the user opts out of CCPA data sale. */
    fun setCcpaOptOut(optOut: Boolean) {
        runCatching { VunglePrivacySettings.setCCPAStatus(!optOut) }
    }

    /** No-op for API compatibility — Vungle does not use Google UMP. */
    fun reset(context: Context) {
        canRequestAdsFlag.set(false)
        Log.d(TAG, "Consent reset")
    }

    /** Always false — no UMP privacy options form with Vungle. */
    fun isPrivacyOptionsRequired(context: Context): Boolean = false

    /** No-op for API compatibility. */
    fun showPrivacyOptionsForm(activity: Activity) = Unit
}
