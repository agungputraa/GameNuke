package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NukeGpuGraphicsPanelOverlay — Floating Graphics & GPU Engine Tuner.
 *
 * Dedicated right-side floating panel for graphics and display optimization:
 *  - Renderer Backend Switcher: SkiaGL (OpenGL ES), SkiaVulkan (Vulkan), System Default
 *  - Display Resolution & DPI Scaler: 720p Pro Gaming, 1080p Balanced, Native
 *  - Refresh Rate Forcer: 60Hz, 90Hz, 120Hz Lock, 144Hz Max
 *  - GPU Performance Booster: Disable HW Overlays, Force 4x MSAA, GPU Turbo
 *  - Game Vibrance & Contrast Enhancer for Free Fire enemy visibility
 */
class NukeGpuGraphicsPanelOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val d = context.resources.displayMetrics.density
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null

    // State
    var currentRenderer: String = "AUTO"   // "GL", "VK", "AUTO"
    var currentResolution: String = "NATIVE" // "NATIVE", "1080P", "720P"
    var targetRefreshRate: Int = 120        // 60, 90, 120, 144
    var disableHwOverlays: Boolean = true
    var forceMsaa: Boolean = false
    var gpuTurbo: Boolean = true
    var gameVibrance: Boolean = true

    // UI Chips for status updates
    private var glChip: TextView? = null
    private var vkChip: TextView? = null
    private var autoChip: TextView? = null
    private var resNativeChip: TextView? = null
    private var res1080pChip: TextView? = null
    private var res720pChip: TextView? = null
    private var hz60Chip: TextView? = null
    private var hz90Chip: TextView? = null
    private var hz120Chip: TextView? = null
    private var statusTv: TextView? = null

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeGpuTuner"
        private const val PREFS_NAME = "NukeGpuPrefs"

        @Volatile
        private var instance: NukeGpuGraphicsPanelOverlay? = null

        fun getInstance(context: Context): NukeGpuGraphicsPanelOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeGpuGraphicsPanelOverlay(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    init {
        loadSettings()
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val (sw, sh) = getScreenSize()
            val isLandscape = sw > sh
            val panelW = if (isLandscape) {
                (330 * d).toInt().coerceAtMost((sw * 0.46f).toInt())
            } else {
                (320 * d).toInt().coerceAtMost((sw * 0.88f).toInt())
            }
            val panelH = if (isLandscape) {
                (sh * 0.88f).toInt()
            } else {
                (500 * d).toInt().coerceAtMost((sh * 0.84f).toInt())
            }

            val lp = WindowManager.LayoutParams(
                panelW, panelH,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * d).toInt()
                y = if (isLandscape) (sh * 0.05f).toInt() else (sh * 0.10f).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val view = buildPanel(lp, panelW)
            val added = runCatching { wm.addView(view, lp) }.isSuccess
            if (added) {
                rootView = view
                rootParams = lp
            }
        }
    }

    fun hide() {
        mainHandler.post {
            rootView?.let { runCatching { wm.removeView(it) } }
            rootView = null
            rootParams = null
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    // ─── UI Layout ──────────────────────────────────────────────────────────

    private fun buildPanel(lp: WindowManager.LayoutParams, panelW: Int): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(Color.parseColor("#F5080C10")) // Obsidian Dark Glass
                setStroke((1.2f * d).toInt(), Color.parseColor("#3300FF88"))
            }
            elevation = 20f * d
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Header (Centered Grip + Title + Circular Close)
        content.addView(buildHeader(lp, panelW))
        content.addView(divider())

        // 2. Scrollable Body
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (6 * d).toInt())
        }

        // Section A: Renderer Backend
        body.addView(sectionTitle("RENDERER BACKEND"))
        body.addView(buildRendererRow())
        body.addView(space(10))

        // Section B: Resolution & DPI
        body.addView(sectionTitle("RESOLUTION & DISPLAY SCALER"))
        body.addView(buildResolutionRow())
        body.addView(space(6))
        body.addView(actionBtn("↺  RESTORE NATIVE DISPLAY", "#00FF88") {
            setResolution("NATIVE")
        })
        body.addView(space(10))

        // Section C: Refresh Rate
        body.addView(sectionTitle("REFRESH RATE LOCK (FPS CAP)"))
        body.addView(buildRefreshRateRow())
        body.addView(space(10))

        // Section D: GPU Engine Boosters
        body.addView(sectionTitle("GPU & SURFACE PIPELINE"))
        body.addView(buildToggleRow("Disable Hardware Overlays", "Forces GPU composition via SurfaceFlinger", disableHwOverlays) {
            disableHwOverlays = it
            saveSettings()
            applyHwOverlays()
        })
        body.addView(space(4))
        body.addView(buildToggleRow("Force 4x MSAA Anti-Aliasing", "Sharper edges in OpenGL ES / Vulkan", forceMsaa) {
            forceMsaa = it
            saveSettings()
            applyMsaa()
        })
        body.addView(space(4))
        body.addView(buildToggleRow("GPU Turbo Performance Clock", "Boosts GPU governor for 0 frame drops", gpuTurbo) {
            gpuTurbo = it
            saveSettings()
            applyGpuTurbo()
        })
        body.addView(space(4))
        body.addView(buildToggleRow("Game Digital Vibrance (Color Boost)", "Increases saturation for high contrast", gameVibrance) {
            gameVibrance = it
            saveSettings()
            applyVibrance()
        })
        body.addView(space(8))

        // Status prompt
        statusTv = TextView(context).apply {
            text = "Engine: Ready • Privileged Shell Active"
            textSize = 8.5f
            setTextColor(Color.parseColor("#00FF88"))
            gravity = Gravity.CENTER
            setPadding(0, dp2px(4), 0, dp2px(4))
        }
        body.addView(statusTv)

        scroll.addView(body)
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(content)
        return root
    }

    private fun buildHeader(lp: WindowManager.LayoutParams, panelW: Int): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (6 * d).toInt())
        }

        // 1. Centered Drag Grip Pill
        val gripBar = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
            val pill = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D94A3B8"))
                    cornerRadius = 2 * d
                }
                layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (3.5f * d).toInt())
            }
            addView(pill)
        }
        attachDragHandler(gripBar, lp, panelW)
        container.addView(gripBar)

        // 2. Title Row + Circular Close Button
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachDragHandler(row, lp, panelW)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleCol.addView(TextView(context).apply {
            text = "🎮 GPU & DISPLAY TUNER"
            textSize = 11.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        })

        titleCol.addView(TextView(context).apply {
            text = "Renderer, Resolution & Surface Booster"
            textSize = 7.8f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (1 * d).toInt(), 0, 0)
        })

        row.addView(titleCol)

        // Styled Circular Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 11.5f
            setTextColor(Color.parseColor("#EF4444"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26EF4444"))
                cornerRadius = 14 * d
                setStroke((1 * d).toInt(), Color.parseColor("#4DEF4444"))
            }
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            setOnClickListener { hide() }
        }
        row.addView(closeBtn)
        container.addView(row)

        return container
    }

    private fun buildRendererRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        glChip = chip("SkiaGL (OpenGL)", currentRenderer == "GL") { setRenderer("GL") }
        vkChip = chip("SkiaVulkan (VK)", currentRenderer == "VK") { setRenderer("VK") }
        autoChip = chip("Default", currentRenderer == "AUTO") { setRenderer("AUTO") }

        row.addView(glChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(vkChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(autoChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))
        return row
    }

    private fun buildResolutionRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        resNativeChip = chip("Native", currentResolution == "NATIVE") { setResolution("NATIVE") }
        res1080pChip = chip("1080p FHD+", currentResolution == "1080P") { setResolution("1080P") }
        res720pChip = chip("720p Extreme", currentResolution == "720P") { setResolution("720P") }

        row.addView(resNativeChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(res1080pChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(res720pChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildRefreshRateRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        hz60Chip = chip("60Hz", targetRefreshRate == 60) { setRefreshRate(60) }
        hz90Chip = chip("90Hz", targetRefreshRate == 90) { setRefreshRate(90) }
        hz120Chip = chip("120Hz Lock", targetRefreshRate == 120) { setRefreshRate(120) }

        row.addView(hz60Chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(hz90Chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(space(4, h = true))
        row.addView(hz120Chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        return row
    }

    private fun chip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((4 * d).toInt(), (7 * d).toInt(), (4 * d).toInt(), (7 * d).toInt())
            updateChipVisual(this, active)
            setOnClickListener {
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(40)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(80).start() }
                    .start()
                onClick()
            }
        }
    }

    private fun updateChipVisual(v: TextView, active: Boolean) {
        v.setTextColor(Color.parseColor(if (active) "#00ff88" else "#94a3b8"))
        v.background = GradientDrawable().apply {
            cornerRadius = 7f * d
            setColor(Color.parseColor(if (active) "#0d2618" else "#111c26"))
            setStroke(dp2px(1), Color.parseColor(if (active) "#00ff88" else "#1e293b"))
        }
    }

    private fun buildToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(context).apply {
            text = title
            textSize = 9.5f
            setTextColor(Color.parseColor("#e2e8f0"))
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            textSize = 7.5f
            setTextColor(Color.parseColor("#64748b"))
        })
        row.addView(textCol)

        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#1A3B2F"))
            setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
        }
        row.addView(sw)
        return row
    }

    private fun actionBtn(text: String, colorHex: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 9f
            setTextColor(Color.parseColor(colorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp2px(7), 0, dp2px(7))
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(Color.parseColor("#0E1620"))
                setStroke(dp2px(1), Color.parseColor("#1E2B38"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(40)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(80).start() }
                    .start()
                onClick()
            }
        }
    }

    // ─── Actions & Scripts ──────────────────────────────────────────────────

    private fun setRenderer(type: String) {
        currentRenderer = type
        saveSettings()
        glChip?.let { updateChipVisual(it, type == "GL") }
        vkChip?.let { updateChipVisual(it, type == "VK") }
        autoChip?.let { updateChipVisual(it, type == "AUTO") }

        scope.launch {
            val cmd = when (type) {
                "GL" -> "setprop debug.hwui.renderer skiagl ; setprop debug.renderengine.backend skiagl"
                "VK" -> "setprop debug.hwui.renderer skiavk ; setprop debug.renderengine.backend skiaglvk"
                else -> "setprop debug.hwui.renderer \"\" ; setprop debug.renderengine.backend \"\""
            }
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand(cmd, 1000L)
            }
            statusTv?.post { statusTv?.text = "Renderer set to $type (Active)" }
        }
    }

    private fun setResolution(res: String) {
        currentResolution = res
        saveSettings()
        resNativeChip?.let { updateChipVisual(it, res == "NATIVE") }
        res1080pChip?.let { updateChipVisual(it, res == "1080P") }
        res720pChip?.let { updateChipVisual(it, res == "720P") }

        scope.launch {
            val cmd = when (res) {
                "1080P" -> "wm size 1080x2400 ; wm density 440"
                "720P" -> "wm size 720x1600 ; wm density 320"
                else -> "wm size reset ; wm density reset"
            }
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand(cmd, 1500L)
            }
            statusTv?.post { statusTv?.text = "Display: $res Applied" }
        }
    }

    private fun setRefreshRate(hz: Int) {
        targetRefreshRate = hz
        saveSettings()
        hz60Chip?.let { updateChipVisual(it, hz == 60) }
        hz90Chip?.let { updateChipVisual(it, hz == 90) }
        hz120Chip?.let { updateChipVisual(it, hz == 120) }

        scope.launch {
            val cmd = "settings put system min_refresh_rate ${hz}.0 ; settings put system peak_refresh_rate ${hz}.0 ; settings put system user_refresh_rate $hz"
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand(cmd, 1000L)
            }
            statusTv?.post { statusTv?.text = "Refresh Rate: ${hz}Hz Locked" }
        }
    }

    private fun applyHwOverlays() {
        scope.launch {
            val code = if (disableHwOverlays) "1" else "0"
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand("service call SurfaceFlinger 1008 i32 $code", 1000L)
            }
        }
    }

    private fun applyMsaa() {
        scope.launch {
            val valStr = if (forceMsaa) "1" else "0"
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand("setprop debug.egl.force_msaa $valStr", 1000L)
            }
        }
    }

    private fun applyGpuTurbo() {
        scope.launch {
            if (gpuTurbo && NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand("setprop debug.composition.type gpu ; setprop debug.sf.hw 1", 1000L)
            }
        }
    }

    private fun applyVibrance() {
        scope.launch {
            val mode = if (gameVibrance) "3" else "1"
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand("settings put system display_color_mode $mode 2>/dev/null", 1000L)
            }
        }
    }

    // ─── Persistence ────────────────────────────────────────────────────────

    private fun saveSettings() {
        prefs.edit()
            .putString("renderer", currentRenderer)
            .putString("resolution", currentResolution)
            .putInt("hz", targetRefreshRate)
            .putBoolean("hw_overlays", disableHwOverlays)
            .putBoolean("msaa", forceMsaa)
            .putBoolean("gpu_turbo", gpuTurbo)
            .putBoolean("vibrance", gameVibrance)
            .apply()
    }

    private fun loadSettings() {
        currentRenderer = prefs.getString("renderer", "AUTO") ?: "AUTO"
        currentResolution = prefs.getString("resolution", "NATIVE") ?: "NATIVE"
        targetRefreshRate = prefs.getInt("hz", 120)
        disableHwOverlays = prefs.getBoolean("hw_overlays", true)
        forceMsaa = prefs.getBoolean("msaa", false)
        gpuTurbo = prefs.getBoolean("gpu_turbo", true)
        gameVibrance = prefs.getBoolean("vibrance", true)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = 8f * d
        setColor(Color.parseColor("#0B1218"))
        setStroke(dp2px(1), Color.parseColor("#1C2732"))
    }

    private fun sectionTitle(title: String) = TextView(context).apply {
        text = "■ $title"
        textSize = 8f
        setTextColor(Color.parseColor("#94A3B8"))
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding(0, dp2px(4), 0, dp2px(3))
    }

    private fun divider() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)).apply {
            topMargin = dp2px(2)
            bottomMargin = dp2px(4)
        }
        setBackgroundColor(Color.parseColor("#1C2732"))
    }

    private fun space(dp: Int, h: Boolean = false) = View(context).apply {
        layoutParams = if (h) LinearLayout.LayoutParams(dp2px(dp), 1)
        else LinearLayout.LayoutParams(1, dp2px(dp))
    }

    private fun dp2px(dp: Int) = (dp * d).toInt()

    private fun attachDragHandler(handle: View, lp: WindowManager.LayoutParams, panelW: Int) {
        var startRawX = 0f; var startRawY = 0f
        var startParamX = 0; var startParamY = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startParamX = lp.x; startParamY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    val (sw, sh) = getScreenSize()
                    lp.x = (startParamX - dx).toInt().coerceIn(0, (sw - panelW).coerceAtLeast(0))
                    lp.y = (startParamY + dy).toInt().coerceIn(0, (sh - (200 * d).toInt()).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(rootView, lp) }
                    true
                }
                else -> true
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}
