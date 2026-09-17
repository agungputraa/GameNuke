package nuke.wandev.touch

import android.os.Process
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

    fun start(libPath: String? = null, allowGrab: Boolean = false): Int {
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
                Log.i(TAG, "Touch hardware event verified — safety net disarmed")
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
                val dw = rawEvent.displayWidth.toFloat()
                val dh = rawEvent.displayHeight.toFloat()
                val dwNorm = if (dw > 0f) dw else 1080f
                val dhNorm = if (dh > 0f) dh else 2400f

                fun findHitPin(): NukeTouchInjector.MacroPinTarget? {
                    return curPins.firstOrNull { pin ->
                        val pinPxX = if (pin.x <= 1.0f && dw > 0f) pin.x * dw else pin.x
                        val pinPxY = if (pin.y <= 1.0f && dh > 0f) pin.y * dh else pin.y
                        val pinPxR = if (pin.radiusPx <= 1.0f && dw > 0f) pin.radiusPx * dw else pin.radiusPx
                        val effectiveRadius = maxOf(pinPxR * 1.35f, pinPxR + 30f)
                        val dx = rawEvent.dispXf - pinPxX
                        val dy = rawEvent.dispYf - pinPxY
                        (dx * dx + dy * dy) <= (effectiveRadius * effectiveRadius)
                    }
                }

                fun resolveTarget(hitPin: NukeTouchInjector.MacroPinTarget): Pair<Float, Float> {
                    val tx = if (hitPin.targetX > 0f) {
                        if (hitPin.targetX <= 1.0f) hitPin.targetX * dwNorm else hitPin.targetX
                    } else {
                        if (hitPin.x <= 1.0f) hitPin.x * dwNorm else hitPin.x
                    }
                    val ty = if (hitPin.targetY > 0f) {
                        if (hitPin.targetY <= 1.0f) hitPin.targetY * dhNorm else hitPin.targetY
                    } else {
                        if (hitPin.y <= 1.0f) hitPin.y * dhNorm else hitPin.y
                    }
                    return Pair(tx, ty)
                }

                if (act == TouchEvent.ACTION_DOWN) {
                    val hitPin = findHitPin()
                    if (hitPin != null) {
                        val (targetX, targetY) = resolveTarget(hitPin)
                        startMacroSession(hwKey, hitPin, targetX, targetY)

                        // ── Multi-Pin Trigger (One-tap triggers all linked pins) ──
                        if (hitPin.linkedPinIds.isNotEmpty()) {
                            val linkedPins = curPins.filter { it.enabled && hitPin.linkedPinIds.contains(it.id) }
                            val delayMs = hitPin.multiPinDelayMs.coerceIn(0L, 500L)
                            linkedPins.forEachIndexed { i, linked ->
                                val (lx, ly) = resolveTarget(linked)
                                val linkedHwKey = hwKey + ((i + 1L) shl 32)
                                if (delayMs > 0L && i > 0) {
                                    macroExecutor.schedule({
                                        startMacroSession(linkedHwKey, linked, lx, ly)
                                    }, delayMs * i, TimeUnit.MILLISECONDS)
                                } else {
                                    startMacroSession(linkedHwKey, linked, lx, ly)
                                }
                            }
                        }
                        return@setSink
                    }
                } else if (act == TouchEvent.ACTION_MOVE) {
                    val session = activeSessions[hwKey]
                    if (session != null) {
                        if (session.pin.mode == 3) {
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
                    } else {
                        // Resilient fallback: finger moved/settled onto macro pin
                        val hitPin = findHitPin()
                        if (hitPin != null) {
                            val (targetX, targetY) = resolveTarget(hitPin)
                            startMacroSession(hwKey, hitPin, targetX, targetY)
                            return@setSink
                        }
                    }
                } else if (act == TouchEvent.ACTION_UP) {
                    if (activeSessions.containsKey(hwKey)) {
                        stopMacroSession(hwKey)
                        return@setSink
                    }
                }
            }

            // Normal gaming touch or camera aim swipe -> passes directly to injector with full X/Y sensitivity
            if (grabActive) {
                injector.onSample(rawEvent)
            }
        }

        val res = touch.nativeStart()
        if (res >= 0) {
            listening = true
            eventReceivedSinceGrab = false

            // CRITICAL SCREEN FREEZE PREVENTION:
            // Never enable hardware grab by default! Only allow when explicitly requested AND verified
            // by a real pre-flight injection capability test (prevents freeze on Xiaomi/HyperOS, Samsung, etc.)
            val canInject = injector.testInjectionCapability()
            if (allowGrab && canInject) {
                val grabOk = runCatching { touch.nativeSetGrab(true); true }.getOrDefault(false)
                grabActive = grabOk
                Log.i(TAG, "Touch listener started and kernel grab active ($grabOk) for $res device(s)")

                if (grabActive) {
                    grabSafetyTimer?.cancel(true)
                    grabSafetyTimer = safetyExecutor.schedule({
                        if (listening && grabActive && !eventReceivedSinceGrab) {
                            Log.w(TAG, "SAFETY NET: no events after ${GRAB_GRACE_MS}ms — releasing grab")
                            touch.nativeSetGrab(false)
                            grabActive = false
                            injector.reset()
                        }
                    }, GRAB_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            } else {
                touch.nativeSetGrab(false)
                grabActive = false
                Log.i(TAG, "Touch listener active in safe monitor mode (grabActive=false, screen free)")
            }
        } else {
            Log.e(TAG, "nativeStart returned failure: $res")
        }
        return res
    }

    private fun startMacroSession(hwKey: Long, pin: NukeTouchInjector.MacroPinTarget, targetX: Float, targetY: Float) {
        val synthKey = (-281474976710656L) or (hwKey and 0x0000_FFFF_FFFF_FFFFL)
        val oldSession = activeSessions.remove(hwKey)
        oldSession?.let {
            it.isAlive.set(false)
            it.scheduledFuture?.cancel(true)
            injector.pointerUp(it.synthKey)
        }

        val session = MacroSession(hwKey, synthKey, pin, targetX, targetY)
        activeSessions[hwKey] = session

        when (pin.mode) {
            0 -> { // RAPID SPAM / BURST (REPEAT_TAP) — Continuous while held on screen
                val holdMs = pin.tapDurationMs.coerceIn(10L, 200L)
                val intervalMs = pin.intervalMs.coerceIn(10L, 200L)
                val repeatLimit = if (pin.repeatCount <= 0) Int.MAX_VALUE else pin.repeatCount
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)

                session.scheduledFuture = macroExecutor.schedule({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    } catch (_: Throwable) {}
                    var count = 0
                    try {
                        while (session.isAlive.get() && count < repeatLimit) {
                            var downEmitted = false
                            try {
                                injector.pointerDown(synthKey, targetX, targetY)
                                downEmitted = true
                                Thread.sleep(holdMs)
                            } finally {
                                if (downEmitted) {
                                    injector.pointerUp(synthKey)
                                }
                            }
                            count++
                            if (!session.isAlive.get() || count >= repeatLimit) break
                            try {
                                Thread.sleep(intervalMs)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    } catch (_: InterruptedException) {
                        // cancellation
                    } finally {
                        injector.pointerUp(synthKey)
                        if (session.isAlive.get() && count >= repeatLimit) {
                            stopMacroSession(hwKey)
                        }
                    }
                }, startDelay, TimeUnit.MILLISECONDS)
            }
            1 -> { // SINGLE TAP
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                val tapDur = pin.tapDurationMs.coerceIn(10L, 200L)
                session.scheduledFuture = macroExecutor.schedule({
                    if (!session.isAlive.get()) return@schedule
                    try {
                        injector.pointerDown(synthKey, targetX, targetY)
                        try { Thread.sleep(tapDur) } catch (_: InterruptedException) {}
                    } finally {
                        injector.pointerUp(synthKey)
                        stopMacroSession(hwKey)
                    }
                }, startDelay, TimeUnit.MILLISECONDS)
            }
            2 -> { // SUSTAINED HOLD
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                val holdDur = pin.holdDurationMs.coerceIn(50L, 30_000L)
                session.scheduledFuture = macroExecutor.schedule({
                    if (!session.isAlive.get()) return@schedule
                    try {
                        injector.pointerDown(synthKey, targetX, targetY)
                        try { Thread.sleep(holdDur) } catch (_: InterruptedException) {}
                    } finally {
                        injector.pointerUp(synthKey)
                        stopMacroSession(hwKey)
                    }
                }, startDelay, TimeUnit.MILLISECONDS)
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
                val endX = (if (pin.targetX <= 1.0f && pin.targetX > 0f) pin.targetX * dw else if (pin.targetX > 0f) pin.targetX else startX).coerceIn(0f, dw)
                val endY = (if (pin.targetY <= 1.0f && pin.targetY > 0f) pin.targetY * dh else if (pin.targetY > 0f) pin.targetY else startY).coerceIn(0f, dh)
                val dur = pin.swipeDurationMs.coerceIn(20L, 2000L)
                val steps = ((dur / 8L).toInt()).coerceIn(4, 64)
                val stepMs = (dur / steps).coerceAtLeast(1L)
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)

                session.scheduledFuture = macroExecutor.schedule({
                    if (!session.isAlive.get()) return@schedule
                    try {
                        injector.pointerDown(synthKey, startX, startY)
                        for (i in 1..steps) {
                            if (!session.isAlive.get()) break
                            val p = i.toFloat() / steps
                            val ix = startX + (endX - startX) * p
                            val iy = startY + (endY - startY) * p
                            try {
                                injector.pointerMove(synthKey, ix, iy)
                                Thread.sleep(stepMs)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    } finally {
                        injector.pointerUp(synthKey)
                        stopMacroSession(hwKey)
                    }
                }, startDelay, TimeUnit.MILLISECONDS)
            }
            5 -> { // DOUBLE TAP (Red Corner 더블 탭)
                val startDelay = pin.startDelayMs.coerceAtLeast(0L)
                val tapDur = pin.tapDurationMs.coerceIn(10L, 200L)
                val gapMs = pin.intervalMs.coerceIn(15L, 500L)
                session.scheduledFuture = macroExecutor.schedule({
                    if (!session.isAlive.get()) return@schedule
                    try {
                        injector.pointerDown(synthKey, targetX, targetY)
                        try { Thread.sleep(tapDur) } catch (_: InterruptedException) {}
                        injector.pointerUp(synthKey)
                        try { Thread.sleep(gapMs) } catch (_: InterruptedException) {}
                        if (session.isAlive.get()) {
                            injector.pointerDown(synthKey, targetX, targetY)
                            try { Thread.sleep(tapDur) } catch (_: InterruptedException) {}
                        }
                    } finally {
                        injector.pointerUp(synthKey)
                        stopMacroSession(hwKey)
                    }
                }, startDelay, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stopMacroSession(hwKey: Long) {
        val session = activeSessions.remove(hwKey)
        session?.let {
            it.isAlive.set(false)
            it.scheduledFuture?.cancel(true)
            it.scheduledFuture = null
            injector.pointerUp(it.synthKey)
        }
        // Also stop any linked sessions spawned from this hwKey
        val baseHwKey = hwKey and 0x0000_FFFF_FFFFL
        val linkedKeys = activeSessions.keys.filter { it != hwKey && (it and 0x0000_FFFF_FFFFL) == baseHwKey }
        for (k in linkedKeys) {
            val s = activeSessions.remove(k) ?: continue
            s.isAlive.set(false)
            s.scheduledFuture?.cancel(true)
            s.scheduledFuture = null
            injector.pointerUp(s.synthKey)
        }
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

        // CRITICAL: flush all active real-finger pointers with ACTION_UP BEFORE releasing the
        // hardware grab. This prevents the Android InputDispatcher from seeing orphaned DOWN
        // events, which would cause the screen to appear frozen until the gesture timeout.
        injector.flushAllActivePointers()

        val touch = TouchListener.INSTANCE
        if (touch.isLoaded) {
            runCatching { touch.nativeSetGrab(false) }
            runCatching { touch.nativeStop() }
        }
        grabActive = false
        TouchListener.setSink(null)
        displayTransform.stop()

        // Allow inject thread 60 ms to drain queued UP events before interrupting it
        try { Thread.sleep(60L) } catch (_: InterruptedException) {}

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
        // Reset per-pointer accumulators so an in-flight gesture doesn't
        // jump to the screen edge when sensitivity changes mid-touch.
        injector.resetAccumulators()
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
