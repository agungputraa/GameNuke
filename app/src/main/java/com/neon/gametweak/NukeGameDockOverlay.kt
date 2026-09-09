package com.neon.gametweak

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * NukeGameDockOverlay — Gaming Floating Edge Dock & Freeform App Multi-Tasker.
 *
 * Developer: Agung Developer
 *
 * Features:
 *  - Discreet, draggable edge handle pinned to left or right screen border
 *  - Smooth swipe-to-open gesture
 *  - Launches apps in Freeform / Floating Window mode over running games (WhatsApp, Instagram, Telegram, etc.)
 *  - Custom App Picker: add or remove any installed game or app to the dock
 *  - Auto-enables Android freeform windowing support via privileged shell
 */
class NukeGameDockOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val pm: PackageManager = context.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val d = context.resources.displayMetrics.density
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    private fun getAppIcon(packageName: String): android.graphics.drawable.Drawable {
        return iconCache.getOrPut(packageName) {
            runCatching { pm.getApplicationIcon(packageName) }
                .getOrElse {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        context.getDrawable(android.R.drawable.sym_def_app_icon)!!
                    } else {
                        @Suppress("DEPRECATION")
                        context.resources.getDrawable(android.R.drawable.sym_def_app_icon)
                    }
                }
        }
    }

    // Handle (Pill) Window
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    // Drawer Window
    private var drawerView: View? = null
    private var drawerParams: WindowManager.LayoutParams? = null

    private var isDrawerOpen = false
    private var isOnRightEdge = true

    @Volatile
    private var isEnabledFlag: Boolean = prefs.getBoolean(KEY_ENABLED, false)

    val isShowing: Boolean get() = isEnabledFlag

    companion object {
        private const val TAG = "NukeGameDock"
        private const val PREFS_NAME = "NukeGameDockPrefs"
        private const val KEY_ENABLED = "dock_enabled"
        private const val KEY_HANDLE_Y = "dock_handle_y"
        private const val KEY_IS_RIGHT = "dock_is_right"
        private const val KEY_CUSTOM_APPS = "dock_custom_apps"

        // Default popular in-game chat & tool packages
        private val DEFAULT_PACKAGES = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.discord",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically"
        )

        @Volatile
        private var instance: NukeGameDockOverlay? = null

        fun getInstance(context: Context): NukeGameDockOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeGameDockOverlay(context.applicationContext).also { instance = it }
            }
    }

    fun toggle(): Boolean {
        val willShow = !isShowing
        if (willShow) show() else hide()
        return willShow
    }

    fun show() {
        isEnabledFlag = true
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        mainHandler.post {
            if (handleView != null) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                NukeToast.error(context, "Grant 'Display over other apps' permission for Cyber Deck")
                isEnabledFlag = false
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                return@post
            }

            createHandleWindow()
            enableFreeformSupportIfPrivileged()
        }
    }

    fun hide() {
        isEnabledFlag = false
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        mainHandler.post {
            closeDrawer()
            handleView?.let {
                runCatching { wm.removeView(it) }
                handleView = null
            }
        }
    }

    private fun enableFreeformSupportIfPrivileged() {
        NukeNativeFreeformLauncher.ensureFreeformEnabledAsync()
    }

    // ── Handle Window (Minimalist Draggable Edge Pill) ─────────────────────────
    private fun createHandleWindow() {
        val handleW = (11 * d).toInt()
        val handleH = (60 * d).toInt()
        val savedY = prefs.getInt(KEY_HANDLE_Y, (context.resources.displayMetrics.heightPixels * 0.45f).toInt())
        isOnRightEdge = prefs.getBoolean(KEY_IS_RIGHT, true)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            handleW,
            handleH,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (isOnRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
            x = 0
            y = savedY
        }

        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0D1F17")) // Translucent graphite green
                val r = 8 * d
                cornerRadii = if (isOnRightEdge) {
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                } else {
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
                setStroke((1 * d).toInt(), Color.parseColor("#00FF88"))
            }

            val indicator = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00FF88"))
                    cornerRadius = 2 * d
                }
                layoutParams = FrameLayout.LayoutParams((2.5f * d).toInt(), (24 * d).toInt(), Gravity.CENTER)
            }
            addView(indicator)
            alpha = 0.65f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        setupHandleTouch(pill, lp)
        handleView = pill
        handleParams = lp

        runCatching { wm.addView(pill, lp) }
    }

    private fun setupHandleTouch(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isLongPressActive = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPressActive = true
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            view.scaleX = 1.25f
            view.scaleY = 1.15f
            view.alpha = 1.0f
            NukeToast.success(context, "Cyber Deck Drag Mode: Move handle to desired edge")
        }

        view.setOnTouchListener { v, event ->
            val screenW = context.resources.displayMetrics.widthPixels
            val screenH = context.resources.displayMetrics.heightPixels

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isLongPressActive = false
                    v.alpha = 1.0f
                    // Schedule long press after 450ms of holding without swipe
                    longPressHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()

                    // If user moves noticeably before long press fires, cancel long press (user is swiping/tapping)
                    if (!isLongPressActive && (abs(dx) > 18 || abs(dy) > 18)) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }

                    // Only allow moving the pill window if long-press was achieved
                    if (isLongPressActive) {
                        lp.gravity = Gravity.START or Gravity.TOP
                        lp.x = (event.rawX - (11 * d) / 2).toInt().coerceIn(0, screenW - (11 * d).toInt())
                        lp.y = (startY + dy).coerceIn(40, screenH - (80 * d).toInt())
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    v.alpha = 0.65f

                    if (isLongPressActive) {
                        // User dragged the handle, snap to nearest screen edge (left or right)
                        val finalX = event.rawX
                        val snapToRight = finalX >= screenW / 2
                        snapHandleToEdge(view, lp, snapToRight)
                        val sideName = if (snapToRight) "Right" else "Left"
                        NukeToast.success(context, "Cyber Deck pinned to $sideName screen edge")
                    } else {
                        // Normal tap or swipe towards screen interior -> Open / toggle drawer
                        val deltaX = event.rawX - touchX
                        val isSwipeOpen = if (isOnRightEdge) (deltaX < -15) else (deltaX > 15)
                        if (isSwipeOpen || abs(event.rawX - touchX) < 18) {
                            toggleDrawer()
                        }
                    }
                    isLongPressActive = false
                    true
                }
                else -> false
            }
        }
    }

    fun snapHandleToEdge(view: View, lp: WindowManager.LayoutParams, snapToRight: Boolean) {
        val screenH = context.resources.displayMetrics.heightPixels
        isOnRightEdge = snapToRight
        lp.gravity = (if (isOnRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        lp.x = 0
        lp.y = lp.y.coerceIn(40, screenH - (80 * d).toInt())
        runCatching { wm.updateViewLayout(view, lp) }
        prefs.edit()
            .putInt(KEY_HANDLE_Y, lp.y)
            .putBoolean(KEY_IS_RIGHT, isOnRightEdge)
            .apply()
        updateHandleShape(view, isOnRightEdge)
    }

    private fun updateHandleShape(view: View, isRight: Boolean) {
        val r = 8 * d
        (view.background as? GradientDrawable)?.cornerRadii = if (isRight) {
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        } else {
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
    }

    // ── Drawer Panel (Sliding Quick Launch Glass Panel) ───────────────────────
    fun toggleDrawer() {
        if (isDrawerOpen) closeDrawer() else openDrawer()
    }

    fun openDrawer() {
        if (isDrawerOpen || drawerView != null) return
        isDrawerOpen = true

        val drawerW = (270 * d).toInt().coerceAtMost((context.resources.displayMetrics.widthPixels * 0.85f).toInt())
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            drawerW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (isOnRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
            x = (12 * d).toInt()
            y = (handleParams?.y ?: (context.resources.displayMetrics.heightPixels * 0.35f).toInt()) - (40 * d).toInt()
            y = y.coerceIn((50 * d).toInt(), (context.resources.displayMetrics.heightPixels * 0.65f).toInt())
        }

        val panel = buildDrawerContent()
        drawerView = panel
        drawerParams = lp

        runCatching { wm.addView(panel, lp) }
    }

    fun closeDrawer() {
        isDrawerOpen = false
        drawerView?.let {
            runCatching { wm.removeView(it) }
            drawerView = null
        }
    }

    private fun buildDrawerContent(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F008120E")) // Dark glass obsidian
                cornerRadius = 14 * d
                setStroke((1.2f * d).toInt(), Color.parseColor("#00FF88"))
            }
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            elevation = 16 * d
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * d).toInt()
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(context).apply {
            text = "⚡ CYBER DECK"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subTv = TextView(context).apply {
            text = "Floating Multitask Hub"
            textSize = 8f
            setTextColor(Color.parseColor("#00FF88"))
        }
        titleCol.addView(titleTv)
        titleCol.addView(subTv)
        val switchSideBtn = TextView(context).apply {
            text = if (isOnRightEdge) "⇄ Snap Left" else "⇄ Snap Right"
            textSize = 9f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C2028"))
                cornerRadius = 6 * d
                setStroke((0.8f * d).toInt(), Color.parseColor("#0284C7"))
            }
            setPadding((6 * d).toInt(), (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (6 * d).toInt()
            }
            setOnClickListener {
                closeDrawer()
                handleView?.let { hv ->
                    handleParams?.let { hp ->
                        snapHandleToEdge(hv, hp, !isOnRightEdge)
                        NukeToast.success(context, "Cyber Deck snapped to " + (if (isOnRightEdge) "Right" else "Left") + " edge")
                    }
                }
            }
        }
        header.addView(switchSideBtn)


        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            setOnClickListener { closeDrawer() }
        }
        header.addView(closeBtn)
        root.addView(header)

        // Divider
        val div = View(context).apply {
            setBackgroundColor(Color.parseColor("#1A2E24"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * d).toInt()
            ).apply {
                bottomMargin = (8 * d).toInt()
            }
        }
        root.addView(div)

        // Apps Grid Container (Built with 3-column rows to prevent icon squishing)
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (250 * d).toInt()
            )
            isFillViewport = true
        }

        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val apps = getDockApps()
        val allCells: MutableList<View> = apps.map { buildAppCell(it) }.toMutableList()
        allCells.add(buildAddAppCell())

        // Chunk into 3-column rows for perfectly proportional display
        val chunked = allCells.chunked(3)
        chunked.forEach { rowCells ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * d).toInt()
                }
            }
            rowCells.forEach { cell ->
                row.addView(cell)
            }
            // Add invisible spacers if row is not full to maintain exact 1/3 column width
            for (empty in 0 until (3 - rowCells.size)) {
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f).apply {
                        setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
                    }
                }
                row.addView(spacer)
            }
            rowsContainer.addView(row)
        }

        scroll.addView(rowsContainer)
        root.addView(scroll)

        return root
    }

    private data class DockAppItem(
        val packageName: String,
        val label: String,
        val isCustom: Boolean = false
    )

    private fun getDockApps(): List<DockAppItem> {
        val result = mutableListOf<DockAppItem>()
        val savedCustom = prefs.getStringSet(KEY_CUSTOM_APPS, emptySet()) ?: emptySet()

        // 1. Gather all candidates: defaults + custom
        val allPackages = (DEFAULT_PACKAGES + savedCustom).distinct()

        for (pkg in allPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                result.add(DockAppItem(pkg, label, isCustom = savedCustom.contains(pkg)))
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed on this device, skip
            }
        }
        return result
    }

    private fun buildAppCell(item: DockAppItem): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15241E"))
                cornerRadius = 10 * d
                setStroke((1 * d).toInt(), Color.parseColor("#1E3D30"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching {
                    launchAppFloating(item.packageName, item.label)
                    closeDrawer()
                }
            }
        }

        // Fixed 44dp x 44dp container with perfect aspect ratio
        val iconContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1410"))
                cornerRadius = 10 * d
            }
            clipToOutline = true
        }

        // Dedicated 38dp x 38dp icon image, scaleType FIT_CENTER, no squishing
        val iconView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt(), Gravity.CENTER)
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(getAppIcon(item.packageName))
        }
        iconContainer.addView(iconView)
        cell.addView(iconContainer)

        val nameTv = TextView(context).apply {
            text = item.label
            textSize = 8.5f
            setTextColor(Color.parseColor("#F1F5F9"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (5 * d).toInt()
            }
        }
        cell.addView(nameTv)

        return cell
    }

    private fun buildAddAppCell(): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1C16"))
                cornerRadius = 10 * d
                setStroke((1 * d).toInt(), Color.parseColor("#00FF88"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { showAppPickerDialog() }
            }
        }

        val iconBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#152C22"))
                cornerRadius = 10 * d
                setStroke((1 * d).toInt(), Color.parseColor("#00FF88"))
            }
            val plusTv = TextView(context).apply {
                text = "＋"
                textSize = 18f
                setTextColor(Color.parseColor("#00FF88"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(plusTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        cell.addView(iconBox)

        val nameTv = TextView(context).apply {
            text = "Add App"
            textSize = 8.5f
            setTextColor(Color.parseColor("#00FF88"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (5 * d).toInt()
            }
        }
        cell.addView(nameTv)

        return cell
    }

    // ── App Picker Modal (Add/Remove apps from floating dock) ──────────────────
    private fun showAppPickerDialog() {
        closeDrawer()
        val allLaunchableApps = mutableListOf<Pair<String, String>>()

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveList) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            val label = info.loadLabel(pm).toString()
            allLaunchableApps.add(pkg to label)
        }
        allLaunchableApps.sortBy { it.second.lowercase() }

        val savedCustom = prefs.getStringSet(KEY_CUSTOM_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        // Create overlay dialog
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dialogLp = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.85f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.75f).toInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        var dialogView: View? = null

        val dialogContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F407120D"))
                cornerRadius = 14 * d
                setStroke((1.2f * d).toInt(), Color.parseColor("#00FF88"))
            }
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())

            // Header
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val titleTv = TextView(context).apply {
                text = "Select Cyber Deck Apps"
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val doneBtn = TextView(context).apply {
                text = "Save"
                textSize = 12f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00FF88"))
                    cornerRadius = 6 * d
                }
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                setOnClickListener {
                    prefs.edit().putStringSet(KEY_CUSTOM_APPS, savedCustom).apply()
                    dialogView?.let { v -> runCatching { wm.removeView(v) } }
                    openDrawer()
                    NukeToast.success(context, "Cyber Deck apps updated successfully!")
                }
            }
            topRow.addView(titleTv)
            topRow.addView(doneBtn)
            addView(topRow)

            val noteTv = TextView(context).apply {
                text = "Toggle applications you wish to access from the floating edge dock:"
                textSize = 8.5f
                setTextColor(Color.parseColor("#94A3B8"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (4 * d).toInt()
                    bottomMargin = (8 * d).toInt()
                }
            }
            addView(noteTv)

            // Scrollable List
            val scroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val listCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            allLaunchableApps.forEach { (pkg, label) ->
                val isChecked = savedCustom.contains(pkg) || DEFAULT_PACKAGES.contains(pkg)
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(if (isChecked) Color.parseColor("#152A20") else Color.parseColor("#0C1712"))
                        cornerRadius = 8 * d
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (4 * d).toInt()
                    }
                    isClickable = true
                    isFocusable = true
                }

                val iconView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
                    try {
                        setImageDrawable(pm.getApplicationIcon(pkg))
                    } catch (e: Throwable) {
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
                itemRow.addView(iconView)

                val labelTv = TextView(context).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = (10 * d).toInt()
                    }
                }
                itemRow.addView(labelTv)

                val checkTv = TextView(context).apply {
                    text = if (isChecked) "✓" else "＋"
                    textSize = 13f
                    setTextColor(if (isChecked) Color.parseColor("#00FF88") else Color.parseColor("#64748B"))
                    typeface = Typeface.DEFAULT_BOLD
                }
                itemRow.addView(checkTv)

                itemRow.setOnClickListener {
                    if (savedCustom.contains(pkg)) {
                        savedCustom.remove(pkg)
                        checkTv.text = "＋"
                        checkTv.setTextColor(Color.parseColor("#64748B"))
                        itemRow.setBackgroundColor(Color.parseColor("#0C1712"))
                    } else {
                        savedCustom.add(pkg)
                        checkTv.text = "✓"
                        checkTv.setTextColor(Color.parseColor("#00FF88"))
                        itemRow.setBackgroundColor(Color.parseColor("#152A20"))
                    }
                }
                listCol.addView(itemRow)
            }
            scroll.addView(listCol)
            addView(scroll)
        }

        dialogView = dialogContent
        runCatching { wm.addView(dialogContent, dialogLp) }
    }

    // ── Launching in Freeform / Floating Mode ──────────────────────────────────
    fun launchAppFloating(packageName: String, label: String) {
        closeDrawer()
        FloatingBoosterService.collapseHub()
        NukeNativeFreeformLauncher.launchAppFloating(context, packageName, label)
    }
}
