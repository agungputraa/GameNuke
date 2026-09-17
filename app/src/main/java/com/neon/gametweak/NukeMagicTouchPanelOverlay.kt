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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * NukeMagicTouchPanelOverlay — Floating Touch Listener Sensitivity Studio.
 *
 * Streamlined, high-precision touchscreen control panel:
 *  - Master Switch (Kernel-level libtouch.so interception via privileged Binder/daemon)
 *  - Sensitivitas Sumbu X (1.00x Normal to 3.00x)
 *  - Sensitivitas Sumbu Y (1.00x Normal to 3.00x with 1.00x/1.00x Reset)
 *  - Sensitivitas Detection Zone (Full Screen, Sisi Kanan Aim/Skill, Sisi Kiri)
 *  - Speed Response Curve (Linear 1:1 Natural, Accelerate, Decelerate)
 *  - Macro Studio Quick Access & Multi-Touch Routing Status
 *
 * Guaranteed 100% natural, pixel-perfect touch fidelity (ideal for Mobile Legends / Fanny cables).
 */
class NukeMagicTouchPanelOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val d = context.resources.displayMetrics.density
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null

    // UI references
    private var masterSwitch: Switch? = null
    private var statusBadge: TextView? = null
    private var statusSubtext: TextView? = null
    private var xValBadge: TextView? = null
    private var yValBadge: TextView? = null
    private var xSeekBar: SeekBar? = null
    private var ySeekBar: SeekBar? = null
    private var areaChipViews = mutableListOf<TextView>()
    private var curveChipViews = mutableListOf<TextView>()
    private var macroStatusTv: TextView? = null

    // Live state values (Defaults to clean 1.00x 1:1 stock natural touch)
    private var sensX: Float = 1.00f
    private var sensY: Float = 1.00f
    private var sensArea: Int = NukeTouchTuningEngine.AREA_ALL
    private var curveMode: Int = NukeTouchTuningEngine.CURVE_LINEAR

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeTouchListener"
        private const val PREFS_NAME = "NukeTouchListenerPrefs"

        @Volatile
        private var instance: NukeMagicTouchPanelOverlay? = null

        fun getInstance(context: Context): NukeMagicTouchPanelOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeMagicTouchPanelOverlay(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    init {
        loadPersistedState()
        runCatching {
            context.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                    mainHandler.post { onOrientationChanged() }
                }
                override fun onLowMemory() {}
                override fun onTrimMemory(level: Int) {}
            })
        }
    }

    private fun loadPersistedState() {
        sensX = prefs.getFloat("touch_sens_x", 1.00f).coerceIn(1.0f, 3.5f)
        sensY = prefs.getFloat("touch_sens_y", 1.00f).coerceIn(1.0f, 3.5f)
        sensArea = prefs.getInt("touch_sens_area", NukeTouchTuningEngine.AREA_ALL)
        curveMode = prefs.getInt("touch_curve_mode", NukeTouchTuningEngine.CURVE_LINEAR)

        // Sync to engine runtime (1:1 stock fast-path defaults)
        NukeTouchTuningEngine.xMultiplier = sensX
        NukeTouchTuningEngine.yMultiplier = sensY
        NukeTouchTuningEngine.sensArea = sensArea
        NukeTouchTuningEngine.curveMode = curveMode
        NukeTouchTuningEngine.euroEnabled = false
        NukeTouchTuningEngine.dragShotCurve = false
    }

    private fun persistState() {
        prefs.edit()
            .putFloat("touch_sens_x", sensX)
            .putFloat("touch_sens_y", sensY)
            .putInt("touch_sens_area", sensArea)
            .putInt("touch_curve_mode", curveMode)
            .apply()

        NukeTouchTuningEngine.xMultiplier = sensX
        NukeTouchTuningEngine.yMultiplier = sensY
        NukeTouchTuningEngine.sensArea = sensArea
        NukeTouchTuningEngine.curveMode = curveMode
        NukeTouchTuningEngine.euroEnabled = false
        NukeTouchTuningEngine.dragShotCurve = false

        // Push live values to running daemon/service immediately
        NukeTouchTuningEngine.syncToDaemon(context)
    }

    fun show() {
        if (!NukeSubscriptionManager.isVipActive(context)) {
            NukeToast.error(context, "Game Nuke VIP required to unlock Touch Listener", true)
            return
        }
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) {
            NukeToast.error(context, "Display over other apps permission required for Touch Listener")
            return
        }

        try {
            val panel = buildTouchListenerUi()
            val dm = context.resources.displayMetrics
            val isPortrait = dm.heightPixels > dm.widthPixels
            val initialWidth = if (isPortrait) {
                minOf((340 * d).toInt(), dm.widthPixels - (20 * d).toInt())
            } else {
                minOf((380 * d).toInt(), (dm.widthPixels * 0.52f).toInt())
            }
            val initialHeight = if (isPortrait) {
                minOf((560 * d).toInt(), (dm.heightPixels * 0.82f).toInt())
            } else {
                minOf((440 * d).toInt(), (dm.heightPixels * 0.90f).toInt())
            }
            val initialX = maxOf((8 * d).toInt(), (dm.widthPixels - initialWidth) / 2)
            val initialY = if (isPortrait) (70 * d).toInt() else (20 * d).toInt()

            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            rootParams = params
            rootView = panel
            wm.addView(panel, params)

            updateStatusUi()
            Log.i(TAG, "Touch Listener floating overlay displayed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Touch Listener overlay: ${e.message}", e)
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun hide() {
        rootView?.let { view ->
            runCatching { wm.removeView(view) }
            rootView = null
            rootParams = null
            Log.i(TAG, "Touch Listener floating overlay dismissed")
        }
    }

    fun onOrientationChanged() {
        mainHandler.post {
            val view = rootView ?: return@post
            val lp = rootParams ?: return@post
            val dm = context.resources.displayMetrics
            val isPortrait = dm.heightPixels > dm.widthPixels
            lp.width = if (isPortrait) {
                minOf((340 * d).toInt(), dm.widthPixels - (20 * d).toInt())
            } else {
                minOf((380 * d).toInt(), (dm.widthPixels * 0.52f).toInt())
            }
            lp.height = if (isPortrait) {
                minOf((560 * d).toInt(), (dm.heightPixels * 0.82f).toInt())
            } else {
                minOf((440 * d).toInt(), (dm.heightPixels * 0.90f).toInt())
            }
            lp.x = maxOf((8 * d).toInt(), (dm.widthPixels - lp.width) / 2)
            lp.y = if (isPortrait) (70 * d).toInt() else (20 * d).toInt()
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    private fun updateStatusUi() {
        mainHandler.post {
            val isCoreActive = NukeTouchTuningEngine.isDaemonTouchActive
            if (isCoreActive) {
                statusBadge?.text = "● ACTIVE"
                statusBadge?.setTextColor(Color.parseColor("#10B981"))
                statusSubtext?.text = "Touch Listener Active — Sensi X: ${"%.2f".format(sensX)}x  Y: ${"%.2f".format(sensY)}x"
                statusSubtext?.setTextColor(Color.parseColor("#10B981"))
                masterSwitch?.isChecked = true
            } else {
                statusBadge?.text = "○ STANDBY"
                statusBadge?.setTextColor(Color.parseColor("#64748B"))
                statusSubtext?.text = "Standby — Tap toggle switch to activate"
                statusSubtext?.setTextColor(Color.parseColor("#94A3B8"))
                masterSwitch?.isChecked = false
            }

            // Update Macro status
            val isMacroOpen = runCatching {
                NukeMacroStudioOverlay.getInstance(context).isShowing
            }.getOrDefault(false)
            if (isMacroOpen) {
                macroStatusTv?.text = "● Macro Studio Active on Screen"
                macroStatusTv?.setTextColor(Color.parseColor("#10B981"))
            } else {
                macroStatusTv?.text = "○ Macro Studio Ready to Use"
                macroStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI BUILDER
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildTouchListenerUi(): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA080E18")) // Obsidian glass
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#2500FF88")) // Emerald neon glow
            }
            elevation = 16 * d
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }

        // 1. Draggable Header
        container.addView(buildHeaderBar())

        // 2. Scrollable Touchscreen Body
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Section Cards (Strictly required components)
        body.addView(buildMasterSwitchCard())
        body.addView(spacer(8))
        body.addView(buildSensitivityXCard())
        body.addView(spacer(8))
        body.addView(buildSensitivityYCard())
        body.addView(spacer(8))
        body.addView(buildDetectionAreaCard())
        body.addView(spacer(8))
        body.addView(buildResponseCurveCard())
        body.addView(spacer(8))
        body.addView(buildMacroStudioCard())

        scrollView.addView(body)
        container.addView(scrollView)
        root.addView(container)

        return root
    }

    private fun buildHeaderBar(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * d).toInt())
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(TextView(context).apply {
            text = "TOUCH LISTENER"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
        })

        statusBadge = TextView(context).apply {
            text = if (NukeTouchTuningEngine.isDaemonTouchActive) "● ACTIVE" else "○ STANDBY"
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (NukeTouchTuningEngine.isDaemonTouchActive) Color.parseColor("#10B981") else Color.parseColor("#64748B"))
            setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1500FF88"))
                cornerRadius = 8 * d
                setStroke((1 * d).toInt(), Color.parseColor("#3000FF88"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8 * d).toInt()
            }
        }
        titleRow.addView(statusBadge)
        titleCol.addView(titleRow)

        titleCol.addView(TextView(context).apply {
            text = "Ultra-Precision Touch & Hardware Aim Engine"
            textSize = 8.5f
            setTextColor(Color.parseColor("#64748B"))
        })

        header.addView(titleCol)

        // Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
            }
            setOnClickListener { hide() }
        }
        header.addView(closeBtn)

        // Dragging gesture on header
        var startX = 0f
        var startY = 0f
        var origX = 0
        var origY = 0
        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    origX = rootParams?.x ?: 0
                    origY = rootParams?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    rootParams?.let { params ->
                        params.x = origX + (ev.rawX - startX).toInt()
                        params.y = origY + (ev.rawY - startY).toInt()
                        rootView?.let { v -> wm.updateViewLayout(v, params) }
                    }
                    true
                }
                else -> false
            }
        }

        return header
    }

    private fun buildMasterSwitchCard(): View {
        val card = cardLayout()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        textCol.addView(TextView(context).apply {
            text = "Touch Listener Engine"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        })

        statusSubtext = TextView(context).apply {
            text = if (NukeTouchTuningEngine.isDaemonTouchActive)
                "Touch Listener Active — Sensi X: ${"%.2f".format(sensX)}x  Y: ${"%.2f".format(sensY)}x"
            else
                "Standby — Tap toggle switch to activate"
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        textCol.addView(statusSubtext)
        row.addView(textCol)

        masterSwitch = Switch(context).apply {
            isChecked = NukeTouchTuningEngine.isDaemonTouchActive || prefs.getBoolean("touch_listener_active", false)
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#155E75"))

            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("touch_listener_active", isChecked).apply()
                if (isChecked) {
                    statusSubtext?.text = "⌛ Connecting to Touch Listener..."
                    statusSubtext?.setTextColor(Color.parseColor("#F59E0B"))
                    statusBadge?.text = "● STARTING..."
                    statusBadge?.setTextColor(Color.parseColor("#F59E0B"))

                    NukeTouchTuningEngine.startDaemonTouchAsync(context) { ok ->
                        mainHandler.post {
                            if (ok) {
                                statusBadge?.text = "● ACTIVE"
                                statusBadge?.setTextColor(Color.parseColor("#10B981"))
                                statusSubtext?.text = "✓ Touch Listener Active — Native 1:1 hardware aim engaged"
                                statusSubtext?.setTextColor(Color.parseColor("#10B981"))
                                masterSwitch?.isChecked = true
                                prefs.edit().putBoolean("touch_listener_active", true).apply()
                            } else {
                                statusBadge?.text = "⚠ STANDBY"
                                statusBadge?.setTextColor(Color.parseColor("#EF4444"))
                                statusSubtext?.text = "Failed to start Touch Listener. Connect Shizuku or ADB first."
                                statusSubtext?.setTextColor(Color.parseColor("#EF4444"))
                                masterSwitch?.isChecked = false
                                prefs.edit().putBoolean("touch_listener_active", false).apply()
                            }
                        }
                    }
                } else {
                    statusBadge?.text = "○ STANDBY"
                    statusBadge?.setTextColor(Color.parseColor("#64748B"))
                    statusSubtext?.text = "Touch Listener disabled (System default input active)"
                    statusSubtext?.setTextColor(Color.parseColor("#94A3B8"))
                    NukeTouchTuningEngine.stopDaemonTouchAsync()
                    NukeTouchTuningEngine.resetToSystemDefaults(context)
                }
            }
        }
        row.addView(masterSwitch)
        card.addView(row)

        return card
    }

    private fun buildSensitivityXCard(): View {
        val card = cardLayout()

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = "X-Axis Sensitivity (Horizontal)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        xValBadge = TextView(context).apply {
            text = if (sensX == 1.0f) "1.00x (Native 1:1)" else "${"%.2f".format(sensX)}x"
            textSize = 10.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (sensX == 1.0f) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(if (sensX == 1.0f) Color.parseColor("#1500FF88") else Color.parseColor("#1538BDF8"))
                cornerRadius = 6 * d
            }
        }
        headerRow.addView(xValBadge)
        card.addView(headerRow)

        card.addView(TextView(context).apply {
            text = "Horizontal swipe speed multiplier. 1.00x is native display hardware accuracy (100% stable)."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        // Slider row with [-] and [+] fine-tuning
        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = TextView(context).apply {
            text = "−"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
            setOnClickListener {
                sensX = (sensX - 0.02f).coerceIn(1.0f, 3.0f)
                updateXBadge()
                xSeekBar?.progress = (((sensX - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
                persistState()
            }
        }
        sliderRow.addView(minusBtn)

        xSeekBar = SeekBar(context).apply {
            max = 100
            progress = (((sensX - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * d).toInt()
                marginEnd = (4 * d).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sensX = (1.0f + (prog * 0.02f)).coerceIn(1.0f, 3.0f)
                        updateXBadge()
                        NukeTouchTuningEngine.xMultiplier = sensX
                        NukeTouchTuningEngine.syncToDaemon(context)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    persistState()
                }
            })
        }
        sliderRow.addView(xSeekBar)

        val plusBtn = TextView(context).apply {
            text = "+"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
            setOnClickListener {
                sensX = (sensX + 0.02f).coerceIn(1.0f, 3.0f)
                updateXBadge()
                xSeekBar?.progress = (((sensX - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
                persistState()
            }
        }
        sliderRow.addView(plusBtn)

        card.addView(sliderRow)
        return card
    }

    private fun updateXBadge() {
        xValBadge?.text = if (sensX == 1.0f) "1.00x (Normal 1:1)" else "${"%.2f".format(sensX)}x"
        xValBadge?.setTextColor(if (sensX == 1.0f) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
        xValBadge?.background = GradientDrawable().apply {
            setColor(if (sensX == 1.0f) Color.parseColor("#1500FF88") else Color.parseColor("#1538BDF8"))
            cornerRadius = 6 * d
        }
    }

    private fun buildSensitivityYCard(): View {
        val card = cardLayout()

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = "Y-Axis Sensitivity (Vertical)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        yValBadge = TextView(context).apply {
            text = if (sensY == 1.0f) "1.00x (Native 1:1)" else "${"%.2f".format(sensY)}x"
            textSize = 10.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (sensY == 1.0f) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(if (sensY == 1.0f) Color.parseColor("#1500FF88") else Color.parseColor("#1538BDF8"))
                cornerRadius = 6 * d
            }
        }
        headerRow.addView(yValBadge)
        card.addView(headerRow)

        card.addView(TextView(context).apply {
            text = "Vertical swipe speed multiplier. 1.00x is native display response (aspect ratio compensated)."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        // Slider row with [-] and [+] fine-tuning
        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = TextView(context).apply {
            text = "−"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
            setOnClickListener {
                sensY = (sensY - 0.02f).coerceIn(1.0f, 3.0f)
                updateYBadge()
                ySeekBar?.progress = (((sensY - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
                persistState()
            }
        }
        sliderRow.addView(minusBtn)

        ySeekBar = SeekBar(context).apply {
            max = 100
            progress = (((sensY - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * d).toInt()
                marginEnd = (4 * d).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sensY = (1.0f + (prog * 0.02f)).coerceIn(1.0f, 3.0f)
                        updateYBadge()
                        NukeTouchTuningEngine.yMultiplier = sensY
                        NukeTouchTuningEngine.syncToDaemon(context)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    persistState()
                }
            })
        }
        sliderRow.addView(ySeekBar)

        val plusBtn = TextView(context).apply {
            text = "+"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
            setOnClickListener {
                sensY = (sensY + 0.02f).coerceIn(1.0f, 3.0f)
                updateYBadge()
                ySeekBar?.progress = (((sensY - 1.0f) / 0.02f).toInt()).coerceIn(0, 100)
                persistState()
            }
        }
        sliderRow.addView(plusBtn)

        card.addView(sliderRow)

        // Reset to Stock Normal Button
        card.addView(spacer(8))
        val resetBtn = TextView(context).apply {
            text = "↺  Reset Screen to Native (1.00x / 1.00x Linear)"
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F291E"))
                cornerRadius = 8 * d
                setStroke((1 * d).toInt(), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                sensX = 1.00f
                sensY = 1.00f
                sensArea = NukeTouchTuningEngine.AREA_ALL
                curveMode = NukeTouchTuningEngine.CURVE_LINEAR
                updateXBadge()
                updateYBadge()
                xSeekBar?.progress = 0
                ySeekBar?.progress = 0
                updateAreaChips()
                updateCurveChips()
                persistState()
                NukeToast.success(context, "Screen normalized 100% (Native 1:1 precision active)")
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(resetBtn)

        return card
    }

    private fun updateYBadge() {
        yValBadge?.text = if (sensY == 1.0f) "1.00x (Native 1:1)" else "${"%.2f".format(sensY)}x"
        yValBadge?.setTextColor(if (sensY == 1.0f) Color.parseColor("#10B981") else Color.parseColor("#38BDF8"))
        yValBadge?.background = GradientDrawable().apply {
            setColor(if (sensY == 1.0f) Color.parseColor("#1500FF88") else Color.parseColor("#1538BDF8"))
            cornerRadius = 6 * d
        }
    }

    private fun buildDetectionAreaCard(): View {
        val card = cardLayout()

        card.addView(TextView(context).apply {
            text = "SENSITIVITY DETECTION ZONE"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })
        card.addView(TextView(context).apply {
            text = "Select active screen zone for sensitivity scaling:\n• Right Side (Aim/Skill): Recommended for MOBA/FPS — Left joystick remains 100% native.\n• Entire Screen: Universal sensitivity across all display regions."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val options = listOf(
            Triple(NukeTouchTuningEngine.AREA_ALL, "Entire Screen", "Full Screen"),
            Triple(NukeTouchTuningEngine.AREA_RIGHT, "Right Side (Aim)", "Skill & Aim"),
            Triple(NukeTouchTuningEngine.AREA_LEFT, "Left Side", "Joystick")
        )

        areaChipViews.clear()
        options.forEach { (code, title, _) ->
            val chip = TextView(context).apply {
                text = title
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * d).toInt()
                }
                setOnClickListener {
                    sensArea = code
                    updateAreaChips()
                    persistState()
                }
            }
            areaChipViews.add(chip)
            row.addView(chip)
        }
        card.addView(row)
        updateAreaChips()

        return card
    }

    private fun updateAreaChips() {
        val options = listOf(
            NukeTouchTuningEngine.AREA_ALL,
            NukeTouchTuningEngine.AREA_RIGHT,
            NukeTouchTuningEngine.AREA_LEFT
        )
        areaChipViews.forEachIndexed { i, chip ->
            val code = options.getOrNull(i) ?: -1
            val selected = (code == sensArea)
            chip.setTextColor(if (selected) Color.parseColor("#080E18") else Color.parseColor("#E2E8F0"))
            chip.background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#10B981") else Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
                if (!selected) setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
        }
    }

    private fun buildResponseCurveCard(): View {
        val card = cardLayout()

        card.addView(TextView(context).apply {
            text = "SPEED RESPONSE CURVE"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })
        card.addView(TextView(context).apply {
            text = "Swipe speed response dynamics:\n• Linear (1:1): Natural response with zero deviation. Highly recommended for esports precision.\n• Accelerate: Fast swipes receive progressive acceleration boost.\n• Decelerate: Dampened high-speed flicks to prevent aim overshoot."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val curves = listOf(
            Pair(NukeTouchTuningEngine.CURVE_LINEAR, "Linear (1:1)"),
            Pair(NukeTouchTuningEngine.CURVE_ACCELERATE, "Accelerate"),
            Pair(NukeTouchTuningEngine.CURVE_DECELERATE, "Decelerate")
        )

        curveChipViews.clear()
        curves.forEach { (code, title) ->
            val chip = TextView(context).apply {
                text = title
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * d).toInt()
                }
                setOnClickListener {
                    curveMode = code
                    updateCurveChips()
                    persistState()
                }
            }
            curveChipViews.add(chip)
            row.addView(chip)
        }
        card.addView(row)
        updateCurveChips()

        return card
    }

    private fun updateCurveChips() {
        val curves = listOf(
            NukeTouchTuningEngine.CURVE_LINEAR,
            NukeTouchTuningEngine.CURVE_ACCELERATE,
            NukeTouchTuningEngine.CURVE_DECELERATE
        )
        curveChipViews.forEachIndexed { i, chip ->
            val code = curves.getOrNull(i) ?: -1
            val selected = (code == curveMode)
            chip.setTextColor(if (selected) Color.parseColor("#080E18") else Color.parseColor("#E2E8F0"))
            chip.background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
                if (!selected) setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
        }
    }

    private fun buildMacroStudioCard(): View {
        val card = cardLayout()

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = "MACRO STUDIO"
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#C084FC"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        macroStatusTv = TextView(context).apply {
            text = "● Ready"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
        }
        headerRow.addView(macroStatusTv)
        card.addView(headerRow)

        card.addView(TextView(context).apply {
            text = "On-screen touch button automation (Spam Tap, Hold, Combo, Swipe). Independent multi-touch routing guarantees gaming controls remain 100% responsive."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (8 * d).toInt())
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Primary Launch Macro Studio Button
        val launchBtn = TextView(context).apply {
            text = "🎯 Open Macro Studio Overlay"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#080E18"))
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A855F7"))
                cornerRadius = 8 * d
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * d).toInt()
            }
            setOnClickListener {
                try {
                    val macro = NukeMacroStudioOverlay.getInstance(context)
                    macro.show()
                    updateStatusUi()
                    NukeToast.success(context, "Macro Studio Overlay opened")
                } catch (t: Throwable) {
                    NukeToast.error(context, "Failed to open Macro Studio: ${t.message}")
                }
            }
        }
        btnRow.addView(launchBtn)

        // Close / Hide Macro Studio Button
        val hideBtn = TextView(context).apply {
            text = "✕ Close"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
                setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                try {
                    val macro = NukeMacroStudioOverlay.getInstance(context)
                    macro.hide()
                    updateStatusUi()
                    NukeToast.success(context, "Macro Studio hidden")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to hide macro studio: ${t.message}")
                }
            }
        }
        btnRow.addView(hideBtn)

        card.addView(btnRow)

        return card
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun cardLayout(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131B2A"))
                cornerRadius = 10 * d
                setStroke((1 * d).toInt(), Color.parseColor("#1E293B"))
            }
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
    }

    private fun spacer(dp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (dp * d).toInt())
        }
    }
}
