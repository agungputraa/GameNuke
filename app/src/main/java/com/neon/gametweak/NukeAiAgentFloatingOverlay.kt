package com.neon.gametweak

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * NukeAiAgentFloatingOverlay — High-Tech Gaming HUD for the Autonomous AI Agent.
 *
 * Professional Minimalist Redesign:
 * - Draggable cybernetic glassmorphic HUD.
 * - Live hardware telemetry metrics (SoC, Thermals, Governor, RAM).
 * - "⚡ AI TURBO BOOST MAX" action button with cooldown enforcement.
 * - Minimalist Phase Pipeline Cards with active animated spinners on running processes.
 * - Expandable Dropdown / Accordion for in-depth phase telemetry and command outputs.
 * - Toggleable Raw Console Output with full vertical scrolling.
 * - 100% English base language for seamless translation integration.
 * - Green process immunity text removed for a sleek, clean, esports HUD aesthetic.
 */
class NukeAiAgentFloatingOverlay private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NukeAiAgentOverlay"

        @Volatile
        private var instance: NukeAiAgentFloatingOverlay? = null

        fun getInstance(context: Context): NukeAiAgentFloatingOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeAiAgentFloatingOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var stateObserverJob: Job? = null

    // UI elements
    private var boostBtn: Button? = null
    private var progressBar: ProgressBar? = null
    private var statusTv: TextView? = null
    private var diagnosisTv: TextView? = null
    private var socTv: TextView? = null
    private var tempTv: TextView? = null
    private var ramTv: TextView? = null
    private var govTv: TextView? = null

    // Prominent Loading Diagnostic HUD (Displayed in body during isBusy)
    private var loadingHudCard: LinearLayout? = null
    private var loadingStageTv: TextView? = null
    private var loadingActionTv: TextView? = null
    private var loadingPill1: TextView? = null
    private var loadingPill2: TextView? = null
    private var loadingPill3: TextView? = null
    private var loadingPill4: TextView? = null
    private var loadingPill5: TextView? = null  // Stage 5: AI Validation
    private var liveTelemetrySocTv: TextView? = null
    private var liveTelemetryRamTv: TextView? = null
    private var liveTelemetryFpsTv: TextView? = null
    private var liveTelemetryThermalTv: TextView? = null
    private var selfHealingWarningBox: LinearLayout? = null
    private var selfHealingTextTv: TextView? = null
    private var validationResultCard: LinearLayout? = null
    private var validationScoreTv: TextView? = null
    private var validationSummaryTv: TextView? = null
    private var modeDescriptionTv: TextView? = null
    private var modeFxTv: TextView? = null
    private var adaptivePlanTitleTv: TextView? = null
    private val modeButtons = linkedMapOf<NukeAiThemeController.Mode, TextView>()

    // AI Neural Model UI
    private var titleTv: TextView? = null
    private var modelActiveTv: TextView? = null
    private var modelDescriptionTv: TextView? = null
    private val modelButtons = linkedMapOf<String, TextView>()

    // Terminal & Phases UI
    private var masterScrollView: ScrollView? = null
    private var phasesContainer: LinearLayout? = null
    private var rawTerminalContainer: LinearLayout? = null
    private var phasesTabBtn: TextView? = null
    private var consoleTabBtn: TextView? = null
    private var isConsoleViewActive: Boolean = false

    // Track which phase accordions are expanded (defaults to running phase)
    private val expandedPhases = mutableSetOf<Int>()
    private var lastRunningPhaseId: Int = -1
    private var lastPhasesSignature: String = ""
    private var lastRenderedLogsCount: Int = -1

    init {
        runCatching {
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    mainHandler.post { updateDimensionsForOrientation() }
                }
                override fun onLowMemory() {}
                override fun onTrimMemory(level: Int) {}
            })
        }
    }

    private fun updateDimensionsForOrientation() {
        val v = rootView ?: return
        val p = rootParams ?: return
        val dm = context.resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val width = if (isLandscape) {
            minOf((430 * d).toInt(), (dm.widthPixels * 0.54f).toInt())
        } else {
            minOf((352 * d).toInt(), (dm.widthPixels * 0.92f).toInt())
        }.coerceAtLeast(minOf((286 * d).toInt(), (dm.widthPixels * 0.80f).toInt()))
        val height = if (isLandscape) {
            minOf((390 * d).toInt(), (dm.heightPixels * 0.90f).toInt())
        } else {
            minOf((530 * d).toInt(), (dm.heightPixels * 0.74f).toInt())
        }.coerceAtLeast(minOf((280 * d).toInt(), (dm.heightPixels * 0.56f).toInt()))
        p.width = width
        p.height = height
        runCatching { wm.updateViewLayout(v, p) }
    }

    val isShowing: Boolean
        get() = rootView != null && rootView?.isAttachedToWindow == true

    fun show() {
        mainHandler.post {
            if (isShowing) return@post

            if (!NukeSubscriptionManager.isVipActive(context) || IntegrityGuard.isCompromised()) {
                Toast.makeText(context, "VIP Exclusive: Active subscription required for AI Agent.", Toast.LENGTH_SHORT).show()
                return@post
            }

            try {
                NukeAiAgentEngine.init(context)
                buildAndAttachView()
                observeState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show AI Agent overlay", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            stateObserverJob?.cancel()
            stateObserverJob = null
            runCatching {
                val v = rootView ?: return@runCatching
                if (v.isAttachedToWindow) {
                    wm.removeView(v)
                }
            }
            rootView = null
            rootParams = null
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAndAttachView() {
        val dm = context.resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val width = if (isLandscape) {
            minOf((430 * d).toInt(), (dm.widthPixels * 0.54f).toInt())
        } else {
            minOf((352 * d).toInt(), (dm.widthPixels * 0.92f).toInt())
        }.coerceAtLeast(minOf((286 * d).toInt(), (dm.widthPixels * 0.80f).toInt()))
        val height = if (isLandscape) {
            minOf((390 * d).toInt(), (dm.heightPixels * 0.90f).toInt())
        } else {
            minOf((530 * d).toInt(), (dm.heightPixels * 0.74f).toInt())
        }.coerceAtLeast(minOf((280 * d).toInt(), (dm.heightPixels * 0.56f).toInt()))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH

        rootParams = WindowManager.LayoutParams(
            width,
            height,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = NukeCyberHudStyler.TacticalPanelDrawable(
                density = d,
                cornerRadiusPx = 16 * d,
                strokeColor = NukeCyberHudStyler.COLOR_CYAN_NEON,
                bgColor = NukeCyberHudStyler.COLOR_BG_OBSIDIAN,
                showGrid = true,
                showBrackets = true
            )
            elevation = 12 * d
        }

        // ── Sticky Slim Header (Draggable) ─────────────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_RAISED)
                cornerRadii = floatArrayOf(
                    12 * d, 12 * d,
                    12 * d, 12 * d,
                    0f, 0f,
                    0f, 0f
                )
            }
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            val p = rootParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initialX + (event.rawX - initialTouchX).toInt()
                    p.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { wm.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }

        val title = TextView(context).apply {
            text = "⚡ NEXUS NEURAL CORE"
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleTv = title
        header.addView(title)

        val vipBadge = TextView(context).apply {
            text = "VIP"
            setTextColor(Color.parseColor("#FFB84A"))
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26FFB830"))
                cornerRadius = 3 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#FFB84A"))
            }
            setPadding((5 * d).toInt(), (1.5f * d).toInt(), (5 * d).toInt(), (1.5f * d).toInt())
        }
        header.addView(vipBadge)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            setOnClickListener { hide() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Subtle 1dp divider
        val headerDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())
            setBackgroundColor(NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
        }
        root.addView(headerDivider)

        // ── Unified Master ScrollView ───────────────────────────────────────
        val masterScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        masterScrollView = masterScroll

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── 1. Hardware Metrics Bar ────────────────────────────────────────
        val metricsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * d).toInt())
        }

        socTv = createMetricBadge("SoC: ...")
        metricsRow.addView(socTv)

        tempTv = createMetricBadge("Temp: ...")
        metricsRow.addView(tempTv)

        govTv = createMetricBadge("Gov: ...")
        metricsRow.addView(govTv)

        ramTv = createMetricBadge("RAM: ...")
        metricsRow.addView(ramTv)

        scrollContent.addView(metricsRow)

        // ── 2. Adaptive AI performance profile ─────────────────────────────
        val modeCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = NukeCyberHudStyler.buildCardBackground(
                density = d,
                cornerRadiusDp = 10f,
                strokeColor = NukeCyberHudStyler.COLOR_BORDER_SUBTLE,
                fillColor = NukeCyberHudStyler.COLOR_BG_CARD
            )
            setPadding((8 * d).toInt(), (7 * d).toInt(), (8 * d).toInt(), (7 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (7 * d).toInt() }
        }
        val modeTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeTop.addView(TextView(context).apply {
            text = "AI PERFORMANCE PROFILE"
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_PRIMARY)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        modeFxTv = TextView(context).apply {
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 8f
            typeface = Typeface.MONOSPACE
        }
        modeTop.addView(modeFxTv)
        modeCard.addView(modeTop)

        val modeScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (5 * d).toInt())
        }
        modeButtons.clear()
        NukeAiThemeController.Mode.entries.forEach { mode ->
            val button = TextView(context).apply {
                text = mode.title.uppercase(Locale.US)
                textSize = 8.2f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                minWidth = (76 * d).toInt()
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (5 * d).toInt() }
                setOnClickListener {
                    NukeAiAgentEngine.setPerformanceMode(context, mode)
                    refreshModeUi(mode)
                    mainHandler.postDelayed({ rebuildForTheme() }, 80L)
                }
            }
            modeButtons[mode] = button
            modeRow.addView(button)
        }
        modeScroller.addView(modeRow)
        modeCard.addView(modeScroller)
        modeDescriptionTv = TextView(context).apply {
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 8.8f
            maxLines = 2
        }
        modeCard.addView(modeDescriptionTv)
        scrollContent.addView(modeCard)
        refreshModeUi(NukeAiThemeController.currentMode)

        // ── 2b. AI Neural Model Selection Card ─────────────────────────────
        val modelCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = NukeCyberHudStyler.buildCardBackground(
                density = d,
                cornerRadiusDp = 10f,
                strokeColor = NukeCyberHudStyler.COLOR_BORDER_SUBTLE,
                fillColor = NukeCyberHudStyler.COLOR_BG_CARD
            )
            setPadding((8 * d).toInt(), (7 * d).toInt(), (8 * d).toInt(), (7 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (7 * d).toInt() }
        }
        val modelTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modelTop.addView(TextView(context).apply {
            text = "AI NEURAL ENGINE MODEL"
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_PRIMARY)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        modelActiveTv = TextView(context).apply {
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 8f
            typeface = Typeface.MONOSPACE
        }
        modelTop.addView(modelActiveTv)
        modelCard.addView(modelTop)

        val modelScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val modelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (5 * d).toInt())
        }
        val availableModels = listOf(
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_FAST_11B, "LLAMA 11B", "Meta LLaMA 3.2 11B • Ultra-Fast Hardware Tuning (<2.5s)"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_DEEPSEEK_V4, "DEEPSEEK V4", "DeepSeek V4.1 Flash • Deep Reasoning & Structured Optimizer"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_GLM, "GLM 5.3", "Z-AI GLM 5.3 Flash • Neural Reasoning Engine"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_GPT_OSS, "GPT-OSS 20B", "OpenAI GPT-OSS 20B • High-Speed Hardware Diagnostics"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_SUPER_120B, "SUPER 120B", "NVIDIA Nemotron 3 Super 120B • Enterprise MoE Engine")
        )
        modelButtons.clear()
        availableModels.forEach { (modelId, label, _) ->
            val button = TextView(context).apply {
                text = label
                textSize = 8.2f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                minWidth = (84 * d).toInt()
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (5 * d).toInt() }
                setOnClickListener {
                    NukeAiAgentEngine.setActiveModel(context, modelId)
                    refreshModelUi(modelId)
                }
            }
            modelButtons[modelId] = button
            modelRow.addView(button)
        }
        modelScroller.addView(modelRow)
        modelCard.addView(modelScroller)

        modelDescriptionTv = TextView(context).apply {
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 8.8f
            maxLines = 2
        }
        modelCard.addView(modelDescriptionTv)
        scrollContent.addView(modelCard)
        refreshModelUi(NukeAiAgentEngine.getActiveModel(context))

        // ── 3. AI Turbo Boost MAX Button & Progress ────────────────────────
        val btnContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        }

        boostBtn = Button(context).apply {
            text = "⚡ AI TURBO BOOST MAX"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                cornerRadius = 8 * d
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (38 * d).toInt()
            )
            setOnClickListener {
                NukeAiAgentEngine.startAutonomousOptimization(context) { ok, msg ->
                    mainHandler.post {
                        NukeToast.fromResult(context, ok, msg)
                    }
                }
            }
        }
        btnContainer.addView(boostBtn)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * d).toInt()
            ).apply {
                topMargin = (4 * d).toInt()
            }
        }
        btnContainer.addView(progressBar)

        statusTv = TextView(context).apply {
            text = "● STANDBY — Ready to audit CPU, GPU, RAM & FPS pacing"
            setTextColor(NukeAiThemeController.currentPalette.accentBright)
            textSize = 9.5f
            gravity = Gravity.CENTER
            setPadding(0, (3 * d).toInt(), 0, (2 * d).toInt())
        }
        btnContainer.addView(statusTv)

        scrollContent.addView(btnContainer)

        // ── 4. Autonomous lifecycle + live diagnostic HUD ───────────
        loadingHudCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 8 * d
                setStroke((1f * d).toInt(), NukeCyberHudStyler.COLOR_CYAN_NEON)
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * d).toInt()
            }
        }

        val loadingHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (5 * d).toInt())
        }

        val loadingSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(NukeCyberHudStyler.COLOR_CYAN_NEON)
            layoutParams = LinearLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            }
        }
        loadingHeaderRow.addView(loadingSpinner)

        loadingStageTv = TextView(context).apply {
            text = "⚡ [STAGE 1/5]: DUMPING HARDWARE"
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        loadingHeaderRow.addView(loadingStageTv)
        loadingHudCard?.addView(loadingHeaderRow)

        val pillsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (5 * d).toInt())
        }
        loadingPill1 = createStagePill("SCAN")
        pillsRow.addView(loadingPill1)
        loadingPill2 = createStagePill("REASON")
        pillsRow.addView(loadingPill2)
        loadingPill3 = createStagePill("DESIGN")
        pillsRow.addView(loadingPill3)
        loadingPill4 = createStagePill("EXEC")
        pillsRow.addView(loadingPill4)
        loadingPill5 = createStagePill("VERIFY")
        pillsRow.addView(loadingPill5)
        loadingHudCard?.addView(pillsRow)

        val liveTelemetryGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_OBSIDIAN)
                cornerRadius = 5 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#16222F"))
            }
            setPadding((6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (5 * d).toInt()
            }
        }

        val telemetryRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        liveTelemetrySocTv = TextView(context).apply {
            text = "SoC: Loading..."
            setTextColor(NukeCyberHudStyler.COLOR_TELEMETRY)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        telemetryRow1.addView(liveTelemetrySocTv)

        liveTelemetryRamTv = TextView(context).apply {
            text = "RAM: Reading..."
            setTextColor(NukeAiThemeController.currentPalette.accentBright)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        telemetryRow1.addView(liveTelemetryRamTv)
        liveTelemetryGrid.addView(telemetryRow1)

        val telemetryRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * d).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        liveTelemetryFpsTv = TextView(context).apply {
            text = "FPS Cap: Targeting..."
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        telemetryRow2.addView(liveTelemetryFpsTv)

        liveTelemetryThermalTv = TextView(context).apply {
            text = "Thermals: Probing..."
            setTextColor(Color.parseColor("#F472B6"))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        telemetryRow2.addView(liveTelemetryThermalTv)
        liveTelemetryGrid.addView(telemetryRow2)
        loadingHudCard?.addView(liveTelemetryGrid)

        loadingActionTv = TextView(context).apply {
            text = "▶ Probing hardware parameters & OEM kernel profile..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            maxLines = 2
            setPadding((2 * d).toInt(), 0, (2 * d).toInt(), (2 * d).toInt())
        }
        loadingHudCard?.addView(loadingActionTv)

        selfHealingWarningBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26F59E0B"))
                cornerRadius = 4 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#F59E0B"))
            }
            setPadding((6 * d).toInt(), (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * d).toInt()
            }
        }
        selfHealingTextTv = TextView(context).apply {
            text = "🔍 Self-Healing: Querying AOSP kernel index..."
            setTextColor(Color.parseColor("#FCD34D"))
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
        }
        selfHealingWarningBox?.addView(selfHealingTextTv)
        loadingHudCard?.addView(selfHealingWarningBox)

        scrollContent.addView(loadingHudCard)

        // ── 4. Validation Result Card ──────────────────────────────────────
        validationResultCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 8 * d
                setStroke((1f * d).toInt(), NukeAiThemeController.currentPalette.accentBright)
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * d).toInt() }
        }
        val valHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        val valTitleTv = TextView(context).apply {
            text = "🤖 AI CONSENSUS VALIDATION"
            setTextColor(NukeAiThemeController.currentPalette.accentBright)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        valHeader.addView(valTitleTv)
        validationScoreTv = TextView(context).apply {
            text = "--/100"
            setTextColor(NukeAiThemeController.currentPalette.accentBright)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        valHeader.addView(validationScoreTv)
        validationResultCard?.addView(valHeader)
        validationSummaryTv = TextView(context).apply {
            text = ""
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            maxLines = 4
        }
        validationResultCard?.addView(validationSummaryTv)
        scrollContent.addView(validationResultCard)

        // ── 5. Minimalist Diagnosis Capsule ────────────────────────────────
        diagnosisTv = TextView(context).apply {
            text = "⚡ Nexus Neural Core initialized. Tap 'AI TURBO BOOST MAX' to begin cognitive hardware analysis."
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 9.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F1622"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * d).toInt()
            }
        }
        scrollContent.addView(diagnosisTv)

        // ── 6. Mode Switch Bar: [ PHASES ] | [ RAW LOGS ] ──────────────────
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        }

        phasesTabBtn = TextView(context).apply {
            text = "PROCESS PHASES"
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A10B981"))
                cornerRadius = 5 * d
                setStroke((1f * d).toInt(), NukeCyberHudStyler.COLOR_CYAN_NEON)
            }
            setPadding((8 * d).toInt(), (3.5f * d).toInt(), (8 * d).toInt(), (3.5f * d).toInt())
            setOnClickListener { switchViewMode(false) }
        }
        tabRow.addView(phasesTabBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((6 * d).toInt(), 1)
        }
        tabRow.addView(spacer)

        consoleTabBtn = TextView(context).apply {
            text = "RAW CONSOLE"
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 5 * d
                setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
            setPadding((8 * d).toInt(), (3.5f * d).toInt(), (8 * d).toInt(), (3.5f * d).toInt())
            setOnClickListener { switchViewMode(true) }
        }
        tabRow.addView(consoleTabBtn)

        scrollContent.addView(tabRow)

        // ── 7. Container 1: Minimalist Process Phases (Accordion List) ─────
        adaptivePlanTitleTv = TextView(context).apply {
            text = "ADAPTIVE AI PLAN • waiting for telemetry"
            setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = .04f
            setPadding((2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
        }
        scrollContent.addView(adaptivePlanTitleTv)

        phasesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(phasesContainer)

        // ── 8. Container 2: Raw Terminal Console ───────────────────────────
        rawTerminalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#080C10"))
                cornerRadius = 7 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#1B2430"))
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(rawTerminalContainer)

        masterScroll.addView(scrollContent)
        root.addView(masterScroll)

        rootView = root
        wm.addView(root, rootParams)
    }

    private fun switchViewMode(showConsole: Boolean) {
        isConsoleViewActive = showConsole
        if (showConsole) {
            phasesContainer?.visibility = View.GONE
            rawTerminalContainer?.visibility = View.VISIBLE
            consoleTabBtn?.setTextColor(NukeCyberHudStyler.COLOR_TELEMETRY)
            consoleTabBtn?.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A38BDF8"))
                cornerRadius = 5 * d
                setStroke((1f * d).toInt(), NukeCyberHudStyler.COLOR_TELEMETRY)
            }
            phasesTabBtn?.setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            phasesTabBtn?.background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 5 * d
                setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
            renderRawTerminal(NukeAiAgentEngine.state.value.terminalLogs, force = true)
        } else {
            phasesContainer?.visibility = View.VISIBLE
            rawTerminalContainer?.visibility = View.GONE
            phasesTabBtn?.setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
            phasesTabBtn?.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A10B981"))
                cornerRadius = 5 * d
                setStroke((1f * d).toInt(), NukeCyberHudStyler.COLOR_CYAN_NEON)
            }
            consoleTabBtn?.setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            consoleTabBtn?.background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 5 * d
                setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
            renderPhases(NukeAiAgentEngine.state.value.phases, force = true)
        }
    }

    private fun refreshModeUi(mode: NukeAiThemeController.Mode) {
        val palette = NukeAiThemeController.currentPalette
        modeButtons.forEach { (candidate, view) ->
            val selected = candidate == mode
            view.setTextColor(if (selected) palette.background else NukeCyberHudStyler.COLOR_TEXT_MUTED)
            view.background = GradientDrawable().apply {
                setColor(if (selected) palette.accent else NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 7 * d
                setStroke((0.8f * d).toInt().coerceAtLeast(1), if (selected) palette.accentBright else NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
        }
        modeDescriptionTv?.text = "${mode.description}  ${mode.behaviorHint}."
        modeDescriptionTv?.setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
        modeFxTv?.text = "${mode.shortLabel} • ${palette.fxLabel}"
        modeFxTv?.setTextColor(palette.accent)
    }

    private fun refreshModelUi(selectedModel: String) {
        val availableModels = listOf(
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_FAST_11B, "LLAMA 11B", "Meta LLaMA 3.2 11B • Ultra-Fast Hardware Tuning (<2.5s)"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_DEEPSEEK_V4, "DEEPSEEK V4", "DeepSeek V4.1 Flash • Deep Reasoning & Structured Optimizer"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_GLM, "GLM 5.3", "Z-AI GLM 5.3 Flash • Neural Reasoning Engine"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_GPT_OSS, "GPT-OSS 20B", "OpenAI GPT-OSS 20B • High-Speed Hardware Diagnostics"),
            Triple(NukeAiAgentEngine.NVIDIA_NIM_MODEL_SUPER_120B, "SUPER 120B", "NVIDIA Nemotron 3 Super 120B • Enterprise MoE Engine")
        )
        val selected = availableModels.firstOrNull { it.first == selectedModel } ?: availableModels.first()

        modelActiveTv?.text = selected.second
        modelDescriptionTv?.text = selected.third
        titleTv?.text = "⚡ NEXUS NEURAL CORE • ${selected.second}"

        modelButtons.forEach { (modelId, btn) ->
            val isSelected = modelId == selected.first
            if (isSelected) {
                btn.setTextColor(Color.WHITE)
                btn.background = GradientDrawable().apply {
                    setColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                    cornerRadius = 6 * d
                    setStroke((1.2f * d).toInt(), Color.parseColor("#80FFFFFF"))
                }
            } else {
                btn.setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                btn.background = GradientDrawable().apply {
                    setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                    cornerRadius = 6 * d
                    setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
                }
            }
        }
    }

    private fun rebuildForTheme() {
        val oldView = rootView ?: return
        val oldParams = rootParams ?: return
        val oldX = oldParams.x
        val oldY = oldParams.y
        stateObserverJob?.cancel()
        stateObserverJob = null
        runCatching { if (oldView.isAttachedToWindow) wm.removeViewImmediate(oldView) }
        rootView = null
        rootParams = null
        runCatching {
            buildAndAttachView()
            rootParams?.let { p ->
                p.x = oldX
                p.y = oldY
                rootView?.let { wm.updateViewLayout(it, p) }
            }
            observeState()
        }.onFailure { Log.e(TAG, "Failed to rebuild AI Agent theme", it) }
    }

    private fun createMetricBadge(initialText: String): TextView {
        return TextView(context).apply {
            text = initialText
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_RAISED)
                cornerRadius = 6 * d
            }
            setPadding((6 * d).toInt(), (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = (4 * d).toInt()
            }
            gravity = Gravity.CENTER
        }
    }

    private fun createStagePill(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                cornerRadius = 4 * d
                setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
            }
            setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (3 * d).toInt()
            }
        }
    }

    private fun updateStagePills(currentStage: Int) {
        val pills = listOf(loadingPill1, loadingPill2, loadingPill3, loadingPill4, loadingPill5)
        pills.forEachIndexed { index, pill ->
            val stageNum = index + 1
            if (stageNum == currentStage) {
                pill?.setTextColor(Color.WHITE)
                pill?.background = GradientDrawable().apply {
                    setColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                    cornerRadius = 4 * d
                }
            } else if (stageNum < currentStage) {
                pill?.setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                pill?.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A10B981"))
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_CYAN_NEON)
                }
            } else {
                pill?.setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                pill?.background = GradientDrawable().apply {
                    setColor(NukeCyberHudStyler.COLOR_BG_CARD_ALT)
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
                }
            }
        }
    }

    private fun observeState() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            NukeAiAgentEngine.state.collectLatest { s ->
                val isBusy = s.isAnalyzing || s.isOptimizing
                refreshModeUi(s.activeMode)
                refreshModelUi(NukeAiAgentEngine.getActiveModel(context))
                modeButtons.values.forEach { button ->
                    button.isEnabled = !isBusy
                    button.alpha = if (isBusy) 0.58f else 1f
                }
                modelButtons.values.forEach { button ->
                    button.isEnabled = !isBusy
                    button.alpha = if (isBusy) 0.58f else 1f
                }
                val adaptiveCount = s.phases.count { it.id > 1 }
                adaptivePlanTitleTv?.text = if (adaptiveCount > 0) {
                    "ADAPTIVE AI PLAN • $adaptiveCount DEVICE-SPECIFIC MODULES"
                } else {
                    "ADAPTIVE AI PLAN • ANALYZING DEVICE CONDITIONS"
                }

                socTv?.text = s.telemetry.socName.take(9)
                tempTv?.text = "${String.format(Locale.US, "%.1f", s.telemetry.thermalTempC)}°C"
                govTv?.text = s.telemetry.cpuGovernor.take(8)
                ramTv?.text = "${s.telemetry.ramFreeMb}M"

                if (isBusy) {
                    loadingHudCard?.visibility = View.VISIBLE
                    diagnosisTv?.visibility = View.GONE
                    validationResultCard?.visibility = View.GONE
                    progressBar?.visibility = View.VISIBLE
                    boostBtn?.isEnabled = false
                    boostBtn?.text = "AI OPTIMIZING..."
                    boostBtn?.background = GradientDrawable().apply {
                        setColor(NukeCyberHudStyler.COLOR_CYAN_DIM)
                        cornerRadius = 10 * d
                    }
                    statusTv?.text = s.progressText

                    val stage = s.activeStage.coerceIn(1, 5)
                    loadingStageTv?.text = if (s.activeStageTitle.isNotBlank()) {
                        "⚡ LIFECYCLE $stage/5 • ${s.activeStageTitle}"
                    } else {
                        "⚡ LIFECYCLE $stage/5 • AI OPTIMIZATION ACTIVE"
                    }

                    loadingActionTv?.text = s.currentActionDetail.ifBlank { s.progressText }
                    updateStagePills(stage)

                    liveTelemetrySocTv?.text = "SoC: ${s.telemetry.socName.take(13)} (${s.telemetry.cpuCores}C)"
                    liveTelemetryRamTv?.text = "RAM: ${s.telemetry.ramFreeMb}M Free (${s.telemetry.deviceTier.take(8)})"
                    liveTelemetryFpsTv?.text = "FPS Cap: ${s.telemetry.maxSupportedRefreshRate}Hz Target"
                    liveTelemetryThermalTv?.text = "Temp: ${String.format(Locale.US, "%.1f", s.telemetry.thermalTempC)}°C"

                    if (s.selfHealingActive) {
                        selfHealingWarningBox?.visibility = View.VISIBLE
                        selfHealingTextTv?.text = s.selfHealingDetail.ifBlank { "Resolving obstacle via AOSP kernel index..." }
                    } else {
                        selfHealingWarningBox?.visibility = View.GONE
                    }
                } else {
                    loadingHudCard?.visibility = View.GONE
                    diagnosisTv?.visibility = View.VISIBLE
                    selfHealingWarningBox?.visibility = View.GONE

                    // Show validation result card if available
                    if (s.validation.isValidated) {
                        validationResultCard?.visibility = View.VISIBLE
                        val scoreColor = when {
                            s.validation.consensusScore >= 75 -> "#68F59A"
                            s.validation.consensusScore >= 50 -> "#FBBF24"
                            else -> "#9CB8AD"
                        }
                        validationScoreTv?.setTextColor(Color.parseColor(scoreColor))
                        validationScoreTv?.text = "${s.validation.consensusScore}/100"
                        validationSummaryTv?.text = s.validation.debateSummary.take(220)
                    } else {
                        validationResultCard?.visibility = View.GONE
                    }

                    if (s.isCooldownActive) {
                        progressBar?.visibility = View.GONE
                        boostBtn?.isEnabled = false
                        boostBtn?.text = "COOLDOWN (${s.cooldownSecondsRemaining}s)"
                        boostBtn?.background = GradientDrawable().apply {
                            setColor(NukeCyberHudStyler.COLOR_BORDER_SUBTLE)
                            cornerRadius = 10 * d
                        }
                        statusTv?.text = "Cooldown active (${s.cooldownSecondsRemaining}s)"
                    } else {
                        progressBar?.visibility = View.GONE
                        boostBtn?.isEnabled = true
                        boostBtn?.text = "⚡ AI TURBO BOOST MAX"
                        boostBtn?.background = GradientDrawable().apply {
                            setColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                            cornerRadius = 10 * d
                        }
                        statusTv?.text = if (s.executionSuccess) {
                            "● SYSTEM NOMINAL — Nexus Neural Optimization Active"
                        } else {
                            s.progressText
                        }
                    }
                }

                // Minimalist Diagnosis Capsule
                val diagShort = if (s.diagnosisReport.length > 110) {
                    s.diagnosisReport.take(110) + "..."
                } else {
                    s.diagnosisReport
                }
                diagnosisTv?.text = diagShort

                // Auto-expand currently running phase once when it starts
                s.phases.find { it.status == NukeAiAgentEngine.PhaseStatus.RUNNING }?.let { running ->
                    if (lastRunningPhaseId != running.id) {
                        lastRunningPhaseId = running.id
                        expandedPhases.add(running.id)
                    }
                }

                // ── Render Minimalist Phase Cards with Spinners & Dropdowns ──
                renderPhases(s.phases)

                // ── Render Raw Terminal Output ──────────────────────────────
                renderRawTerminal(s.terminalLogs)
            }
        }
    }

    private fun renderPhases(phases: List<NukeAiAgentEngine.AgentPhase>, force: Boolean = false) {
        val container = phasesContainer ?: return
        val currentSig = phases.joinToString(";") { "${it.id}:${it.status}:${it.subtitle}:${it.details.size}:${expandedPhases.contains(it.id)}" }
        if (!force && currentSig == lastPhasesSignature && container.childCount > 0) return
        lastPhasesSignature = currentSig

        container.removeAllViews()

        phases.forEach { phase ->
            val isExpanded = expandedPhases.contains(phase.id)

            val phaseCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(NukeCyberHudStyler.COLOR_BG_CARD)
                    cornerRadius = 8 * d
                    val strokeColor = when (phase.status) {
                        NukeAiAgentEngine.PhaseStatus.RUNNING -> NukeCyberHudStyler.COLOR_CYAN_NEON
                        NukeAiAgentEngine.PhaseStatus.COMPLETED -> NukeCyberHudStyler.COLOR_BORDER_SUBTLE
                        NukeAiAgentEngine.PhaseStatus.FAILED -> Color.parseColor("#EF4444")
                        NukeAiAgentEngine.PhaseStatus.PENDING -> Color.parseColor("#161E28")
                    }
                    setStroke((0.9f * d).toInt(), strokeColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * d).toInt()
                }
            }

            // Clickable Header Row
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                isClickable = true
                setOnClickListener {
                    if (isExpanded) {
                        expandedPhases.remove(phase.id)
                    } else {
                        expandedPhases.add(phase.id)
                    }
                    renderPhases(phases, force = true)
                }
            }

            // Indicator: Animated Spinner if RUNNING, Checkmark if COMPLETED, Dot if PENDING
            when (phase.status) {
                NukeAiAgentEngine.PhaseStatus.RUNNING -> {
                    val spinner = ProgressBar(context).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(NukeCyberHudStyler.COLOR_CYAN_NEON)
                        layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply {
                            marginEnd = (8 * d).toInt()
                        }
                    }
                    headerRow.addView(spinner)
                }
                NukeAiAgentEngine.PhaseStatus.COMPLETED -> {
                    val checkTv = TextView(context).apply {
                        text = "✔"
                        setTextColor(NukeCyberHudStyler.COLOR_CYAN_NEON)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = (8 * d).toInt()
                        }
                    }
                    headerRow.addView(checkTv)
                }
                NukeAiAgentEngine.PhaseStatus.FAILED -> {
                    val failTv = TextView(context).apply {
                        text = "✖"
                        setTextColor(Color.parseColor("#EF4444"))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = (8 * d).toInt()
                        }
                    }
                    headerRow.addView(failTv)
                }
                NukeAiAgentEngine.PhaseStatus.PENDING -> {
                    val dotTv = TextView(context).apply {
                        text = "○"
                        setTextColor(Color.parseColor("#475569"))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = (8 * d).toInt()
                        }
                    }
                    headerRow.addView(dotTv)
                }
            }

            // Title & Subtitle Column
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(context).apply {
                text = "${phase.id}. ${phase.title}"
                setTextColor(
                    when (phase.status) {
                        NukeAiAgentEngine.PhaseStatus.RUNNING -> NukeCyberHudStyler.COLOR_CYAN_NEON
                        NukeAiAgentEngine.PhaseStatus.COMPLETED -> Color.parseColor("#E2E8F0")
                        else -> NukeCyberHudStyler.COLOR_TEXT_MUTED
                    }
                )
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            textCol.addView(titleTv)

            val subTv = TextView(context).apply {
                text = phase.subtitle
                setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                textSize = 9.5f
            }
            textCol.addView(subTv)
            headerRow.addView(textCol)

            // Dropdown Toggle Arrow (Accordion Indicator)
            val arrowTv = TextView(context).apply {
                text = if (isExpanded) "▲" else "▼"
                setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                textSize = 10f
                setPadding((4 * d).toInt(), 0, (2 * d).toInt(), 0)
            }
            headerRow.addView(arrowTv)
            phaseCard.addView(headerRow)

            // Dropdown Accordion Content (Expandable Details)
            if (isExpanded) {
                val detailBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(NukeCyberHudStyler.COLOR_BG_OBSIDIAN)
                        cornerRadius = 6 * d
                    }
                    setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins((10 * d).toInt(), 0, (10 * d).toInt(), (8 * d).toInt())
                    }
                }

                if (phase.details.isEmpty()) {
                    val noDetailTv = TextView(context).apply {
                        text = if (phase.status == NukeAiAgentEngine.PhaseStatus.PENDING) "Awaiting pipeline execution..." else "Telemetry verified. No anomalies."
                        setTextColor(Color.parseColor("#475569"))
                        textSize = 9.5f
                        typeface = Typeface.MONOSPACE
                    }
                    detailBox.addView(noDetailTv)
                } else {
                    phase.details.forEach { item ->
                        val itemTv = TextView(context).apply {
                            text = "• $item"
                            setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                            textSize = 9.5f
                            typeface = Typeface.MONOSPACE
                            setPadding(0, (1.5f * d).toInt(), 0, (1.5f * d).toInt())
                        }
                        detailBox.addView(itemTv)
                    }
                }
                phaseCard.addView(detailBox)
            }

            container.addView(phaseCard)
        }
    }

    private fun renderRawTerminal(logs: List<NukeAiAgentEngine.TerminalLine>, force: Boolean = false) {
        val container = rawTerminalContainer ?: return
        if (!isConsoleViewActive && !force) return
        if (!force && logs.size == lastRenderedLogsCount && container.childCount > 0) return
        lastRenderedLogsCount = logs.size

        // Only auto-scroll down if user was already at the bottom (or first lines)
        val wasAtBottom = masterScrollView?.let { sv ->
            !sv.canScrollVertically(1)
        } ?: true

        container.removeAllViews()

        if (logs.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "No shell commands executed yet.\nTap 'AI TURBO BOOST MAX' to start."
                setTextColor(Color.parseColor("#475569"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
            }
            container.addView(emptyTv)
        } else {
            logs.takeLast(60).forEach { log ->
                val cmdTv = TextView(context).apply {
                    text = "[${log.timestamp}] $ ${log.command}"
                    setTextColor(if (log.isSuccess) NukeCyberHudStyler.COLOR_TELEMETRY else Color.parseColor("#F87171"))
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                }
                container.addView(cmdTv)

                if (log.output.isNotBlank()) {
                    val outTv = TextView(context).apply {
                        text = log.output
                        setTextColor(NukeCyberHudStyler.COLOR_TEXT_MUTED)
                        textSize = 9f
                        typeface = Typeface.MONOSPACE
                        setPadding((8 * d).toInt(), 0, 0, (2 * d).toInt())
                    }
                    container.addView(outTv)
                }
            }
        }

        if (isConsoleViewActive && (wasAtBottom || logs.size <= 2 || force)) {
            masterScrollView?.post {
                masterScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
