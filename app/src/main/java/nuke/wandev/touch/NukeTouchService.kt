package nuke.wandev.touch

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level coordinator bridging libwandev.so with NukeTouchInjector and DisplayTransform.
 * Runs inside NukeShellDaemon under UID 2000 (shell) with GID 1004 (input).
 *
 * Implements Red Corner's exact kernel-level Multi-Touch Router (TouchMappingRouter):
 * - Absorbs physical touch hits on Macro Pins and executes multi-touch virtual bursts.
 * - Leaves all other screen areas (camera aim, gestures) 100% untouched with full X & Y sensitivity scaling.
 * - Guarantees that camera swipe / aim never freezes or cancels while macro bursts are active!
 */
object NukeTouchService {
    private const val TAG = "NukeTouchService"
    private const val GRAB_GRACE_MS = 30000L

    private val displayTransform = DisplayTransform()
    val injector = NukeTouchInjector()

    @Volatile
    private var listening = false

    @Volatile
    private var grabActive = false

    @Volatile
    private var eventReceivedSinceGrab = false

    private val safetyExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nuke-grab-safety").apply { isDaemon = true }
    }
    private var grabSafetyTimer: ScheduledFuture<*>? = null

    // Macro Router Storage (Red Corner uu4.java benchmark)
    private class MacroSession(
        val hwKey: Long,
        val synthKey: Long,
        val pin: NukeTouchInjector.MacroPinTarget,
        val targetX: Float,
        val targetY: Float,
        val isAlive: AtomicBoolean = AtomicBoolean(true),
        @Volatile var scheduledFuture: ScheduledFuture<*>? = null
    )
    private val macroPins = CopyOnWriteArrayList<NukeTouchInjector.MacroPinTarget>()
    private val activeSessions = ConcurrentHashMap<Long, MacroSession>()
    private val macroExecutor = Executors.newScheduledThreadPool(4) { r ->
        Thread(r, "nuke-macro-worker").apply { isDaemon = true }
    }

    fun start(libPath: String? = null): Int {
        if (listening) return 1
        val touch = TouchListener.INSTANCE
        if (!touch.isLoaded) {
            var loaded = false
            // Check /data/local/tmp/libwandev.so first as it is universally accessible to shell & binder
            if (touch.load("/data/local/tmp/libwandev.so")) {
                loaded = true
            }
            if (!loaded && !libPath.isNullOrBlank()) {
                loaded = touch.load(libPath)
            }
            if (!loaded) {
                loaded = touch.loadLibrary("touch")
            }
            if (!loaded) {
                // Check standard fallback paths on device
                val fallbacks = listOf(
                    "/data/local/tmp/libwandev.so",
                    "/system/lib64/libwandev.so"
                )
                for (fb in fallbacks) {
                    if (touch.load(fb)) {
                        loaded = true
                        break
                    }
                }
            }
            if (!loaded) {
                Log.e(TAG, "Cannot start touch service: libwandev.so could not be loaded")
                return -1
            }
        }

        displayTransform.start()
        injector.start()

        TouchListener.setSink { rawEvent ->
            if (!eventReceivedSinceGrab) {
                eventReceivedSinceGrab = true
                grabSafetyTimer?.cancel(true)
                grabSafetyTimer = null
                Log.i(TAG, "Touch hardware event verified — safety net disarmed, grab permanently active")
            }
            if (rawEvent.action == TouchEvent.ACTION_FRAME) {
                injector.onFrame()
                return@setSink
            }

            val px = displayTransform.toDisplayPixelsF(rawEvent.normX, rawEvent.normY)
            if (px == null) {
                return@setSink
            }
            rawEvent.dispX = px[0].toInt()
            rawEvent.dispY = px[1].toInt()
            rawEvent.dispXf = px[0]
            rawEvent.dispYf = px[1]
            rawEvent.displayWidth = displayTransform.snapshot.width
            rawEvent.displayHeight = displayTransform.snapshot.height
            rawEvent.rotation = displayTransform.snapshot.rotation

            // --- RED CORNER MULTI-TOUCH MACRO ROUTER (uu4.java) ---
            val curPins = macroPins
            val hwKey = (rawEvent.slot.toLong() and 0xFFFFFFFFL) or (rawEvent.deviceId.toLong() shl 32)

            if (curPins.isNotEmpty()) {
                val act = rawEvent.action
                if (act == TouchEvent.ACTION_DOWN) {
                    val dw = rawEvent.displayWidth.toFloat()
                    val dh = rawEvent.displayHeight.toFloat()
                    val hitPin = curPins.firstOrNull { pin ->
                        val pinPxX = if (pin.x <= 1.0f && dw > 0f) pin.x * dw else pin.x
                        val pinPxY = if (pin.y <= 1.0f && dh > 0f) pin.y * dh else pin.y
                        val pinPxR = if (pin.radiusPx <= 1.0f && dw > 0f) pin.radiusPx * dw else pin.radiusPx
                        val dx = rawEvent.dispXf - pinPxX
                        val dy = rawEvent.dispYf - pinPxY
                        (dx * dx + dy * dy) <= (pinPxR * pinPxR)
                    }
                    if (hitPin != null) {
                        val dwNorm = if (dw > 0f) dw else 1080f
                        val dhNorm = if (dh > 0f) dh else 2400f
                        val targetX = if (hitPin.x <= 1.0f) hitPin.x * dwNorm else hitPin.x
                        val targetY = if (hitPin.y <= 1.0f) hitPin.y * dhNorm else hitPin.y
                        startMacroSession(hwKey, hitPin, targetX, targetY)
                        return@setSink
                    }
                } else if (act == TouchEvent.ACTION_MOVE) {
                    val session = activeSessions[hwKey]
                    if (session != null) {
                        if (session.pin.mode == 3) {
                            val dw = if (rawEvent.displayWidth > 0) rawEvent.displayWidth.toFloat() else 1080f
                            val dh = if (rawEvent.displayHeight > 0) rawEvent.displayHeight.toFloat() else 2400f
                            val pinX = if (session.pin.x <= 1.0f) session.pin.x * dw else session.pin.x
                            val pinY = if (session.pin.y <= 1.0f) session.pin.y * dh else session.pin.y
                            val relX = rawEvent.dispXf - pinX
                            val relY = rawEvent.dispYf - pinY
                            val finalDx = (if (session.pin.invertX) -relX else relX) * session.pin.sensX
                            val finalDy = (if (session.pin.invertY) -relY else relY) * session.pin.sensY
                            val baseTargetX = if (session.pin.targetX <= 1.0f && session.pin.targetX > 0f) session.pin.targetX * dw else session.targetX
                            val baseTargetY = if (session.pin.targetY <= 1.0f && session.pin.targetY > 0f) session.pin.targetY * dh else session.targetY
                            injector.pointerMove(session.synthKey, (baseTargetX + finalDx).coerceIn(0f, dw), (baseTargetY + finalDy).coerceIn(0f, dh))
                        }
                        return@setSink // Absorb movement of macro finger
                    }
                } else if (act == TouchEvent.ACTION_UP) {
                    if (activeSessions.containsKey(hwKey)) {
                        stopMacroSession(hwKey)
                        return@setSink
                    }
                }
            }

            // Normal gaming touch or camera aim swipe -> passes directly to injector with full X/Y sensitivity
            injector.onSample(rawEvent)
        }

        val res = touch.nativeStart()
        if (res >= 0) {
            listening = true
            eventReceivedSinceGrab = false

            // CRITICAL SAFETY GUARD:
            // Enable hardware grab if injector is verified
            if (injector.isInjectionReady()) {
                touch.nativeSetGrab(true)
                grabActive = true
                Log.i(TAG, "Touch listener started and grab enabled for $res device(s)")

                grabSafetyTimer?.cancel(true)
                grabSafetyTimer = safetyExecutor.schedule({
                    if (listening && grabActive && !eventReceivedSinceGrab) {
                        Log.w(TAG, "SAFETY NET: no events after ${GRAB_GRACE_MS}ms — releasing grab")
                        touch.nativeSetGrab(false)
                        grabActive = false
                        injector.reset()
                    }
                }, GRAB_GRACE_MS, TimeUnit.MILLISECONDS)
            } else {
                touch.nativeSetGrab(false)
                grabActive = false
                Log.w(TAG, "Injector not ready — running in monitor mode without grab (failsafe)")
            }
        } else {
            Log.e(TAG, "nativeStart returned failure: $res")
        }
        return res
    }

    private fun startMacroSession(hwKey: Long, pin: NukeTouchInjector.MacroPinTarget, targetX: Float, targetY: Float) {
        val synthKey = (-281474976710656L) or (hwKey and 0x0000_FFFF_FFFF_FFFFL)
        val session = MacroSession(hwKey, synthKey, pin, targetX, targetY)
        activeSessions[hwKey] = session

        when (pin.mode) {
            0 -> { // RAPID SPAM / BURST (REPEAT_TAP)
                val holdMs = pin.tapDurationMs.coerceAtLeast(1L)
                val intervalMs = pin.intervalMs.coerceIn(2L, 500L)
                val repeatLimit = if (pin.repeatCount <= 0) Int.MAX_VALUE else pin.repeatCount
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)

                fun executeCycle(count: Int) {
                    if (!session.isAlive.get()) return
                    if (count >= repeatLimit) {
                        stopMacroSession(hwKey)
                        return
                    }

                    // 1. Pointer Down
                    injector.pointerDown(synthKey, targetX, targetY)

                    // 2. Schedule Pointer Up
                    session.scheduledFuture = macroExecutor.schedule({
                        injector.pointerUp(synthKey)
                        if (session.isAlive.get() && count + 1 < repeatLimit) {
                            // 3. Schedule next Pointer Down
                            session.scheduledFuture = macroExecutor.schedule({
                                executeCycle(count + 1)
                            }, intervalMs, TimeUnit.MILLISECONDS)
                        } else if (count + 1 >= repeatLimit) {
                            stopMacroSession(hwKey)
                        }
                    }, holdMs, TimeUnit.MILLISECONDS)
                }

                if (startDelay > 0) {
                    session.scheduledFuture = macroExecutor.schedule({
                        executeCycle(0)
                    }, startDelay, TimeUnit.MILLISECONDS)
                } else {
                    executeCycle(0)
                }
            }
            1 -> { // SINGLE TAP
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                val tapDur = pin.tapDurationMs.coerceAtLeast(1L)
                fun doTap() {
                    if (!session.isAlive.get()) return
                    injector.pointerDown(synthKey, targetX, targetY)
                    session.scheduledFuture = macroExecutor.schedule({
                        injector.pointerUp(synthKey)
                        stopMacroSession(hwKey)
                    }, tapDur, TimeUnit.MILLISECONDS)
                }
                if (startDelay > 0) {
                    session.scheduledFuture = macroExecutor.schedule({
                        doTap()
                    }, startDelay, TimeUnit.MILLISECONDS)
                } else {
                    doTap()
                }
            }
            2 -> { // SUSTAINED HOLD
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                fun doHold() {
                    if (!session.isAlive.get()) return
                    injector.pointerDown(synthKey, targetX, targetY)
                    if (pin.holdDurationMs > 0) {
                        session.scheduledFuture = macroExecutor.schedule({
                            injector.pointerUp(synthKey)
                        }, pin.holdDurationMs, TimeUnit.MILLISECONDS)
                    }
                }
                if (startDelay > 0) {
                    session.scheduledFuture = macroExecutor.schedule({
                        doHold()
                    }, startDelay, TimeUnit.MILLISECONDS)
                } else {
                    doHold()
                }
            }
            3 -> { // MIRROR MODE (Red Corner uu4.java benchmark)
                val dw = displayTransform.snapshot.width.toFloat().let { if (it > 0f) it else 1080f }
                val dh = displayTransform.snapshot.height.toFloat().let { if (it > 0f) it else 2400f }
                val mTargetX = if (pin.targetX <= 1.0f && pin.targetX > 0f) pin.targetX * dw else if (pin.targetX > 0f) pin.targetX else targetX
                val mTargetY = if (pin.targetY <= 1.0f && pin.targetY > 0f) pin.targetY * dh else if (pin.targetY > 0f) pin.targetY else targetY
                injector.pointerDown(synthKey, mTargetX, mTargetY)
            }
            4 -> { // SWIPE / DRAG (Red Corner 드래그 — pin coordinate -> target coordinate)
                val dw = displayTransform.snapshot.width.toFloat().let { if (it > 0f) it else 1080f }
                val dh = displayTransform.snapshot.height.toFloat().let { if (it > 0f) it else 2400f }
                val startX = targetX.coerceIn(0f, dw)
                val startY = targetY.coerceIn(0f, dh)
                val endX = (if (pin.targetX <= 1.0f && pin.targetX > 0f) pin.targetX * dw else if (pin.targetX > 0f) pin.targetX else startX)
                    .coerceIn(0f, dw)
                val endY = (if (pin.targetY <= 1.0f && pin.targetY > 0f) pin.targetY * dh else if (pin.targetY > 0f) pin.targetY else startY)
                    .coerceIn(0f, dh)
                val dur = pin.swipeDurationMs.coerceIn(20L, 2000L)
                val steps = ((dur / 8L).toInt()).coerceIn(4, 64)
                val stepMs = (dur / steps).coerceAtLeast(1L)
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                fun doSwipe() {
                    if (!session.isAlive.get()) return
                    injector.pointerDown(synthKey, startX, startY)
                    var step = 0
                    session.scheduledFuture = macroExecutor.scheduleAtFixedRate({
                        if (!session.isAlive.get()) {
                            session.scheduledFuture?.cancel(false)
                            return@scheduleAtFixedRate
                        }
                        step++
                        if (step >= steps) {
                            try {
                                injector.pointerMove(synthKey, endX, endY)
                            } catch (_: Throwable) {}
                            session.scheduledFuture?.cancel(false)
                            macroExecutor.schedule({
                                injector.pointerUp(synthKey)
                                stopMacroSession(hwKey)
                            }, stepMs, TimeUnit.MILLISECONDS)
                        } else {
                            val p = step.toFloat() / steps
                            val ix = startX + (endX - startX) * p
                            val iy = startY + (endY - startY) * p
                            try {
                                injector.pointerMove(synthKey, ix, iy)
                            } catch (_: Throwable) {}
                        }
                    }, stepMs, stepMs, TimeUnit.MILLISECONDS)
                }
                if (startDelay > 0) {
                    session.scheduledFuture = macroExecutor.schedule({ doSwipe() }, startDelay, TimeUnit.MILLISECONDS)
                } else {
                    doSwipe()
                }
            }
            5 -> { // DOUBLE TAP (Red Corner 더블 탭)
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                val tapDur = pin.tapDurationMs.coerceAtLeast(1L)
                val gapMs = pin.intervalMs.coerceIn(15L, 800L)
                fun tapOnce() {
                    if (!session.isAlive.get()) return
                    injector.pointerDown(synthKey, targetX, targetY)
                    session.scheduledFuture = macroExecutor.schedule({
                        injector.pointerUp(synthKey)
                    }, tapDur, TimeUnit.MILLISECONDS)
                }
                fun doDouble() {
                    tapOnce()
                    session.scheduledFuture = macroExecutor.schedule({
                        injector.pointerDown(synthKey, targetX, targetY)
                        session.scheduledFuture = macroExecutor.schedule({
                            injector.pointerUp(synthKey)
                            stopMacroSession(hwKey)
                        }, tapDur, TimeUnit.MILLISECONDS)
                    }, tapDur + gapMs, TimeUnit.MILLISECONDS)
                }
                if (startDelay > 0) {
                    session.scheduledFuture = macroExecutor.schedule({ doDouble() }, startDelay, TimeUnit.MILLISECONDS)
                } else {
                    doDouble()
                }
            }
        }
    }

    private fun stopMacroSession(hwKey: Long) {
        val session = activeSessions.remove(hwKey) ?: return
        session.isAlive.set(false)
        session.scheduledFuture?.cancel(false)
        session.scheduledFuture = null
        injector.pointerUp(session.synthKey)
    }

    fun stop() {
        if (!listening) return
        grabSafetyTimer?.cancel(true)
        grabSafetyTimer = null

        // Stop all ongoing macro bursts
        for ((_, session) in activeSessions) {
            session.isAlive.set(false)
            session.scheduledFuture?.cancel(false)
            injector.pointerUp(session.synthKey)
        }
        activeSessions.clear()

        val touch = TouchListener.INSTANCE
        if (touch.isLoaded) {
            runCatching { touch.nativeSetGrab(false) }
            runCatching { touch.nativeStop() }
        }
        grabActive = false
        TouchListener.setSink(null)
        displayTransform.stop()
        injector.stop()
        listening = false
        Log.i(TAG, "Touch listener stopped and grab safely released")
    }

    fun isRunning(): Boolean = listening

    fun emergencyReleaseGrab(reason: String) {
        if (!grabActive) return
        Log.e(TAG, "EMERGENCY SAFETY RELEASE: $reason — releasing hardware grab immediately!")
        grabActive = false
        val touch = TouchListener.INSTANCE
        if (touch.isLoaded) {
            runCatching { touch.nativeSetGrab(false) }
        }
        runCatching { injector.reset() }
    }

    fun configure(
        sx: Float,
        sy: Float,
        area: Int = NukeTouchInjector.AREA_RIGHT,
        curve: Int = NukeTouchInjector.CURVE_ACCELERATE,
        smoothing: Boolean = true,
        minCutoff: Float = 1.0f,
        beta: Float = 0.007f,
        dragShot: Boolean = true
    ) {
        injector.sensX = sx
        injector.sensY = sy
        injector.sensArea = area
        injector.sensCurve = curve
        injector.euroEnabled = smoothing
        injector.euroMinCutoff = minCutoff
        injector.euroBeta = beta
        injector.dragShotCurve = dragShot
        Log.i(TAG, "Touch configured: X=%.2f Y=%.2f area=%d curve=%d smooth=%b dragShot=%b".format(sx, sy, area, curve, smoothing, dragShot))
    }

    fun setMacroPins(pins: List<NukeTouchInjector.MacroPinTarget>) {
        macroPins.clear()
        // Red Corner coordinator only pushes enabled pins; the router also guards here.
        macroPins.addAll(pins.filter { it.enabled })
        injector.macroPinTargets = macroPins
        Log.i(TAG, "Configured ${macroPins.size} macro pin(s) in NukeTouchService router")
    }

    fun setMacroPinTriggerListener(listener: ((NukeTouchInjector.MacroPinTarget) -> Unit)?) {
        injector.onMacroPinTriggered = listener
    }

    fun macroPointerDown(macroKey: Long, x: Float, y: Float): Boolean {
        return injector.macroPointerDown(macroKey, x, y)
    }

    fun macroPointerUp(macroKey: Long): Boolean {
        return injector.macroPointerUp(macroKey)
    }

    fun injectDirect(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return injector.injectDirect(action, pointerId, x, y, pressure)
    }

    fun injectTap(x: Float, y: Float, durationMs: Long = 15L): Boolean {
        return injector.injectTapCoordinates(x, y, durationMs)
    }

    fun injectHold(x: Float, y: Float, durationMs: Long): Boolean {
        return injector.injectHoldCoordinates(x, y, durationMs)
    }

    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 120L): Boolean {
        return injector.injectSwipeCoordinates(x1, y1, x2, y2, durationMs)
    }
}
