package com.neon.gametweak

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Tactical In-Game Floating PiP Wiki & Build Browser.
 *
 * Spawns a floating semi-transparent WebView overlay enabling gamers
 * to check item counters, hero matchups, and tactical guides
 * without minimizing the game or risking AFK disconnects.
 */
class NukeWikiOverlayView(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootContainer: FrameLayout? = null
    private var webView: WebView? = null
    private var isMinimized = false
    private var currentAlpha = 0.85f

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val isShowing: Boolean
        get() = rootContainer != null

    @SuppressLint("SetJavaScriptEnabled")
    fun show(initialUrl: String = "https://m.mobilelegends.com/") {
        if (rootContainer != null) return

        val dm = context.resources.displayMetrics
        val initialWidth = (dm.widthPixels * 0.42f).toInt().coerceAtLeast(480)
        val initialHeight = (dm.heightPixels * 0.55f).toInt().coerceAtLeast(600)

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
            x = (dm.widthPixels - initialWidth) - 40
            y = 120
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb((currentAlpha * 255).toInt(), 11, 17, 32))
            elevation = 16f
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Header Toolbar (Draggable) ─────────────────────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(230, 3, 7, 18))
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "⚡ TACTICAL PIP WIKI"
            setTextColor(Color.parseColor("#00ff88"))
            textSize = 11f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val alphaSlider = SeekBar(context).apply {
            max = 100
            progress = (currentAlpha * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(20)
                    currentAlpha = p / 100f
                    root.alpha = currentAlpha
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val minimizeBtn = TextView(context).apply {
            text = " ─ "
            setTextColor(Color.parseColor("#00e5ff"))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(12, 0, 12, 0)
            setOnClickListener { toggleMinimize(params) }
        }

        val closeBtn = TextView(context).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#ff0055"))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(12, 0, 12, 0)
            setOnClickListener { hide() }
        }

        header.addView(titleView)
        header.addView(alphaSlider)
        header.addView(minimizeBtn)
        header.addView(closeBtn)

        // Drag listener on header
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        // ── Quick Bookmarks Bar ────────────────────────────────────────────────
        val quickBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 15, 23, 42))
            setPadding(8, 6, 8, 6)
        }

        fun createBookmarkBtn(label: String, url: String) = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(14, 6, 14, 6)
            setBackgroundColor(Color.parseColor("#1e293b"))
            setOnClickListener { webView?.loadUrl(url) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 0, 4, 0) }
            layoutParams = lp
        }

        quickBar.addView(createBookmarkBtn("MLBB Wiki", "https://m.mobilelegends.com/"))
        quickBar.addView(createBookmarkBtn("Item Counters", "https://liquipedia.net/mobilelegends/"))
        quickBar.addView(createBookmarkBtn("Google Guide", "https://www.google.com/search?q=mlbb+counter+item+guide"))

        // ── Embedded WebView ──────────────────────────────────────────────────
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

        mainLayout.addView(header)
        mainLayout.addView(quickBar)
        mainLayout.addView(web)
        root.addView(mainLayout)

        windowManager.addView(root, params)
        rootContainer = root
    }

    private fun toggleMinimize(params: WindowManager.LayoutParams) {
        val root = rootContainer ?: return
        isMinimized = !isMinimized

        if (isMinimized) {
            params.width = 140
            params.height = 70
            webView?.visibility = View.GONE
        } else {
            val dm = context.resources.displayMetrics
            params.width = (dm.widthPixels * 0.42f).toInt().coerceAtLeast(480)
            params.height = (dm.heightPixels * 0.55f).toInt().coerceAtLeast(600)
            webView?.visibility = View.VISIBLE
        }
        windowManager.updateViewLayout(root, params)
    }

    fun hide() {
        rootContainer?.let {
            try {
                webView?.destroy()
                webView = null
                windowManager.removeView(it)
            } catch (e: Throwable) {
                // ignore
            }
            rootContainer = null
        }
    }
}
