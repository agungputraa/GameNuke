package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * NukeTaskManagerPanelOverlay — Tactical In-Game Task Manager Studio.
 *
 * Realtime background process supervisor enabling gamers to view and terminate
 * rogue background apps eating CPU cycles & RAM, with strict safeguards:
 *  - STRICT SYSTEM PROTECTION: Hides and protects SystemUI, Android core, telephony,
 *    keyboards (IME), launchers, Shizuku, and essential OS services to prevent device harm.
 *  - CREATOR / STREAMER PROTECTION: Strictly protects all screen recording and streaming
 *    tools so gaming reviews & recordings are NEVER disrupted.
 *  - ACTIVE GAME IMMUNITY: Never kills or touches the current foreground game or Game Nuke.
 *  - 1-Tap [END TASK] per individual app & 1-Tap [⚡ KILL ALL SAFE APPS].
 */
class NukeTaskManagerPanelOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var refreshJob: Job? = null

    // UI elements
    private var taskListContainer: LinearLayout? = null
    private var taskCountTv: TextView? = null
    private var totalRamTv: TextView? = null
    private var loadingProgressBar: ProgressBar? = null
    private var actionStatusTv: TextView? = null

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeTaskManager"

        @Volatile
        private var instance: NukeTaskManagerPanelOverlay? = null

        fun getInstance(context: Context): NukeTaskManagerPanelOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeTaskManagerPanelOverlay(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    data class BackgroundAppItem(
        val packageName: String,
        val appLabel: String,
        val pid: Int,
        val estimatedRamMb: Long,
        val isProtectedOrActive: Boolean = false,
        val statusTag: String = ""
    )

    // ─── Public Show / Hide / Toggle API ────────────────────────────────────

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val (sw, sh) = getScreenSize()
            val isLandscape = sw > sh
            val panelW = if (isLandscape) {
                (360 * d).toInt().coerceAtMost((sw * 0.52f).toInt())
            } else {
                (340 * d).toInt().coerceAtMost((sw * 0.92f).toInt())
            }
            val panelH = if (isLandscape) {
                (sh * 0.90f).toInt()
            } else {
                (520 * d).toInt().coerceAtMost((sh * 0.85f).toInt())
            }

            val lp = WindowManager.LayoutParams(
                panelW, panelH,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((sw - panelW) / 2).coerceAtLeast(0)
                y = if (isLandscape) (sh * 0.05f).toInt() else (sh * 0.08f).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            rootParams = lp

            val panel = buildView()
            rootView = panel

            try {
                wm.addView(panel, lp)
                refreshTasksList()
                Log.d(TAG, "Task Manager overlay opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add Task Manager view", e)
                rootView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            refreshJob?.cancel()
            refreshJob = null
            rootView?.let {
                try { wm.removeView(it) } catch (e: Exception) { Log.w(TAG, "removeView error: ${e.message}") }
            }
            rootView = null
            rootParams = null
            Log.d(TAG, "Task Manager overlay closed")
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    // ─── UI Construction ───────────────────────────────────────────────────

    private fun buildView(): View {
        val root = FrameLayout(context).apply {
            background = NukeCyberHudStyler.TacticalPanelDrawable(
                density = d,
                cornerRadiusPx = 14 * d,
                strokeColor = NukeCyberHudStyler.COLOR_EMERALD_NEON,
                bgColor = NukeCyberHudStyler.COLOR_BG_OBSIDIAN,
                showGrid = true,
                showBrackets = true
            )
            clipToOutline = true
        }

        val mainCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }

        // 1. Top Draggable Cyber Header Bar
        val header = buildHeader()
        mainCol.addView(header)

        // 2. Summary Stats & Quick Clean Banner
        val summaryCard = buildSummaryCard()
        mainCol.addView(summaryCard)

        // 3. Status text feedback
        actionStatusTv = TextView(context).apply {
            text = "PROCESS CONTROL: Select an eligible background app to manage or end its process"
            textSize = 9.5f
            setTextColor(Color.parseColor("#00E5C8"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, (6 * d).toInt())
        }
        mainCol.addView(actionStatusTv)

        // 4. Loading Spinner
        loadingProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (4 * d).toInt()
            }
            visibility = View.GONE
        }
        mainCol.addView(loadingProgressBar)

        // 5. Scrollable Task List
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            isVerticalScrollBarEnabled = true
        }

        taskListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(taskListContainer)
        mainCol.addView(scroll)

        root.addView(mainCol)
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * d).toInt() }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F1A17"))
                cornerRadius = 8 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#10B981"))
            }
            setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }

        // Draggable Grip Area
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val dragArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener { _, event ->
                val lp = rootParams ?: return@setOnTouchListener false
                val rv = rootView ?: return@setOnTouchListener false
                val (sw, sh) = getScreenSize()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = initialX + (event.rawX - initialTouchX).toInt()
                        val newY = initialY + (event.rawY - initialTouchY).toInt()
                        lp.x = newX.coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                        lp.y = newY.coerceIn(0, (sh - 100 * d).toInt())
                        runCatching { wm.updateViewLayout(rv, lp) }
                        true
                    }
                    else -> false
                }
            }
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply {
                rightMargin = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        dragArea.addView(dot)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * d).toInt()
            }
        }
        val titleTv = TextView(context).apply {
            text = tr("TASK MANAGER")
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = TextView(context).apply {
            text = tr("BACKGROUND APP & MEMORY MANAGER • DRAG TO MOVE")
            textSize = 6.2f
            setTextColor(Color.parseColor("#7A9E94"))
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        dragArea.addView(titleCol)
        row.addView(dragArea)

        // Custom styled Refresh Button (Never clipped/drowned)
        val refreshBtn = TextView(context).apply {
            text = "↺"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E5C8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#142B24"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#00E5C8"))
            }
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply {
                rightMargin = (6 * d).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { refreshTasksList() }
        }
        row.addView(refreshBtn)


        // Custom styled Close Button (Never clipped/drowned)
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF3D55"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B1417"))
                cornerRadius = 6 * d
                setStroke((1 * d).toInt(), Color.parseColor("#FF3D55"))
            }
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { hide() }
        }
        row.addView(closeBtn)

        return row
    }

    private fun buildSummaryCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * d).toInt() }
            background = NukeCyberHudStyler.buildCardBackground(
                density = d,
                cornerRadiusDp = 8f,
                strokeColor = NukeCyberHudStyler.COLOR_BORDER_SUBTLE,
                fillColor = NukeCyberHudStyler.COLOR_BG_CARD
            )
            setPadding((10 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * d).toInt()
            }
        }
        taskCountTv = TextView(context).apply {
            text = "SCANNING APPS..."
            textSize = 9.5f
            setTextColor(Color.parseColor("#F0FFF4"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        totalRamTv = TextView(context).apply {
            text = "MEMORY FOOTPRINT: CALCULATING"
            textSize = 7.5f
            setTextColor(Color.parseColor("#7A9E94"))
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        infoCol.addView(taskCountTv)
        infoCol.addView(totalRamTv)
        card.addView(infoCol)

        // Action Buttons Row: [💀 PURGE ZOMBIES], [⚖ BALANCE ALL], [⚡ END SAFE]
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val killZombiesBtn = TextView(context).apply {
            text = tr("PROCESS SCAN")
            textSize = 7.5f
            setTextColor(Color.parseColor("#F43F5E"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#260813"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#F43F5E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * d).toInt()
            ).apply { rightMargin = (4 * d).toInt() }
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                scope.launch {
                    try {
                        val killed = NukeProcessPurgeGuardian.killRogueZombieProcesses(context)
                        withContext(Dispatchers.Main) {
                            actionStatusTv?.text = if (killed > 0) "Completed: stopped $killed stalled background process group(s)." else "No stalled background process groups detected."
                            actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                            refreshTasksList()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "killZombies error: ${t.message}", t)
                    }
                }
            }
        }
        actionRow.addView(killZombiesBtn)

        val balanceAllBtn = TextView(context).apply {
            text = tr("BALANCE")
            textSize = 7.5f
            setTextColor(Color.parseColor("#00E5C8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E2420"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#00E5C8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * d).toInt()
            ).apply { rightMargin = (4 * d).toInt() }
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { balanceAllSafeProcesses() }
        }
        actionRow.addView(balanceAllBtn)

        val killAllBtn = TextView(context).apply {
            text = tr("END ELIGIBLE")
            textSize = 7.5f
            setTextColor(Color.parseColor("#050D0A"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 6 * d
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (48 * d).toInt()
            )
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { killAllSafeProcesses() }
        }
        actionRow.addView(killAllBtn)
        card.addView(actionRow)

        return card
    }

    // ─── Process Query & Safety Filtering ──────────────────────────────────

    private fun refreshTasksList() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.VISIBLE
                    actionStatusTv?.text = "SCANNING ELIGIBLE BACKGROUND APPS..."
                    actionStatusTv?.setTextColor(Color.parseColor("#00E5C8"))
                }

                val tasks = queryRunningUserApps()

                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    renderTaskList(tasks)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "refreshTasksList error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    actionStatusTv?.text = "✓ TASK MONITOR READY"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                }
            }
        }
    }

    private suspend fun resolveForegroundPackage(detector: ActiveGameDetector): String = withContext(Dispatchers.IO) {
        val myPkg = context.packageName
        val pm = context.packageManager

        // 1. Primary: Query dumpsys activity & window for the exact resumed foreground activity
        val cmd = "dumpsys activity activities 2>/dev/null | grep -m 1 -E 'topResumedActivity|mResumedActivity|ResumedActivity' || dumpsys window 2>/dev/null | grep -m 1 -E 'mFocusedApp|mCurrentFocus'"
        val output = if (AdbManager.getInstance(context).isConnected()) {
            AdbManager.getInstance(context).executeCommand(cmd, "/", 1_800L)?.output.orEmpty()
        } else {
            NukeConnectionManager.executeCommand(cmd, 1_800L)?.output.orEmpty()
        }

        if (output.isNotBlank()) {
            val pkgRegex = Regex("""(?:ActivityRecord\{[0-9a-fA-F]+\s+u\d+\s+(?:[a-zA-Z0-9_.]+/)?|Window\{[0-9a-fA-F]+\s+u\d+\s+|ResumedActivity:\s*ActivityRecord\{[0-9a-fA-F]+\s+u\d+\s+)([a-zA-Z0-9_.]+)""")
            for (match in pkgRegex.findAll(output)) {
                val p = match.groupValues.getOrNull(1)?.trim()?.substringBefore("/")
                if (!p.isNullOrBlank() && p != myPkg && !p.contains("com.neon.gametweak") && !isSensitiveSystemPackage(p.lowercase(Locale.US))) {
                    // Ensure it is a valid installed user app or game, not a native service
                    val info = runCatching {
                        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(p, PackageManager.ApplicationInfoFlags.of(0L))
                        else @Suppress("DEPRECATION") pm.getApplicationInfo(p, 0)
                    }.getOrNull()
                    if (info != null) {
                        val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isUpdatedSys = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val isGame = (Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME) ||
                                     ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
                        if (!isSys || isUpdatedSys || isGame) {
                            return@withContext p
                        }
                    }
                }
            }
        }

        // 2. ActiveGameDetector fallback
        val detected = runCatching { detector.detectState() }.getOrNull()
        val focused = detected?.focusedPackage.orEmpty()
        if (focused.isNotBlank() && focused != myPkg && !focused.contains("com.neon.gametweak") && !isSensitiveSystemPackage(focused.lowercase(Locale.US))) {
            return@withContext focused
        }

        // 3. UsageStats fallback (works natively without root/ADB)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val events = runCatching { usm.queryEvents(now - 45_000L, now) }.getOrNull()
                if (events != null) {
                    val event = android.app.usage.UsageEvents.Event()
                    var lastPkg: String? = null
                    var lastTime = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                            val ep = event.packageName
                            if (!ep.isNullOrBlank() && ep != myPkg && !ep.contains("com.neon.gametweak") && !isSensitiveSystemPackage(ep.lowercase(Locale.US)) && event.timeStamp >= lastTime) {
                                lastPkg = ep
                                lastTime = event.timeStamp
                            }
                        }
                    }
                    if (!lastPkg.isNullOrBlank()) {
                        return@withContext lastPkg
                    }
                }
            }
        }

        // 4. Runtime state & resumed game fallback (crucial when overlay is on top)
        val detectedFallback = runCatching { detector.detectState() }.getOrNull()
        val resumed = detectedFallback?.resumedGame?.packageName
        if (!resumed.isNullOrBlank() && resumed != myPkg && !isSensitiveSystemPackage(resumed.lowercase(Locale.US))) {
            return@withContext resumed
        }
        val activeGame = NukeRuntimeState.state.value.activePackage
        if (!activeGame.isNullOrBlank() && activeGame != myPkg && !isSensitiveSystemPackage(activeGame.lowercase(Locale.US))) {
            return@withContext activeGame
        }
        val lastGame = NukeRuntimeState.lastKnownGamePackage
        if (!lastGame.isNullOrBlank() && lastGame != myPkg && !isSensitiveSystemPackage(lastGame.lowercase(Locale.US))) {
            return@withContext lastGame
        }

        ""
    }

    private suspend fun queryRunningUserApps(): List<BackgroundAppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val myPkg = context.packageName
        val activeGamePkg = NukeRuntimeState.state.value.activePackage.orEmpty()
        val lastGamePkg = NukeRuntimeState.lastKnownGamePackage.orEmpty()
        val detector = ActiveGameDetector(context, AdbManager.getInstance(context))
        val detectedState = runCatching { detector.detectState() }.getOrNull()
        val resumedGamePkg = detectedState?.resumedGame?.packageName.orEmpty()

        // 1. Resolve current active foreground package (the underlying activity under overlay)
        val foregroundPkg = resolveForegroundPackage(detector)

        // 2. High-speed discovery of recent tasks affinity via shell (identifies apps sitting in recent apps)
        val recentCmd = "dumpsys activity recents 2>/dev/null | grep -E 'affinity=[0-9]+:' | sed -E 's/.*affinity=[0-9]+://' | sort -u"
        val recentOutput = if (AdbManager.getInstance(context).isConnected()) {
            AdbManager.getInstance(context).executeCommand(recentCmd, "/", 1_800L)?.output.orEmpty()
        } else {
            NukeConnectionManager.executeCommand(recentCmd, 1_800L)?.output.orEmpty()
        }
        val recentTasksSet = recentOutput.lineSequence()
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() && it.contains(".") }
            .toSet()

        // Fast Discovery Layer: Map of PackageName -> Pair(PID, RAM_MB)
        val rawProcessMap = LinkedHashMap<String, Pair<Int, Long>>()

        // 3. High-speed accurate snapshot via ps -A -o PID,NAME,RSS
        val cmd = "ps -A -o PID,NAME,RSS 2>/dev/null || ps -o PID,NAME,RSS 2>/dev/null || ps -A -o PID,RSS,NAME 2>/dev/null"
        val psOutput = if (AdbManager.getInstance(context).isConnected()) {
            AdbManager.getInstance(context).executeCommand(cmd, "/", 2_500L)?.output.orEmpty()
        } else {
            NukeConnectionManager.executeCommand(cmd, 2_500L)?.output.orEmpty()
        }

        if (psOutput.isNotBlank()) {
            psOutput.lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val pid = parts[0].toIntOrNull() ?: return@forEach
                    val name = parts[1]
                    val rssKb = parts[2].toLongOrNull() ?: 0L

                    // Skip kernel threads, services without package structure
                    if (name.startsWith("[") || !name.contains(".")) return@forEach
                    val cleanPkg = name.substringBefore(":")
                    val ramMb = maxOf(16L, (rssKb / 1024L))

                    val existing = rawProcessMap[cleanPkg]
                    if (existing == null || existing.second < ramMb) {
                        rawProcessMap[cleanPkg] = Pair(pid, ramMb)
                    }
                }
            }
        }

        // 2. Comprehensive Layer: Always check ActivityManager runningAppProcesses
        val runningProcesses = am?.runningAppProcesses.orEmpty()
        runningProcesses.forEach { proc ->
            val pkgList = proc.pkgList.orEmpty()
            val procPkg = if (pkgList.isNotEmpty()) pkgList[0] else proc.processName.substringBefore(":")
            val pid = proc.pid
            if (procPkg.contains(".") && !procPkg.startsWith("[")) {
                val existing = rawProcessMap[procPkg]
                if (existing == null || existing.first <= 0) {
                    rawProcessMap[procPkg] = Pair(pid, existing?.second ?: 42L)
                }
            }
        }

        // Ensure Game Nuke itself is always present in the process map
        if (!rawProcessMap.containsKey(myPkg)) {
            rawProcessMap[myPkg] = Pair(android.os.Process.myPid(), 65L)
        }

        val resultList = mutableListOf<BackgroundAppItem>()

        rawProcessMap.forEach { (pkg, pair) ->
            val pid = pair.first
            val ramMb = pair.second
            val lower = pkg.lowercase(Locale.US)

            // 1. GAME NUKE (Absolute Self-Preservation: PID, UID, and Package Immunity)
            val isMyPkg = (pid == android.os.Process.myPid() ||
                pkg == myPkg ||
                pkg == "com.neon.gametweak" ||
                lower.contains("gametweak") ||
                lower.contains("wandev") ||
                lower.contains("frb.axeron") ||
                lower.contains("nukedaemon") ||
                lower.contains("nukeprocess") ||
                lower.contains("nuketouch"))

            // 2. SENSITIVE SYSTEM & OEM CHECK: Filter out Android core, emergency alerts, cell broadcast, OEM security daemons
            if (!isMyPkg && isSensitiveSystemPackage(lower)) {
                return@forEach
            }

            val appInfo = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                }
            }.getOrNull()

            // Discard any process that is NOT an installed Android application
            if (!isMyPkg && appInfo == null) {
                return@forEach
            }

            // 3. YOUTUBE & MEDIA PLAYERS — 100% IMMUNE (Evaluated FIRST so YouTube is NEVER mistaken as recorder)
            val isYouTube = lower == "com.google.android.youtube" ||
                lower == "com.google.android.apps.youtube.music" ||
                lower.contains("youtube") ||
                lower.contains("vanced") ||
                lower.contains("revanced") ||
                lower.contains("newpipe")

            val isMusic = lower.contains("music") || lower.contains("spotify") || lower.contains("audio") || lower.contains("soundcloud") || lower.contains("tidal")

            val isMedia = isYouTube || isMusic ||
                lower.contains("netflix") ||
                lower.contains("disney") ||
                lower.contains("primevideo") ||
                lower.contains("hotstar") ||
                lower.contains("twitch") ||
                lower.contains("bilibili") ||
                lower.contains("videoplayer") ||
                lower.contains("video.player") ||
                lower.contains("mxplayer") ||
                lower.contains("vlc") ||
                lower.contains("tiktok") ||
                lower.contains("snackvideo")

            // 4. SCREEN RECORDER — 100% IMMUNE (Strict dedicated matching without catching YouTube or cellbroadcast)
            val isRecorder = !isMedia && (
                NukeScreenRecordGuardian.isProtected(pkg) ||
                lower.contains("screenrecorder") ||
                lower.contains("screen.recorder") ||
                lower.contains("screen_recorder") ||
                lower.contains("screenrecord") ||
                lower.contains("xrecorder") ||
                lower.contains("mobizen") ||
                lower.contains("azscreenrecorder") ||
                lower.contains("vidma") ||
                lower.contains("streamlabs")
            )
            val isActivelyRecording = isRecorder && NukeScreenRecordGuardian.isActivelyRecording(context, pkg)

            // 5. DYNAMIC ACTIVE GAME CLASSIFICATION (100% Immunity)
            val isCategoryGame = appInfo != null && (
                (Build.VERSION.SDK_INT >= 26 && appInfo.category == ApplicationInfo.CATEGORY_GAME) ||
                ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
            )
            val gameMeta = detector.classifyGame(pkg)
            val isKnownCatalogGame = gameMeta != null
            val isEngineGame = detector.isLikelyGame(pkg)
            val isDynamicGame = isCategoryGame || isKnownCatalogGame || isEngineGame ||
                lower.contains("freefire") || lower.contains("dts.freefire") ||
                lower.contains("mobile.legends") || lower.contains("pubg") ||
                lower.contains("codm") || lower.contains("genshin") ||
                (activeGamePkg.isNotBlank() && lower == activeGamePkg.lowercase(Locale.US)) ||
                (lastGamePkg.isNotBlank() && lower == lastGamePkg.lowercase(Locale.US)) ||
                (resumedGamePkg.isNotBlank() && lower == resumedGamePkg.lowercase(Locale.US))

            val isPlayingNow = isDynamicGame && foregroundPkg.isNotBlank() && (
                lower == foregroundPkg.lowercase(Locale.US) ||
                lower.startsWith(foregroundPkg.lowercase(Locale.US)) ||
                foregroundPkg.lowercase(Locale.US).startsWith(lower)
            )

            val isCurrentForeground = foregroundPkg.isNotBlank() && (
                lower == foregroundPkg.lowercase(Locale.US) ||
                lower.startsWith(foregroundPkg.lowercase(Locale.US)) ||
                foregroundPkg.lowercase(Locale.US).startsWith(lower)
            )

            // Discard pure internal system services that are NOT Game Nuke, NOT recorder, NOT game, NOT media
            val isSys = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSys = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSys && !isUpdatedSys && !isMyPkg && !isRecorder && !isDynamicGame && !isMedia) {
                return@forEach
            }

            // Check running process importance to never kill visible/foreground processes
            val procImportance = runningProcesses.firstOrNull { proc ->
                proc.processName.lowercase(Locale.US).substringBefore(":") == lower ||
                    proc.pkgList?.any { it.lowercase(Locale.US) == lower } == true
            }?.importance ?: 999
            val isForegroundRunning = procImportance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE

            val label = if (isMyPkg) {
                "Game Nuke Premium"
            } else if (gameMeta != null) {
                gameMeta.label
            } else if (appInfo != null) {
                runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
            } else {
                pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            }

            when {
                isMyPkg -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = if (pid == 0) android.os.Process.myPid() else pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = true,
                            statusTag = "GAME NUKE"
                        )
                    )
                }
                isPlayingNow -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = true,
                            statusTag = "PLAYING NOW"
                        )
                    )
                }
                isDynamicGame -> {
                    val isRecent = recentTasksSet.contains(lower) || recentTasksSet.any { it.contains(lower) || lower.contains(it) }
                    val gameTag = if (isRecent) "GAME (RECENT)" else "GAME (BG)"
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = true,
                            statusTag = gameTag
                        )
                    )
                }
                isYouTube -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = false,
                            statusTag = "🎬 YOUTUBE"
                        )
                    )
                }
                isMedia -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = false,
                            statusTag = if (isMusic) "🎵 MUSIC" else "🎬 MEDIA"
                        )
                    )
                }
                isRecorder -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = true,
                            statusTag = if (isActivelyRecording) "🔴 RECORDING" else "🎥 RECORDER"
                        )
                    )
                }
                isCurrentForeground -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = false,
                            statusTag = "ACTIVE NOW"
                        )
                    )
                }
                isForegroundRunning -> {
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = false,
                            statusTag = "RUNNING"
                        )
                    )
                }
                else -> {
                    // ELIGIBLE BACKGROUND APP (Only genuine 3rd-party user background apps)
                    resultList.add(
                        BackgroundAppItem(
                            packageName = pkg,
                            appLabel = label,
                            pid = pid,
                            estimatedRamMb = ramMb,
                            isProtectedOrActive = false,
                            statusTag = ""
                        )
                    )
                }
            }
        }

        // Sort: Protected / Active items first, then by RAM footprint descending
        resultList.sortWith(
            compareByDescending<BackgroundAppItem> { it.isProtectedOrActive }
                .thenByDescending { it.estimatedRamMb }
        )
        resultList
    }

    private fun isSensitiveSystemPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val lower = pkg.lowercase(Locale.US)

        // Cell Broadcast & Emergency Alerts (Peringatan Darurat Nirkabel) - NEVER display in user task manager
        if (lower.contains("cellbroadcast") || lower.contains("emergency") || lower.contains("alert") || lower.contains("carrier")) return true
        if (lower.contains("telecom") || lower.contains("telephony") || lower.contains("incallui") || lower.contains("dialer")) return true
        if (lower.contains("stk") || lower.contains("sim") || lower.contains("radio") || lower.contains("bluetooth") || lower.contains("nfc")) return true

        // Dedicated screen recorders (keep visible and protected)
        if (!lower.contains("cellbroadcast") && !lower.contains("emergency") && !lower.contains("youtube") && (
            NukeScreenRecordGuardian.isProtected(lower) ||
            lower.contains("screenrecorder") ||
            lower.contains("screen.recorder") ||
            lower.contains("xrecorder") ||
            lower.contains("mobizen") ||
            lower.contains("azscreenrecorder") ||
            lower.contains("vidma")
        )) {
            return false
        }

        // Android core & framework services
        if (lower == "android" || lower.startsWith("android.")) return true
        if (lower.startsWith("media.") || lower.contains("swcodec") || lower.contains("hwcodec") || lower.contains("codec")) return true
        if (lower.contains("soter") || lower.contains("soterserver")) return true
        if (lower.startsWith("com.android.")) return true
        if (lower.startsWith("com.google.android.gms") || lower.startsWith("com.google.android.gsf") || lower.startsWith("com.google.android.inputmethod") || lower.startsWith("com.google.android.googlequicksearchbox")) return true

        // Xiaomi / HyperOS / MIUI security, powerkeeper, and system frameworks
        if (lower.startsWith("com.miui.") || lower.startsWith("com.xiaomi.") || lower.startsWith("com.lbe.")) return true

        // Samsung OneUI Knox, security, and framework services
        if (lower.startsWith("com.sec.") || lower.startsWith("com.samsung.")) return true

        // Oppo / Realme / OnePlus ColorOS & HeyTap frameworks
        if (lower.startsWith("com.oplus.") || lower.startsWith("com.coloros.") || lower.startsWith("com.nearme.") || lower.startsWith("com.heytap.")) return true

        // Vivo OriginOS / Funtouch & BBK frameworks
        if (lower.startsWith("com.vivo.") || lower.startsWith("com.iqoo.") || lower.startsWith("com.bbk.")) return true

        // Transsion (Infinix, Tecno, Itel) system services
        if (lower.startsWith("com.transsion.") || lower.startsWith("com.infinix.") || lower.startsWith("com.tecno.")) return true

        // Huawei & Honor frameworks
        if (lower.startsWith("com.huawei.") || lower.startsWith("com.hihonor.")) return true

        // Hardware abstraction, chipsets, and vendor daemons
        if (lower.startsWith("com.mediatek.") || lower.startsWith("com.qualcomm.") || lower.startsWith("com.qti.")) return true
        if (lower.startsWith("vendor.") || lower.startsWith("android.hardware.")) return true

        // Launchers, Keyboards, & Debugging bridges
        if (lower.contains("launcher") || lower.contains("trebuchet") || lower.contains(".home")) return true
        if (lower.contains("keyboard") || lower.contains("ime") || lower.contains("inputmethod")) return true
        if (lower.contains("shell") || lower.contains("shizuku") || lower.contains("iadb")) return true

        return false
    }

    // ─── Rendering the UI List ─────────────────────────────────────────────

    private fun renderTaskList(tasks: List<BackgroundAppItem>) {
        val container = taskListContainer ?: return
        container.removeAllViews()

        val totalRam = tasks.sumOf { it.estimatedRamMb }
        val killableCount = tasks.count { !it.isProtectedOrActive }
        taskCountTv?.text = "$killableCount ELIGIBLE APPS • ${tasks.size - killableCount} PROTECTED"
        totalRamTv?.text = "APP MEMORY FOOTPRINT: ~${totalRam} MB"

        if (tasks.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "✓ NO ELIGIBLE BACKGROUND APPS\nProtected and active processes are left untouched."
                textSize = 10f
                setTextColor(Color.parseColor("#7A9E94"))
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, (30 * d).toInt(), 0, (30 * d).toInt())
            }
            container.addView(emptyTv)
            return
        }

        tasks.forEach { item ->
            val row = buildTaskRow(item)
            container.addView(row)
        }
    }

    private fun buildTaskRow(item: BackgroundAppItem): View {
        val isProt = item.isProtectedOrActive

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (5 * d).toInt() }
            background = GradientDrawable().apply {
                if (isProt) {
                    setColor(Color.parseColor("#12161A"))
                    cornerRadius = 8 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#334155"))
                } else {
                    setColor(Color.parseColor("#0C1217"))
                    cornerRadius = 8 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#1A2530"))
                }
            }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }

        // App Initial Badge
        val avatar = TextView(context).apply {
            val letter = item.appLabel.take(1).uppercase(Locale.ROOT)
            text = when {
                item.statusTag.contains("GAME NUKE") -> "⚡"
                item.statusTag.contains("PLAYING NOW") -> "🎮"
                item.statusTag.contains("GAME") -> "🎯"
                item.statusTag.contains("YOUTUBE") -> "▶"
                item.statusTag.contains("ACTIVE NOW") -> "📱"
                item.statusTag.contains("RECORDING") -> "🔴"
                item.statusTag.contains("RECORDER") -> "🎥"
                item.statusTag.contains("MEDIA") -> "🎬"
                item.statusTag.contains("MUSIC") -> "🎵"
                letter.isNotBlank() -> letter
                else -> "•"
            }
            textSize = 11f
            setTextColor(if (isProt) Color.parseColor("#94A3B8") else Color.parseColor("#00E5C8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply {
                rightMargin = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                if (isProt) {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 6 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#475569"))
                } else {
                    setColor(Color.parseColor("#14222E"))
                    cornerRadius = 6 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#00E5C8"))
                }
            }
        }
        card.addView(avatar)

        // App Label, PID, Package Details & RAM
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * d).toInt()
            }
        }

        // Top line: Name + RAM footprint badge + Status Tag
        val titleLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameTv = TextView(context).apply {
            text = item.appLabel
            textSize = 9.5f
            setTextColor(if (isProt) Color.parseColor("#CBD5E1") else Color.parseColor("#E2E8F0"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val ramBadge = TextView(context).apply {
            text = " ${item.estimatedRamMb} MB "
            textSize = 7f
            setTextColor(if (isProt) Color.parseColor("#94A3B8") else Color.parseColor("#10B981"))
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(if (isProt) Color.parseColor("#1E293B") else Color.parseColor("#142B22"))
                cornerRadius = 3 * d
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (5 * d).toInt() }
        }
        titleLine.addView(nameTv)
        titleLine.addView(ramBadge)

        if (item.statusTag.isNotBlank()) {
            val statusTagTv = TextView(context).apply {
                text = " ${item.statusTag} "
                textSize = 6.2f
                setTextColor(
                    when {
                        item.statusTag.contains("RECORDING") -> Color.parseColor("#FF6B6B")
                        item.statusTag.contains("RECORDER") -> Color.parseColor("#C084FC")
                        item.statusTag.contains("PLAYING NOW") -> Color.parseColor("#10B981")
                        item.statusTag.contains("ACTIVE NOW") -> Color.parseColor("#10B981")
                        item.statusTag.contains("GAME NUKE") -> Color.parseColor("#F59E0B")
                        item.statusTag.contains("YOUTUBE") -> Color.parseColor("#FF0033")
                        item.statusTag.contains("GAME") -> Color.parseColor("#00E5C8")
                        item.statusTag.contains("MEDIA") -> Color.parseColor("#38BDF8")
                        item.statusTag.contains("MUSIC") -> Color.parseColor("#EC4899")
                        else -> Color.parseColor("#E2E8F0")
                    }
                )
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(if (isProt) Color.parseColor("#334155") else Color.parseColor("#1E293B"))
                    cornerRadius = 3 * d
                    if (!isProt) setStroke((0.6f * d).toInt(), Color.parseColor("#475569"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (4 * d).toInt() }
            }
            titleLine.addView(statusTagTv)
        }

        textCol.addView(titleLine)

        // Bottom line: PID and Package Name
        val pidText = if (item.pid > 0) "PID: ${item.pid} • " else ""
        val pkgTv = TextView(context).apply {
            text = "${pidText}${item.packageName}"
            textSize = 6.6f
            setTextColor(if (isProt) Color.parseColor("#64748B") else Color.parseColor("#7A9E94"))
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(pkgTv)
        card.addView(textCol)

        // Actions Row
        val actionsCol = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (isProt) {
            val badgeText = when {
                item.statusTag.contains("GAME NUKE") -> "🔒 NUKE"
                item.statusTag.contains("PLAYING NOW") -> "🔒 PLAYING"
                item.statusTag.contains("GAME (RECENT)") -> "🔒 GAME (RECENT)"
                item.statusTag.contains("GAME (BG)") -> "🔒 GAME (BG)"
                item.statusTag.contains("GAME") -> "🔒 GAME"
                item.statusTag.contains("YOUTUBE") -> "🔒 YOUTUBE"
                item.statusTag.contains("ACTIVE NOW") -> "🔒 ACTIVE"
                item.statusTag.contains("RECORDING") -> "🔒 RECORDING"
                item.statusTag.contains("RECORDER") -> "🔒 RECORDER"
                item.statusTag.contains("MEDIA") -> "🔒 MEDIA"
                item.statusTag.contains("MUSIC") -> "🔒 MUSIC"
                else -> "🔒 PROTECTED"
            }
            val badgeColor = when {
                item.statusTag.contains("RECORDING") -> Color.parseColor("#FF3D55")
                item.statusTag.contains("RECORDER") -> Color.parseColor("#A855F7")
                item.statusTag.contains("PLAYING NOW") -> Color.parseColor("#10B981")
                item.statusTag.contains("ACTIVE NOW") -> Color.parseColor("#10B981")
                item.statusTag.contains("GAME (RECENT)") -> Color.parseColor("#00E5C8")
                item.statusTag.contains("GAME (BG)") -> Color.parseColor("#00E5C8")
                item.statusTag.contains("GAME") -> Color.parseColor("#00E5C8")
                item.statusTag.contains("GAME NUKE") -> Color.parseColor("#F59E0B")
                item.statusTag.contains("YOUTUBE") -> Color.parseColor("#FF0033")
                item.statusTag.contains("MEDIA") -> Color.parseColor("#38BDF8")
                item.statusTag.contains("MUSIC") -> Color.parseColor("#EC4899")
                else -> Color.parseColor("#94A3B8")
            }
            val immuneBadge = TextView(context).apply {
                text = badgeText
                textSize = 7.5f
                setTextColor(badgeColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 5 * d
                    setStroke((0.8f * d).toInt(), badgeColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (26 * d).toInt()
                )
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                isClickable = false
                isFocusable = false
            }
            actionsCol.addView(immuneBadge)
        } else {
            // Balance / Trim RAM Button
            val balanceBtn = TextView(context).apply {
                text = "⚖ BAL"
                textSize = 7.5f
                setTextColor(Color.parseColor("#00E5C8"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#102B24"))
                    cornerRadius = 5 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#00E5C8"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (26 * d).toInt()
                ).apply { rightMargin = (4 * d).toInt() }
                setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    balanceTask(item, card, this)
                }
            }
            actionsCol.addView(balanceBtn)

            // End Task Button
            val endBtn = TextView(context).apply {
                text = "✕ END"
                textSize = 7.5f
                setTextColor(Color.parseColor("#FF3D55"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#261115"))
                    cornerRadius = 5 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#FF3D55"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (26 * d).toInt()
                )
                setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    terminateTask(item, card)
                }
            }
            actionsCol.addView(endBtn)
        }

        card.addView(actionsCol)
        return card
    }

    // ─── Process Termination & Optimization ─────────────────────────────────

    private fun balanceTask(item: BackgroundAppItem, cardView: View, balanceBtn: TextView) {
        if (item.isProtectedOrActive) {
            actionStatusTv?.text = "PROTECTED PROCESS • CANNOT COMPACT"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        scope.launch {
            try {
                val cmd = "am compact ${item.packageName} full 2>/dev/null"
                if (AdbManager.getInstance(context).isConnected()) {
                    AdbManager.getInstance(context).executeCommand(cmd, "/", 2_500L)
                } else {
                    NukeConnectionManager.executeCommand(cmd, 2_500L)
                }
                withContext(Dispatchers.Main) {
                    balanceBtn.text = "✓ OK"
                    balanceBtn.setTextColor(Color.parseColor("#10B981"))
                    balanceBtn.isClickable = false
                    actionStatusTv?.text = "✓ MEMORY COMPACTED: ${item.appLabel.uppercase()}"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "balanceTask error: ${t.message}", t)
            }
        }
    }

    private fun terminateTask(item: BackgroundAppItem, cardView: View) {
        val myPkg = context.packageName.lowercase(Locale.US)
        val pkg = item.packageName.trim().lowercase(Locale.US)
        val activeGame = NukeRuntimeState.state.value.activePackage?.trim()?.lowercase(Locale.US).orEmpty()
        val myPid = android.os.Process.myPid()
        val isNukeCore = item.pid == myPid ||
            pkg == myPkg ||
            pkg == "com.neon.gametweak" ||
            pkg.contains("gametweak") ||
            pkg.contains("wandev") ||
            pkg.contains("axeron") ||
            pkg.contains("nuke") ||
            pkg.contains("shell") ||
            pkg.contains("shizuku") ||
            pkg.contains("iadb")

        if (isNukeCore) {
            actionStatusTv?.text = "GAME NUKE CORE • CANNOT TERMINATE SELF"
            actionStatusTv?.setTextColor(Color.parseColor("#EF4444"))
            return
        }

        val isRecorder = NukeScreenRecordGuardian.isProtected(item.packageName) ||
            pkg.contains("screenrecorder") ||
            pkg.contains("screen.recorder") ||
            pkg.contains("screen_recorder") ||
            pkg.contains("xrecorder") ||
            pkg.contains("mobizen") ||
            pkg.contains("azscreenrecorder") ||
            pkg.contains("vidma")

        if (isRecorder) {
            actionStatusTv?.text = "SCREEN RECORDER • PROTECTED"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        val detectorSync = ActiveGameDetector(context)
        if (item.isProtectedOrActive || detectorSync.isLikelyGame(pkg) || detectorSync.classifyGame(pkg) != null || NukeProcessPurgeGuardian.isProtected(context, item.packageName)) {
            actionStatusTv?.text = "GAME / PROTECTED TASK • CANNOT TERMINATE"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        val isCurrentPlaying = activeGame.isNotBlank() && (pkg == activeGame || pkg.startsWith("$activeGame:"))
        if (isCurrentPlaying) {
            actionStatusTv?.text = "ACTIVE GAME • CANNOT TERMINATE ACTIVE GAME"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        if (isSensitiveSystemPackage(pkg)) {
            actionStatusTv?.text = "SYSTEM CORE • PROTECTED"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        scope.launch {
            try {
                val detector = ActiveGameDetector(context, AdbManager.getInstance(context))
                val resumedGame = runCatching { detector.detectState().resumedGame?.packageName?.lowercase(Locale.US) }.getOrNull().orEmpty()
                val foregroundPkg = resolveForegroundPackage(detector).lowercase(Locale.US)
                val currentPlayingGame = when {
                    activeGame.isNotBlank() -> activeGame
                    resumedGame.isNotBlank() -> resumedGame
                    foregroundPkg.isNotBlank() && (detector.isLikelyGame(foregroundPkg) || detector.classifyGame(foregroundPkg) != null) -> foregroundPkg
                    else -> ""
                }

                if (currentPlayingGame.isNotBlank() && (pkg == currentPlayingGame || pkg.startsWith("$currentPlayingGame:"))) {
                    withContext(Dispatchers.Main) {
                        actionStatusTv?.text = "ACTIVE GAME • CANNOT TERMINATE ACTIVE GAME"
                        actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
                    }
                    return@launch
                }

                val activeGameCase = if (currentPlayingGame.isNotBlank()) "*$currentPlayingGame*)" else ""
                val cmd = """
                    case "${item.packageName}" in
                      *com.neon.gametweak*|*gametweak*|*wandev*|*axeron*|*nuke*|*shell*|*shizuku*|*iadb*)
                        ;;
                      *screenrecorder*|*screenrecord*|*recorder*|*screencap*|*smartcapture*|*xrecorder*|*mobizen*|*azscreenrecorder*|*vidma*|*streamlabs*)
                        ;;
                      $activeGameCase
                        ;;
                      android|com.android.*|com.google.android.*|com.miui.*|com.xiaomi.*|com.lbe.*|com.sec.*|com.samsung.*|com.oplus.*|com.coloros.*|com.vivo.*|com.transsion.*|com.huawei.*|com.mediatek.*|com.qualcomm.*)
                        ;;
                      *)
                        am force-stop "${item.packageName}" 2>/dev/null
                        ;;
                    esac
                """.trimIndent()
                if (AdbManager.getInstance(context).isConnected()) {
                    AdbManager.getInstance(context).executeCommand(cmd, "/", 2_500L)
                } else {
                    val res = NukeConnectionManager.executeCommand(cmd, 2_500L)
                    if (res == null || !res.isSuccess) {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        runCatching { am?.killBackgroundProcesses(item.packageName) }
                    }
                }

                withContext(Dispatchers.Main) {
                    // Smooth card removal
                    cardView.animate()
                        .alpha(0f)
                        .scaleY(0f)
                        .setDuration(160)
                        .withEndAction {
                            taskListContainer?.removeView(cardView)
                        }
                        .start()

                    actionStatusTv?.text = "Ended: ${item.appLabel.uppercase()} • estimated ${item.estimatedRamMb} MB available"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "terminateTask error: ${t.message}", t)
            }
        }
    }

    private fun balanceAllSafeProcesses() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.VISIBLE
                    actionStatusTv?.text = "REQUESTING MEMORY COMPACTION FOR ELIGIBLE APPS..."
                    actionStatusTv?.setTextColor(Color.parseColor("#00E5C8"))
                }

                val myPkg = context.packageName.lowercase(Locale.US)
                val activeGame = NukeRuntimeState.state.value.activePackage?.trim()?.lowercase(Locale.US).orEmpty()
                val detector = ActiveGameDetector(context, AdbManager.getInstance(context))
                val resumedGame = runCatching { detector.detectState()?.resumedGame?.packageName?.lowercase(Locale.US) }.getOrNull().orEmpty()
                val foregroundPkg = resolveForegroundPackage(detector).lowercase(Locale.US)
                val currentPlayingGame = when {
                    activeGame.isNotBlank() -> activeGame
                    resumedGame.isNotBlank() -> resumedGame
                    foregroundPkg.isNotBlank() && (detector.isLikelyGame(foregroundPkg) || detector.classifyGame(foregroundPkg) != null) -> foregroundPkg
                    else -> ""
                }

                val allTasks = queryRunningUserApps()
                val balanceTasks = allTasks.filter { task ->
                    val p = task.packageName.trim().lowercase(Locale.US)
                    !task.isProtectedOrActive &&
                    p != myPkg &&
                    p != "com.neon.gametweak" &&
                    (currentPlayingGame.isBlank() || (p != currentPlayingGame && !p.startsWith("$currentPlayingGame:"))) &&
                    !p.contains("gametweak") &&
                    !p.contains("wandev") &&
                    !p.contains("axeron") &&
                    !p.contains("nuke") &&
                    !p.contains("shell") &&
                    !p.contains("shizuku") &&
                    !p.contains("iadb") &&
                    !detector.isLikelyGame(p) &&
                    detector.classifyGame(p) == null &&
                    !NukeProcessPurgeGuardian.isProtected(context, task.packageName) &&
                    !NukeScreenRecordGuardian.isProtected(task.packageName) &&
                    !p.contains("screenrecorder") &&
                    !p.contains("xrecorder") &&
                    !p.contains("mobizen") &&
                    !p.contains("azscreenrecorder") &&
                    !p.contains("vidma") &&
                    !isSensitiveSystemPackage(p)
                }

                if (balanceTasks.isNotEmpty()) {
                    val pkgs = balanceTasks.joinToString(" ") { it.packageName }
                    val activeGameCase = if (currentPlayingGame.isNotBlank()) "*$currentPlayingGame*)" else ""
                    val script = """
                        for PKG in $pkgs; do
                          if [ -z "${'$'}PKG" ] || [ "${'$'}PKG" = "$myPkg" ] || [ -n "$currentPlayingGame" -a "${'$'}PKG" = "$currentPlayingGame" ]; then
                            continue
                          fi
                          case "${'$'}PKG" in
                            *com.neon.gametweak*|*gametweak*|*wandev*|*axeron*|*nuke*|*shell*|*shizuku*|*iadb*)
                              continue ;;
                            *screenrecorder*|*screenrecord*|*recorder*|*screencap*|*smartcapture*|*xrecorder*|*mobizen*|*azscreenrecorder*|*vidma*|*streamlabs*)
                              continue ;;
                            *freefire*|*dts*|*garena*|*mobilelegends*|*pubg*|*codm*|*genshin*|*honkai*|*roblox*|*unity*|*epicgames*)
                              continue ;;
                            $activeGameCase
                              continue ;;
                            android|com.android.*|com.google.android.*|com.miui.*|com.xiaomi.*|com.lbe.*|com.sec.*|com.samsung.*|com.oplus.*|com.coloros.*|com.vivo.*|com.transsion.*|com.huawei.*|com.mediatek.*|com.qualcomm.*)
                              continue ;;
                            *)
                              cmd activity send-trim-memory "${'$'}PKG" RUNNING_LOW 2>/dev/null
                              am kill "${'$'}PKG" 2>/dev/null
                              ;;
                          esac
                        done
                        sync 2>/dev/null
                    """.trimIndent()

                    if (AdbManager.getInstance(context).isConnected()) {
                        AdbManager.getInstance(context).executeCommand(script, "/", 4_500L)
                    } else {
                        NukeConnectionManager.executeCommand(script, 4_500L)
                    }
                }

                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    actionStatusTv?.text = "✓ BALANCE: COMPACTED MEMORY FOR ${balanceTasks.size} BACKGROUND APP(S)"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                    refreshTasksList()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "balanceAllSafeProcesses failed safely: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    actionStatusTv?.text = "✓ MEMORY COMPACTION COMPLETE"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                    refreshTasksList()
                }
            }
        }
    }

    private fun killAllSafeProcesses() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.VISIBLE
                    actionStatusTv?.text = "ENDING ELIGIBLE BACKGROUND TASKS..."
                    actionStatusTv?.setTextColor(Color.parseColor("#00E5C8"))
                }

                val myPkg = context.packageName.lowercase(Locale.US)
                val activeGame = NukeRuntimeState.state.value.activePackage?.trim()?.lowercase(Locale.US).orEmpty()
                val detector = ActiveGameDetector(context, AdbManager.getInstance(context))
                val resumedGame = runCatching { detector.detectState()?.resumedGame?.packageName?.lowercase(Locale.US) }.getOrNull().orEmpty()
                val foregroundPkg = resolveForegroundPackage(detector).lowercase(Locale.US)
                val currentPlayingGame = when {
                    activeGame.isNotBlank() -> activeGame
                    resumedGame.isNotBlank() -> resumedGame
                    foregroundPkg.isNotBlank() && (detector.isLikelyGame(foregroundPkg) || detector.classifyGame(foregroundPkg) != null) -> foregroundPkg
                    else -> ""
                }

                val allTasks = queryRunningUserApps()

                val myPid = android.os.Process.myPid()
                val killableTasks = allTasks.filter { task ->
                    val p = task.packageName.trim().lowercase(Locale.US)
                    task.pid != myPid &&
                    !task.isProtectedOrActive &&
                    p != myPkg &&
                    p != "com.neon.gametweak" &&
                    (currentPlayingGame.isBlank() || (p != currentPlayingGame && !p.startsWith("$currentPlayingGame:"))) &&
                    !p.contains("gametweak") &&
                    !p.contains("wandev") &&
                    !p.contains("axeron") &&
                    !p.contains("nuke") &&
                    !p.contains("shell") &&
                    !p.contains("shizuku") &&
                    !p.contains("iadb") &&
                    !detector.isLikelyGame(p) &&
                    detector.classifyGame(p) == null &&
                    !NukeProcessPurgeGuardian.isProtected(context, task.packageName) &&
                    !NukeScreenRecordGuardian.isProtected(task.packageName) &&
                    !p.contains("screenrecorder") &&
                    !p.contains("xrecorder") &&
                    !p.contains("mobizen") &&
                    !p.contains("azscreenrecorder") &&
                    !p.contains("vidma") &&
                    !isSensitiveSystemPackage(p)
                }

                val adb = AdbManager.getInstance(context)
                val isPrivileged = adb.isConnected() || NukeConnectionManager.isConnected()

                if (killableTasks.isNotEmpty()) {
                    if (isPrivileged) {
                        val pkgs = killableTasks.joinToString(" ") { it.packageName }
                        val activeGameCase = if (currentPlayingGame.isNotBlank()) "*$currentPlayingGame*)" else ""
                        val script = """
                            for PKG in $pkgs; do
                              if [ -z "${'$'}PKG" ] || [ "${'$'}PKG" = "$myPkg" ] || [ -n "$currentPlayingGame" -a "${'$'}PKG" = "$currentPlayingGame" ]; then
                                continue
                              fi
                              case "${'$'}PKG" in
                                *com.neon.gametweak*|*gametweak*|*wandev*|*axeron*|*nuke*|*shell*|*shizuku*|*iadb*)
                                  continue ;;
                                *screenrecorder*|*screenrecord*|*recorder*|*screencap*|*smartcapture*|*xrecorder*|*mobizen*|*azscreenrecorder*|*vidma*|*streamlabs*)
                                  continue ;;
                                *freefire*|*dts*|*garena*|*mobilelegends*|*pubg*|*codm*|*genshin*|*honkai*|*roblox*|*unity*|*epicgames*)
                                  continue ;;
                                $activeGameCase
                                  continue ;;
                                android|com.android.*|com.google.android.*|com.miui.*|com.xiaomi.*|com.lbe.*|com.sec.*|com.samsung.*|com.oplus.*|com.coloros.*|com.vivo.*|com.transsion.*|com.huawei.*|com.mediatek.*|com.qualcomm.*)
                                  continue ;;
                                *)
                                  am force-stop "${'$'}PKG" 2>/dev/null
                                  ;;
                              esac
                            done
                            sync 2>/dev/null
                        """.trimIndent()

                        if (adb.isConnected()) {
                            adb.executeCommand(script, "/", 5_000L)
                        } else {
                            NukeConnectionManager.executeCommand(script, 5_000L)
                        }
                    } else {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        killableTasks.forEach { task ->
                            runCatching { am?.killBackgroundProcesses(task.packageName) }
                        }
                    }
                }

                val freedTotalMb = killableTasks.sumOf { it.estimatedRamMb }

                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    actionStatusTv?.text = "Completed: ${killableTasks.size} eligible app(s) ended • estimated ${freedTotalMb} MB available"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                    refreshTasksList()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "killAllSafeProcesses failed safely: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    loadingProgressBar?.visibility = View.GONE
                    actionStatusTv?.text = "Process action completed; protected game and system services were excluded"
                    actionStatusTv?.setTextColor(Color.parseColor("#10B981"))
                    refreshTasksList()
                }
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}
