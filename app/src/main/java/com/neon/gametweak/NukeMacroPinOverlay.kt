package com.neon.gametweak

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
 * Multi-Pin Draggable Macro HUD Overlay.
 *
 * Each macro tap point gets its own independent floating pin that the user can:
 * - Drag freely anywhere on screen
 * - Add/remove via the control panel
 * - See numbered (●1 ●2 ●3…) for easy identification
 *
 * A separate, independently draggable control panel provides:
 * - Run / Stop toggle
 * - Add Pin (+) button
 * - Remove Last Pin (−) button
 * - Speed selector (1× → 2× → 3× → 5×)
 * - Close button
 *
 * Each pin lives in its own WindowManager view so they can be positioned
 * completely independently without interfering with each other.
 */
class NukeMacroPinOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val controller = NukeMacroController.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // One WindowManager view per pin
    private val pinViews = mutableMapOf<Int, View>()
    private val pinParams = mutableMapOf<Int, WindowManager.LayoutParams>()
    private val pinAnimators = mutableMapOf<Int, ObjectAnimator>()

    // Control panel
    private var controlPanel: View? = null
    private var controlPanelParams: WindowManager.LayoutParams? = null
    private var stateObserverJob: Job? = null
    private var runToggleBtn: TextView? = null

    val isShowing: Boolean
        get() = controlPanel != null

    private val density: Float
        get() = context.resources.displayMetrics.density

    // ──────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────

    fun show() {
        if (controlPanel != null) return

        // Ensure at least 1 tap point exists
        if (controller.state.value.points.isEmpty()) {
            val dm = context.resources.displayMetrics
            controller.addPoint(dm.widthPixels * 0.5f, dm.heightPixels * 0.55f, 60L)
        }

        showControlPanel()
        refreshAllPins()
        observeState()
    }

    fun hide() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        controller.stopMacro()
        removeControlPanel()
        removeAllPinViews()
    }

    fun release() {
        hide()
        scope.cancel()
    }

    // ──────────────────────────────────────────────────────
    // CONTROL PANEL
    // ──────────────────────────────────────────────────────

    private fun showControlPanel() {
        val d = density
        val panelW = (200 * d).toInt()
        val panelH = WindowManager.LayoutParams.WRAP_CONTENT

        val params = makeOverlayParams(panelW, panelH).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (48 * d).toInt()
            y = (48 * d).toInt()
        }

        val panel = buildControlPanel(params)
        runCatching { windowManager.addView(panel, params) }
        controlPanel = panel
        controlPanelParams = params
    }

    private fun buildControlPanel(params: WindowManager.LayoutParams): LinearLayout {
        val d = density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * d
            setColor(Color.parseColor("#f0060e0b"))
            setStroke((1.2f * d).toInt(), Color.parseColor("#4400ff88"))
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            elevation = 8f * d
        }

        // Title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = makeTv("⚡ MACRO", 10.5f, "#00ff88", bold = true)
        title.setPadding((4 * d).toInt(), 0, 0, 0)
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Close button
        val closeBtn = makeTv("✕", 12f, "#667a8a", bold = true).apply {
            setPadding((6 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            setOnClickListener { hide() }
        }
        titleRow.addView(closeBtn)
        panel.addView(titleRow)

        // Divider
        panel.addView(makeDivider())

        // Run / Stop row
        val runRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }

        val runBtn = makeTv("▶ RUN", 11.5f, "#00ff88", bold = true).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * d
                setColor(Color.parseColor("#2200ff88"))
                setStroke((1f * d).toInt(), Color.parseColor("#5500ff88"))
            }
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            gravity = Gravity.CENTER
            setOnClickListener {
                if (controller.state.value.isRunning) controller.stopMacro()
                else { controller.detectBestEngine(); controller.startMacro() }
            }
        }
        this.runToggleBtn = runBtn
        runRow.addView(runBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Speed button
        val speedBtn = makeTv("1×", 11f, "#00d4ff", bold = true).apply {
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            setOnClickListener {
                val next = when (controller.state.value.speedMultiplier) {
                    1.0f -> 2.0f; 2.0f -> 3.0f; 3.0f -> 5.0f; else -> 1.0f
                }
                controller.setSpeedMultiplier(next)
                text = "${next.toInt()}×"
            }
        }
        runRow.addView(speedBtn)
        panel.addView(runRow)

        panel.addView(makeDivider())

        // Pin management row
        val pinRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
        }

        val pinLabel = makeTv("PINS", 9.5f, "#667a8a", bold = true)
        pinLabel.setPadding((4 * d).toInt(), 0, 0, 0)
        pinRow.addView(pinLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Remove pin button
        val removeBtn = makeTv("−", 16f, "#ff4466", bold = true).apply {
            setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
            setOnClickListener {
                val points = controller.state.value.points
                if (points.size > 1) {
                    val last = points.last()
                    removePinView(last.id)
                    controller.removePoint(last.id)
                }
            }
        }
        pinRow.addView(removeBtn)

        // Add pin button
        val addBtn = makeTv("+", 16f, "#00ff88", bold = true).apply {
            setPadding((10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt())
            setOnClickListener {
                if (controller.state.value.points.size < 8) { // max 8 pins
                    val dm = context.resources.displayMetrics
                    val offset = controller.state.value.points.size * (60 * density).toInt()
                    val newPoint = controller.addPoint(
                        (dm.widthPixels * 0.5f + offset).coerceAtMost(dm.widthPixels * 0.85f),
                        dm.heightPixels * 0.55f,
                        60L
                    )
                    addPinView(newPoint)
                }
            }
        }
        pinRow.addView(addBtn)
        panel.addView(pinRow)

        // Make control panel itself draggable
        makeDraggable(panel, params, onDragEnd = null)
        return panel
    }

    private fun removeControlPanel() {
        controlPanel?.let { runCatching { windowManager.removeView(it) } }
        controlPanel = null
        controlPanelParams = null
        runToggleBtn = null
    }

    // ──────────────────────────────────────────────────────
    // PIN VIEWS
    // ──────────────────────────────────────────────────────

    private fun refreshAllPins() {
        val currentPoints = controller.state.value.points
        // Remove views for deleted points
        val currentIds = currentPoints.map { it.id }.toSet()
        pinViews.keys.toList().forEach { id ->
            if (id !in currentIds) removePinView(id)
        }
        // Add views for new points
        currentPoints.forEach { point ->
            if (point.id !in pinViews) addPinView(point)
        }
    }

    private fun addPinView(point: NukeMacroController.MacroPoint) {
        val d = density
        val pinSize = (52 * d).toInt()
        val dm = context.resources.displayMetrics

        val params = makeOverlayParams(pinSize, pinSize).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (point.x - pinSize / 2f).toInt().coerceIn(0, dm.widthPixels - pinSize)
            y = (point.y - pinSize / 2f).toInt().coerceIn(0, dm.heightPixels - pinSize)
        }

        val pinNumber = point.id
        val pin = buildPinView(pinNumber, point, params)
        runCatching { windowManager.addView(pin, params) }
        pinViews[pinNumber] = pin
        pinParams[pinNumber] = params
    }

    private fun buildPinView(
        id: Int,
        point: NukeMacroController.MacroPoint,
        params: WindowManager.LayoutParams
    ): FrameLayout {
        val d = density
        val colors = listOf("#00ff88", "#00d4ff", "#ffcc00", "#ff4466", "#cc88ff", "#ff8800", "#44ffcc", "#ff88cc")
        val color = colors[(id - 1) % colors.size]

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#cc040c09"))
            setStroke((2f * d).toInt(), Color.parseColor(color))
        }

        val pin = FrameLayout(context).apply {
            background = bg
            clipChildren = false
        }

        val label = TextView(context).apply {
            text = "●$id"
            setTextColor(Color.parseColor(color))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        pin.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        // Pulse animation — started/stopped based on macro run state
        val animator = ObjectAnimator.ofFloat(pin, View.SCALE_X, 1.0f, 1.2f, 1.0f).apply {
            duration = 280L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val animatorY = ObjectAnimator.ofFloat(pin, View.SCALE_Y, 1.0f, 1.2f, 1.0f).apply {
            duration = 280L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        // Store both using the X animator; we control Y manually
        pinAnimators[id] = animator
        if (controller.state.value.isRunning) {
            animator.start(); animatorY.start()
        }

        // Drag to reposition pin
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        pin.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + (event.rawX - initTouchX)).toInt()
                    params.y = (initY + (event.rawY - initTouchY)).toInt()
                    if (pinViews[id] != null) {
                        runCatching { windowManager.updateViewLayout(pin, params) }
                    }
                    // Update the logical tap coordinate to the center of the pin
                    val dm = context.resources.displayMetrics
                    val pinSize = pin.width.takeIf { it > 0 } ?: (52 * d).toInt()
                    controller.updatePointCoordinates(id,
                        params.x + pinSize / 2f,
                        params.y + pinSize / 2f
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dm = context.resources.displayMetrics
                    val pinSize = pin.width.takeIf { it > 0 } ?: (52 * d).toInt()
                    controller.updatePointCoordinates(id,
                        params.x + pinSize / 2f,
                        params.y + pinSize / 2f
                    )
                    controller.saveCurrentProfile()
                    true
                }
                else -> false
            }
        }

        return pin
    }

    private fun removePinView(id: Int) {
        pinAnimators.remove(id)?.cancel()
        pinViews.remove(id)?.let { runCatching { windowManager.removeView(it) } }
        pinParams.remove(id)
    }

    private fun removeAllPinViews() {
        pinAnimators.values.forEach { it.cancel() }
        pinAnimators.clear()
        pinViews.values.forEach { runCatching { windowManager.removeView(it) } }
        pinViews.clear()
        pinParams.clear()
    }

    // ──────────────────────────────────────────────────────
    // STATE OBSERVER
    // ──────────────────────────────────────────────────────

    private fun observeState() {
        stateObserverJob = scope.launch {
            controller.state.collectLatest { state ->
                // Update run button label and color
                runToggleBtn?.apply {
                    text = if (state.isRunning) "⏸ STOP" else "▶ RUN"
                    setTextColor(Color.parseColor(if (state.isRunning) "#ff4466" else "#00ff88"))
                }

                // Update pulse on all pins
                pinViews.keys.forEach { id ->
                    val animator = pinAnimators[id] ?: return@forEach
                    if (state.isRunning) {
                        if (!animator.isStarted) animator.start()
                    } else {
                        animator.cancel()
                        pinViews[id]?.scaleX = 1f
                        pinViews[id]?.scaleY = 1f
                    }
                }

                // Sync pin views with current point list
                refreshAllPins()
            }
        }
    }

    // ──────────────────────────────────────────────────────
    // DRAG HELPER
    // ──────────────────────────────────────────────────────

    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        onDragEnd: (() -> Unit)?
    ) {
        var initX = 0; var initY = 0
        var initTX = 0f; var initTY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = event.rawX; initTY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + (event.rawX - initTX)).toInt()
                    params.y = (initY + (event.rawY - initTY)).toInt()
                    if (controlPanel != null) runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { onDragEnd?.invoke(); true }
                else -> false
            }
        }
    }

    // ──────────────────────────────────────────────────────
    // VIEW FACTORIES
    // ──────────────────────────────────────────────────────

    private fun makeTv(text: String, size: Float, color: String, bold: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            textSize = size
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
    }

    private fun makeDivider(): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#1a334433"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }
    }

    private fun makeOverlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }
}
