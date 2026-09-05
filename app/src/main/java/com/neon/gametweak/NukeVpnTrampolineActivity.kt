package com.neon.gametweak

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent Trampoline Activity to request system VPN permission
 * and handle the user's approval callback reliably across Android 11 - 16.
 */
class NukeVpnTrampolineActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            NukeVpnService.startBoost(applicationContext, NukeVpnService.BoostMode.PING_BOOST)
            NukeToast.success(applicationContext, "VPN Ping Boost: ACTIVE ⚡")
        } else {
            NukeToast.error(applicationContext, "Izin VPN ditolak pengguna")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            runCatching {
                vpnPermissionLauncher.launch(prepareIntent)
            }.onFailure { e ->
                NukeToast.error(applicationContext, "Gagal membuka dialog izin VPN: ${e.message}")
                finish()
            }
        } else {
            // Already granted
            NukeVpnService.startBoost(applicationContext, NukeVpnService.BoostMode.PING_BOOST)
            NukeToast.success(applicationContext, "VPN Ping Boost: ACTIVE ⚡")
            finish()
        }
    }
}
