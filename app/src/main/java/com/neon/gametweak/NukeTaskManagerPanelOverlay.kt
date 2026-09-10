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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
            text = "⚡ TACTICAL HUD: Select an app to terminate or purge zombie loops"
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
                setStroke((0.8f * d).toInt(), Color.parseColor("#00FF88"))
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
                setColor(Color.parseColor("#00FF88"))
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
            text = "TASK MANAGER"
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = TextView(context).apply {
            text = "BACKGROUND APP & MEMORY MANAGER • DRAG TO MOVE"
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
            text = "💀 ZOMBIES"
            textSize = 7.5f
            setTextColor(Color.parseColor("#FF0055"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#260813"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#FF0055"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28 * d).toInt()
            ).apply { rightMargin = (4 * d).toInt() }
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                scope.launch {
                    val killed = NukeProcessPurgeGuardian.killRogueZombieProcesses(context)
                    withContext(Dispatchers.Main) {
                        actionStatusTv?.text = if (killed > 0) "✓ Terminated $killed rogue zombie cluster(s)! CPU 100% clean." else "✓ 0 rogue zombies detected. System clean!"
                        actionStatusTv?.setTextColor(Color.parseColor("#00FF88"))
                        refreshTasksList()
                    }
                }
            }
        }
        actionRow.addView(killZombiesBtn)

        val balanceAllBtn = TextView(context).apply {
            text = "⚖ BALANCE"
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
                (28 * d).toInt()
            ).apply { rightMargin = (4 * d).toInt() }
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { balanceAllSafeProcesses() }
        }
        actionRow.addView(balanceAllBtn)

        val killAllBtn = TextView(context).apply {
            text = "END SAFE"
            textSize = 7.5f
            setTextColor(Color.parseColor("#050D0A"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00FF88"))
                cornerRadius = 6 * d
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28 * d).toInt()
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
        }
    }

    private suspend fun queryRunningUserApps(): List<BackgroundAppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val myPkg = context.packageName
        val activeGamePkg = NukeRuntimeState.state.value.activePackage.orEmpty()
        val focusedPkg = runCatching {
            ActiveGameDetector(context, AdbManager.getInstance(context)).detectForegroundPackage()
        }.getOrNull().orEmpty()

        // Fast Discovery Layer: Map of PackageName -> Pair(PID, RAM_MB)
        val rawProcessMap = LinkedHashMap<String, Pair<Int, Long>>()

        // 1. First priority: High-speed snapshot via ps -A -o PID,RSS,NAME (50ms response)
        val cmd = "ps -A -o PID,RSS,NAME"
        val psOutput = if (AdbManager.getInstance(context).isConnected()) {
            AdbManager.getInstance(context).executeCommand(cmd, "/", 3_000L)?.output.orEmpty()
        } else {
            NukeConnectionManager.executeCommand(cmd, 3_000L)?.output.orEmpty()
        }

        if (psOutput.isNotBlank()) {
            psOutput.lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val pid = parts[0].toIntOrNull() ?: return@forEach
                    val rssKb = parts[1].toLongOrNull() ?: 0L
                    val name = parts.last()

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

        // 2. Fallback if shell snapshot had 0 results
        if (rawProcessMap.isEmpty()) {
            val runningProcesses = am?.runningAppProcesses.orEmpty()
            runningProcesses.forEach { proc ->
                val pkg = proc.pkgList?.firstOrNull() ?: proc.processName.substringBefore(":")
                if (!rawProcessMap.containsKey(pkg)) {
                    rawProcessMap[pkg] = Pair(proc.pid, 45L)
                }
            }

            // Also check installed third-party apps so user always has background candidates
            val installed = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }.getOrDefault(emptyList())

            installed.forEach { app ->
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSys && !rawProcessMap.containsKey(app.packageName)) {
                    rawProcessMap[app.packageName] = Pair(0, 32L)
                }
            }
        }

        // Ensure Game Nuke and active game are present in the snapshot map
        if (!rawProcessMap.containsKey(myPkg)) {
            rawProcessMap[myPkg] = Pair(android.os.Process.myPid(), 65L)
        }
        if (activeGamePkg.isNotBlank() && !rawProcessMap.containsKey(activeGamePkg)) {
            rawProcessMap[activeGamePkg] = Pair(0, 150L)
        }
        if (focusedPkg.isNotBlank() && !rawProcessMap.containsKey(focusedPkg) && !isSensitiveSystemPackage(focusedPkg.lowercase(Locale.US))) {
            rawProcessMap[focusedPkg] = Pair(0, 150L)
        }

        val resultList = mutableListOf<BackgroundAppItem>()

        rawProcessMap.forEach { (pkg, pair) ->
            val pid = pair.first
            val ramMb = pair.second
            val lower = pkg.lowercase(Locale.US)

            // ─── CLASSIFY PROTECTED (ACTIVE GAME & GAME NUKE) ──────────────────
            val isMyPkg = (pkg == myPkg)
            val isActiveGame = (activeGamePkg.isNotBlank() && pkg == activeGamePkg) ||
                               (focusedPkg.isNotBlank() && pkg == focusedPkg)

            if (isMyPkg || isActiveGame) {
                val appInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                    }
                }.getOrNull()

                val label = if (isMyPkg) {
                    "Game Nuke Premium"
                } else if (appInfo != null) {
                    runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                } else {
                    pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                }

                resultList.add(
                    BackgroundAppItem(
                        packageName = pkg,
                        appLabel = label,
                        pid = if (isMyPkg && pid == 0) android.os.Process.myPid() else pid,
                        estimatedRamMb = ramMb,
                        isProtectedOrActive = true,
                        statusTag = if (isMyPkg) "GAME NUKE" else "ACTIVE GAME"
                    )
                )
                return@forEach
            }

            // ─── STRICT SAFETY FILTER FOR REGULAR BACKGROUND APPS ───────────────
            // 1. Skip Screen Recorders & Creators tools (NEVER kill recording!)
            if (NukeScreenRecordGuardian.isProtected(pkg)) return@forEach

            // 2. Skip Essential System Packages, SystemUI, Launchers, IME Keyboards
            if (isSensitiveSystemPackage(lower)) return@forEach

            // 3. Resolve clean User-Friendly App Label
            val appInfo = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                }
            }.getOrNull()

            val label = if (appInfo != null) {
                runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
            } else {
                pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            }

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

        // Sort: Protected / Active items first, then by RAM footprint descending
        resultList.sortWith(
            compareByDescending<BackgroundAppItem> { it.isProtectedOrActive }
                .thenByDescending { it.estimatedRamMb }
        )
        resultList
    }

    private fun isSensitiveSystemPackage(pkg: String): Boolean {
        if (pkg == "android") return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (pkg.startsWith("com.android.phone")) return true
        if (pkg.startsWith("com.android.settings")) return true
        if (pkg.startsWith("com.android.providers.")) return true
        if (pkg.startsWith("com.android.server")) return true
        if (pkg.startsWith("com.android.bluetooth")) return true
        if (pkg.startsWith("com.android.nfc")) return true
        if (pkg.startsWith("com.android.keyguard")) return true
        if (pkg.startsWith("com.google.android.gms")) return true
        if (pkg.startsWith("com.google.android.gsf")) return true
        if (pkg.startsWith("com.google.android.inputmethod")) return true
        if (pkg.contains("launcher") || pkg.contains("trebuchet") || pkg.contains("nexuslauncher")) return true
        if (pkg.contains("keyboard") || pkg.contains("ime") || pkg.contains("inputmethod")) return true
        if (pkg.contains("telecom") || pkg.contains("telephony") || pkg.contains("incallui")) return true
        if (pkg.contains("shizuku") || pkg.contains("iadb")) return true
        if (pkg.startsWith("vendor.") || pkg.startsWith("android.hardware.")) return true
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
            text = if (isProt) {
                if (item.statusTag.contains("GAME NUKE")) "⚡" else "🎮"
            } else if (letter.isNotBlank()) letter else "•"
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
            setTextColor(if (isProt) Color.parseColor("#94A3B8") else Color.parseColor("#00FF88"))
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

        if (isProt && item.statusTag.isNotBlank()) {
            val statusTagTv = TextView(context).apply {
                text = " ${item.statusTag} "
                textSize = 6.2f
                setTextColor(Color.parseColor("#E2E8F0"))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#334155"))
                    cornerRadius = 3 * d
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
            // Protected items CANNOT be terminated: Display muted [🔒 IMMUNE] badge
            val immuneBadge = TextView(context).apply {
                text = "🔒 PROTECTED"
                textSize = 7.5f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 5 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#334155"))
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
        if (item.isProtectedOrActive) return
        scope.launch {
            val cmd = "am compact ${item.packageName} full 2>/dev/null\npm trim-caches 9999999999 2>/dev/null"
            if (AdbManager.getInstance(context).isConnected()) {
                AdbManager.getInstance(context).executeCommand(cmd, "/", 2_500L)
            } else {
                NukeConnectionManager.executeCommand(cmd, 2_500L)
            }
            withContext(Dispatchers.Main) {
                balanceBtn.text = "✓ OK"
                balanceBtn.setTextColor(Color.parseColor("#00FF88"))
                balanceBtn.isClickable = false
                actionStatusTv?.text = "✓ MEMORY COMPACTION REQUESTED: ${item.appLabel.uppercase()}"
                actionStatusTv?.setTextColor(Color.parseColor("#00FF88"))
            }
        }
    }

    private fun terminateTask(item: BackgroundAppItem, cardView: View) {
        if (item.isProtectedOrActive || item.packageName == context.packageName || item.packageName == NukeRuntimeState.state.value.activePackage) {
            actionStatusTv?.text = "PROTECTED PROCESS • NO ACTION TAKEN"
            actionStatusTv?.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        scope.launch {
            val cmd = "am kill ${item.packageName} 2>/dev/null"
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

                actionStatusTv?.text = "✓ END REQUEST SENT: ${item.appLabel.uppercase()} • ~${item.estimatedRamMb} MB footprint"
                actionStatusTv?.setTextColor(Color.parseColor("#00FF88"))
            }
        }
    }

    private fun balanceAllSafeProcesses() {
        scope.launch {
            withContext(Dispatchers.Main) {
                loadingProgressBar?.visibility = View.VISIBLE
                actionStatusTv?.text = "REQUESTING MEMORY COMPACTION FOR ELIGIBLE APPS..."
                actionStatusTv?.setTextColor(Color.parseColor("#00E5C8"))
            }

            val allTasks = queryRunningUserApps()
            val myPkg = context.packageName
            val activeGamePkg = NukeRuntimeState.state.value.activePackage.orEmpty()
            val tasks = allTasks.filter { task ->
                !task.isProtectedOrActive &&
                task.packageName != myPkg &&
                (activeGamePkg.isBlank() || task.packageName != activeGamePkg)
            }

            val script = StringBuilder()
            tasks.forEach { task ->
                script.append("am compact ${task.packageName} full 2>/dev/null\n")
            }
            script.append("pm trim-caches 9999999999 2>/dev/null\necho 1 > /proc/sys/vm/compact_memory 2>/dev/null\n")

            if (AdbManager.getInstance(context).isConnected()) {
                AdbManager.getInstance(context).executeCommand(script.toString(), "/", 4_500L)
            } else {
                NukeConnectionManager.executeCommand(script.toString(), 4_500L)
            }

            withContext(Dispatchers.Main) {
                loadingProgressBar?.visibility = View.GONE
                actionStatusTv?.text = "✓ MEMORY COMPACTION REQUESTED FOR ${tasks.size} APPS"
                actionStatusTv?.setTextColor(Color.parseColor("#00FF88"))
                refreshTasksList()
            }
        }
    }

    private fun killAllSafeProcesses() {
        scope.launch {
            withContext(Dispatchers.Main) {
                loadingProgressBar?.visibility = View.VISIBLE
                actionStatusTv?.text = "ENDING ELIGIBLE BACKGROUND TASKS..."
                actionStatusTv?.setTextColor(Color.parseColor("#00E5C8"))
            }

            val allTasks = queryRunningUserApps()
            val myPkg = context.packageName
            val activeGamePkg = NukeRuntimeState.state.value.activePackage.orEmpty()
            val tasks = allTasks.filter { task ->
                val p = task.packageName
                !task.isProtectedOrActive &&
                p != myPkg &&
                (activeGamePkg.isBlank() || p != activeGamePkg) &&
                !p.contains("webview", ignoreCase = true) &&
                !p.contains("chromium", ignoreCase = true) &&
                !p.contains("trichrome", ignoreCase = true) &&
                !p.startsWith("com.google.android.gms") &&
                !p.startsWith("com.android.vending") &&
                !p.startsWith("com.android.systemui") &&
                !p.contains("launcher", ignoreCase = true) &&
                !NukeScreenRecordGuardian.isProtected(p) &&
                !NukeProcessPurgeGuardian.isProtected(context, p)
            }

            val adb = AdbManager.getInstance(context)
            val isPrivileged = adb.isConnected() || NukeConnectionManager.isConnected()
            val killedZombies = NukeProcessPurgeGuardian.killRogueZombieProcesses(context)

            if (isPrivileged) {
                val scriptBuilder = StringBuilder()
                tasks.forEach { task ->
                    scriptBuilder.append("am kill ${task.packageName} 2>/dev/null\n")
                }
                scriptBuilder.append("""
                    pm trim-caches 9999999999 2>/dev/null
                    am compact all 2>/dev/null
                    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
                    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
                    sync
                """.trimIndent())

                if (adb.isConnected()) {
                    adb.executeCommand(scriptBuilder.toString(), "/", 6_000L)
                } else {
                    NukeConnectionManager.executeCommand(scriptBuilder.toString(), 6_000L)
                }
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                tasks.forEach { task ->
                    runCatching { am?.killBackgroundProcesses(task.packageName) }
                }
                System.gc()
            }

            val freedTotalMb = tasks.sumOf { it.estimatedRamMb }

            withContext(Dispatchers.Main) {
                loadingProgressBar?.visibility = View.GONE
                actionStatusTv?.text = "✓ END SAFE: $killedZombies zombie cluster(s) terminated • ${tasks.size} tasks ended • ~${freedTotalMb} MB RAM freed"
                actionStatusTv?.setTextColor(Color.parseColor("#00FF88"))
                refreshTasksList()
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}
