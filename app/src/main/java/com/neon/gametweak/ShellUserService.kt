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

    /** Called by Shizuku/iAdb to destroy this service. MUST call exitProcess. */
    override fun destroy() {
        Log.i(tag, "destroy() called — stopping touch service, reverting pointer speed, and exiting shell process")
        runCatching { frb.axeron.server.touch.NukeTouchService.stop() }
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

    override fun touchStart(libPath: String?): Int {
        Log.i(tag, "touchStart called via privileged Binder with libPath: $libPath")
        return frb.axeron.server.touch.NukeTouchService.start(libPath)
    }

    override fun touchStop() {
        Log.i(tag, "touchStop called via privileged Binder")
        frb.axeron.server.touch.NukeTouchService.stop()
    }

    override fun isTouchRunning(): Boolean {
        return frb.axeron.server.touch.NukeTouchService.isRunning()
    }

    override fun touchConfigure(
        sx: Float,
        sy: Float,
        area: Int,
        curve: Int,
        smooth: Boolean,
        minCutoff: Float,
        beta: Float
    ) {
        frb.axeron.server.touch.NukeTouchService.configure(sx, sy, area, curve, smooth, minCutoff, beta)
    }

    override fun touchSetGrab(grab: Boolean) {
        val touch = frb.axeron.server.touch.TouchListener.INSTANCE
        if (touch.isLoaded) {
            touch.nativeSetGrab(grab)
        }
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
