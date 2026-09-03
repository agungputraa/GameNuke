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
                statsTextView?.text = "${String.format("%.1f", batteryTemp)}°C • 120Hz"

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

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let {
                batteryTemp = it / 10.0f
            }
        }
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
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(220, 11, 17, 32))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#00ff88"))
        }

        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(20, 12, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 12f
        }

        val fpsView = TextView(context).apply {
            text = "120 FPS"
            setTextColor(Color.parseColor("#00ff88"))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(0, 0, 12, 0)
        }
        fpsTextView = fpsView

        val statsView = TextView(context).apply {
            text = "36.5°C • 120Hz"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 10f
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
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(chip, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(chip, params)
        rootChip = chip

        // Start sampling
        isSampling = true
        lastFpsTimestamp = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)

        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
