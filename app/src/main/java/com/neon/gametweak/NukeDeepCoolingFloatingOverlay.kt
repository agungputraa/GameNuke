package com.neon.gametweak

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NukeDeepCoolingFloatingOverlay — Active In-Game Thermal Control & Telemetry Studio.
 *
 * Provides a responsive, professional floating cockpit overlay for in-game thermal
 * profile tuning, kernel cache maintenance, and real-time hardware temperature telemetry.
 */
class NukeDeepCoolingFloatingOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var telemetryJob: Job? = null

    // UI elements
    private var tempTv: TextView? = null
    private var statusTv: TextView? = null
    private var statusBadgeTv: TextView? = null
    private var progressBar: ProgressBar? = null
    private var purgeBtn: Button? = null
    private var modeButtons = mutableMapOf<CoolingMode, TextView>()

    enum class CoolingMode(val label: String, val desc: String) {
        CRYO("RELIEF", "Reduce background load & trim caches"),
        HYPER("PERFORMANCE", "Performance governor & GPU prioritization"),
        AUTO("ADAPTIVE", "Continuous thermal & memory balance")
    }

    private var currentMode = CoolingMode.AUTO
    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeThermalControlHUD"

        @Volatile
        private var instance: NukeDeepCoolingFloatingOverlay? = null

        fun getInstance(context: Context): NukeDeepCoolingFloatingOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeDeepCoolingFloatingOverlay(context.applicationContext).also { instance = it }
            }
    }

    fun toggle(): Boolean {
        val willShow = !isShowing
        if (willShow) show() else hide()
        return willShow
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post

            val (sw, sh) = getScreenSize()
            val panelW = (320 * d).toInt().coerceAtMost((sw * 0.92f).toInt())
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            val lp = WindowManager.LayoutParams(
                panelW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((sw - panelW) / 2).coerceAtLeast(0)
                y = (sh * 0.16f).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            rootParams = lp

            val panel = buildView(panelW)
            rootView = panel

            try {
                wm.addView(panel, lp)
                startTelemetryLoop()
            } catch (e: Exception) {
                rootView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            telemetryJob?.cancel()
            telemetryJob = null
            rootView?.let {
                runCatching { wm.removeView(it) }
                rootView = null
                rootParams = null
            }
        }
    }

    private fun buildView(panelW: Int): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = NukeCyberHudStyler.TacticalPanelDrawable(
                density = d,
                cornerRadiusPx = 14 * d,
                strokeColor = NukeCyberHudStyler.COLOR_CYAN_NEON,
                bgColor = NukeCyberHudStyler.COLOR_BG_OBSIDIAN,
                showGrid = true,
                showBrackets = true
            )
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            elevation = 16 * d
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        // ── 1. Top Draggable Header (Dedicated Drag Handle) ───────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (6 * d).toInt())
        }

        // Draggable Pill Indicator
        val gripBar = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
            val pill = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D94A3B8"))
                    cornerRadius = 2 * d
                }
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (3.5f * d).toInt())
            }
            addView(pill)
        }
        header.addView(gripBar)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = "THERMAL CONTROL"
            textSize = 12f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        val subTv = TextView(context).apply {
            text = "PROFILE & CACHE MANAGEMENT"
            textSize = 7.5f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.MONOSPACE
            setPadding(0, (1 * d).toInt(), 0, 0)
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        headerRow.addView(titleCol)

        // Status Badge
        statusBadgeTv = TextView(context).apply {
            text = "[ACTIVE]"
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#10B981"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1410B981"))
                cornerRadius = 4 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#2810B981"))
            }
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * d).toInt() }
        }
        headerRow.addView(statusBadgeTv)

        // Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 12 * d
            }
            layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt())
            setOnClickListener { hide() }
        }
        headerRow.addView(closeBtn)
        header.addView(headerRow)

        // Attach dragging strictly to the header
        setupDrag(header)
        root.addView(header)

        // Divider
        val div = View(context).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
                bottomMargin = (8 * d).toInt()
            }
        }
        root.addView(div)

        // ── 2. Hardware Telemetry Card ───────────────────────────────────────
        val telemetryCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NukeCyberHudStyler.buildCardBackground(
                density = d,
                cornerRadiusDp = 8f,
                strokeColor = NukeCyberHudStyler.COLOR_BORDER_SUBTLE,
                fillColor = NukeCyberHudStyler.COLOR_BG_CARD
            )
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() }
        }

        // Temp Block
        val tempBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tempLabel = TextView(context).apply {
            text = "DEVICE TEMP"
            textSize = 8f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.DEFAULT_BOLD
        }
        tempTv = TextView(context).apply {
            text = "--.-°C"
            textSize = 14f
            setTextColor(Color.parseColor("#10B981"))
            typeface = Typeface.DEFAULT_BOLD
        }
        tempBlock.addView(tempLabel)
        tempBlock.addView(tempTv)
        telemetryCard.addView(tempBlock)

        // Subtle Divider
        val cardDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (22 * d).toInt()).apply {
                leftMargin = (6 * d).toInt(); rightMargin = (6 * d).toInt()
            }
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        telemetryCard.addView(cardDivider)

        // Profile State Block
        val stateBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val stateLabel = TextView(context).apply {
            text = "GOVERNOR PROFILE"
            textSize = 8f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.DEFAULT_BOLD
        }
        statusTv = TextView(context).apply {
            text = "ADAPTIVE"
            textSize = 12.5f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        stateBlock.addView(stateLabel)
        stateBlock.addView(statusTv)
        telemetryCard.addView(stateBlock)

        root.addView(telemetryCard)

        // ── 3. Tri-Mode Selector ─────────────────────────────────────────────
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() }
        }

        CoolingMode.values().forEach { mode ->
            val modeBtn = TextView(context).apply {
                text = mode.label
                textSize = 10f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = (3 * d).toInt()
                    leftMargin = (3 * d).toInt()
                }
                setOnClickListener { selectMode(mode) }
            }
            modeButtons[mode] = modeBtn
            modeRow.addView(modeBtn)
        }
        root.addView(modeRow)
        refreshModeButtons()

        // ── 4. Main 1-Tap Active Flush Button ────────────────────────────────
        val purgeContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * d).toInt()
            )
        }

        purgeBtn = Button(context).apply { installNukePressFeedback() }.apply {
            text = "TRIM MEMORY & OPTIMIZE PROFILE"
            textSize = 11.5f
            setTextColor(Color.parseColor("#06100C"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981")) // Cyber Emerald
                cornerRadius = 8 * d
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { executeInstantCryoPurge() }
        }
        purgeContainer.addView(purgeBtn)

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER)
        }
        purgeContainer.addView(progressBar)

        root.addView(purgeContainer)

        // Footer note
        val footerTv = TextView(context).apply {
            text = "Applies cache trimming and dynamic memory compaction. Hardware temperature is tracked live."
            textSize = 8.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        root.addView(footerTv)

        return root
    }

    private fun selectMode(mode: CoolingMode) {
        currentMode = mode
        refreshModeButtons()
        statusTv?.text = mode.name

        scope.launch(Dispatchers.IO) {
            when (mode) {
                CoolingMode.CRYO -> {
                    // Set thermal mitigation properties & drop background load
                    NukeConnectionManager.executeCommand("""
                        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
                        setprop debug.thermal.throttle 1 2>/dev/null
                    """.trimIndent(), 2_000L)
                }
                CoolingMode.HYPER -> {
                    // Max clock profile
                    NukeConnectionManager.executeCommand("""
                        setprop debug.thermal.throttle 0 2>/dev/null
                        setprop debug.sf.hw 1 2>/dev/null
                        setprop debug.egl.hw 1 2>/dev/null
                    """.trimIndent(), 2_000L)
                }
                CoolingMode.AUTO -> {
                    // Adaptive auto-pilot
                    NukeAiSentinel.forceSweepNow(context)
                }
            }
            withContext(Dispatchers.Main) {
                NukeToast.success(context, "${mode.label}: profile applied")
            }
        }
    }

    private fun refreshModeButtons() {
        modeButtons.forEach { (mode, btn) ->
            val isSelected = mode == currentMode
            btn.background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#10B981") else Color.parseColor("#111A24"))
                cornerRadius = 6 * d
                if (!isSelected) setStroke((1 * d).toInt(), Color.parseColor("#1E293B"))
            }
            btn.setTextColor(if (isSelected) Color.parseColor("#06100C") else Color.parseColor("#CBD5E1"))
        }
    }

    private fun executeInstantCryoPurge() {
        purgeBtn?.visibility = View.INVISIBLE
        progressBar?.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            // 1. Kernel memory cache purge
            val dropCacheCmd = "echo 3 > /proc/sys/vm/drop_caches"
            val compactCmd = "echo 1 > /proc/sys/vm/compact_memory"
            NukeConnectionManager.executeCommand("$dropCacheCmd\n$compactCmd", 2_500L)

            // 2. Trigger AI Sentinel deep sweep
            NukeAiSentinel.forceSweepNow(context)

            // 3. Compact RAM via AM
            NukeConnectionManager.executeCommand("am compact-memory all 2>/dev/null", 2_000L)

            delay(600L)

            withContext(Dispatchers.Main) {
                progressBar?.visibility = View.GONE
                purgeBtn?.visibility = View.VISIBLE
                statusTv?.text = "OPTIMIZED"
                statusTv?.setTextColor(Color.parseColor("#10B981"))
                NukeToast.success(context, "Memory and cache optimization completed.")
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val temp = readHardwareTemp()
                withContext(Dispatchers.Main) {
                    tempTv?.text = if (temp > 0f) String.format("%.1f°C", temp) else "N/A"
                    tempTv?.setTextColor(
                        when {
                            temp >= 44f -> Color.parseColor("#EF4444") // Red warm
                            temp >= 40f -> Color.parseColor("#F59E0B") // Amber mild
                            else -> Color.parseColor("#10B981") // Green cool
                        }
                    )
                }
                delay(3_000L)
            }
        }
    }

    private fun readHardwareTemp(): Float {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = androidx.core.content.ContextCompat.registerReceiver(
                context, null, ifilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val raw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (raw > 0) raw / 10.0f else 0f
        } catch (e: Throwable) {
            0f
        }
    }

    private fun setupDrag(dragHandle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            val lp = rootParams ?: return@setOnTouchListener false
            val rv = rootView ?: return@setOnTouchListener false
            val (sw, sh) = getScreenSize()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = startX + (event.rawX - touchX).toInt()
                    val newY = startY + (event.rawY - touchY).toInt()
                    lp.x = newX.coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                    lp.y = newY.coerceIn(0, (sh - (80 * d).toInt()).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(rv, lp) }
                    true
                }
                else -> false
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}
