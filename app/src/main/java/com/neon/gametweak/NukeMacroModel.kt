package com.neon.gametweak

import java.util.UUID

/**
 * NukeMacroModel — Red Corner Macro Mapping Data Architecture (1:1 Clone).
 *
 * Mirrors the Red Corner "Touch Mapping" / Macro system:
 *  1. TouchMappingCoordinatorFeature keeps a per-game (foreground package) profile set
 *     ("sens_profiles_json" + master "use_mapping" switch). Every game layout is an
 *     independent MacroProfile that swaps automatically when the game is foregrounded.
 *  2. Each MacroProfile contains a list of MacroPinConfig — autonomous floating macro
 *     buttons drawn on the game screen. A physical touch inside a pin radius is absorbed
 *     by the kernel touch router and turned into a virtual multi-touch burst / mirror.
 *  3. MacroTriggerMode covers the complete Red Corner action palette:
 *     TAP, REPEAT_TAP (rapid), HOLD, MIRROR (twin-touch with sensitivity+inversion),
 *     SWIPE (drag) and DOUBLE_TAP.
 */

enum class MacroTriggerSource {
    TOUCH_SCREEN,
    VOLUME_UP,
    VOLUME_DOWN;

    fun displayName(): String = when (this) {
        TOUCH_SCREEN -> "Touch Screen"
        VOLUME_UP -> "Volume Up Key"
        VOLUME_DOWN -> "Volume Down Key"
    }

    fun shortTag(): String = when (this) {
        TOUCH_SCREEN -> "TOUCH"
        VOLUME_UP -> "VOL UP"
        VOLUME_DOWN -> "VOL DN"
    }
}

enum class MacroTriggerMode {
    /** Rapid burst of N taps or infinite spam — Gloo Wall / Rapid Fire. */
    REPEAT_TAP,

    /** Drag / swipe vector — Scope Headshot Flick / Recoil Drag / Directional Swipe. */
    SWIPE,

    /** Single instantaneous tap. */
    TAP,

    /** Sustained hold for holdDurationMs. */
    HOLD,

    /** Auto-retry / timer loop — Mobile Legends Auto Retry / AFK Farm. */
    LOOP,

    /** Quick double tap fired at the pin coordinate. */
    DOUBLE_TAP,

    /** Mirror a second finger to target. */
    MIRROR;

    /** Red Corner kernel router mode code. */
    fun daemonCode(): Int = when (this) {
        REPEAT_TAP -> 0
        TAP -> 1
        HOLD -> 2
        MIRROR -> 3
        SWIPE -> 4
        DOUBLE_TAP -> 5
        LOOP -> 0
    }

    /** Short visual tag shown inside the pin reticle. */
    fun reticleTag(): String = when (this) {
        REPEAT_TAP -> "SPAM"
        SWIPE -> "DRAG"
        TAP -> "TAP"
        HOLD -> "HOLD"
        LOOP -> "LOOP"
        DOUBLE_TAP -> "2X"
        MIRROR -> "MIR"
    }

    /** Stable short label used by the profile coordinator UI. */
    fun shortName(): String = when (this) {
        REPEAT_TAP -> "RAPID SPAM"
        SWIPE -> "AUTO DRAG"
        TAP -> "INSTANT TAP"
        HOLD -> "TIMED HOLD"
        LOOP -> "AUTO RETRY"
        DOUBLE_TAP -> "DOUBLE TAP"
        MIRROR -> "MIRROR"
    }
}

/** Runtime run-state of the app-side test-fire engine (NukeMacroEngine). */
sealed class MacroRunState {
    object Idle : MacroRunState()
    data class Triggering(val pinIndex: Int, val mode: MacroTriggerMode, val label: String = "") : MacroRunState()
    data class Stopping(val reason: String = "") : MacroRunState()
    data class Error(val message: String) : MacroRunState()
}

/**
 * MacroPinConfig — one Red Corner macro pin (floating macro button).
 *
 * All ratios are screen-normalised in [0.02, 0.98]. Sensitivities follow the Red Corner
 * magic touch range [0.1x .. 5.0x] (MagicTouchSettingsFeature / ri9.h slider bounds).
 */
data class MacroPinConfig(
    val id: String = UUID.randomUUID().toString(),
    /** 1-based pin index shown inside the reticle (Red Corner pin label). */
    var index: Int = 1,
    /** Optional user label shown in the pin (Red Corner 매크로 라벨). */
    var label: String = "",
    /** When false the pin is skipped by the kernel router but stays visible in editor. */
    var enabled: Boolean = true,
    /** Trigger source: on-screen touch or hardware volume key. */
    var triggerSource: MacroTriggerSource = MacroTriggerSource.TOUCH_SCREEN,
    /** Screen X ratio in [0.0, 1.0]. */
    var xRatio: Float = 0.5f,
    /** Screen Y ratio in [0.0, 1.0]. */
    var yRatio: Float = 0.5f,
    /** Macro behaviour of this pin. */
    var mode: MacroTriggerMode = MacroTriggerMode.REPEAT_TAP,
    /** Delay before the first trigger in ms. */
    var startDelayMs: Long = 0L,
    /** Number of taps in REPEAT_TAP / LOOP mode; 0 means infinite-while-held. */
    var repeatCount: Int = 0,
    /** Interval between taps in ms. */
    var intervalMs: Long = 20L,
    /** Per-tap duration in ms. */
    var tapDurationMs: Long = 12L,
    /** Sustained hold duration in ms. */
    var holdDurationMs: Long = 300L,
    /** Drag duration in ms for SWIPE / AUTO_DRAG mode. */
    var swipeDurationMs: Long = 120L,
    /** Target X ratio for SWIPE / AUTO_DRAG. */
    var targetXRatio: Float = 0.5f,
    /** Target Y ratio for SWIPE / AUTO_DRAG (e.g. upward offset for scope headshot flick). */
    var targetYRatio: Float = 0.35f,
    /** Invert X axis in MIRROR mode. */
    var invertX: Boolean = false,
    /** Invert Y axis in MIRROR mode. */
    var invertY: Boolean = false,
    /** X sensitivity scale in [0.1 .. 5.0]. */
    var sensX: Float = 1.0f,
    /** Y sensitivity scale in [0.1 .. 5.0]. */
    var sensY: Float = 1.0f,
    /** Visual / hit radius in dp. */
    var radiusDp: Float = 36f,
    /** Visual accent colour (soft enterprise slate blue default). */
    var color: Int = 0xFF38BDF8.toInt(),
    /** Red Corner lock — locked pins cannot be repositioned. */
    var isLocked: Boolean = false
) {
    fun copyPin(): MacroPinConfig = MacroPinConfig(
        id = id,
        index = index,
        label = label,
        enabled = enabled,
        triggerSource = triggerSource,
        xRatio = xRatio,
        yRatio = yRatio,
        mode = mode,
        startDelayMs = startDelayMs,
        repeatCount = repeatCount,
        intervalMs = intervalMs,
        tapDurationMs = tapDurationMs,
        holdDurationMs = holdDurationMs,
        swipeDurationMs = swipeDurationMs,
        targetXRatio = targetXRatio,
        targetYRatio = targetYRatio,
        invertX = invertX,
        invertY = invertY,
        sensX = sensX,
        sensY = sensY,
        radiusDp = radiusDp,
        color = color,
        isLocked = isLocked
    )

    /** Scrub any character that could break the daemon CSV contract. */
    fun sanitizedLabel(): String = label
        .replace(';', '-')
        .replace(',', '-')
        .replace('|', '-')
        .replace('\n', ' ')
        .trim()
        .take(24)
}

/**
 * MacroProfile — the Red Corner per-game mapping profile (TouchMappingCoordinatorFeature).
 *
 *  - packageKey == "" → the "ALL GAMES" universal profile used whenever no dedicated
 *    profile exists for the foreground package.
 *  - packageKey == "<package>" → dedicated layout for that game, auto-swapped when the
 *    game is foregrounded (Red Corner foreground package coordinator behaviour).
 *  - useMapping is the Red Corner master "use_mapping" toggle: when false the pins are
 *    not delivered to the kernel router at all.
 */
data class MacroProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "ALL GAMES",
    var packageKey: String = "",
    var useMapping: Boolean = true,
    /** Per-profile X sensitivity (Red Corner sens_profiles_json entry). */
    var sensX: Float = 1.0f,
    /** Per-profile Y sensitivity (Red Corner sens_profiles_json entry). */
    var sensY: Float = 1.0f,
    var pins: MutableList<MacroPinConfig> = mutableListOf()
) {
    fun nextPinIndex(): Int = (pins.maxOfOrNull { it.index } ?: 0) + 1
}