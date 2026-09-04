package com.neon.gametweak

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tactical In-Game Floating Macro Reticle & Draggable Pin Overlay.
 *
 * Allows gamers to visually drag a precision target pin directly over any skill,
 * attack, or action button in-game, showing live feedback, speed selector,
 * and rapid-fire execution animation.
 */
class NukeMacroPinOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val controller = NukeMacroController.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootView: FrameLayout? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var stateObserverJob: Job? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val isShowing: Boolean
        get() = rootView != null

    fun show() {
        if (rootView != null) return

        val dm = context.resources.displayMetrics
        val density = dm.density

        // Retrieve existing point or default to 75% width, 65% height
        val currentPoint = controller.state.value.points.firstOrNull() ?: controller.addPoint(
            dm.widthPixels * 0.75f,
            dm.heightPixels * 0.65f,
            60L
        )

        val pinSizePx = (56 * density).toInt()
        val totalWidthPx = (150 * density).toInt()
        val totalHeightPx = (110 * density).toInt()

        val params = WindowManager.LayoutParams(
            totalWidthPx,
            totalHeightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (currentPoint.x - (totalWidthPx / 2f)).toInt().coerceIn(0, dm.widthPixels - totalWidthPx)
            y = (currentPoint.y - (pinSizePx / 2f)).toInt().coerceIn(0, dm.heightPixels - totalHeightPx)
        }

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // 1. Draggable Pin Target (Reticle)
        val targetPin = FrameLayout(context).apply {
            val pinBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#990a1612")) // Dark translucent cyber green
                setStroke((2.5f * density).toInt(), Color.parseColor("#00ff88")) // Neon green
            }
            background = pinBg

            val pinLabel = TextView(context).apply {
                text = "🎯 #1"
                setTextColor(Color.parseColor("#00ff88"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(pinLabel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))
        }

        val pinParams = FrameLayout.LayoutParams(pinSizePx, pinSizePx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        container.addView(targetPin, pinParams)

        // 2. Control Pill (Run/Stop, Speed, Close)
        val controlCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())

            val cardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.parseColor("#ea060f0c"))
                setStroke((1f * density).toInt(), Color.parseColor("#3300ff88"))
            }
            background = cardBg
        }

        // Toggle Run/Stop Button
        val runToggleBtn = TextView(context).apply {
            text = if (controller.state.value.isRunning) "⏸" else "▶"
            setTextColor(Color.parseColor("#00ff88"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                if (controller.state.value.isRunning) {
                    controller.stopMacro()
                } else {
                    controller.detectBestEngine()
                    controller.startMacro()
                }
            }
        }
        controlCard.addView(runToggleBtn)

        // Speed Multiplier Button
        val speedBtn = TextView(context).apply {
            val currentSpeed = controller.state.value.speedMultiplier
            text = "${currentSpeed.toInt()}x"
            setTextColor(Color.parseColor("#00e5ff"))
            textSize = 10.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                val nextSpeed = when (controller.state.value.speedMultiplier) {
                    1.0f -> 2.0f
                    2.0f -> 3.0f
                    3.0f -> 5.0f
                    else -> 1.0f
                }
                controller.setSpeedMultiplier(nextSpeed)
                text = "${nextSpeed.toInt()}x"
            }
        }
        controlCard.addView(speedBtn)

        // Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#889aa7a2"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
            setOnClickListener {
                controller.stopMacro()
                hide()
            }
        }
        controlCard.addView(closeBtn)

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        container.addView(controlCard, cardParams)

        // Drag-to-Move gesture on the Reticle Pin
        targetPin.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                    params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                    runCatching { windowManager.updateViewLayout(container, params) }

                    // Compute target coordinates in screen space
                    val centerX = params.x + (totalWidthPx / 2f)
                    val centerY = params.y + (pinSizePx / 2f)
                    controller.updatePointCoordinates(1, centerX, centerY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val centerX = params.x + (totalWidthPx / 2f)
                    val centerY = params.y + (pinSizePx / 2f)
                    controller.updatePointCoordinates(1, centerX, centerY)
                    controller.saveCurrentProfile()
                    true
                }
                else -> false
            }
        }

        // Pulse animation when macro is active
        pulseAnimator = ObjectAnimator.ofFloat(targetPin, View.SCALE_X, 1.0f, 1.15f, 1.0f).apply {
            duration = 320L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        // Observe macro state to update UI elements live
        stateObserverJob = scope.launch {
            controller.state.collectLatest { state ->
                runToggleBtn.text = if (state.isRunning) "⏸" else "▶"
                if (state.isRunning) {
                    runToggleBtn.setTextColor(Color.parseColor("#ff0055"))
                    if (pulseAnimator?.isStarted != true) {
                        pulseAnimator?.start()
                    }
                } else {
                    runToggleBtn.setTextColor(Color.parseColor("#00ff88"))
                    pulseAnimator?.cancel()
                    targetPin.scaleX = 1.0f
                    targetPin.scaleY = 1.0f
                }
            }
        }

        runCatching {
            windowManager.addView(container, params)
            rootView = container
        }
    }

    fun hide() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        stateObserverJob?.cancel()
        stateObserverJob = null

        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
    }

    fun release() {
        hide()
        scope.cancel()
    }
}
