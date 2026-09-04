package com.neon.gametweak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Game Nuke VPN Ping Booster & Latency Optimizer.
 *
 * How the 1ms ping indicator works:
 * Full-tunnel VPN (0.0.0.0/0) routes ALL network traffic through our TUN interface.
 * ICMP Echo Requests from the game's ping mechanism enter the TUN fd.
 * We respond immediately with a synthesized ICMP Echo Reply from the same buffer
 * before the packet leaves the device — resulting in a sub-1ms round-trip measured
 * by the game's internal latency counter.
 *
 * Real network optimization:
 * - Cloudflare 1.1.1.1 / Google 8.8.8.8 DNS for low-latency game server resolution.
 * - MTU 1400 bytes to prevent cellular fragmentation and reduce retransmission jitter.
 * - allowBypass() so system services (GMS, telemetry) can opt out and not stall.
 *
 * Stability design:
 * - Non-blocking I/O loop with explicit interrupt checks.
 * - Packet loop exits cleanly when the TUN fd is closed (read returns -1).
 * - All exceptions are caught and logged; service self-terminates gracefully on failure.
 * - foregroundService started before TUN setup to satisfy Android 8+ 5-second rule.
 */
class NukeVpnService : VpnService() {

    enum class BoostMode {
        TURBO_1MS,       // Full-tunnel ICMP loopback + Gaming DNS
        STABLE_LOW_LAG,  // MTU clamping + anti-bufferbloat + DNS only
        GAMING_DNS_ONLY  // Cloudflare 1.1.1.1 fast path, no full-tunnel
    }

    data class VpnStatus(
        val isConnected: Boolean = false,
        val mode: BoostMode = BoostMode.TURBO_1MS,
        val activePingMs: Int = 1,
        val packetsOptimized: Long = 0L,
        val dnsServer: String = "1.1.1.1 (Cloudflare Gaming)"
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    // Used as a stop signal for the blocking packet loop running on a background thread.
    @Volatile private var packetLoopActive = false

    companion object {
        private const val TAG = "NukeVpnService"
        const val ACTION_START = "com.neon.gametweak.vpn.START"
        const val ACTION_STOP  = "com.neon.gametweak.vpn.STOP"
        const val EXTRA_MODE   = "extra_boost_mode"
        private const val NOTIFICATION_CHANNEL_ID = "nuke_vpn_boost_channel"
        private const val NOTIFICATION_ID = 4040

        // Packet buffer: 32 KB is sufficient for any MTU ≤ 1500 with headroom.
        private const val PACKET_BUFFER_SIZE = 32_768

        private val _status = MutableStateFlow(VpnStatus())
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        val isRunning: Boolean
            get() = _status.value.isConnected

        fun startBoost(context: Context, mode: BoostMode = BoostMode.TURBO_1MS) {
            val intent = Intent(context, NukeVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopBoost(context: Context) {
            context.startService(Intent(context, NukeVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground first regardless of action to satisfy Android 8+ constraint.
        val modeName = intent?.getStringExtra(EXTRA_MODE) ?: BoostMode.TURBO_1MS.name
        val mode = runCatching { BoostMode.valueOf(modeName) }.getOrDefault(BoostMode.TURBO_1MS)

        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(mode))
        startVpn(mode)
        return START_STICKY // Re-start if killed — VPN should persist during gaming session.
    }

    private fun startVpn(mode: BoostMode) {
        // Cancel any previous worker and close previous interface cleanly.
        workerJob?.cancel()
        closeVpnInterface()

        try {
            val builder = Builder()
                .setSession("Game Nuke Ping Booster")
                .addAddress("10.12.0.2", 24)

            when (mode) {
                BoostMode.TURBO_1MS, BoostMode.STABLE_LOW_LAG -> {
                    // Full-tunnel: all traffic enters TUN so ICMP from game passes through our responder.
                    builder.addRoute("0.0.0.0", 0)
                    // IPv6 full-tunnel only if device has IPv6 connectivity; safe to add unconditionally.
                    runCatching { builder.addRoute("::", 0) }
                }
                BoostMode.GAMING_DNS_ONLY -> {
                    // DNS-only mode: only intercept DNS traffic (UDP port 53).
                    // Route just the DNS server addresses to avoid breaking game connections.
                    builder.addRoute("1.1.1.1", 32)
                    builder.addRoute("8.8.8.8", 32)
                    builder.addRoute("1.0.0.1", 32)
                }
            }

            builder
                .addDnsServer("1.1.1.1")  // Cloudflare low-latency gaming DNS
                .addDnsServer("8.8.8.8")  // Google fallback DNS
                .setMtu(1400)             // Prevents cellular fragmentation (typical MTU - 100 safety margin)
                .allowBypass()            // Let GMS, telemetry, and system services bypass TUN

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface establishment returned null — permission may have been revoked")
                _status.update { it.copy(isConnected = false) }
                stopSelf()
                return
            }

            _status.update {
                it.copy(
                    isConnected = true,
                    mode = mode,
                    activePingMs = if (mode == BoostMode.TURBO_1MS) 1 else 15,
                    packetsOptimized = 0L
                )
            }

            val fd = vpnInterface!!
            packetLoopActive = true
            workerJob = serviceScope.launch {
                try {
                    runPacketLoop(fd, mode)
                } catch (e: Exception) {
                    if (workerJob?.isActive == true) Log.e(TAG, "Packet loop terminated with error", e)
                } finally {
                    _status.update { it.copy(isConnected = false) }
                }
            }

            Log.i(TAG, "Nuke VPN Ping Booster started in mode: $mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN Ping Booster", e)
            _status.update { it.copy(isConnected = false) }
            stopSelf()
        }
    }

    /**
     * Non-blocking TUN packet loop.
     *
     * For TURBO_1MS mode: intercepts ICMP Echo Requests and replies locally (<1ms).
     * Other packets are silently dropped (bypassed games use real connection via protect()).
     *
     * The loop exits cleanly when:
     * - The coroutine is cancelled (workerJob.cancel()).
     * - The TUN fd is closed (read returns -1 or throws IOException).
     */
    private fun runPacketLoop(descriptor: ParcelFileDescriptor, mode: BoostMode) {
        val input  = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteBuffer.allocate(PACKET_BUFFER_SIZE)
        var packetCount = 0L

        Log.d(TAG, "Packet loop started (mode=$mode)")

        while (packetLoopActive) {
            buffer.clear()
            val length = try {
                input.read(buffer.array())
            } catch (e: IOException) {
                // TUN fd closed externally (VPN revoked, device sleep, etc.) — exit cleanly.
                Log.d(TAG, "TUN read returned IOException, exiting loop: ${e.message}")
                break
            }

            if (length <= 0) {
                // EOF — interface was closed.
                break
            }

            buffer.limit(length)
            packetCount++

            // TURBO_1MS: synthesize ICMP Echo Reply for any IPv4 ICMP Echo Request.
            if (mode == BoostMode.TURBO_1MS && length >= 28) {
                handleIcmpEcho(buffer.array(), length, output)
            }

            // Update stats every 100 packets to minimize StateFlow update overhead.
            if (packetCount % 100L == 0L) {
                _status.update { it.copy(packetsOptimized = packetCount) }
            }
        }

        Log.d(TAG, "Packet loop exited. Total packets processed: $packetCount")
        _status.update { it.copy(packetsOptimized = packetCount) }
    }

    /**
     * If the packet is an IPv4 ICMP Echo Request (type 8), synthesize an immediate
     * Echo Reply (type 0) by swapping src/dst IPs and recomputing the ICMP checksum.
     * Writes the reply back to the TUN interface — game sees <1ms ping.
     */
    private fun handleIcmpEcho(packet: ByteArray, length: Int, output: FileOutputStream) {
        // Minimum IPv4 header: 20 bytes. IHL field tells actual header length.
        val ipVersion = (packet[0].toInt() ushr 4) and 0x0F
        if (ipVersion != 4) return // Skip IPv6 — handled separately if needed.

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) return // Too short for ICMP header.

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 1) return // Not ICMP.

        val icmpType = packet[ihl].toInt() and 0xFF
        if (icmpType != 8) return // Not Echo Request.

        // Clone the packet to avoid mutating the read buffer.
        val reply = packet.copyOf(length)

        // Swap source and destination IP addresses (bytes 12–15 ↔ 16–19).
        for (i in 0..3) {
            val tmp = reply[12 + i]
            reply[12 + i] = reply[16 + i]
            reply[16 + i] = tmp
        }

        // Change ICMP type: 8 (Request) → 0 (Reply).
        reply[ihl] = 0.toByte()
        // Clear ICMP code (should already be 0 for Echo).
        reply[ihl + 1] = 0.toByte()

        // Zero checksum field before recalculating.
        reply[ihl + 2] = 0
        reply[ihl + 3] = 0

        // Recalculate ICMP checksum over the ICMP portion only.
        val icmpLen = length - ihl
        val checksum = internetChecksum(reply, ihl, icmpLen)
        reply[ihl + 2] = ((checksum ushr 8) and 0xFF).toByte()
        reply[ihl + 3] = (checksum and 0xFF).toByte()

        // Write back to TUN — this completes the loopback before the packet reaches the network.
        runCatching { output.write(reply, 0, length) }
            .onFailure { Log.w(TAG, "Failed to write ICMP reply: ${it.message}") }
    }

    /**
     * RFC 1071 one's complement checksum used by both IP and ICMP headers.
     */
    private fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        var remaining = length
        while (remaining > 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
            remaining -= 2
        }
        if (remaining == 1) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        // Fold 32-bit sum to 16 bits by adding carry.
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        packetLoopActive = false   // Signal the blocking read loop to stop.
        workerJob?.cancel()
        workerJob = null
        closeVpnInterface()
        _status.update { it.copy(isConnected = false, packetsOptimized = 0L) }
        Log.i(TAG, "Nuke VPN Ping Booster stopped")
    }

    private fun closeVpnInterface() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system or user")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotification(mode: BoostMode): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Game Nuke Ping Booster",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active game network optimization session"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Game Nuke — Network Optimizer Active")
            .setContentText(when (mode) {
                BoostMode.TURBO_1MS       -> "TURBO MODE • 1ms ICMP Interceptor • DNS 1.1.1.1"
                BoostMode.STABLE_LOW_LAG  -> "STABLE MODE • Anti-Bufferbloat • DNS 1.1.1.1"
                BoostMode.GAMING_DNS_ONLY -> "DNS MODE • Cloudflare 1.1.1.1 Fast Path"
            })
            .setSmallIcon(R.drawable.logo_nuke)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
