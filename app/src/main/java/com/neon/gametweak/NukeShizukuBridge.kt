package com.neon.gametweak

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to Shizuku API.
 *
 * Shizuku persistence model (same as Shizuku itself):
 * - The binder lives in the Shizuku server process, not our process.
 * - The Shizuku server is started once via ADB/Wireless Debugging and then stays alive
 *   independently, even when WiFi is off or the app is killed.
 * - On next app launch, Shizuku delivers the binder via ShizukuProvider automatically
 *   (using ContentProvider). No manual reconnect needed.
 * - This is why Shizuku "just works" even after the app is cleared from recents.
 *
 * Thread safety: All callbacks are dispatched on the caller's thread (usually the main thread
 * via ShizukuProvider). We marshal heavier work to a background thread.
 */
object NukeShizukuBridge {
    private const val TAG = "NukeShizukuBridge"
    private const val PERMISSION_CODE = 7730

    @Volatile private var userServiceBinder: IBinder? = null
    @Volatile private var serviceConnection: ServiceConnection? = null
    private val binderAlive = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    // ── Shizuku lifecycle listeners ──────────────────────────────────────────

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — server is alive")
        binderAlive.set(true)
        if (checkSelfPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died — server stopped")
        binderAlive.set(false)
        userServiceBinder = null
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == PERMISSION_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted")
            bindUserService()
        } else {
            Log.w(TAG, "Shizuku permission denied (code=$code result=$grantResult)")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Register Shizuku listeners. Call once from Application.onCreate() or Activity.onCreate().
     * Safe to call multiple times — idempotent.
     */
    fun register() {
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            // If Shizuku is already alive and permitted at register time, bind immediately.
            if (Shizuku.pingBinder()) {
                binderAlive.set(true)
                if (checkSelfPermission()) bindUserService()
            }
        }.onFailure {
            Log.w(TAG, "Shizuku register failed (Shizuku not installed?): ${it.message}")
        }
    }

    /**
     * Unregister all listeners. Call from Application when shutting down (optional — listeners
     * are lightweight and the process will be killed anyway).
     */
    fun unregister() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
    }

    /** Returns true if Shizuku app is installed on the device. */
    fun isInstalled(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        pm.getPackageInfo("moe.shizuku.privileged.api", 0) != null
    }.getOrDefault(false)

    /** Returns true if Shizuku is installed and its server is running. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Returns true if we have Shizuku permission AND the UserService binder is alive. */
    fun isConnected(): Boolean = userServiceBinder?.pingBinder() == true

    /** Check if permission has been granted. */
    fun hasPermission(): Boolean = checkSelfPermission()

    /** Launch Shizuku app if installed. */
    fun launchApp(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app"))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Trigger binding UserService immediately if permission is granted. */
    fun bindUserServiceNow() {
        if (checkSelfPermission()) {
            bindUserService()
        }
    }

    /**
     * Execute a shell command via Shizuku's privileged UserService.
     * Returns null if Shizuku is not connected or the command fails.
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
            Log.w(TAG, "Shizuku execute failed: ${it.message}")
            userServiceBinder = null
        }.getOrNull()
    }

    /** Manually trigger permission request (e.g. from a UI button). */
    fun requestPermission() {
        runCatching {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 is not supported")
                return
            }
            Shizuku.requestPermission(PERMISSION_CODE)
        }.onFailure { Log.w(TAG, "Shizuku requestPermission failed: ${it.message}") }
    }

    /** Status label for display in the UI. */
    fun statusLabel(): String = when {
        !isRunning()      -> "Shizuku not running"
        !checkSelfPermission() -> "Permission required"
        isConnected()     -> "Connected via Shizuku"
        else              -> "Shizuku: connecting…"
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun checkSelfPermission(): Boolean = runCatching {
        !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun buildUserServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)               // Not a daemon — we manage lifecycle ourselves
            .processNameSuffix("shell")  // Nice name: com.neon.gametweak:shell
            .debuggable(false)
            .version(1)

    /**
     * Bind the ShellUserService inside the Shizuku process.
     * Idempotent — will not bind twice.
     */
    fun bindUserService() {
        if (!connecting.compareAndSet(false, true)) return
        // Need a context. We'll get one lazily.
        val context = NukeApplication.instance ?: run {
            connecting.set(false)
            Log.w(TAG, "bindUserService: NukeApplication.instance is null, deferring")
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.i(TAG, "Shizuku UserService connected")
                userServiceBinder = binder
                connecting.set(false)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Shizuku UserService disconnected")
                userServiceBinder = null
                connecting.set(false)
            }
        }
        serviceConnection = connection
        runCatching {
            Shizuku.bindUserService(buildUserServiceArgs(context), connection)
        }.onFailure {
            Log.w(TAG, "Shizuku bindUserService failed: ${it.message}")
            connecting.set(false)
        }
    }
}
