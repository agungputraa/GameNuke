package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Real-Time Hardware Performance & FPS Floating Overlay Chip.
 *
 * Employs Android Choreographer frame callbacks to compute genuine rendered FPS
 * along with real-time battery thermal data in a compact draggable floating badge.
 */
class NukeFpsOverlayView(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootChip: LinearLayout? = null
    private var fpsTextView: TextView? = null
    private var statsTextView: TextView? = null

    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var isSampling = false
    private var batteryTemp = 0.0f

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val isShowing: Boolean
        get() = rootChip != null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isSampling) return
            frameCount++

            val now = System.currentTimeMillis()
            val delta = now - lastFpsTimestamp
            if (delta >= 1000L) {
                val currentFps = (frameCount * 1000.0 / delta).toInt()
                fpsTextView?.text = "$currentFps FPS"
                val refreshHz = currentRefreshRateHz()
                statsTextView?.text = "${String.format("%.1f", batteryTemp)}°C • ${refreshHz}Hz"

                // Color code based on stability
                when {
                    currentFps >= 90 -> fpsTextView?.setTextColor(Color.parseColor("#00ff88")) // Neon green
                    currentFps >= 55 -> fpsTextView?.setTextColor(Color.parseColor("#00e5ff")) // Cyan
                    else -> fpsTextView?.setTextColor(Color.parseColor("#ff0055"))             // Red warning
                }

                frameCount = 0
                lastFpsTimestamp = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun currentRefreshRateHz(): Int = runCatching {
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate ?: windowManager.defaultDisplay.refreshRate
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
        rate.toInt().coerceAtLeast(1)
    }.getOrDefault(60)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let {
                batteryTemp = it / 10.0f
            }
        }
    }

    companion object {
        @Volatile
        private var instance: NukeFpsOverlayView? = null

        fun getInstance(context: Context): NukeFpsOverlayView {
            return instance ?: synchronized(this) {
                instance ?: NukeFpsOverlayView(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun show() {
        if (rootChip != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val d = context.resources.displayMetrics.density
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(235, 8, 12, 16)) // Obsidian Cyber Glass
            cornerRadius = 10f * d
            setStroke((1.2f * d).toInt(), Color.parseColor("#3300FF88"))
        }

        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            gravity = Gravity.CENTER_VERTICAL
            elevation = 12f
        }

        val fpsView = TextView(context).apply {
            text = "-- FPS"
            setTextColor(Color.parseColor("#00FF88"))
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(0, 0, (8 * d).toInt(), 0)
        }
        fpsTextView = fpsView

        val statsView = TextView(context).apply {
            text = "--.-°C • ${currentRefreshRateHz()}Hz"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
        }
        statsTextView = statsView

        chip.addView(fpsView)
        chip.addView(statsView)

        // Draggable touch listener
        chip.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = context.resources.displayMetrics
                    params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, (dm.widthPixels - (120 * d).toInt()).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, (dm.heightPixels - (50 * d).toInt()).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(chip, params) }
                    true
                }
                else -> false
            }
        }

        val added = runCatching { windowManager.addView(chip, params) }.isSuccess
        if (!added) {
            return
        }
        rootChip = chip

        // Start sampling
        isSampling = true
        lastFpsTimestamp = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)

        try {
            ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun hide() {
        isSampling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Throwable) {
            // ignore
        }

        rootChip?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Throwable) {
                // ignore
            }
            rootChip = null
        }
    }
}
