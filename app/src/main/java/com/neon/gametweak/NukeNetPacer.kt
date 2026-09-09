package com.neon.gametweak

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Only callers using this engine benefit. It cannot route or accelerate other apps. */
object NukeNetPacer {
    enum class BoostMode { PING_BOOST }
    data class Status(val isConnected: Boolean = false, val mode: BoostMode = BoostMode.PING_BOOST,
        val measuredPingMs: Long = -1L)
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    val isRunning get() = state.value.isConnected
    private data class CacheEntry(val addresses: List<InetAddress>, val expires: Long)
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > 64
    }
    private val gate = Mutex()
    private var nextConnect = 0L
    fun startBoost(context: Context, mode: BoostMode = BoostMode.PING_BOOST) { state.value = Status(true, mode) }
    fun stopBoost(context: Context) {
        state.value = Status()
        synchronized(cache) { cache.clear() }
    }
    suspend fun resolve(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && host.length <= 253)
        val now = SystemClock.elapsedRealtime()
        val cached = synchronized(cache) { cache[host]?.takeIf { it.expires > now } }
        cached?.addresses ?: InetAddress.getAllByName(host).toList().also { addresses ->
            if (isRunning) synchronized(cache) { cache[host] = CacheEntry(addresses, SystemClock.elapsedRealtime() + 60_000L) }
        }
    }
    /** Caller owns and must close the returned socket. RTT represents this TCP connect only. */
    suspend fun connect(host: String, port: Int, intervalMs: Long = 25): Socket = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        require(intervalMs in 10..500)
        if (isRunning) gate.withLock {
            delay((nextConnect - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            nextConnect = SystemClock.elapsedRealtime() + intervalMs
        }
        val socket = Socket()
        try {
            val address = resolve(host).first()
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val started = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(address, port), 5_000)
            if (isRunning) state.value = state.value.copy(measuredPingMs = SystemClock.elapsedRealtime() - started)
            socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }
}
