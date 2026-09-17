package com.neon.gametweak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    private val _runState = MutableStateFlow<MacroRunState>(MacroRunState.Idle)
    private val macroExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Unhandled macro worker error: ${error.message}", error)
        _runState.value = MacroRunState.Error("Macro worker stopped safely")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + macroExceptionHandler)

    /** Test-fire jobs (one-shot, from UI) */
    private val testJobs = ConcurrentHashMap<String, Job>()

    /** Global hold loops (continuous, while user finger is held on pin) */
    private val holdJobs = ConcurrentHashMap<String, Job>()
    val runState: StateFlow<MacroRunState> = _runState.asStateFlow()

    val isRunning: Boolean get() = holdJobs.isNotEmpty() || testJobs.isNotEmpty()

    private fun requireInjection(action: () -> Boolean) {
        if (runCatching { action() }.getOrDefault(false)) return
        if (NukeConnectionManager.ensureTouchBackend(900L) && runCatching { action() }.getOrDefault(false)) return
        throw IllegalStateException("Touch injection unavailable")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GLOBAL HOLD API — called by daemon pin detection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Start macro execution for [pin] — called when daemon detects finger DOWN on pin area.
     * Runs until [releasePin] is called (finger UP).
     */
    fun holdPin(pin: MacroPinConfig, screenW: Int, screenH: Int, triggerLinked: Boolean = true) {
        holdJobs[pin.id]?.cancel()
        _runState.value = MacroRunState.Triggering(pin.index, pin.mode, pin.label)

        if (triggerLinked && pin.linkedPinIds.isNotEmpty()) {
            val linkedPins = memoryPinsCache?.filter { it.enabled && pin.linkedPinIds.contains(it.id) }.orEmpty()
            for (linked in linkedPins) {
                holdPin(linked, screenW, screenH, triggerLinked = false)
            }
        }

        val px = (pin.xRatio * screenW).coerceIn(0f, screenW - 1f)
        val py = (pin.yRatio * screenH).coerceIn(0f, screenH - 1f)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!NukeConnectionManager.ensureTouchBackend(1_500L)) {
                    _runState.value = MacroRunState.Error("Touch backend unavailable")
                    return@launch
                }
                if (pin.startDelayMs > 0) delay(pin.startDelayMs.coerceIn(0L, 3_000L))
                when (pin.mode) {
                    MacroTriggerMode.REPEAT_TAP -> {
                        val interval = pin.intervalMs.coerceIn(5L, 500L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        while (currentCoroutineContext().isActive) {
                            requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                            delay(interval)
                        }
                    }
                    MacroTriggerMode.HOLD -> {
                        // Hold down for user-defined duration or until released
                        requireInjection { NukeConnectionManager.injectHold(px, py, pin.holdDurationMs.coerceIn(50L, 30_000L)) }
                    }
                    MacroTriggerMode.TAP -> {
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 120L)) }
                    }
                    MacroTriggerMode.DOUBLE_TAP -> {
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L)) }
                        delay(pin.intervalMs.coerceIn(30L, 300L))
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L)) }
                    }
                    MacroTriggerMode.SWIPE -> {
                        val rawTx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val rawTy = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        val isSame = (pin.targetXRatio == pin.xRatio && pin.targetYRatio == pin.yRatio)
                        val tx = if (isSame) rawTx else rawTx
                        val ty = if (isSame) (rawTy - (screenH * 0.15f)).coerceIn(0f, screenH - 1f) else rawTy
                        requireInjection { NukeConnectionManager.injectSwipe(px, py, tx, ty, pin.swipeDurationMs.coerceIn(20L, 2_000L)) }
                    }
                    MacroTriggerMode.LOOP -> {
                        val interval = pin.intervalMs.coerceIn(50L, 10_000L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        while (currentCoroutineContext().isActive) {
                            requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                            delay(interval)
                        }
                    }
                    MacroTriggerMode.MIRROR -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        requireInjection { NukeConnectionManager.injectTap(tx, ty, pin.tapDurationMs.coerceIn(8L, 80L)) }
                    }
                    MacroTriggerMode.COMBO -> {
                        val rawTx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val rawTy = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        val isSame = (pin.targetXRatio == pin.xRatio && pin.targetYRatio == pin.yRatio)
                        val tx = if (isSame) (px + (screenW * 0.12f)).coerceIn(0f, screenW - 1f) else rawTx
                        val ty = if (isSame) (py + (screenH * 0.08f)).coerceIn(0f, screenH - 1f) else rawTy
                        val tapDur = pin.tapDurationMs.coerceIn(8L, 80L)
                        val comboDelay = pin.intervalMs.coerceIn(10L, 300L)
                        while (currentCoroutineContext().isActive) {
                            requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                            delay(comboDelay)
                            requireInjection { NukeConnectionManager.injectTap(tx, ty, tapDur) }
                            delay(comboDelay * 2)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "holdPin error pin#${pin.index}: ${e.message}")
                    _runState.value = MacroRunState.Error(e.message ?: "Macro execution failed")
                }
            } finally {
                currentCoroutineContext()[Job]?.let { self -> holdJobs.remove(pin.id, self) }
                updateIdleState()
            }
        }
        holdJobs[pin.id] = job
        job.start()
    }

    /** Stop macro for [pinId] — called when daemon detects finger UP on pin area. */
    fun releasePin(pinId: String) {
        val masterPin = memoryPinsCache?.find { it.id == pinId }
        masterPin?.linkedPinIds?.forEach { linkedId ->
            holdJobs[linkedId]?.cancel()
            holdJobs.remove(linkedId)
        }
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
     * Supports simultaneous or staggered multi-pin linked triggers.
     * Uses NukeConnectionManager injection path.
     */
    fun triggerPin(pin: MacroPinConfig, screenW: Int, screenH: Int, triggerLinked: Boolean = true) {
        if (!NukeConnectionManager.isConnected()) {
            Log.w(TAG, "No backend connected — test fire skipped")
            _runState.value = MacroRunState.Error("No privileged backend connected")
            return
        }

        if (triggerLinked && pin.linkedPinIds.isNotEmpty()) {
            val linkedPins = memoryPinsCache?.filter { it.enabled && pin.linkedPinIds.contains(it.id) }.orEmpty()
            if (linkedPins.isNotEmpty()) {
                scope.launch {
                    val delayMs = pin.multiPinDelayMs.coerceIn(0L, 500L)
                    for (linked in linkedPins) {
                        if (delayMs > 0) delay(delayMs)
                        triggerPin(linked, screenW, screenH, triggerLinked = false)
                    }
                }
            }
        }

        testJobs[pin.id]?.cancel()
        _runState.value = MacroRunState.Triggering(pin.index, pin.mode, pin.label)

        val px = (pin.xRatio * screenW).coerceIn(0f, screenW - 1f)
        val py = (pin.yRatio * screenH).coerceIn(0f, screenH - 1f)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!NukeConnectionManager.ensureTouchBackend(1_500L)) {
                    _runState.value = MacroRunState.Error("Touch backend unavailable")
                    return@launch
                }
                if (pin.startDelayMs > 0) delay(pin.startDelayMs.coerceIn(0L, 3_000L))
                when (pin.mode) {
                    MacroTriggerMode.REPEAT_TAP -> {
                        val count = if (pin.repeatCount <= 0) 5 else pin.repeatCount.coerceIn(1, 50)
                        val interval = pin.intervalMs.coerceIn(5L, 500L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        repeat(count) { i ->
                            if (!currentCoroutineContext().isActive) return@repeat
                            requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                            if (i < count - 1) delay(interval)
                        }
                    }
                    MacroTriggerMode.TAP -> {
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 120L)) }
                    }
                    MacroTriggerMode.HOLD -> {
                        requireInjection { NukeConnectionManager.injectHold(px, py, pin.holdDurationMs.coerceIn(50L, 3_000L)) }
                    }
                    MacroTriggerMode.DOUBLE_TAP -> {
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L)) }
                        delay(pin.intervalMs.coerceIn(30L, 300L))
                        requireInjection { NukeConnectionManager.injectTap(px, py, pin.tapDurationMs.coerceIn(8L, 80L)) }
                    }
                    MacroTriggerMode.SWIPE -> {
                        val rawTx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val rawTy = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        val isSame = (pin.targetXRatio == pin.xRatio && pin.targetYRatio == pin.yRatio)
                        val tx = if (isSame) rawTx else rawTx
                        val ty = if (isSame) (rawTy - (screenH * 0.15f)).coerceIn(0f, screenH - 1f) else rawTy
                        requireInjection { NukeConnectionManager.injectSwipe(px, py, tx, ty, pin.swipeDurationMs.coerceIn(20L, 2_000L)) }
                    }
                    MacroTriggerMode.LOOP -> {
                        val count = if (pin.repeatCount <= 0) 3 else pin.repeatCount.coerceIn(1, 50)
                        val interval = pin.intervalMs.coerceIn(50L, 5_000L)
                        val tapDur = pin.tapDurationMs.coerceIn(5L, 120L)
                        repeat(count) { i ->
                            if (!currentCoroutineContext().isActive) return@repeat
                            requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                            if (i < count - 1) delay(interval)
                        }
                    }
                    MacroTriggerMode.MIRROR -> {
                        val tx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val ty = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        requireInjection { NukeConnectionManager.injectTap(tx, ty, pin.tapDurationMs.coerceIn(8L, 80L)) }
                    }
                    MacroTriggerMode.COMBO -> {
                        val rawTx = (pin.targetXRatio * screenW).coerceIn(0f, screenW - 1f)
                        val rawTy = (pin.targetYRatio * screenH).coerceIn(0f, screenH - 1f)
                        val isSame = (pin.targetXRatio == pin.xRatio && pin.targetYRatio == pin.yRatio)
                        val tx = if (isSame) (px + (screenW * 0.12f)).coerceIn(0f, screenW - 1f) else rawTx
                        val ty = if (isSame) (py + (screenH * 0.08f)).coerceIn(0f, screenH - 1f) else rawTy
                        val tapDur = pin.tapDurationMs.coerceIn(8L, 80L)
                        val comboDelay = pin.intervalMs.coerceIn(10L, 300L)
                        requireInjection { NukeConnectionManager.injectTap(px, py, tapDur) }
                        delay(comboDelay)
                        requireInjection { NukeConnectionManager.injectTap(tx, ty, tapDur) }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "triggerPin error pin#${pin.index}: ${e.message}")
                    _runState.value = MacroRunState.Error(e.message ?: "Macro execution failed")
                }
            } finally {
                currentCoroutineContext()[Job]?.let { self -> testJobs.remove(pin.id, self) }
                updateIdleState()
            }
        }
        testJobs[pin.id] = job
        job.start()
    }

    fun stopAll() {
        holdJobs.values.forEach { it.cancel() }
        holdJobs.clear()
        testJobs.values.forEach { it.cancel() }
        testJobs.clear()
        _runState.value = MacroRunState.Idle
    }

    fun stopMacro() = stopAll()

    private fun updateIdleState() {
        if (holdJobs.isEmpty() && testJobs.isEmpty() && _runState.value !is MacroRunState.Error) {
            _runState.value = MacroRunState.Idle
        }
    }
}