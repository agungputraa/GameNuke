package com.neon.gametweak

import android.content.Context
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * NukeVoiceChangerFloatingOverlay — In-Game Floating Controller for 16kHz Realtime Voice Modulator.
 *
 * Developer: Agung Developer
 *
 * Features:
 *  - Floats discreetly over any running game
 *  - 1-Tap Voice FX switching (Normal, Studio Vocal, Deep Demon, Anime Girl, Cyber Robot, Tactical Radio)
 *  - Realtime Pitch Shifter Slider (-12 to +12 semitones)
 *  - Mic Gain Booster Slider (100% - 300%)
 *  - Live Monitor Switch (Hear self in headset)
 *  - Draggable & Minimizable
 */
class NukeVoiceChangerFloatingOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var observeJob: Job? = null

    // UI elements
    private var pitchValueTv: TextView? = null
    private var pitchSeekBar: SeekBar? = null
    private var gainValueTv: TextView? = null
    private var gainSeekBar: SeekBar? = null
    private var monitorBtn: TextView? = null
    private var powerBtn: TextView? = null
    private val presetButtons = mutableMapOf<NukeVoiceChangerEngine.Preset, TextView>()

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeVoiceChangerHUD"

        @Volatile
        private var instance: NukeVoiceChangerFloatingOverlay? = null

        fun getInstance(context: Context): NukeVoiceChangerFloatingOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeVoiceChangerFloatingOverlay(context.applicationContext).also { instance = it }
            }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val vc = NukeVoiceChangerEngine.getInstance(context)
            if (!vc.hasRecordPermission()) {
                NukeAudioPermissionActivity.launch(context)
                return@post
            }

            val dm = context.resources.displayMetrics
            val panelW = (290 * d).toInt().coerceAtMost((dm.widthPixels * 0.90f).toInt())
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
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
                x = ((dm.widthPixels - panelW) / 2).coerceAtLeast(0)
                y = (dm.heightPixels * 0.18f).toInt()
            }
            rootParams = lp

            val panel = buildView(lp)
            rootView = panel

            try {
                wm.addView(panel, lp)
                startObservingState()
                Log.d(TAG, "Voice Changer Floating Controller shown")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show Voice Changer Floating Controller", e)
                rootView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            observeJob?.cancel()
            observeJob = null
            rootView?.let {
                runCatching { wm.removeView(it) }
                rootView = null
                rootParams = null
            }
        }
    }

    private fun buildView(lp: WindowManager.LayoutParams): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E608140F")) // Obsidian military emerald glass
                cornerRadius = 14 * d
                setStroke((1.2f * d).toInt(), Color.parseColor("#00FF88"))
            }
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        // Header / Drag Handle
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * d).toInt()
            }
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    lp.x = startX + dx
                    lp.y = startY + dy
                    rootView?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    true
                }
                else -> false
            }
        }

        val iconTv = TextView(context).apply {
            text = "🎙️"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (6 * d).toInt()
            }
        }
        header.addView(iconTv)

        val titleTv = TextView(context).apply {
            text = "VOICE CHANGER 16K"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleTv)

        powerBtn = TextView(context).apply {
            text = "ON"
            textSize = 9.5f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#142E22"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#00FF88"))
            }
            setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (6 * d).toInt()
            }
            setOnClickListener {
                val vc = NukeVoiceChangerEngine.getInstance(context)
                vc.toggle()
            }
        }
        header.addView(powerBtn)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            setOnClickListener { hide() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Preset Grid (2 rows x 3 columns)
        val grid = GridLayout(context).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10 * d).toInt()
            }
        }

        presetButtons.clear()
        NukeVoiceChangerEngine.Preset.values().forEach { preset ->
            val pBtn = TextView(context).apply {
                text = "${preset.icon}\n${preset.displayName}"
                textSize = 9f
                setTextColor(Color.parseColor("#CBD5E1"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D1A14"))
                    cornerRadius = 8 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#1C3328"))
                }
                setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                val param = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (48 * d).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                }
                layoutParams = param

                setOnClickListener {
                    NukeVoiceChangerEngine.getInstance(context).setPreset(preset)
                }
            }
            presetButtons[preset] = pBtn
            grid.addView(pBtn)
        }
        root.addView(grid)

        // Pitch Slider Section
        val pitchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val pitchLabel = TextView(context).apply {
            text = "PITCH (SEMITONES):"
            textSize = 9f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        pitchRow.addView(pitchLabel)

        pitchValueTv = TextView(context).apply {
            text = " 0 SEMITONE"
            textSize = 9f
            setTextColor(Color.parseColor("#00FF88"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pitchRow.addView(pitchValueTv)

        val resetPitchBtn = TextView(context).apply {
            text = "Reset"
            textSize = 8.5f
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                NukeVoiceChangerEngine.getInstance(context).setPitchSemitones(0)
                pitchSeekBar?.progress = 12
            }
        }
        pitchRow.addView(resetPitchBtn)
        root.addView(pitchRow)

        pitchSeekBar = SeekBar(context).apply {
            max = 24 // maps 0..24 to -12..+12 semitones
            progress = 12
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
                bottomMargin = (8 * d).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val semi = prog - 12
                        NukeVoiceChangerEngine.getInstance(context).setPitchSemitones(semi)
                        pitchValueTv?.text = " ${if (semi > 0) "+$semi" else "$semi"} SEMITONE"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(pitchSeekBar)

        // Mic Gain & Live Monitor Row
        val bottomControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        monitorBtn = TextView(context).apply {
            text = "🎧 Self-Monitor: OFF"
            textSize = 9f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F2018"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#1D3B2C"))
            }
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * d).toInt()
            }
            setOnClickListener {
                val vc = NukeVoiceChangerEngine.getInstance(context)
                val next = !vc.isLoopbackMonitorEnabled.value
                vc.setLoopbackMonitor(next)
            }
        }
        bottomControls.addView(monitorBtn)

        val hintTv = TextView(context).apply {
            text = "16kHz Low-Latency"
            textSize = 8.5f
            setTextColor(Color.parseColor("#00FF88"))
        }
        bottomControls.addView(hintTv)

        root.addView(bottomControls)

        return root
    }

    private fun startObservingState() {
        val vc = NukeVoiceChangerEngine.getInstance(context)
        observeJob = scope.launch {
            launch {
                vc.isActive.collectLatest { active ->
                    powerBtn?.text = if (active) "ON" else "OFF"
                    powerBtn?.setTextColor(if (active) Color.parseColor("#00FF88") else Color.parseColor("#EF4444"))
                }
            }
            launch {
                vc.currentPreset.collectLatest { cur ->
                    presetButtons.forEach { (preset, btn) ->
                        val isSelected = preset == cur
                        btn.setTextColor(if (isSelected) Color.parseColor("#00FF88") else Color.parseColor("#CBD5E1"))
                        (btn.background as? GradientDrawable)?.apply {
                            setColor(if (isSelected) Color.parseColor("#153625") else Color.parseColor("#0D1A14"))
                            setStroke((1f * d).toInt(), if (isSelected) Color.parseColor("#00FF88") else Color.parseColor("#1C3328"))
                        }
                    }
                }
            }
            launch {
                vc.pitchSemitones.collectLatest { semi ->
                    pitchValueTv?.text = " ${if (semi > 0) "+$semi" else "$semi"} SEMITONE"
                    pitchSeekBar?.progress = semi + 12
                }
            }
            launch {
                vc.isLoopbackMonitorEnabled.collectLatest { enabled ->
                    monitorBtn?.text = if (enabled) "🎧 Self-Monitor: ACTIVE" else "🎧 Self-Monitor: OFF"
                    monitorBtn?.setTextColor(if (enabled) Color.parseColor("#00FF88") else Color.parseColor("#94A3B8"))
                }
            }
        }
    }
}
