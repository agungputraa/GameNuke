package com.neon.gametweak

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Small role-request bridge for Android's official Call Screening role.
 *
 * Game Nuke deliberately does not request contacts or call-log data. The shield handles only calls
 * Android makes eligible for the role and keeps the permission footprint appropriate for Play.
 */
class CallShieldRoleActivity : Activity() {
    companion object { private const val REQUEST_ROLE = 4102 }
    private var requestStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { finishSafely(); return }
        if (NukeCallShield.isRoleHeld(this)) { finishRoleSetup(); return }
        val roleManager = getSystemService(RoleManager::class.java) ?: run { finishSafely(); return }
        val available = runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
        if (!available) { finishSafely(); return }
        requestStarted = true
        runCatching {
            startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQUEST_ROLE)
        }.onFailure { finishSafely() }
    }

    override fun onResume() {
        super.onResume()
        if (requestStarted && NukeCallShield.isRoleHeld(this)) finishRoleSetup()
    }

    @Deprecated("Deprecated in Android framework; retained for API 29+ role chooser compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROLE) return
        if (resultCode == RESULT_OK || NukeCallShield.isRoleHeld(this)) finishRoleSetup() else {
            NukeCallShield.setEnabled(this, false)
            finishSafely()
        }
    }

    private fun finishRoleSetup() {
        NukeCallShield.setEnabled(this, NukeCallShield.isRoleHeld(this))
        finishSafely()
    }

    private fun finishSafely() {
        runCatching { finish() }
        runCatching { overridePendingTransition(0, 0) }
    }
}
