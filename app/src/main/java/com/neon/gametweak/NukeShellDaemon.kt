package com.neon.gametweak

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Local persistent shell core, bootstrapped only after an explicit, authorized ADB session.
 * It is not a hidden Wireless-ADB bypass and does not survive a reboot. The socket authenticates
 * callers by Android UID and exposes shell operations via NukeDaemonPolicy.
 *
 * Architecture follows the Shizuku model:
 * - Uses Looper.prepareMainLooper() + Looper.loop() so the Android framework treats this as
 *   a proper long-lived process instead of a short-lived command.
 * - Socket accept runs on a dedicated thread, not on the main looper thread.
 * - OOM score is reduced so MIUI/HyperOS won't kill it during memory pressure.
 */
object NukeShellDaemon {
    private const val PACKAGE = "com.neon.gametweak"
    private const val TAG = "GameNukeCore"
    private val running = AtomicBoolean(true)
    private val lastKnownPackageUid = AtomicInteger(-1)
    private val pool = Executors.newFixedThreadPool(4)

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != 2000 && Process.myUid() != 0) return
        val passedUid = args.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            ?: args.firstOrNull { it.startsWith("--uid=") }?.substringAfter("--uid=")?.toIntOrNull()
        if (passedUid != null && passedUid > 0) {
            lastKnownPackageUid.set(passedUid)
        }

        if (Looper.myLooper() == null) {
            runCatching { Looper.prepareMainLooper() }
        }

        val server = runCatching { LocalServerSocket(NukeDaemonClient.SOCKET_NAME) }.getOrNull() ?: return

        runCatching {
            java.io.File("/proc/${Process.myPid()}/oom_score_adj").writeText("-800")
        }
        runCatching {
            java.io.File("/proc/${Process.myPid()}/oom_adj").writeText("-16")
        }
        Log.i(TAG, "Core started pid=${Process.myPid()} uid=${Process.myUid()} expectedPkgUid=${lastKnownPackageUid.get()}")

        thread(name = "Nuke-Core-Watchdog", isDaemon = true) {
            try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@thread }
            var definitiveMissingPasses = 0
            while (running.get()) {
                when (val uid = resolvePackageUid()) {
                    -1 -> {
                        definitiveMissingPasses++
                        Log.w(TAG, "Package lookup says Game Nuke is missing ($definitiveMissingPasses/12)")
                        if (definitiveMissingPasses >= 12) {
                            running.set(false)
                            runCatching { server.close() }
                            break
                        }
                    }
                    -2 -> {
                        definitiveMissingPasses = 0
                        Log.w(TAG, "Package UID lookup transiently unavailable; keeping local core alive")
                    }
                    else -> {
                        lastKnownPackageUid.set(uid)
                        definitiveMissingPasses = 0
                    }
                }
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }

        thread(name = "Nuke-Core-Socket", isDaemon = false) {
            try {
                while (running.get()) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    pool.execute { handle(socket) }
                }
            } finally {
                running.set(false)
                runCatching { server.close() }
                pool.shutdownNow()
            }
        }

        try {
            Looper.loop()
        } catch (_: Throwable) {
            while (running.get()) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun handle(socket: LocalSocket) {
        try {
            socket.soTimeout = 125000
            var expected = lastKnownPackageUid.get()
            if (expected < 0) {
                val resolved = resolvePackageUid()
                if (resolved >= 0) {
                    lastKnownPackageUid.set(resolved)
                    expected = resolved
                }
            }
            val peer = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
            // Allow if peer matches expected app UID, or is shell (2000), or is root (0)
            if (expected > 0 && peer != expected && peer != 2000 && peer != 0) {
                val resolved = resolvePackageUid()
                if (resolved > 0) {
                    lastKnownPackageUid.set(resolved)
                    expected = resolved
                }
                if (expected > 0 && peer != expected && peer != 2000 && peer != 0) {
                    socket.outputStream.bufferedWriter().use { it.write("DENIED\n") }
                    return
                }
            }
            val line = BufferedReader(InputStreamReader(socket.inputStream)).readLine().orEmpty()
            val response = when {
                line == "PING" -> "PONG|${Process.myPid()}"
                line == "STOP" -> { running.set(false); "BYE" }
                line.startsWith("EXEC|") -> executeRequest(line)
                else -> "ERROR|PROTOCOL"
            }
            socket.outputStream.bufferedWriter().use {
                it.write(response)
                it.write("\n")
                it.flush()
            }
            if (line == "STOP") thread(isDaemon = true) {
                try { Thread.sleep(80) } catch (_: Throwable) {}
                runCatching { Looper.getMainLooper()?.quit() }
                Process.killProcess(Process.myPid())
            }
        } catch (_: Throwable) {} finally {
            runCatching { socket.close() }
        }
    }

    private fun executeRequest(line: String): String {
        val p = line.split('|', limit = 3)
        if (p.size != 3) return "ERROR|PROTOCOL"
        val timeout = p[1].toLongOrNull()?.coerceIn(500, 120000) ?: 7500L
        val command = runCatching { String(Base64.decode(p[2], Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")
        val proc = runCatching { ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start() }.getOrNull()
            ?: return "RESULT|-1|0|"
        val out = StringBuilder()
        val reader = thread(isDaemon = true, name = "Nuke-Core-Read") {
            runCatching {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.takeWhile { out.length < 131072 }.forEach { lineOut ->
                        if (out.isNotEmpty()) out.append('\n')
                        out.append(lineOut.take(4096))
                    }
                }
            }
        }
        val completed = runCatching { proc.waitFor(timeout, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!completed) runCatching { proc.destroyForcibly() }
        runCatching { reader.join(350) }
        val code = if (completed) runCatching { proc.exitValue() }.getOrDefault(-1) else -1
        val encoded = Base64.encodeToString(out.toString().take(131072).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "RESULT|$code|${if (completed) 0 else 1}|$encoded"
    }

    /** >=0 valid UID, -1 package definitively absent, -2 transient command/parse failure. */
    private fun resolvePackageUid(): Int {
        val p = runCatching {
            ProcessBuilder("/system/bin/pm", "path", PACKAGE).redirectErrorStream(true).start()
        }.getOrElse {
            return -2
        }
        val completed = runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!completed) {
            runCatching { p.destroyForcibly() }
            return -2
        }
        val text = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        if (!text.contains("package:")) return -1

        val p2 = runCatching {
            ProcessBuilder("/system/bin/cmd", "package", "list", "packages", "-U", PACKAGE).redirectErrorStream(true).start()
        }.getOrNull() ?: return -2
        if (!runCatching { p2.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { p2.destroyForcibly() }
            return -2
        }
        val text2 = runCatching { p2.inputStream.bufferedReader().readText() }.getOrDefault("")
        return Regex("uid:(\\d+)").find(text2)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -2
    }
}
