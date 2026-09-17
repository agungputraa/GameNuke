package com.neon.gametweak

import android.annotation.SuppressLint
import android.content.Context
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * NukeCyberJukeboxOverlay — Interactive In-Game YouTube Music Studio.
 *
 * Floating gaming overlay allowing players to search YouTube, pick gaming presets,
 * control volume, and listen to music in the background during matches.
 */
class NukeCyberJukeboxOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private val engine = NukeCyberJukeboxEngine.getInstance(context)

    // UI references
    private var trackTitleTv: TextView? = null
    private var trackArtistTv: TextView? = null
    private var playPauseBtn: TextView? = null
    private var searchEt: EditText? = null
    private var resultsContainer: LinearLayout? = null
    private var loadingBar: ProgressBar? = null
    private var volumeBar: SeekBar? = null
    private var volumeLabelTv: TextView? = null

    private var observeJob: Job? = null

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeCyberJukeboxOverlay"

        @Volatile
        private var instance: NukeCyberJukeboxOverlay? = null

        fun getInstance(context: Context): NukeCyberJukeboxOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeCyberJukeboxOverlay(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val dm = context.resources.displayMetrics
            val isLandscape = dm.widthPixels > dm.heightPixels
            val panelW = if (isLandscape) (360 * d).toInt().coerceAtMost((dm.widthPixels * 0.52f).toInt())
                         else (340 * d).toInt().coerceAtMost((dm.widthPixels * 0.94f).toInt())
            val panelH = if (isLandscape) (dm.heightPixels * 0.92f).toInt()
                         else (540 * d).toInt().coerceAtMost((dm.heightPixels * 0.85f).toInt())

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
                x = if (isLandscape) (dm.widthPixels - panelW - (20 * d).toInt()) else (16 * d).toInt()
                y = (40 * d).toInt()
            }

            rootParams = lp
            val view = buildCyberJukeboxView()
            rootView = view
            runCatching {
                wm.addView(view, lp)
                bindEngineState()
            }.onFailure {
                Log.e(TAG, "Failed to attach Jukebox overlay", it)
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
            }
        }
    }

    private fun bindEngineState() {
        observeJob = scope.launch {
            launch {
                engine.currentTrack.collectLatest { track ->
                    track?.let {
                        trackTitleTv?.text = it.title
                        trackArtistTv?.text = "${it.artist} • ${if (it.duration.isNotBlank()) it.duration else "STREAM"}"
                    }
                }
            }
            launch {
                engine.isPlaying.collectLatest { playing ->
                    playPauseBtn?.text = if (playing) "❚❚ PAUSE" else "▶ PLAY"
                    playPauseBtn?.setBackgroundDrawable(createRoundedDrawable(
                        if (playing) Color.parseColor("#10B981") else Color.parseColor("#00E5C8"),
                        Color.parseColor("#047857"),
                        (6 * d).toInt(), (1 * d).toInt()
                    ))
                }
            }
            launch {
                engine.searchResults.collectLatest { list ->
                    populateResults(list)
                }
            }
            launch {
                engine.isSearching.collectLatest { searching ->
                    loadingBar?.visibility = if (searching) View.VISIBLE else View.GONE
                }
            }
            launch {
                engine.volumePercent.collectLatest { vol ->
                    volumeBar?.progress = vol
                    volumeLabelTv?.text = "VOL: $vol%"
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCyberJukeboxView(): View {
        val root = FrameLayout(context).apply {
            background = createCyberBackground()
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }

        val mainCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // ─── 1. Header (Draggable) ──────────────────────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * d).toInt())
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }

        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0

        header.setOnTouchListener { _, event ->
            val lp = rootParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = lp.x
                    initialY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - startX).toInt()
                    lp.y = initialY + (event.rawY - startY).toInt()
                    rootView?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    true
                }
                else -> false
            }
        }

        val iconBadge = TextView(context).apply {
            text = "🎧"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
                marginEnd = (6 * d).toInt()
            }
        }
        header.addView(iconBadge)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(context).apply {
            text = "CYBER JUKEBOX"
            setTextColor(Color.parseColor("#00E5C8"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        val subtitleTv = TextView(context).apply {
            text = "YOUTUBE IN-GAME AUDIO STREAMER"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 6.5f
            typeface = Typeface.MONOSPACE
        }
        titleCol.addView(titleTv)
        titleCol.addView(subtitleTv)
        header.addView(titleCol)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            background = createRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), (6 * d).toInt(), 1)
            setOnClickListener { hide() }
        }
        header.addView(closeBtn)
        mainCol.addView(header)

        // ─── 2. Current Track Hero Card ─────────────────────────────────────
        val heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * d).toInt()
                bottomMargin = (6 * d).toInt()
            }
            background = createRoundedDrawable(Color.parseColor("#0A141A"), Color.parseColor("#00E5C8"), (8 * d).toInt(), (1 * d).toInt())
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }

        val trackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val eqAnim = TextView(context).apply {
            text = "●"
            setTextColor(Color.parseColor("#00E5C8"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * d).toInt()
            }
        }
        trackRow.addView(eqAnim)

        val trackDetails = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tTitle = TextView(context).apply {
            text = "Montagem Diamante Rosa (Brazilian Phonk)"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSelected = true
        }
        trackTitleTv = tTitle

        val tArtist = TextView(context).apply {
            text = "Phonk Gaming Drift • 2:45"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 7.5f
            maxLines = 1
        }
        trackArtistTv = tArtist

        trackDetails.addView(tTitle)
        trackDetails.addView(tArtist)
        trackRow.addView(trackDetails)
        heroCard.addView(trackRow)

        // Media Controls
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (32 * d).toInt()).apply {
                topMargin = (6 * d).toInt()
            }
        }

        val prevBtn = TextView(context).apply {
            text = "⏮ PREV"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((62 * d).toInt(), (26 * d).toInt()).apply {
                marginEnd = (8 * d).toInt()
            }
            background = createRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), (5 * d).toInt(), 1)
            setOnClickListener { engine.playPrevPreset() }
        }
        controlsRow.addView(prevBtn)

        val playBtn = TextView(context).apply {
            text = "▶ PLAY"
            setTextColor(Color.parseColor("#001B14"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((78 * d).toInt(), (28 * d).toInt())
            background = createRoundedDrawable(Color.parseColor("#00E5C8"), Color.parseColor("#047857"), (6 * d).toInt(), (1 * d).toInt())
            setOnClickListener { engine.togglePlayPause() }
        }
        playPauseBtn = playBtn
        controlsRow.addView(playBtn)

        val nextBtn = TextView(context).apply {
            text = "NEXT ⏭"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((62 * d).toInt(), (26 * d).toInt()).apply {
                marginStart = (8 * d).toInt()
            }
            background = createRoundedDrawable(Color.parseColor("#1E293B"), Color.parseColor("#334155"), (5 * d).toInt(), 1)
            setOnClickListener { engine.playNextPreset() }
        }
        controlsRow.addView(nextBtn)
        heroCard.addView(controlsRow)

        // Volume Bar Row
        val volumeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
            }
        }
        val volLbl = TextView(context).apply {
            text = "VOL: 85%"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 7f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        volumeLabelTv = volLbl
        volumeRow.addView(volLbl)

        val sb = SeekBar(context).apply {
            max = 100
            progress = 85
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) engine.setVolume(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeBar = sb
        volumeRow.addView(sb)
        heroCard.addView(volumeRow)

        mainCol.addView(heroCard)

        // ─── 3. Presets Chips Scroll ────────────────────────────────────────
        val presetsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * d).toInt()).apply {
                bottomMargin = (6 * d).toInt()
            }
        }
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val presets = listOf(
            Triple("⚡ PHONK", "phonk drift gaming", NukeCyberJukeboxEngine.PRESET_TRACKS[0]),
            Triple("☕ LO-FI", "lofi beats chill", NukeCyberJukeboxEngine.PRESET_TRACKS[1]),
            Triple("⚔️ NCS", "alan walker ncs gaming", NukeCyberJukeboxEngine.PRESET_TRACKS[2]),
            Triple("🔥 HYPE", "neffex fight back", NukeCyberJukeboxEngine.PRESET_TRACKS[3]),
            Triple("🎮 ANIME", "naruto anime gaming ost", NukeCyberJukeboxEngine.PRESET_TRACKS[5]),
            Triple("💣 TRAP", "extreme bass trap gaming", NukeCyberJukeboxEngine.PRESET_TRACKS[6])
        )

        presets.forEach { (label, searchKey, defaultTrack) ->
            val chip = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#00E5C8"))
                textSize = 7.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
                background = createRoundedDrawable(Color.parseColor("#0C2024"), Color.parseColor("#00E5C8"), (4 * d).toInt(), 1)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (22 * d).toInt()).apply {
                    marginEnd = (5 * d).toInt()
                }
                setOnClickListener {
                    engine.playTrack(defaultTrack)
                    searchEt?.setText(searchKey)
                    engine.search(searchKey)
                }
            }
            chipsRow.addView(chip)
        }
        presetsScroll.addView(chipsRow)
        mainCol.addView(presetsScroll)

        // ─── 4. Search Bar Row ──────────────────────────────────────────────
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * d).toInt()).apply {
                bottomMargin = (6 * d).toInt()
            }
        }

        val et = EditText(context).apply {
            hint = "Search YouTube track or artist…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 8.5f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            background = createRoundedDrawable(Color.parseColor("#0B131B"), Color.parseColor("#1E293B"), (5 * d).toInt(), 1)
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, (32 * d).toInt(), 1f).apply {
                marginEnd = (6 * d).toInt()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = text.toString()
                    engine.search(query)
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        searchEt = et
        searchRow.addView(et)

        val searchBtn = TextView(context).apply {
            text = "SEARCH"
            setTextColor(Color.BLACK)
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((62 * d).toInt(), (32 * d).toInt())
            background = createRoundedDrawable(Color.parseColor("#00E5C8"), Color.parseColor("#00E5C8"), (5 * d).toInt(), 1)
            setOnClickListener {
                val query = et.text.toString()
                engine.search(query)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(et.windowToken, 0)
            }
        }
        searchRow.addView(searchBtn)
        mainCol.addView(searchRow)

        // Loading indicator
        val pb = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply {
                gravity = Gravity.CENTER
                bottomMargin = (4 * d).toInt()
            }
        }
        loadingBar = pb
        mainCol.addView(pb)

        // ─── 5. Results / Track List ────────────────────────────────────────
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultsContainer = listContainer
        scroll.addView(listContainer)
        mainCol.addView(scroll)

        populateResults(NukeCyberJukeboxEngine.PRESET_TRACKS)

        root.addView(mainCol)
        return root
    }

    private fun populateResults(tracks: List<NukeCyberJukeboxEngine.Track>) {
        val container = resultsContainer ?: return
        container.removeAllViews()

        if (tracks.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "No songs found. Try different keywords!"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 8f
                gravity = Gravity.CENTER
                setPadding(0, (20 * d).toInt(), 0, 0)
            }
            container.addView(emptyTv)
            return
        }

        tracks.forEachIndexed { index, track ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (38 * d).toInt()).apply {
                    bottomMargin = (4 * d).toInt()
                }
                background = createRoundedDrawable(Color.parseColor("#0A1117"), Color.parseColor("#15222E"), (6 * d).toInt(), 1)
                setPadding((6 * d).toInt(), (3 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    engine.playTrack(track)
                }
            }

            val numTv = TextView(context).apply {
                text = "${index + 1}"
                setTextColor(Color.parseColor("#00E5C8"))
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            card.addView(numTv)

            val infoCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (4 * d).toInt()
                    marginEnd = (6 * d).toInt()
                }
            }
            val titleTv = TextView(context).apply {
                text = track.title
                setTextColor(Color.WHITE)
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val metaTv = TextView(context).apply {
                text = "${track.artist} ${if (track.duration.isNotBlank()) "• ${track.duration}" else ""}"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 6.8f
                maxLines = 1
            }
            infoCol.addView(titleTv)
            infoCol.addView(metaTv)
            card.addView(infoCol)

            val playIcon = TextView(context).apply {
                text = "▶"
                setTextColor(Color.parseColor("#00E5C8"))
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt())
            }
            card.addView(playIcon)

            container.addView(card)
        }
    }

    private fun createCyberBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * d
            setColor(Color.parseColor("#060A0E"))
            setStroke((1.2f * d).toInt(), Color.parseColor("#00E5C8"))
        }
    }

    private fun createRoundedDrawable(bgColor: Int, strokeColor: Int, radiusPx: Int, strokeWidthPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(bgColor)
            setStroke(strokeWidthPx, strokeColor)
        }
    }
}
