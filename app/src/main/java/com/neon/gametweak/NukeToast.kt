package com.neon.gametweak

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * NukeToast — High-priority HUD overlay toast system.
 *
 * Renders on top of all windows (including Floating Booster HUD, overlays, and full-screen games)
 * via TYPE_APPLICATION_OVERLAY at Gravity.TOP.
 *
 * Provides distinct visual identities for:
 * - INFO (Cyan / Blue)
 * - SUCCESS (Neon Green)
 * - WARNING / NOTICE (Amber)
 * - ERROR (Crimson Red)
 * - UNSUPPORTED (Orange)
 */
object NukeToast {
    enum class Outcome(val label: String, val colorHex: String, val iconChar: String) {
        SUCCESS("SUCCESS", "#00FFA3", "✓"),
        INFO("INFO", "#00E5FF", "ℹ"),
        WARNING("NOTICE", "#FFB84A", "⚠"),
        ERROR("ERROR", "#FF3366", "✕"),
        UNSUPPORTED("UNSUPPORTED", "#FF9800", "⛔"),
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToastView: View? = null
    private var dismissRunnable: Runnable? = null

    fun success(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.SUCCESS, message, long)

    fun info(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.INFO, message, long)

    fun warning(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.WARNING, message, long)

    fun error(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.ERROR, message, long)

    fun unsupported(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.UNSUPPORTED, message, long)

    fun fromResult(context: Context, success: Boolean, message: String, long: Boolean = false) =
        show(context, if (success) Outcome.SUCCESS else Outcome.ERROR, message, long)

    private fun show(context: Context, outcome: Outcome, message: String, long: Boolean) {
        val app = context.applicationContext
        val cleanMsg = message.trim().ifBlank {
            if (outcome == Outcome.SUCCESS) "Action completed" else "Action could not be completed"
        }.take(180)

        val action = {
            if (Settings.canDrawOverlays(app)) {
                showOverlayToast(app, outcome, cleanMsg, long)
            } else {
                val fallbackText = "${outcome.label} • $cleanMsg"
                Toast.makeText(app, fallbackText, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun showOverlayToast(app: Context, outcome: Outcome, message: String, long: Boolean) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val dm = app.resources.displayMetrics
        fun dp(value: Int): Int = (value * dm.density).toInt()

        // 1. Dismiss any existing overlay toast view safely
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        currentToastView?.let { oldView ->
            runCatching { wm.removeView(oldView) }
            currentToastView = null
        }

        val themeColor = Color.parseColor(outcome.colorHex)

        // 2. Build root container (floating pill card)
        val container = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F40C1218"))
                setStroke(dp(1), themeColor)
                cornerRadius = dp(24).toFloat()
            }
            background = bg
            elevation = dp(24).toFloat()
        }

        // 3. Left indicator badge (icon + outcome label)
        val badgeContainer = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))

            val badgeBg = GradientDrawable().apply {
                setColor(Color.argb(45, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor)))
                setStroke(dp(1), themeColor)
                cornerRadius = dp(12).toFloat()
            }
            background = badgeBg
        }

        val badgeText = TextView(app).apply {
            text = "${outcome.iconChar} ${outcome.label}"
            setTextColor(themeColor)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        badgeContainer.addView(badgeText)
        container.addView(badgeContainer)

        // 4. Message text
        val msgView = TextView(app).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
            setPadding(dp(9), 0, 0, 0)
        }
        container.addView(msgView)

        // 5. Layout parameters: Top-level overlay above floating HUD and games
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(65) // Sits comfortably below system status bar and above HUD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val attached = runCatching {
            wm.addView(container, params)
            currentToastView = container
            true
        }.getOrDefault(false)

        if (attached) {
            val duration = if (long) 3800L else 2400L
            val runnable = Runnable {
                currentToastView?.let { v ->
                    runCatching {
                        v.animate()
                            .alpha(0f)
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(180)
                            .withEndAction {
                                runCatching { wm.removeView(v) }
                                if (currentToastView === v) {
                                    currentToastView = null
                                }
                            }
                            .start()
                    }
                }
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, duration)
        } else {
            runCatching {
                val fallbackText = "${outcome.label} • $message"
                Toast.makeText(app, fallbackText, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }
}
