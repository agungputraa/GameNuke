package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NukeMagicTouchPanelOverlay — Floating Touch Listener Sensitivity Studio.
 *
 * Built strictly from scratch focusing 100% on touchscreen tuning:
 *  - Touch Listener Master Switch (Kernel-level libtouch.so interception via privileged Binder/daemon)
 *  - Independent X & Y Axis Sensitivity Multipliers (1.00x to 3.50x)
 *  - Sensitivity Detection Area (Right Half, Full Screen, Left Half)
 *  - Speed Response Curves (Accelerate, Linear, Decelerate)
 *  - 1€ OneEuro Micro-Jitter Smoothing (Cutoff & Beta fine-tuning)
 *  - Relative Aim Mode (Edge gliding)
 *  - System Pointer Speed (-7 to +7) & Touch Calibration (slop, pressure, pointer_speed)
 *  - Drag Shot DPI Elevated Density (+0, +40, +80, +120)
 *  - Interactive Live Touchpad Canvas (Real-time tracking, delta X/Y readout, multiplier feedback)
 */
class NukeMagicTouchPanelOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var pointerValBadge: TextView? = null
    private var pointerSeekBar: SeekBar? = null
    private var jitterSwitch: Switch? = null
    private var relativeAimSwitch: Switch? = null
    private var dpiStatusTv: TextView? = null
    private var dpiChipViews = mutableListOf<TextView>()
    private var areaChipViews = mutableListOf<TextView>()
    private var curveChipViews = mutableListOf<TextView>()

    // Live state values
    private var sensX: Float = 1.80f
    private var sensY: Float = 2.20f
    private var sensArea: Int = NukeTouchTuningEngine.AREA_RIGHT
    private var curveMode: Int = NukeTouchTuningEngine.CURVE_ACCELERATE
    private var jitterSmoothing: Boolean = true
    private var jitterCutoff: Float = 1.0f
    private var jitterBeta: Float = 0.007f
    private var relativeAim: Boolean = true
    private var pointerSpeed: Int = 0
    private var physicalDpi: Int = 0
    private var currentDpiOffset: Int = 0

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
    }

    private fun loadPersistedState() {
        sensX = prefs.getFloat("touch_sens_x", NukeTouchTuningEngine.xMultiplier).coerceIn(1.0f, 3.5f)
        sensY = prefs.getFloat("touch_sens_y", NukeTouchTuningEngine.yMultiplier).coerceIn(1.0f, 3.5f)
        sensArea = prefs.getInt("touch_sens_area", NukeTouchTuningEngine.sensArea)
        curveMode = prefs.getInt("touch_curve_mode", NukeTouchTuningEngine.curveMode)
        jitterSmoothing = prefs.getBoolean("touch_jitter_smooth", NukeTouchTuningEngine.euroEnabled)
        jitterCutoff = prefs.getFloat("touch_jitter_cutoff", NukeTouchTuningEngine.euroMinCutoff)
        jitterBeta = prefs.getFloat("touch_jitter_beta", NukeTouchTuningEngine.euroBeta)
        relativeAim = prefs.getBoolean("touch_relative_aim", true)
        pointerSpeed = prefs.getInt("touch_pointer_speed", 0)

        // Sync to engine runtime
        NukeTouchTuningEngine.xMultiplier = sensX
        NukeTouchTuningEngine.yMultiplier = sensY
        NukeTouchTuningEngine.sensArea = sensArea
        NukeTouchTuningEngine.curveMode = curveMode
        NukeTouchTuningEngine.euroEnabled = jitterSmoothing
        NukeTouchTuningEngine.euroMinCutoff = jitterCutoff
        NukeTouchTuningEngine.euroBeta = jitterBeta
    }

    private fun persistState() {
        prefs.edit()
            .putFloat("touch_sens_x", sensX)
            .putFloat("touch_sens_y", sensY)
            .putInt("touch_sens_area", sensArea)
            .putInt("touch_curve_mode", curveMode)
            .putBoolean("touch_jitter_smooth", jitterSmoothing)
            .putFloat("touch_jitter_cutoff", jitterCutoff)
            .putFloat("touch_jitter_beta", jitterBeta)
            .putBoolean("touch_relative_aim", relativeAim)
            .putInt("touch_pointer_speed", pointerSpeed)
            .apply()

        NukeTouchTuningEngine.xMultiplier = sensX
        NukeTouchTuningEngine.yMultiplier = sensY
        NukeTouchTuningEngine.sensArea = sensArea
        NukeTouchTuningEngine.curveMode = curveMode
        NukeTouchTuningEngine.euroEnabled = jitterSmoothing
        NukeTouchTuningEngine.euroMinCutoff = jitterCutoff
        NukeTouchTuningEngine.euroBeta = jitterBeta
        NukeTouchTuningEngine.syncToDaemon(context)
    }

    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) {
            NukeToast.error(context, "Izin Display over other apps diperlukan untuk Touch Listener")
            return
        }

        try {
            val panel = buildTouchListenerUi()
            val initialWidth = (340 * d).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels - (16 * d).toInt())
            val initialHeight = (520 * d).toInt().coerceAtMost((context.resources.displayMetrics.heightPixels * 0.82f).toInt())

            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (context.resources.displayMetrics.widthPixels - initialWidth) / 2
                y = (100 * d).toInt()
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

    private fun updateStatusUi() {
        mainHandler.post {
            val isCoreActive = NukeTouchTuningEngine.isDaemonTouchActive
            if (isCoreActive) {
                statusBadge?.text = "● ACTIVE"
                statusBadge?.setTextColor(Color.parseColor("#00FF88"))
                statusSubtext?.text = "Kernel Touch Listener aktif — X: ${"%.2f".format(sensX)}x  Y: ${"%.2f".format(sensY)}x"
                statusSubtext?.setTextColor(Color.parseColor("#00FF88"))
                masterSwitch?.isChecked = true
            } else {
                statusBadge?.text = "○ STANDBY"
                statusBadge?.setTextColor(Color.parseColor("#64748B"))
                statusSubtext?.text = "Standby — Ketuk tombol di samping untuk mengaktifkan"
                statusSubtext?.setTextColor(Color.parseColor("#94A3B8"))
                masterSwitch?.isChecked = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI BUILDER (Built from 0 — Touch Screen Components Only)
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

        // Section Cards (All Touchscreen Exclusive)
        body.addView(buildMasterSwitchCard())
        body.addView(spacer(8))
        body.addView(buildSensitivitySlidersCard())
        body.addView(spacer(8))
        body.addView(buildDetectionAreaCard())
        body.addView(spacer(8))
        body.addView(buildResponseCurveCard())
        body.addView(spacer(8))
        body.addView(buildJitterFilterCard())
        body.addView(spacer(8))
        body.addView(buildSystemTouchCalibrationCard())
        body.addView(spacer(8))
        body.addView(buildInteractiveTouchpadCard())

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

        // Drag handle bar
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
            setTextColor(Color.parseColor("#00FF88"))
        })

        statusBadge = TextView(context).apply {
            text = if (NukeTouchTuningEngine.isDaemonTouchActive) "● ACTIVE" else "○ STANDBY"
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (NukeTouchTuningEngine.isDaemonTouchActive) Color.parseColor("#00FF88") else Color.parseColor("#64748B"))
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
            text = "Hardware In-Game Touch Multiplier Studio"
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
                "Kernel Touch Listener aktif — X: ${"%.2f".format(sensX)}x  Y: ${"%.2f".format(sensY)}x"
            else
                "Standby — Ketuk tombol di samping untuk mengaktifkan"
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        textCol.addView(statusSubtext)
        row.addView(textCol)

        masterSwitch = Switch(context).apply {
            isChecked = NukeTouchTuningEngine.isDaemonTouchActive || prefs.getBoolean("touch_listener_active", false)
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#155E75"))

            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("touch_listener_active", isChecked).apply()
                if (isChecked) {
                    statusSubtext?.text = "⌛ Menghubungkan ke Touch Listener Kernel..."
                    statusSubtext?.setTextColor(Color.parseColor("#F59E0B"))
                    statusBadge?.text = "● STARTING..."
                    statusBadge?.setTextColor(Color.parseColor("#F59E0B"))

                    NukeTouchTuningEngine.startDaemonTouchAsync(context) { ok ->
                        mainHandler.post {
                            if (ok) {
                                statusBadge?.text = "● ACTIVE"
                                statusBadge?.setTextColor(Color.parseColor("#00FF88"))
                                statusSubtext?.text = "✓ Touch Listener Aktif — Sensitivitas in-game bekerja live"
                                statusSubtext?.setTextColor(Color.parseColor("#00FF88"))
                                masterSwitch?.isChecked = true
                                prefs.edit().putBoolean("touch_listener_active", true).apply()
                            } else {
                                statusBadge?.text = "⚠ STANDBY"
                                statusBadge?.setTextColor(Color.parseColor("#EF4444"))
                                statusSubtext?.text = "Gagal start Touch Listener. Hubungkan Shizuku / ADB terlebih dahulu."
                                statusSubtext?.setTextColor(Color.parseColor("#EF4444"))
                                masterSwitch?.isChecked = false
                                prefs.edit().putBoolean("touch_listener_active", false).apply()
                            }
                        }
                    }
                } else {
                    statusBadge?.text = "○ STANDBY"
                    statusBadge?.setTextColor(Color.parseColor("#64748B"))
                    statusSubtext?.text = "Touch Listener dinonaktifkan (Input default layar)"
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

    private fun buildSensitivitySlidersCard(): View {
        val card = cardLayout()

        card.addView(TextView(context).apply {
            text = "IN-GAME SENSITIVITY MULTIPLIERS"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })

        // --- X-AXIS MULTIPLIER ---
        card.addView(spacer(6))
        val xHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        xHeaderRow.addView(TextView(context).apply {
            text = "Sumbu X (Horizontal / Aim)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        xValBadge = TextView(context).apply {
            text = "${"%.2f".format(sensX)}x"
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00FF88"))
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1500FF88"))
                cornerRadius = 6 * d
            }
        }
        xHeaderRow.addView(xValBadge)
        card.addView(xHeaderRow)

        // SeekBar X: 1.00x to 3.50x (progress 0..50 => step 0.05 => 1.00 + progress * 0.05)
        xSeekBar = SeekBar(context).apply {
            max = 50
            progress = (((sensX - 1.0f) / 0.05f).toInt()).coerceIn(0, 50)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sensX = (1.0f + (prog * 0.05f)).coerceIn(1.0f, 3.5f)
                        xValBadge?.text = "${"%.2f".format(sensX)}x"
                        persistState()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(xSeekBar)

        // X Presets
        val xPresets = listOf(1.0f, 1.4f, 1.8f, 2.2f, 2.8f, 3.2f)
        card.addView(buildPresetChips(xPresets, sensX) { target ->
            sensX = target
            xValBadge?.text = "${"%.2f".format(sensX)}x"
            xSeekBar?.progress = (((sensX - 1.0f) / 0.05f).toInt()).coerceIn(0, 50)
            persistState()
        })

        card.addView(spacer(10))

        // --- Y-AXIS MULTIPLIER ---
        val yHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        yHeaderRow.addView(TextView(context).apply {
            text = "Sumbu Y (Vertical / Drag Shot)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        yValBadge = TextView(context).apply {
            text = "${"%.2f".format(sensY)}x"
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1538BDF8"))
                cornerRadius = 6 * d
            }
        }
        yHeaderRow.addView(yValBadge)
        card.addView(yHeaderRow)

        // SeekBar Y: 1.00x to 3.50x
        ySeekBar = SeekBar(context).apply {
            max = 50
            progress = (((sensY - 1.0f) / 0.05f).toInt()).coerceIn(0, 50)
            progressTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        sensY = (1.0f + (prog * 0.05f)).coerceIn(1.0f, 3.5f)
                        yValBadge?.text = "${"%.2f".format(sensY)}x"
                        persistState()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(ySeekBar)

        // Y Presets
        val yPresets = listOf(1.0f, 1.6f, 2.0f, 2.4f, 2.8f, 3.5f)
        card.addView(buildPresetChips(yPresets, sensY) { target ->
            sensY = target
            yValBadge?.text = "${"%.2f".format(sensY)}x"
            ySeekBar?.progress = (((sensY - 1.0f) / 0.05f).toInt()).coerceIn(0, 50)
            persistState()
        })

        return card
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
            text = "Pilih area layar tempat multiplier aktif. Setengah kanan disarankan agar analog kiri tidak terpengaruh."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val options = listOf(
            Triple(NukeTouchTuningEngine.AREA_RIGHT, "Setengah Kanan", "Bidik / Tembak (Disarankan)"),
            Triple(NukeTouchTuningEngine.AREA_ALL, "Seluruh Layar", "Layar Penuh"),
            Triple(NukeTouchTuningEngine.AREA_LEFT, "Setengah Kiri", "Gerakan / Joystick")
        )

        areaChipViews.clear()
        options.forEach { (code, title, _) ->
            val chip = TextView(context).apply {
                text = title
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
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
            NukeTouchTuningEngine.AREA_RIGHT,
            NukeTouchTuningEngine.AREA_ALL,
            NukeTouchTuningEngine.AREA_LEFT
        )
        areaChipViews.forEachIndexed { i, chip ->
            val code = options.getOrNull(i) ?: -1
            val selected = (code == sensArea)
            chip.setTextColor(if (selected) Color.parseColor("#080E18") else Color.parseColor("#E2E8F0"))
            chip.background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#00FF88") else Color.parseColor("#1E293B"))
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
            text = "Accelerate: Halus saat geser perlahan (micro-aim), responsif cepat saat flick drag shot."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val curves = listOf(
            Pair(NukeTouchTuningEngine.CURVE_ACCELERATE, "Accelerate"),
            Pair(NukeTouchTuningEngine.CURVE_LINEAR, "Linear (1:1)"),
            Pair(NukeTouchTuningEngine.CURVE_DECELERATE, "Decelerate")
        )

        curveChipViews.clear()
        curves.forEach { (code, title) ->
            val chip = TextView(context).apply {
                text = title
                textSize = 9.5f
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
            NukeTouchTuningEngine.CURVE_ACCELERATE,
            NukeTouchTuningEngine.CURVE_LINEAR,
            NukeTouchTuningEngine.CURVE_DECELERATE
        )
        curveChipViews.forEachIndexed { i, chip ->
            val code = curves.getOrNull(i) ?: -1
            val selected = (code == curveMode)
            chip.setTextColor(if (selected) Color.parseColor("#080E18") else Color.parseColor("#E2E8F0"))
            chip.background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#00E5FF") else Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
                if (!selected) setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
        }
    }

    private fun buildJitterFilterCard(): View {
        val card = cardLayout()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(context).apply {
            text = "Anti-Jitter Smoothing (1€ Filter)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        col.addView(TextView(context).apply {
            text = "Menghilangkan micro-tremor getaran jari saat membidik presisi jarak jauh"
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        row.addView(col)

        jitterSwitch = Switch(context).apply {
            isChecked = jitterSmoothing
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#155E75"))
            setOnCheckedChangeListener { _, isChecked ->
                jitterSmoothing = isChecked
                persistState()
            }
        }
        row.addView(jitterSwitch)
        card.addView(row)

        card.addView(spacer(6))
        val aimRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val aimCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        aimCol.addView(TextView(context).apply {
            text = "Relative Aim Mode (Edge Gliding)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        aimCol.addView(TextView(context).apply {
            text = "Memungkinkan putaran kamera tak terbatas melampaui tepi fisik layar"
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        aimRow.addView(aimCol)

        relativeAimSwitch = Switch(context).apply {
            isChecked = relativeAim
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#155E75"))
            setOnCheckedChangeListener { _, isChecked ->
                relativeAim = isChecked
                persistState()
            }
        }
        aimRow.addView(relativeAimSwitch)
        card.addView(aimRow)

        return card
    }

    private fun buildSystemTouchCalibrationCard(): View {
        val card = cardLayout()

        card.addView(TextView(context).apply {
            text = "ANDROID SYSTEM TOUCH CALIBRATION"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })

        // Pointer speed
        card.addView(spacer(6))
        val ptrRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ptrRow.addView(TextView(context).apply {
            text = "Pointer Speed Sistem (-7 s/d +7)"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pointerValBadge = TextView(context).apply {
            text = if (pointerSpeed >= 0) "+$pointerSpeed" else "$pointerSpeed"
            textSize = 10.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00FF88"))
        }
        ptrRow.addView(pointerValBadge)
        card.addView(ptrRow)

        pointerSeekBar = SeekBar(context).apply {
            max = 14
            progress = pointerSpeed + 7
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        pointerSpeed = prog - 7
                        pointerValBadge?.text = if (pointerSpeed >= 0) "+$pointerSpeed" else "$pointerSpeed"
                        persistState()
                        scope.launch {
                            NukeConnectionManager.executeCommand("settings put system pointer_speed $pointerSpeed")
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(pointerSeekBar)

        // Drag shot DPI boost
        card.addView(spacer(6))
        card.addView(TextView(context).apply {
            text = "Drag Shot DPI Density Boost"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
        })

        dpiStatusTv = TextView(context).apply {
            text = "Meningkatkan density virtual layar agar tarikan drag shot Free Fire lebih tajam & responsif."
            textSize = 8.5f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        card.addView(dpiStatusTv)

        val dpiRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        val dpiOffsets = listOf(0, 40, 80, 120)
        dpiChipViews.clear()
        dpiOffsets.forEach { offset ->
            val chip = TextView(context).apply {
                text = if (offset == 0) "Default" else "+$offset DPI"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * d).toInt()
                }
                setOnClickListener {
                    applyDpiBoost(offset)
                }
            }
            dpiChipViews.add(chip)
            dpiRow.addView(chip)
        }
        card.addView(dpiRow)
        updateDpiChips()

        return card
    }

    private fun updateDpiChips() {
        val dpiOffsets = listOf(0, 40, 80, 120)
        dpiChipViews.forEachIndexed { i, chip ->
            val off = dpiOffsets.getOrNull(i) ?: 0
            val selected = (off == currentDpiOffset)
            chip.setTextColor(if (selected) Color.parseColor("#080E18") else Color.parseColor("#E2E8F0"))
            chip.background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"))
                cornerRadius = 8 * d
                if (!selected) setStroke((1 * d).toInt(), Color.parseColor("#334155"))
            }
        }
    }

    private fun applyDpiBoost(offset: Int) {
        currentDpiOffset = offset
        updateDpiChips()
        scope.launch {
            if (!NukeConnectionManager.isConnected()) {
                mainHandler.post {
                    dpiStatusTv?.text = "⚠ ADB/Shizuku belum terhubung untuk mengubah DPI."
                    dpiStatusTv?.setTextColor(Color.parseColor("#F59E0B"))
                }
                return@launch
            }
            if (offset == 0) {
                val ok = NukeTouchTuningEngine.resetDragShotDpi()
                mainHandler.post {
                    dpiStatusTv?.text = if (ok) "✓ DPI dikembalikan ke default layar" else "Gagal reset DPI"
                    dpiStatusTv?.setTextColor(if (ok) Color.parseColor("#00FF88") else Color.parseColor("#EF4444"))
                }
            } else {
                if (physicalDpi == 0) physicalDpi = NukeTouchTuningEngine.getPhysicalDensity()
                val targetDpi = (physicalDpi + offset).coerceIn(320, 640)
                val ok = NukeTouchTuningEngine.applyDragShotDpi(targetDpi)
                mainHandler.post {
                    dpiStatusTv?.text = if (ok) "✓ DPI aktif: ${physicalDpi} → $targetDpi (+$offset DPI) — Drag shot siap" else "Gagal set DPI"
                    dpiStatusTv?.setTextColor(if (ok) Color.parseColor("#00FF88") else Color.parseColor("#EF4444"))
                }
            }
        }
    }

    private fun buildInteractiveTouchpadCard(): View {
        val card = cardLayout()

        card.addView(TextView(context).apply {
            text = "LIVE TOUCHPAD TEST AREA"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })

        val readoutTv = TextView(context).apply {
            text = "Geser jari di bawah untuk menguji responsivitas X & Y"
            textSize = 8.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        }
        card.addView(readoutTv)

        // Custom live interactive canvas for touch test
        val canvasView = object : View(context) {
            private val gridPaint = Paint().apply {
                color = Color.parseColor("#1538BDF8")
                strokeWidth = 1f * d
                style = Paint.Style.STROKE
            }
            private val trailPaint = Paint().apply {
                color = Color.parseColor("#00FF88")
                strokeWidth = 3f * d
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            private val crosshairPaint = Paint().apply {
                color = Color.parseColor("#00E5FF")
                strokeWidth = 1.5f * d
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            private val pointPaint = Paint().apply {
                color = Color.parseColor("#00FF88")
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            private var currentX = -1f
            private var currentY = -1f
            private var lastX = -1f
            private var lastY = -1f
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()

                // Background grid lines
                val cols = 6
                for (i in 1 until cols) {
                    val x = w * (i.toFloat() / cols)
                    canvas.drawLine(x, 0f, x, h, gridPaint)
                }
                val rows = 3
                for (i in 1 until rows) {
                    val y = h * (i.toFloat() / rows)
                    canvas.drawLine(0f, y, w, y, gridPaint)
                }

                // Touch Trail
                canvas.drawPath(path, trailPaint)

                // Current Touch Crosshair
                if (currentX >= 0 && currentY >= 0) {
                    canvas.drawLine(currentX - (15 * d), currentY, currentX + (15 * d), currentY, crosshairPaint)
                    canvas.drawLine(currentX, currentY - (15 * d), currentX, currentY + (15 * d), crosshairPaint)
                    canvas.drawCircle(currentX, currentY, 5 * d, pointPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                        currentX = event.x
                        currentY = event.y
                        path.reset()
                        path.moveTo(event.x, event.y)
                        readoutTv.text = "Down: (X=${event.x.toInt()}, Y=${event.y.toInt()}) [Gain: X=${"%.2f".format(sensX)}x, Y=${"%.2f".format(sensY)}x]"
                        readoutTv.setTextColor(Color.parseColor("#00FF88"))
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val appliedDx = dx * sensX
                        val appliedDy = dy * sensY
                        lastX = event.x
                        lastY = event.y
                        currentX = event.x
                        currentY = event.y
                        path.lineTo(event.x, event.y)
                        readoutTv.text = "ΔX: ${if (appliedDx >= 0) "+" else ""}${"%.1f".format(appliedDx)}  ΔY: ${if (appliedDy >= 0) "+" else ""}${"%.1f".format(appliedDy)} (Multiplier: ${"%.2f".format(sensX)}x / ${"%.2f".format(sensY)}x)"
                        readoutTv.setTextColor(Color.parseColor("#00E5FF"))
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        readoutTv.text = "Lepas — Siap untuk pengujian sentuhan berikutnya"
                        readoutTv.setTextColor(Color.parseColor("#94A3B8"))
                        currentX = -1f
                        currentY = -1f
                        invalidate()
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (90 * d).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 8 * d
                setStroke((1 * d).toInt(), Color.parseColor("#1E293B"))
            }
        }
        card.addView(canvasView)

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

    private fun buildPresetChips(
        presets: List<Float>,
        current: Float,
        onSelect: (Float) -> Unit
    ): View {
        val hscroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presets.forEach { target ->
            val isMatch = (kotlin.math.abs(target - current) < 0.05f)
            val chip = TextView(context).apply {
                text = "${target}x"
                textSize = 9f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setTextColor(if (isMatch) Color.parseColor("#080E18") else Color.parseColor("#94A3B8"))
                background = GradientDrawable().apply {
                    setColor(if (isMatch) Color.parseColor("#00FF88") else Color.parseColor("#1E293B"))
                    cornerRadius = 6 * d
                    if (!isMatch) setStroke((1 * d).toInt(), Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (4 * d).toInt()
                }
                setOnClickListener {
                    onSelect(target)
                }
            }
            row.addView(chip)
        }
        hscroll.addView(row)
        return hscroll
    }
}
