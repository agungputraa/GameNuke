package com.neon.gametweak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * NukeMacroEngine v3 — Reliable Multi-Touch Macro Executor.
 *
 * Design philosophy:
 * - Uses NukeConnectionManager (binder → daemon → shell fallback) for injection.
 *   This is the correct path — the NukeTouchInjector inside NukeTouchService (daemon process)
 *   is the one that handles the actual hardware-level multi-touch stream.
 * - For TEST-FIRE: inject through NukeConnectionManager (same path as ARMED mode).
 * - For GLOBAL HOLD: holdPin() starts a coroutine loop; releasePin() cancels it.
 * - Each loop fires injectTap() in rapid succession — the daemon's NukeTouchInjector
 *   handles the multi-touch from inside the privileged process, so user's real touch
 *   events are NOT affected.
 */
object NukeMacroEngine {

    private const val TAG = "NukeMacroEngine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test-fire jobs (one-shot, from UI) */
    private val testJobs = ConcurrentHashMap<String, Job>()

    /** Global hold loops (continuous, while user finger is held on pin) */
    private val holdJobs = ConcurrentHashMap<String, Job>()

    private val _runState = MutableStateFlow<MacroRunState>(MacroRunState.Idle)
    val runState: StateFlow<MacroRunState> = _runState.asStateFlow()

    val isRunning: Boolean get() = _runState.value !is MacroRunState.Idle

    // ──────────────────────────────────────────────────────────────────────────
    // GLOBAL HOLD API — called by daemon pin detection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Start macro execution for [pin] — called when daemon detects finger DOWN on pin area.
     * Runs until [releasePin] is called (finger UP).
     */
    fun holdPin(pin: MacroPinConfig, screenW: Int, screenH: Int) {
        holdJobs[pin.id]?.cancel()
        _runState.value = MacroRunState.Triggering(pin.index, pin.mode, pin.label)

        val px = (pin.xRatio * screenW).coerceIn(0f, screenW - 1f)
        val py = (pin.yRatio * screenH).coerceIn(0f, screenH - 1f)

        val job = scope.launch {
            if (pin.startDelayMs > 0) delay(pin.startDelayMs.coerceIn(0L, 3_000L))
            try {
                when (pin.mode) {
                    MacroTriggerMode.REPEAT_TAP -> {
                        val interval = pin.intervalMs.coerceIn(5L, 500L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        while (currentCoroutineContext().isActive) {
                            NukeConnectionManager.injectTap(px, py, tapDur)
                            delay(interval)
                        }
                    }
                    MacroTriggerMode.HOLD -> {
                        // Hold down for user-defined duration or until released
                        NukeConnectionManager.injectHold(px, py, pin.holdDurationMs.coerceIn(50L, 30_000L))
                    }
                    MacroTriggerMode.TAP -> {
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 120L))
                    }
                    MacroTriggerMode.DOUBLE_TAP -> {
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L))
                        delay(pin.intervalMs.coerceIn(30L, 300L))
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L))
                    }
                    MacroTriggerMode.SWIPE -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        NukeConnectionManager.injectSwipe(px, py, tx, ty, pin.swipeDurationMs.coerceIn(20L, 2_000L))
                    }
                    MacroTriggerMode.LOOP -> {
                        val interval = pin.intervalMs.coerceIn(50L, 10_000L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        while (currentCoroutineContext().isActive) {
                            NukeConnectionManager.injectTap(px, py, tapDur)
                            delay(interval)
                        }
                    }
                    MacroTriggerMode.MIRROR -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        NukeConnectionManager.injectTap(tx, ty, pin.tapDurationMs.coerceIn(8L, 80L))
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "holdPin error pin#${pin.index}: ${e.message}")
                }
            } finally {
                holdJobs.remove(pin.id)
                updateIdleState()
            }
        }
        holdJobs[pin.id] = job
    }

    /** Stop macro for [pinId] — called when daemon detects finger UP on pin area. */
    fun releasePin(pinId: String) {
        holdJobs[pinId]?.cancel()
        holdJobs.remove(pinId)
        updateIdleState()
    }

    /** Release all holds (e.g. when overlay closes or profile switches). */
    fun releaseAll() {
        holdJobs.values.forEach { it.cancel() }
        holdJobs.clear()
        testJobs.values.forEach { it.cancel() }
        testJobs.clear()
        _runState.value = MacroRunState.Idle
    }

    // ──────────────────────────────────────────────────────────────────────────
    // IN-MEMORY ZERO-LATENCY PIN CACHE (Sub-millisecond Hardware Key Dispatch)
    // ──────────────────────────────────────────────────────────────────────────

    @Volatile
    private var memoryPinsCache: List<MacroPinConfig>? = null

    /**
     * Synchronize active pins into RAM so physical volume keys trigger instantly
     * without hitting SharedPreferences/disk I/O.
     */
    fun syncActivePins(pins: List<MacroPinConfig>) {
        memoryPinsCache = pins.map { it.copyPin() }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST-FIRE / MANUAL TRIGGER API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Trigger all active pins configured for [source] (e.g. Volume Up or Volume Down hardware key).
     */
    fun triggerPinsForSource(context: Context, source: MacroTriggerSource) {
        val matchingPins = memoryPinsCache?.filter { it.enabled && it.triggerSource == source }
            ?: run {
                val profiles = NukeMacroRepository.loadProfiles(context)
                val activeProfileId = NukeMacroRepository.activeProfileId(context)
                val profile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull() ?: return
                if (!profile.useMapping) return
                memoryPinsCache = profile.pins.map { it.copyPin() }
                profile.pins.filter { it.enabled && it.triggerSource == source }
            }
        if (matchingPins.isEmpty()) return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        for (pin in matchingPins) {
            triggerPin(pin, screenW, screenH)
        }
    }

    /**
     * Fire [pin] once for test/preview or key-triggered action.
     * Uses NukeConnectionManager injection path.
     */
    fun triggerPin(pin: MacroPinConfig, screenW: Int, screenH: Int) {
        if (!NukeConnectionManager.isConnected()) {
            Log.w(TAG, "No backend connected — test fire skipped")
            _runState.value = MacroRunState.Error("No backend connected")
            return
        }

        testJobs[pin.id]?.cancel()
        _runState.value = MacroRunState.Triggering(pin.index, pin.mode, pin.label)

        val px = (pin.xRatio * screenW).coerceIn(0f, screenW - 1f)
        val py = (pin.yRatio * screenH).coerceIn(0f, screenH - 1f)

        val job = scope.launch {
            try {
                if (pin.startDelayMs > 0) delay(pin.startDelayMs.coerceIn(0L, 3_000L))
                when (pin.mode) {
                    MacroTriggerMode.REPEAT_TAP -> {
                        val count = if (pin.repeatCount <= 0) 5 else pin.repeatCount.coerceIn(1, 50)
                        val interval = pin.intervalMs.coerceIn(5L, 500L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        repeat(count) { i ->
                            if (!currentCoroutineContext().isActive) return@repeat
                            NukeConnectionManager.injectTap(px, py, tapDur)
                            if (i < count - 1) delay(interval)
                        }
                    }
                    MacroTriggerMode.TAP -> {
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 120L))
                    }
                    MacroTriggerMode.HOLD -> {
                        NukeConnectionManager.injectHold(px, py, pin.holdDurationMs.coerceIn(50L, 3_000L))
                    }
                    MacroTriggerMode.DOUBLE_TAP -> {
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L))
                        delay(pin.intervalMs.coerceIn(30L, 300L))
                        NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L))
                    }
                    MacroTriggerMode.SWIPE -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        NukeConnectionManager.injectSwipe(px, py, tx, ty, pin.swipeDurationMs.coerceIn(20L, 2_000L))
                    }
                    MacroTriggerMode.LOOP -> {
                        val count = if (pin.repeatCount <= 0) 3 else pin.repeatCount.coerceIn(1, 50)
                        val interval = pin.intervalMs.coerceIn(50L, 5_000L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        repeat(count) { i ->
                            if (!currentCoroutineContext().isActive) return@repeat
                            NukeConnectionManager.injectTap(px, py, tapDur)
                            if (i < count - 1) delay(interval)
                        }
                    }
                    MacroTriggerMode.MIRROR -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        NukeConnectionManager.injectTap(tx, ty, pin.tapDurationMs.coerceIn(8L, 80L))
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "triggerPin error pin#${pin.index}: ${e.message}")
                }
            } finally {
                testJobs.remove(pin.id)
                updateIdleState()
            }
        }
        testJobs[pin.id] = job
    }

    fun stopAll() {
        testJobs.values.forEach { it.cancel() }
        testJobs.clear()
        _runState.value = MacroRunState.Idle
    }

    fun stopMacro() = stopAll()

    private fun updateIdleState() {
        if (holdJobs.isEmpty() && testJobs.isEmpty()) {
            _runState.value = MacroRunState.Idle
        }
    }
}