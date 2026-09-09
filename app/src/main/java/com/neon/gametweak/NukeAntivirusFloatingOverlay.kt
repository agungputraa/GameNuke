package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NukeAntivirusFloatingOverlay — Executive Stealth In-Game Antivirus & Trojan Sentinel.
 *
 * Professional minimal cyber-defense telemetry HUD overlay.
 */
class NukeAntivirusFloatingOverlay(private val context: Context) {

    companion object {
        private const val TAG = "NukeAntivirusOverlay"

        @Volatile
        private var instance: NukeAntivirusFloatingOverlay? = null

        fun getInstance(context: Context): NukeAntivirusFloatingOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeAntivirusFloatingOverlay(context.applicationContext).also { instance = it }
            }
    }

    fun toggle(): Boolean {
        return if (isShowing) {
            hide()
            false
        } else {
            show()
            true
        }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanObserverJob: Job? = null

    private var rootView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    var isShowing = false
        private set

    private val d = context.resources.displayMetrics.density

    // UI Dynamic Views
    private var statusDotView: View? = null
    private var statusBadgeTv: TextView? = null
    private var progressScanBar: ProgressBar? = null
    private var progressStepTv: TextView? = null
    private var packagesMetricTv: TextView? = null
    private var payloadsMetricTv: TextView? = null
    private var threatIndexMetricTv: TextView? = null
    private var threatsContainer: LinearLayout? = null
    private var emptyStateView: View? = null
    private var actionScanBtn: TextView? = null
    private var actionCleanBtn: TextView? = null

    fun show() {
        if (isShowing) return
        try {
            val panelW = (320 * d).toInt().coerceAtMost((context.resources.displayMetrics.widthPixels * 0.92f).toInt())
            val panelH = (440 * d).toInt().coerceAtMost((context.resources.displayMetrics.heightPixels * 0.85f).toInt())

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val lp = WindowManager.LayoutParams(
                panelW,
                panelH,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = 0
                y = 0
            }

            val view = buildExecutivePanel()
            rootView = view
            windowParams = lp

            attachDragAndOutsideTouch(view, lp)
            wm.addView(view, lp)
            isShowing = true

            observeScanEngine()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show antivirus overlay", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        scanObserverJob?.cancel()
        scanObserverJob = null

        rootView?.let {
            runCatching { wm.removeView(it) }
        }
        rootView = null
        windowParams = null
    }

    private fun buildExecutivePanel(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0B0F15")) // Deep matte obsidian glass
                cornerRadius = 14 * d
                setStroke((1 * d).toInt(), Color.parseColor("#1E293B")) // Stealth dark slate border
            }
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            elevation = 20 * d
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        // ── 1. Executive Header Bar ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * d).toInt()
            }
        }

        // Status Indicator Dot
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((7 * d).toInt(), (7 * d).toInt()).apply {
                rightMargin = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981")) // Active emerald dot
            }
        }
        statusDotView = dot
        header.addView(dot)

        // Title Column
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = "APP SECURITY SENTINEL"
            textSize = 11.5f
            setTextColor(Color.parseColor("#F8FAFC"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        val subTv = TextView(context).apply {
            text = "SYSTEM INTEGRITY • HEURISTIC AUDIT"
            textSize = 7.5f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.MONOSPACE
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        header.addView(titleCol)

        // Status Badge Monospace
        val badge = TextView(context).apply {
            text = "[PROTECTED]"
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#10B981"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1010B981"))
                cornerRadius = 4 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#2510B981"))
            }
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * d).toInt()
            }
        }
        statusBadgeTv = badge
        header.addView(badge)

        // Close Button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
            setOnClickListener { hide() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Divider
        val div = View(context).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
                bottomMargin = (10 * d).toInt()
            }
        }
        root.addView(div)

        // ── 2. Telemetry Metrics Card (2x2 Grid) ──
        val metricsGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#080D13"))
                cornerRadius = 8 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#1E293B"))
            }
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * d).toInt()
            }
        }

        // Row 1: Packages & File Payloads
        val metricRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val pkgMetricCol = buildMetricCell("PACKAGES AUDITED", "0", Color.parseColor("#38BDF8"))
        packagesMetricTv = pkgMetricCol.second
        metricRow1.addView(pkgMetricCol.first)

        val payloadMetricCol = buildMetricCell("FILE PAYLOADS", "0", Color.parseColor("#38BDF8"))
        payloadsMetricTv = payloadMetricCol.second
        metricRow1.addView(payloadMetricCol.first)
        metricsGrid.addView(metricRow1)

        // Metric Divider
        val metricDiv = View(context).apply {
            setBackgroundColor(Color.parseColor("#141E2B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (0.8f * d).toInt()).apply {
                topMargin = (6 * d).toInt()
                bottomMargin = (6 * d).toInt()
            }
        }
        metricsGrid.addView(metricDiv)

        // Row 2: Kernel Daemon & Threat Index
        val metricRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val shellStatus = if (NukeConnectionManager.isConnected()) "UID 2000 (PRIVILEGED)" else "FRAMEWORK"
        val shellMetricCol = buildMetricCell("SECURITY PRIVILEGE", shellStatus, Color.parseColor("#10B981"))
        metricRow2.addView(shellMetricCol.first)

        val threatMetricCol = buildMetricCell("THREAT INDEX", "0 DETECTED", Color.parseColor("#10B981"))
        threatIndexMetricTv = threatMetricCol.second
        metricRow2.addView(threatMetricCol.first)
        metricsGrid.addView(metricRow2)

        root.addView(metricsGrid)

        // ── 3. Slim Progress Bar ──
        val pBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * d).toInt()).apply {
                bottomMargin = (6 * d).toInt()
            }
        }
        progressScanBar = pBar
        root.addView(pBar)

        val pStepTv = TextView(context).apply {
            text = "Standby • Real-Time Protection Active"
            textSize = 7.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * d).toInt()
            }
        }
        progressStepTv = pStepTv
        root.addView(pStepTv)

        // ── 4. Scrollable Threats Container ──
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        threatsContainer = container

        // Empty State: All Secure
        val emptyBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt())
        }
        val emptyTitle = TextView(context).apply {
            text = "SYSTEM INTEGRITY VERIFIED"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            letterSpacing = 0.04f
        }
        val emptyDesc = TextView(context).apply {
            text = "No high-risk permissions, overlay interceptors, or anomalous background packages detected."
            textSize = 8f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
            }
        }
        emptyBox.addView(emptyTitle)
        emptyBox.addView(emptyDesc)
        emptyStateView = emptyBox
        container.addView(emptyBox)

        scroll.addView(container)
        root.addView(scroll)

        // ── 5. Executive Action Buttons (Footer) ──
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * d).toInt()
            }
        }

        val scanBtn = TextView(context).apply {
            text = "SCAN SYSTEM"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0B0F15"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 7 * d
            }
            setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (9 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                rightMargin = (6 * d).toInt()
            }
            setOnClickListener {
                NukeAntivirusEngine.triggerDeepScan(context)
            }
        }
        actionScanBtn = scanBtn
        footer.addView(scanBtn)

        val cleanBtn = TextView(context).apply {
            text = "MANAGE ALL"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151F2E"))
                cornerRadius = 7 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#EF4444"))
            }
            setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val currentThreats = NukeAntivirusEngine.state.value.threats
                currentThreats.forEach { t ->
                    NukeAntivirusEngine.uninstallOrDeleteThreat(context, t)
                }
            }
        }
        actionCleanBtn = cleanBtn
        footer.addView(cleanBtn)

        root.addView(footer)
        return root
    }

    private fun buildMetricCell(title: String, initialVal: String, valColor: Int): Pair<View, TextView> {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tTv = TextView(context).apply {
            text = title
            textSize = 6.8f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        val vTv = TextView(context).apply {
            text = initialVal
            textSize = 9.5f
            setTextColor(valColor)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }
        cell.addView(tTv)
        cell.addView(vTv)
        return cell to vTv
    }

    private fun observeScanEngine() {
        scanObserverJob?.cancel()
        scanObserverJob = scope.launch {
            NukeAntivirusEngine.state.collectLatest { state ->
                updateStateUI(state)
            }
        }
    }

    private fun updateStateUI(state: NukeAntivirusEngine.AntivirusShieldState) {
        if (!isShowing) return

        // Progress bar
        progressScanBar?.let { pb ->
            pb.visibility = if (state.isScanning) View.VISIBLE else View.GONE
            pb.isIndeterminate = state.isScanning
        }

        // Current Step
        progressStepTv?.text = if (state.isScanning) {
            state.statusMessage.ifBlank { "Scanning memory, packages, and storage..." }
        } else {
            "Real-Time Protection Active • Heuristics Standby"
        }

        // Counters
        packagesMetricTv?.text = "${state.scannedItemsCount}"
        payloadsMetricTv?.text = "${state.scannedItemsCount}"

        // Threat Index & Status Badge
        val threatCount = state.threats.size
        if (threatCount > 0) {
            statusDotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444")) // Alert Red
            }
            statusBadgeTv?.apply {
                text = "[$threatCount THREATS]"
                setTextColor(Color.parseColor("#EF4444"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1AEF4444"))
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#35EF4444"))
                }
            }
            threatIndexMetricTv?.apply {
                text = "$threatCount DETECTED"
                setTextColor(Color.parseColor("#EF4444"))
            }
            actionCleanBtn?.visibility = View.VISIBLE
        } else {
            statusDotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981")) // Secure Emerald
            }
            statusBadgeTv?.apply {
                text = if (state.isScanning) "[SCANNING]" else "[PROTECTED]"
                val color = if (state.isScanning) Color.parseColor("#38BDF8") else Color.parseColor("#10B981")
                setTextColor(color)
                background = GradientDrawable().apply {
                    setColor(if (state.isScanning) Color.parseColor("#1538BDF8") else Color.parseColor("#1010B981"))
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), color)
                }
            }
            threatIndexMetricTv?.apply {
                text = "0 DETECTED"
                setTextColor(Color.parseColor("#10B981"))
            }
            actionCleanBtn?.visibility = View.GONE
        }

        // Button text
        actionScanBtn?.text = if (state.isScanning) "SCANNING..." else "SCAN SYSTEM"

        // Render Threat Cards
        val container = threatsContainer ?: return
        container.removeAllViews()

        if (state.threats.isEmpty()) {
            emptyStateView?.let { container.addView(it) }
        } else {
            state.threats.forEach { threat ->
                val card = buildStealthThreatCard(threat)
                container.addView(card)
            }
        }
    }

    private fun buildStealthThreatCard(threat: NukeAntivirusEngine.DetectedThreat): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C131D"))
                cornerRadius = 8 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#1E2D3E"))
            }
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * d).toInt()
            }
        }

        // Header: Level badge + Threat Name
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val levelColorHex = when (threat.severity) {
            NukeAntivirusEngine.ThreatSeverity.CRITICAL -> "#EF4444"
            NukeAntivirusEngine.ThreatSeverity.HIGH -> "#F59E0B"
            NukeAntivirusEngine.ThreatSeverity.WARNING -> "#38BDF8"
        }

        val levelBadge = TextView(context).apply {
            text = threat.severity.name
            textSize = 7.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(levelColorHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15" + levelColorHex.removePrefix("#")))
                cornerRadius = 3 * d
                setStroke((0.6f * d).toInt(), Color.parseColor(levelColorHex))
            }
            setPadding((5 * d).toInt(), (2 * d).toInt(), (5 * d).toInt(), (2 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (6 * d).toInt()
            }
        }
        topRow.addView(levelBadge)

        val nameTv = TextView(context).apply {
            text = threat.title
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(nameTv)
        card.addView(topRow)

        // Target Identifier Monospace
        val pathTv = TextView(context).apply {
            text = threat.targetIdentifier
            textSize = 7.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#64748B"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }
        card.addView(pathTv)

        // Description
        val descTv = TextView(context).apply {
            text = threat.description
            textSize = 8f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (3 * d).toInt()
                bottomMargin = (6 * d).toInt()
            }
        }
        card.addView(descTv)

        // Actions Row: ISOLATE, FORCE STOP, UNINSTALL / DELETE
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (!threat.isFile) {
            val isolateBtn = buildMiniActionButton("ISOLATE", "#38BDF8") {
                NukeAntivirusEngine.isolateThreat(context, threat)
            }
            actionsRow.addView(isolateBtn)

            val stopBtn = buildMiniActionButton("FORCE STOP", "#F59E0B") {
                NukeAntivirusEngine.forceStopThreat(context, threat)
            }
            actionsRow.addView(stopBtn)

            val delBtn = buildMiniActionButton("UNINSTALL", "#EF4444") {
                NukeAntivirusEngine.uninstallOrDeleteThreat(context, threat)
            }
            actionsRow.addView(delBtn)
        } else {
            val purgeBtn = buildMiniActionButton("DELETE FILE", "#EF4444") {
                NukeAntivirusEngine.uninstallOrDeleteThreat(context, threat)
            }
            actionsRow.addView(purgeBtn)
        }

        card.addView(actionsRow)
        return card
    }

    private fun buildMiniActionButton(label: String, colorHex: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            text = label
            textSize = 7.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(colorHex))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10" + colorHex.removePrefix("#")))
                cornerRadius = 4 * d
                setStroke((0.6f * d).toInt(), Color.parseColor(colorHex))
            }
            setPadding((6 * d).toInt(), (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (4 * d).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun attachDragAndOutsideTouch(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_OUTSIDE -> {
                    hide()
                    true
                }
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - startX).toInt()
                    val deltaY = (event.rawY - startY).toInt()
                    lp.x = initialX + deltaX
                    lp.y = initialY + deltaY
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                else -> false
            }
        }
    }
}
