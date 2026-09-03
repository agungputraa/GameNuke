package com.neon.gametweak

import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class AdbShellProcess(private val stream: AdbStream?) : Process() {

    private val offline: Boolean = stream == null

    private val inStream: InputStream =
        stream?.openInputStream() ?: ByteArrayInputStream(ByteArray(0))

    private val outStream: OutputStream =
        stream?.openOutputStream() ?: NullOutputStream()

    override fun getInputStream(): InputStream = inStream
    override fun getErrorStream(): InputStream = inStream
    override fun getOutputStream(): OutputStream = outStream

    override fun waitFor(): Int {
        if (offline) return -1
        return try {
            val buf = ByteArray(4096)
            while (inStream.read(buf) != -1) { }
            0
        } catch (_: Throwable) {
            -1
        }
    }

    override fun exitValue(): Int = if (offline) -1 else 0

    override fun destroy() {
        runCatching { outStream.close() }
        runCatching { inStream.close() }
        runCatching { stream?.close() }
    }

    fun isOffline(): Boolean = offline

    private class NullOutputStream : OutputStream() {
        override fun write(b: Int) { }
        override fun write(b: ByteArray, off: Int, len: Int) { }
    }
}
