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
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

/**
 * NukeLiveChatOverlay — Enterprise Floating Live Chat Support
 *
 * Developer: Agung Developer
 * Enterprise Live Chat Interface
 *
 * Features:
 *  - 100% in-overlay crash-proof modal sheet (eliminates WindowManager$BadTokenException)
 *  - Clean minimalist dark glassmorphism bubbles
 *  - Automated syntax formatting for shell commands sent via remote support
 *  - Edit, Delete, Copy to clipboard with instant local update & sync
 *  - Device auth profile header
 */
class NukeLiveChatOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var collectJob: Job? = null

    // UI references
    private var messagesContainer: LinearLayout? = null
    private var messagesScrollView: ScrollView? = null
    private var emptyStateView: LinearLayout? = null
    private var inputEt: EditText? = null
    private var sendBtn: Button? = null
    private var sendProgressBar: ProgressBar? = null
    private var editBannerView: LinearLayout? = null
    private var editBannerTv: TextView? = null

    // In-Overlay Modal Sheet (Crash-proof dialog replacement)
    private var modalOverlay: FrameLayout? = null
    private var modalContentBox: LinearLayout? = null

    // State
    private var editingMessageId: String? = null

    val isShowing: Boolean get() = rootView != null

    companion object {
        private const val TAG = "NukeLiveChatOverlay"

        @Volatile
        private var instance: NukeLiveChatOverlay? = null

        fun getInstance(context: Context): NukeLiveChatOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeLiveChatOverlay(context.applicationContext).also { instance = it }
            }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            NukeLiveChatRepository.init(context)
            NukeLiveChatRepository.resetUnread()
            NukeLiveChatNotifier.cancelNotification(context)

            val dm = context.resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val isLandscape = sw > sh

            val panelW = if (isLandscape) (sw * 0.45f).toInt().coerceIn((360 * d).toInt(), (480 * d).toInt())
                         else (sw * 0.90f).toInt().coerceIn((320 * d).toInt(), (420 * d).toInt())
            val panelH = if (isLandscape) (sh * 0.88f).toInt().coerceIn((320 * d).toInt(), (460 * d).toInt())
                         else (sh * 0.65f).toInt().coerceIn((380 * d).toInt(), (560 * d).toInt())

            val lp = WindowManager.LayoutParams(
                panelW, panelH,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((sw - panelW) / 2).coerceAtLeast(0)
                y = if (isLandscape) (sh * 0.05f).toInt() else (sh * 0.10f).toInt()
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            rootParams = lp

            val panel = buildView()
            rootView = panel

            try {
                wm.addView(panel, lp)
                startObservingMessages()
                Log.d(TAG, "Live Chat overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show Live Chat overlay", e)
                rootView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            hideKeyboard()
            collectJob?.cancel()
            collectJob = null
            rootView?.let {
                runCatching { wm.removeView(it) }
                rootView = null
                rootParams = null
                editingMessageId = null
                modalOverlay = null
                modalContentBox = null
                Log.d(TAG, "Live Chat overlay hidden")
            }
        }
    }

    private fun startObservingMessages() {
        collectJob?.cancel()
        collectJob = scope.launch {
            NukeLiveChatRepository.messages.collect { msgList ->
                renderMessages(msgList)
            }
        }
    }

    private fun buildView(): View {
        val root = FrameLayout(context).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        val mainPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#090E13")) // Deep Obsidian Glass
                cornerRadius = 16 * d
                setStroke((1.4f * d).toInt(), Color.parseColor("#00FF88")) // Cyber Neon Emerald
            }
            elevation = 24 * d
            clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        // 1. HEADER (Draggable, Developer Profile, Clear, Close — Clean design, no terminal word)
        val header = buildHeader()
        mainPanel.addView(header)

        // 2. EDIT MODE BANNER (Hidden by default)
        editBannerView = buildEditBanner().also { mainPanel.addView(it) }

        // 3. CHAT BODY (Messages Scroll View)
        val chatBody = buildChatBody()
        mainPanel.addView(chatBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 4. QUICK SUGGESTIONS BAR
        val chipsBar = buildQuickChipsBar()
        mainPanel.addView(chipsBar)

        // 5. INPUT & SEND ROW
        val inputBar = buildInputBar()
        mainPanel.addView(inputBar)

        root.addView(mainPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // In-Overlay Modal Sheet (100% crash-free dialogs)
        val modal = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#B3000000"))
            setOnClickListener { hideModal() }
        }

        val cardBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D141C"))
                cornerRadius = 14 * d
                setStroke((1.2f * d).toInt(), Color.parseColor("#00FF88"))
            }
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            layoutParams = FrameLayout.LayoutParams(
                (280 * d).toInt().coerceAtMost((context.resources.displayMetrics.widthPixels * 0.85f).toInt()),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setOnClickListener { /* Consume click inside card */ }
        }

        modal.addView(cardBox)
        root.addView(modal, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        modalOverlay = modal
        modalContentBox = cardBox

        return root
    }

    private data class ModalAction(
        val title: String,
        val colorHex: String,
        val onClick: () -> Unit
    )

    private fun hideModal() {
        modalOverlay?.visibility = View.GONE
        modalContentBox?.removeAllViews()
    }

    private fun showModal(title: String, subtitle: String?, actions: List<ModalAction>) {
        val box = modalContentBox ?: return
        box.removeAllViews()

        // Header Title
        val titleTv = TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (4 * d).toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }
        box.addView(titleTv)

        if (!subtitle.isNullOrBlank()) {
            val subTv = TextView(context).apply {
                text = subtitle
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (12 * d).toInt())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            box.addView(subTv)
        } else {
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, (8 * d).toInt())
            }
            box.addView(spacer)
        }

        // Action buttons
        actions.forEach { action ->
            val btn = Button(context).apply {
                text = action.title
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(action.colorHex))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#15202B"))
                    cornerRadius = 8 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor(action.colorHex).and(0x66FFFFFF))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (36 * d).toInt()
                ).apply {
                    bottomMargin = (8 * d).toInt()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
                setOnClickListener {
                    hideModal()
                    action.onClick()
                }
            }
            box.addView(btn)
        }

        // Cancel Button
        val cancelBtn = TextView(context).apply {
            text = "✕ Batal"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            setOnClickListener { hideModal() }
        }
        box.addView(cancelBtn)

        modalOverlay?.visibility = View.VISIBLE
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#101820"))
                cornerRadii = floatArrayOf(16 * d, 16 * d, 16 * d, 16 * d, 0f, 0f, 0f, 0f)
            }
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false

            // Touch listener for dragging window across screen
            var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        rootParams?.let { lp ->
                            startX = lp.x; startY = lp.y
                            touchX = event.rawX; touchY = event.rawY
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        rootParams?.let { lp ->
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            lp.x = startX + dx
                            lp.y = startY + dy
                            rootView?.let { v -> runCatching { wm.updateViewLayout(v, lp) } }
                        }
                        true
                    }
                    else -> false
                }
            }

            // Top Row: Avatar, Name & Info, Clear, Close
            val contentRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Developer Avatar with Online Status
            val avatarBox = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                    rightMargin = (8 * d).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#06251B"))
                    shape = GradientDrawable.OVAL
                    setStroke((1 * d).toInt(), Color.parseColor("#00FF88"))
                }
                val icon = TextView(context).apply {
                    text = "👨‍💻"
                    textSize = 15f
                    gravity = Gravity.CENTER
                }
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                val onlineDot = View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#00FF88"))
                        shape = GradientDrawable.OVAL
                    }
                    layoutParams = FrameLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt(), Gravity.BOTTOM or Gravity.END)
                }
                addView(onlineDot)
            }
            contentRow.addView(avatarBox)

            // Name & Info Column
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(context).apply {
                text = "Agung Developer"
                textSize = 12f
                setTextColor(Color.parseColor("#FFFFFF"))
                typeface = Typeface.DEFAULT_BOLD
            }
            val subTv = TextView(context).apply {
                text = "Lead System Architect • Enterprise Live Support"
                textSize = 8.5f
                setTextColor(Color.parseColor("#00FF88"))
            }
            textCol.addView(titleTv)
            textCol.addView(subTv)
            contentRow.addView(textCol)

            // Clear history button
            val clearBtn = TextView(context).apply {
                text = "🗑"
                textSize = 13f
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                setOnClickListener { promptClearHistory() }
            }
            contentRow.addView(clearBtn)

            // Close button
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener { hide() }
            }
            contentRow.addView(closeBtn)

            addView(contentRow)

            // Device Auth Badge Chip
            val devAuthRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (5 * d).toInt()
                }
            }

            val authChip = TextView(context).apply {
                val uid = NukeLiveChatRepository.getUserUid(context)
                val model = Build.MODEL
                text = "🔒 AUTH: #UID_$uid • $model"
                textSize = 8f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00D4FF"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0A212E"))
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#00D4FF").and(0x66FFFFFF))
                }
                setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            }
            devAuthRow.addView(authChip)
            addView(devAuthRow)
        }
    }

    private fun buildEditBanner(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#152C3D"))
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())

            editBannerTv = TextView(context).apply {
                text = "Mengedit pesan..."
                textSize = 9.5f
                setTextColor(Color.parseColor("#00D4FF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(editBannerTv)

            val cancelBtn = TextView(context).apply {
                text = "✕ Batal"
                textSize = 10f
                setTextColor(Color.parseColor("#EF4444"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
                setOnClickListener { cancelEditing() }
            }
            addView(cancelBtn)
        }
    }

    private fun buildChatBody(): FrameLayout {
        return FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#070B0E"))

            messagesContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            }

            messagesScrollView = ScrollView(context).apply {
                isFillViewport = true
                addView(messagesContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            addView(messagesScrollView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            // Empty state placeholder
            emptyStateView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())

                val emptyIcon = TextView(context).apply {
                    text = "💬"
                    textSize = 34f
                    gravity = Gravity.CENTER
                }
                val emptyTitle = TextView(context).apply {
                    text = "Live Chat Developer"
                    textSize = 14f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (8 * d).toInt()
                    }
                }
                val emptyDesc = TextView(context).apply {
                    text = "Tanyakan rekomendasi setting game, konsultasi panas/cooling, atau konsultasi optimasi langsung dengan Agung Developer!"
                    textSize = 10f
                    setTextColor(Color.parseColor("#94A3B8"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (4 * d).toInt()
                    }
                }
                addView(emptyIcon)
                addView(emptyTitle)
                addView(emptyDesc)
            }
            addView(emptyStateView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildQuickChipsBar(): HorizontalScrollView {
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0C131A"))
            setPadding((6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (5 * d).toInt())

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val chips = listOf(
                "⚡ Rekomendasi Setting",
                "❄️ Konsultasi Panas / Cooling",
                "🎮 Atasi FPS Drop",
                "✨ Request Fitur Baru"
            )

            chips.forEach { chipText ->
                val btn = TextView(context).apply {
                    text = chipText
                    textSize = 9f
                    setTextColor(Color.parseColor("#38BDF8"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#112233"))
                        cornerRadius = 12 * d
                        setStroke((0.8f * d).toInt(), Color.parseColor("#0284C7"))
                    }
                    setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (26 * d).toInt()).apply {
                        rightMargin = (6 * d).toInt()
                    }
                    setOnClickListener {
                        inputEt?.let { et ->
                            et.setText(chipText.substringAfter(" "))
                            et.setSelection(et.text.length)
                        }
                    }
                }
                row.addView(btn)
            }
            addView(row)
        }
    }

    private fun buildInputBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0A1118"))
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false

            inputEt = EditText(context).apply {
                hint = "Tulis pesan ke Agung Developer..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 4
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F1A24"))
                    cornerRadius = 10 * d
                    setStroke((1f * d).toInt(), Color.parseColor("#0284C7"))
                }
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = (8 * d).toInt()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            addView(inputEt)

            val btnContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            }

            sendBtn = Button(context).apply {
                text = "➤"
                textSize = 15f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00FF88"))
                    cornerRadius = 10 * d
                }
                setPadding(0, 0, 0, 0)
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
                setOnClickListener { onSendClicked() }
            }
            btnContainer.addView(sendBtn)

            sendProgressBar = ProgressBar(context).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER)
            }
            btnContainer.addView(sendProgressBar)

            addView(btnContainer)
        }
    }

    private fun renderMessages(list: List<NukeChatMessage>) {
        val container = messagesContainer ?: return
        container.removeAllViews()

        if (list.isEmpty()) {
            emptyStateView?.visibility = View.VISIBLE
            return
        }
        emptyStateView?.visibility = View.GONE

        list.forEach { msg ->
            val bubbleRow = buildMessageBubble(msg)
            container.addView(bubbleRow)
        }

        // Scroll to bottom smoothly
        mainHandler.postDelayed({
            messagesScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
        }, 100L)
    }

    private fun buildMessageBubble(msg: NukeChatMessage): LinearLayout {
        val isUser = msg.sender == "USER"
        val isDev = msg.sender == "DEV"
        val cmd = msg.commandText

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * d).toInt()
                if (isUser) {
                    leftMargin = (36 * d).toInt()
                    rightMargin = 0
                } else {
                    leftMargin = 0
                    rightMargin = (36 * d).toInt()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            background = GradientDrawable().apply {
                if (isUser) {
                    setColor(Color.parseColor("#151D24")) // Minimalist enterprise dark slate
                    cornerRadii = floatArrayOf(12 * d, 12 * d, 12 * d, 12 * d, 2 * d, 2 * d, 12 * d, 12 * d)
                    setStroke((1f * d).toInt(), Color.parseColor("#253444"))
                } else {
                    setColor(Color.parseColor("#0F1620")) // Deep obsidian graphite
                    cornerRadii = floatArrayOf(12 * d, 12 * d, 12 * d, 12 * d, 12 * d, 12 * d, 2 * d, 2 * d)
                    setStroke((1f * d).toInt(), Color.parseColor("#1C2936"))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false

            setOnClickListener { showMessageActions(msg) }
            setOnLongClickListener {
                showMessageActions(msg)
                true
            }
        }

        // Developer sender badge
        if (isDev) {
            val devHeader = TextView(context).apply {
                text = "🛡️ AGUNG DEVELOPER"
                textSize = 8.5f
                setTextColor(Color.parseColor("#60A5FA"))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * d).toInt()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            card.addView(devHeader)
        }

        // Message Body: If Developer sent /cmd, show syntax-formatted command box
        if (isDev && !cmd.isNullOrBlank()) {
            val cmdHeader = TextView(context).apply {
                text = "⚡ SHELL COMMAND"
                textSize = 8.5f
                setTextColor(Color.parseColor("#00FF88"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (3 * d).toInt())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            card.addView(cmdHeader)

            val cmdBox = TextView(context).apply {
                text = cmd
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#34D399"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#071512"))
                    cornerRadius = 6 * d
                    setStroke((1f * d).toInt(), Color.parseColor("#059669"))
                }
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            card.addView(cmdBox)

            // Direct 1-tap run command button below code box
            val runBtn = TextView(context).apply {
                text = "▶ Jalankan di Terminal"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00FF88"))
                    cornerRadius = 6 * d
                }
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * d).toInt()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
                setOnClickListener {
                    copyToClipboard(cmd)
                    NukeTerminalOverlay.getInstance(context).showWithCommand(cmd)
                }
            }
            card.addView(runBtn)
        } else {
            // Standard clean chat message — High Contrast Crisp Text
            val textTv = TextView(context).apply {
                text = msg.text
                textSize = 12.5f
                setTextColor(Color.parseColor("#F1F5F9"))
                setLineSpacing(2 * d, 1.15f)
                typeface = Typeface.DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            card.addView(textTv)
        }

        // Footer Row: Time, Edited status, Delivery tick, and Subtle Menu Button ⋮
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = (5 * d).toInt()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }

        if (msg.isEdited) {
            val editedTv = TextView(context).apply {
                text = "(diedit) "
                textSize = 8f
                setTextColor(Color.parseColor("#94A3B8"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            footerRow.addView(editedTv)
        }

        val timeTv = TextView(context).apply {
            text = msg.formattedTime
            textSize = 8f
            setTextColor(Color.parseColor("#94A3B8"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
        }
        footerRow.addView(timeTv)

        if (isUser) {
            val statusTick = TextView(context).apply {
                text = when (msg.status) {
                    "SENDING" -> " ⏳"
                    "FAILED" -> " ⚠️"
                    else -> " ✓✓"
                }
                textSize = 8f
                setTextColor(when (msg.status) {
                    "FAILED" -> Color.parseColor("#EF4444")
                    "SENDING" -> Color.parseColor("#FDE047")
                    else -> Color.parseColor("#00FF88")
                })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            footerRow.addView(statusTick)

            // Subtle menu button ⋮
            val menuBtn = TextView(context).apply {
                text = "  ⋮"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#64748B"))
                setPadding((4 * d).toInt(), 0, (2 * d).toInt(), 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
                setOnClickListener { showMessageActions(msg) }
            }
            footerRow.addView(menuBtn)
        }

        card.addView(footerRow)
        row.addView(card)
        return row
    }

    private fun onSendClicked() {
        val text = inputEt?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val editId = editingMessageId
        if (editId != null) {
            // Update existing message
            sendProgressBar?.visibility = View.VISIBLE
            sendBtn?.visibility = View.INVISIBLE
            NukeLiveChatRepository.editMessage(context, editId, text) { success, err ->
                sendProgressBar?.visibility = View.GONE
                sendBtn?.visibility = View.VISIBLE
                if (success) {
                    cancelEditing()
                    inputEt?.setText("")
                } else {
                    NukeToast.error(context, err ?: "Gagal memperbarui pesan", false)
                }
            }
        } else {
            // Send new message
            sendProgressBar?.visibility = View.VISIBLE
            sendBtn?.visibility = View.INVISIBLE
            inputEt?.setText("")
            NukeLiveChatRepository.sendMessage(context, text) { success, err ->
                sendProgressBar?.visibility = View.GONE
                sendBtn?.visibility = View.VISIBLE
                if (!success) {
                    NukeToast.error(context, err ?: "Gagal mengirim pesan", false)
                }
            }
        }
    }

    private fun showMessageActions(msg: NukeChatMessage) {
        val isUser = msg.sender == "USER"
        val actions = mutableListOf<ModalAction>()

        if (isUser) {
            actions.add(ModalAction("✏️ Edit Pesan", "#38BDF8") {
                startEditing(msg)
            })
            actions.add(ModalAction("📋 Salin Teks", "#00FF88") {
                copyToClipboard(msg.text)
            })
            actions.add(ModalAction("🗑️ Hapus Pesan", "#EF4444") {
                NukeLiveChatRepository.deleteMessage(context, msg.id) { ok, err ->
                    if (ok) {
                        NukeToast.success(context, "Pesan berhasil dihapus")
                    } else {
                        NukeToast.error(context, err ?: "Gagal menghapus pesan", false)
                    }
                }
            })
        } else {
            actions.add(ModalAction("📋 Salin Teks", "#00FF88") {
                copyToClipboard(msg.commandText ?: msg.text)
            })
            val cmd = msg.commandText
            if (!cmd.isNullOrBlank()) {
                actions.add(ModalAction("▶ Jalankan di Terminal", "#00FF88") {
                    copyToClipboard(cmd)
                    NukeTerminalOverlay.getInstance(context).showWithCommand(cmd)
                })
            }
        }

        val snippet = if (msg.text.length > 45) msg.text.take(42) + "..." else msg.text
        showModal(if (isUser) "Pesan Anda" else "Agung Developer", snippet, actions)
    }

    private fun startEditing(msg: NukeChatMessage) {
        editingMessageId = msg.id
        editBannerTv?.text = "Mengedit: \"${msg.text.take(30)}...\""
        editBannerView?.visibility = View.VISIBLE
        inputEt?.setText(msg.text)
        inputEt?.setSelection(msg.text.length)
        inputEt?.requestFocus()
        showKeyboard()
    }

    private fun cancelEditing() {
        editingMessageId = null
        editBannerView?.visibility = View.GONE
        inputEt?.setText("")
    }

    private fun confirmDeleteMessage(msg: NukeChatMessage) {
        showModal(
            "Hapus Pesan?",
            "Pesan ini akan dihapus secara permanen dari percakapan.",
            listOf(
                ModalAction("🗑 Hapus Sekarang", "#EF4444") {
                    NukeLiveChatRepository.deleteMessage(context, msg.id) { ok, err ->
                        if (ok) {
                            NukeToast.success(context, "Pesan berhasil dihapus")
                        } else {
                            NukeToast.error(context, err ?: "Gagal menghapus pesan", false)
                        }
                    }
                }
            )
        )
    }

    private fun promptClearHistory() {
        showModal(
            "Bersihkan Riwayat?",
            "Semua riwayat chat di aplikasi ini akan dihapus.",
            listOf(
                ModalAction("🗑 Bersihkan Semua", "#EF4444") {
                    NukeLiveChatRepository.clearHistory(context)
                    NukeToast.success(context, "Riwayat chat dibersihkan")
                }
            )
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("NukeChat", text))
        NukeToast.success(context, "Teks disalin ke clipboard")
    }

    private fun showKeyboard() {
        mainHandler.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputEt?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
        }, 120L)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputEt?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
