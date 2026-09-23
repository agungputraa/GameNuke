package com.neon.gametweak

/** Shared low-overhead visual tokens for the Game Nuke adaptive AI cockpit. */
object NukeHudPalette {
    val Void get() = NukeAiThemeController.currentPalette.background
    val Panel get() = NukeAiThemeController.currentPalette.panel
    val PanelRaised get() = NukeAiThemeController.currentPalette.panelRaised
    val PanelSoft get() = NukeAiThemeController.currentPalette.panelSoft
    val Cyan get() = NukeAiThemeController.currentPalette.telemetry
    val CyanDim get() = NukeAiThemeController.currentPalette.accentDim
    val Blue get() = NukeAiThemeController.currentPalette.accent
    val Violet get() = NukeAiThemeController.currentPalette.accentBright
    val Green get() = NukeAiThemeController.currentPalette.accent
    val GreenBright get() = NukeAiThemeController.currentPalette.accentBright
    val GreenDim get() = NukeAiThemeController.currentPalette.accentDim
    val GreenMuted get() = NukeAiThemeController.currentPalette.panelSoft
    val Amber get() = NukeAiThemeController.currentPalette.warning
    val Danger get() = NukeAiThemeController.currentPalette.danger
    val Text get() = NukeAiThemeController.currentPalette.text
    val Muted get() = NukeAiThemeController.currentPalette.muted
    val MutedDeep get() = NukeAiThemeController.currentPalette.borderBright
    val Outline get() = NukeAiThemeController.currentPalette.border
    val OutlineBright get() = NukeAiThemeController.currentPalette.borderBright
}
