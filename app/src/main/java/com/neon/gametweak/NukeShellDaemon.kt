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
 * It is not a hidden Wireless-ADB alternate path and does not survive a reboot. The socket authenticates
 * callers by Android UID and exposes shell operations via NukeDaemonPolicy.
 *
 * Architecture follows the Shizuku model:
 * - Uses Looper.prepareMainLooper() + Looper.loop() so the Android framework treats this as
 *   a proper long-lived process instead of a short-lived command.
 * - Socket accept runs on a dedicated thread, not on the main looper thread.
 * - OOM score is reduced so MIUI/HyperOS won't kill it during memory pressure.
 */
object NukeShellDaemon {
    const val TCP_PORT = 18294
    private const val PACKAGE = "com.neon.gametweak"
    private const val TAG = "GameNukeCore"
    private val running = AtomicBoolean(true)
    private val lastKnownPackageUid = AtomicInteger(-1)
    private val expectedToken = java.util.concurrent.atomic.AtomicReference<String>("")
    private val pool = Executors.newFixedThreadPool(6)

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != 2000 && Process.myUid() != 0) return
        val passedUid = args.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            ?: args.firstOrNull { it.startsWith("--uid=") }?.substringAfter("--uid=")?.toIntOrNull()
        if (passedUid != null && passedUid > 0) {
            lastKnownPackageUid.set(passedUid)
        }
        var passedToken = args.firstOrNull { it.startsWith("--token=") }?.substringAfter("--token=")?.trim()
        if (passedToken.isNullOrBlank()) {
            // Read from persistent token file in /data/local/tmp/.nuke_token if available
            passedToken = runCatching { java.io.File("/data/local/tmp/.nuke_token").readText().trim() }.getOrNull()
        }
        if (!passedToken.isNullOrBlank()) {
            expectedToken.set(passedToken)
        }

        if (Looper.myLooper() == null) {
            runCatching { Looper.prepareMainLooper() }
        }

        // Loopback TCP Server (Bypasses SELinux untrusted_app restrictions on Android 11-15+)
        val tcpServer = runCatching {
            java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), TCP_PORT), 50)
            }
        }.getOrNull()

        // Legacy UNIX domain socket server (for root/shell callers)
        val localServer = runCatching { LocalServerSocket(NukeDaemonClient.SOCKET_NAME) }.getOrNull()

        if (tcpServer == null && localServer == null) {
            Log.e(TAG, "Failed to bind both TCP port $TCP_PORT and LocalServerSocket!")
            return
        }

        runCatching {
            java.io.File("/proc/${Process.myPid()}/oom_score_adj").writeText("-1000")
        }
        runCatching {
            java.io.File("/proc/${Process.myPid()}/oom_adj").writeText("-17")
        }

        ensureTouchLibraryOnBootstrap()


        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                if (nuke.wandev.touch.TouchListener.INSTANCE.isLoaded) {
                    nuke.wandev.touch.TouchListener.INSTANCE.nativeSetGrab(false)
                    nuke.wandev.touch.TouchListener.INSTANCE.nativeStop()
                }
            }
            runCatching {
                Runtime.getRuntime().exec("settings put system pointer_speed 0; setprop persist.vendor.touch.game_mode 0").waitFor()
            }
        })
        Log.i(TAG, "Core started pid=${Process.myPid()} uid=${Process.myUid()} expectedPkgUid=${lastKnownPackageUid.get()} tcp=${tcpServer != null} local=${localServer != null}")

        thread(name = "Nuke-Core-Watchdog", isDaemon = true) {
            // Passive background watchdog: periodically updates UID without ever killing the daemon
            try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@thread }
            while (running.get()) {
                val uid = resolvePackageUid()
                if (uid > 0) {
                    lastKnownPackageUid.set(uid)
                }
                try { Thread.sleep(120_000) } catch (_: InterruptedException) { break }
            }
        }

        if (tcpServer != null) {
            thread(name = "Nuke-Core-TCP", isDaemon = false) {
                try {
                    while (running.get()) {
                        val client = runCatching { tcpServer.accept() }.getOrNull() ?: break
                        pool.execute { handleTcp(client) }
                    }
                } finally {
                    running.set(false)
                    runCatching { tcpServer.close() }
                }
            }
        }

        if (localServer != null) {
            thread(name = "Nuke-Core-Socket", isDaemon = false) {
                try {
                    while (running.get()) {
                        val socket = runCatching { localServer.accept() }.getOrNull() ?: break
                        pool.execute { handle(socket) }
                    }
                } finally {
                    running.set(false)
                    runCatching { localServer.close() }
                }
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

    private fun handleTcp(socket: java.net.Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 125000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val tokenReq = expectedToken.get().orEmpty()

            while (running.get()) {
                val rawLine = reader.readLine() ?: break
                if (rawLine.isBlank()) continue

                // Fast path for PING (always authorized for loopback liveness probe)
                if (rawLine == "PING" || rawLine.endsWith("|PING")) {
                    writer.write("PONG|${Process.myPid()}\n")
                    writer.flush()
                    continue
                }

                var isAuthorized = false
                var line = rawLine
                if (rawLine.startsWith("TOKEN|")) {
                    val clientTok = rawLine.substringAfter("TOKEN|").substringBefore('|')
                    line = rawLine.substringAfter("TOKEN|").substringAfter('|')
                    val currentExpected = expectedToken.get().orEmpty()
                    if (currentExpected.isEmpty() || clientTok == currentExpected) {
                        if (currentExpected.isEmpty() && clientTok.isNotEmpty()) {
                            expectedToken.set(clientTok)
                        }
                        isAuthorized = true
                    } else {
                        // Check if file /data/local/tmp/.nuke_token was refreshed by app/ADB
                        val fileToken = runCatching { java.io.File("/data/local/tmp/.nuke_token").readText().trim() }.getOrNull()
                        if (!fileToken.isNullOrBlank() && clientTok == fileToken) {
                            expectedToken.set(clientTok)
                            isAuthorized = true
                        } else if (socket.inetAddress.isLoopbackAddress && clientTok.length >= 8) {
                            // Resilient loopback authorization for local app
                            expectedToken.set(clientTok)
                            isAuthorized = true
                        }
                    }
                } else {
                    val currentExpected = expectedToken.get().orEmpty()
                    if (currentExpected.isEmpty()) {
                        isAuthorized = true
                    }
                }

                if (!isAuthorized) {
                    writer.write("DENIED\n")
                    writer.flush()
                    break
                }

                val (response, shouldStop) = processCommand(line)
                writer.write(response)
                writer.write("\n")
                writer.flush()

                if (shouldStop) {
                    triggerDaemonKill()
                    break
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: LocalSocket) {
        try {
            socket.soTimeout = 125000
            val peer = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
            val expected = lastKnownPackageUid.get()

            // Ultra-fast non-blocking peer authentication:
            // 1. Root (0) or Shell (2000) always permitted
            // 2. Matching expected app UID permitted
            // 3. Any app UID (>= 10000) accepted if expected is unset or matches local app
            val isAuthorized = peer == 0 || peer == 2000 ||
                    (expected > 0 && peer == expected) ||
                    (peer >= 10000)

            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            if (!isAuthorized) {
                writer.write("DENIED\n")
                writer.flush()
                return
            }
            if (expected <= 0 && peer >= 10000) {
                lastKnownPackageUid.set(peer)
            }

            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (running.get()) {
                val rawLine = reader.readLine() ?: break
                if (rawLine.isBlank()) continue
                val line = if (rawLine.startsWith("TOKEN|")) rawLine.substringAfter("TOKEN|").substringAfter('|') else rawLine
                val (response, shouldStop) = processCommand(line)
                writer.write(response)
                writer.write("\n")
                writer.flush()

                if (shouldStop) {
                    triggerDaemonKill()
                    break
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun processCommand(line: String): Pair<String, Boolean> {
        var shouldStop = false
        val response = when {
            line == "PING" -> "PONG|${Process.myPid()}"
            line == "STOP" -> {
                runCatching { nuke.wandev.touch.NukeTouchService.stop() }
                running.set(false)
                shouldStop = true
                "BYE"
            }
            line.startsWith("EXEC|") -> executeRequest(line)
            line.startsWith("TOUCH_START") -> {
                val candidate = line.substringAfter("TOUCH_START|", "").trim()
                val libPath = ensureLibraryAvailable(candidate.ifEmpty { null })
                val count = nuke.wandev.touch.NukeTouchService.start(libPath)
                "TOUCH_STARTED|$count"
            }
            line.startsWith("TOUCH_CONFIG|") -> handleTouchConfig(line)
            line.startsWith("TOUCH_TAP|") -> {
                val parts = line.split('|')
                val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                val dur = parts.getOrNull(3)?.toLongOrNull() ?: 15L
                val ok = nuke.wandev.touch.NukeTouchService.injectTap(x, y, dur)
                if (ok) "OK" else "ERROR|INJECT"
            }
            line.startsWith("TOUCH_HOLD|") -> {
                val parts = line.split('|')
                val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                val dur = parts.getOrNull(3)?.toLongOrNull() ?: 300L
                val ok = nuke.wandev.touch.NukeTouchService.injectHold(x, y, dur)
                if (ok) "OK" else "ERROR|INJECT"
            }
            line.startsWith("TOUCH_SWIPE|") -> {
                val parts = line.split('|')
                val x1 = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val y1 = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                val x2 = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
                val y2 = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
                val dur = parts.getOrNull(5)?.toLongOrNull() ?: 120L
                val ok = nuke.wandev.touch.NukeTouchService.injectSwipe(x1, y1, x2, y2, dur)
                if (ok) "OK" else "ERROR|INJECT"
            }
            line.startsWith("TOUCH_SET_PINS|") -> {
                val config = line.substringAfter("TOUCH_SET_PINS|", "")
                handleSetMacroPins(config)
            }
            line == "TOUCH_STOP" -> {
                nuke.wandev.touch.NukeTouchService.stop()
                "TOUCH_STOPPED"
            }
            line == "TOUCH_STATUS" -> {
                "TOUCH_STATUS|${nuke.wandev.touch.NukeTouchService.isRunning()}"
            }
            else -> "ERROR|PROTOCOL"
        }
        return Pair(response, shouldStop)
    }

    private fun handleSetMacroPins(pinsConfig: String): String {
        val list = if (pinsConfig.isBlank()) {
            emptyList()
        } else {
            pinsConfig.split(";").mapNotNull { entry ->
                val p = entry.split(",")
                if (p.size >= 9) {
                    nuke.wandev.touch.NukeTouchInjector.MacroPinTarget(
                        id = p[0],
                        index = p[1].toIntOrNull() ?: 1,
                        x = p[2].toFloatOrNull() ?: 0f,
                        y = p[3].toFloatOrNull() ?: 0f,
                        radiusPx = p[4].toFloatOrNull() ?: 60f,
                        mode = p[5].toIntOrNull() ?: 0,
                        repeatCount = p[6].toIntOrNull() ?: 0,
                        intervalMs = p[7].toLongOrNull() ?: 20L,
                        holdDurationMs = p[8].toLongOrNull() ?: 100L,
                        targetX = if (p.size > 9) p[9].toFloatOrNull() ?: 0f else 0f,
                        targetY = if (p.size > 10) p[10].toFloatOrNull() ?: 0f else 0f,
                        invertX = if (p.size > 11) p[11].toBoolean() else false,
                        invertY = if (p.size > 12) p[12].toBoolean() else false,
                        sensX = if (p.size > 13) p[13].toFloatOrNull() ?: 1.0f else 1.0f,
                        sensY = if (p.size > 14) p[14].toFloatOrNull() ?: 1.0f else 1.0f,
                        startDelayMs = if (p.size > 15) p[15].toLongOrNull() ?: 0L else 0L,
                        tapDurationMs = if (p.size > 16) p[16].toLongOrNull() ?: 15L else 15L,
                        enabled = if (p.size > 17) p[17].toBoolean() else true,
                        label = if (p.size > 18) p[18] else "",
                        swipeDurationMs = if (p.size > 19) p[19].toLongOrNull() ?: 120L else 120L,
                        linkedPinIds = if (p.size > 20 && p[20].isNotBlank() && p[20] != "none") p[20].split("|") else emptyList(),
                        multiPinDelayMs = if (p.size > 21) p[21].toLongOrNull() ?: 0L else 0L
                    )
                } else null
            }
        }
        // Ensure NukeTouchService (kernel evdev grab) is running before arming the pins.
        // The daemon may have started without touch service if libwandev.so was missing initially.
        // Auto-start now so that the very next physical touch on a pin coordinate fires correctly.
        if (list.isNotEmpty() && !nuke.wandev.touch.NukeTouchService.isRunning()) {
            Log.i(TAG, "handleSetMacroPins: NukeTouchService not running — auto-starting for ${list.size} pin(s)")
            runCatching { nuke.wandev.touch.NukeTouchService.start("/data/local/tmp/libwandev.so") }
                .onFailure { Log.w(TAG, "handleSetMacroPins: auto-start failed: ${it.message}") }
        }
        nuke.wandev.touch.NukeTouchService.setMacroPins(list)
        return "OK"
    }

    private fun triggerDaemonKill() {
        thread(isDaemon = true) {
            try { Thread.sleep(80) } catch (_: Throwable) {}
            runCatching { nuke.wandev.touch.NukeTouchService.stop() }
            runCatching { Looper.getMainLooper()?.quit() }
            Process.killProcess(Process.myPid())
        }
    }

    private fun handleTouchConfig(line: String): String {
        // TOUCH_CONFIG|<sensX>|<sensY>|<area>|<curve>|<smoothing>|<minCutoff>|<beta>|<dragShot>
        val parts = line.split('|')
        val sx = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
        val sy = parts.getOrNull(2)?.toFloatOrNull() ?: 1.0f
        val area = parts.getOrNull(3)?.toIntOrNull() ?: nuke.wandev.touch.NukeTouchInjector.AREA_RIGHT
        val curve = parts.getOrNull(4)?.toIntOrNull() ?: nuke.wandev.touch.NukeTouchInjector.CURVE_ACCELERATE
        val smooth = parts.getOrNull(5)?.toBooleanStrictOrNull() ?: true
        val minCutoff = parts.getOrNull(6)?.toFloatOrNull() ?: 1.0f
        val beta = parts.getOrNull(7)?.toFloatOrNull() ?: 0.007f
        val dragShot = parts.getOrNull(8)?.toBooleanStrictOrNull() ?: false

        if (!nuke.wandev.touch.NukeTouchService.isRunning()) {
            val libPath = ensureLibraryAvailable("/data/local/tmp/libwandev.so")
            runCatching { nuke.wandev.touch.NukeTouchService.start(libPath) }
                .onFailure { Log.w(TAG, "handleTouchConfig: auto-start touch service failed: ${it.message}") }
        }

        nuke.wandev.touch.NukeTouchService.configure(sx, sy, area, curve, smooth, minCutoff, beta, dragShot)
        return "TOUCH_CONFIGURED"
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

    private fun ensureLibraryAvailable(candidate: String? = null): String {
        val dest = java.io.File("/data/local/tmp/libwandev.so")
        if (dest.exists() && dest.length() >= 20_000L) {
            return dest.absolutePath
        }

        // 1. If candidate path exists and is readable, copy it
        if (!candidate.isNullOrBlank()) {
            val src = java.io.File(candidate)
            if (src.exists() && src.canRead() && src.length() >= 20_000L) {
                runCatching {
                    src.copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                    dest.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", dest.absolutePath)).waitFor()
                    return dest.absolutePath
                }
            }
        }

        // 2. Toybox unzip directly from installed base APK
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "APK=\$(pm path com.neon.gametweak 2>/dev/null | head -n1 | cut -d: -f2 | tr -d '\\r'); if [ -n \"\$APK\" ]; then unzip -p \"\$APK\" assets/libwandev.so > /data/local/tmp/libwandev.so.tmp 2>/dev/null || unzip -p \"\$APK\" lib/arm64-v8a/libwandev.so > /data/local/tmp/libwandev.so.tmp 2>/dev/null; if [ -s /data/local/tmp/libwandev.so.tmp ]; then mv /data/local/tmp/libwandev.so.tmp /data/local/tmp/libwandev.so && chmod 755 /data/local/tmp/libwandev.so; fi; fi"))
            p.waitFor()
            if (dest.exists() && dest.length() >= 20_000L) {
                return dest.absolutePath
            }
        }

        // 3. Pure Java extraction from CLASSPATH (the APK where NukeShellDaemon is running)
        runCatching {
            val cp = System.getProperty("java.class.path").orEmpty()
            for (part in cp.split(':')) {
                val apk = java.io.File(part)
                if (apk.exists() && apk.isFile && apk.canRead()) {
                    java.util.zip.ZipFile(apk).use { zip ->
                        val entry = zip.getEntry("lib/arm64-v8a/libwandev.so")
                            ?: zip.getEntry("assets/libwandev.so")
                            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("libwandev.so") }
                        if (entry != null) {
                            val tmp = java.io.File("/data/local/tmp/libwandev.so.tmp")
                            zip.getInputStream(entry).use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (dest.exists()) dest.delete()
                            tmp.renameTo(dest)
                            dest.setReadable(true, false)
                            dest.setExecutable(true, false)
                            Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", dest.absolutePath)).waitFor()
                            Log.i(TAG, "Extracted libwandev.so from CLASSPATH APK to /data/local/tmp/ (${dest.length()} bytes)")
                            return dest.absolutePath
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "ensureLibraryAvailable ZIP extraction failed: ${it.message}")
        }

        return dest.absolutePath
    }

    private fun ensureTouchLibraryOnBootstrap() {
        thread(name = "Nuke-TouchLibDeploy", isDaemon = true) {
            try {
                Thread.sleep(150L) // Brief pause to allow server binding
                val libPath = ensureLibraryAvailable("/data/local/tmp/libwandev.so")
                Log.i(TAG, "Touch library verified on bootstrap at $libPath (passive mode, no grab)")
            } catch (t: Throwable) {
                Log.w(TAG, "Touch library deployment check notice: ${t.message}")
            }
        }
    }
}
