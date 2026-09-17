package com.neon.gametweak

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

object NukeDaemonClient {
    const val SOCKET_NAME = "gamenuke.core.v1"
    const val TCP_PORT = 18294
    private const val PING_CACHE_MS = 600L
    @Volatile private var lastPingAt = 0L
    @Volatile private var lastPing = false
    @Volatile private var authToken: String = ""

    fun init(context: Context) {
        if (authToken.isEmpty()) {
            val sp = context.getSharedPreferences("nuke_daemon_sec", Context.MODE_PRIVATE)
            var tok = sp.getString("token", null)
            if (tok.isNullOrBlank()) {
                tok = java.util.UUID.randomUUID().toString()
                sp.edit().putString("token", tok).apply()
            }
            authToken = tok
        }
    }

    fun getToken(context: Context? = null): String {
        if (authToken.isNotEmpty()) return authToken
        val ctx = context ?: NukeApplication.instance
        if (ctx != null) {
            init(ctx)
            return authToken
        }
        return ""
    }

    fun ping(force: Boolean = false): Boolean {
        // If called from Main UI Thread, NEVER execute blocking socket I/O!
        // A failed socket connect blocks when daemon is not running, causing UI freezes/ANR.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return lastPing
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPingAt < PING_CACHE_MS) return lastPing
        val ok = runCatching { request("PING", 2500) }.getOrNull()?.startsWith("PONG|") == true
        lastPingAt = now
        lastPing = ok
        return ok
    }

    fun execute(command: String, timeoutMs: Long = 7500L, maxOutputChars: Int = 131072): NukeCommandResult? {
        val payload = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val response = runCatching {
            request("EXEC|${timeoutMs.coerceIn(500, 120000)}|$payload", (timeoutMs + 2000).toInt().coerceAtMost(125000))
        }.onFailure {
            lastPing = false
            lastPingAt = SystemClock.elapsedRealtime()
        }.getOrNull() ?: return null

        val parts = response.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != "RESULT") return null
        lastPing = true
        lastPingAt = SystemClock.elapsedRealtime()
        val code = parts[1].toIntOrNull() ?: -1
        val timed = parts[2] == "1"
        val out = runCatching {
            String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("").take(maxOutputChars)
        return NukeCommandResult(code, out, timed)
    }

    fun stop(): Boolean {
        val ok = runCatching { request("STOP", 2000).startsWith("BYE") }.getOrDefault(false)
        if (ok) {
            lastPing = false
            lastPingAt = 0L
        }
        return ok
    }

    fun touchStart(libPath: String? = null): Boolean {
        val payload = if (!libPath.isNullOrBlank()) "TOUCH_START|$libPath" else "TOUCH_START"
        val resp = runCatching { request(payload, 5000) }.getOrNull()
        if (resp?.startsWith("TOUCH_STARTED|") == true) {
            val count = resp.substringAfter("TOUCH_STARTED|").toIntOrNull() ?: -1
            return count >= 0
        }
        return false
    }

    fun touchConfig(
        sx: Float,
        sy: Float,
        area: Int = 1,
        curve: Int = 1,
        smooth: Boolean = true,
        minCutoff: Float = 1.0f,
        beta: Float = 0.007f,
        dragShot: Boolean = false
    ): Boolean {
        val cmd = "TOUCH_CONFIG|$sx|$sy|$area|$curve|$smooth|$minCutoff|$beta|$dragShot"
        val streamResp = requestStream(cmd)
        if (streamResp == "TOUCH_CONFIGURED") return true
        val resp = runCatching { request(cmd, 3000) }.getOrNull()
        return resp == "TOUCH_CONFIGURED"
    }

    fun touchStop(): Boolean {
        val resp = runCatching { request("TOUCH_STOP", 3000) }.getOrNull()
        return resp == "TOUCH_STOPPED"
    }

    fun touchStatus(): Boolean {
        val resp = runCatching { request("TOUCH_STATUS", 2000) }.getOrNull()
        return resp == "TOUCH_STATUS|true"
    }

    // Dedicated persistent socket for sub-millisecond touch macro streams (Gloo Wall spam / Rapid Fire)
    @Volatile private var persistentSocket: Socket? = null
    @Volatile private var persistentReader: BufferedReader? = null
    @Volatile private var persistentWriter: java.io.BufferedWriter? = null
    private val persistentLock = Any()

    private fun getOrCreatePersistentConnection(timeoutMs: Int = 2000): Pair<BufferedReader, java.io.BufferedWriter>? {
        synchronized(persistentLock) {
            val sock = persistentSocket
            val r = persistentReader
            val w = persistentWriter
            if (sock != null && !sock.isClosed && sock.isConnected && r != null && w != null) {
                return Pair(r, w)
            }
            closePersistentConnection()
            return runCatching {
                val newSock = Socket().apply {
                    tcpNoDelay = true
                    soTimeout = 4000
                    connect(InetSocketAddress("127.0.0.1", TCP_PORT), timeoutMs.coerceIn(200, 2000))
                }
                val newR = BufferedReader(InputStreamReader(newSock.getInputStream(), Charsets.UTF_8))
                val newW = newSock.getOutputStream().bufferedWriter(Charsets.UTF_8)
                persistentSocket = newSock
                persistentReader = newR
                persistentWriter = newW
                Pair(newR, newW)
            }.getOrNull()
        }
    }

    fun closePersistentConnection() {
        synchronized(persistentLock) {
            runCatching { persistentWriter?.flush() }
            runCatching { persistentReader?.close() }
            runCatching { persistentSocket?.close() }
            persistentWriter = null
            persistentReader = null
            persistentSocket = null
        }
    }

    private fun requestStream(line: String): String? {
        synchronized(persistentLock) {
            for (attempt in 0..1) {
                val conn = getOrCreatePersistentConnection() ?: return null
                try {
                    val tok = getToken()
                    val payload = if (tok.isNotEmpty()) "TOKEN|$tok|$line" else line
                    conn.second.write(payload)
                    conn.second.write("\n")
                    conn.second.flush()
                    val resp = conn.first.readLine()
                    if (resp == "DENIED") {
                        closePersistentConnection()
                        throw java.io.IOException("Daemon TCP rejected authentication")
                    }
                    if (!resp.isNullOrBlank()) {
                        return resp
                    }
                } catch (_: Exception) {
                    closePersistentConnection()
                    if (attempt == 1) return null
                }
            }
            return null
        }
    }

    fun touchTap(x: Float, y: Float, durationMs: Long = 15L): Boolean {
        val streamResp = requestStream("TOUCH_TAP|$x|$y|$durationMs")
        if (streamResp == "OK") return true
        val resp = runCatching { request("TOUCH_TAP|$x|$y|$durationMs", 1500) }.getOrNull()
        return resp == "OK"
    }

    fun touchHold(x: Float, y: Float, durationMs: Long): Boolean {
        val streamResp = requestStream("TOUCH_HOLD|$x|$y|$durationMs")
        if (streamResp == "OK") return true
        val resp = runCatching { request("TOUCH_HOLD|$x|$y|$durationMs", (durationMs + 1000).toInt()) }.getOrNull()
        return resp == "OK"
    }

    fun touchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 120L): Boolean {
        val streamResp = requestStream("TOUCH_SWIPE|$x1|$y1|$x2|$y2|$durationMs")
        if (streamResp == "OK") return true
        val resp = runCatching { request("TOUCH_SWIPE|$x1|$y1|$x2|$y2|$durationMs", (durationMs + 1000).toInt()) }.getOrNull()
        return resp == "OK"
    }

    fun setMacroPins(pinsConfig: String): Boolean {
        val streamResp = requestStream("TOUCH_SET_PINS|$pinsConfig")
        if (streamResp == "OK") return true
        val resp = runCatching { request("TOUCH_SET_PINS|$pinsConfig", 2500) }.getOrNull()
        return resp == "OK"
    }

    private fun requestTcp(line: String, timeoutMs: Int): String {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs.coerceAtLeast(150)
            socket.connect(InetSocketAddress("127.0.0.1", TCP_PORT), timeoutMs.coerceIn(200, 2000))
            val tok = getToken()
            val payload = if (tok.isNotEmpty()) "TOKEN|$tok|$line" else line
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write(payload)
            writer.write("\n")
            writer.flush()
            val resp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine().orEmpty()
            if (resp == "DENIED") {
                throw java.io.IOException("Daemon TCP rejected authentication")
            }
            return resp
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun requestLocal(line: String, timeoutMs: Int): String {
        val socket = LocalSocket()
        try {
            socket.soTimeout = timeoutMs.coerceAtLeast(150)
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(line)
            writer.write("\n")
            writer.flush()
            return BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).readLine().orEmpty()
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Sends a request to the daemon.
     * Priority 1: High-speed loopback TCP (127.0.0.1:18294) - SELinux-proof on Android 11-15+
     * Priority 2: Abstract UNIX domain socket (for legacy shell / root environments)
     */
    private fun request(line: String, timeoutMs: Int): String {
        val tcpResult = runCatching { requestTcp(line, timeoutMs) }
        if (tcpResult.isSuccess && tcpResult.getOrNull()?.isNotEmpty() == true) {
            return tcpResult.getOrThrow()
        }

        val localResult = runCatching { requestLocal(line, timeoutMs) }
        if (localResult.isSuccess && localResult.getOrNull()?.isNotEmpty() == true) {
            return localResult.getOrThrow()
        }

        throw tcpResult.exceptionOrNull() ?: localResult.exceptionOrNull() ?: java.io.IOException("Daemon unreachable")
    }
}

