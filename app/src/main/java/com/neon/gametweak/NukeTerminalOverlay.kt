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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NukeTerminalOverlay — Tactical Cyber Monospace Floating Terminal.
 *
 * Provides a dedicated floating terminal window for gamers and power-users to execute
 * local commands and diagnostic scripts in real time directly over gameplay or any screen.
 *
 * Features:
 *  - Privileged execution via Shizuku / Wireless ADB / Local Core (fallback to Android local sh)
 *  - Full command history with ▲/▼ navigation
 *  - Quick diagnostic chip presets (top, battery, thermal, gfxinfo, packages, uptime)
 *  - Monospace syntax-styled output buffer (Cyan prompt, Green standard, Red error)
 *  - Copy full console log to clipboard & Clear screen
 *  - Pre-fill execution bridge with NukeLiveChat (1-tap execute from developer support)
 */
class NukeTerminalOverlay private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NukeTerminalOverlay"

        @Volatile
        private var instance: NukeTerminalOverlay? = null

        fun getInstance(context: Context): NukeTerminalOverlay {
            return instance ?: synchronized(this) {
                instance ?: NukeTerminalOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val d = context.resources.displayMetrics.density

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null

    // UI references
    private var outputScrollView: ScrollView? = null
    private var outputContainer: LinearLayout? = null
    private var commandInputEt: EditText? = null
    private var runBtn: Button? = null
    private var execProgressBar: ProgressBar? = null
    private var backendBadgeTv: TextView? = null

    // History and state
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private val outputBuffer = StringBuilder()
    private var activeJob: Job? = null

    val isShowing: Boolean
        get() = rootView != null && rootView?.isAttachedToWindow == true

    fun show() {
        showWithCommand(null)
    }

    fun showWithCommand(initialCommand: String? = null) {
        mainHandler.post {
            if (isShowing) {
                bringToFront()
                initialCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    commandInputEt?.setText(cmd)
                    commandInputEt?.setSelection(cmd.length)
                }
                return@post
            }

            try {
                buildAndAttachView()
                initialCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    commandInputEt?.setText(cmd)
                    commandInputEt?.setSelection(cmd.length)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show terminal overlay", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            runCatching {
                val v = rootView ?: return@runCatching
                if (v.isAttachedToWindow) {
                    wm.removeView(v)
                }
            }
            rootView = null
            rootParams = null
            activeJob?.cancel()
        }
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    private fun bringToFront() {
        rootView?.let { v ->
            rootParams?.let { p ->
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    private fun buildAndAttachView() {
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.94f).coerceAtMost(480 * d).toInt()
        val height = (dm.heightPixels * 0.58f).coerceIn(320 * d, 560 * d).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        rootParams = WindowManager.LayoutParams(
            width,
            height,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE060A0F")) // Deep Obsidian Glass
                cornerRadius = 14 * d
                setStroke((1.2f * d).toInt(), Color.parseColor("#00FF88")) // Cyber Green Border
            }
            elevation = 24 * d
            clipToOutline = true
        }

        // 1. Draggable Header
        val header = buildHeaderView()
        container.addView(header)

        // 2. Quick Command Preset Chips
        val presetRow = buildPresetChipsView()
        container.addView(presetRow)

        // 3. Monospace Terminal Output Area
        val consoleView = buildConsoleView()
        container.addView(consoleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // 4. Command Input Row
        val inputRow = buildInputRowView()
        container.addView(inputRow)

        rootView = container
        wm.addView(container, rootParams)

        // Setup drag listener on header
        setupDragBehavior(header)

        // Initial welcome banner
        printWelcomeBanner()
    }

    private fun buildHeaderView(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#220A141E"))
                cornerRadii = floatArrayOf(14 * d, 14 * d, 14 * d, 14 * d, 0f, 0f, 0f, 0f)
            }

            // Terminal Icon & Title
            val titleIcon = TextView(context).apply {
                text = ">_"
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00FF88"))
                setPadding(0, 0, (6 * d).toInt(), 0)
            }
            addView(titleIcon)

            val titleTv = TextView(context).apply {
                text = "CYBER TERMINAL"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            addView(titleTv)

            // Backend Privilege Badge
            backendBadgeTv = TextView(context).apply {
                val label = NukeConnectionManager.connectionLabel()
                text = " $label "
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                setTextColor(if (NukeConnectionManager.isConnected()) Color.parseColor("#00FF88") else Color.parseColor("#F59E0B"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#15202B"))
                    cornerRadius = 4 * d
                    setStroke((0.8f * d).toInt(), if (NukeConnectionManager.isConnected()) Color.parseColor("#00FF88").and(0x66FFFFFF) else Color.parseColor("#F59E0B").and(0x66FFFFFF))
                }
                setPadding((5 * d).toInt(), (1 * d).toInt(), (5 * d).toInt(), (1 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (8 * d).toInt()
                }
            }
            addView(backendBadgeTv)

            // Spacer
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            addView(spacer)

            // Copy Console Output Button
            val copyBtn = TextView(context).apply {
                text = "📋"
                textSize = 12f
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#162330"))
                    cornerRadius = 6 * d
                }
                setOnClickListener {
                    copyConsoleOutput()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * d).toInt()
                }
            }
            addView(copyBtn)

            // Clear Screen Button
            val clearBtn = TextView(context).apply {
                text = "🧹"
                textSize = 12f
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#162330"))
                    cornerRadius = 6 * d
                }
                setOnClickListener {
                    clearConsole()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * d).toInt()
                }
            }
            addView(clearBtn)

            // Close Button
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#EF4444"))
                setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#261418"))
                    cornerRadius = 6 * d
                }
                setOnClickListener { hide() }
            }
            addView(closeBtn)
        }
    }

    private fun buildPresetChipsView(): HorizontalScrollView {
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A1017"))
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val presets = listOf(
                "⚡ top -m 5" to "top -m 5 -s cpu",
                "🔋 battery" to "dumpsys battery",
                "📱 model" to "getprop ro.product.model",
                "❄️ thermal" to "cat /sys/class/thermal/thermal_zone0/temp",
                "🎮 gfxinfo" to "dumpsys gfxinfo",
                "📦 user apps" to "pm list packages -3",
                "⏱️ uptime" to "uptime",
                "👤 whoami" to "whoami",
                "💾 memory" to "cat /proc/meminfo"
            )

            presets.forEach { (label, cmd) ->
                val chip = TextView(context).apply {
                    text = label
                    textSize = 8.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#38BDF8"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F1E2E"))
                        cornerRadius = 10 * d
                        setStroke((0.8f * d).toInt(), Color.parseColor("#0284C7"))
                    }
                    setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (6 * d).toInt()
                    }
                    setOnClickListener {
                        commandInputEt?.setText(cmd)
                        commandInputEt?.setSelection(cmd.length)
                        executeCommand(cmd)
                    }
                }
                row.addView(chip)
            }

            addView(row)
        }
    }

    private fun buildConsoleView(): ScrollView {
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor("#03070A"))
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        scrollView.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        outputScrollView = scrollView
        outputContainer = container

        return scrollView
    }

    private fun buildInputRowView(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#080D14"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 14 * d, 14 * d, 14 * d, 14 * d)
                setStroke((0.8f * d).toInt(), Color.parseColor("#15202B"))
            }

            // Prompt Prefix
            val promptTv = TextView(context).apply {
                text = "sh$ "
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00FF88"))
                setPadding(0, 0, (4 * d).toInt(), 0)
            }
            addView(promptTv)

            // Command Input
            commandInputEt = EditText(context).apply {
                hint = "Ketik command shell..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                maxLines = 2
                imeOptions = EditorInfo.IME_ACTION_DONE
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#04070A"))
                    cornerRadius = 8 * d
                    setStroke((0.8f * d).toInt(), Color.parseColor("#1E293B"))
                }
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = (6 * d).toInt()
                }

                setOnEditorActionListener { _, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE ||
                        (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                    ) {
                        val cmd = text.toString().trim()
                        if (cmd.isNotEmpty()) {
                            executeCommand(cmd)
                        }
                        true
                    } else false
                }
            }
            addView(commandInputEt)

            // History Navigation ▲ / ▼
            val navRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * d).toInt()
                }

                val upBtn = TextView(context).apply {
                    text = "▲"
                    textSize = 10f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0E1A26"))
                        cornerRadius = 6 * d
                    }
                    setOnClickListener {
                        navigateHistory(isUp = true)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (3 * d).toInt()
                    }
                }
                addView(upBtn)

                val downBtn = TextView(context).apply {
                    text = "▼"
                    textSize = 10f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0E1A26"))
                        cornerRadius = 6 * d
                    }
                    setOnClickListener {
                        navigateHistory(isUp = false)
                    }
                }
                addView(downBtn)
            }
            addView(navRow)

            // Paste Button
            val pasteBtn = TextView(context).apply {
                text = "📋"
                textSize = 12f
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#15202B"))
                    cornerRadius = 6 * d
                }
                setOnClickListener {
                    pasteFromClipboard()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * d).toInt()
                }
            }
            addView(pasteBtn)

            // Run Button Container
            val runContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (34 * d).toInt())
            }

            runBtn = Button(context).apply {
                text = "RUN"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#000000"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#00FF88"))
                    cornerRadius = 8 * d
                }
                setPadding(0, 0, 0, 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    val cmd = commandInputEt?.text?.toString()?.trim().orEmpty()
                    if (cmd.isNotEmpty()) {
                        executeCommand(cmd)
                    }
                }
            }
            runContainer.addView(runBtn)

            execProgressBar = ProgressBar(context).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt(), Gravity.CENTER)
            }
            runContainer.addView(execProgressBar)

            addView(runContainer)
        }
    }

    private fun printWelcomeBanner() {
        val banner = buildString {
            append("╔═════════════════════════════════════════════════════╗\n")
            append("║       ⚡ GAME NUKE CYBER TERMINAL v2.3.0            ║\n")
            append("║  Mode: ${NukeConnectionManager.connectionLabel().padEnd(16)} Status: READY                 ║\n")
            append("╚═════════════════════════════════════════════════════╝\n")
            append("Ketik perintah shell di bawah atau tap preset chip di atas.")
        }
        appendConsoleLine(banner, Color.parseColor("#38BDF8"), isPrompt = false)
    }

    private fun executeCommand(cmd: String) {
        if (cmd.isBlank()) return

        // Push to command history
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
        }
        historyIndex = commandHistory.size

        // Clear input field
        commandInputEt?.setText("")

        // Print command echo in console
        appendConsoleLine("nuke@android:~$ $cmd", Color.parseColor("#00D4FF"), isPrompt = true)

        // Show running spinner
        runBtn?.visibility = View.INVISIBLE
        execProgressBar?.visibility = View.VISIBLE

        activeJob?.cancel()
        activeJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runShellCommandSafely(cmd)
            }

            runBtn?.visibility = View.VISIBLE
            execProgressBar?.visibility = View.GONE

            if (result.stdout.isNotBlank()) {
                appendConsoleLine(result.stdout.trimEnd(), Color.parseColor("#00FF88"))
            }
            if (result.stderr.isNotBlank()) {
                appendConsoleLine(result.stderr.trimEnd(), Color.parseColor("#EF4444"))
            }

            // Exit code summary
            val exitColor = if (result.exitCode == 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
            val exitSummary = "[Process exited with code ${result.exitCode}]"
            appendConsoleLine(exitSummary, exitColor)

            // Update active backend badge in real time
            backendBadgeTv?.let { tv ->
                val label = NukeConnectionManager.connectionLabel()
                tv.text = " $label "
                tv.setTextColor(if (NukeConnectionManager.isConnected()) Color.parseColor("#00FF88") else Color.parseColor("#F59E0B"))
            }
        }
    }

    private data class ShellExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private fun runShellCommandSafely(command: String): ShellExecResult {
        // 1. Try privileged execution through NukeConnectionManager (Shizuku, ADB, iAdb, Local Core)
        if (NukeConnectionManager.isConnected()) {
            runCatching {
                val cmdResult = NukeConnectionManager.executeCommand(command, timeoutMs = 25_000L, maxOutputChars = 262_144)
                if (cmdResult != null) {
                    return ShellExecResult(
                        exitCode = cmdResult.exitCode,
                        stdout = if (cmdResult.isSuccess) cmdResult.output else "",
                        stderr = if (!cmdResult.isSuccess) cmdResult.output else ""
                    )
                }
            }.onFailure { Log.w(TAG, "Privileged execution failed, trying local shell fallback", it) }
        }

        // 2. Fallback to local device shell process (sh -c)
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stdoutBuilder.length < 262_144) {
                                stdoutBuilder.append(line).append("\n")
                            }
                        }
                    }
                }
            }

            val stderrThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stderrBuilder.length < 65_536) {
                                stderrBuilder.append(line).append("\n")
                            }
                        }
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exited = process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                return ShellExecResult(-1, stdoutBuilder.toString(), "Execution timed out after 25s")
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            ShellExecResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString(),
                stderr = stderrBuilder.toString()
            )
        } catch (e: Exception) {
            ShellExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "Local shell exception: ${e.message}"
            )
        }
    }

    private fun appendConsoleLine(text: String, color: Int, isPrompt: Boolean = false) {
        val container = outputContainer ?: return

        val lineTv = TextView(context).apply {
            this.text = text
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            setTextIsSelectable(true)
            setLineSpacing(1.5f * d, 1f)
            if (isPrompt) {
                setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
                typeface = Typeface.DEFAULT_BOLD
            } else {
                setPadding(0, 0, 0, (2 * d).toInt())
            }
        }

        container.addView(lineTv)
        outputBuffer.append(text).append("\n")

        // Auto-scroll to bottom
        mainHandler.postDelayed({
            outputScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
        }, 50L)
    }

    private fun navigateHistory(isUp: Boolean) {
        if (commandHistory.isEmpty()) return

        if (isUp) {
            if (historyIndex > 0) {
                historyIndex--
                val cmd = commandHistory[historyIndex]
                commandInputEt?.setText(cmd)
                commandInputEt?.setSelection(cmd.length)
            }
        } else {
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                val cmd = commandHistory[historyIndex]
                commandInputEt?.setText(cmd)
                commandInputEt?.setSelection(cmd.length)
            } else {
                historyIndex = commandHistory.size
                commandInputEt?.setText("")
            }
        }
    }

    private fun copyConsoleOutput() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Nuke Terminal Logs", outputBuffer.toString())
        cm.setPrimaryClip(clip)
        NukeToast.success(context, "Log terminal berhasil disalin!")
    }

    private fun clearConsole() {
        outputContainer?.removeAllViews()
        outputBuffer.setLength(0)
        printWelcomeBanner()
    }

    private fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            commandInputEt?.setText(text)
            commandInputEt?.setSelection(text.length)
        }
    }

    private fun setupDragBehavior(dragHandle: View) {
        var startX = 0f
        var startY = 0f
        var initParamsX = 0
        var initParamsY = 0
        var isDragging = false

        dragHandle.setOnTouchListener { _, event ->
            val p = rootParams ?: return@setOnTouchListener false
            val v = rootView ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initParamsX = p.x
                    initParamsY = p.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (Math.hypot(dx.toDouble(), dy.toDouble()) > 10 * d)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        p.x = initParamsX + dx.toInt()
                        p.y = initParamsY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging
                }
                else -> false
            }
        }
    }
}
