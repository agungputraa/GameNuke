package com.neon.gametweak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.neon.gametweak.NukeSystemParamGuardian.ParamCategory
import com.neon.gametweak.NukeSystemParamGuardian.ParamSource
import com.neon.gametweak.NukeSystemParamGuardian.RiskLevel
import com.neon.gametweak.NukeSystemParamRepository.NukeSystemParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NukeSystemEditorFloatingOverlay — Enterprise In-Game Device System Editor.
 *
 * Professional executive dark UI designed for non-disruptive in-game parameter editing.
 * Uses a refined obsidian / slate palette with crisp typography, responsive layout,
 * horizontally scrollable quick actions, and clean cards.
 */
class NukeSystemEditorFloatingOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var loadJob: Job? = null
    private var dynamicSearchJob: Job? = null

    private var cachedParams = listOf<NukeSystemParam>()
    private var dynamicParams = listOf<NukeSystemParam>()
    private var currentFilter = ""
    private var activeCategoryFilter: ParamCategory? = null
    private var showOnlyModified = false
    private var showOnlyCurated = false
    private var showOnlyBrand = false
    private var expandedEditParamId: String? = null

    private var paramsContainer: LinearLayout? = null
    private var countBadgeTv: TextView? = null
    private var progressBar: ProgressBar? = null
    private var searchEt: EditText? = null
    private var categoryTabsRow: LinearLayout? = null

    val isShowing: Boolean get() = rootView != null && rootView?.isAttachedToWindow == true

    companion object {
        private const val TAG = "NukeSysEditorOverlay"

        // ── Enterprise Obsidian & Slate Palette ─────────────────────────────────
        private const val BG             = "#F7070B10"   // 97% opaque obsidian black
        private const val SURFACE        = "#FF0E1620"   // Card surface
        private const val SURFACE_HIGH   = "#FF141F2C"   // Elevated / input surface
        private const val SURFACE_INSET  = "#FF090E14"   // Inset panel
        private const val BORDER         = "#FF1E293B"   // Slate border
        private const val BORDER_ACTIVE  = "#FF334B64"   // Focused / active border
        private const val TEXT_PRIMARY   = "#FFF1F5F9"   // Slate 100
        private const val TEXT_SECONDARY = "#FF94A3B8"   // Slate 400
        private const val TEXT_DIM       = "#FF64748B"   // Slate 500
        private const val ACCENT         = "#FF065F46"   // Muted emerald
        private const val ACCENT_BRIGHT  = "#FF10B981"   // Emerald 500
        private const val AMBER          = "#FFF59E0B"   // Amber for modified
        private const val ROSE           = "#FFEF4444"   // Rose for restricted
        private const val CYAN           = "#FF38BDF8"   // Sky blue

        @Volatile
        private var instance: NukeSystemEditorFloatingOverlay? = null

        fun getInstance(context: Context): NukeSystemEditorFloatingOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeSystemEditorFloatingOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    fun toggle(): Boolean = if (isShowing) { hide(); false } else { show(); true }

    fun show() {
        if (isShowing) return
        mainHandler.post { buildAndAttachWindow() }
    }

    fun hide() {
        mainHandler.post {
            loadJob?.cancel()
            dynamicSearchJob?.cancel()
            rootView?.let {
                runCatching { wm.removeView(it) }
                rootView = null
            }
        }
    }

    private fun dp(v: Float): Int = (v * d).toInt()

    private fun buildAndAttachWindow() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Responsive window dimension calculation
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val screenHeightDp = displayMetrics.heightPixels / displayMetrics.density
        val targetWidthDp = (screenWidthDp * 0.94f).coerceIn(330f, 430f)
        val targetHeightDp = (screenHeightDp * 0.72f).coerceIn(460f, 660f)

        val width = dp(targetWidthDp)
        val height = dp(targetHeightDp)

        rootParams = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        // ── Root Window Frame ────────────────────────────────────────────────────
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BG))
                cornerRadius = dp(14f).toFloat()
                setStroke(dp(1.2f), Color.parseColor(BORDER))
            }
            clipToOutline = true
            elevation = dp(12f).toFloat()
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── 1. Header Bar (Draggable with sleek handle) ──────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(SURFACE))
            }
        }

        // Drag handle bar
        val handleBarWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(2f))
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF334155"))
                    cornerRadius = dp(2f).toFloat()
                }
            })
        }
        header.addView(handleBarWrapper)

        val headerContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(4f), dp(12f), dp(10f))
        }

        var initialX = 0; var initialY = 0
        var touchStartX = 0f; var touchStartY = 0f
        val touchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = rootParams?.x ?: 0; initialY = rootParams?.y ?: 0
                    touchStartX = event.rawX; touchStartY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    rootParams?.x = initialX + (event.rawX - touchStartX).toInt()
                    rootParams?.y = initialY + (event.rawY - touchStartY).toInt()
                    rootView?.let { wm.updateViewLayout(it, rootParams) }; true
                }
                else -> false
            }
        }
        header.setOnTouchListener(touchListener)
        headerContent.setOnTouchListener(touchListener)

        // Title + badge
        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(context).apply {
            text = "⚡ SYSTEM EDITOR"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
        titleBox.addView(titleTv)

        countBadgeTv = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor(CYAN))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6f), dp(1.5f), dp(6f), dp(1.5f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(SURFACE_HIGH))
                cornerRadius = dp(4f).toFloat()
                setStroke(dp(0.6f), Color.parseColor(BORDER_ACTIVE))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) }
        }
        titleBox.addView(countBadgeTv)
        headerContent.addView(titleBox)

        // Close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(SURFACE_HIGH))
                cornerRadius = dp(6f).toFloat()
            }
            setOnClickListener { hide() }
        }
        headerContent.addView(closeBtn)
        header.addView(headerContent)
        mainLayout.addView(header)

        // Accent divider
        mainLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(Color.parseColor(ACCENT))
        })

        // ── 2. Scrollable Quick Actions Row ──────────────────────────────────────
        val actionScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(10f), dp(8f), dp(10f), dp(6f))
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addActionBtn(label: String, onClick: () -> Unit) {
            val btn = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(SURFACE_HIGH))
                    cornerRadius = dp(6f).toFloat()
                    setStroke(dp(0.8f), Color.parseColor(BORDER_ACTIVE))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener { onClick() }
            }
            actionRow.addView(btn)
        }

        addActionBtn("⚡ 120Hz Mode") {
            scope.launch {
                val p = NukeSystemParam(
                    "GLOBAL:peak_refresh_rate", ParamSource.GLOBAL, "peak_refresh_rate",
                    "120.0", ParamCategory.DISPLAY, RiskLevel.SAFE
                )
                val res = NukeSystemParamRepository.applyParameter(context, p, "120.0")
                withContext(Dispatchers.Main) {
                    if (res.success) NukeToast.success(context, res.message) else NukeToast.error(context, res.message)
                    refreshList()
                }
            }
        }

        addActionBtn("🎯 Touch Tuning") {
            scope.launch {
                val p = NukeSystemParam(
                    "SYSTEM:pointer_speed", ParamSource.SYSTEM, "pointer_speed",
                    "7", ParamCategory.TOUCH, RiskLevel.SAFE
                )
                val res = NukeSystemParamRepository.applyParameter(context, p, "7")
                withContext(Dispatchers.Main) {
                    if (res.success) NukeToast.success(context, res.message) else NukeToast.error(context, res.message)
                    refreshList()
                }
            }
        }

        addActionBtn("↩ Rollback All") {
            scope.launch {
                val summary = NukeSystemParamRepository.rollbackAll(context)
                withContext(Dispatchers.Main) {
                    NukeToast.success(context, "Rollback: ${summary.successCount} parameter dipulihkan", long = true)
                    refreshList()
                }
            }
        }

        addActionBtn("📤 Export Preset") {
            scope.launch {
                val json = NukeSystemParamRepository.exportParametersToJson(
                    context = context,
                    moduleName = "${Build.MANUFACTURER} Gaming Preset",
                    author = "Game Nuke Overlay",
                    description = "Exported from Game Nuke Floating Editor"
                )
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("GameNuke Preset", json))
                withContext(Dispatchers.Main) {
                    NukeToast.success(context, "Preset JSON disalin ke clipboard!", long = true)
                }
            }
        }

        addActionBtn("🔄 Refresh") {
            refreshList()
        }

        actionScrollView.addView(actionRow)
        mainLayout.addView(actionScrollView)

        // ── 3. Scrollable Category Filter Tabs ───────────────────────────────────
        val catScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(10f), dp(4f), dp(10f), dp(2f))
        }
        categoryTabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        catScrollView.addView(categoryTabsRow)
        mainLayout.addView(catScrollView)
        buildCategoryTabs()

        // Separator
        mainLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(0.8f)).apply {
                setMargins(dp(10f), dp(4f), dp(10f), 0)
            }
            setBackgroundColor(Color.parseColor(BORDER))
        })

        // ── 4. Search Box ────────────────────────────────────────────────────────
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        }

        val searchPrefixTv = TextView(context).apply {
            text = "🔍"
            textSize = 10f
            setPadding(0, 0, dp(6f), 0)
        }
        searchBox.addView(searchPrefixTv)

        searchEt = EditText(context).apply {
            hint = "Cari parameter atau flags sistem..."
            setHintTextColor(Color.parseColor(TEXT_DIM))
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(SURFACE_HIGH))
                cornerRadius = dp(6f).toFloat()
                setStroke(dp(0.8f), Color.parseColor(BORDER))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    currentFilter = query
                    renderParams()
                    if (query.length >= 2) {
                        dynamicSearchJob?.cancel()
                        dynamicSearchJob = scope.launch {
                            val existingIds = cachedParams.map { it.id }.toSet()
                            val found = NukeSystemParamRepository.queryDynamicHiddenParameters(context, query, existingIds)
                            dynamicParams = found
                            withContext(Dispatchers.Main) { renderParams() }
                        }
                    } else {
                        dynamicParams = emptyList()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        searchBox.addView(searchEt)

        val clearBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor(TEXT_DIM))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { searchEt?.setText("") }
        }
        searchBox.addView(clearBtn)
        mainLayout.addView(searchBox)

        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f))
            visibility = View.VISIBLE
        }
        mainLayout.addView(progressBar)

        // ── 5. Parameter Scroll List ─────────────────────────────────────────────
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        paramsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(12f))
        }
        scrollView.addView(paramsContainer)
        mainLayout.addView(scrollView)

        root.addView(mainLayout)
        rootView = root

        runCatching {
            wm.addView(rootView, rootParams)
            refreshList()
        }.onFailure { Log.e(TAG, "Failed to attach floating overlay: ${it.message}") }
    }

    private fun buildCategoryTabs() {
        val row = categoryTabsRow ?: return
        row.removeAllViews()

        data class Tab(val title: String, val isActive: Boolean, val onClick: () -> Unit)

        val tabs = listOf(
            Tab("ALL", activeCategoryFilter == null && !showOnlyCurated && !showOnlyModified && !showOnlyBrand) {
                activeCategoryFilter = null; showOnlyCurated = false; showOnlyModified = false; showOnlyBrand = false
                buildCategoryTabs(); renderParams()
            },
            Tab("CURATED", showOnlyCurated) {
                showOnlyCurated = !showOnlyCurated
                if (showOnlyCurated) { activeCategoryFilter = null; showOnlyModified = false; showOnlyBrand = false }
                buildCategoryTabs(); renderParams()
            },
            Tab("MODIFIED", showOnlyModified) {
                showOnlyModified = !showOnlyModified
                if (showOnlyModified) { activeCategoryFilter = null; showOnlyCurated = false; showOnlyBrand = false }
                buildCategoryTabs(); renderParams()
            },
            Tab("BRAND", showOnlyBrand) {
                showOnlyBrand = !showOnlyBrand
                if (showOnlyBrand) { activeCategoryFilter = null; showOnlyCurated = false; showOnlyModified = false }
                buildCategoryTabs(); renderParams()
            },
            Tab("DISPLAY", activeCategoryFilter == ParamCategory.DISPLAY) {
                activeCategoryFilter = if (activeCategoryFilter == ParamCategory.DISPLAY) null else ParamCategory.DISPLAY
                showOnlyCurated = false; showOnlyModified = false; showOnlyBrand = false
                buildCategoryTabs(); renderParams()
            },
            Tab("TOUCH", activeCategoryFilter == ParamCategory.TOUCH) {
                activeCategoryFilter = if (activeCategoryFilter == ParamCategory.TOUCH) null else ParamCategory.TOUCH
                showOnlyCurated = false; showOnlyModified = false; showOnlyBrand = false
                buildCategoryTabs(); renderParams()
            },
            Tab("GRAPHICS", activeCategoryFilter == ParamCategory.GRAPHICS) {
                activeCategoryFilter = if (activeCategoryFilter == ParamCategory.GRAPHICS) null else ParamCategory.GRAPHICS
                showOnlyCurated = false; showOnlyModified = false; showOnlyBrand = false
                buildCategoryTabs(); renderParams()
            },
            Tab("PERF", activeCategoryFilter == ParamCategory.PERFORMANCE) {
                activeCategoryFilter = if (activeCategoryFilter == ParamCategory.PERFORMANCE) null else ParamCategory.PERFORMANCE
                showOnlyCurated = false; showOnlyModified = false; showOnlyBrand = false
                buildCategoryTabs(); renderParams()
            }
        )

        for (tab in tabs) {
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12f) }
                setOnClickListener { tab.onClick() }
            }
            val label = TextView(context).apply {
                text = tab.title
                setTextColor(if (tab.isActive) Color.parseColor(ACCENT_BRIGHT) else Color.parseColor(TEXT_DIM))
                textSize = 8.5f
                typeface = if (tab.isActive) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                letterSpacing = if (tab.isActive) 0.06f else 0.04f
                setPadding(0, dp(4f), 0, dp(3f))
            }
            wrapper.addView(label)
            // Underline indicator
            wrapper.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2f))
                setBackgroundColor(if (tab.isActive) Color.parseColor(ACCENT_BRIGHT) else Color.TRANSPARENT)
            })
            row.addView(wrapper)
        }
    }

    private fun refreshList() {
        progressBar?.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = scope.launch {
            val list = NukeSystemParamRepository.fetchAllParameters(context)
            cachedParams = list
            withContext(Dispatchers.Main) {
                progressBar?.visibility = View.GONE
                renderParams()
            }
        }
    }

    private fun renderParams() {
        val container = paramsContainer ?: return
        container.removeAllViews()

        val query = currentFilter.lowercase()
        val allPool = (dynamicParams + cachedParams).distinctBy { it.id }

        val filtered = allPool.filter { param ->
            val matchesQuery = query.isEmpty() ||
                    param.key.lowercase().contains(query) ||
                    param.value.lowercase().contains(query) ||
                    (param.description?.lowercase()?.contains(query) == true)
            val matchesCat = activeCategoryFilter == null || param.category == activeCategoryFilter
            val matchesCurated = !showOnlyCurated || param.isCurated
            val matchesMod = !showOnlyModified || param.isModified
            val matchesBrand = !showOnlyBrand || (param.targetBrand != null && param.targetBrand != NukeSystemParamGuardian.OemBrand.GENERIC)
            matchesQuery && matchesCat && matchesCurated && matchesMod && matchesBrand
        }.take(100)

        countBadgeTv?.text = "${filtered.size}/${allPool.size}"

        if (filtered.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = if (query.isNotEmpty()) "Tidak ada parameter yang cocok dengan '$query'" else "Tidak ada parameter dalam kategori ini"
                setTextColor(Color.parseColor(TEXT_DIM))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(32f), 0, dp(32f))
            }
            container.addView(emptyTv)
            return
        }

        for (param in filtered) {
            val isExpanded = expandedEditParamId == param.id
            val isBlocked = param.riskLevel == RiskLevel.BLOCKED

            // ── Parameter Card Container ────────────────────────────────────────
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    setColor(
                        if (isExpanded) Color.parseColor(SURFACE_HIGH)
                        else Color.parseColor(SURFACE)
                    )
                    cornerRadius = dp(8f).toFloat()
                    setStroke(
                        dp(0.9f),
                        when {
                            isExpanded -> Color.parseColor(ACCENT_BRIGHT)
                            param.isModified -> Color.parseColor(AMBER)
                            param.isDynamicallyDiscovered -> Color.parseColor(BORDER_ACTIVE)
                            else -> Color.parseColor(BORDER)
                        }
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            // Top row: [SOURCE] Key ..................... Value [Edit/Lock]
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Source badge pill with distinct category color
            val (srcText, srcColor) = when (param.source) {
                ParamSource.SYSTEM -> "SYS" to "#FF0284C7"
                ParamSource.GLOBAL -> "GLB" to "#FF8B5CF6"
                ParamSource.SECURE -> "SEC" to "#FFF59E0B"
                ParamSource.PROP   -> "PROP" to "#FF10B981"
                ParamSource.KERNEL -> "KRNL" to "#FFEC4899"
            }
            val srcBadge = TextView(context).apply {
                text = srcText
                setTextColor(Color.parseColor(srcColor))
                textSize = 7.5f
                typeface = Typeface.MONOSPACE
                setPadding(dp(4f), dp(1.5f), dp(4f), dp(1.5f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(SURFACE_HIGH))
                    cornerRadius = dp(3f).toFloat()
                    setStroke(dp(0.6f), Color.parseColor(srcColor))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
            }
            topRow.addView(srcBadge)

            // Key name
            val keyTv = TextView(context).apply {
                text = param.key
                setTextColor(Color.parseColor(if (param.isModified) TEXT_PRIMARY else TEXT_SECONDARY))
                textSize = 9.5f
                typeface = if (param.isModified) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            topRow.addView(keyTv)

            // Value badge pill
            val valueTv = TextView(context).apply {
                text = param.value.ifBlank { "—" }.take(14)
                setTextColor(
                    when {
                        param.isModified -> Color.parseColor(ACCENT_BRIGHT)
                        isBlocked -> Color.parseColor(ROSE)
                        else -> Color.parseColor(CYAN)
                    }
                )
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(SURFACE_INSET))
                    cornerRadius = dp(4f).toFloat()
                    setStroke(dp(0.5f), Color.parseColor(BORDER))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6f)
                    marginEnd = dp(6f)
                }
            }
            topRow.addView(valueTv)

            // Action Pill Button (Edit / Close / Lock)
            if (!isBlocked) {
                val actionBtn = TextView(context).apply {
                    text = if (isExpanded) "Tutup" else "Edit"
                    setTextColor(
                        if (isExpanded) Color.parseColor(ACCENT_BRIGHT)
                        else Color.parseColor(TEXT_SECONDARY)
                    )
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(SURFACE_HIGH))
                        cornerRadius = dp(4f).toFloat()
                        setStroke(dp(0.6f), if (isExpanded) Color.parseColor(ACCENT_BRIGHT) else Color.parseColor(BORDER_ACTIVE))
                    }
                    setOnClickListener {
                        expandedEditParamId = if (isExpanded) null else param.id
                        renderParams()
                    }
                }
                topRow.addView(actionBtn)
            } else {
                val lockBadge = TextView(context).apply {
                    text = "🔒 Lock"
                    setTextColor(Color.parseColor(ROSE))
                    textSize = 8f
                    setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                }
                topRow.addView(lockBadge)
            }
            card.addView(topRow)

            // Description subtitle (neatly aligned)
            val desc = param.description
            if (!desc.isNullOrBlank() && !isExpanded) {
                val descTv = TextView(context).apply {
                    text = desc
                    setTextColor(Color.parseColor(TEXT_DIM))
                    textSize = 8f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3f), 0, 0)
                }
                card.addView(descTv)
            }

            // Quick preset pills (horizontal scroll/flow)
            if (param.presets.isNotEmpty() && !isBlocked && !isExpanded) {
                val presetsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4f), 0, 0)
                }
                val presetLabel = TextView(context).apply {
                    text = "Preset:"
                    setTextColor(Color.parseColor(TEXT_DIM))
                    textSize = 7.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 0, dp(4f), 0)
                }
                presetsRow.addView(presetLabel)

                for (preset in param.presets.take(5)) {
                    val pBtn = TextView(context).apply {
                        text = preset
                        val isActive = param.value == preset
                        setTextColor(if (isActive) Color.parseColor(ACCENT_BRIGHT) else Color.parseColor(TEXT_SECONDARY))
                        textSize = 8f
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor(SURFACE_INSET))
                            cornerRadius = dp(3f).toFloat()
                            setStroke(dp(0.6f), if (isActive) Color.parseColor(ACCENT_BRIGHT) else Color.parseColor(BORDER))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(4f) }
                        setOnClickListener {
                            scope.launch {
                                val res = NukeSystemParamRepository.applyParameter(context, param, preset)
                                withContext(Dispatchers.Main) {
                                    if (res.success) {
                                        NukeToast.success(context, res.message)
                                        refreshList()
                                    } else {
                                        NukeToast.error(context, res.message, long = true)
                                    }
                                }
                            }
                        }
                    }
                    presetsRow.addView(pBtn)
                }
                card.addView(presetsRow)
            }

            // ── Inline Editor Panel (expanded) ──────────────────────────────────
            if (isExpanded && !isBlocked) {
                val editPanel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(SURFACE_INSET))
                        cornerRadius = dp(6f).toFloat()
                        setStroke(dp(0.7f), Color.parseColor(BORDER_ACTIVE))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6f) }
                }

                // Description
                if (!desc.isNullOrBlank()) {
                    editPanel.addView(TextView(context).apply {
                        text = desc
                        setTextColor(Color.parseColor(TEXT_SECONDARY))
                        textSize = 8.5f
                        setPadding(0, 0, 0, dp(6f))
                    })
                }

                // Input + Apply row
                val inputRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val editField = EditText(context).apply {
                    setText(param.value)
                    setSelection(text.length)
                    setTextColor(Color.parseColor(TEXT_PRIMARY))
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(SURFACE_HIGH))
                        cornerRadius = dp(5f).toFloat()
                        setStroke(dp(0.8f), Color.parseColor(BORDER_ACTIVE))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6f)
                    }
                }
                inputRow.addView(editField)

                val applyBtn = TextView(context).apply {
                    text = "APPLY"
                    setTextColor(Color.parseColor("#FF020705"))
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(ACCENT_BRIGHT))
                        cornerRadius = dp(5f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        val inputVal = editField.text.toString().trim()
                        scope.launch {
                            val res = NukeSystemParamRepository.applyParameter(context, param, inputVal)
                            withContext(Dispatchers.Main) {
                                if (res.success) {
                                    NukeToast.success(context, res.message)
                                    expandedEditParamId = null
                                    refreshList()
                                } else {
                                    NukeToast.error(context, res.message, long = true)
                                }
                            }
                        }
                    }
                }
                inputRow.addView(applyBtn)

                val cancelBtn = TextView(context).apply {
                    text = "BATAL"
                    setTextColor(Color.parseColor(TEXT_DIM))
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
                    setOnClickListener {
                        expandedEditParamId = null
                        renderParams()
                    }
                }
                inputRow.addView(cancelBtn)
                editPanel.addView(inputRow)

                // Revert option if modified
                if (param.isModified) {
                    val revertRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6f), 0, 0)
                    }
                    revertRow.addView(TextView(context).apply {
                        text = "↩ Kembalikan ke nilai bawaan (Stock)"
                        setTextColor(Color.parseColor(AMBER))
                        textSize = 8.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(2f), 0, dp(2f))
                        setOnClickListener {
                            scope.launch {
                                val res = NukeSystemParamRepository.revertSingleParameter(context, param)
                                withContext(Dispatchers.Main) {
                                    if (res.success) NukeToast.success(context, res.message) else NukeToast.error(context, res.message)
                                    refreshList()
                                }
                            }
                        }
                    })
                    editPanel.addView(revertRow)
                }

                // Security footer
                editPanel.addView(TextView(context).apply {
                    text = "🛡 SafeGuard Aktif · Backup dibuat otomatis sebelum modifikasi"
                    setTextColor(Color.parseColor(TEXT_DIM))
                    textSize = 7.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(5f), 0, 0)
                })

                card.addView(editPanel)
            }

            container.addView(card)
        }
    }
}
