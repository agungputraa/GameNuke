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
    private const val GRAB_GRACE_MS = 15000L

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

    fun start(libPath: String? = null, allowGrab: Boolean = true): Int {
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
                loaded = touch.loadLibrary("wandev")
            }
            if (!loaded) {
                loaded = touch.loadLibrary("touch")
            }
            if (!loaded) {
                // Check standard fallback paths on device
                val fallbacks = listOf(
                    "/data/local/tmp/libwandev.so",
                    "/system/lib64/libwandev.so",
                    "/vendor/lib64/libwandev.so"
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
            if (grabActive && !eventReceivedSinceGrab) {
                eventReceivedSinceGrab = true
                grabSafetyTimer?.cancel(true)
                grabSafetyTimer = null
            }

            // Kernel hardware event: BTN_TOUCH = 0 (type 1, code 330, value 0)
            // Absolute hardware signal that ZERO fingers touch the physical screen. Purge any stuck pointers immediately.
            if (rawEvent.rawType == 1 && rawEvent.rawCode == 330 && rawEvent.rawValue == 0) {
                injector.onPhysicalScreenReleased()
                if (activeSessions.isNotEmpty()) {
                    activeSessions.keys.forEach { stopMacroSession(it) }
                }
            }

            if (rawEvent.action == TouchEvent.ACTION_FRAME) {
                injector.onFrame()
                return@setSink
            }

            val px = displayTransform.toDisplayPixelsF(rawEvent.normX, rawEvent.normY)
            if (px != null) {
                rawEvent.dispX = px[0].toInt()
                rawEvent.dispY = px[1].toInt()
                rawEvent.dispXf = px[0]
                rawEvent.dispYf = px[1]
            } else {
                // Safe non-blocking fallback if normX/normY was not resolved by display transform
                val snap = displayTransform.snapshot
                val dw = if (snap.width > 0) snap.width else 1080
                val dh = if (snap.height > 0) snap.height else 2400
                val nx = if (rawEvent.normX >= 0f) rawEvent.normX.coerceIn(0f, 1f) else 0f
                val ny = if (rawEvent.normY >= 0f) rawEvent.normY.coerceIn(0f, 1f) else 0f
                rawEvent.dispX = (nx * dw).toInt()
                rawEvent.dispY = (ny * dh).toInt()
                rawEvent.dispXf = nx * dw
                rawEvent.dispYf = ny * dh
            }
            rawEvent.displayWidth = displayTransform.snapshot.width.let { if (it > 0) it else 1080 }
            rawEvent.displayHeight = displayTransform.snapshot.height.let { if (it > 0) it else 2400 }
            rawEvent.rotation = displayTransform.snapshot.rotation

            // --- RED CORNER MULTI-TOUCH MACRO ROUTER (uu4.java & NukeTouchService dump reference) ---
            val curPins = macroPins
            val hwKey = (rawEvent.slot.toLong() and 0xFFFFFFFFL) or (rawEvent.deviceId.toLong() shl 32)

            if (curPins.isNotEmpty()) {
                val act = rawEvent.action
                val dw = rawEvent.displayWidth.toFloat()
                val dh = rawEvent.displayHeight.toFloat()
                val dwNorm = if (dw > 0f) dw else 1080f
                val dhNorm = if (dh > 0f) dh else 2400f

                if (act == TouchEvent.ACTION_DOWN) {
                    val hit = findHitPin(curPins, dwNorm, dhNorm, rawEvent)
                    if (hit != null) {
                        val (resolvedTx, resolvedTy) = resolveTarget(dwNorm, dhNorm, hit)
                        startMacroSession(hwKey, hit, resolvedTx, resolvedTy)

                        // Execute linked combo pins if configured
                        if (hit.linkedPinIds.isNotEmpty()) {
                            val linkedTargets = curPins.filter { it.enabled && hit.linkedPinIds.contains(it.id) }
                            val delayMs = hit.multiPinDelayMs.coerceIn(0L, 500L)
                            linkedTargets.forEachIndexed { index, linkedPin ->
                                val (lTx, lTy) = resolveTarget(dwNorm, dhNorm, linkedPin)
                                val linkedHwKey = hwKey + ((index + 1).toLong() shl 32)
                                if (delayMs <= 0 || index == 0) {
                                    startMacroSession(linkedHwKey, linkedPin, lTx, lTy)
                                } else {
                                    macroExecutor.schedule({
                                        startMacroSession(linkedHwKey, linkedPin, lTx, lTy)
                                    }, delayMs * index, TimeUnit.MILLISECONDS)
                                }
                            }
                        }
                        return@setSink
                    }
                } else if (act == TouchEvent.ACTION_MOVE) {
                    val session = activeSessions[hwKey]
                    if (session != null) {
                        if (session.pin.mode == 3 && session.isAlive.get()) {
                            // MODE 3: STICK_FOLLOW (Virtual Aim/Move Stick displacement from pin origin)
                            val pinOriginX = if (session.pin.x <= 1.0f) session.pin.x * dwNorm else session.pin.x
                            val pinOriginY = if (session.pin.y <= 1.0f) session.pin.y * dhNorm else session.pin.y
                            val curX = if (!rawEvent.dispXf.isNaN()) rawEvent.dispXf else rawEvent.dispX.toFloat()
                            val curY = if (!rawEvent.dispYf.isNaN()) rawEvent.dispYf else rawEvent.dispY.toFloat()
                            var dispX = curX - pinOriginX
                            var dispY = curY - pinOriginY
                            if (session.pin.invertX) dispX = -dispX
                            if (session.pin.invertY) dispY = -dispY
                            val sensX = dispX * session.pin.sensX
                            val sensY = dispY * session.pin.sensY
                            val baseTx = if (session.pin.targetX > 0f) {
                                if (session.pin.targetX <= 1.0f) session.pin.targetX * dwNorm else session.pin.targetX
                            } else session.targetX
                            val baseTy = if (session.pin.targetY > 0f) {
                                if (session.pin.targetY <= 1.0f) session.pin.targetY * dhNorm else session.pin.targetY
                            } else session.targetY
                            injector.pointerMove(
                                session.synthKey,
                                (baseTx + sensX).coerceIn(0f, dwNorm),
                                (baseTy + sensY).coerceIn(0f, dhNorm)
                            )
                        }
                        return@setSink
                    }

                    // Slide-in gesture detection: finger slid from game screen onto a macro pin
                    val slideHit = findHitPin(curPins, dwNorm, dhNorm, rawEvent)
                    if (slideHit != null) {
                        val (resolvedTx, resolvedTy) = resolveTarget(dwNorm, dhNorm, slideHit)
                        startMacroSession(hwKey, slideHit, resolvedTx, resolvedTy)
                        return@setSink
                    }
                } else if (act == TouchEvent.ACTION_UP) {
                    if (activeSessions.containsKey(hwKey)) {
                        stopMacroSession(hwKey)
                        return@setSink
                    }
                }
            }

            // Normal gaming touch or camera aim swipe passes to injector when grab is active.
            // When grab is inactive (1.0x native sensitivity), Linux passes directly to Android OS with 0ms latency.
            if (grabActive) {
                injector.onSample(rawEvent)
            }
        }

        val res = touch.nativeStart()
        if (res >= 0) {
            listening = true
            eventReceivedSinceGrab = false

            val canInject = injector.testInjectionCapability()
            if (allowGrab && canInject) {
                runCatching { touch.nativeSetGrab(true) }
                grabActive = true
                Log.i(TAG, "Touch listener active with hardware grab ENABLED (sensitivity scaling active)")
                grabSafetyTimer?.cancel(true)
                grabSafetyTimer = safetyExecutor.schedule({
                    if (!eventReceivedSinceGrab) {
                        Log.w(TAG, "Watchdog: No touch events received within grace period; releasing grab")
                        emergencyReleaseGrab("No touch events received")
                    }
                }, GRAB_GRACE_MS, TimeUnit.MILLISECONDS)
            } else {
                runCatching { touch.nativeSetGrab(false) }
                grabActive = false
                Log.i(TAG, "Touch listener active in monitor mode (grab=false, canInject=$canInject)")
            }
        } else {
            Log.e(TAG, "nativeStart returned failure: $res")
        }
        return res
    }

    private fun findHitPin(pins: List<NukeTouchInjector.MacroPinTarget>, w: Float, h: Float, ev: TouchEvent): NukeTouchInjector.MacroPinTarget? {
        val touchX = if (!ev.dispXf.isNaN()) ev.dispXf else ev.dispX.toFloat()
        val touchY = if (!ev.dispYf.isNaN()) ev.dispYf else ev.dispY.toFloat()
        for (pin in pins) {
            val px = if (pin.x > 1.0f || w <= 0f) pin.x else pin.x * w
            val py = if (pin.y > 1.0f || h <= 0f) pin.y else pin.y * h
            val radiusPx = if (pin.radiusPx > 1.0f || w <= 0f) pin.radiusPx else pin.radiusPx * w
            val max = maxOf(1.35f * radiusPx, radiusPx + 30.0f)
            val dx = touchX - px
            val dy = touchY - py
            if ((dx * dx + dy * dy) <= max * max) {
                return pin
            }
        }
        return null
    }

    private fun resolveTarget(w: Float, h: Float, pin: NukeTouchInjector.MacroPinTarget): Pair<Float, Float> {
        val tx = if (pin.targetX > 0f) {
            if (pin.targetX <= 1.0f) pin.targetX * w else pin.targetX
        } else if (pin.x <= 1.0f) {
            pin.x * w
        } else {
            pin.x
        }
        val ty = if (pin.targetY > 0f) {
            if (pin.targetY <= 1.0f) pin.targetY * h else pin.targetY
        } else if (pin.y <= 1.0f) {
            pin.y * h
        } else {
            pin.y
        }
        return Pair(tx, ty)
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
                // Calibrated esports pacing: ~10-14 taps/sec prevents InputDispatcher choking and camera fling
                val holdMs = pin.tapDurationMs.coerceIn(30L, 200L)
                val intervalMs = pin.intervalMs.coerceIn(40L, 500L)
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
                val tapDur = pin.tapDurationMs.coerceIn(30L, 200L)
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
                val pinPxX = if (pin.x <= 1.0f) pin.x * dw else pin.x
                val pinPxY = if (pin.y <= 1.0f) pin.y * dh else pin.y
                val startX = pinPxX.coerceIn(0f, dw)
                val startY = pinPxY.coerceIn(0f, dh)
                val endX = targetX.coerceIn(0f, dw)
                val endY = targetY.coerceIn(0f, dh)
                val dur = pin.swipeDurationMs.coerceIn(40L, 2000L)
                val steps = ((dur / 10L).toInt()).coerceIn(4, 64)
                val stepMs = (dur / steps).coerceAtLeast(4L)
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
                val tapDur = pin.tapDurationMs.coerceIn(30L, 150L)
                val gapMs = pin.intervalMs.coerceIn(40L, 300L)
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

    fun isGrabActive(): Boolean = grabActive

    fun setGrab(enable: Boolean): Boolean {
        val touch = TouchListener.INSTANCE
        if (!touch.isLoaded) return false
        if (enable) {
            val canInject = injector.testInjectionCapability()
            if (!canInject) {
                Log.w(TAG, "Cannot enable grab: testInjectionCapability failed")
                return false
            }
            runCatching { touch.nativeSetGrab(true) }
            grabActive = true
            Log.i(TAG, "setGrab: hardware grab ENABLED (sensitivity scaling active)")
            grabSafetyTimer?.cancel(true)
            grabSafetyTimer = safetyExecutor.schedule({
                if (!eventReceivedSinceGrab) {
                    Log.w(TAG, "Watchdog: No touch events received within grace period; releasing grab")
                    emergencyReleaseGrab("No touch events received")
                }
            }, GRAB_GRACE_MS, TimeUnit.MILLISECONDS)
        } else {
            injector.flushAllActivePointers()
            runCatching { touch.nativeSetGrab(false) }
            grabActive = false
            grabSafetyTimer?.cancel(true)
            grabSafetyTimer = null
            Log.i(TAG, "setGrab: hardware grab disabled, physical touches direct to OS")
        }
        return true
    }

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
        area: Int = NukeTouchInjector.AREA_ALL,
        curve: Int = NukeTouchInjector.CURVE_LINEAR,
        smoothing: Boolean = false,
        minCutoff: Float = 1.0f,
        beta: Float = 0.007f,
        dragShot: Boolean = false
    ) {
        injector.sensX = sx
        injector.sensY = sy
        injector.sensArea = area
        injector.sensCurve = curve
        injector.euroEnabled = smoothing
        injector.euroMinCutoff = minCutoff
        injector.euroBeta = beta
        injector.dragShotCurve = dragShot
        injector.relativeAimEnabled = true
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

        val touch = TouchListener.INSTANCE
        val needGrab = macroPins.isNotEmpty() || (injector.sensX != 1.0f || injector.sensY != 1.0f)
        if (listening && touch.isLoaded) {
            if (needGrab && !grabActive) {
                runCatching { touch.nativeSetGrab(true) }
                grabActive = true
                Log.i(TAG, "Hardware grab activated for ${macroPins.size} armed macro pin(s)")
            } else if (!needGrab && grabActive) {
                runCatching { touch.nativeSetGrab(false) }
                grabActive = false
                injector.reset()
                Log.i(TAG, "Hardware grab deactivated (no macro pins & 1.0x sensitivity)")
            }
        }
    }

    fun hasActiveMacroPins(): Boolean = macroPins.isNotEmpty()

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
