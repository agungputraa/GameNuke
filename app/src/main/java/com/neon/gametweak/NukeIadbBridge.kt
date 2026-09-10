package com.neon.gametweak

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.iadb.Iadb
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to iAdb API.
 *
 * iAdb is a lightweight fork of Shizuku-API (Android 11+ only).
 * The persistence model is identical to Shizuku:
 * - The iAdb server process is started once via ADB/Wireless Debugging and stays alive
 *   independently. WiFi can be off; the server lives via the Unix domain socket / binder.
 * - IadbProvider delivers the binder to the app via ContentProvider on startup.
 * - No WiFi or ADB transport required after initial setup.
 *
 * The API is almost identical to Shizuku; the main difference is:
 *  - Class: Iadb instead of Shizuku
 *  - Package: com.iadb instead of rikka.shizuku
 *  - Provider: com.iadb.IadbProvider instead of rikka.shizuku.ShizukuProvider
 */
object NukeIadbBridge {
    private const val TAG = "NukeIadbBridge"
    private const val PERMISSION_CODE = 7731

    @Volatile private var userServiceBinder: IBinder? = null
    @Volatile private var serviceConnection: ServiceConnection? = null
    private val binderAlive = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    // ── iAdb lifecycle listeners ─────────────────────────────────────────────

    private val binderReceivedListener = Iadb.OnBinderReceivedListener {
        Log.i(TAG, "iAdb binder received — server is alive")
        binderAlive.set(true)
        if (checkSelfPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Iadb.OnBinderDeadListener {
        Log.w(TAG, "iAdb binder died — server stopped")
        binderAlive.set(false)
        userServiceBinder = null
    }

    private val permissionResultListener = Iadb.OnRequestPermissionResultListener { code, grantResult ->
        if (code == PERMISSION_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "iAdb permission granted")
            bindUserService()
        } else {
            Log.w(TAG, "iAdb permission denied (code=$code result=$grantResult)")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Register iAdb listeners. Call once from Application.onCreate().
     * Safe to call multiple times — idempotent.
     */
    fun register() {
        runCatching {
            Iadb.addBinderReceivedListener(binderReceivedListener)
            Iadb.addBinderDeadListener(binderDeadListener)
            Iadb.addRequestPermissionResultListener(permissionResultListener)
            // If iAdb is already alive and permitted at register time, bind immediately.
            if (Iadb.pingBinder()) {
                binderAlive.set(true)
                if (checkSelfPermission()) bindUserService()
            }
        }.onFailure {
            Log.w(TAG, "iAdb register failed (iAdb not installed?): ${it.message}")
        }
    }

    /** Unregister all listeners. */
    fun unregister() {
        runCatching { Iadb.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Iadb.removeBinderDeadListener(binderDeadListener) }
        runCatching { Iadb.removeRequestPermissionResultListener(permissionResultListener) }
    }

    /** Returns true if iAdb app is installed on the device. */
    fun isInstalled(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val packages = listOf("com.iadb.helper", "com.smoothie.wirelessDebuggingSwitch", "com.iadb")
        packages.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
        }
    }.getOrDefault(false)

    /** Returns true if iAdb is installed and its server is running. */
    fun isRunning(): Boolean = runCatching { Iadb.pingBinder() }.getOrDefault(false)

    /** Returns true if we have iAdb permission AND (UserService binder is alive OR iAdb server is running). */
    fun isConnected(): Boolean = (userServiceBinder?.pingBinder() == true) || (isRunning() && hasPermission())

    /** Check if permission has been granted. */
    fun hasPermission(): Boolean = checkSelfPermission()

    /** Launch iAdb app if installed, or open Google Play Store. */
    fun launchApp(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val packages = listOf("com.iadb.helper", "com.smoothie.wirelessDebuggingSwitch", "com.iadb")
        var intent: android.content.Intent? = null
        for (pkg in packages) {
            intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) break
        }
        val targetIntent = if (intent != null) {
            intent
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.iadb.helper")).apply {
                setPackage("com.android.vending")
            }
        }
        targetIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(targetIntent)
        } catch (e: Exception) {
            val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.iadb.helper")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
        true
    }.getOrDefault(false)

    /** Trigger binding UserService immediately if permission is granted. */
    fun bindUserServiceNow() {
        if (checkSelfPermission()) {
            bindUserService()
        }
    }

    /** Returns the active IShellService AIDL interface if bound and alive. */
    fun getShellService(): IShellService? {
        val binder = userServiceBinder?.takeIf { it.pingBinder() } ?: return null
        return IShellService.Stub.asInterface(binder)
    }

    /**
     * Execute a shell command via iAdb's privileged UserService or direct iAdb fallback.
     * Returns null if iAdb is not connected or the command fails.
     */
    fun execute(command: String, timeoutMs: Long = 7_500L, maxOutputChars: Int = 131_072): NukeCommandResult? {
        val binder = userServiceBinder?.takeIf { it.pingBinder() }
        if (binder != null) {
            val res = runCatching {
                val service = IShellService.Stub.asInterface(binder)
                val result = service.execCommand(command, timeoutMs) ?: return null
                NukeCommandResult(
                    exitCode = result.exitCode,
                    output = result.output.take(maxOutputChars),
                    timedOut = result.timedOut,
                )
            }.onFailure {
                Log.w(TAG, "iAdb execute failed: ${it.message}")
                userServiceBinder = null
            }.getOrNull()
            if (res != null) return res
        }

        if (isRunning() && hasPermission()) {
            return executeViaIadbProcess(command, timeoutMs, maxOutputChars)
        }
        return null
    }

    private fun executeViaIadbProcess(command: String, timeoutMs: Long, maxOutputChars: Int): NukeCommandResult? {
        return runCatching {
            val method = Iadb::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as java.lang.Process
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (output.length < maxOutputChars) {
                                if (output.isNotEmpty()) output.append('\n')
                                output.append(line.take(maxOutputChars - output.length))
                            }
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                reader.join(250)
                return NukeCommandResult(exitCode = -1, output = output.toString().trimEnd(), timedOut = true)
            }
            reader.join(250)
            NukeCommandResult(exitCode = process.exitValue(), output = output.toString().trimEnd(), timedOut = false)
        }.onFailure {
            Log.w(TAG, "executeViaIadbProcess failed: ${it.message}")
        }.getOrNull()
    }

    /** Manually trigger permission request. */
    fun requestPermission() {
        runCatching {
            Iadb.requestPermission(PERMISSION_CODE)
        }.onFailure { Log.w(TAG, "iAdb requestPermission failed: ${it.message}") }
    }

    /** Status label for display in the UI. */
    fun statusLabel(): String = when {
        !isRunning()           -> "iAdb not running"
        !checkSelfPermission() -> "Permission required"
        isConnected()          -> "Connected via iAdb"
        else                   -> "iAdb: connecting…"
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun checkSelfPermission(): Boolean = runCatching {
        Iadb.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun buildUserServiceArgs(context: Context): Iadb.UserServiceArgs =
        Iadb.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell-iadb")
            .debuggable(false)
            .version(1)

    fun bindUserService() {
        if (!connecting.compareAndSet(false, true)) return
        val context = NukeApplication.instance ?: run {
            connecting.set(false)
            Log.w(TAG, "bindUserService: NukeApplication.instance is null, deferring")
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.i(TAG, "iAdb UserService connected")
                userServiceBinder = binder
                connecting.set(false)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "iAdb UserService disconnected")
                userServiceBinder = null
                connecting.set(false)
            }
        }
        serviceConnection = connection
        runCatching {
            Iadb.bindUserService(buildUserServiceArgs(context), connection)
        }.onFailure {
            Log.w(TAG, "iAdb bindUserService failed: ${it.message}")
            connecting.set(false)
        }
    }
}
