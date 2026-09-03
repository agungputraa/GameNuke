package com.neon.gametweak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Small process-local bridge shared by the main app and the floating cockpit.
 * Persistent preferences remain the source of truth across process death; this flow keeps
 * foreground UI surfaces synchronized without polling while the process is alive.
 */
object NukeRuntimeState {
    data class Snapshot(
        val overlayRunning: Boolean = false,
        val activePackage: String? = null,
        val language: String = "en",
        val crosshairEnabled: Boolean = false,
        val currentHz: Int = 0,
        val maxHz: Int = 0,
        val lastFps: Float? = null,
        val phase: String = "IDLE",
        val message: String = "CORE STANDBY",
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // Process-local guard covering the short splash -> service handoff. Stale legacy-display
    // recovery must not race a session that is in the process of becoming visible.
    private val launchHandoff = AtomicBoolean(false)
    private val externalNavigationCooldownUntil = AtomicLong(0L)
    private val externalNavigationGraceUntil = AtomicLong(0L)

    fun setLaunchHandoffActive(active: Boolean) {
        launchHandoff.set(active)
    }

    fun isLaunchHandoffActive(): Boolean = launchHandoff.get()

    /** Single-flight gate shared by the Service and engine so click spam opens one settings page. */
    fun tryBeginExternalNavigation(cooldownMs: Long = 12_000L): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < externalNavigationGraceUntil.get()) return false
        while (true) {
            val blockedUntil = externalNavigationCooldownUntil.get()
            if (now < blockedUntil) return false
            if (externalNavigationCooldownUntil.compareAndSet(blockedUntil, now + cooldownMs)) return true
        }
    }

    /** Keep automatic game-exit tracking from closing the HUD while its own Settings page is open. */
    fun markExternalNavigationOpened(graceMs: Long = 75_000L) {
        val until = android.os.SystemClock.elapsedRealtime() + graceMs
        externalNavigationGraceUntil.updateAndGet { current -> maxOf(current, until) }
    }

    fun isExternalNavigationGraceActive(): Boolean =
        android.os.SystemClock.elapsedRealtime() < externalNavigationGraceUntil.get()

    fun clearExternalNavigationGrace() {
        externalNavigationGraceUntil.set(0L)
    }

    fun publish(value: Snapshot) {
        _state.value = value
    }

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }
}
