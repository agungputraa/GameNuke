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
 * NukeMagicTouchPanelOverlay — Floating Magic Touch Sensitivity Studio.
 *
 * Realtime touch acceleration and sensitivity panel:
 *  - X-Axis Swipe Sensitivity seekbar (horizontal aim tracking)
 *  - Y-Axis Swipe Sensitivity seekbar (vertical drag-headshot optimization for Free Fire)
 *  - System Pointer Speed realtime slider
 *  - Touch Polling Rate & Velocity Booster
 *  - Touch Deadzone / Latency Reducer
 *  - Anti-Jitter / Aim Stabilizer
 *  - 1-Tap Presets (FF Drag Headshot, Close Shotgun, Sniper Precision)
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

    // UI elements for live updates
    private var xValTv: TextView? = null
    private var yValTv: TextView? = null
    private var pointerValTv: TextView? = null
    private var xSeekBar: SeekBar? = null
    private var ySeekBar: SeekBar? = null
    private var pointerSeekBar: SeekBar? = null
    private var pollingSwitch: Switch? = null
    private var latencySwitch: Switch? = null
    private var antiJitterSwitch: Switch? = null

    // State values (persisted)
    var sensitivityX: Int = 50       // 1 - 100 (default 50 = 1.0x)
    var sensitivityY: Int = 70       // 1 - 100 (default 70 = 1.7x Drag Headshot)
    var pointerSpeed: Int = 0        // -7 to +7 (default 0)
    var ultraPollingEnabled: Boolean = true
    var zeroLatencyEnabled: Boolean = true
    var antiJitterEnabled: Boolean = false

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeMagicTouch"
        private const val PREFS_NAME = "NukeMagicTouchPrefs"

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
        loadSettings()
    }

    // ─── Public Show / Hide API ─────────────────────────────────────────────

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
                (490 * d).toInt().coerceAtMost((sh * 0.84f).toInt())
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
                applySettings()
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

    // ─── UI Builder ─────────────────────────────────────────────────────────

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

        // Section A: Axis Sensitivities
        body.addView(sectionTitle("AXIS SWIPE SENSITIVITY"))
        body.addView(buildXAxisControl())
        body.addView(space(6))
        body.addView(buildYAxisControl())
        body.addView(space(10))

        // Section B: Pointer & Sampling
        body.addView(sectionTitle("TOUCH ENGINE & RESPONSE"))
        body.addView(buildPointerSpeedControl())
        body.addView(buildToggleRow(
            title = "Ultra-High Touch Polling Rate",
            subtitle = "Boosts velocity sampling to 480Hz",
            checked = ultraPollingEnabled,
            onChecked = {
                ultraPollingEnabled = it
                saveSettings()
                applySettings()
            },
            onSwitchCreated = { pollingSwitch = it }
        ))
        body.addView(space(4))
        body.addView(buildToggleRow(
            title = "Zero Touch Latency (Instant Slop)",
            subtitle = "Removes initial tap threshold delay",
            checked = zeroLatencyEnabled,
            onChecked = {
                zeroLatencyEnabled = it
                saveSettings()
                applySettings()
            },
            onSwitchCreated = { latencySwitch = it }
        ))
        body.addView(space(4))
        body.addView(buildToggleRow(
            title = "Anti-Jitter Aim Stabilizer",
            subtitle = "Smooths micro-shaking for sniper tracking",
            checked = antiJitterEnabled,
            onChecked = {
                antiJitterEnabled = it
                saveSettings()
                applySettings()
            },
            onSwitchCreated = { antiJitterSwitch = it }
        ))
        body.addView(space(10))

        // Section C: One-Tap Presets
        body.addView(sectionTitle("ONE-TAP PRO PRESETS"))
        body.addView(buildPresetsRow())
        body.addView(space(8))

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

        // 1. Centered Modern Drag Grip Pill
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
            text = "⚡ MAGIC TOUCH STUDIO"
            textSize = 11.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        })

        titleCol.addView(TextView(context).apply {
            text = "Realtime Swipe & Sensitivity Tuner"
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

    private fun buildXAxisControl(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "X-Axis (Horizontal Aim Speed)"
            textSize = 9f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        xValTv = TextView(context).apply {
            text = formatXMultiplier(sensitivityX)
            textSize = 9.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(xValTv)
        box.addView(header)

        xSeekBar = SeekBar(context).apply {
            max = 100
            progress = sensitivityX
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivityX = progress.coerceIn(1, 100)
                    xValTv?.text = formatXMultiplier(sensitivityX)
                    if (fromUser) {
                        saveSettings()
                        applySettings()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(xSeekBar)
        return box
    }

    private fun buildYAxisControl(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Y-Axis (Vertical Drag Headshot)"
            textSize = 9f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        yValTv = TextView(context).apply {
            text = formatYMultiplier(sensitivityY)
            textSize = 9.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(yValTv)
        box.addView(header)

        ySeekBar = SeekBar(context).apply {
            max = 100
            progress = sensitivityY
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivityY = progress.coerceIn(1, 100)
                    yValTv?.text = formatYMultiplier(sensitivityY)
                    if (fromUser) {
                        saveSettings()
                        applySettings()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(ySeekBar)
        return box
    }

    private fun buildPointerSpeedControl(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "System Pointer Speed"
            textSize = 9f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pointerValTv = TextView(context).apply {
            text = "${if (pointerSpeed > 0) "+$pointerSpeed" else "$pointerSpeed"}"
            textSize = 9.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(pointerValTv)
        box.addView(header)

        pointerSeekBar = SeekBar(context).apply {
            max = 14
            progress = pointerSpeed + 7
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            progressTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    pointerSpeed = progress - 7
                    pointerValTv?.text = "${if (pointerSpeed > 0) "+$pointerSpeed" else "$pointerSpeed"}"
                    if (fromUser) {
                        saveSettings()
                        applyPointerSpeed()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(pointerSeekBar)
        return box
    }

    private fun buildToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
        onSwitchCreated: ((Switch) -> Unit)? = null
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
            textSize = 9f
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            textSize = 7.5f
            setTextColor(Color.parseColor("#64748B"))
        })
        row.addView(textCol)

        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
            trackTintList = ColorStateList.valueOf(Color.parseColor("#1A3B2F"))
            setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
        }
        onSwitchCreated?.invoke(sw)
        row.addView(sw)
        return row
    }

    private fun buildPresetsRow(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(presetBtn("⚡ FF HEADSHOT") {
            applyPreset(x = 65, y = 90, ptr = 5, polling = true, latency = true, jitter = false)
        })
        row.addView(space(6, h = true))
        row.addView(presetBtn("🎯 CQB SHOTGUN") {
            applyPreset(x = 85, y = 75, ptr = 4, polling = true, latency = true, jitter = false)
        })
        row.addView(space(6, h = true))
        row.addView(presetBtn("🔭 SNIPER AIM") {
            applyPreset(x = 40, y = 40, ptr = 0, polling = true, latency = false, jitter = true)
        })
        container.addView(row)

        val resetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp2px(6), 0, 0)
        }
        val resetBtn = TextView(context).apply {
            text = "↺ RESET TO STOCK (1.00x DEFAULT)"
            textSize = 8f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(Color.parseColor("#080C10"))
                setStroke(dp2px(1), Color.parseColor("#1C2732"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(40)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(80).start() }
                    .start()
                applyPreset(x = 20, y = 20, ptr = 0, polling = false, latency = false, jitter = false)
            }
        }
        resetRow.addView(resetBtn)
        container.addView(resetRow)

        return container
    }

    private fun presetBtn(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 8.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((6 * d).toInt(), (7 * d).toInt(), (6 * d).toInt(), (7 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                setColor(Color.parseColor("#0E1620"))
                setStroke(dp2px(1), Color.parseColor("#1E2B38"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(40)
                    .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(80).start() }
                    .start()
                onClick()
            }
        }
    }

    private fun applyPreset(x: Int, y: Int, ptr: Int, polling: Boolean, latency: Boolean, jitter: Boolean) {
        sensitivityX = x
        sensitivityY = y
        pointerSpeed = ptr
        ultraPollingEnabled = polling
        zeroLatencyEnabled = latency
        antiJitterEnabled = jitter

        xSeekBar?.progress = x
        ySeekBar?.progress = y
        pointerSeekBar?.progress = ptr + 7
        xValTv?.text = formatXMultiplier(x)
        yValTv?.text = formatYMultiplier(y)
        pointerValTv?.text = "${if (ptr > 0) "+$ptr" else "$ptr"}"
        pollingSwitch?.isChecked = polling
        latencySwitch?.isChecked = latency
        antiJitterSwitch?.isChecked = jitter

        saveSettings()
        applySettings()
    }

    // ─── Realtime Engine Application ────────────────────────────────────────

    private fun applySettings() {
        applyPointerSpeed()
        scope.launch {
            val script = buildString {
                if (ultraPollingEnabled) {
                    append("setprop debug.velocity_tracker lsq2 2>/dev/null ; ")
                    append("setprop touch.pressure.scale 0.001 2>/dev/null ; ")
                    append("setprop touch.size.bias 0 2>/dev/null ; ")
                }
                if (zeroLatencyEnabled) {
                    append("setprop view.touch_slop 0 2>/dev/null ; ")
                }
                if (antiJitterEnabled) {
                    append("setprop debug.input.filter 1 2>/dev/null ; ")
                }
            }
            if (script.isNotBlank() && NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand(script, 1000L)
            }
        }
    }

    private fun applyPointerSpeed() {
        runCatching {
            Settings.System.putInt(context.contentResolver, "pointer_speed", pointerSpeed)
        }
        if (NukeConnectionManager.isConnected()) {
            scope.launch {
                NukeConnectionManager.executeCommand("settings put system pointer_speed $pointerSpeed", 500L)
            }
        }
    }

    // ─── Persistence ────────────────────────────────────────────────────────

    private fun saveSettings() {
        prefs.edit()
            .putInt("sens_x", sensitivityX)
            .putInt("sens_y", sensitivityY)
            .putInt("pointer_speed", pointerSpeed)
            .putBoolean("polling", ultraPollingEnabled)
            .putBoolean("latency", zeroLatencyEnabled)
            .putBoolean("jitter", antiJitterEnabled)
            .apply()
    }

    private fun loadSettings() {
        sensitivityX = prefs.getInt("sens_x", 50)
        sensitivityY = prefs.getInt("sens_y", 70)
        pointerSpeed = prefs.getInt("pointer_speed", 0)
        ultraPollingEnabled = prefs.getBoolean("polling", true)
        zeroLatencyEnabled = prefs.getBoolean("latency", true)
        antiJitterEnabled = prefs.getBoolean("jitter", false)
    }

    // ─── Formatting & Helpers ───────────────────────────────────────────────

    private fun formatXMultiplier(v: Int): String {
        val mult = 0.5f + (v / 100f) * 2.5f // 0.5x to 3.0x
        return String.format("%.2fx", mult)
    }

    private fun formatYMultiplier(v: Int): String {
        val mult = 0.5f + (v / 100f) * 3.5f // 0.5x to 4.0x
        return String.format("%.2fx (Drag Boost)", mult)
    }

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
