package com.neon.gametweak

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader

object NukeDaemonClient {
    const val SOCKET_NAME = "gamenuke.core.v1"
    private const val PING_CACHE_MS = 600L
    @Volatile private var lastPingAt = 0L
    @Volatile private var lastPing = false

    fun ping(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPingAt < PING_CACHE_MS) return lastPing
        val ok = runCatching { request("PING", 1200) }.getOrNull()?.startsWith("PONG|") == true
        lastPingAt = now
        lastPing = ok
        return ok
    }

    fun execute(command: String, timeoutMs: Long = 7500L, maxOutputChars: Int = 131072): NukeCommandResult? {
        val payload = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val response = runCatching {
            request("EXEC|${timeoutMs.coerceIn(500, 120000)}|$payload", (timeoutMs + 1500).toInt().coerceAtMost(122000))
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
        val ok = runCatching { request("STOP", 1000).startsWith("BYE") }.getOrDefault(false)
        if (ok) {
            lastPing = false
            lastPingAt = 0L
        }
        return ok
    }

    /**
     * Sends a request to the abstract UNIX domain socket server.
     * Note: LocalSocket.connect(endpoint, timeout) throws UnsupportedOperationException on Android!
     * We must use the 1-argument connect(endpoint) and set soTimeout on the socket for read timeouts.
     */
    private fun request(line: String, timeoutMs: Int): String {
        val socket = LocalSocket()
        try {
            socket.soTimeout = timeoutMs.coerceAtLeast(100)
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            val writer = socket.outputStream.bufferedWriter()
            writer.write(line)
            writer.write("\n")
            writer.flush()
            return BufferedReader(InputStreamReader(socket.inputStream)).readLine().orEmpty()
        } finally {
            runCatching { socket.close() }
        }
    }
}
