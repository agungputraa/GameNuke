package com.neon.gametweak

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * NukeNativeFreeformLauncher — Enterprise Native App Floating Window Orchestrator.
 *
 * Developer: Agung Developer
 *
 * Responsibilities:
 *  - Opens the USER'S REAL INSTALLED NATIVE APPLICATIONS (WhatsApp, Instagram, Telegram,
 *    Messenger, Discord, Browser, etc.) in Android Freeform Windowing Mode (WINDOWING_MODE_FREEFORM = 5)
 *    directly over active games WITHOUT kicking the user out of the game.
 *  - Multi-tier execution strategy:
 *     Tier 1: Privileged Shell (Shizuku / Local ADB / Daemon / Root via NukeConnectionManager)
 *             - Auto-configures: enable_freeform_support=1 & force_resizable_activities=1
 *             - Launches via: am start -n <Component> --windowingMode 5 --activity-launch-bounds <l,t,r,b> -f 0x18000000
 *     Tier 2: In-App ActivityOptions Reflection & OEM Multi-Window Intents
 *             - setLaunchBounds(Rect)
 *             - setLaunchWindowingMode(5) via reflection
 *             - Xiaomi/HyperOS, Samsung OneUI Pop-up, ColorOS Flexible Window intent extras
 *     Tier 3: User guidance & Developer Options shortcut if firmware restricts freeform.
 */
object NukeNativeFreeformLauncher {

    private const val TAG = "NukeFreeformLauncher"
    private const val WINDOWING_MODE_FREEFORM = 5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Flag: Intent.FLAG_ACTIVITY_NEW_TASK (0x10000000) | Intent.FLAG_ACTIVITY_MULTIPLE_TASK (0x08000000) | FLAG_ACTIVITY_LAUNCH_ADJACENT (0x00001000)
    private const val LAUNCH_FLAGS_HEX = "0x18001000"

    // Floating Close Pill (appears on screen after native freeform launch)
    private var closePillView: android.view.View? = null
    private var closePillParams: android.view.WindowManager.LayoutParams? = null
    private var lastLaunchedPkg: String = ""
    private var lastLaunchedLabel: String = ""

    /**
     * Launches an installed package in a floating / freeform window on top of the current game.
     */
    fun launchAppFloating(context: Context, packageName: String, label: String) {
        val pm = context.packageManager

        // 1. Resolve package (auto-detect WhatsApp standard vs WhatsApp Business)
        var targetPkg = packageName
        if (targetPkg == "com.whatsapp" && pm.getLaunchIntentForPackage("com.whatsapp") == null) {
            if (pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null) {
                targetPkg = "com.whatsapp.w4b"
            }
        } else if (targetPkg == "com.whatsapp.w4b" && pm.getLaunchIntentForPackage("com.whatsapp.w4b") == null) {
            if (pm.getLaunchIntentForPackage("com.whatsapp") != null) {
                targetPkg = "com.whatsapp"
            }
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
        if (launchIntent == null) {
            NukeToast.error(context, "$label is not installed on this device")
            return
        }

        val component = launchIntent.component
        val componentNameStr = component?.flattenToString() ?: ""

        // 2. Calculate optimal floating window geometry proportional to current screen
        val bounds = calculateFloatingBounds(context)
        val (l, t, r, b) = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)

        Log.d(TAG, "Launching $targetPkg ($componentNameStr) in floating window: $bounds")

        // 3. Priority 1: Privileged Shell Execution (Shizuku / Local ADB / Daemon / Root)
        // Shell launch with windowingMode 5 is the ONLY way on modern Android (11-15) to open
        // a real floating window OVER an active game without kicking the game to the background.
        if (NukeConnectionManager.isConnected()) {
            scope.launch {
                executeShellFreeformLaunch(context, targetPkg, componentNameStr, l, t, r, b, label)
            }
            return
        }

        // 4. Priority 2: OEM-specific floating window intents for supported manufacturers
        if (isDeviceFreeformCapable(context)) {
            val inAppSuccess = executeInAppFreeformLaunch(context, launchIntent, bounds, label)
            if (inAppSuccess) return
        }

        // 5. Fallback: Device does not have privileged shell and in-app launch failed
        Log.w(TAG, "Native freeform unsupported without ADB/Shizuku for $targetPkg")
        mainHandler.post {
            NukeToast.unsupported(
                context,
                "⚡ Activate Shizuku / ADB to open $label in Floating Window mode",
                long = true
            )
        }
    }

    /**
     * Calculates proportional floating window bounds (72% width, 70% height centered).
     */
    fun calculateFloatingBounds(context: Context): Rect {
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        val isLandscape = sw > sh
        val w = if (isLandscape) (sw * 0.58f).roundToInt() else (sw * 0.78f).roundToInt()
        val h = if (isLandscape) (sh * 0.82f).roundToInt() else (sh * 0.68f).roundToInt()

        val l = ((sw - w) / 2).coerceAtLeast(0)
        val t = if (isLandscape) ((sh - h) / 2).coerceAtLeast(0) else (sh * 0.12f).roundToInt().coerceAtLeast(0)

        return Rect(l, t, l + w, t + h)
    }

    /**
     * Executes shell-based launch into windowingMode 5 using privileged UID 2000 (Shizuku/ADB) or UID 0 (Root).
     */
    private fun executeShellFreeformLaunch(
        context: Context,
        packageName: String,
        componentName: String,
        l: Int, t: Int, r: Int, b: Int,
        label: String
    ) {
        try {
            // 1. Ensure system-level freeform settings & transparent letterbox are enabled
            // Eliminates solid gray letterbox background on Android 12-15
            val setupScript = """
                settings put global enable_freeform_support 1 2>/dev/null
                settings put global force_resizable_activities 1 2>/dev/null
                settings put secure force_resizable_activities 1 2>/dev/null
                cmd window set-multi-window-config --supportsNonResizable 1 2>/dev/null
                cmd window set-letterbox-style --isTranslucentLetterboxingEnabled true 2>/dev/null
                cmd window set-letterbox-style --backgroundType app_color_background_floating 2>/dev/null
            """.trimIndent()
            NukeConnectionManager.executeCommand(setupScript, 2_000L)

            // 2. Build target am start command (Without --activity-launch-bounds which is deprecated/unsupported on Android 14/15)
            val targetParam = if (componentName.isNotBlank()) "-n $componentName" else packageName
            val amCmd = "am start $targetParam --windowingMode $WINDOWING_MODE_FREEFORM -f $LAUNCH_FLAGS_HEX"

            val result = NukeConnectionManager.executeCommand(amCmd, 5_000L)
            Log.d(TAG, "Shell am start result: exit=${result?.exitCode}, out=${result?.output}")

            if (result?.isSuccess == true && !result.output.contains("Error", ignoreCase = true)) {
                NukeToast.success(context, "💬 Opening $label (Floating Mode)")
                mainHandler.postDelayed({ showClosePill(context, packageName, label) }, 800L)
                return
            }

            // If component-specific start had an issue, fallback to package only
            val fallbackCmd = "am start $packageName --windowingMode $WINDOWING_MODE_FREEFORM -f $LAUNCH_FLAGS_HEX"
            val fallbackRes = NukeConnectionManager.executeCommand(fallbackCmd, 4_000L)
            if (fallbackRes?.isSuccess == true && fallbackRes.output.contains("Starting:", ignoreCase = true)) {
                NukeToast.success(context, "💬 Opening $label (Floating Mode)")
                mainHandler.postDelayed({ showClosePill(context, packageName, label) }, 800L)
                return
            }

            // If shell didn't succeed, fallback to in-app ActivityOptions ONLY if device is known freeform capable
            if (isDeviceFreeformCapable(context)) {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    val bounds = Rect(l, t, r, b)
                    executeInAppFreeformLaunch(context, intent, bounds, label)
                }
            } else {
                mainHandler.post {
                    NukeToast.unsupported(context, "⚡ Floating Window for $label requires active Shizuku / ADB.")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "executeShellFreeformLaunch failed", e)
            if (isDeviceFreeformCapable(context)) {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    val bounds = Rect(l, t, r, b)
                    executeInAppFreeformLaunch(context, intent, bounds, label)
                } else {
                    mainHandler.post {
                        NukeToast.error(context, "Failed to launch $label in floating mode")
                    }
                }
            } else {
                mainHandler.post {
                    NukeToast.unsupported(context, "⚡ Floating Window for $label requires active Shizuku / ADB.")
                }
            }
        }
    }

    /**
     * Executes in-app launch using ActivityOptions reflection and OEM multi-window intent extras.
     */
    private fun executeInAppFreeformLaunch(
        context: Context,
        launchIntent: Intent,
        bounds: Rect,
        label: String
    ): Boolean {
        return try {
            val options = ActivityOptions.makeBasic()

            // 1. Set launch bounds
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                options.launchBounds = bounds
            }

            // 2. Set windowing mode to FREEFORM (5) via reflection (Android 9+)
            var windowModeSet = false
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode",
                    Int::class.javaPrimitiveType
                )
                method.invoke(options, WINDOWING_MODE_FREEFORM)
                windowModeSet = true
            } catch (ignored: Throwable) {}

            // 3. Fallback for older Android 7.0 - 8.1 (setLaunchStackId)
            if (!windowModeSet) {
                try {
                    val method = ActivityOptions::class.java.getMethod(
                        "setLaunchStackId",
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(options, WINDOWING_MODE_FREEFORM)
                } catch (ignored: Throwable) {}
            }

            // 4. Configure intent flags for multi-window (Adjacent & Multiple Task, preserving background game)
            launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

                // Generic Android Freeform Extra
                putExtra("android.intent.extra.WINDOW_MODE", WINDOWING_MODE_FREEFORM)
                putExtra("launch_bounds", bounds)

                // Xiaomi / HyperOS / MIUI Floating Window Extras
                putExtra("miui.focus.windowingMode", 5)
                putExtra("miui.window.freeform", true)
                putExtra("miui_floating_window", true)

                // Samsung OneUI Pop-up View Extras
                putExtra("com.samsung.android.freeform", true)
                putExtra("com.samsung.android.intent.extra.STACK_BOUNDS", bounds)

                // ColorOS / Realme / OnePlus Flexible Window Extras
                putExtra("oplus_freeform", true)
                putExtra("coloros_freeform", true)

                // Vivo / OriginOS Small Window Extras
                putExtra("vivo_freeform", true)
            }

            context.startActivity(launchIntent, options.toBundle())
            NukeToast.success(context, "💬 Opening $label (Floating Mode)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "In-app freeform launch failed", e)
            false
        }
    }

    /**
     * Checks if the device firmware natively reports freeform windowing support.
     */
    fun isDeviceFreeformCapable(context: Context): Boolean {
        val pm = context.packageManager
        val hasFeature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        } else false
        val isOemKnown = Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.contains("samsung", ignoreCase = true) ||
                Build.MANUFACTURER.contains("oppo", ignoreCase = true) ||
                Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
                Build.MANUFACTURER.contains("oneplus", ignoreCase = true)
        return hasFeature || (isOemKnown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    /**
     * Enables system-level freeform support if privileged shell is active.
     */
    fun ensureFreeformEnabledAsync() {
        if (!NukeConnectionManager.isConnected()) return
        scope.launch {
            try {
                val script = """
                    settings put global enable_freeform_support 1 2>/dev/null
                    settings put global force_resizable_activities 1 2>/dev/null
                    settings put secure force_resizable_activities 1 2>/dev/null
                """.trimIndent()
                NukeConnectionManager.executeCommand(script, 3_000L)
            } catch (ignored: Throwable) {}
        }
    }

    // ── Floating Close Pill ──────────────────────────────────────────────

    /**
     * Shows a floating close pill (small draggable bar with ✕ button) so user can close
     * the native floating app at any time without leaving the game.
     */
    @Suppress("DEPRECATION")
    fun showClosePill(context: Context, packageName: String, label: String) {
        hideClosePill(context)
        lastLaunchedPkg = packageName
        lastLaunchedLabel = label
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val d = context.resources.displayMetrics.density
        val dm = context.resources.displayMetrics

        val pillW = (180 * d).toInt()
        val pillH = (34 * d).toInt()

        val windowType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else android.view.WindowManager.LayoutParams.TYPE_PHONE

        val lp = android.view.WindowManager.LayoutParams(
            pillW, pillH, windowType,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            x = (12 * d).toInt()
            y = (dm.heightPixels * 0.08f).toInt()
        }
        closePillParams = lp

        val pill = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E8100A0A"))
                cornerRadius = 17 * d
                setStroke((1.2f * d).toInt(), android.graphics.Color.parseColor("#EF4444"))
            }
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)

            // Drag handle dot
            val dot = android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#EF4444"))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply { rightMargin = (6 * d).toInt() }
            }
            addView(dot)

            val closeTv = android.widget.TextView(context).apply {
                text = "✕ Close $label"
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    // Force-stop the native floating app
                    scope.launch {
                        NukeConnectionManager.executeCommand("am force-stop $packageName", 3_000L)
                    }
                    hideClosePill(context)
                    NukeToast.success(context, "✓ $label closed")
                }
            }
            addView(closeTv)
        }

        // Drag support for the pill itself
        var startX = 0; var startY = 0; var initX = 0; var initY = 0
        pill.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { startX = event.rawX.toInt(); startY = event.rawY.toInt(); initX = lp.x; initY = lp.y; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lp.x = (initX - (event.rawX.toInt() - startX)).coerceIn(0, dm.widthPixels - pillW)
                    lp.y = (initY + (event.rawY.toInt() - startY)).coerceIn(0, dm.heightPixels - pillH)
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }

        closePillView = pill
        runCatching { wm.addView(pill, lp) }
    }

    fun hideClosePill(context: Context) {
        closePillView?.let { v ->
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            runCatching { wm.removeView(v) }
        }
        closePillView = null
        closePillParams = null
    }
}
