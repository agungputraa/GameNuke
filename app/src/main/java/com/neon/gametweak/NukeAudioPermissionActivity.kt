package com.neon.gametweak

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * NukeAudioPermissionActivity — Transparent runtime permission solicitor for Voice Changer.
 * Prompts runtime microphone permission seamlessly without disrupting game overlay sessions.
 */
class NukeAudioPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onAudioPermissionGranted()
            finish()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_AUDIO_PERM
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onAudioPermissionGranted()
            } else {
                NukeToast.error(this, "Microphone permission denied. Voice Changer requires audio permission.")
            }
        }
        finish()
    }

    private fun onAudioPermissionGranted() {
        val vc = NukeVoiceChangerEngine.getInstance(applicationContext)
        val started = vc.start()
        if (started) {
            NukeVoiceChangerFloatingOverlay.getInstance(applicationContext).show()
            NukeToast.success(this, "🎙️ Voice Changer Active! Floating over game.")
        }
    }

    companion object {
        private const val REQ_AUDIO_PERM = 7701

        fun launch(context: Context) {
            val appContext = context.applicationContext
            // 1. Privileged Silent Grant via iADB / Shizuku / Wireless ADB (Zero disruption to active game)
            if (NukeConnectionManager.isConnected()) {
                val res = NukeConnectionManager.executeCommand("pm grant ${appContext.packageName} android.permission.RECORD_AUDIO", 3_000L)
                val vc = NukeVoiceChangerEngine.getInstance(appContext)
                if (vc.hasRecordPermission()) {
                    if (vc.start()) {
                        NukeVoiceChangerFloatingOverlay.getInstance(appContext).show()
                        NukeToast.success(appContext, "🎙️ Microphone Permission Granted (In-Game)")
                    }
                    return
                }
            }

            // 2. Fallback to isolated transparent activity
            val intent = Intent(appContext, NukeAudioPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            appContext.startActivity(intent)
        }
    }
}
