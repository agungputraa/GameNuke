package com.neon.gametweak

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline activity that shows the Android system VPN permission dialog.
 * After the user grants (or denies) permission, it starts NukeGameVpnService and finishes.
 *
 * This is needed because VpnService.prepare() must be called from an Activity context,
 * not a Service context.
 */
class NukeVpnPermissionActivity : Activity() {

    companion object {
        private const val TAG = "NukeVpnPerm"
        private const val REQUEST_VPN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout — fully transparent
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            // Already granted — start VPN and close
            Log.i(TAG, "VPN permission already granted, starting service")
            NukeGameVpnService.start(this)
            finish()
        } else {
            // Show system dialog
            @Suppress("DEPRECATION")
            startActivityForResult(vpnIntent, REQUEST_VPN)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "VPN permission granted — starting NukeGameVpnService")
                NukeGameVpnService.start(this)
            } else {
                Log.w(TAG, "VPN permission denied by user")
            }
        }
        finish()
    }
}
