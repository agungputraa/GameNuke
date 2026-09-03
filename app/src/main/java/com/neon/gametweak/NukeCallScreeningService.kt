package com.neon.gametweak

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Official CallScreeningService used only after the user grants ROLE_CALL_SCREENING.
 * It rejects incoming calls only while the Game Nuke gaming session and Call Shield are both on.
 */
class NukeCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val shouldReject = runCatching {
            val prefs = getSharedPreferences("NukePrefs", MODE_PRIVATE)
            val activeSession = prefs.safeBoolean("overlay_active_session", false)
            val enabled = prefs.safeBoolean(NukeCallShield.PREF_ENABLED, false)
            val incoming = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                callDetails.callDirection == Call.Details.DIRECTION_INCOMING
            } else true
            activeSession && enabled && incoming
        }.getOrDefault(false)

        val response = CallScreeningService.CallResponse.Builder().apply {
            setDisallowCall(shouldReject)
            setRejectCall(shouldReject)
            setSkipCallLog(false)
            setSkipNotification(false)
        }.build()
        runCatching { respondToCall(callDetails, response) }
    }
}
