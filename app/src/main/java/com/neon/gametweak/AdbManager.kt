package com.neon.gametweak

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.github.muntashirakon.adb.AdbStream
import java.io.FileWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

data class NukeCommandResult(
    val exitCode: Int,
    val output: String = "",
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0
}

data class NukePairingResult(
    val success: Boolean,
    val message: String,
    val pairedTarget: String? = null,
    val connected: Boolean = false,
    val recoverable: Boolean = false,
)

private data class AdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun target(): String {
        val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        return "$formattedHost:$port"
    }
}

private data class LegacyResolveRequest(
    val service: NsdServiceInfo,
    val isPairing: Boolean,
)


class AdbManager private constructor(context: Context) {
    private val mContext: Context = context.applicationContext
    private val prefs = mContext.getSharedPreferences("NeonAdbPrefs", Context.MODE_PRIVATE)
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private val legacyResolveQueue = ConcurrentLinkedQueue<LegacyResolveRequest>()
    private val legacyResolving = AtomicBoolean(false)
    private val scannerGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var mdnsMulticastLock: WifiManager.MulticastLock? = null

    @Volatile private var latestPairingEndpoint: AdbEndpoint? = null
    @Volatile private var latestConnectEndpoint: AdbEndpoint? = null
    @Volatile private var connectedFlag = false
    @Volatile private var authorizationRevokedHint = prefs.safeBoolean("authorization_revoked_hint", false)

    companion object {
        private const val PAIR_ENDPOINT_TTL_MS = 45_000L
        private const val CONNECT_ENDPOINT_TTL_MS = 90_000L

        @Volatile
        private var instance: AdbManager? = null
        fun getInstance(context: Context): AdbManager = instance ?: synchronized(this) {
            instance ?: AdbManager(context).also { instance = it }
        }
    }

    private fun manager(): AdbConnectionManager? =
        runCatching { AdbConnectionManager.getInstance(mContext) }.getOrNull()

    fun authorizationRevoked(): Boolean = authorizationRevokedHint

    fun clearAuthorizationRevokedHint() {
        authorizationRevokedHint = false
        prefs.edit().putBoolean("authorization_revoked_hint", false).apply()
    }

    private fun observeAuthorizationFailure(message: String) {
        val lower = message.lowercase(Locale.US)
        val explicitAuthFailure = listOf(
            "unauthorized", "authentication failed", "auth failed", "public key rejected",
            "failed to authenticate", "authorization rejected", "key rejected",
        ).any(lower::contains)
        if (!explicitAuthFailure) return
        authorizationRevokedHint = true
        prefs.edit().putBoolean("authorization_revoked_hint", true).apply()
        // NOTE: We intentionally do NOT stop the daemon here. The daemon operates via Unix
        // abstract socket and is completely independent of wireless ADB authorization state.
        // Shizuku works the same way: the daemon stays alive regardless of ADB key status.
        // The user must explicitly re-pair to clear the revocation hint.
        connectedFlag = false
        writeTraceLog("AUTHORIZATION REVOKED/REJECTED (daemon preserved): $message")
    }

    fun writeTraceLog(msg: String) {
        try {
            mContext.getExternalFilesDir(null)?.let { dir ->
                val logFile = java.io.File(dir, "adb_trace.log")
                FileWriter(logFile, true).use { fw ->
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    fw.write("[$time] $msg\n")
                }
            }
        } catch (_: Throwable) {}
    }

    fun isLocalBinaryAvailable(): Boolean = true

    fun isConnected(): Boolean {
        if (NukeConnectionManager.isConnected()) { connectedFlag = true; return true }
        if (NukeDaemonClient.ping()) { connectedFlag = true; return true }
        return try {
            val m = manager() ?: return false
            val ok = m.isConnected
            connectedFlag = ok
            ok
        } catch (_: Throwable) {
            connectedFlag
        }
    }

    /**
     * Open a short, fixed ADB service destination and stream the actual command over stdin.
     *
     * libadb 3.1.1 has a known BufferOverflowException in AdbProtocol.generateOpen() when the
     * service destination itself becomes long. Game Nuke used to call `openStream("shell:<cmd>")`,
     * so a long game-overlay snapshot or the exit-code wrapper could poison an otherwise healthy
     * ADB session. `shell:sh -s` keeps the OPEN destination constant and lets Android's shell read
     * the script from stdin instead.
     */
    private fun openShellStream(command: String, dir: String?): AdbStream? {
        var stream: AdbStream? = null
        return try {
            val m = manager() ?: return null
            if (!m.isConnected && !reconnectFromKnownEndpoint(m)) return null

            stream = m.openStream("shell:sh -s") ?: return null
            val safeDir = dir?.takeIf { it.isNotBlank() && it != "null" && it != "/" }
            val full = if (safeDir != null) {
                "cd ${shellQuote(safeDir)} && { $command; }"
            } else command

            // Explicit exit makes the one-shot script deterministic without closing the local
            // output stream early (which can close the whole ADB stream on some implementations).
            val payload = "$full\nexit\n".toByteArray(StandardCharsets.UTF_8)
            stream.openOutputStream().apply {
                write(payload)
                flush()
            }
            stream
        } catch (t: Throwable) {
            runCatching { stream?.close() }
            connectedFlag = false
            val message = rootMessage(t)
            writeTraceLog("OPEN SHELL: $message")
            observeAuthorizationFailure(message)
            if (!authorizationRevokedHint) startNetworkScanner()
            null
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Self-heal a dropped Wireless ADB transport without running an always-on polling daemon. */
    private fun reconnectFromKnownEndpoint(m: AdbConnectionManager): Boolean {
        val candidates = buildList<AdbEndpoint> {
            latestConnectEndpoint?.takeIf { isEndpointFresh(it, CONNECT_ENDPOINT_TTL_MS) }?.let(::add)

            // Persisten: selalu coba target tersimpan tanpa batas waktu.
            // TTL lama (120 detik) menyebabkan reconnect gagal setelah WiFi dimatikan.
            val saved = getTarget().takeIf { it.isNotBlank() }
            saved?.let(::parseTarget)?.let { (host, port) ->
                if (none { it.host == host && it.port == port }) {
                    add(AdbEndpoint(host, port, "saved"))
                }
            }
        }

        for (endpoint in candidates) {
            val ok = runCatching { m.connect(endpoint.host, endpoint.port) || m.isConnected }
                .onFailure {
                    val message = rootMessage(it)
                    writeTraceLog("SELF-HEAL ${endpoint.target()}: $message")
                    observeAuthorizationFailure(message)
                }
                .getOrDefault(false)
            if (ok) {
                connectedFlag = true
                clearAuthorizationRevokedHint()
                saveTarget(endpoint.target())
                stopNetworkScanner()
                return true
            }
        }

        connectedFlag = false
        startNetworkScanner()
        return false
    }

    private fun newProcess(command: String, dir: String?): Process {
        val stream = openShellStream(command, dir)
        return AdbShellProcess(stream)
    }

    /** Execute through the unified connection manager or wireless ADB transport. */
    fun executeCommand(
        command: String,
        dir: String? = "/",
        timeoutMs: Long = 7_500L,
        maxOutputChars: Int = 131_072,
    ): NukeCommandResult {
        // Execute optimistically through the unified connection manager (iAdb -> Shizuku -> Local Daemon).
        // This makes all gateway, telemetry, booster, and deep cleaner features work instantly under any connected bridge.
        NukeConnectionManager.executeCommand(command, timeoutMs, maxOutputChars)?.let {
            connectedFlag = true
            return it
        }
        // A live Wireless ADB transport may be used as the fallback.
        return executeCommandDirect(command, dir, timeoutMs, maxOutputChars)
    }

    /**
     * Execute one already hash-verified ModuleShop entry under the Android shell UID.
     *
     * This path intentionally bypasses [NukeDaemonPolicy] only for scripts accepted by
     * [NukeModuleScriptPolicy] and the signed catalog chain. It never uses the persistent local
     * daemon, refuses adb-root transports, masks `su`, and keeps the short `shell:sh -s` service
     * destination so larger module bodies do not hit libadb's OPEN buffer limit.
     */
    internal fun executeVerifiedModuleScript(
        moduleId: String,
        entryName: String,
        script: String,
    ): NukeCommandResult {
        val cleanScript = script.replace("\r\n", "\n")
        writeTraceLog("MODULE $moduleId/$entryName: executing module script")
        NukeConnectionManager.executeCommand(cleanScript, timeoutMs = 45_000L, maxOutputChars = 32_768)?.let {
            return it
        }
        return executeCommandDirect(cleanScript, "/", timeoutMs = 45_000L, maxOutputChars = 32_768)
    }

    fun persistentCoreOnline(): Boolean = NukeDaemonClient.ping()

    /**
     * Best-effort trust verification that deliberately bypasses the local daemon. A missing/stale
     * endpoint is inconclusive (null); only an explicit authentication rejection marks revocation.
     * This lets a persistent local core keep working offline without treating ordinary network loss
     * as a revoked ADB key.
     */
    fun verifyWirelessAuthorizationIfReachable(): Boolean? {
        // When the persistent local core is online via Unix socket, WiFi/wireless ADB state
        // is irrelevant. The daemon works without any network. Only check wireless trust if
        // we can actually reach the endpoint; otherwise return null (inconclusive).
        if (authorizationRevokedHint) {
            // Even if auth was revoked, if the daemon is alive it should keep working.
            // Return null (inconclusive) instead of false to prevent the orchestrator
            // from killing the daemon.
            return null
        }
        val m = manager() ?: return null
        if (runCatching { m.isConnected }.getOrDefault(false)) {
            clearAuthorizationRevokedHint()
            return true
        }
        val discoveredEndpoint = latestConnectEndpoint?.takeIf { isEndpointFresh(it, CONNECT_ENDPOINT_TTL_MS) }
        val endpoint = discoveredEndpoint
            ?: getTarget().takeIf { it.isNotBlank() }?.let(::parseTarget)?.let { (host, port) ->
                AdbEndpoint(host, port, "saved-health")
            }
            ?: return null
        return try {
            val ok = m.connect(endpoint.host, endpoint.port) || m.isConnected
            if (ok) {
                connectedFlag = true
                clearAuthorizationRevokedHint()
                saveTarget(endpoint.target())
                true
            } else if (discoveredEndpoint != null && tcpEndpointReachable(endpoint)) {
                observeAuthorizationFailure("authorization rejected by reachable wireless ADB endpoint")
                false
            } else null  // endpoint unreachable — NOT an auth failure, just offline
        } catch (t: Throwable) {
            // Network errors (timeout, unreachable, connection refused) are NOT authorization
            // failures. They just mean WiFi is off or the device is unreachable. The daemon
            // should keep running via Unix socket regardless.
            val message = rootMessage(t)
            writeTraceLog("TRUST CHECK ${endpoint.target()}: $message (network, not auth)")
            null  // Inconclusive — preserve daemon
        }
    }

    private fun tcpEndpointReachable(endpoint: AdbEndpoint): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 650)
            socket.isConnected
        }
    }.getOrDefault(false)

    fun connectionMode(): String = when {
        NukeDaemonClient.ping() -> "LOCAL CORE"
        runCatching { manager()?.isConnected == true }.getOrDefault(false) -> "WIRELESS ADB"
        else -> "OFFLINE"
    }

    fun stopPersistentCore(): Boolean = NukeDaemonClient.stop()

    /**
     * Starts a shell-UID local helper from the app APK after a real authorized ADB connection.
     * The helper intentionally does not survive reboot and exits when this package is uninstalled.
     * Saves a persistent flag so the app knows to try the local socket on next launch.
     *
     * Launch pattern follows Shizuku exactly:
     *   (CLASSPATH='<apk>' app_process -Djava.class.path='<apk>' /system/bin --nice-name=<name> <class> <uid>)&
     * The subshell fork `(cmd)&` makes the process fully independent of the ADB shell session.
     */
    fun ensurePersistentCore(): Boolean {
        if (NukeDaemonClient.ping(force = true)) {
            prefs.edit().putBoolean("daemon_ever_started", true).apply()
            return true
        }
        val m = manager() ?: return false
        if (!runCatching { m.isConnected }.getOrDefault(false)) return false

        // Kill stale daemon only if socket exists but doesn't respond (zombie).
        // Use the existing ADB connection to run the kill.
        runCatching {
            executeCommandDirect("pkill -f game-nuke-core 2>/dev/null || true", timeoutMs = 1_500L, maxOutputChars = 128)
            Thread.sleep(400)  // Wait for socket to be released
        }

        val apkResult = executeCommandDirect("pm path ${mContext.packageName}", timeoutMs = 3_500L, maxOutputChars = 8_192)
        val apkPath = apkResult.output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim().orEmpty()
        if (apkPath.isBlank()) return false
        val quotedApk = shellQuote(apkPath)
        val myUid = android.os.Process.myUid()
        val className = NukeShellDaemon::class.java.name
        // ── Shizuku's exact launch pattern + /dev/null to prevent SIGPIPE ──
        // Without /dev/null: when the ADB shell stream closes after this command returns,
        // the daemon's inherited stdout fd becomes a broken pipe. Any write (even from the
        // JVM internals) triggers SIGPIPE → daemon dies immediately.
        // With /dev/null: stdout/stderr/stdin are detached from the ADB stream. The daemon
        // lives independently, communicating only via Unix abstract socket.
        val launch = "(export CLASSPATH=$quotedApk; exec /system/bin/app_process /system/bin --nice-name=game-nuke-core $className $myUid </dev/null >/dev/null 2>&1)&"
        writeTraceLog("BOOTSTRAP CMD: $launch")
        val result = executeCommandDirect(launch, timeoutMs = 4_500L, maxOutputChars = 4_096)
        if (!result.isSuccess && result.exitCode != 0) {
            writeTraceLog("PERSISTENT CORE BOOTSTRAP FAILED: exit=${result.exitCode} out=${result.output.take(500)}")
        }
        // Wait for daemon to start accepting connections (up to ~2.4 seconds)
        repeat(16) {
            if (NukeDaemonClient.ping(force = true)) {
                writeTraceLog("PERSISTENT CORE ONLINE (Shizuku-style fork, /dev/null)")
                prefs.edit()
                    .putBoolean("daemon_ever_started", true)
                    .putLong("daemon_start_time", System.currentTimeMillis())
                    .apply()
                return true
            }
            try { Thread.sleep(150L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        writeTraceLog("PERSISTENT CORE BOOTSTRAP: daemon did not respond after 2.4s")
        return false
    }


    /**
     * Execute a finite shell command and recover its real shell exit code.
     *
     * The legacy Process wrappers can only tell whether a transport exists; they cannot expose the
     * remote shell's exit status. That made failed OEM-specific tweaks look successful and could
     * leave toggle state out of sync with the device. This executor appends a unique sentinel in a
     * parent shell, so even commands containing `exit` are isolated inside a subshell. The reader is
     * bounded and the transport is destroyed on timeout to avoid hanging UI/coroutine work forever.
     */
    private fun executeCommandDirect(
        command: String,
        dir: String? = "/",
        timeoutMs: Long = 7_500L,
        maxOutputChars: Int = 131_072,
    ): NukeCommandResult {
        if (command.isBlank()) return NukeCommandResult(exitCode = -1)

        val marker = "__NUKE_EXIT_${System.nanoTime()}__="
        val wrapped = "( $command ); __nuke_rc=\$?; printf '\\n${marker}%s\\n' \"\$__nuke_rc\""
        val process = runCatching { newProcess(wrapped, dir) }.getOrElse {
            return NukeCommandResult(exitCode = -1, output = it.message.orEmpty())
        }

        val stored = StringBuilder()
        val tail = StringBuilder()
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val readerThread = thread(name = "Nuke-Cmd-${System.nanoTime()}", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        if (stored.length < maxOutputChars) {
                            val keep = minOf(read, maxOutputChars - stored.length)
                            if (keep > 0) stored.append(buffer, 0, keep)
                        }
                        tail.append(buffer, 0, read)
                        if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }

        val timeout = timeoutMs.coerceIn(500L, 120_000L)
        val completed = try {
            done.await(timeout, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            runCatching { process.destroy() }
            runCatching { readerThread.interrupt() }
            runCatching { done.await(350L, TimeUnit.MILLISECONDS) }
            return NukeCommandResult(exitCode = -1, output = stored.toString().take(maxOutputChars), timedOut = true)
        }

        runCatching { process.destroy() }
        val markerText = tail.toString()
        val markerIndex = markerText.lastIndexOf(marker)
        val code = if (markerIndex >= 0) {
            markerText.substring(markerIndex + marker.length)
                .lineSequence().firstOrNull()?.trim()?.toIntOrNull()
        } else null

        val cleanOutput = stored.toString()
            .lineSequence()
            .filterNot { it.startsWith(marker) }
            .joinToString("\n")
            .trimEnd()

        return if (code != null) {
            NukeCommandResult(exitCode = code, output = cleanOutput)
        } else {
            val message = failure.get()?.message.orEmpty()
            NukeCommandResult(
                exitCode = -1,
                output = if (cleanOutput.isNotBlank()) cleanOutput else message,
            )
        }
    }

    fun initServer() {
        thread(name = "Nuke-ADB-AutoConnect", isDaemon = true) {
            // Step 1: Always ping the local Unix socket daemon first.
            // The daemon communicates via abstract socket — works without WiFi.
            // This is the Shizuku model: WiFi only needed for initial pairing.
            if (NukeDaemonClient.ping(force = true)) {
                connectedFlag = true
                writeTraceLog("INIT: local daemon already online via Unix socket")
                return@thread
            }

            // Step 2: If daemon flag was set but ping failed, daemon may have been killed by the system.
            // Try to reconnect via wireless ADB to re-bootstrap the daemon.
            val daemonWasEverStarted = prefs.safeBoolean("daemon_ever_started", false)
            if (daemonWasEverStarted) {
                writeTraceLog("INIT: daemon was running before but socket ping failed, attempting re-bootstrap")
            }

            val m = manager() ?: return@thread
            startNetworkScanner()
            repeat(3) { attempt ->
                if (runCatching { m.isConnected }.getOrDefault(false)) {
                    connectedFlag = true
                    ensurePersistentCore()
                    stopNetworkScanner()
                    return@thread
                }

                val endpoint = latestConnectEndpoint?.takeIf { isEndpointFresh(it, 30_000L) }
                val directConnected = endpoint?.let { ep ->
                    runCatching { m.connect(ep.host, ep.port) || m.isConnected }
                        .onFailure { writeTraceLog("DIRECT AUTOCONNECT ${ep.target()}: ${rootMessage(it)}") }
                        .getOrDefault(false)
                } ?: false

                if (directConnected) {
                    connectedFlag = true
                    endpoint?.let { saveTarget(it.target()) }
                    ensurePersistentCore()
                    stopNetworkScanner()
                    return@thread
                }

                // Try saved target (persistent — no TTL limit)
                val savedTarget = getTarget().takeIf { it.isNotBlank() }
                if (savedTarget != null && attempt == 0) {
                    val parsed = parseTarget(savedTarget)
                    if (parsed != null) {
                        val (host, port) = parsed
                        val savedOk = runCatching { m.connect(host, port) || m.isConnected }
                            .onFailure { writeTraceLog("SAVED TARGET AUTOCONNECT $savedTarget: ${rootMessage(it)}") }
                            .getOrDefault(false)
                        if (savedOk) {
                            connectedFlag = true
                            ensurePersistentCore()
                            stopNetworkScanner()
                            return@thread
                        }
                    }
                }

                runCatching { m.autoConnect(mContext, 3_500L) }
                    .onFailure { writeTraceLog("MDNS AUTOCONNECT[$attempt]: ${rootMessage(it)}") }
                connectedFlag = runCatching { m.isConnected }.getOrDefault(false)
                if (connectedFlag) {
                    ensurePersistentCore()
                    stopNetworkScanner()
                    return@thread
                }

                try {
                    Thread.sleep(450L + attempt * 500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
        }
    }

    fun forceCustomIdentity() { }

    /**
     * Backwards-compatible wrapper. New UI paths should call [pairSmart] so the target can be
     * refreshed from mDNS before the short-lived Android pairing session expires.
     */
    fun pair(target: String, code: String): String {
        val result = pairSmart(code = code, hintedTarget = target)
        return if (result.success) "Paired successfully." else "Pairing error: ${result.message}"
    }

    /**
     * Pairing state machine for Android 11+.
     *
     * 1. Validate the six digit code.
     * 2. Verify bundled Conscrypt exposes the TLS exporter required by ADB pairing.
     * 3. Prefer the freshest _adb-tls-pairing endpoint over the target embedded in an old
     *    notification.
     * 4. Retry safe host aliases (resolved interface address -> loopback) for OEMs that advertise
     *    a service on one interface but accept the local connection on another.
     * 5. Restart discovery after success so the separate _adb-tls-connect port is picked up.
     */
    fun pairSmart(code: String, hintedTarget: String? = null): NukePairingResult {
        val normalizedCode = code.trim()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return NukePairingResult(
                success = false,
                message = "Wireless Debugging pairing membutuhkan Android 11 atau lebih baru.",
            )
        }
        if (!normalizedCode.matches(Regex("^[0-9]{6}$"))) {
            return NukePairingResult(
                success = false,
                message = Tx.t("Kode pairing harus tepat 6 digit.", "Pairing code must be exactly 6 digits."),
                recoverable = true,
            )
        }

        pairingCryptoProblem()?.let { problem ->
            writeTraceLog("PAIR CRYPTO PREFLIGHT: $problem")
            return NukePairingResult(
                success = false,
                message = problem,
            )
        }

        val m = manager() ?: return NukePairingResult(false, Tx.t("Device Control tidak tersedia.", "Device Control is unavailable."))
        val hintedEndpoint = hintedTarget?.let { parseTarget(it) }?.let { (host, port) ->
            AdbEndpoint(host = host, port = port, serviceName = "notification")
        }

        // Notification actions can outlive Android's short-lived pairing port. Before trusting the
        // embedded target, actively refresh mDNS for a short bounded window. This runs on the
        // receiver's IO coroutine, not the UI thread.
        var freshEndpoint = latestPairingEndpoint?.takeIf { isEndpointFresh(it, PAIR_ENDPOINT_TTL_MS) }
        if (freshEndpoint == null) {
            startNetworkScanner()
            freshEndpoint = awaitFreshPairingEndpoint(2_500L)
        }

        val endpoints = buildList<AdbEndpoint> {
            if (freshEndpoint != null) add(freshEndpoint)
            if (hintedEndpoint != null && none { it.host == hintedEndpoint.host && it.port == hintedEndpoint.port }) {
                add(hintedEndpoint)
            }
        }
        if (endpoints.isEmpty()) {
            startNetworkScanner()
            return NukePairingResult(
                success = false,
                message = Tx.t("Port pairing belum ditemukan. Biarkan layar 'Pair device with pairing code' tetap terbuka lalu coba lagi.", "Pairing port was not found. Keep the 'Pair device with pairing code' screen open, then try again."),
                recoverable = true,
            )
        }

        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            for (host in pairingHostCandidates(endpoint.host)) {
                try {
                    writeTraceLog("PAIR TRY ${formatTarget(host, endpoint.port)} service=${endpoint.serviceName}")
                    if (m.pair(host, endpoint.port, normalizedCode)) {
                        setLastPairSuccess()
                        prefs.edit()
                            .putString("last_pair_target", formatTarget(host, endpoint.port))
                            .putLong("last_pair_endpoint_time", System.currentTimeMillis())
                            .apply()
                        writeTraceLog("PAIR OK ${formatTarget(host, endpoint.port)}")

                        // The pairing port and normal ADB connect port are intentionally different.
                        // Refresh immediately because Android normally removes the pairing service
                        // and publishes _adb-tls-connect after a successful pair.
                        latestPairingEndpoint = null
                        startNetworkScanner()
                        val connected = connectAfterPairing()
                        if (connected) stopNetworkScanner()
                        return NukePairingResult(
                            success = true,
                            pairedTarget = formatTarget(host, endpoint.port),
                            connected = connected,
                            message = if (connected) {
                                Tx.t("Pairing berhasil dan Device Control sudah siap.", "Pairing succeeded and Device Control is ready.")
                            } else {
                                Tx.t("Pairing berhasil. Menunggu port koneksi Wireless Debugging yang baru.", "Pairing succeeded. Waiting for the new Wireless Debugging connection port.")
                            },
                            recoverable = !connected,
                        )
                    }

                    // A false return means the pairing protocol reached the peer but the session
                    // was rejected. Retrying loopback aliases with the same short-lived code only
                    // repeats the authentication exchange and can make recovery less predictable.
                    return NukePairingResult(
                        success = false,
                        message = Tx.t("Sesi pairing ditolak. Buat kode 6 digit baru dan pastikan dialog pairing tetap terbuka sampai selesai.", "Pairing session was rejected. Generate a new 6-digit code and keep the pairing dialog open until it finishes."),
                        recoverable = true,
                    )
                } catch (t: Throwable) {
                    lastFailure = t
                    writeTraceLog("PAIR FAIL ${formatTarget(host, endpoint.port)}: ${rootMessage(t)}")
                    val classification = classifyPairingFailure(t)
                    if (classification.fatal) {
                        return NukePairingResult(
                            success = false,
                            message = classification.userMessage,
                            recoverable = classification.recoverable,
                        )
                    }
                    // Authentication/protocol failures will not be fixed by trying a different
                    // local interface. Do not burn the short-lived code on redundant attempts.
                    if (!classification.tryNextHost) break
                }
            }
        }

        val classification = classifyPairingFailure(lastFailure)
        return NukePairingResult(
            success = false,
            message = classification.userMessage,
            recoverable = classification.recoverable,
        )
    }

    fun connect(target: String): String {
        return try {
            val (host, port) = parseTarget(target) ?: return "Invalid address."
            val m = manager() ?: return "Device Control unavailable."
            val ok = m.connect(host, port)
            if (ok || m.isConnected) {
                connectedFlag = true
                clearAuthorizationRevokedHint()
                saveTarget(formatTarget(host, port))
                ensurePersistentCore()
                "connected to ${formatTarget(host, port)} // persistent core ${if (NukeDaemonClient.ping()) "online" else "pending"}"
            } else {
                "failed to connect to ${formatTarget(host, port)}"
            }
        } catch (e: Throwable) {
            connectedFlag = false
            val reason = rootMessage(e)
            writeTraceLog("CONNECT ERROR $target: $reason")
            observeAuthorizationFailure(reason)
            "connect error: $reason"
        }
    }

    private fun connectAfterPairing(): Boolean {
        val m = manager() ?: return false
        repeat(5) { attempt ->
            if (runCatching { m.isConnected }.getOrDefault(false)) {
                connectedFlag = true
                ensurePersistentCore()
                return true
            }

            val endpoint = latestConnectEndpoint?.takeIf { isEndpointFresh(it, CONNECT_ENDPOINT_TTL_MS) }
            if (endpoint != null) {
                val ok = runCatching { m.connect(endpoint.host, endpoint.port) || m.isConnected }
                    .onFailure { writeTraceLog("POST-PAIR CONNECT ${endpoint.target()}: ${rootMessage(it)}") }
                    .getOrDefault(false)
                if (ok) {
                    connectedFlag = true
                    saveTarget(endpoint.target())
                    ensurePersistentCore()
                    return true
                }
            }

            // Give Android time to retire _adb-tls-pairing and advertise
            // _adb-tls-connect. This is normally sub-second but several OEMs are slower.
            try {
                Thread.sleep(350L + attempt * 300L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        connectedFlag = false
        return false
    }

    private data class PairingFailure(
        val userMessage: String,
        val recoverable: Boolean,
        val tryNextHost: Boolean,
        val fatal: Boolean = false,
    )

    private fun classifyPairingFailure(t: Throwable?): PairingFailure {
        if (t == null) {
            return PairingFailure(
                Tx.t("Pairing ditolak. Pastikan kode masih aktif dan coba buat kode baru.", "Pairing was rejected. Make sure the code is still active or generate a new code."),
                recoverable = true,
                tryNextHost = false,
            )
        }
        val chain = generateSequence(t) { it.cause }.toList()
        val all = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        val lower = all.lowercase(Locale.US)

        if (chain.any { it is NoSuchMethodException } || lower.contains("exportkeyingmaterial")) {
            return PairingFailure(
                Tx.t("Engine TLS pairing tidak lengkap. Game Nuke harus memakai bundled Conscrypt; instal build 3.0.1 atau lebih baru.", "The TLS pairing engine is incomplete. Game Nuke must use bundled Conscrypt; install build 3.0.1 or newer."),
                recoverable = false,
                tryNextHost = false,
                fatal = true,
            )
        }
        if (lower.contains("tlsv1.3") || lower.contains("conscrypt")) {
            return PairingFailure(
                Tx.t("TLS 1.3 pairing engine gagal diinisialisasi. Restart Game Nuke lalu coba pairing lagi.", "The TLS 1.3 pairing engine failed to initialize. Restart Game Nuke and try pairing again."),
                recoverable = true,
                tryNextHost = false,
                fatal = true,
            )
        }
        if (lower.contains("connection refused") || lower.contains("econnrefused") ||
            lower.contains("no route") || lower.contains("unreachable") ||
            lower.contains("failed to connect") || lower.contains("connectexception")) {
            return PairingFailure(
                Tx.t("Port pairing sudah berubah atau tidak dapat dijangkau. Tetap buka dialog pairing agar Game Nuke menemukan port terbaru.", "The pairing port changed or is unreachable. Keep the pairing dialog open so Game Nuke can discover the latest port."),
                recoverable = true,
                tryNextHost = true,
            )
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return PairingFailure(
                Tx.t("Pairing timeout. Pastikan Wi-Fi aktif, Wireless Debugging masih ON, dan dialog kode pairing tetap terbuka.", "Pairing timed out. Keep Wi-Fi and Wireless Debugging on, and leave the pairing-code dialog open."),
                recoverable = true,
                tryNextHost = true,
            )
        }
        if (lower.contains("exchanging message") || lower.contains("exchange peer") ||
            lower.contains("pairing cipher") || lower.contains("spake")) {
            return PairingFailure(
                Tx.t("Kode pairing salah/kedaluwarsa atau sesi pairing Android sudah berubah. Buat kode 6 digit baru lalu coba lagi.", "The pairing code is invalid/expired or Android changed the pairing session. Generate a new 6-digit code and try again."),
                recoverable = true,
                tryNextHost = false,
            )
        }
        if (lower.contains("ssl") || lower.contains("handshake")) {
            return PairingFailure(
                Tx.t("Handshake Wireless ADB gagal. Buat ulang kode pairing dan jangan tutup dialog pairing sampai proses selesai.", "Wireless ADB handshake failed. Generate a new pairing code and keep the pairing dialog open until completion."),
                recoverable = true,
                tryNextHost = false,
            )
        }
        return PairingFailure(
            Tx.t("Pairing tidak selesai. Buat kode 6 digit baru, biarkan dialog pairing tetap terbuka, lalu coba lagi.", "Pairing did not complete. Generate a new 6-digit code, keep the pairing dialog open, and try again."),
            recoverable = true,
            tryNextHost = false,
        )
    }

    /**
     * libadb-android first looks for org.conscrypt.OpenSSLProvider. If it cannot find it, it
     * reflects into com.android.org.conscrypt.Conscrypt. That system/private API differs between
     * OEM builds and is exactly where NoSuchMethodException originates. This preflight guarantees
     * our packaged provider has the exporter method before the user spends a pairing code.
     */
    private fun pairingCryptoProblem(): String? {
        return try {
            Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor()
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            conscrypt.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Integer.TYPE,
            )
            null
        } catch (t: Throwable) {
            writeTraceLog("PAIR CRYPTO PREFLIGHT FAIL: ${rootMessage(t)}")
            Tx.t("Komponen TLS Wireless ADB tidak termuat dengan benar. Gunakan build Game Nuke terbaru lalu buka ulang aplikasi.", "Wireless ADB TLS components did not load correctly. Use the latest Game Nuke build and reopen the app.")
        }
    }

    private fun parseTarget(target: String): Pair<String, Int>? {
        val t = target.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val closing = t.indexOf(']')
            if (closing <= 1 || closing + 2 > t.length || t.getOrNull(closing + 1) != ':') return null
            val host = t.substring(1, closing)
            val port = t.substring(closing + 2).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }
        val idx = t.lastIndexOf(':')
        if (idx <= 0 || idx == t.length - 1) return null
        val host = t.substring(0, idx)
        val port = t.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun formatTarget(host: String, port: Int): String {
        val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        return "$formattedHost:$port"
    }

    private fun awaitFreshPairingEndpoint(maxWaitMs: Long): AdbEndpoint? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs.coerceIn(0L, 5_000L))
        while (System.nanoTime() < deadline) {
            latestPairingEndpoint?.takeIf { isEndpointFresh(it, PAIR_ENDPOINT_TTL_MS) }?.let { return it }
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return latestPairingEndpoint?.takeIf { isEndpointFresh(it, PAIR_ENDPOINT_TTL_MS) }
    }

    private fun pairingHostCandidates(advertisedHost: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val clean = advertisedHost.removePrefix("[").removeSuffix("]")
        if (clean.isNotBlank()) candidates += clean
        // Game Nuke pairs with adbd on the same physical phone. Loopback avoids several OEM mDNS
        // address/scope quirks and future LAN restrictions while preserving the discovered port.
        candidates += "127.0.0.1"
        candidates += "::1"
        return candidates.toList()
    }

    private fun isEndpointFresh(endpoint: AdbEndpoint, ttlMs: Long): Boolean =
        System.currentTimeMillis() - endpoint.updatedAt in 0..ttlMs

    private fun saveTarget(target: String) {
        prefs.edit()
            .putString("connected_target", target)
            .putLong("connected_target_time", System.currentTimeMillis())
            .apply()
    }

    private fun getTarget(): String = prefs.safeString("connected_target", "")
    fun setLastPairSuccess() {
        clearAuthorizationRevokedHint()
        prefs.edit().putLong("last_pair_time", System.currentTimeMillis()).apply()
    }

    fun hasPairedBefore(): Boolean = NukeDaemonClient.ping() || prefs.safeLong("last_pair_time", 0L) > 0L

    /**
     * True jika device sudah pernah dipair dan identitas ADB belum dicabut.
     * Berbeda dari hasPairedBefore(): ini juga memeriksa authorization revoke,
     * sehingga UI dapat membedakan "perlu pairing ulang" vs "hanya terputus jaringan".
     */
    fun isPairedAndTrusted(): Boolean =
        !authorizationRevokedHint && prefs.safeLong("last_pair_time", 0L) > 0L

    /**
     * Warm-up kunci RSA di background thread agar buka aplikasi tidak macet.
     * Dipanggil dari Application.onCreate() via thread terpisah.
     */
    fun warmUpKeyMaterial() {
        kotlin.concurrent.thread(name = "Nuke-ADB-KeyWarmup", isDaemon = true) {
            runCatching { AdbConnectionManager.getInstance(mContext) }
                .onFailure { writeTraceLog("KEY WARMUP FAILED: ${rootMessage(it)}") }
        }
    }

    private fun rootMessage(t: Throwable): String {
        val deepest = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return deepest.message?.takeIf { it.isNotBlank() }
            ?: t.message?.takeIf { it.isNotBlank() }
            ?: deepest.javaClass.simpleName
    }

    private fun endpointFrom(service: NsdServiceInfo): AdbEndpoint? {
        @Suppress("DEPRECATION")
        val address = runCatching { service.host }.getOrNull() ?: return null
        if (!isOwnDeviceAddress(address)) {
            writeTraceLog("NSD IGNORE foreign=${address.hostAddress} name=${service.serviceName}")
            return null
        }
        val port = service.port
        if (port !in 1..65535) return null
        return AdbEndpoint(host = address.hostAddress, port = port, serviceName = service.serviceName)
    }

    private fun isOwnDeviceAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { networkInterface ->
                Collections.list(networkInterface.inetAddresses).any { local ->
                    // Compare raw address bytes too: IPv6 link-local addresses may be represented
                    // with different scope-id strings by NSD and NetworkInterface on OEM ROMs.
                    local == address || local.address.contentEquals(address.address)
                }
            }
        }.getOrDefault(false)
    }

    private fun handleResolvedService(service: NsdServiceInfo, isPairing: Boolean) {
        val endpoint = endpointFrom(service) ?: return
        if (isPairing) {
            latestPairingEndpoint = endpoint
            writeTraceLog("NSD PAIR ${endpoint.target()} name=${endpoint.serviceName}")
            val lastPairTime = prefs.safeLong("last_pair_time", 0L)
            if (System.currentTimeMillis() - lastPairTime >= 15_000L && !isConnected()) {
                runCatching {
                    NotificationHelper(mContext).updateNotification(
                        Tx.t("Device Control Siap Dipair", "Device Control Ready to Pair"),
                        Tx.t("Masukkan kode 6 digit sebelum dialog pairing ditutup.", "Enter the 6-digit code before closing the pairing dialog."),
                        true,
                        true,
                        endpoint.target(),
                    )
                }
            }
        } else {
            latestConnectEndpoint = endpoint
            writeTraceLog("NSD CONNECT ${endpoint.target()} name=${endpoint.serviceName}")
            if (!isConnected()) {
                thread(name = "Nuke-ADB-Connect", isDaemon = true) {
                    val result = connect(endpoint.target())
                    if (result.contains("connected", ignoreCase = true) && isConnected()) {
                        stopNetworkScanner()
                        runCatching {
                            NotificationHelper(mContext).updateNotification(
                                "Game Nuke Core Online",
                                "Device Control terhubung dan siap digunakan.",
                                true,
                                false,
                                null,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun enqueueLegacyResolve(
        nsdManager: NsdManager,
        service: NsdServiceInfo,
        isPairing: Boolean,
    ) {
        legacyResolveQueue.offer(LegacyResolveRequest(service, isPairing))
        drainLegacyResolveQueue(nsdManager)
    }

    private fun drainLegacyResolveQueue(nsdManager: NsdManager) {
        if (!legacyResolving.compareAndSet(false, true)) return
        val request = legacyResolveQueue.poll()
        if (request == null) {
            legacyResolving.set(false)
            return
        }

        fun finishAndContinue() {
            legacyResolving.set(false)
            if (legacyResolveQueue.isNotEmpty()) drainLegacyResolveQueue(nsdManager)
        }

        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(request.service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    try {
                        handleResolvedService(resolvedService, request.isPairing)
                    } finally {
                        finishAndContinue()
                    }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    writeTraceLog("NSD LEGACY RESOLVE FAIL code=$errorCode name=${serviceInfo.serviceName}")
                    finishAndContinue()
                }
            })
        } catch (t: Throwable) {
            writeTraceLog("NSD LEGACY RESOLVE EXCEPTION ${rootMessage(t)}")
            finishAndContinue()
        }
    }

    @Synchronized
    private fun acquireMdnsMulticastLock() {
        val existing = mdnsMulticastLock
        if (existing != null && runCatching { existing.isHeld }.getOrDefault(false)) return
        val wifi = mContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching {
            wifi.createMulticastLock("GameNuke:WirelessAdbMdns").apply {
                setReferenceCounted(false)
                acquire()
                mdnsMulticastLock = this
            }
            writeTraceLog("MDNS MULTICAST LOCK acquired")
        }.onFailure { writeTraceLog("MDNS MULTICAST LOCK failed: ${rootMessage(it)}") }
    }

    @Synchronized
    private fun releaseMdnsMulticastLock() {
        val lock = mdnsMulticastLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { writeTraceLog("MDNS MULTICAST UNLOCK failed: ${rootMessage(it)}") }
        mdnsMulticastLock = null
    }

    fun stopNetworkScanner() {
        scannerGeneration.incrementAndGet()
        val nsdManager = mContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager != null) clearNsdState(nsdManager)
        releaseMdnsMulticastLock()
    }

    private fun clearNsdState(nsdManager: NsdManager) {
        pairingListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
        connectListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
        pairingListener = null
        connectListener = null
        legacyResolveQueue.clear()
        // Do not force legacyResolving=false while a framework resolve callback is still in
        // flight. The callback will release the gate and drain any newly queued request.
    }

    fun startNetworkScanner() {
        val generation = scannerGeneration.incrementAndGet()
        thread(name = "Nuke-NSD-Scanner-$generation", isDaemon = true) {
            val nsdManager = mContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@thread
            try {
                clearNsdState(nsdManager)
                acquireMdnsMulticastLock()
                try {
                    Thread.sleep(120L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
                if (generation != scannerGeneration.get()) return@thread

                runCatching {
                    NotificationHelper(mContext).updateNotification(
                        "Mencari Device Control...",
                        "Pastikan Wireless Debugging aktif.",
                        true,
                        false,
                        null,
                    )
                }

                fun newListener(isPairing: Boolean) = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        writeTraceLog("NSD START type=$regType")
                    }

                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (generation != scannerGeneration.get()) return
                        // Use the API-16 resolver path deliberately. It is available on every
                        // Android 11+ device and avoids linking API-34 callback classes into the
                        // base ADB manager. The queue serializes resolves for OEM NSD stacks that
                        // reject concurrent resolveService calls.
                        enqueueLegacyResolve(nsdManager, service, isPairing)
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {
                        if (isPairing && latestPairingEndpoint?.serviceName == service.serviceName) {
                            latestPairingEndpoint = null
                        }
                        if (!isPairing && latestConnectEndpoint?.serviceName == service.serviceName) {
                            latestConnectEndpoint = null
                        }
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        writeTraceLog("NSD STOP type=$serviceType")
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        writeTraceLog("NSD START FAIL type=$serviceType code=$errorCode")
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        writeTraceLog("NSD STOP FAIL type=$serviceType code=$errorCode")
                    }
                }

                val pairDiscovery = newListener(true)
                val connectDiscovery = newListener(false)
                pairingListener = pairDiscovery
                connectListener = connectDiscovery

                runCatching {
                    nsdManager.discoverServices(
                        "_adb-tls-pairing._tcp",
                        NsdManager.PROTOCOL_DNS_SD,
                        pairDiscovery,
                    )
                }.onFailure { writeTraceLog("NSD PAIR DISCOVERY EXCEPTION ${rootMessage(it)}") }

                runCatching {
                    nsdManager.discoverServices(
                        "_adb-tls-connect._tcp",
                        NsdManager.PROTOCOL_DNS_SD,
                        connectDiscovery,
                    )
                }.onFailure { writeTraceLog("NSD CONNECT DISCOVERY EXCEPTION ${rootMessage(it)}") }
            } catch (t: Throwable) {
                writeTraceLog("NSD SCANNER EXCEPTION ${rootMessage(t)}")
                if (generation == scannerGeneration.get() && !isConnected()) releaseMdnsMulticastLock()
            }
        }
    }


}
