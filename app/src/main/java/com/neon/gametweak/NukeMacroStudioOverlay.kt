package com.neon.gametweak

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * NukeMacroStudioOverlay v6.2 Enterprise — Highly Responsive Minimalist Gaming Studio.
 *
 * Key Architecture:
 * - Hide vs Minimize:
 *     - [X] completely hides the studio box and floating ball from the game screen,
 *       leaving ONLY the pass-through pin reticles active on screen.
 *     - [—] minimizes to a sleek 36dp pill parked at the screen edge.
 *     - Hidden studio can be re-opened instantly via Main Floating Booster menu.
 * - Atomic Pin Positioning: Prevents pin coordinates from snapping back to default when editing settings.
 * - 4 Sakti Core Modes: SPAM (Rapid Fire), AUTO DRAG (Flick), HOLD (Sustain), AUTO RETRY (Loop).
 * - Custom MicroSwitch: Fast, non-clipping, ultra-responsive 32x18dp toggle with glow indicators.
 * - Zero Latency Hardware Volume Trigger: Dispatches from in-memory RAM cache (<1ms).
 * - Enterprise Slate & Titanium Palette: 100% vector icons, zero emojis.
 */
class NukeMacroStudioOverlay private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── State ────────────────────────────────────────────────────────────────
    private val profiles = mutableStateListOf<MacroProfile>()
    private var activeProfileIndex by mutableIntStateOf(0)
    private val activePins = mutableStateListOf<MacroPinConfig>()

    var isEditMode by mutableStateOf(true)
        private set

    var isArmed by mutableStateOf(false)
        private set

    var isMinimized by mutableStateOf(false)
        private set

    var isPanelOpen by mutableStateOf(true)
        private set

    var isPanelHidden by mutableStateOf(false)
        private set

    private var selectedPinId by mutableStateOf<String?>(null)
    private var isDaemonActive by mutableStateOf(false)

    private var macroOpacity by mutableFloatStateOf(
        context.getSharedPreferences("NukeMacroStudio", Context.MODE_PRIVATE)
            .getFloat("KEY_MACRO_OPACITY", 0.90f)
    )

    private fun setOpacity(value: Float) {
        val clamped = value.coerceIn(0.20f, 1.0f)
        macroOpacity = clamped
        context.getSharedPreferences("NukeMacroStudio", Context.MODE_PRIVATE)
            .edit()
            .putFloat("KEY_MACRO_OPACITY", clamped)
            .apply()
    }

    // Floating box coordinates (px)
    private var panelX = 24
    private var panelY = 90

    private val activeProfile: MacroProfile
        get() = profiles.getOrElse(activeProfileIndex) {
            profiles.firstOrNull() ?: MacroProfile()
        }

    val isShowing: Boolean
        get() = panelView?.isAttachedToWindow == true || canvasView?.isAttachedToWindow == true

    // ── Windows & Lifecycles ────────────────────────────────────────────────
    private var canvasView: ComposeView? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var canvasLifecycleOwner: OverlayComposeLifecycleOwner? = null

    private var panelView: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelLifecycleOwner: OverlayComposeLifecycleOwner? = null

    companion object {
        private const val TAG = "NukeMacroV6"

        @Volatile private var instance: NukeMacroStudioOverlay? = null

        fun getInstance(context: Context): NukeMacroStudioOverlay =
            instance ?: synchronized(this) {
                instance ?: NukeMacroStudioOverlay(context.applicationContext).also { instance = it }
            }

        // Soft Enterprise Slate Palette
        val COLOR_OBSIDIAN  = Color(0xF40B0F19)
        val COLOR_SURFACE   = Color(0xF8111827)
        val COLOR_CARD      = Color(0xFF1E293B)
        val COLOR_BORDER    = Color(0xFF334155)
        val COLOR_ACCENT    = Color(0xFF38BDF8) // Soft Sky Blue
        val COLOR_SUCCESS   = Color(0xFF34D399) // Soft Jade Green
        val COLOR_DANGER    = Color(0xFFF43F5E) // Soft Rose Crimson
        val COLOR_AMBER     = Color(0xFFFBBF24) // Soft Amber
        val COLOR_INDIGO    = Color(0xFF818CF8) // Soft Indigo
        val COLOR_TEXT_PRI  = Color(0xFFF8FAFC) // Titanium White
        val COLOR_TEXT_SEC  = Color(0xFF94A3B8) // Muted Slate
        val COLOR_TEXT_DIM  = Color(0xFF64748B) // Dim Slate

        val PIN_PALETTE = listOf(
            0xFF38BDF8.toInt(), // Soft Sky
            0xFF34D399.toInt(), // Soft Emerald
            0xFF818CF8.toInt(), // Soft Indigo
            0xFFFBBF24.toInt(), // Soft Amber
            0xFF94A3B8.toInt()  // Soft Titanium
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Public Lifecycle & Navigation API
    // ═════════════════════════════════════════════════════════════════════════

    fun toggle() {
        if (isShowing) {
            if (isPanelHidden || !isPanelOpen) {
                openPanel()
            } else {
                hidePanel(keepPinsActive = true)
            }
        } else {
            show()
        }
    }

    fun show() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            NukeToast.error(context, "Overlay permission required for Macro Studio")
            return
        }

        loadProfiles()
        isPanelHidden = false
        isPanelOpen = true
        isMinimized = false
        isEditMode = true
        isArmed = false
        selectedPinId = null

        ensureDaemonTouch()
        NukeVolumeKeyTriggerManager.start(context)
        attachWindows()
        panelView?.visibility = View.VISIBLE
        updateCanvasTouchability(touchable = true)
        notifyPanelLayoutChanged()
        Log.i(TAG, "Macro Studio shown (Edit mode)")
    }

    fun openPanel() {
        isPanelHidden = false
        isPanelOpen = true
        isMinimized = false
        if (!isShowing) {
            show()
        } else {
            panelView?.visibility = View.VISIBLE
            updateCanvasTouchability(touchable = isEditMode)
            notifyPanelLayoutChanged()
            NukeToast.success(context, "Macro Studio Opened")
        }
    }

    /**
     * Completely hides the floating studio panel and floating ball from the screen.
     * All macro pins remain fully visible on screen and active in pass-through armed mode.
     * The studio panel can be re-opened at any time from the Main Floating Booster menu.
     */
    fun hidePanel(keepPinsActive: Boolean = true) {
        if (keepPinsActive) {
            isPanelHidden = true
            isPanelOpen = false
            isMinimized = false
            if (!isArmed) {
                isArmed = true
                isEditMode = false
                selectedPinId = null
                persistAndSync()
                ensureDaemonTouch()
            }
            updateCanvasTouchability(touchable = false)
            panelView?.visibility = View.GONE
            notifyPanelLayoutChanged()
            NukeToast.success(context, "Macro Studio Hidden • Pins active (Reopen via Booster)")
            Log.i(TAG, "Panel hidden, pins active on screen")
        } else {
            deactivateMacro()
        }
    }

    fun closePanel(keepPinsActive: Boolean = true) = hidePanel(keepPinsActive)

    fun minimizePanel() {
        isPanelHidden = false
        isMinimized = true
        panelView?.visibility = View.VISIBLE
        notifyPanelLayoutChanged()
    }

    fun expandPanel() {
        isPanelHidden = false
        isMinimized = false
        panelView?.visibility = View.VISIBLE
        notifyPanelLayoutChanged()
    }

    fun deactivateMacro() {
        NukeMacroEngine.releaseAll()
        NukeVolumeKeyTriggerManager.stop()
        if (isArmed) {
            NukeConnectionManager.syncMacroPins("")
            isArmed = false
        }
        detachWindows()
        NukeToast.success(context, "Macro deactivated • Pins removed")
        Log.i(TAG, "Macro fully deactivated")
    }

    fun hide() = hidePanel(keepPinsActive = true)

    fun toggleArmMode() {
        if (isArmed) {
            isArmed = false
            isEditMode = true
            NukeConnectionManager.syncMacroPins("")
            updateCanvasTouchability(touchable = true)
            NukeToast.success(context, "Macro standby • Edit mode active")
        } else {
            isArmed = true
            isEditMode = false
            selectedPinId = null
            persistAndSync()
            ensureDaemonTouch()
            NukeVolumeKeyTriggerManager.start(context)
            updateCanvasTouchability(touchable = false)
            NukeToast.success(context, "Macro armed • Hardware multi-touch active")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Window Management
    // ═════════════════════════════════════════════════════════════════════════

    private fun attachWindows() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        // ── 1. Fullscreen Pin Canvas View ─────────────────────────────────────
        if (canvasView == null) {
            val cOwner = OverlayComposeLifecycleOwner().also { it.start() }
            canvasLifecycleOwner = cOwner
            val cView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(cOwner)
                setViewTreeViewModelStoreOwner(cOwner)
                setViewTreeSavedStateRegistryOwner(cOwner)
                setContent { CanvasRoot() }
            }
            canvasView = cView

            val cLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            canvasParams = cLp
            runCatching { wm.addView(cView, cLp) }
        }

        // ── 2. Floating Studio Box View ───────────────────────────────────────
        if (panelView == null) {
            val pOwner = OverlayComposeLifecycleOwner().also { it.start() }
            panelLifecycleOwner = pOwner
            val pView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(pOwner)
                setViewTreeViewModelStoreOwner(pOwner)
                setViewTreeSavedStateRegistryOwner(pOwner)
                setContent { FloatingBoxRoot() }
            }
            panelView = pView

            val density = context.resources.displayMetrics.density
            panelX = (20 * density).toInt()
            panelY = (75 * density).toInt()

            val pLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = panelX
                y = panelY
            }
            panelParams = pLp
            runCatching { wm.addView(pView, pLp) }
        }
    }

    private fun detachWindows() {
        canvasView?.let { runCatching { wm.removeView(it) } }
        canvasView = null
        canvasParams = null
        canvasLifecycleOwner?.stop()
        canvasLifecycleOwner?.destroy()
        canvasLifecycleOwner = null

        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelParams = null
        panelLifecycleOwner?.stop()
        panelLifecycleOwner?.destroy()
        panelLifecycleOwner = null
    }

    private fun updateCanvasTouchability(touchable: Boolean) {
        val v = canvasView ?: return
        val lp = canvasParams ?: return
        lp.flags = if (touchable) {
            (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun notifyPanelLayoutChanged() {
        mainHandler.post {
            panelView?.requestLayout()
            panelView?.let { v ->
                panelParams?.let { lp ->
                    runCatching { wm.updateViewLayout(v, lp) }
                }
            }
        }
    }

    private fun updatePanelPosition(dx: Float, dy: Float) {
        val v = panelView ?: return
        val lp = panelParams ?: return
        val dm = context.resources.displayMetrics
        panelX = (panelX + dx).roundToInt().coerceIn(0, dm.widthPixels - 36)
        panelY = (panelY + dy).roundToInt().coerceIn(0, dm.heightPixels - 36)
        lp.x = panelX
        lp.y = panelY
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Data Management
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadProfiles() {
        val loaded = NukeMacroRepository.loadProfiles(context)
        profiles.clear()
        profiles.addAll(loaded)
        activeProfileIndex = 0
        activePins.clear()
        activePins.addAll(activeProfile.pins.map { it.copyPin() })
        NukeMacroEngine.syncActivePins(activePins)
    }

    private fun persistAndSync() {
        val p = activeProfile
        p.pins.clear()
        p.pins.addAll(activePins)
        NukeMacroRepository.updateProfile(context, p)
        NukeMacroEngine.syncActivePins(activePins)
        if (isArmed) {
            NukeMacroRepository.pushProfileToDaemon(context, p)
        }
    }

    private fun ensureDaemonTouch() {
        isDaemonActive = NukeTouchTuningEngine.isDaemonTouchActive
        if (!isDaemonActive) {
            NukeTouchTuningEngine.startDaemonTouchAsync(context) { success ->
                mainHandler.post {
                    isDaemonActive = success
                    if (success && isArmed) {
                        persistAndSync()
                    }
                }
            }
        }
    }

    private fun cycleProfile() {
        if (profiles.isEmpty()) return
        persistAndSync()
        activeProfileIndex = (activeProfileIndex + 1) % profiles.size
        activePins.clear()
        activePins.addAll(activeProfile.pins.map { it.copyPin() })
        NukeMacroEngine.syncActivePins(activePins)
        selectedPinId = null
        if (isArmed) {
            persistAndSync()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Composable: Canvas View (Soft Minimalist Pro Reticles)
    // ═════════════════════════════════════════════════════════════════════════

    @Composable
    private fun CanvasRoot() {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth
            val screenH = constraints.maxHeight
            val density = LocalDensity.current

            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    activePins.forEach { pin ->
                        val px = pin.xRatio * screenW
                        val py = pin.yRatio * screenH
                        val rPx = with(density) { pin.radiusDp.dp.toPx() }
                        val isSel = selectedPinId == pin.id
                        val pinColor = Color(pin.color)

                        // Drag Vector line for SWIPE mode in Edit mode
                        if (isEditMode && isSel && pin.mode == MacroTriggerMode.SWIPE) {
                            val tx = pin.targetXRatio * screenW
                            val ty = pin.targetYRatio * screenH
                            Canvas(Modifier.fillMaxSize()) {
                                drawLine(
                                    pinColor.copy(0.4f), Offset(px, py), Offset(tx, ty),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                                )
                                drawCircle(pinColor.copy(0.18f), 16f, Offset(tx, ty))
                                drawCircle(pinColor.copy(0.8f), 16f, Offset(tx, ty), style = Stroke(1.5f))
                            }

                            // Target Destination Handle
                            Box(
                                Modifier
                                    .offset {
                                        IntOffset(
                                            (tx - with(density) { 16.dp.toPx() }).roundToInt(),
                                            (ty - with(density) { 16.dp.toPx() }).roundToInt()
                                        )
                                    }
                                    .size(32.dp)
                                    .semantics {
                                        contentDescription = "Pin ${pin.index} Drag Destination"
                                        role = Role.Button
                                    }
                                    .pointerInput(pin.id + "_target") {
                                        detectDragGestures(
                                            onDrag = { ch, drag ->
                                                ch.consume()
                                                val idx = activePins.indexOfFirst { it.id == pin.id }
                                                if (idx >= 0 && isEditMode) {
                                                    val p = activePins[idx]
                                                    activePins[idx] = p.copy(
                                                        targetXRatio = ((p.targetXRatio * screenW + drag.x) / screenW).coerceIn(0.02f, 0.98f),
                                                        targetYRatio = ((p.targetYRatio * screenH + drag.y) / screenH).coerceIn(0.02f, 0.98f)
                                                    )
                                                }
                                            },
                                            onDragEnd = {
                                                persistAndSync()
                                            }
                                        )
                                    }
                            )
                        }

                        // Main Reticle
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (px - rPx).roundToInt(),
                                        (py - rPx).roundToInt()
                                    )
                                }
                                .size(with(density) { (rPx * 2).toDp() })
                                .semantics {
                                    contentDescription = "Pin ${pin.index}, Mode: ${pin.mode.shortName()}"
                                    role = Role.Button
                                }
                                .then(
                                    if (isEditMode) {
                                        Modifier.pointerInput(pin.id + "_drag") {
                                            detectDragGestures(
                                                onDrag = { ch, drag ->
                                                    ch.consume()
                                                    if (!pin.isLocked) {
                                                        val idx = activePins.indexOfFirst { it.id == pin.id }
                                                        if (idx >= 0) {
                                                            val p = activePins[idx]
                                                            activePins[idx] = p.copy(
                                                                xRatio = ((p.xRatio * screenW + drag.x) / screenW).coerceIn(0.02f, 0.98f),
                                                                yRatio = ((p.yRatio * screenH + drag.y) / screenH).coerceIn(0.02f, 0.98f)
                                                            )
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    persistAndSync()
                                                }
                                            )
                                        }
                                    } else Modifier
                                )
                                .then(
                                    if (isEditMode) {
                                        Modifier.pointerInput(pin.id + "_tap") {
                                            detectTapGestures(
                                                onTap = {
                                                    selectedPinId = if (isSel) null else pin.id
                                                },
                                                onLongPress = {
                                                    activePins.removeIf { it.id == pin.id }
                                                    activePins.forEachIndexed { i, p ->
                                                        activePins[i] = p.copy(index = i + 1)
                                                    }
                                                    if (selectedPinId == pin.id) selectedPinId = null
                                                    persistAndSync()
                                                }
                                            )
                                        }
                                    } else Modifier
                                )
                        ) {
                            PinReticle(pin, isSel, isEditMode)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PinReticle(pin: MacroPinConfig, isSelected: Boolean, isEdit: Boolean) {
        val pc = Color(pin.color)
        val baseAlpha = if (pin.enabled) (if (isEdit) 1f else 0.85f) else 0.35f
        val alphaVal = (baseAlpha * macroOpacity).coerceIn(0.15f, 1f)
        val strokeW = if (isSelected) 2.4f else 1.4f

        Canvas(Modifier.fillMaxSize().alpha(alphaVal)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width / 2f

            // Soft dark backing for readability
            drawCircle(COLOR_OBSIDIAN.copy(0.45f), r)

            // Primary outline
            drawCircle(pc, r, style = Stroke(strokeW))

            // Precision crosshair tick marks (N, S, E, W)
            val arm = r * 0.22f
            drawLine(pc.copy(0.7f), Offset(cx - arm, cy), Offset(cx + arm, cy), 1.2f)
            drawLine(pc.copy(0.7f), Offset(cx, cy - arm), Offset(cx, cy + arm), 1.2f)

            if (isSelected) {
                drawCircle(COLOR_ACCENT.copy(0.5f), r + 3f, style = Stroke(1.2f))
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(COLOR_OBSIDIAN.copy(0.75f))
                        .padding(horizontal = 3.dp, vertical = 0.5.dp)
                ) {
                    Text(
                        if (pin.triggerSource != MacroTriggerSource.TOUCH_SCREEN)
                            "${pin.index}•${pin.triggerSource.shortTag()}"
                        else
                            "${pin.index}•${pin.mode.reticleTag()}",
                        color = Color(pin.color).copy(if (isEdit) 1f else 0.95f),
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (pin.isLocked && isEdit) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(COLOR_AMBER.copy(0.85f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Locked", tint = Color.Black, modifier = Modifier.size(8.dp))
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Composable: Floating Box View (Compact & Responsive)
    // ═════════════════════════════════════════════════════════════════════════

    @Composable
    private fun FloatingBoxRoot() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            if (isPanelHidden) {
                // Completely hidden from game screen — zero visual or touch obstruction
                Box(Modifier.size(0.dp))
            } else if (isMinimized) {
                MinimizedPill()
            } else if (isPanelOpen) {
                ExpandedStudioPanel()
            }
        }
    }

    /** Minimized pill parked at screen edge. */
    @Composable
    private fun MinimizedPill() {
        var totalDragX by remember { mutableFloatStateOf(0f) }
        var totalDragY by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .wrapContentSize()
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(COLOR_SURFACE)
                .border(1.dp, if (isArmed) COLOR_SUCCESS else COLOR_ACCENT, RoundedCornerShape(16.dp))
                .semantics {
                    contentDescription = "Macro Studio Pill. Tap to expand."
                    role = Role.Button
                }
                .clickable { expandPanel() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            totalDragX = 0f
                            totalDragY = 0f
                        },
                        onDragEnd = {
                            if (hypot(totalDragX, totalDragY) < 12f) {
                                expandPanel()
                            }
                        },
                        onDragCancel = {},
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            updatePanelPosition(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (isArmed) COLOR_SUCCESS else COLOR_AMBER, CircleShape)
                )
                Text(
                    if (isArmed) "ARMED" else "STANDBY",
                    color = if (isArmed) COLOR_SUCCESS else COLOR_TEXT_PRI,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "${activePins.count { it.enabled }}P",
                    color = COLOR_ACCENT,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    /**
     * Expanded studio panel — Responsive & Compact.
     * Constrained to 82% of screen height (or max 360dp) with interior scrolling,
     * ensuring it comfortably fits on all device screens in both portrait and landscape.
     */
    @Composable
    private fun ExpandedStudioPanel() {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val maxAvailableHeight = ((dm.heightPixels / density) * 0.82f).dp.coerceIn(230.dp, 360.dp)

        Column(
            Modifier
                .width(268.dp)
                .heightIn(max = maxAvailableHeight)
                .alpha(macroOpacity)
                .shadow(16.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(COLOR_OBSIDIAN)
                .border(1.dp, COLOR_BORDER, RoundedCornerShape(12.dp))
        ) {
            // Fixed Header
            HeaderBar()

            // Fixed Status
            StatusBar()

            // Scrollable Content Area (Pin Strip + Responsive Pin Editor / Empty State)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (activePins.isNotEmpty()) {
                    PinStrip()
                } else {
                    EmptyPinsCard()
                }

                AnimatedVisibility(
                    visible = selectedPinId != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val selId = selectedPinId
                    if (selId != null) {
                        PinEditor(selId)
                    }
                }
            }

            // Fixed Footer
            FooterBar()
        }
    }

    @Composable
    private fun HeaderBar() {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        updatePanelPosition(drag.x, drag.y)
                    }
                }
                .background(COLOR_CARD)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = COLOR_ACCENT,
                modifier = Modifier.size(14.dp)
            )

            Text(
                "MACRO STUDIO",
                color = COLOR_TEXT_PRI,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )

            Spacer(Modifier.weight(1f))

            // Add Pin [+]
            ActionIconButton(Icons.Rounded.Add, "Add Pin", COLOR_ACCENT) {
                val newPin = MacroPinConfig(
                    index = (activePins.maxOfOrNull { it.index } ?: 0) + 1,
                    xRatio = 0.5f,
                    yRatio = 0.5f,
                    radiusDp = 36f,
                    mode = MacroTriggerMode.REPEAT_TAP,
                    triggerSource = MacroTriggerSource.TOUCH_SCREEN,
                    repeatCount = 0,
                    intervalMs = 20L,
                    tapDurationMs = 12L,
                    color = PIN_PALETTE[activePins.size % PIN_PALETTE.size]
                )
                activePins.add(newPin)
                selectedPinId = newPin.id
                persistAndSync()
            }

            // Arm / Standby Toggle
            ActionIconButton(
                if (isArmed) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isArmed) "Pause Macro" else "Arm Macro",
                if (isArmed) COLOR_SUCCESS else COLOR_AMBER
            ) {
                toggleArmMode()
            }

            // Minimize (—)
            ActionIconButton(Icons.Rounded.Remove, "Minimize to Pill", COLOR_TEXT_SEC) {
                minimizePanel()
            }

            // Hide (✕) (Keeps Pins Active)
            ActionIconButton(Icons.Rounded.Close, "Hide Studio (Pins Stay Active)", COLOR_TEXT_SEC) {
                hidePanel(keepPinsActive = true)
            }
        }
    }

    @Composable
    private fun StatusBar() {
        Column(Modifier.fillMaxWidth().background(COLOR_SURFACE)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (isDaemonActive) COLOR_SUCCESS else COLOR_AMBER, CircleShape)
                )

                Text(
                    if (isDaemonActive) "HARDWARE DRIVER ACTIVE" else "INITIALIZING DRIVER...",
                    color = if (isDaemonActive) COLOR_SUCCESS else COLOR_AMBER,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.weight(1f))

                // Profile chip
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(COLOR_CARD)
                        .border(0.8.dp, COLOR_BORDER, RoundedCornerShape(3.dp))
                        .clickable { cycleProfile() }
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        activeProfile.name.uppercase(),
                        color = COLOR_ACCENT,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Opacity Stepper Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "OVERLAY OPACITY",
                    color = COLOR_TEXT_DIM,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(COLOR_CARD)
                            .border(0.6.dp, COLOR_BORDER, RoundedCornerShape(2.dp))
                            .clickable { setOpacity(macroOpacity - 0.10f) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("-", color = COLOR_TEXT_PRI, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "${(macroOpacity * 100).roundToInt()}%",
                        color = COLOR_ACCENT,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Box(
                        Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(COLOR_CARD)
                            .border(0.6.dp, COLOR_BORDER, RoundedCornerShape(2.dp))
                            .clickable { setOpacity(macroOpacity + 0.10f) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = COLOR_TEXT_PRI, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyPinsCard() {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = COLOR_TEXT_DIM,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "No macro pins created",
                color = COLOR_TEXT_SEC,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(COLOR_CARD)
                    .border(0.8.dp, COLOR_BORDER, RoundedCornerShape(4.dp))
                    .clickable {
                        val newPin = MacroPinConfig(
                            index = 1,
                            xRatio = 0.5f,
                            yRatio = 0.5f,
                            radiusDp = 36f,
                            mode = MacroTriggerMode.REPEAT_TAP,
                            triggerSource = MacroTriggerSource.TOUCH_SCREEN,
                            repeatCount = 0,
                            intervalMs = 20L,
                            tapDurationMs = 12L,
                            color = PIN_PALETTE[0]
                        )
                        activePins.add(newPin)
                        selectedPinId = newPin.id
                        persistAndSync()
                    }
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    "+ Add Trigger Pin",
                    color = COLOR_ACCENT,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun PinStrip() {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            activePins.forEach { pin ->
                val isSel = selectedPinId == pin.id
                val c = Color(pin.color)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isSel) COLOR_CARD else COLOR_SURFACE)
                        .border(1.dp, if (isSel) COLOR_ACCENT else COLOR_BORDER, RoundedCornerShape(5.dp))
                        .clickable {
                            selectedPinId = if (isSel) null else pin.id
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .semantics {
                            contentDescription = "Pin ${pin.index}"
                            role = Role.Button
                        }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(4.dp).background(c, CircleShape))
                        Text(
                            "#${pin.index} ${pin.mode.reticleTag()}",
                            color = if (isSel) COLOR_TEXT_PRI else COLOR_TEXT_SEC,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (pin.triggerSource != MacroTriggerSource.TOUCH_SCREEN) {
                            Text(
                                pin.triggerSource.shortTag(),
                                color = COLOR_ACCENT,
                                fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Composable: Compact 2-Tab Pin Configuration Editor (Atomic Position Safety)
    // ═════════════════════════════════════════════════════════════════════════

    @Composable
    private fun PinEditor(pinId: String) {
        val pin = activePins.find { it.id == pinId } ?: return
        var editorTab by remember(pinId) { mutableIntStateOf(0) } // 0: ACTION & SPEED, 1: TRIGGER & ZONE

        fun updatePin(block: (MacroPinConfig) -> MacroPinConfig) {
            val idx = activePins.indexOfFirst { it.id == pinId }
            if (idx >= 0) {
                val current = activePins[idx]
                activePins[idx] = block(current)
                persistAndSync()
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(COLOR_SURFACE)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── Header Row: Title, ON/OFF MicroSwitch, Lock, Delete, Done ───
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.size(6.dp).background(Color(pin.color), CircleShape))
                Text(
                    "PIN #${pin.index}",
                    color = Color(pin.color),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(COLOR_CARD)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        pin.mode.reticleTag(),
                        color = COLOR_ACCENT,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.weight(1f))

                // Custom Ultra-Responsive MicroSwitch
                MicroSwitch(
                    checked = pin.enabled,
                    onCheckedChange = { isChecked ->
                        updatePin { p -> p.copy(enabled = isChecked) }
                    }
                )

                // Lock Position
                ActionIconButton(
                    if (pin.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    "Lock Position",
                    if (pin.isLocked) COLOR_AMBER else COLOR_TEXT_SEC
                ) {
                    updatePin { p -> p.copy(isLocked = !p.isLocked) }
                }

                // Delete Pin
                ActionIconButton(Icons.Rounded.Delete, "Delete Pin", COLOR_DANGER) {
                    activePins.removeIf { it.id == pinId }
                    activePins.forEachIndexed { i, p -> activePins[i] = p.copy(index = i + 1) }
                    selectedPinId = null
                    persistAndSync()
                }

                // Done
                ActionIconButton(Icons.Rounded.Check, "Done", COLOR_ACCENT) {
                    selectedPinId = null
                }
            }

            // ── 2-Tab Segmented Switcher (Halves vertical height!) ───────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (editorTab == 0) COLOR_CARD else COLOR_SURFACE)
                        .border(0.8.dp, if (editorTab == 0) COLOR_ACCENT else COLOR_BORDER, RoundedCornerShape(3.dp))
                        .clickable { editorTab = 0 }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "ACTION & SPEED",
                        color = if (editorTab == 0) COLOR_ACCENT else COLOR_TEXT_SEC,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (editorTab == 1) COLOR_CARD else COLOR_SURFACE)
                        .border(0.8.dp, if (editorTab == 1) COLOR_ACCENT else COLOR_BORDER, RoundedCornerShape(3.dp))
                        .clickable { editorTab = 1 }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "TRIGGER & ZONE",
                        color = if (editorTab == 1) COLOR_ACCENT else COLOR_TEXT_SEC,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── Tab 0: Action Modes & Sliders (4 Sakti Modes) ───────────────
            if (editorTab == 0) {
                // 4 Sakti Modes: SPAM, AUTO DRAG, HOLD, AUTO RETRY
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val modes = listOf(
                        MacroTriggerMode.REPEAT_TAP to "SPAM",
                        MacroTriggerMode.SWIPE to "AUTO DRAG",
                        MacroTriggerMode.HOLD to "HOLD",
                        MacroTriggerMode.LOOP to "AUTO RETRY"
                    )
                    modes.forEach { (m, label) ->
                        val isM = pin.mode == m
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isM) COLOR_ACCENT.copy(0.20f) else COLOR_CARD)
                                .border(0.8.dp, if (isM) COLOR_ACCENT else COLOR_BORDER, RoundedCornerShape(3.dp))
                                .clickable {
                                    updatePin { it.copy(mode = m) }
                                }
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isM) COLOR_ACCENT else COLOR_TEXT_SEC,
                                fontSize = 6.8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Mode Sliders
                when (pin.mode) {
                    MacroTriggerMode.REPEAT_TAP -> {
                        CompactSlider(
                            "BURST COUNT",
                            if (pin.repeatCount <= 0) "HOLD = INF" else "${pin.repeatCount}x",
                            0f..30f,
                            pin.repeatCount.toFloat()
                        ) { v ->
                            updatePin { p -> p.copy(repeatCount = v.toInt()) }
                        }
                        CompactSlider("INTERVAL", "${pin.intervalMs}ms", 2f..120f, pin.intervalMs.toFloat()) { v ->
                            updatePin { p -> p.copy(intervalMs = v.toLong()) }
                        }
                        CompactSlider("CONTACT TIME", "${pin.tapDurationMs}ms", 2f..40f, pin.tapDurationMs.toFloat()) { v ->
                            updatePin { p -> p.copy(tapDurationMs = v.toLong()) }
                        }
                        CompactSlider("INITIAL DELAY", "${pin.startDelayMs}ms", 0f..300f, pin.startDelayMs.toFloat()) { v ->
                            updatePin { p -> p.copy(startDelayMs = v.toLong()) }
                        }
                    }

                    MacroTriggerMode.SWIPE -> {
                        CompactSlider("DRAG DURATION", "${pin.swipeDurationMs}ms", 15f..600f, pin.swipeDurationMs.toFloat()) { v ->
                            updatePin { p -> p.copy(swipeDurationMs = v.toLong()) }
                        }
                        CompactSlider("SCOPE DELAY", "${pin.startDelayMs}ms", 0f..400f, pin.startDelayMs.toFloat()) { v ->
                            updatePin { p -> p.copy(startDelayMs = v.toLong()) }
                        }
                        Text(
                            "Drag the dotted circle on screen to adjust drag vector",
                            color = COLOR_TEXT_DIM, fontSize = 6.5.sp, fontFamily = FontFamily.Monospace
                        )
                    }

                    MacroTriggerMode.LOOP -> {
                        CompactSlider("LOOP GAP", "${pin.intervalMs}ms", 50f..5000f, pin.intervalMs.toFloat()) { v ->
                            updatePin { p -> p.copy(intervalMs = v.toLong()) }
                        }
                        CompactSlider("TAP DURATION", "${pin.tapDurationMs}ms", 5f..100f, pin.tapDurationMs.toFloat()) { v ->
                            updatePin { p -> p.copy(tapDurationMs = v.toLong()) }
                        }
                        CompactSlider(
                            "MAX CYCLES",
                            if (pin.repeatCount <= 0) "CONTINUOUS" else "${pin.repeatCount}x",
                            0f..50f,
                            pin.repeatCount.toFloat()
                        ) { v ->
                            updatePin { p -> p.copy(repeatCount = v.toInt()) }
                        }
                    }

                    MacroTriggerMode.HOLD -> {
                        CompactSlider("HOLD TIME", "${pin.holdDurationMs}ms", 50f..3000f, pin.holdDurationMs.toFloat()) { v ->
                            updatePin { p -> p.copy(holdDurationMs = v.toLong()) }
                        }
                        CompactSlider("INITIAL DELAY", "${pin.startDelayMs}ms", 0f..400f, pin.startDelayMs.toFloat()) { v ->
                            updatePin { p -> p.copy(startDelayMs = v.toLong()) }
                        }
                    }

                    else -> {
                        CompactSlider("TAP DURATION", "${pin.tapDurationMs}ms", 5f..100f, pin.tapDurationMs.toFloat()) { v ->
                            updatePin { p -> p.copy(tapDurationMs = v.toLong()) }
                        }
                    }
                }
            }

            // ── Tab 1: Trigger Source & Hit Zone ─────────────────────────────
            if (editorTab == 1) {
                // Trigger Source Tabs
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    val sources = listOf(
                        MacroTriggerSource.TOUCH_SCREEN to "TOUCH",
                        MacroTriggerSource.VOLUME_UP to "VOL UP",
                        MacroTriggerSource.VOLUME_DOWN to "VOL DN"
                    )
                    sources.forEach { (src, label) ->
                        val isSrc = pin.triggerSource == src
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isSrc) COLOR_ACCENT.copy(0.18f) else COLOR_CARD)
                                .border(0.8.dp, if (isSrc) COLOR_ACCENT else COLOR_BORDER, RoundedCornerShape(3.dp))
                                .clickable {
                                    updatePin { p -> p.copy(triggerSource = src) }
                                }
                                .padding(vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSrc) COLOR_ACCENT else COLOR_TEXT_SEC,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Text(
                    when (pin.triggerSource) {
                        MacroTriggerSource.TOUCH_SCREEN -> "Fires when touching the pin area on screen"
                        MacroTriggerSource.VOLUME_UP -> "Zero-delay trigger via hardware Volume Up key"
                        MacroTriggerSource.VOLUME_DOWN -> "Zero-delay trigger via hardware Volume Down key"
                    },
                    color = COLOR_TEXT_DIM,
                    fontSize = 6.5.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Hit Zone Radius
                CompactSlider("HIT ZONE RADIUS", "${pin.radiusDp.toInt()}dp", 20f..56f, pin.radiusDp) { v ->
                    updatePin { p -> p.copy(radiusDp = v) }
                }

                // Palette & Test Fire Row
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    PIN_PALETTE.forEach { c ->
                        val isC = pin.color == c
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(Color(c), CircleShape)
                                .then(if (isC) Modifier.border(1.5.dp, COLOR_TEXT_PRI, CircleShape) else Modifier)
                                .clickable {
                                    updatePin { it.copy(color = c) }
                                }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Test Fire Button
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(COLOR_CARD)
                            .border(0.8.dp, COLOR_ACCENT.copy(0.6f), RoundedCornerShape(3.dp))
                            .clickable {
                                val dm = context.resources.displayMetrics
                                NukeMacroEngine.triggerPin(pin, dm.widthPixels, dm.heightPixels)
                            }
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = COLOR_ACCENT, modifier = Modifier.size(10.dp))
                            Text(
                                "TEST",
                                color = COLOR_ACCENT,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    /** Custom ultra-responsive 32x18dp micro switch with animated thumb and smooth glow. */
    @Composable
    private fun MicroSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val trackWidth = 32.dp
        val trackHeight = 18.dp
        val thumbSize = 14.dp
        val thumbPadding = 2.dp

        val density = LocalDensity.current
        val maxOffsetPx = with(density) { (trackWidth - thumbSize - thumbPadding * 2).toPx() }
        val thumbOffsetPx by animateFloatAsState(
            targetValue = if (checked) maxOffsetPx else 0f,
            animationSpec = tween(durationMillis = 150),
            label = "microSwitchThumb"
        )

        Box(
            modifier = modifier
                .size(trackWidth, trackHeight)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) COLOR_SUCCESS.copy(alpha = 0.35f) else COLOR_CARD)
                .border(
                    width = 1.dp,
                    color = if (checked) COLOR_SUCCESS else COLOR_BORDER,
                    shape = RoundedCornerShape(9.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onCheckedChange(!checked)
                }
                .padding(thumbPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                    .size(thumbSize)
                    .shadow(2.dp, CircleShape)
                    .background(if (checked) COLOR_SUCCESS else COLOR_TEXT_DIM, CircleShape)
            )
        }
    }

    @Composable
    private fun FooterBar() {
        Row(
            Modifier
                .fillMaxWidth()
                .background(COLOR_OBSIDIAN)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "X hides studio • Pins remain active",
                color = COLOR_TEXT_DIM,
                fontSize = 6.5.sp,
                fontFamily = FontFamily.Monospace
            )

            // Explicit Deactivate Button
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(COLOR_DANGER.copy(0.12f))
                    .border(0.8.dp, COLOR_DANGER.copy(0.4f), RoundedCornerShape(3.dp))
                    .clickable { deactivateMacro() }
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    .semantics {
                        contentDescription = "Deactivate Macro"
                        role = Role.Button
                    }
            ) {
                Text(
                    "TURN OFF",
                    color = COLOR_DANGER,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun ActionIconButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        desc: String,
        tint: Color,
        onClick: () -> Unit
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(COLOR_SURFACE)
                .border(0.8.dp, COLOR_BORDER, RoundedCornerShape(3.dp))
                .clickable { onClick() }
                .semantics {
                    contentDescription = desc
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(12.dp))
        }
    }

    @Composable
    private fun CompactSlider(
        label: String,
        valueStr: String,
        range: ClosedFloatingPointRange<Float>,
        value: Float,
        onChange: (Float) -> Unit
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    color = COLOR_TEXT_DIM,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    valueStr,
                    color = COLOR_ACCENT,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = COLOR_ACCENT,
                    activeTrackColor = COLOR_ACCENT,
                    inactiveTrackColor = COLOR_CARD
                )
            )
        }
    }
}