package com.neon.gametweak

import java.io.File
import kotlin.math.roundToInt

/** Lightweight app-process fallback for whole-device CPU when /proc/stat is readable to the app. */
object NukeLocalCpuSampler {
    private data class Sample(val total: Long, val idle: Long)
    @Volatile private var previous: Sample? = null

    @Synchronized
    fun readPercent(): Int? = runCatching {
        val line = File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } } ?: return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (values.size < 4) return null
        val current = Sample(values.sum(), values[3] + values.getOrElse(4) { 0L })
        val old = previous
        previous = current
        if (old == null) return null
        val dt = current.total - old.total
        val di = current.idle - old.idle
        if (dt <= 0 || di < 0) return null
        (((dt - di).toDouble() / dt.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }.getOrNull()
}
