package com.neon.gametweak

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.PhoneLocked
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

internal enum class FloatingHudTool {
    CROSSHAIR,
    DEEP_CLEAN,
    MAX_FPS,
    NETWORK,
    MONITOR,
    RESOURCE_RADAR,
    SCREEN_CONTROL,
    CPU_CLOCKS,
}

internal enum class FloatingHudToggle {
    GAME_MODE,
    DND,
    CALL_SHIELD,
    BATTERY_SAVER,
    DATA_SAVER,
    NETWORK_BOOST,
    CROSSHAIR,
    KEEP_AWAKE,
}

internal enum class FloatingCoreHealth { ONLINE, DEGRADED, OFFLINE }

internal enum class FloatingHudWing { LEFT, RIGHT, TOP, BOTTOM, PORTRAIT }

@Immutable
internal data class FloatingHudSnapshot(
    val gameLabel: String = "GAME",
    val phaseLabel: String = "CORE READY",
    val statusMessage: String = "CORE STANDBY",
    val cpuPercent: Int? = null,
    val ramPercent: Int? = null,
    val ramDetail: String = "-- / --",
    val fps: String = "--",
    val ping: String = "--",
    val probeMs: Long? = null,
    val probeEnabled: Boolean = false,
    val temperature: String = "--",
    val battery: String = "--",
    val storage: String = "--",
    val network: String = "--",
    val coreHealth: FloatingCoreHealth = FloatingCoreHealth.OFFLINE,
    val remoteDefinition: NukeRemoteHudDefinition = NukeRemoteHudDefinition.Bundled,
    val supportedTools: Set<FloatingHudTool> = FloatingHudTool.entries.toSet(),
    val toggleValues: Map<FloatingHudToggle, Boolean> = emptyMap(),
    val supportedToggles: Set<FloatingHudToggle> = FloatingHudToggle.entries.toSet(),
    val busyToggles: Set<FloatingHudToggle> = emptySet(),
    val quickToolStates: Map<String, Boolean> = emptyMap(),
    val unsupportedQuickTools: Set<String> = emptySet(),
    val brightnessPercent: Int = 100,
    val displayDpi: Int = 400,
    val toastMessage: String? = null,
    val toastTimestamp: Long = 0L,
)

@Immutable
internal data class FloatingHudCallbacks(
    val onTool: (FloatingHudTool) -> Unit,
    val onToggle: (FloatingHudToggle, Boolean) -> Unit,
    val onMinimize: () -> Unit,
    val onEndSession: () -> Unit,
    val onCoreClick: () -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onModuleRefresh: () -> Unit,
    val onModuleInstall: (String) -> Unit,
    val onModuleToggle: (String, Boolean) -> Unit,
    val onSearchFocusChanged: (Boolean) -> Unit,
    val onQuickAction: (String) -> Unit = {},
    val onBrightnessChanged: (Int) -> Unit = {},
    val onDpiChanged: (Int) -> Unit = {},
)

internal fun createFloatingHudComposeView(
    context: Context,
    owner: OverlayComposeLifecycleOwner,
    state: StateFlow<FloatingHudSnapshot>,
    moduleState: StateFlow<NukeModuleShopState>,
    wing: FloatingHudWing,
    callbacks: FloatingHudCallbacks,
): ComposeView = ComposeView(context).apply {
    this.setViewTreeLifecycleOwner(owner)
    this.setViewTreeSavedStateRegistryOwner(owner)
    this.setViewTreeViewModelStoreOwner(owner)
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    setContent {
        val currentDensity = androidx.compose.ui.platform.LocalDensity.current
        // Guarantee clean, crisp, untruncated UI geometry across all screen DPIs and system font sizes
        val stableDensity = androidx.compose.ui.unit.Density(
            density = currentDensity.density,
            fontScale = currentDensity.fontScale.coerceIn(0.85f, 1.15f)
        )
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides stableDensity
        ) {
            val snapshot by state.collectAsStateWithLifecycle()
            val modules by moduleState.collectAsStateWithLifecycle()
            if (wing == FloatingHudWing.PORTRAIT) {
                NukePortraitCockpit(snapshot = snapshot, moduleState = modules, callbacks = callbacks)
            } else {
                NukeFloatingWing(snapshot = snapshot, moduleState = modules, wing = wing, callbacks = callbacks)
            }
        }
    }
}

private val NukeGreen = Color(0xFF35C99B)
private val NukeGreenDim = Color(0xFF68C900)
private val NukeCyan = Color(0xFF00E5C8)
private val NukeAmber = Color(0xFFFFB830)
private val NukeRed = Color(0xFFFF4D6A)
private val NukePurple = Color(0xFFA855F7)
private val NukeVoid = Color(0xFF020705)
private val NukePanel = Color(0xFF0D1A13)
private val NukePanelHigh = Color(0xFF0F2018)
private val NukePanelBright = Color(0xFF1C2E2A)
private val NukeText = Color(0xFFFFFFFF)
private val NukeMuted = Color(0xFF9BB0A6)
private val NukeHairline = Color(0xFF1D3528)

private val NukeColorScheme = darkColorScheme(
    primary = NukeGreen,
    secondary = NukeCyan,
    tertiary = NukeAmber,
    background = NukeVoid,
    surface = NukePanel,
    surfaceVariant = NukePanelHigh,
    onPrimary = NukeVoid,
    onBackground = NukeText,
    onSurface = NukeText,
    error = NukeRed,
)

private val BayShape = GenericShape { size, _ ->
    val c = minOf(size.width, size.height) * .16f
    moveTo(c * .75f, 0f)
    lineTo(size.width - c * .28f, 0f)
    quadraticBezierTo(size.width, 0f, size.width, c * .72f)
    lineTo(size.width, size.height - c * .55f)
    quadraticBezierTo(size.width, size.height, size.width - c, size.height)
    lineTo(c * .35f, size.height)
    quadraticBezierTo(0f, size.height, 0f, size.height - c * .70f)
    lineTo(0f, c * .72f)
    quadraticBezierTo(0f, 0f, c * .75f, 0f)
    close()
}

// LEFT wing: full-height flush against the left screen edge, inner side has a smooth
// inward-curving saber cut so it looks like a sleek gaming side panel, not a trapezoid.
private val LeftWingShape = GenericShape { size, _ ->
    val r = size.height * .08f        // corner radius equivalent
    val inset = size.width * .14f     // how far the inner edge tapers at mid-point
    moveTo(0f, r)
    quadraticBezierTo(0f, 0f, r, 0f)
    // top of inner edge – slight diagonal down
    lineTo(size.width - inset * .3f, 0f)
    // inner saber curve: concave sweep inward then back out
    quadraticBezierTo(size.width + inset * .18f, size.height * .5f, size.width - inset * .3f, size.height)
    lineTo(r, size.height)
    quadraticBezierTo(0f, size.height, 0f, size.height - r)
    close()
}

// RIGHT wing: mirror of LeftWingShape, flush against the right screen edge.
private val RightWingShape = GenericShape { size, _ ->
    val r = size.height * .08f
    val inset = size.width * .14f
    moveTo(size.width - r, 0f)
    quadraticBezierTo(size.width, 0f, size.width, r)
    lineTo(size.width, size.height - r)
    quadraticBezierTo(size.width, size.height, size.width - r, size.height)
    lineTo(inset * .3f, size.height)
    // inner saber curve
    quadraticBezierTo(-inset * .18f, size.height * .5f, inset * .3f, 0f)
    close()
}

// TOP panel: flush at top, bottom edge has a smooth concave scoop.
private val TopTrapezoidShape = GenericShape { size, _ ->
    val r = size.width * .04f
    val scoop = size.height * .18f
    moveTo(r, 0f)
    lineTo(size.width - r, 0f)
    quadraticBezierTo(size.width, 0f, size.width, r)
    lineTo(size.width, size.height - scoop)
    quadraticBezierTo(size.width * .75f, size.height + scoop * .4f, size.width * .5f, size.height)
    quadraticBezierTo(size.width * .25f, size.height + scoop * .4f, 0f, size.height - scoop)
    lineTo(0f, r)
    quadraticBezierTo(0f, 0f, r, 0f)
    close()
}

// BOTTOM panel: flush at bottom, top edge has a smooth concave scoop.
private val BottomTrapezoidShape = GenericShape { size, _ ->
    val r = size.width * .04f
    val scoop = size.height * .18f
    moveTo(0f, scoop)
    quadraticBezierTo(size.width * .25f, -scoop * .4f, size.width * .5f, 0f)
    quadraticBezierTo(size.width * .75f, -scoop * .4f, size.width, scoop)
    lineTo(size.width, size.height - r)
    quadraticBezierTo(size.width, size.height, size.width - r, size.height)
    lineTo(r, size.height)
    quadraticBezierTo(0f, size.height, 0f, size.height - r)
    close()
}

private val ControlShape = GenericShape { size, _ ->
    val c = minOf(size.width, size.height) * .28f
    moveTo(c, 0f)
    lineTo(size.width - c * .35f, 0f)
    quadraticTo(size.width, 0f, size.width, c * .72f)
    lineTo(size.width, size.height - c)
    quadraticTo(size.width, size.height, size.width - c, size.height)
    lineTo(c * .35f, size.height)
    quadraticTo(0f, size.height, 0f, size.height - c * .72f)
    lineTo(0f, c)
    quadraticTo(0f, 0f, c, 0f)
    close()
}

private data class ToolVisual(
    val tool: FloatingHudTool,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private fun toolsFor(remote: NukeRemoteHudDefinition): List<ToolVisual> = remote.panels.mapNotNull { panel ->
    val mapping = when (panel.id) {
        "frame_control" -> FloatingHudTool.MAX_FPS to Icons.Outlined.Speed
        "deep_clean" -> FloatingHudTool.DEEP_CLEAN to Icons.Outlined.CleaningServices
        "crosshair" -> FloatingHudTool.CROSSHAIR to Icons.Outlined.GpsFixed
        "live_monitor" -> FloatingHudTool.MONITOR to Icons.Outlined.MonitorHeart
        "network" -> FloatingHudTool.NETWORK to Icons.Outlined.NetworkCheck
        "pressure_radar" -> FloatingHudTool.RESOURCE_RADAR to Icons.Outlined.Radar
        "screen_hud" -> FloatingHudTool.SCREEN_CONTROL to Icons.Outlined.ScreenRotation
        "cpu_monitor" -> FloatingHudTool.CPU_CLOCKS to Icons.Outlined.Radar
        else -> null
    } ?: return@mapNotNull null
    ToolVisual(mapping.first, panel.title, panel.subtitle, mapping.second)
}

private data class ToggleVisual(
    val toggle: FloatingHudToggle,
    val title: String,
    val icon: ImageVector,
)

private fun quickControlsFor(remote: NukeRemoteHudDefinition): List<ToggleVisual> = remote.quickControls.mapNotNull { item ->
    when (item.id) {
        "adaptive_mode" -> ToggleVisual(FloatingHudToggle.GAME_MODE, item.title, Icons.Outlined.SportsEsports)
        "focus" -> ToggleVisual(FloatingHudToggle.DND, item.title, Icons.Outlined.DoNotDisturbOn)
        "network_boost" -> ToggleVisual(FloatingHudToggle.NETWORK_BOOST, item.title, Icons.Outlined.Bolt)
        "crosshair_toggle" -> ToggleVisual(FloatingHudToggle.CROSSHAIR, item.title, Icons.Outlined.GpsFixed)
        "keep_awake" -> ToggleVisual(FloatingHudToggle.KEEP_AWAKE, item.title, Icons.Outlined.ScreenRotation)
        else -> null
    }
}

/**
 * One physical overlay window renders one wing. The service attaches two windows instead of one
 * transparent full-screen rectangle, so the gameplay gap in the middle remains visible *and*
 * touchable. Landscape uses left/right mirrored trapezoids; narrow/portrait viewports use
 * top/bottom trapezoids with the same separation rule.
 */
@Composable
private fun NukeFloatingWing(
    snapshot: FloatingHudSnapshot,
    moduleState: NukeModuleShopState,
    wing: FloatingHudWing,
    callbacks: FloatingHudCallbacks,
) {
    MaterialTheme(colorScheme = NukeColorScheme, typography = com.neon.gametweak.ui.theme.Typography) {
        var entered by remember { mutableStateOf(false) }
        var confirmEnd by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val progress by animateFloatAsState(
            if (entered) 1f else 0f,
            tween(snapshot.remoteDefinition.panelOpenMs),
            label = "edge-wing-reveal",
        )
        val animatedCpu by animateFloatAsState(
            targetValue = (snapshot.cpuPercent ?: 0).coerceIn(0, 100).toFloat(),
            animationSpec = tween(150),
            label = "cpu-fuel-level",
        )
        val animatedRam by animateFloatAsState(
            targetValue = (snapshot.ramPercent ?: 0).coerceIn(0, 100).toFloat(),
            animationSpec = tween(150),
            label = "ram-fuel-level",
        )
        val shape = when (wing) {
            FloatingHudWing.LEFT -> LeftWingShape
            FloatingHudWing.RIGHT -> RightWingShape
            FloatingHudWing.TOP -> BottomTrapezoidShape
            FloatingHudWing.BOTTOM -> TopTrapezoidShape
            FloatingHudWing.PORTRAIT -> RoundedCornerShape(14.dp)
        }
        val controlWing = wing == FloatingHudWing.LEFT || wing == FloatingHudWing.TOP
        val accent = if (controlWing) NukeGreen else NukeCyan
        // Generous inner padding on the curved side so content never overlaps the vertical battery bar
        val startPadding = when (wing) {
            FloatingHudWing.RIGHT -> 32.dp
            FloatingHudWing.LEFT -> 8.dp
            else -> 14.dp
        }
        val endPadding = when (wing) {
            FloatingHudWing.LEFT -> 32.dp
            FloatingHudWing.RIGHT -> 8.dp
            else -> 14.dp
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = .18f + progress * .82f
                    translationX = when (wing) {
                        FloatingHudWing.LEFT -> -(1f - progress) * 80.dp.toPx()
                        FloatingHudWing.RIGHT -> (1f - progress) * 80.dp.toPx()
                        else -> 0f
                    }
                    translationY = when (wing) {
                        FloatingHudWing.TOP -> -(1f - progress) * 60.dp.toPx()
                        FloatingHudWing.BOTTOM -> (1f - progress) * 60.dp.toPx()
                        else -> 0f
                    }
                }
                .clip(shape)
                .semantics {
                    contentDescription = if (controlWing) "Game Nuke control panel" else "Game Nuke plugin panel"
                }
                .drawWithCache {
                    val edgeStart = when (wing) {
                        FloatingHudWing.LEFT -> Offset.Zero
                        FloatingHudWing.RIGHT -> Offset(size.width, 0f)
                        FloatingHudWing.TOP -> Offset(size.width * .5f, 0f)
                        FloatingHudWing.BOTTOM, FloatingHudWing.PORTRAIT -> Offset(size.width * .5f, size.height)
                    }
                    val edgeEnd = when (wing) {
                        FloatingHudWing.LEFT -> Offset(size.width, size.height * .5f)
                        FloatingHudWing.RIGHT -> Offset(0f, size.height * .5f)
                        FloatingHudWing.TOP -> Offset(size.width * .5f, size.height)
                        FloatingHudWing.BOTTOM, FloatingHudWing.PORTRAIT -> Offset(size.width * .5f, 0f)
                    }
                    val bgGrad = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color(0xFF020506),
                            .30f to Color(0xFF060F0D),
                            .70f to Color(0xFF0B1714),
                            1f to Color(0xFF0F1E1A),
                        ),
                        start = edgeStart,
                        end = edgeEnd,
                    )
                    val glowGrad = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to accent.copy(alpha = 0f),
                            .25f to accent.copy(alpha = .55f),
                            .5f to accent.copy(alpha = .9f),
                            .75f to accent.copy(alpha = .55f),
                            1f to accent.copy(alpha = 0f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                    )
                    onDrawBehind {
                        drawRect(bgGrad)
                        // Subtle scanlines for depth
                        val lineSpacing = 18.dp.toPx()
                        var scanY = 0f
                        while (scanY < size.height) {
                            drawLine(
                                color = accent.copy(alpha = .035f),
                                start = Offset(0f, scanY),
                                end = Offset(size.width, scanY),
                                strokeWidth = .8.dp.toPx(),
                            )
                            scanY += lineSpacing
                        }
                        // Neon outer-edge glow stroke
                        val glowW = 2.8.dp.toPx()
                        when (wing) {
                            FloatingHudWing.LEFT -> drawLine(glowGrad, Offset(glowW / 2, 0f), Offset(glowW / 2, size.height), glowW)
                            FloatingHudWing.RIGHT -> drawLine(glowGrad, Offset(size.width - glowW / 2, 0f), Offset(size.width - glowW / 2, size.height), glowW)
                            FloatingHudWing.TOP -> drawLine(
                                Brush.linearGradient(listOf(accent.copy(0f), accent.copy(.9f), accent.copy(0f)), Offset(0f,0f), Offset(size.width,0f)),
                                Offset(0f, glowW / 2), Offset(size.width, glowW / 2), glowW
                            )
                            FloatingHudWing.BOTTOM, FloatingHudWing.PORTRAIT -> drawLine(
                                Brush.linearGradient(listOf(accent.copy(0f), accent.copy(.9f), accent.copy(0f)), Offset(0f,0f), Offset(size.width,0f)),
                                Offset(0f, size.height - glowW / 2), Offset(size.width, size.height - glowW / 2), glowW
                            )
                        }

                        // ── CURVED CAPSULE PROGRESS BAR (CPU on Left, RAM on Right) ──
                        if (wing == FloatingHudWing.LEFT || wing == FloatingHudWing.RIGHT) {
                            val isLeft = wing == FloatingHudWing.LEFT
                            val currentPercent = if (isLeft) animatedCpu else animatedRam
                            val gaugeAccent = if (isLeft) NukeGreen else NukeCyan
                            val gaugeLabel = if (isLeft) "CPU" else "RAM"
                            
                            val inset = size.width * .14f
                            val p0x = if (isLeft) size.width - inset * .3f else inset * .3f
                            val p1x = if (isLeft) size.width + inset * .18f else -inset * .18f
                            val p2x = if (isLeft) size.width - inset * .3f else inset * .3f
                            
                            fun getEdgeX(t: Float): Float {
                                val oneMinusT = 1f - t
                                return oneMinusT * oneMinusT * p0x + 2f * oneMinusT * t * p1x + t * t * p2x
                            }

                            // Balanced vertical height: centered on the inward curve (22% to 78%)
                            val tTop = 0.22f
                            val tBottom = 0.78f
                            val trackW = 10.dp.toPx()
                            val fillW = 6.5.dp.toPx()

                            // 1. Build the continuous curved path for the capsule progressbar
                            val pathSteps = 30
                            val fullTrackPath = androidx.compose.ui.graphics.Path()
                            for (step in 0..pathSteps) {
                                val t = tTop + (step.toFloat() / pathSteps) * (tBottom - tTop)
                                val cx = if (isLeft) getEdgeX(t) - 15.dp.toPx() else getEdgeX(t) + 15.dp.toPx()
                                val cy = t * size.height
                                if (step == 0) fullTrackPath.moveTo(cx, cy) else fullTrackPath.lineTo(cx, cy)
                            }

                            // 2. Draw outer slot / capsule track background
                            drawPath(
                                path = fullTrackPath,
                                color = Color(0xFF060E0C),
                                style = Stroke(width = trackW, cap = StrokeCap.Round),
                            )
                            // Outer hairline border for the slot
                            drawPath(
                                path = fullTrackPath,
                                color = gaugeAccent.copy(alpha = 0.35f),
                                style = Stroke(width = trackW + 1.2.dp.toPx(), cap = StrokeCap.Round),
                            )
                            drawPath(
                                path = fullTrackPath,
                                color = Color(0xFF081411),
                                style = Stroke(width = trackW, cap = StrokeCap.Round),
                            )

                            // 3. Draw active progress bar fill inside the track
                            val fillFraction = (currentPercent / 100f).coerceIn(0.02f, 1f)
                            val fillTTop = tBottom - fillFraction * (tBottom - tTop)
                            val fillPath = androidx.compose.ui.graphics.Path()
                            val fillSteps = 24
                            for (step in 0..fillSteps) {
                                val t = fillTTop + (step.toFloat() / fillSteps) * (tBottom - fillTTop)
                                val cx = if (isLeft) getEdgeX(t) - 15.dp.toPx() else getEdgeX(t) + 15.dp.toPx()
                                val cy = t * size.height
                                if (step == 0) fillPath.moveTo(cx, cy) else fillPath.lineTo(cx, cy)
                            }

                            val fillColor = when {
                                currentPercent >= 85f -> NukeRed
                                currentPercent >= 60f -> NukeAmber
                                else -> gaugeAccent
                            }

                            val fillGrad = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to fillColor,
                                    1f to gaugeAccent.copy(alpha = 0.6f),
                                ),
                                startY = fillTTop * size.height,
                                endY = tBottom * size.height,
                            )

                            // Draw the active glowing progress fill
                            drawPath(
                                path = fillPath,
                                brush = fillGrad,
                                style = Stroke(width = fillW, cap = StrokeCap.Round),
                            )

                            // Bright neon core reflection line inside the progress bar
                            drawPath(
                                path = fillPath,
                                color = Color.White.copy(alpha = 0.60f),
                                style = Stroke(width = fillW * 0.35f, cap = StrokeCap.Round),
                            )

                            // 4. Subtle signal / graduation tick marks along the track (like progress steps)
                            val tickCount = 10
                            for (tick in 1 until tickCount) {
                                val t = tBottom - (tick.toFloat() / tickCount.toFloat()) * (tBottom - tTop)
                                val cx = if (isLeft) getEdgeX(t) - 15.dp.toPx() else getEdgeX(t) + 15.dp.toPx()
                                val cy = t * size.height
                                val isTickActive = (tick.toFloat() / tickCount.toFloat()) * 100f <= currentPercent
                                
                                drawLine(
                                    color = if (isTickActive) Color(0xFF040A08).copy(alpha = 0.85f) else gaugeAccent.copy(alpha = 0.20f),
                                    start = Offset(cx - trackW * 0.38f, cy),
                                    end = Offset(cx + trackW * 0.38f, cy),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }

                            // 5. Labels: CPU/RAM cleanly at the top, % cleanly at the bottom (NO OVERLAP!)
                            val topCx = if (isLeft) getEdgeX(tTop) - 15.dp.toPx() else getEdgeX(tTop) + 15.dp.toPx()
                            val topCy = tTop * size.height - (trackW / 2) - 4.dp.toPx()
                            
                            val botCx = if (isLeft) getEdgeX(tBottom) - 15.dp.toPx() else getEdgeX(tBottom) + 15.dp.toPx()
                            val botCy = tBottom * size.height + (trackW / 2) + 12.dp.toPx()

                            val paintTopLabel = android.graphics.Paint().apply {
                                color = gaugeAccent.toArgb()
                                textSize = 8.5.sp.toPx()
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            val paintBotValue = android.graphics.Paint().apply {
                                color = fillColor.toArgb()
                                textSize = 8.5.sp.toPx()
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }

                            // Draw "CPU" or "RAM" above the progressbar
                            drawContext.canvas.nativeCanvas.drawText(gaugeLabel, topCx, topCy, paintTopLabel)
                            // Draw "XX%" below the progressbar
                            drawContext.canvas.nativeCanvas.drawText("${currentPercent.roundToInt()}%", botCx, botCy, paintBotValue)
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = startPadding, end = endPadding, top = 10.dp, bottom = 10.dp),
            ) {
                if (controlWing) {
                    ControlWingContent(snapshot, callbacks)
                } else {
                    ModuleWingContent(
                        snapshot = snapshot,
                        state = moduleState,
                        callbacks = callbacks,
                        confirmEnd = confirmEnd,
                        onConfirmEndChanged = { confirmEnd = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(label: String, value: String, tint: Color) {
    Row(
        Modifier
            .height(28.dp)
            .clip(ControlShape)
            .background(tint.copy(alpha = 0.08f))
            .border(0.6.dp, tint.copy(alpha = 0.25f), ControlShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(value, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        Text(label, color = NukeMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NukePortraitCockpit(
    snapshot: FloatingHudSnapshot,
    moduleState: NukeModuleShopState,
    callbacks: FloatingHudCallbacks,
) {
    MaterialTheme(colorScheme = NukeColorScheme, typography = com.neon.gametweak.ui.theme.Typography) {
        var entered by remember { mutableStateOf(false) }
        var confirmEnd by rememberSaveable { mutableStateOf(false) }
        var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: TOOLS, 1: CONTROLS, 2: PLUGINS
        var isCleaning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) { entered = true }
        val progress by animateFloatAsState(
            if (entered) 1f else 0f,
            tween(snapshot.remoteDefinition.panelOpenMs),
            label = "portrait-cockpit-reveal",
        )
        val animatedCpu by animateFloatAsState(
            targetValue = (snapshot.cpuPercent ?: 0).coerceIn(0, 100).toFloat(),
            animationSpec = tween(150),
            label = "portrait-cpu",
        )
        val animatedRam by animateFloatAsState(
            targetValue = (snapshot.ramPercent ?: 0).coerceIn(0, 100).toFloat(),
            animationSpec = tween(150),
            label = "portrait-ram",
        )

        val cockpitShape = RoundedCornerShape(14.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = .18f + progress * .82f
                    scaleX = .94f + progress * .06f
                    scaleY = .94f + progress * .06f
                }
                .clip(cockpitShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF030706),
                            Color(0xFF0A1613),
                            Color(0xFF08120F),
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                NukeGreen.copy(alpha = 0.85f),
                                NukeCyan.copy(alpha = 0.65f),
                                NukeGreen.copy(alpha = 0.85f),
                            )
                        )
                    ),
                    cockpitShape
                )
                .drawWithCache {
                    onDrawBehind {
                        // Subtle scanlines
                        val lineSpacing = 16.dp.toPx()
                        var scanY = 0f
                        while (scanY < size.height) {
                            drawLine(
                                color = NukeCyan.copy(alpha = 0.035f),
                                start = Offset(0f, scanY),
                                end = Offset(size.width, scanY),
                                strokeWidth = 0.8.dp.toPx(),
                            )
                            scanY += lineSpacing
                        }
                    }
                }
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── 1. Top Draggable Cyber Header ──────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(ControlShape)
                        .background(NukePanelBright)
                        .border(0.8.dp, NukeGreen.copy(alpha = 0.35f), ControlShape)
                        .pointerInput(callbacks) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                callbacks.onDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(BayShape)
                            .background(NukeGreen.copy(alpha = 0.18f))
                            .border(0.8.dp, NukeGreen.copy(alpha = 0.60f), BayShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ParticleGlyph(14.dp, NukeGreen)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "GAME NUKE",
                                color = NukeGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.7.sp,
                            )
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(NukeCyan.copy(alpha = 0.20f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    snapshot.gameLabel.uppercase(),
                                    color = NukeCyan,
                                    fontSize = 5.8.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            "DRAG TO REPOSITION • COCKPIT LIVE",
                            color = NukeMuted,
                            fontSize = 5.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    HeaderAction(Icons.Outlined.Close, "Minimize", NukeCyan, false, true, callbacks.onMinimize)
                    if (snapshot.remoteDefinition.showEndSession) {
                        Spacer(Modifier.width(4.dp))
                        HeaderAction(Icons.Outlined.PowerSettingsNew, "End Session", NukeRed, true, true) {
                            confirmEnd = true
                        }
                    }
                }

                Spacer(Modifier.height(5.dp))

                // ── End Session Dialog Gate ──────────────────────────────
                if (confirmEnd) {
                    CompactEndSessionGate(
                        onCancel = { confirmEnd = false },
                        onConfirm = callbacks.onEndSession,
                    )
                    Spacer(Modifier.height(5.dp))
                }

                // ── 2. Unified Live Telemetry Matrix ─────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TelemetryChip("FPS", snapshot.fps, NukeGreen)
                    TelemetryChip("TCP", probeText(snapshot), probeColor(snapshot))
                    TelemetryChip("°C", snapshot.temperature, NukeAmber)
                    TelemetryChip("BAT", snapshot.battery, NukeCyan)
                    Row(
                        Modifier
                            .height(28.dp)
                            .clip(ControlShape)
                            .background(NukeGreen.copy(alpha = 0.08f))
                            .border(0.6.dp, NukeGreen.copy(alpha = 0.28f), ControlShape)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("CPU", color = NukeMuted, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        Text("${animatedCpu.roundToInt()}%", color = NukeGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                    Row(
                        Modifier
                            .height(28.dp)
                            .clip(ControlShape)
                            .background(NukeCyan.copy(alpha = 0.08f))
                            .border(0.6.dp, NukeCyan.copy(alpha = 0.28f), ControlShape)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("RAM", color = NukeMuted, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        Text("${animatedRam.roundToInt()}%", color = NukeCyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(Modifier.height(5.dp))

                // ── 3. Cyber Segment Tab Switcher ─────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val tabs = listOf(
                        Triple(0, "ENGINES", Icons.Outlined.Tune),
                        Triple(1, "CONTROLS", Icons.Outlined.Widgets),
                        Triple(2, "PLUGINS (${moduleState.modules.size})", Icons.Outlined.Extension),
                    )
                    tabs.forEach { (index, title, icon) ->
                        val isSelected = selectedTab == index
                        val tabTint = if (index == 0) NukeGreen else NukeCyan
                        val tabBg by animateColorAsState(
                            if (isSelected) tabTint.copy(alpha = 0.22f) else Color(0xFF0F1B17),
                            tween(100), "tabBg$index"
                        )
                        val tabBorder by animateColorAsState(
                            if (isSelected) tabTint else NukeHairline,
                            tween(100), "tabBorder$index"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(ControlShape)
                                .background(tabBg)
                                .border(if (isSelected) 1.dp else 0.6.dp, tabBorder, ControlShape)
                                .clickable { selectedTab = index }
                                .padding(horizontal = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    icon,
                                    null,
                                    tint = if (isSelected) tabTint else NukeMuted,
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    title,
                                    color = if (isSelected) Color.White else NukeMuted,
                                    fontSize = 7.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(5.dp))

                // ── 4. Tab Body Content ──────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (selectedTab) {
                        0 -> {
                            TacticalEnginesDeckView(snapshot = snapshot, callbacks = callbacks)
                        }
                        1 -> {
                            QuickActionsDeckView(
                                snapshot = snapshot,
                                state = moduleState,
                                callbacks = callbacks,
                                confirmEnd = false,
                                onConfirmEndChanged = {},
                                onOpenModuleShop = { selectedTab = 2 },
                                isCleaning = isCleaning,
                                onTriggerClean = {
                                    if (!isCleaning) {
                                        isCleaning = true
                                        scope.launch {
                                            callbacks.onQuickAction("deep_clean")
                                            delay(1200L)
                                            isCleaning = false
                                        }
                                    }
                                },
                            )
                        }
                        2 -> {
                            ModuleShopFullView(
                                state = moduleState,
                                callbacks = callbacks,
                                onBack = { selectedTab = 1 },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ── 5. Status Rail ───────────────────────────────────────
                StatusRail(snapshot.statusMessage, true)
            }
        }
    }
}

@Composable
private fun ControlWingContent(snapshot: FloatingHudSnapshot, callbacks: FloatingHudCallbacks) {
    TacticalEnginesDeckView(snapshot = snapshot, callbacks = callbacks)
}

@Composable
private fun TacticalEnginesDeckView(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
) {
    val states = snapshot.quickToolStates
    val isGameOn = states["game_mode"] ?: false
    val isTouchOn = states["touch_response"] ?: false
    val isNetOn = states["net_boost"] ?: false
    val isCpuTurboOn = states["cpu_turbo"] ?: false
    val isPhoneHealthOn = states["phone_health"] ?: false
    val isMagicTouchOn = states["magic_touch"] ?: false
    val isGpuTunerOn = states["gpu_tuner"] ?: false
    val isAiSentinelOn = states["ai_sentinel"] ?: true
    val isAiCoolingOn = states["ai_cooling"] ?: false
    val isTaskManagerOn = states["task_manager"] ?: false
    val isSystemEditorOn = states["system_editor"] ?: false
    val isLiveChatOn = states["live_chat"] ?: false
    val isTerminalOn = states["terminal"] ?: false
    val isVpnOn = states["vpn_boost"] ?: false
    val isFootstepOn = states["footstep_boost"] ?: false
    val isWikiOn = states["wiki_pip"] ?: false
    val isDeepCoolingOn = states["deep_cooling"] ?: false

    Column(Modifier.fillMaxSize()) {
        // ── Header: Logo + game name + close ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(ControlShape)
                .background(NukePanelBright)
                .border(.8.dp, NukeGreen.copy(alpha = .35f), ControlShape)
                .pointerInput(callbacks) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        callbacks.onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(BayShape)
                    .background(NukeGreen.copy(alpha = .18f))
                    .border(.8.dp, NukeGreen.copy(alpha = .60f), BayShape),
                contentAlignment = Alignment.Center,
            ) { ParticleGlyph(14.dp, NukeGreen) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "GAME NUKE",
                    color = NukeGreen,
                    fontSize = 8.8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = .7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "TACTICAL ENGINES DECK",
                    color = NukeMuted,
                    fontSize = 5.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HeaderAction(Icons.Outlined.Close, "Minimize", NukeCyan, false, true, callbacks.onMinimize)
        }
        Spacer(Modifier.height(4.dp))

        // ── Telemetry: compact inline badges ─────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(26.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(
                Triple("FPS", snapshot.fps, NukeGreen),
                Triple("TCP", probeText(snapshot), probeColor(snapshot)),
                Triple("°C", snapshot.temperature, NukeAmber),
                Triple("BAT", snapshot.battery, NukeCyan),
            ).forEach { (label, value, tint) ->
                Row(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(ControlShape)
                        .background(tint.copy(alpha = .08f))
                        .border(.6.dp, tint.copy(alpha = .22f), ControlShape)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(value, color = tint, fontSize = 8.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Spacer(Modifier.width(2.dp))
                    Text(label, color = NukeMuted, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── PRO TUNING ENGINES (EXPANDABLE) ────────────────────
            SectionDivider("ADVANCED TOOLS")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelLauncherCard(
                    icon = Icons.Outlined.Security,
                    title = "TASK MANAGER",
                    badgeText = "PROCESS",
                    statusText = if (isTaskManagerOn) "ACTIVE • OPEN" else "MANAGE BACKGROUND APPS",
                    isOpen = isTaskManagerOn,
                    onClick = { callbacks.onQuickAction("task_manager") },
                    modifier = Modifier.weight(1f)
                )
                PanelLauncherCard(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "AI SENTINEL",
                    badgeText = if (isAiCoolingOn) "THERMAL" else if (isAiSentinelOn) "ADAPTIVE" else "OFF",
                    statusText = if (isAiCoolingOn) "THERMAL RESPONSE" else if (isAiSentinelOn) "SESSION MONITOR ON" else "PAUSED",
                    isOpen = isAiSentinelOn || isAiCoolingOn,
                    onClick = { callbacks.onQuickAction("ai_sentinel") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelLauncherCard(
                    icon = Icons.Outlined.TouchApp,
                    title = "TOUCH LISTENER",
                    badgeText = "STUDIO",
                    statusText = if (isMagicTouchOn) "ACTIVE • OPEN" else "TOUCH MULTIPLIER CONTROLS",
                    isOpen = isMagicTouchOn,
                    onClick = { callbacks.onQuickAction("magic_touch") },
                    modifier = Modifier.weight(1f)
                )
                PanelLauncherCard(
                    icon = Icons.Outlined.SportsEsports,
                    title = "GPU TUNER",
                    badgeText = "ENGINE",
                    statusText = if (isGpuTunerOn) "ACTIVE • OPEN" else "GRAPHICS PIPELINE",
                    isOpen = isGpuTunerOn,
                    onClick = { callbacks.onQuickAction("gpu_tuner") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelLauncherCard(
                    icon = Icons.Outlined.HealthAndSafety,
                    title = "DEVICE HEALTH",
                    badgeText = "SENSORS",
                    statusText = if (isPhoneHealthOn) "ACTIVE • OPEN" else "HARDWARE TELEMETRY",
                    isOpen = isPhoneHealthOn,
                    onClick = { callbacks.onQuickAction("phone_health") },
                    modifier = Modifier.weight(1f)
                )
                PanelLauncherCard(
                    icon = Icons.Outlined.MenuBook,
                    title = "WIKI PIP",
                    badgeText = "GUIDE",
                    statusText = if (isWikiOn) "ACTIVE • OPEN" else "HERO & META COUNTER",
                    isOpen = isWikiOn,
                    onClick = { callbacks.onQuickAction("wiki_pip") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelLauncherCard(
                    icon = Icons.Outlined.Tune,
                    title = "DEVICE SYS",
                    badgeText = "TUNING",
                    statusText = if (isSystemEditorOn) "ACTIVE • OPEN" else "DEVICE SYSTEM EDITOR",
                    isOpen = isSystemEditorOn,
                    onClick = { callbacks.onQuickAction("system_editor") },
                    modifier = Modifier.weight(1f)
                )
                PanelLauncherCard(
                    icon = Icons.Outlined.AcUnit,
                    title = "THERMAL CONTROL",
                    badgeText = "MONITOR",
                    statusText = if (isDeepCoolingOn) "ACTIVE • OPEN" else "TEMP & LOAD TOOLS",
                    isOpen = isDeepCoolingOn,
                    onClick = { callbacks.onQuickAction("deep_cooling") },
                    modifier = Modifier.weight(1f)
                )
            }

            // ── TACTICAL HARDWARE TUNING — 3 column square grid ───────────────
            SectionDivider("DEVICE PERFORMANCE CONTROLS")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TacticalHardwareCard(Icons.Outlined.Speed, "CPU PROFILE", "PERF", "AUTO", checked = isCpuTurboOn, onToggle = { callbacks.onQuickAction("cpu_turbo") }, modifier = Modifier.weight(1f))
                TacticalHardwareCard(Icons.Outlined.NetworkCheck, "NET PRIORITY", "ACTIVE", "STD", checked = isNetOn, onToggle = { callbacks.onQuickAction("net_boost") }, modifier = Modifier.weight(1f))
                TacticalHardwareCard(Icons.Outlined.Bolt, "LOCAL NET", "ON", "OFF", checked = isVpnOn, onToggle = { callbacks.onQuickAction("vpn_boost") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TacticalHardwareCard(Icons.Outlined.TouchApp, "TOUCH TUNE", "ACTIVE", "STD", checked = isTouchOn, onToggle = { callbacks.onQuickAction("touch_response") }, modifier = Modifier.weight(1f))
                TacticalHardwareCard(Icons.Outlined.Tune, "AUDIO TUNE", "ACTIVE", "STD", checked = isFootstepOn, onToggle = { callbacks.onQuickAction("footstep_boost") }, modifier = Modifier.weight(1f))
                TacticalHardwareCard(Icons.Outlined.SportsEsports, "GAME MODE", "PERF", "STD", checked = isGameOn, onToggle = { callbacks.onQuickAction("game_mode") }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        StatusRail(snapshot.statusMessage, true)
    }
}

@Composable
private fun WingTelemetryRail(snapshot: FloatingHudSnapshot) {
    val metrics = listOf(
        Triple("FPS", snapshot.fps, NukeGreen),
        Triple("TCP", probeText(snapshot), probeColor(snapshot)),
        Triple("°C", snapshot.temperature, NukeAmber),
    )
    Row(
        Modifier.fillMaxWidth().height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        metrics.forEach { (label, value, tint) ->
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(ControlShape)
                    .background(NukePanelBright)
                    .border(.8.dp, tint.copy(alpha = .28f), ControlShape)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(value, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text(label, color = NukeMuted, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ModuleWingContent(
    snapshot: FloatingHudSnapshot,
    state: NukeModuleShopState,
    callbacks: FloatingHudCallbacks,
    confirmEnd: Boolean,
    onConfirmEndChanged: (Boolean) -> Unit,
) {
    var inModuleShopView by rememberSaveable { mutableStateOf(false) }
    var isCleaning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (inModuleShopView) {
        ModuleShopFullView(
            state = state,
            callbacks = callbacks,
            onBack = { inModuleShopView = false },
        )
    } else {
        QuickActionsDeckView(
            snapshot = snapshot,
            state = state,
            callbacks = callbacks,
            confirmEnd = confirmEnd,
            onConfirmEndChanged = onConfirmEndChanged,
            onOpenModuleShop = { inModuleShopView = true },
            isCleaning = isCleaning,
            onTriggerClean = {
                if (!isCleaning) {
                    isCleaning = true
                    scope.launch {
                        callbacks.onQuickAction("deep_clean")
                        delay(1200L)
                        isCleaning = false
                    }
                }
            },
        )
    }
}

@Composable
private fun QuickActionsDeckView(
    snapshot: FloatingHudSnapshot,
    state: NukeModuleShopState,
    callbacks: FloatingHudCallbacks,
    confirmEnd: Boolean,
    onConfirmEndChanged: (Boolean) -> Unit,
    onOpenModuleShop: () -> Unit,
    isCleaning: Boolean,
    onTriggerClean: () -> Unit,
) {
    val states = snapshot.quickToolStates
    val unsupported = snapshot.unsupportedQuickTools

    val isFpsLockOn = states["fps_lock"] ?: false
    val fpsLockHz by NukeUniversalFpsLock.targetFpsState.collectAsState()
    val fpsLockBadge = if (fpsLockHz > 0) "${fpsLockHz}Hz" else "AUTO"
    val isGameOn = states["game_mode"] ?: false
    val isDndOn = states["dnd"] ?: false
    val isTouchOn = states["touch_response"] ?: false
    val isNetOn = states["net_boost"] ?: false
    val isHotspotOn = states["hotspot"] ?: false
    val isSilentOn = states["silent_mode"] ?: false
    val isReadingOn = states["reading_mode"] ?: false
    val isDarkOn = states["dark_mode"] ?: false
    val isRotationOn = states["rotation_lock"] ?: false
    val isBatteryOn = states["battery_saver"] ?: false
    val isBluetoothOn = states["bluetooth"] ?: false
    val isAirplaneOn = states["airplane_mode"] ?: false
    val isVibrationOn = states["vibration"] ?: true
    val isCpuTurboOn = states["cpu_turbo"] ?: false
    val isScreenTimeoutOn = states["screen_timeout_extend"] ?: false
    val isDataSaverOn = states["data_saver"] ?: false
    val isPhoneHealthOn = states["phone_health"] ?: false
    val isMagicTouchOn = states["magic_touch"] ?: false
    val isGpuTunerOn = states["gpu_tuner"] ?: false
    val isAiSentinelOn = states["ai_sentinel"] ?: true
    val isVpnOn = states["vpn_boost"] ?: false
    val isFootstepOn = states["footstep_boost"] ?: false
    val isWikiOn = states["wiki_pip"] ?: false
    val isLiveChatOn = states["live_chat"] ?: false
    val isTerminalOn = states["terminal"] ?: false
    val isFpsChipOn = states["fps_overlay"] ?: false
    val isCrosshairOn = snapshot.toggleValues[FloatingHudToggle.CROSSHAIR] == true
    val isBrightnessLockOn = states["brightness_lock"] ?: false
    val isGameDockOn = states["game_dock"] ?: false
    val isDeepCoolingOn = states["deep_cooling"] ?: false
    val isAntivirusOn = states["antivirus"] ?: false

    Column(Modifier.fillMaxSize()) {
        // ── Compact Header ───────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth()
                .height(32.dp)
                .clip(ControlShape)
                .background(NukePanelBright)
                .border(.8.dp, NukeCyan.copy(alpha = .25f), ControlShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(BayShape)
                    .background(NukeCyan.copy(alpha = .15f))
                    .border(.7.dp, NukeCyan.copy(alpha = .5f), BayShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Widgets, null, tint = NukeCyan, modifier = Modifier.size(11.dp)) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "SYSTEM CONTROLS",
                    color = NukeCyan,
                    fontSize = 8.8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "QUICK ACTION DECK",
                    color = NukeMuted,
                    fontSize = 5.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (snapshot.remoteDefinition.showEndSession) {
                Spacer(Modifier.width(4.dp))
                HeaderAction(Icons.Outlined.PowerSettingsNew, "End session", NukeRed, true, true) {
                    onConfirmEndChanged(true)
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (confirmEnd) {
            CompactEndSessionGate(
                onCancel = { onConfirmEndChanged(false) },
                onConfirm = callbacks.onEndSession,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // ── Top Action Bar: Crosshair Studio, TCP Ping, Live FPS ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SquareMiniCard(Icons.Outlined.GpsFixed, "CROSSHAIR", "STUDIO", "STUDIO", checked = isCrosshairOn, onToggle = { callbacks.onQuickAction("crosshair_studio") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.NetworkCheck, "TCP PING", "ON", "OFF", checked = snapshot.probeEnabled, onToggle = { callbacks.onQuickAction("ping_monitor") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.Speed, "LIVE FPS", "ON", "OFF", checked = isFpsChipOn, onToggle = { callbacks.onQuickAction("fps_overlay") }, modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TopActionPill(
                        icon = Icons.Outlined.Extension,
                        label = "PLUGINS",
                        badge = "${state.modules.size}",
                        tint = NukeCyan,
                        active = false,
                        modifier = Modifier.wrapContentWidth(),
                        onClick = onOpenModuleShop
                    )
                    TopActionPill(
                        icon = Icons.Outlined.CleaningServices,
                        label = if (isCleaning) "CLEANING…" else "DEEP CLEAN",
                        badge = null,
                        tint = NukeGreen,
                        active = isCleaning,
                        modifier = Modifier.wrapContentWidth(),
                        onClick = { if (!isCleaning) onTriggerClean() }
                    )
                    TopActionPill(
                        icon = Icons.Outlined.Forum,
                        label = "LIVE CHAT",
                        badge = if (isLiveChatOn) "❐ OPEN" else "❐ WIN",
                        tint = NukeCyan,
                        active = isLiveChatOn,
                        modifier = Modifier.wrapContentWidth(),
                        onClick = { callbacks.onQuickAction("live_chat") }
                    )
                    TopActionPill(
                        icon = Icons.Outlined.Terminal,
                        label = "TERMINAL",
                        badge = if (isTerminalOn) "❐ OPEN" else "❐ WIN",
                        tint = NukeGreen,
                        active = isTerminalOn,
                        modifier = Modifier.wrapContentWidth(),
                        onClick = { callbacks.onQuickAction("terminal") }
                    )
                }

                // ── Sliders ──────────────────────────────────────────────────────
                BrightnessSliderCard(percent = snapshot.brightnessPercent, onChanged = callbacks.onBrightnessChanged)
                DensityStepperCard(currentDpi = snapshot.displayDpi, onChanged = callbacks.onDpiChanged)

                // ── DISPLAY & ENVIRONMENT — 3 column square grid ─────────────────
                SectionDivider("DISPLAY & ENVIRONMENT")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SquareMiniCard(Icons.Outlined.Speed, "REFRESH TARGET", fpsLockBadge, "AUTO", checked = fpsLockHz > 0 || isFpsLockOn, onToggle = { callbacks.onQuickAction("fps_lock") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.MonitorHeart, "FPS HUD", "CHIP", "OFF", checked = isFpsChipOn, onToggle = { callbacks.onQuickAction("fps_overlay") }, modifier = Modifier.weight(1f), isFloatingWindow = true)
                    SquareMiniCard(Icons.Outlined.GpsFixed, "CROSSHAIR", "OVERLAY", "OFF", checked = isCrosshairOn, onToggle = { callbacks.onToggle(FloatingHudToggle.CROSSHAIR, !isCrosshairOn) }, modifier = Modifier.weight(1f), isFloatingWindow = true)
                    SquareMiniCard(Icons.Outlined.Brightness6, "BRIGHT LOCK", "MAX", "AUTO", checked = isBrightnessLockOn, onToggle = { callbacks.onQuickAction("brightness_lock") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SquareMiniCard(Icons.Outlined.ScreenRotation, "ROTATION", "LOCKED", "AUTO", checked = isRotationOn, onToggle = { callbacks.onQuickAction("rotation_lock") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.Nightlight, "EYE COMFORT", "WARM", "OFF", checked = isReadingOn, onToggle = { callbacks.onQuickAction("reading_mode") }, modifier = Modifier.weight(1f), supported = !unsupported.contains("reading_mode"))
                    SquareMiniCard(Icons.Outlined.DarkMode, "DARK THEME", "NIGHT", "DAY", checked = isDarkOn, onToggle = { callbacks.onQuickAction("dark_mode") }, modifier = Modifier.weight(1f), supported = !unsupported.contains("dark_mode"))
                }

                // ── DEVICE & SYSTEM CONTROLS — 3 column square grid ───────────────
                SectionDivider("DEVICE & SYSTEM CONTROLS")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SquareMiniCard(Icons.Outlined.DoNotDisturbOn, "DND MODE", "MUTED", "OFF", checked = isDndOn, onToggle = { callbacks.onQuickAction("dnd") }, modifier = Modifier.weight(1f), isWarning = true)
                    SquareMiniCard(Icons.Outlined.HourglassEmpty, "TIMEOUT", "+30M", "1M", checked = isScreenTimeoutOn, onToggle = { callbacks.onQuickAction("screen_timeout_extend") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.BatterySaver, "BATTERY", "SAVE", "OFF", checked = isBatteryOn, onToggle = { callbacks.onQuickAction("battery_saver") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SquareMiniCard(Icons.Outlined.VolumeOff, "SILENT", "MUTED", "SOUND", checked = isSilentOn, onToggle = { callbacks.onQuickAction("silent_mode") }, modifier = Modifier.weight(1f), isWarning = true)
                    SquareMiniCard(Icons.Outlined.WifiTethering, "HOTSPOT", "ON", "OFF", checked = isHotspotOn, onToggle = { callbacks.onQuickAction("hotspot") }, modifier = Modifier.weight(1f))
                    SquareMiniCard(Icons.Outlined.Bluetooth, "BLUETOOTH", "ON", "OFF", checked = isBluetoothOn, onToggle = { callbacks.onQuickAction("bluetooth") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SquareMiniCard(Icons.Outlined.Widgets, "CYBER DECK", "PORTAL", "OFF", checked = isGameDockOn, onToggle = { callbacks.onQuickAction("game_dock") }, modifier = Modifier.weight(1f), isFloatingWindow = true)
                    SquareMiniCard(Icons.Outlined.Security, "APP SECURITY", "MONITOR", "READY", checked = isAntivirusOn, onToggle = { callbacks.onQuickAction("antivirus") }, modifier = Modifier.weight(1f), isFloatingWindow = true)
                    SquareMiniCard(Icons.Outlined.CleaningServices, "CACHE TRIM", "OPTIMIZE", "READY", checked = false, onToggle = { callbacks.onQuickAction("zombie_clean") }, modifier = Modifier.weight(1f), isWarning = false)
                }
            }
        }
        StatusRail(state.message, true)
    }
}

// ── Expandable Panel Launcher Card ──
@Composable
private fun PanelLauncherCard(
    icon: ImageVector,
    title: String,
    badgeText: String,
    statusText: String,
    isOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customAccent: Color? = null,
) {
    val accent = customAccent ?: NukeGreen
    val cardShape = RoundedCornerShape(8.dp)
    val bg by animateColorAsState(
        if (isOpen) Color(0xFF142B22) else Color(0xFF0C131A),
        tween(120), "panelBg"
    )
    val border by animateColorAsState(
        if (isOpen) accent else Color(0xFF1B2836),
        tween(120), "panelBorder"
    )

    Box(
        modifier = modifier.nukeCardEntrance().nukePressFeedback()
            .height(50.dp)
            .clip(cardShape)
            .background(bg)
            .border(if (isOpen) 1.dp else 0.7.dp, border, cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isOpen) accent.copy(alpha = 0.22f) else Color(0xFF141E28))
                        .border(0.6.dp, if (isOpen) accent.copy(alpha = 0.6f) else Color(0xFF1F2E3D), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = if (isOpen) accent else Color(0xFF88A0B2), modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(6.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = title,
                        color = if (isOpen) accent else Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText,
                        color = if (isOpen) accent.copy(alpha = 0.85f) else Color(0xFF64748B),
                        fontSize = 6.8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOpen) accent.copy(alpha = 0.20f) else Color(0xFF121B24))
                    .border(0.6.dp, if (isOpen) accent.copy(alpha = 0.60f) else Color(0xFF1C2A38), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isOpen) "❐ OPEN" else "❐ $badgeText",
                    color = if (isOpen) accent else Color(0xFF8499AB),
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ── Tactical Hardware Military Card ──
@Composable
private fun TacticalHardwareCard(
    icon: ImageVector,
    label: String,
    activeText: String,
    inactiveText: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tacticalShape = CutCornerShape(topEnd = 8.dp, bottomStart = 4.dp)
    val bg by animateColorAsState(
        if (checked) Color(0xFF091610) else Color(0xFF090E11),
        tween(120), "tacticalBg"
    )
    val border by animateColorAsState(
        if (checked) NukeGreen else Color(0xFF1B2C24),
        tween(120), "tacticalBorder"
    )
    val iconTint by animateColorAsState(
        if (checked) NukeGreen else Color(0xFF6B8074),
        tween(120), "tacticalIcon"
    )

    Box(
        modifier = modifier.nukeCardEntrance().nukePressFeedback()
            .height(54.dp)
            .clip(tacticalShape)
            .background(bg)
            .border(if (checked) 1.dp else 0.7.dp, border, tacticalShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(12.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (checked) NukeGreen else Color(0xFF22362C))
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .clip(CutCornerShape(topEnd = 3.dp))
                        .background(if (checked) NukeGreen.copy(alpha = 0.20f) else Color(0xFF121B16))
                        .border(0.6.dp, if (checked) NukeGreen.copy(alpha = 0.6f) else Color(0xFF1F2F27), CutCornerShape(topEnd = 3.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(11.dp),
                    )
                }

                Text(
                    text = if (checked) activeText else inactiveText,
                    color = if (checked) NukeGreen else Color(0xFF6B8074),
                    fontSize = 6.3.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }

            Column {
                Text(
                    text = label,
                    color = if (checked) Color.White else Color(0xFF9EABA4),
                    fontSize = 7.2.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 0.2.sp,
                )
                Text(
                    text = if (checked) "TACTICAL • ON" else "READY",
                    color = if (checked) NukeGreen.copy(alpha = 0.85f) else Color(0xFF526159),
                    fontSize = 5.8.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Square Proportional Mini Card ──
@Composable
private fun SquareMiniCard(
    icon: ImageVector,
    title: String,
    activeLabel: String,
    inactiveLabel: String,
    accent: Color = NukeGreen,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supported: Boolean = true,
    isWarning: Boolean = false,
    isFloatingWindow: Boolean = false,
) {
    val isActive = checked && supported
    val effectiveAccent = if (isWarning && isActive) NukeAmber else accent
    val cardShape = RoundedCornerShape(8.dp)
    val bg by animateColorAsState(
        if (isActive) (if (isWarning) Color(0xFF241A0E) else Color(0xFF0F261E)) else Color(0xFF0F2018),
        tween(100), "sqBg",
    )
    val border by animateColorAsState(
        if (isActive) effectiveAccent else Color(0xFF1D3528),
        tween(100), "sqBorder",
    )
    Box(
        modifier = modifier.nukeCardEntrance().nukePressFeedback()
            .aspectRatio(1.08f)
            .clip(cardShape)
            .background(bg)
            .border(1.dp, border, cardShape)
            .clickable(enabled = supported, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(effectiveAccent)
            )
        }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (isActive) (if (isWarning) Color(0xFF382614) else Color(0xFF163C2E)) else Color(0xFF131D27)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = if (!supported) Color(0xFF33463E) else if (isActive) effectiveAccent else Color(0xFF7A93A6),
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                title,
                color = if (!supported) Color(0xFF33463E) else if (isActive) Color.White else Color(0xFF9BB0A6),
                fontSize = 6.8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isActive) (if (isWarning) Color(0xFF452D12) else Color(0xFF194A37)) else Color(0xFF121B24))
                    .border(0.5.dp, if (isActive) effectiveAccent.copy(alpha = 0.6f) else Color(0xFF1E2B38), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                val labelText = when {
                    !supported -> "N/A"
                    isFloatingWindow -> if (isActive) "❐ $activeLabel" else "❐ $inactiveLabel"
                    isActive -> activeLabel
                    else -> inactiveLabel
                }
                Text(
                    labelText,
                    color = if (!supported) Color(0xFF33463E) else if (isActive) effectiveAccent else Color(0xFF64748B),
                    fontSize = if (isFloatingWindow) 5.8.sp else 6.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

@Composable
private fun BrightnessSliderCard(
    percent: Int,
    onChanged: (Int) -> Unit,
) {
    var sliderVal by remember(percent) { mutableStateOf(percent.toFloat()) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(NukePanelBright)
            .border(0.8.dp, NukeHairline, ControlShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Brightness6, null, tint = NukeGreen, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("SCREEN BRIGHTNESS", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
                }
                Box(
                    Modifier
                        .clip(ControlShape)
                        .background(NukeGreen.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("${sliderVal.roundToInt()}%", color = NukeGreen, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
            }
            Slider(
                value = sliderVal,
                onValueChange = { sliderVal = it },
                onValueChangeFinished = { onChanged(sliderVal.roundToInt()) },
                valueRange = 5f..100f,
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = SliderDefaults.colors(
                    thumbColor = NukeGreen,
                    activeTrackColor = NukeGreen,
                    inactiveTrackColor = Color(0xFF0F1E19),
                ),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(25, 50, 75, 100).forEach { preset ->
                    val isSelected = sliderVal.roundToInt() == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ControlShape)
                            .background(if (isSelected) NukeGreen.copy(alpha = 0.22f) else Color(0xFF0C1411))
                            .border(0.6.dp, if (isSelected) NukeGreen else NukeHairline, ControlShape)
                            .clickable {
                                sliderVal = preset.toFloat()
                                onChanged(preset)
                            }
                            .padding(vertical = 1.5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (preset == 100) "MAX" else "$preset%", color = if (isSelected) NukeGreen else NukeMuted, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DensityStepperCard(
    currentDpi: Int,
    onChanged: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(NukePanelBright)
            .border(0.8.dp, NukeHairline, ControlShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = NukeGreen, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("MINIMUM WIDTH / DPI (DISPLAY SCALE)", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
                }
                Box(
                    Modifier
                        .clip(ControlShape)
                        .background(NukeGreen.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("$currentDpi DP", color = NukeGreen, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(
                    360 to "360\nLarge",
                    411 to "411\nNormal",
                    480 to "480\nCompact",
                    540 to "540\nSmooth",
                    600 to "600\nUltra"
                ).forEach { (dpi, label) ->
                    val isSelected = (currentDpi - dpi).let { if (it < 0) -it else it } < 25
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ControlShape)
                            .background(if (isSelected) NukeGreen.copy(alpha = 0.22f) else Color(0xFF0C1411))
                            .border(0.6.dp, if (isSelected) NukeGreen else NukeHairline, ControlShape)
                            .clickable { onChanged(dpi) }
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) NukeGreen else NukeMuted,
                            fontSize = 6.5.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 8.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ── Professional Pill Toggle Card ──────────────────────
@Composable
private fun PillToggleCard(
    icon: ImageVector,
    title: String,
    activeLabel: String,
    inactiveLabel: String,
    tint: Color,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supported: Boolean = true,
) {
    val isActive = checked && supported
    val bgColor by animateColorAsState(
        if (isActive) tint.copy(alpha = 0.15f) else Color(0xFF0A1410),
        animationSpec = tween(100), label = "pillBg",
    )
    val borderColor by animateColorAsState(
        if (isActive) tint.copy(alpha = 0.8f) else Color(0xFF1A2820),
        animationSpec = tween(100), label = "pillBorder",
    )
    Box(
        modifier = modifier.nukeCardEntrance().nukePressFeedback()
            .alpha(if (supported) 1f else 0.35f)
            .clip(ControlShape)
            .background(bgColor)
            .border(1.dp, borderColor, ControlShape)
            .clickable(enabled = supported, onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(if (isActive) tint.copy(alpha = 0.22f) else Color(0xFF111D17))
                    .border(1.dp, if (isActive) tint.copy(alpha = 0.9f) else Color(0xFF1F2E27), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null,
                    tint = if (!supported) NukeMuted else if (isActive) tint else Color(0xFF3D5248),
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                title,
                color = if (!supported) NukeMuted else if (isActive) Color.White else Color(0xFF4A6357),
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) tint.copy(alpha = 0.20f) else Color(0xFF0D1A14))
                    .border(0.5.dp, if (isActive) tint.copy(alpha = 0.5f) else Color(0xFF1A2820), RoundedCornerShape(8.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (!supported) "N/A" else if (isActive) activeLabel else inactiveLabel,
                    color = if (!supported) NukeMuted else if (isActive) tint else Color(0xFF2E4038),
                    fontSize = 5.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

// ── Top pill action button ──────────────────────────────────────────────────
@Composable
private fun TopActionPill(
    icon: ImageVector,
    label: String,
    badge: String?,
    tint: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) tint.copy(alpha = 0.18f) else tint.copy(alpha = 0.08f), tween(120), "topBg")
    val border by animateColorAsState(if (active) tint.copy(alpha = 0.7f) else tint.copy(alpha = 0.35f), tween(120), "topBorder")
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(ControlShape)
            .background(bg)
            .border(0.8.dp, border, ControlShape)
            .nukePressFeedback().clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (active) CircularProgressIndicator(Modifier.size(8.dp), color = tint, strokeWidth = 1.2.dp)
            else Icon(icon, null, tint = tint, modifier = Modifier.size(9.dp))
            Text(label, color = Color.White, fontSize = 6.8.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
            if (badge != null) {
                Box(Modifier.clip(ControlShape).background(tint.copy(alpha = 0.22f)).padding(horizontal = 2.5.dp, vertical = 0.5.dp)) {
                    Text(badge, color = tint, fontSize = 5.2.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Section divider ───────────────────────────────────────────────────────────
@Composable
private fun SectionDivider(title: String, accent: Color = NukeGreen) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(3.dp, 8.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent.copy(alpha = 0.85f))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            title,
            color = Color(0xFF9BB0A6),
            fontSize = 6.4.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(5.dp))
        Box(Modifier.weight(1f).height(0.6.dp).background(Color(0xFF1E2B38)))
    }
}


@Composable
private fun ModuleShopFullView(
    state: NukeModuleShopState,
    callbacks: FloatingHudCallbacks,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Request window IME focus once on enter, release on exit
    DisposableEffect(Unit) {
        callbacks.onSearchFocusChanged(true)
        onDispose {
            callbacks.onSearchFocusChanged(false)
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        runCatching { focusRequester.requestFocus() }
        kotlinx.coroutines.delay(150)
        keyboard?.show()
    }

    val categories = listOf("ALL", "PERFORMANCE", "GRAPHICS", "TOUCH", "NETWORK", "AUDIO", "BATTERY")

    val filtered = remember(state.modules, query, selectedCategory) {
        val needle = query.trim().lowercase(Locale.getDefault())
        state.modules.filter { module ->
            val matchCat = if (selectedCategory == "ALL") true else module.category.equals(selectedCategory, ignoreCase = true) || module.name.contains(selectedCategory, ignoreCase = true)
            val matchQuery = if (needle.isBlank()) true else {
                module.name.lowercase(Locale.getDefault()).contains(needle) ||
                    module.category.lowercase(Locale.getDefault()).contains(needle) ||
                    module.description.lowercase(Locale.getDefault()).contains(needle)
            }
            matchCat && matchQuery
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ── Header with Back Button ─────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(42.dp)
                .clip(ControlShape)
                .background(NukePanelBright)
                .border(.8.dp, NukeCyan.copy(alpha = .30f), ControlShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderAction(Icons.Outlined.ArrowBack, "Back to deck", NukeCyan, false, true) {
                keyboard?.hide()
                focusManager.clearFocus()
                callbacks.onSearchFocusChanged(false)
                onBack()
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("PLUGIN LIBRARY", color = NukeCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(
                    if (state.catalogTrusted) "${state.modules.size} PLUGINS AVAILABLE" else "CATALOG READY",
                    color = if (state.catalogTrusted) NukeGreen else NukeAmber,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeaderAction(Icons.Outlined.Refresh, "Refresh catalog", NukeCyan, false, true, callbacks.onModuleRefresh)
        }
        Spacer(Modifier.height(4.dp))

        // ── Fixed High-Contrast Search Bar ────────────────────────────────────
        var isSearchFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(ControlShape)
                .background(Color(0xFF0A1613))
                .border(
                    1.dp,
                    if (isSearchFocused) NukeCyan else NukeCyan.copy(alpha = 0.50f),
                    ControlShape
                )
                .clickable {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = if (isSearchFocused) NukeCyan else NukeMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search plugins (e.g. GPU, Touch)...",
                            fontSize = 10.sp,
                            color = NukeMuted,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                isSearchFocused = focus.isFocused
                            },
                        singleLine = true,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(NukeCyan),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(NukeCyan.copy(alpha = 0.25f))
                            .clickable {
                                query = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Clear search",
                            tint = NukeCyan,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // ── Category Filter Chips (Scrollable Horizontal) ─────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            categories.take(4).forEach { cat ->
                val selected = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ControlShape)
                        .background(if (selected) NukeCyan.copy(alpha = 0.25f) else NukePanelBright)
                        .border(0.7.dp, if (selected) NukeCyan else NukeHairline, ControlShape)
                        .clickable { selectedCategory = cat }
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cat,
                        color = if (selected) NukeCyan else NukeMuted,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (state.loading && state.modules.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = NukeCyan, strokeWidth = 2.dp)
                    Spacer(Modifier.height(6.dp))
                    Text("LOADING CATALOG", color = NukeMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.id }) { module ->
                    ModuleShopCard(module, state, callbacks)
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "NO PLUGINS IN CATEGORY: $selectedCategory" else "NO PLUGINS MATCH: \"${query.take(20)}\"",
                            color = NukeMuted,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
        StatusRail(state.message, true)
    }
}

@Composable
private fun ModuleShopCard(
    module: NukeShopModule,
    state: NukeModuleShopState,
    callbacks: FloatingHudCallbacks,
) {
    val installed = module.id in state.installedIds
    val active = module.id in state.activeIds
    val busy = module.id in state.busyIds
    val blocked = state.blockedReasons[module.id]
    val accentColor = when {
        active -> NukeGreen
        installed -> NukeCyan
        else -> NukeHairline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(if (active) NukeGreen.copy(alpha = .08f) else NukePanelBright)
            .border(.8.dp, accentColor.copy(alpha = if (active) .55f else .25f), ControlShape),
    ) {
        // Left accent bar — gaming style colored indicator
        Box(
            Modifier
                .width(3.dp)
                .height(72.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(accentColor.copy(alpha = 0f), accentColor, accentColor.copy(alpha = 0f))
                    )
                )
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
        ) {
            // Name + category badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    module.name,
                    color = if (active) NukeGreen else NukeText,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(ControlShape)
                        .background(NukeCyan.copy(alpha = .14f))
                        .border(.6.dp, NukeCyan.copy(alpha = .38f), ControlShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(module.category.uppercase(), color = NukeCyan, fontSize = 6.sp, fontWeight = FontWeight.Black)
                }
            }
            // Description
            if (module.description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    module.description,
                    color = NukeMuted,
                    fontSize = 7.5.sp,
                    lineHeight = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Status line
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        blocked != null -> "⚠ ${blocked.take(40)}"
                        active -> "● ACTIVE  v${module.version}"
                        installed -> "✓ INSTALLED  v${module.version}"
                        else -> "v${module.version}${if (module.support.isNotBlank()) "  •  ${module.support.take(20)}" else ""}"
                    },
                    color = when {
                        blocked != null -> NukeRed
                        active -> NukeGreen
                        installed -> NukeCyan
                        else -> NukeMuted
                    },
                    fontSize = 6.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                when {
                    busy -> CircularProgressIndicator(
                        Modifier.size(20.dp), color = NukeCyan, strokeWidth = 2.dp
                    )
                    !installed -> Box(
                        Modifier
                            .clip(ControlShape)
                            .background(NukeCyan.copy(alpha = .18f))
                            .border(.8.dp, NukeCyan.copy(alpha = .55f), ControlShape)
                            .clickable { callbacks.onModuleInstall(module.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (blocked == null) "INSTALL" else "RETRY",
                            color = NukeCyan, fontSize = 8.sp, fontWeight = FontWeight.Black
                        )
                    }
                    else -> Switch(
                        checked = active,
                        onCheckedChange = { callbacks.onModuleToggle(module.id, it) },
                        enabled = !busy && blocked == null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NukeVoid,
                            checkedTrackColor = NukeGreen,
                            uncheckedThumbColor = NukeMuted,
                            uncheckedTrackColor = NukePanelBright,
                            uncheckedBorderColor = NukeHairline,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactEndSessionGate(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(BayShape).background(NukeRed.copy(alpha = .11f))
            .border(.8.dp, NukeRed.copy(alpha = .55f), BayShape).padding(10.dp),
    ) {
        Text("END GAME NUKE SESSION?", color = NukeRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .55.sp)
        Text("Active plugins are restored in reverse activation order before the overlay ends.", color = NukeMuted, fontSize = 7.5.sp, lineHeight = 9.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(Modifier.weight(1f).height(48.dp).nukePressFeedback().clickable(onClick = onCancel), color = NukePanelHigh, border = BorderStroke(.7.dp, NukeHairline), shape = ControlShape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("CANCEL", color = NukeText, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
            }
            Surface(Modifier.weight(1f).height(48.dp).nukePressFeedback().clickable(onClick = onConfirm), color = NukeRed.copy(alpha = .17f), border = BorderStroke(.8.dp, NukeRed.copy(alpha = .70f)), shape = ControlShape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("END NOW", color = NukeRed, fontSize = 8.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun NukeFloatingHud(snapshot: FloatingHudSnapshot, callbacks: FloatingHudCallbacks) {
    MaterialTheme(colorScheme = NukeColorScheme, typography = com.neon.gametweak.ui.theme.Typography) {
        var entered by remember { mutableStateOf(false) }
        var confirmEnd by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val curtainProgress by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(snapshot.remoteDefinition.panelOpenMs),
            label = "quantum-curtain",
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Game Nuke adaptive split-wing gaming cockpit" },
        ) {
            // Split curtains need a medium-width viewport. Narrow landscape windows deliberately
            // use the stacked trapezoid fallback instead of squeezing or covering content.
            val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
            val dense = maxHeight < snapshot.remoteDefinition.shortHeightDp.dp ||
                maxWidth < snapshot.remoteDefinition.mediumMaxWidthDp.dp
            val tiny = maxHeight < 285.dp
            val padding = if (dense) 9.dp else 12.dp

            QuantumBackdrop(snapshot.coreHealth)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = padding, vertical = if (dense) 7.dp else 9.dp),
            ) {
                CommandHeader(snapshot, callbacks, dense) { confirmEnd = true }
                if (confirmEnd) {
                    EndSessionGate(
                        dense = dense,
                        onCancel = { confirmEnd = false },
                        onConfirm = callbacks.onEndSession,
                    )
                } else {
                    TelemetryRail(snapshot, dense)
                }
                Spacer(Modifier.height(if (dense) 5.dp else 7.dp))

                if (landscape) {
                    LandscapeCommandBody(snapshot, callbacks, dense, tiny, curtainProgress, Modifier.weight(1f))
                } else {
                    PortraitCommandBody(snapshot, callbacks, dense, curtainProgress, Modifier.weight(1f))
                }
                StatusRail(snapshot.statusMessage, dense)
            }
        }
    }
}

@Composable
private fun QuantumBackdrop(health: FloatingCoreHealth) {
    val accent by animateColorAsState(
        targetValue = when (health) {
            FloatingCoreHealth.ONLINE -> NukeGreen
            FloatingCoreHealth.DEGRADED -> NukeAmber
            FloatingCoreHealth.OFFLINE -> NukeRed
        },
        animationSpec = tween(260),
        label = "chassis-accent",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(accent.copy(alpha = .09f), Color.Transparent),
                center = center,
                radius = size.minDimension * .78f,
            ),
        )
        repeat(7) { index ->
            val x = size.width * (.10f + index * .135f)
            val y = size.height * (if (index % 2 == 0) .18f else .82f)
            drawCircle(accent.copy(alpha = .24f), radius = 1.2.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun CommandHeader(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    onRequestEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (dense) 32.dp else 34.dp)
            .pointerInput(callbacks) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    callbacks.onDrag(dragAmount.x, dragAmount.y)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (dense) 22.dp else 24.dp)
                .clip(BayShape)
                .background(NukeGreen.copy(alpha = .13f))
                .border(.8.dp, NukeGreen.copy(alpha = .55f), BayShape),
            contentAlignment = Alignment.Center,
        ) {
            ParticleGlyph(if (dense) 15.dp else 17.dp, NukeGreen)
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "GAME NUKE  /  ${snapshot.gameLabel.uppercase()}",
                color = NukeText,
                fontSize = if (dense) 8.5.sp else 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = .65.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthDot(snapshot.coreHealth)
                Spacer(Modifier.width(4.dp))
                Text(
                    snapshot.phaseLabel,
                    color = NukeMuted,
                    fontSize = if (dense) 7.sp else 7.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        HeaderAction(Icons.Outlined.Close, "Close panel to edge bubble", NukeCyan, false, dense, callbacks.onMinimize)
        if (snapshot.remoteDefinition.showEndSession) {
            Spacer(Modifier.width(4.dp))
            HeaderAction(Icons.Outlined.PowerSettingsNew, "End floating session", NukeRed, true, dense, onRequestEnd)
        }
    }
}

@Composable
private fun EndSessionGate(dense: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (dense) 52.dp else 56.dp)
            .clip(BottomTrapezoidShape)
            .background(NukeRed.copy(alpha = .12f))
            .border(.8.dp, NukeRed.copy(alpha = .55f), BottomTrapezoidShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("END SESSION?", color = NukeRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.height(48.dp).widthIn(min = 70.dp).nukePressFeedback().clickable(onClick = onCancel),
            shape = ControlShape,
            color = NukePanelHigh,
            border = BorderStroke(.7.dp, NukeHairline),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("CANCEL", color = NukeText, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.width(6.dp))
        Surface(
            modifier = Modifier.height(48.dp).widthIn(min = 86.dp).nukePressFeedback().clickable(onClick = onConfirm),
            shape = ControlShape,
            color = NukeRed.copy(alpha = .18f),
            border = BorderStroke(.8.dp, NukeRed.copy(alpha = .72f)),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("END NOW", color = NukeRed, fontSize = 8.sp, fontWeight = FontWeight.Black) } }
    }
}

@Composable
private fun ParticleGlyph(diameter: Dp, accent: Color) {
    Canvas(Modifier.size(diameter)) {
        val orbitTop = Offset(size.width * .13f, size.height * .35f)
        val orbitSize = Size(size.width * .74f, size.height * .30f)
        listOf(0f, 60f, 120f).forEach { angle ->
            rotate(angle, pivot = center) {
                drawOval(accent.copy(alpha = .72f), orbitTop, orbitSize, style = Stroke(1.dp.toPx()))
            }
        }
        drawCircle(accent.copy(alpha = .20f), radius = size.minDimension * .18f)
        drawCircle(accent, radius = size.minDimension * .09f)
        drawCircle(accent, radius = size.minDimension * .055f, center = Offset(size.width * .84f, size.height * .5f))
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    danger: Boolean,
    dense: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(48.dp).clip(ControlShape).nukePressFeedback().clickable(onClick = onClick),
        color = if (danger) NukeRed.copy(alpha = .10f) else NukePanelHigh,
        border = BorderStroke(.8.dp, tint.copy(alpha = if (danger) .58f else .32f)),
        shape = ControlShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(if (dense) 19.dp else 21.dp))
        }
    }
}

@Composable
private fun HealthDot(health: FloatingCoreHealth) {
    val color = when (health) {
        FloatingCoreHealth.ONLINE -> NukeGreen
        FloatingCoreHealth.DEGRADED -> NukeAmber
        FloatingCoreHealth.OFFLINE -> NukeRed
    }
    Canvas(Modifier.size(7.dp)) {
        drawCircle(color.copy(alpha = .18f), radius = size.minDimension * .5f)
        drawCircle(color, radius = size.minDimension * .27f)
    }
}

@Composable
private fun TelemetryRail(snapshot: FloatingHudSnapshot, dense: Boolean) {
    val metrics = listOf(
        "FPS" to snapshot.fps,
        "PING" to snapshot.ping,
        "TEMP" to snapshot.temperature,
        "BAT" to snapshot.battery,
        "STORAGE" to snapshot.storage,
        "LINK" to snapshot.network,
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(if (dense) 33.dp else 37.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        metrics.forEachIndexed { index, item ->
            val metricAccent = when (item.first) {
                "FPS" -> NukeGreen
                "PING" -> {
                    val pingMs = item.second.filter(Char::isDigit).toIntOrNull()
                    when {
                        pingMs == null -> NukeMuted
                        pingMs < 50 -> NukeGreen
                        pingMs < 100 -> NukeAmber
                        pingMs > 150 -> NukeRed
                        else -> NukeAmber
                    }
                }
                else -> NukeMuted
            }
            val highlighted = item.first == "FPS" || item.first == "PING"
            Box(
                modifier = Modifier
                    .widthIn(min = if (dense) 69.dp else 76.dp)
                    .fillMaxHeight()
                    .clip(ControlShape)
                    .background(if (highlighted) metricAccent.copy(alpha = .10f) else NukePanel.copy(alpha = .82f))
                    .border(.7.dp, if (highlighted) metricAccent.copy(alpha = .40f) else NukeHairline, ControlShape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.first, color = if (highlighted) metricAccent else NukeMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(item.second, color = if (item.first == "PING") metricAccent else NukeText, fontSize = if (dense) 9.sp else 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LandscapeCommandBody(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    tiny: Boolean,
    curtainProgress: Float,
    modifier: Modifier,
) {
    val tools = toolsFor(snapshot.remoteDefinition).filter { it.tool in snapshot.supportedTools }
    val splitAt = (tools.size + 1) / 2
    Column(modifier) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(if (dense) 5.dp else 7.dp)) {
            WingToolDeck(
                title = "LEFT WING",
                tools = tools.take(splitAt),
                shape = LeftWingShape,
                dense = dense,
                modifier = Modifier
                    .weight(.34f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = -(1f - curtainProgress) * 78.dp.toPx()
                        alpha = .25f + curtainProgress * .75f
                    },
                onTool = callbacks.onTool,
            )
            ReactorBay(
                snapshot,
                callbacks,
                dense,
                !tiny,
                Modifier
                    .weight(.32f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = .88f + curtainProgress * .12f
                        scaleY = .88f + curtainProgress * .12f
                        alpha = .35f + curtainProgress * .65f
                    },
                BayShape,
            )
            WingToolDeck(
                title = "RIGHT WING",
                tools = tools.drop(splitAt),
                shape = RightWingShape,
                dense = dense,
                modifier = Modifier
                    .weight(.34f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = (1f - curtainProgress) * 78.dp.toPx()
                        alpha = .25f + curtainProgress * .75f
                    },
                onTool = callbacks.onTool,
            )
        }
        if (!tiny) {
            Spacer(Modifier.height(6.dp))
            CompactControlDeck(snapshot, callbacks, Modifier.height(if (dense) 48.dp else 52.dp))
        }
    }
}

@Composable
private fun WingToolDeck(
    title: String,
    tools: List<ToolVisual>,
    shape: Shape,
    dense: Boolean,
    modifier: Modifier,
    onTool: (FloatingHudTool) -> Unit,
) {
    Surface(
        modifier = modifier.clip(shape),
        color = NukePanel.copy(alpha = .90f),
        border = BorderStroke(.85.dp, NukeCyan.copy(alpha = .34f)),
        shape = shape,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (dense) 7.dp else 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null, tint = NukeCyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(title, color = NukeCyan, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
                Spacer(Modifier.weight(1f))
                Text("${tools.size}", color = NukeGreen, fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
            tools.forEach { visual ->
                MissionCard(
                    visual = visual,
                    supported = true,
                    dense = true,
                    modifier = Modifier.fillMaxWidth().height(if (dense) 48.dp else 54.dp),
                ) { onTool(visual.tool) }
            }
            if (tools.isEmpty()) {
                Text("NO VERIFIED PLUGIN", color = NukeMuted, fontSize = 8.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun PortraitCommandBody(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    curtainProgress: Float,
    modifier: Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReactorBay(
            snapshot,
            callbacks,
            dense,
            true,
            Modifier.fillMaxWidth().height(if (dense) 118.dp else 132.dp).graphicsLayer {
                translationY = -(1f - curtainProgress) * 56.dp.toPx()
                alpha = .30f + curtainProgress * .70f
            },
            TopTrapezoidShape,
        )
        CommandDeck(
            snapshot,
            callbacks,
            dense,
            Modifier.fillMaxWidth().height(if (dense) 300.dp else 330.dp).graphicsLayer {
                translationY = (1f - curtainProgress) * 56.dp.toPx()
                alpha = .30f + curtainProgress * .70f
            },
            BottomTrapezoidShape,
        )
        ControlDeck(snapshot, callbacks, dense, Modifier.fillMaxWidth().height(if (dense) 170.dp else 182.dp))
    }
}

@Composable
private fun ReactorBay(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    showDetail: Boolean,
    modifier: Modifier,
    shape: Shape = BayShape,
) {
    Surface(
        modifier = modifier.clip(shape).nukePressFeedback().clickable(onClick = callbacks.onCoreClick),
        color = Color.Transparent,
        shape = shape,
        border = BorderStroke(.8.dp, NukeGreen.copy(alpha = .28f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glow = Brush.radialGradient(listOf(NukeGreen.copy(alpha = .16f), Color.Transparent), center = Offset(size.width * .5f, size.height * .47f), radius = size.minDimension * .68f)
                    onDrawBehind {
                        drawRect(NukePanel.copy(alpha = .86f))
                        drawRect(glow)
                        drawLine(NukeCyan.copy(alpha = .48f), Offset(size.width * .16f, 0f), Offset(size.width * .69f, 0f), 1.2.dp.toPx())
                        drawLine(NukeGreen.copy(alpha = .34f), Offset(size.width * .72f, size.height), Offset(size.width * .92f, size.height), 1.2.dp.toPx())
                    }
                }
                .padding(if (dense) 8.dp else 11.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val narrow = maxWidth < 215.dp
                val gaugeSize = if (narrow) 56.dp else if (dense) 70.dp else 82.dp
                val coreSize = if (narrow) 34.dp else if (dense) 44.dp else 52.dp
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    SpeedometerGauge("CPU", snapshot.cpuPercent, gaugeSize, NukeGreen)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        ReactorGlyph(snapshot.coreHealth, coreSize)
                        if (showDetail && !narrow) {
                            Text("NUKE CORE", color = NukeText, fontSize = if (dense) 9.sp else 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(snapshot.ramDetail, color = NukeMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    SpeedometerGauge("RAM", snapshot.ramPercent, gaugeSize, NukeCyan)
                }
            }
        }
    }
}

@Composable
private fun ReactorGlyph(health: FloatingCoreHealth, diameter: Dp) {
    val accent = when (health) {
        FloatingCoreHealth.ONLINE -> NukeGreen
        FloatingCoreHealth.DEGRADED -> NukeAmber
        FloatingCoreHealth.OFFLINE -> NukeRed
    }
    Canvas(Modifier.size(diameter)) {
        drawCircle(accent.copy(alpha = .10f), radius = size.minDimension * .43f)
        val orbitTop = Offset(size.width * .20f, size.height * .37f)
        val orbitSize = Size(size.width * .60f, size.height * .26f)
        listOf(0f, 60f, 120f).forEach { angle ->
            rotate(angle, pivot = center) {
                drawOval(accent.copy(alpha = .84f), orbitTop, orbitSize, style = Stroke(1.dp.toPx()))
            }
        }
        drawCircle(accent.copy(alpha = .22f), radius = size.minDimension * .15f)
        drawCircle(accent, radius = size.minDimension * .075f)
        drawCircle(accent, radius = size.minDimension * .035f, center = Offset(size.width * .80f, size.height * .50f))
    }
}

@Composable
private fun SpeedometerGauge(label: String, value: Int?, diameter: Dp, accent: Color) {
    val progress by animateFloatAsState(targetValue = (value ?: 0).coerceIn(0, 100) / 100f, animationSpec = tween(280), label = "$label-gauge")
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke * 1.4f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val start = 150f
            val sweep = 240f
            drawArc(NukeMuted.copy(alpha = .14f), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            if (value != null) drawArc(accent, start, sweep * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val center = Offset(size.width * .5f, size.height * .5f)
            val radius = arcSize.minDimension * .5f
            repeat(11) { index ->
                val degrees = start + sweep * index / 10f
                val radians = Math.toRadians(degrees.toDouble())
                val outer = Offset(
                    center.x + kotlin.math.cos(radians).toFloat() * radius,
                    center.y + kotlin.math.sin(radians).toFloat() * radius,
                )
                val innerRadius = radius - if (index % 5 == 0) 6.dp.toPx() else 3.5.dp.toPx()
                val inner = Offset(
                    center.x + kotlin.math.cos(radians).toFloat() * innerRadius,
                    center.y + kotlin.math.sin(radians).toFloat() * innerRadius,
                )
                drawLine(if (index <= progress * 10f) accent else NukeMuted.copy(alpha = .32f), inner, outer, if (index % 5 == 0) 1.2.dp.toPx() else .7.dp.toPx())
            }
            if (value != null) {
                val needleRadians = Math.toRadians((start + sweep * progress).toDouble())
                val needleEnd = Offset(
                    center.x + kotlin.math.cos(needleRadians).toFloat() * radius * .64f,
                    center.y + kotlin.math.sin(needleRadians).toFloat() * radius * .64f,
                )
                drawLine(accent, center, needleEnd, 1.7.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(NukeVoid, 3.2.dp.toPx(), center)
                drawCircle(accent, 2.dp.toPx(), center)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(diameter * .10f))
            Text(value?.let { "$it%" } ?: "--", color = NukeText, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(label, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            GradientFuelBar(value, accent, Modifier.width(diameter * .54f).height(4.dp))
        }
    }
}

@Composable
private fun GradientFuelBar(value: Int?, accent: Color, modifier: Modifier) {
    val progress by animateFloatAsState(
        targetValue = (value ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(280),
        label = "fuel-bar",
    )
    Canvas(modifier) {
        val segments = 8
        val gap = 1.1.dp.toPx()
        val width = (size.width - gap * (segments - 1)) / segments
        val active = if (value == null) 0 else (progress * segments + .999f).toInt().coerceIn(0, segments)
        repeat(segments) { index ->
            val color = if (index < active) {
                lerp(NukeCyan, accent, index / (segments - 1f))
            } else {
                NukeMuted.copy(alpha = .16f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (width + gap), 0f),
                size = Size(width, size.height),
            )
        }
    }
}

@Composable
private fun CommandDeck(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    modifier: Modifier,
    shape: Shape = BayShape,
) {
    val tools = toolsFor(snapshot.remoteDefinition).filter { it.tool in snapshot.supportedTools }
    Surface(modifier = modifier.clip(shape), color = NukePanel.copy(alpha = .78f), border = BorderStroke(.8.dp, NukeCyan.copy(alpha = .22f)), shape = shape) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (dense) 7.dp else 9.dp)) {
            val compactRail = maxHeight < 155.dp
            val columns = when {
                maxWidth <= snapshot.remoteDefinition.compactMaxWidthDp.dp -> snapshot.remoteDefinition.moduleColumnsCompact
                maxWidth <= snapshot.remoteDefinition.mediumMaxWidthDp.dp -> snapshot.remoteDefinition.moduleColumnsMedium
                else -> snapshot.remoteDefinition.moduleColumnsExpanded
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.height(25.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = NukeCyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("MISSION PLUGINS", color = NukeCyan, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .8.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (compactRail) "${tools.size} TOOLS  /  SWIPE" else "ALL ${tools.size} VISIBLE",
                        color = NukeGreen,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = .45.sp,
                    )
                }
                Spacer(Modifier.height(5.dp))
                if (compactRail) {
                    Row(
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        tools.forEach { visual ->
                            MissionCard(
                                visual,
                                visual.tool in snapshot.supportedTools,
                                true,
                                Modifier.width(112.dp).fillMaxHeight(),
                            ) { callbacks.onTool(visual.tool) }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        tools.chunked(columns).forEach { rowTools ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                rowTools.forEach { visual ->
                                    MissionCard(
                                        visual,
                                        visual.tool in snapshot.supportedTools,
                                        dense,
                                        Modifier.weight(1f).fillMaxHeight(),
                                    ) { callbacks.onTool(visual.tool) }
                                }
                                repeat(columns - rowTools.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionCard(
    visual: ToolVisual,
    supported: Boolean,
    dense: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.clip(ControlShape).alpha(if (supported) 1f else .40f)
            .nukePressFeedback().clickable(enabled = supported, interactionSource = interaction, indication = null, onClick = onClick),
        color = NukePanelHigh.copy(alpha = .80f),
        border = BorderStroke(.75.dp, NukeHairline),
        shape = ControlShape,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val lowProfile = maxHeight < 68.dp
            if (lowProfile) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(25.dp).clip(BayShape).background(NukeCyan.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                        Icon(visual.icon, null, tint = if (supported) NukeCyan else NukeMuted, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(visual.title, color = NukeText, fontSize = 8.5.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(visual.subtitle, color = NukeMuted, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = if (supported) NukeGreen else NukeMuted, fontSize = 15.sp, fontWeight = FontWeight.Light)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = if (dense) 7.dp else 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (dense) 28.dp else 32.dp).clip(BayShape).background(NukeCyan.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                            Icon(visual.icon, null, tint = if (supported) NukeCyan else NukeMuted, modifier = Modifier.size(if (dense) 17.dp else 19.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("›", color = if (supported) NukeGreen else NukeMuted, fontSize = 18.sp, fontWeight = FontWeight.Light)
                    }
                    Column {
                        Text(visual.title, color = NukeText, fontSize = if (dense) 9.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(visual.subtitle, color = NukeMuted, fontSize = 7.5.sp, lineHeight = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlDeck(snapshot: FloatingHudSnapshot, callbacks: FloatingHudCallbacks, dense: Boolean, modifier: Modifier) {
    val controls = quickControlsFor(snapshot.remoteDefinition).filter { it.toggle in snapshot.supportedToggles }
    val split = (controls.size + 1) / 2
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ControlGroup("SESSION LAYER", Icons.Outlined.Bolt, controls.take(split), snapshot, callbacks, dense, Modifier.weight(1f))
        ControlGroup("PLAYER LAYER", Icons.Outlined.Security, controls.drop(split), snapshot, callbacks, dense, Modifier.weight(1f))
    }
}

@Composable
private fun CompactControlDeck(
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    modifier: Modifier,
) {
    val controls = quickControlsFor(snapshot.remoteDefinition).filter { it.toggle in snapshot.supportedToggles }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        controls.forEach { item ->
            val interaction = remember(item.toggle) { MutableInteractionSource() }
            val supported = item.toggle in snapshot.supportedToggles
            val checked = snapshot.toggleValues[item.toggle] == true
            val busy = item.toggle in snapshot.busyToggles
            Row(
                modifier = Modifier
                    .width(108.dp)
                    .fillMaxHeight()
                    .clip(ControlShape)
                    .background(if (checked) NukeGreen.copy(alpha = .11f) else NukePanel.copy(alpha = .84f))
                    .border(.75.dp, if (checked) NukeGreen.copy(alpha = .44f) else NukeHairline, ControlShape)
                    .alpha(if (supported) 1f else .42f)
                    .toggleable(
                        value = checked,
                        interactionSource = interaction,
                        indication = null,
                        enabled = supported && !busy,
                        role = Role.Switch,
                        onValueChange = { callbacks.onToggle(item.toggle, it) },
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, tint = if (checked) NukeGreen else NukeMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.title, modifier = Modifier.weight(1f), color = if (checked) NukeGreen else NukeText, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = NukeCyan, strokeWidth = 2.dp)
                else Canvas(Modifier.size(10.dp)) {
                    drawCircle(if (checked) NukeGreen else NukeMuted.copy(alpha = .45f), radius = size.minDimension * .32f)
                    if (checked) drawCircle(NukeGreen.copy(alpha = .24f), radius = size.minDimension * .49f, style = Stroke(.7.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ControlGroup(
    title: String,
    icon: ImageVector,
    controls: List<ToggleVisual>,
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    modifier: Modifier,
) {
    Surface(modifier.clip(BayShape), color = NukePanel.copy(alpha = .78f), border = BorderStroke(.75.dp, NukeGreen.copy(alpha = .18f)), shape = BayShape) {
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp)) {
            Row(Modifier.height(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = NukeGreen, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(title, color = NukeGreen, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .55.sp)
            }
            controls.forEach { item -> ToggleControl(item, snapshot, callbacks, dense, Modifier.weight(1f).fillMaxWidth()) }
        }
    }
}

@Composable
private fun ToggleControl(
    item: ToggleVisual,
    snapshot: FloatingHudSnapshot,
    callbacks: FloatingHudCallbacks,
    dense: Boolean,
    modifier: Modifier,
) {
    val interaction = remember(item.toggle) { MutableInteractionSource() }
    val supported = item.toggle in snapshot.supportedToggles
    val checked = snapshot.toggleValues[item.toggle] == true
    val busy = item.toggle in snapshot.busyToggles
    Row(
        modifier = modifier.alpha(if (supported) 1f else .42f)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = supported && !busy,
                role = Role.Switch,
                onValueChange = { callbacks.onToggle(item.toggle, it) },
            )
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, tint = if (checked) NukeGreen else NukeMuted, modifier = Modifier.size(if (dense) 14.dp else 15.dp))
        Spacer(Modifier.width(6.dp))
        Text(item.title, modifier = Modifier.weight(1f), color = if (checked) NukeGreen else NukeText, fontSize = if (dense) 8.5.sp else 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (busy) {
            CircularProgressIndicator(Modifier.size(17.dp), color = NukeCyan, strokeWidth = 2.dp)
        } else {
            Switch(
                checked = checked,
                enabled = supported,
                onCheckedChange = null,
                modifier = Modifier.size(width = 38.dp, height = 23.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NukeVoid,
                    checkedTrackColor = NukeGreen,
                    uncheckedThumbColor = NukeMuted,
                    uncheckedTrackColor = Color(0xFF243237),
                    uncheckedBorderColor = NukeHairline,
                    disabledCheckedTrackColor = NukeGreen.copy(alpha = .25f),
                ),
            )
        }
    }
}

@Composable
private fun StatusRail(message: String, dense: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().height(if (dense) 21.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(15.dp)) {
            drawLine(NukeGreen, Offset(0f, size.height * .5f), Offset(size.width * .72f, size.height * .5f), 1.2.dp.toPx())
            drawCircle(NukeGreen, radius = 1.7.dp.toPx(), center = Offset(size.width * .82f, size.height * .5f))
        }
        Text(text = message, modifier = Modifier.weight(1f), color = NukeMuted, fontSize = if (dense) 8.sp else 8.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
        Text("LIVE", color = NukeGreenDim, fontSize = 7.5.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
    }
}

private fun probeText(snapshot: FloatingHudSnapshot): String = if (snapshot.probeEnabled) snapshot.probeMs?.let { "${it}ms" } ?: "TIMEOUT" else snapshot.ping
private fun probeColor(snapshot: FloatingHudSnapshot): Color = when {
    !snapshot.probeEnabled || snapshot.probeMs == null -> Color(0xFF9BB0A6)
    snapshot.probeMs < 50 -> Color(0xFF35C99B)
    snapshot.probeMs <= 120 -> Color(0xFFFFB830)
    else -> Color(0xFFFF4D6A)
}
