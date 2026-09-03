package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (NotificationHelper.ACTION_PAIR != intent.action) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = remoteInput.getCharSequence(NotificationHelper.EXTRA_REPLY)?.toString()?.trim().orEmpty()
        val hintedTarget = intent.getStringExtra("pairing_target")
        val appContext = context.applicationContext
        val notif = NotificationHelper(appContext)

        notif.updateNotification(
            Tx.t("Menghubungkan Device Control...", "Connecting Device Control..."),
            Tx.t("Memvalidasi kode dan endpoint terbaru.", "Validating the code and latest endpoint."),
            true,
            false,
            null,
        )

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            runCatching { AdbManager.getInstance(appContext).writeTraceLog("PAIR RECEIVER COROUTINE: ${error.message}") }
        }).launch {
            try {
                val adb = AdbManager.getInstance(appContext)
                val result = adb.pairSmart(code = code, hintedTarget = hintedTarget)
                val mainHandler = Handler(Looper.getMainLooper())

                if (result.success) {
                    mainHandler.post {
                        NukeToast.success(appContext, result.message, long = true)
                    }
                    notif.updateNotification(
                        if (result.connected) Tx.t("Game Nuke Core Online", "Game Nuke Core Online") else Tx.t("Pairing Berhasil", "Pairing Successful"),
                        result.message,
                        true,
                        false,
                        null,
                    )
                    // pairSmart already restarted discovery and attempted the separate TLS
                    // connect port. Avoid restarting the scanner again here so a just-discovered
                    // endpoint is not cancelled by a second generation.
                } else {
                    mainHandler.post {
                        NukeToast.error(appContext, Tx.t("Pairing gagal: ${result.message}", "Pairing failed: ${result.message}"), long = true)
                    }
                    notif.updateNotification(
                        Tx.t("Pairing Gagal", "Pairing Failed"),
                        result.message,
                        false,
                        false,
                        null,
                    )
                    if (result.recoverable) adb.startNetworkScanner()
                }
            } catch (t: Throwable) {
                val rawMessage = t.message ?: t.javaClass.simpleName
                runCatching {
                    AdbManager.getInstance(appContext).writeTraceLog("PAIR RECEIVER FATAL: $rawMessage")
                }
                val userMessage = Tx.t("Koneksi berhenti tak terduga. Buka ulang Wireless Debugging, buat kode baru, lalu coba lagi.", "The connection stopped unexpectedly. Reopen Wireless Debugging, generate a new code, and try again.")
                Handler(Looper.getMainLooper()).post {
                    NukeToast.error(appContext, userMessage, long = true)
                }
                notif.updateNotification(Tx.t("Pairing Gagal", "Pairing Failed"), userMessage, false, false, null)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
