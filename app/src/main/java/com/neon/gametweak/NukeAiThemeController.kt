package com.neon.gametweak

import android.content.Context
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Single lightweight source of truth for the AI Agent performance mode + floating-HUD palette.
 *
 * The controller intentionally stores only primitive colors and a mode enum. It does not retain
 * Views, Contexts, bitmaps or animators, so changing mode has effectively zero persistent RAM cost.
 * Compose floating surfaces collect [state]; classic child overlays read [currentPalette] when they
 * are created. The preference is process-death safe.
 */
object NukeAiThemeController {
    private const val PREFS_NAME = "nuke_ai_agent_prefs"
    private const val KEY_MODE = "key_ai_performance_mode"

    enum class Mode(
        val title: String,
        val shortLabel: String,
        val description: String,
        val behaviorHint: String,
    ) {
        LOW_POWER(
            title = "Low Power",
            shortLabel = "ECO",
            description = "Prioritizes battery life and thermal headroom while keeping gameplay stable.",
            behaviorHint = "Conservative tuning • lower thermal pressure • battery-first decisions",
        ),
        BALANCE(
            title = "Balance",
            shortLabel = "BAL",
            description = "Balances sustained FPS, temperature, memory pressure and power draw.",
            behaviorHint = "Adaptive tuning • sustained frame pacing • balanced thermal target",
        ),
        PERFORMANCE(
            title = "Performance",
            shortLabel = "PERF",
            description = "Prioritizes responsive frame pacing and active-game resources with thermal awareness.",
            behaviorHint = "Aggressive foreground priority • high refresh • latency-first tuning",
        ),
        EXTREME(
            title = "Extreme",
            shortLabel = "XTRM",
            description = "Maximum permitted performance profile with stronger thermal and stability guardrails.",
            behaviorHint = "Peak session tuning • maximum foreground priority • continuous safety checks",
        ),
    }

    data class Palette(
        val mode: Mode,
        val accent: Int,
        val accentBright: Int,
        val accentDim: Int,
        val telemetry: Int,
        val background: Int,
        val panel: Int,
        val panelRaised: Int,
        val panelSoft: Int,
        val border: Int,
        val borderBright: Int,
        val text: Int = Color.rgb(243, 255, 249),
        val muted: Int = Color.rgb(156, 184, 173),
        val danger: Int = Color.rgb(255, 91, 111),
        val warning: Int = Color.rgb(255, 184, 74),
        val fxLabel: String,
    )

    private fun paletteFor(mode: Mode): Palette = when (mode) {
        Mode.LOW_POWER -> Palette(
            mode, 0xFF48C78E.toInt(), 0xFF7EE4B5.toInt(), 0xFF205E41.toInt(),
            0xFF62D2A2.toInt(), 0xFF050B08.toInt(), 0xFF0A1410.toInt(), 0xFF0F1D17.toInt(),
            0xFF142720.toInt(), 0xFF1A382D.toInt(), 0xFF2A5545.toInt(), fxLabel = "ECO SAFE"
        )
        Mode.BALANCE -> Palette(
            mode, 0xFF4A90E2.toInt(), 0xFF80B6F4.toInt(), 0xFF1C4C7E.toInt(),
            0xFF64B5F6.toInt(), 0xFF04090F.toInt(), 0xFF0A131C.toInt(), 0xFF0F1B27.toInt(),
            0xFF142434.toInt(), 0xFF1A344C.toInt(), 0xFF2A4E70.toInt(), fxLabel = "BALANCED"
        )
        Mode.PERFORMANCE -> Palette(
            mode, 0xFFE5A93C.toInt(), 0xFFFFCD70.toInt(), 0xFF7D5514.toInt(),
            0xFFFFB74D.toInt(), 0xFF0B0803.toInt(), 0xFF151007.toInt(), 0xFF1D170B.toInt(),
            0xFF261E0F.toInt(), 0xFF3D3017.toInt(), 0xFF5C4924.toInt(), fxLabel = "TURBO"
        )
        Mode.EXTREME -> Palette(
            mode, 0xFF9D7BE8.toInt(), 0xFFC6AEF8.toInt(), 0xFF503685.toInt(),
            0xFFB39DDB.toInt(), 0xFF0A0610.toInt(), 0xFF130D1F.toInt(), 0xFF1C132B.toInt(),
            0xFF25193A.toInt(), 0xFF3A2859.toInt(), 0xFF583F85.toInt(), fxLabel = "EXTREME"
        )
    }

    data class State(
        val mode: Mode = Mode.BALANCE,
        val palette: Palette = paletteFor(Mode.BALANCE),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val currentMode: Mode get() = _state.value.mode
    val currentPalette: Palette get() = _state.value.palette

    fun accentHex(): String = String.format(Locale.US, "#%06X", currentPalette.accent and 0xFFFFFF)
    fun telemetryHex(): String = String.format(Locale.US, "#%06X", currentPalette.telemetry and 0xFFFFFF)

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.BALANCE.name)
        val mode = runCatching { Mode.valueOf(saved ?: Mode.BALANCE.name) }.getOrDefault(Mode.BALANCE)
        if (_state.value.mode != mode) _state.value = State(mode, paletteFor(mode))
    }

    fun setMode(context: Context, mode: Mode) {
        if (_state.value.mode == mode) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
        _state.value = State(mode, paletteFor(mode))
    }

    fun modeInstruction(mode: Mode = currentMode): String = when (mode) {
        Mode.LOW_POWER -> "Prefer efficiency, stable frame pacing, moderate refresh and thermal headroom. Avoid peak-performance locks unless telemetry shows they are necessary."
        Mode.BALANCE -> "Balance sustained FPS, latency, temperature, RAM pressure and power draw. Apply only telemetry-justified changes."
        Mode.PERFORMANCE -> "Prioritize active-game responsiveness, high refresh and low latency while respecting thermal throttling and stability boundaries."
        Mode.EXTREME -> "Prioritize the strongest safe foreground-game performance available to the existing non-root engine, but back off immediately for critical thermals or instability."
    }
}
