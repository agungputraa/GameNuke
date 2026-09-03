package com.neon.gametweak

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference

/**
 * Process bootstrap intentionally kept tiny. Expensive ADB discovery, consent/ads, integrity checks
 * and local-server creation are deferred until MainActivity has painted its first frame.
 */
class NukeApplication : Application(), Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    private var currentActivityRef: WeakReference<Activity>? = null

    companion object {
        /** Global Application instance used by Shizuku/iAdb bridges to call bindUserService(). */
        @Volatile
        var instance: NukeApplication? = null
            private set
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        installCrashBreadcrumbGuard()
        NukeRemoteConfigRepository.initialize(this)

        // Language restore is cheap and avoids a visible text flip after the first frame.
        val prefs = getSharedPreferences("NukePrefs", MODE_PRIVATE)
        val code = prefs.safeString("hud_lang", "en")
            .takeIf { candidate -> Tx.supportedLangs.any { it.first == candidate } }
            ?: "en"
        Tx.setLang(code)

        // Register Shizuku and iAdb bridge listeners in the background.
        // These are no-ops if Shizuku/iAdb is not installed.
        // The binder will be delivered by their ContentProviders automatically.
        kotlin.concurrent.thread(name = "Nuke-BridgeInit", isDaemon = true) {
            runCatching { NukeShizukuBridge.register() }
            runCatching { NukeIadbBridge.register() }
        }

        // Pre-warm ADB RSA key material in background to avoid main-thread I/O block on first connect.
        kotlin.concurrent.thread(name = "Nuke-KeyWarmup", isDaemon = true) {
            runCatching { AdbManager.getInstance(applicationContext).warmUpKeyMaterial() }
        }

        // Keep local REST API server running in background if enabled.
        // Must use startForegroundService() on Android 8+ so the system knows
        // the service will call startForeground() within 5 s of creation.
        val apiAutoStart = prefs.getBoolean("pref_api_server_enabled", true)
        if (apiAutoStart) {
            runCatching {
                val svcIntent = Intent(this, NukeWebServerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        NukeRemoteConfigRepository.refreshInBackground("app-foreground")
        // kick() is a no-op until MainActivity has explicitly started the orchestrator.
        NukeAdbOrchestrator.kick("app-foreground")
        currentActivityRef?.get()?.let { activity ->
            if (activity is MainActivity && !activity.isFinishing && !activity.isDestroyed) {
                runCatching { NukeAdManager.showAppOpen(activity) }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) { currentActivityRef = WeakReference(activity) }
    override fun onActivityResumed(activity: Activity) { currentActivityRef = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) currentActivityRef = null
    }

    /**
     * Records a tiny local breadcrumb before delegating to Android's original fatal handler.
     * We deliberately do not swallow uncaught exceptions: a corrupted process must terminate.
     * Runtime operations are expected to contain recoverable failures at their source.
     */
    private fun installCrashBreadcrumbGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = java.io.File(filesDir, "last_fatal.txt")
                file.writeText(
                    buildString {
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine("thread=${thread.name}")
                        appendLine("type=${error.javaClass.name}")
                        appendLine("message=${error.message.orEmpty().take(500)}")
                    },
                )
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

}
