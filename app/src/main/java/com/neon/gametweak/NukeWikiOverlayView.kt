package com.neon.gametweak

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Tactical In-Game Floating PiP Wiki & Build Browser.
 *
 * Spawns a floating semi-transparent WebView overlay styled with luxury obsidian cyberpunk
 * glassmorphism, enabling gamers to check item counters, hero matchups, and tactical guides
 * without minimizing the game or risking AFK disconnects.
 */
class NukeWikiOverlayView private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val d = context.resources.displayMetrics.density

    private var rootContainer: FrameLayout? = null
    private var webView: WebView? = null
    private var isMinimized = false
    private var currentAlpha = 0.88f

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val isShowing: Boolean
        get() = rootContainer != null

    companion object {
        private const val TAG = "NukeWikiOverlay"

        @Volatile
        private var instance: NukeWikiOverlayView? = null

        fun getInstance(context: Context): NukeWikiOverlayView {
            return instance ?: synchronized(this) {
                instance ?: NukeWikiOverlayView(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun toggle(initialUrl: String = "https://m.mobilelegends.com/") {
        if (isShowing) hide() else show(initialUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(initialUrl: String = "https://m.mobilelegends.com/") {
        mainHandler.post {
            if (rootContainer != null) return@post

            val dm = context.resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val isLandscape = sw > sh

            val initialWidth = if (isLandscape) {
                (sw * 0.46f).toInt().coerceIn((340 * d).toInt(), (sw * 0.60f).toInt())
            } else {
                (sw * 0.88f).toInt().coerceIn((300 * d).toInt(), (sw * 0.94f).toInt())
            }

            val initialHeight = if (isLandscape) {
                (sh * 0.88f).toInt().coerceIn((260 * d).toInt(), (sh * 0.92f).toInt())
            } else {
                (sh * 0.55f).toInt().coerceIn((360 * d).toInt(), (sh * 0.75f).toInt())
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (sw - initialWidth - (12 * d).toInt()).coerceAtLeast((8 * d).toInt())
                y = (sh * 0.06f).toInt()
            }

            // ── Root Obsidian Cyber Glass Container ──────────────────────────────
            val root = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.argb((currentAlpha * 255).toInt(), 8, 12, 16)) // Obsidian Glass
                    cornerRadius = 16 * d
                    setStroke((1.2f * d).toInt(), Color.parseColor("#3300FF88")) // Cyber Emerald Glow
                }
                elevation = 20f
                clipToOutline = true
            }

            val mainLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // ── Drag Handle Pill ─────────────────────────────────────────────────
            val gripContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
                val pill = View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#4D94A3B8"))
                        cornerRadius = 2 * d
                    }
                    layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (3.5f * d).toInt())
                }
                addView(pill)
            }

            // ── Header Toolbar (Draggable) ───────────────────────────────────────
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#E6080C10"))
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(context).apply {
                text = "⚡ TACTICAL PIP WIKI"
                setTextColor(Color.parseColor("#00FF88"))
                textSize = 11.5f
                paint.isFakeBoldText = true
            }

            val subtitleView = TextView(context).apply {
                text = "In-Game Meta & Strategy Guide"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 8f
            }

            titleCol.addView(titleView)
            titleCol.addView(subtitleView)

            // Opacity SeekBar
            val alphaSlider = SeekBar(context).apply {
                max = 100
                progress = (currentAlpha * 100).toInt()
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF88"))
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF88"))
                layoutParams = LinearLayout.LayoutParams((80 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val p = progress.coerceAtLeast(25)
                        currentAlpha = p / 100f
                        (root.background as? GradientDrawable)?.setColor(
                            Color.argb((currentAlpha * 255).toInt(), 8, 12, 16)
                        )
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            // Minimize Button
            val minimizeBtn = TextView(context).apply {
                text = " ─ "
                setTextColor(Color.parseColor("#00FF88"))
                textSize = 12f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2600FF88"))
                    cornerRadius = 8 * d
                }
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * d).toInt()
                }
                layoutParams = lp
                setOnClickListener { toggleMinimize(params) }
            }

            // Close Button
            val closeBtn = TextView(context).apply {
                text = " ✕ "
                setTextColor(Color.parseColor("#EF4444"))
                textSize = 12f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#26EF4444"))
                    cornerRadius = 8 * d
                    setStroke((1 * d).toInt(), Color.parseColor("#4DEF4444"))
                }
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * d).toInt()
                }
                layoutParams = lp
                setOnClickListener { hide() }
            }

            header.addView(titleCol)
            header.addView(alphaSlider)
            header.addView(minimizeBtn)
            header.addView(closeBtn)

            // Header touch listener for dragging
            val dragTouchListener = View.OnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dm = context.resources.displayMetrics
                        params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, (dm.heightPixels - (80 * d).toInt()).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(root, params) }
                        true
                    }
                    else -> false
                }
            }
            gripContainer.setOnTouchListener(dragTouchListener)
            header.setOnTouchListener(dragTouchListener)

            // ── Quick Bookmarks Chips Bar ─────────────────────────────────────────
            val quickBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#800B1017"))
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            }

            fun createBookmarkBtn(label: String, url: String) = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 9.5f
                gravity = Gravity.CENTER
                setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 6 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#33475B"))
                }
                setOnClickListener { webView?.loadUrl(url) }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
                layoutParams = lp
            }

            quickBar.addView(createBookmarkBtn("MLBB Wiki", "https://m.mobilelegends.com/"))
            quickBar.addView(createBookmarkBtn("Item Counters", "https://liquipedia.net/mobilelegends/"))
            quickBar.addView(createBookmarkBtn("Liquipedia", "https://liquipedia.net/"))
            quickBar.addView(createBookmarkBtn("Free Fire Wiki", "https://freefire.fandom.com/"))

            // ── Embedded High-Performance WebView ─────────────────────────────────
            val web = WebView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(initialUrl)
            }
            webView = web

            mainLayout.addView(gripContainer)
            mainLayout.addView(header)
            mainLayout.addView(quickBar)
            mainLayout.addView(web)
            root.addView(mainLayout)

            try {
                windowManager.addView(root, params)
                rootContainer = root
                Log.d(TAG, "Tactical PiP Wiki overlay opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add Wiki Overlay view: ${e.message}", e)
                rootContainer = null
            }
        }
    }

    private fun toggleMinimize(params: WindowManager.LayoutParams) {
        val root = rootContainer ?: return
        isMinimized = !isMinimized

        if (isMinimized) {
            params.width = (180 * d).toInt()
            params.height = (72 * d).toInt()
            webView?.visibility = View.GONE
        } else {
            val dm = context.resources.displayMetrics
            params.width = (dm.widthPixels * 0.44f).toInt().coerceIn((360 * d).toInt(), (580 * d).toInt())
            params.height = (dm.heightPixels * 0.58f).toInt().coerceIn((420 * d).toInt(), (720 * d).toInt())
            webView?.visibility = View.VISIBLE
        }
        windowManager.updateViewLayout(root, params)
    }

    fun hide() {
        mainHandler.post {
            rootContainer?.let {
                try {
                    webView?.destroy()
                    webView = null
                    windowManager.removeView(it)
                } catch (e: Throwable) {
                    Log.w(TAG, "Error removing Wiki overlay: ${e.message}")
                }
                rootContainer = null
            }
        }
    }
}
