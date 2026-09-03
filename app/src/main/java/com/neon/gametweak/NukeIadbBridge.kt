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

    /** Returns true if we have iAdb permission AND the UserService binder is alive. */
    fun isConnected(): Boolean = userServiceBinder?.pingBinder() == true

    /** Check if permission has been granted. */
    fun hasPermission(): Boolean = checkSelfPermission()

    /** Launch iAdb app if installed. */
    fun launchApp(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val packages = listOf("com.iadb.helper", "com.smoothie.wirelessDebuggingSwitch", "com.iadb")
        var intent: android.content.Intent? = null
        for (pkg in packages) {
            intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) break
        }
        val finalIntent = intent ?: android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://github.com/FileContainer/iAdb-api")
        )
        finalIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(finalIntent)
        true
    }.getOrDefault(false)

    /** Trigger binding UserService immediately if permission is granted. */
    fun bindUserServiceNow() {
        if (checkSelfPermission()) {
            bindUserService()
        }
    }

    /**
     * Execute a shell command via iAdb's privileged UserService.
     * Returns null if iAdb is not connected or the command fails.
     */
    fun execute(command: String, timeoutMs: Long = 7_500L, maxOutputChars: Int = 131_072): NukeCommandResult? {
        val binder = userServiceBinder?.takeIf { it.pingBinder() } ?: return null
        return runCatching {
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
