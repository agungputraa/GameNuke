package com.neon.gametweak

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * UserService that runs inside the Shizuku / iAdb privileged process (shell UID = 2000).
 *
 * This class must:
 *  - Extend IShellService.Stub (generated from IShellService.aidl)
 *  - Have a no-arg constructor (used by Shizuku/iAdb to instantiate it)
 *  - Optionally have a Context constructor (used by Shizuku v13+)
 *
 * IMPORTANT: This class runs in a DIFFERENT PROCESS with ADB-shell UID.
 * Android context APIs like registerReceiver, getContentResolver etc. do NOT work here.
 */
class ShellUserService() : IShellService.Stub() {

    /** Constructor with Context — used by Shizuku v13+. Required signature. */
    @Suppress("unused")
    constructor(context: Context) : this()

    private val tag = "NukeShellUserService"

    init {
        cleanupZombieInstances()
    }

    private fun cleanupZombieInstances() {
        runCatching {
            val myPid = android.os.Process.myPid()
            val p = ProcessBuilder("/system/bin/sh", "-c", "pgrep -f 'com.neon.gametweak:shell-iadb' || ps -ef | grep 'com.neon.gametweak:shell-iadb' | awk '{print \$2}'")
                .redirectErrorStream(true)
                .start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor(1000, TimeUnit.MILLISECONDS)
            for (line in lines) {
                val pid = line.trim().toIntOrNull() ?: continue
                if (pid > 0 && pid != myPid) {
                    Log.i(tag, "Terminating zombie shell-iadb PID $pid (myPid=$myPid)")
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-9", pid.toString())).waitFor(500, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }.onFailure {
            Log.w(tag, "cleanupZombieInstances failed: ${it.message}")
        }
    }

    /** Called by Shizuku/iAdb to destroy this service. MUST call exitProcess. */
    override fun destroy() {
        Log.i(tag, "destroy() called — stopping touch service, reverting pointer speed, and exiting shell process")
        runCatching { nuke.wandev.touch.NukeTouchService.stop() }
        runCatching {
            Runtime.getRuntime().exec("settings put system pointer_speed 0").waitFor()
        }
        exitProcess(0)
    }

    /** Called by the app when it is done with the service. */
    override fun exit() {
        destroy()
    }

    /** Returns true — if this method is reachable, the service is alive. */
    override fun ping(): Boolean = true

    override fun deployTouchLibrary(libBytes: ByteArray?): Boolean {
        if (libBytes == null || libBytes.isEmpty()) return false
        return runCatching {
            val dest = java.io.File("/data/local/tmp/libwandev.so")
            val tmp = java.io.File("/data/local/tmp/libwandev.so.tmp")
            if (tmp.exists()) tmp.delete()
            tmp.writeBytes(libBytes)
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", "/data/local/tmp/libwandev.so")).waitFor()
            val success = dest.exists() && dest.length() >= 20_000L
            Log.i(tag, "deployTouchLibrary wrote ${dest.length()} bytes, success=$success")
            success
        }.onFailure {
            Log.e(tag, "deployTouchLibrary error: ${it.message}", it)
        }.getOrDefault(false)
    }

    override fun touchStart(libPath: String?): Int {
        val destFile = java.io.File("/data/local/tmp/libwandev.so")
        // If /data/local/tmp/libwandev.so is missing but caller provided a readable path, copy it into /data/local/tmp
        if ((!destFile.exists() || destFile.length() < 20_000L) && !libPath.isNullOrBlank()) {
            val srcFile = java.io.File(libPath)
            if (srcFile.exists() && srcFile.canRead() && srcFile.length() >= 20_000L) {
                runCatching {
                    srcFile.copyTo(destFile, overwrite = true)
                    destFile.setReadable(true, false)
                    destFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", "/data/local/tmp/libwandev.so")).waitFor()
                    Log.i(tag, "Copied libwandev.so from $libPath to /data/local/tmp/ (${destFile.length()} bytes)")
                }
            }
        }

        // Self-extraction 1: Shell toybox unzip directly from installed base.apk
        if (!destFile.exists() || destFile.length() < 20_000L) {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "APK=\$(pm path com.neon.gametweak 2>/dev/null | head -n1 | cut -d: -f2 | tr -d '\\r'); if [ -n \"\$APK\" ]; then unzip -p \"\$APK\" assets/libwandev.so > /data/local/tmp/libwandev.so.tmp 2>/dev/null || unzip -p \"\$APK\" lib/arm64-v8a/libwandev.so > /data/local/tmp/libwandev.so.tmp 2>/dev/null; [ -s /data/local/tmp/libwandev.so.tmp ] && mv /data/local/tmp/libwandev.so.tmp /data/local/tmp/libwandev.so && chmod 755 /data/local/tmp/libwandev.so; fi"))
                p.waitFor()
            }
        }

        // Self-extraction 2: Pure Java from running CLASSPATH APK if destFile is still missing
        if (!destFile.exists() || destFile.length() < 20_000L) {
            val cp = System.getProperty("java.class.path") ?: ""
            for (part in cp.split(java.io.File.pathSeparator)) {
                val f = java.io.File(part)
                if (f.exists() && f.name.endsWith(".apk", ignoreCase = true)) {
                    runCatching {
                        java.util.zip.ZipFile(f).use { zip ->
                            val entry = zip.getEntry("lib/arm64-v8a/libwandev.so")
                                ?: zip.getEntry("assets/libwandev.so")
                                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("libwandev.so") }
                            if (entry != null) {
                                zip.getInputStream(entry).use { input ->
                                    val tmp = java.io.File("/data/local/tmp/libwandev.so.tmp")
                                    tmp.outputStream().use { out -> input.copyTo(out) }
                                    if (destFile.exists()) destFile.delete()
                                    tmp.renameTo(destFile)
                                    destFile.setReadable(true, false)
                                    destFile.setExecutable(true, false)
                                    Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", "/data/local/tmp/libwandev.so")).waitFor()
                                    Log.i(tag, "Self-extracted libwandev.so from classpath: ${destFile.length()} bytes")
                                }
                            }
                        }
                    }
                }
            }
        }

        val targetLib = if (destFile.exists() && destFile.length() >= 20_000L) {
            "/data/local/tmp/libwandev.so"
        } else if (!libPath.isNullOrBlank()) {
            libPath
        } else {
            "/data/local/tmp/libwandev.so"
        }

        Log.i(tag, "touchStart called via privileged Binder with targetLib: $targetLib")
        if (nuke.wandev.touch.NukeTouchService.isRunning()) {
            nuke.wandev.touch.NukeTouchService.stop()
        }
        return nuke.wandev.touch.NukeTouchService.start(targetLib)
    }

    override fun touchStop() {
        Log.i(tag, "touchStop called via privileged Binder")
        nuke.wandev.touch.NukeTouchService.stop()
    }

    override fun isTouchRunning(): Boolean {
        return nuke.wandev.touch.NukeTouchService.isRunning()
    }

    override fun touchConfigure(
        sx: Float,
        sy: Float,
        area: Int,
        curve: Int,
        smooth: Boolean,
        minCutoff: Float,
        beta: Float,
        dragShot: Boolean
    ) {
        nuke.wandev.touch.NukeTouchService.configure(sx, sy, area, curve, smooth, minCutoff, beta, dragShot)
    }

    override fun touchSetGrab(grab: Boolean) {
        val touch = nuke.wandev.touch.TouchListener.INSTANCE
        if (touch.isLoaded) {
            touch.nativeSetGrab(grab)
        }
    }

    override fun injectMotionEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return nuke.wandev.touch.NukeTouchService.injectDirect(action, pointerId, x, y, pressure)
    }

    override fun injectTap(x: Float, y: Float, durationMs: Long): Boolean {
        return nuke.wandev.touch.NukeTouchService.injectTap(x, y, durationMs)
    }

    override fun injectHold(x: Float, y: Float, durationMs: Long): Boolean {
        return nuke.wandev.touch.NukeTouchService.injectHold(x, y, durationMs)
    }

    override fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        return nuke.wandev.touch.NukeTouchService.injectSwipe(x1, y1, x2, y2, durationMs)
    }

    override fun setMacroPins(pinsConfig: String) {
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
        // Ensure the hardware touch router is running before arming the pins.
        // If NukeTouchService died or was never started, auto-start it now so
        // the kernel evdev grab is active and pins fire correctly.
        if (list.isNotEmpty() && !nuke.wandev.touch.NukeTouchService.isRunning()) {
            Log.i(tag, "setMacroPins: NukeTouchService not running — auto-starting before arming ${list.size} pin(s)")
            val libPath = "/data/local/tmp/libwandev.so"
            runCatching { nuke.wandev.touch.NukeTouchService.start(libPath) }
                .onFailure { Log.w(tag, "setMacroPins: auto-start failed: ${it.message}") }
        }
        nuke.wandev.touch.NukeTouchService.setMacroPins(list)
    }

    /**
     * Executes a shell command in the ADB-shell UID process.
     * This uses an alternate path around all normal Android sandbox restrictions that apply to a regular app UID,
     * since shell (UID 2000) has the same privileges as ADB commands.
     */
    override fun execCommand(command: String?, timeoutMs: Long): ShellResult {
        if (command.isNullOrBlank()) {
            return ShellResult(exitCode = -1, output = "Empty command", timedOut = false)
        }
        return try {
            val safeTimeout = timeoutMs.coerceIn(500L, 120_000L)
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val outputBytes = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            if (outputBytes.length < 131072) {
                                outputBytes.append(buf, 0, minOf(n, 131072 - outputBytes.length))
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            readerThread.isDaemon = true
            readerThread.start()

            val finished = process.waitFor(safeTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                readerThread.join(350)
                return ShellResult(exitCode = -1, output = outputBytes.toString().trimEnd(), timedOut = true)
            }
            readerThread.join(350)
            ShellResult(
                exitCode = process.exitValue(),
                output = outputBytes.toString().trimEnd(),
                timedOut = false,
            )
        } catch (t: Throwable) {
            Log.w(tag, "execCommand failed: ${t.message}")
            ShellResult(exitCode = -1, output = t.message.orEmpty(), timedOut = false)
        }
    }
}
