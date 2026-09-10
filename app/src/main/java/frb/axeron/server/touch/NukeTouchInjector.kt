package frb.axeron.server.touch

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.pow

/**
 * Game Nuke Touch Injector (Red Corner 1:1 Benchmark Alignment).
 *
 * Implements:
 * 1. Strict FIFO Injection Queue with real-time thread priority (-8).
 * 2. Frame-Coalesced single ACTION_MOVE per display tick (0ms lag backlog).
 * 3. Relative Aim screen boundary expansion for seamless 3D aiming in Free Fire / PUBG.
 * 4. Micro-allocation OneEuroFilter for smooth jitter-free dragshots.
 * 5. Monotonic hardware clock synchronization matching Android InputDispatcher.
 */
class NukeTouchInjector {
    companion object {
        private const val TAG = "NukeTouchInjector"

        const val AREA_ALL = 0
        const val AREA_LEFT = -1
        const val AREA_RIGHT = 1

        const val CURVE_LINEAR = 0
        const val CURVE_ACCELERATE = 1
        const val CURVE_DECELERATE = 2

        const val INJECT_MODE_ASYNC = 0
        const val MAX_POINTERS = 16
    }

    /**
     * Micro-allocation OneEuroFilter matching Red Corner's w23 algorithm.
     */
    class OneEuroFilter {
        var initialized: Boolean = false
        var prevX: Float = 0f
        var prevDx: Float = 0f
        var prevTime: Long = 0L

        fun filter(x: Float, timestampMs: Long, minCutoff: Float, beta: Float): Float {
            if (!initialized) {
                initialized = true
                prevX = x
                prevDx = 0f
                prevTime = timestampMs
                return x
            }
            var dt = (timestampMs - prevTime).toFloat() / 1000.0f
            if (dt <= 0f) dt = 0.001f
            prevTime = timestampMs

            val dx = (x - prevX) / dt
            val edx = ((dx - prevDx) * (1.0f / ((0.15915494f / dt) + 1.0f))) + prevDx
            prevDx = edx

            val cutoff = (abs(edx) * beta) + minCutoff
            val filteredX = ((x - prevX) * (1.0f / (((1.0f / (cutoff * 6.2831855f)) / dt) + 1.0f))) + prevX
            prevX = filteredX
            return filteredX
        }

        fun reset() {
            initialized = false
            prevX = 0f
            prevDx = 0f
            prevTime = 0L
        }
    }

    private class PointerState(
        val id: Int,
        var x: Float,
        var y: Float,
        var rawX: Float,
        var rawY: Float,
        var pressure: Float,
        var size: Float,
        val inSensArea: Boolean,
        var accumX: Float,
        var accumY: Float,
        val filterX: OneEuroFilter = OneEuroFilter(),
        val filterY: OneEuroFilter = OneEuroFilter()
    )

    @Volatile var sensX: Float = 1.0f
    @Volatile var sensY: Float = 1.0f
    @Volatile var sensArea: Int = AREA_RIGHT
    @Volatile var sensCurve: Int = CURVE_ACCELERATE
    @Volatile var euroEnabled: Boolean = true
    @Volatile var euroMinCutoff: Float = 1.0f
    @Volatile var euroBeta: Float = 0.007f
    @Volatile var relativeAimEnabled: Boolean = true

    private val activePointers = LinkedHashMap<Long, PointerState>()
    private val pendingPointers = HashSet<Long>()
    private val usedIds = BooleanArray(MAX_POINTERS)

    private val propsPool = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coordsPool = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private val emitProps = Array(MAX_POINTERS) { propsPool[it] }
    private val emitCoords = Array(MAX_POINTERS) { coordsPool[it] }

    private var downTime: Long = 0L
    private var lastInjectEventTime: Long = 0L
    private var lastHwTimeMs: Long = 0L
    private var moveDirty: Boolean = false
    private var maxPressureSeen: Float = 1.0f
    private var maxTouchMajorSeen: Float = 1.0f

    private val injectQueue = LinkedBlockingQueue<MotionEvent>()

    @Volatile private var running = true
    private var injectThread: Thread? = null

    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    init {
        resolveInputManager()
        start()
    }

    @Synchronized
    fun start() {
        resolveInputManager()
        reset()
        running = true
        if (injectThread == null || !injectThread!!.isAlive) {
            injectThread = thread(name = "nuke-touch-inject", isDaemon = true) {
                runCatching { Process.setThreadPriority(-8) }
                while (running) {
                    try {
                        val event = injectQueue.take()
                        injectNow(event)
                    } catch (_: InterruptedException) {
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error in inject loop: ${t.message}")
                    }
                }
            }
            Log.i(TAG, "injectThread started successfully (daemon thread active)")
        }
    }

    private fun resolveInputManager() {
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                inputManager = asInterface.invoke(null, binder)

                val im = inputManager
                if (im != null) {
                    for (m in im.javaClass.methods) {
                        if (m.name == "injectInputEvent") {
                            val params = m.parameterTypes
                            if (params.isNotEmpty() && params[0].name.contains("InputEvent")) {
                                m.isAccessible = true
                                injectInputEventMethod = m
                                Log.i(TAG, "Resolved injectInputEvent: $m (${params.size} params)")
                                break
                            }
                        }
                    }
                }
            }
            setDisplayIdMethod = runCatching {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }.getOrNull()
        }.onFailure {
            Log.e(TAG, "Failed to resolve IInputManager: ${it.message}", it)
        }
    }

    /**
     * Pre-flight injection capability test:
     * Verifies that IInputManager.injectInputEvent can actually execute in this process
     * before hardware grab is enabled. Prevents any screen lockup if OEM permissions are restricted.
     */
    fun testInjectionCapability(): Boolean {
        return try {
            val im = inputManager ?: return false
            val method = injectInputEventMethod ?: return false
            val dummy = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            setDisplayIdMethod?.invoke(dummy, 0)
            val params = method.parameterTypes
            val res = if (params.size == 2) {
                method.invoke(im, dummy, INJECT_MODE_ASYNC)
            } else if (params.size >= 3) {
                method.invoke(im, dummy, INJECT_MODE_ASYNC, 0)
            } else {
                method.invoke(im, dummy)
            }
            dummy.recycle()
            (res == true || res == null)
        } catch (t: Throwable) {
            Log.w(TAG, "testInjectionCapability failed: ${t.message} — grab must NOT be enabled")
            false
        }
    }

    fun isInjectionReady(): Boolean {
        if (inputManager == null || injectInputEventMethod == null) {
            resolveInputManager()
        }
        if (inputManager == null || injectInputEventMethod == null) {
            return false
        }
        return testInjectionCapability()
    }

    @Volatile private var consecutiveErrors = 0

    private fun injectNow(event: MotionEvent) {
        val qSize = injectQueue.size
        if (qSize > 30) {
            Log.w(TAG, "injectQueue backed up with $qSize events — releasing grab for safety")
            NukeTouchService.emergencyReleaseGrab("injectQueue backlog: $qSize")
            return
        }
        try {
            setDisplayIdMethod?.invoke(event, 0)
        } catch (_: Throwable) {}
        try {
            val im = inputManager ?: run {
                Log.e(TAG, "injectNow: inputManager is null — emergency releasing grab!")
                NukeTouchService.emergencyReleaseGrab("inputManager is null")
                return
            }
            val method = injectInputEventMethod ?: run {
                Log.e(TAG, "injectNow: injectInputEventMethod is null — emergency releasing grab!")
                NukeTouchService.emergencyReleaseGrab("injectInputEventMethod is null")
                return
            }
            val params = method.parameterTypes
            val res = if (params.size == 2) {
                method.invoke(im, event, INJECT_MODE_ASYNC)
            } else if (params.size >= 3) {
                method.invoke(im, event, INJECT_MODE_ASYNC, 0)
            } else {
                method.invoke(im, event)
            }
            if (res == false) {
                consecutiveErrors++
                if (consecutiveErrors >= 3) {
                    Log.e(TAG, "injectInputEvent rejected 3 consecutive times — releasing grab!")
                    NukeTouchService.emergencyReleaseGrab("injectInputEvent rejected")
                }
            } else {
                consecutiveErrors = 0
            }
        } catch (t: Throwable) {
            Log.e(TAG, "injectNow exception: ${t.message} — releasing grab for user safety!")
            NukeTouchService.emergencyReleaseGrab("injectNow exception: ${t.message}")
        } finally {
            runCatching { event.recycle() }
        }
    }

    private fun allocId(): Int {
        for (i in 0 until MAX_POINTERS) {
            if (!usedIds[i]) {
                usedIds[i] = true
                return i
            }
        }
        return -1
    }

    private fun biasedGain(sx: Float, sy: Float, weight: Float): Float {
        if (sx == sy) return sx
        val w = weight.coerceIn(0f, 1f).toDouble()
        return (sx.toDouble().pow(1.0 - w) * sy.toDouble().pow(w)).toFloat()
    }

    private fun curveFactor(normalizedSpeed: Float): Float {
        return when (sensCurve) {
            CURVE_LINEAR -> 1.0f
            CURVE_ACCELERATE -> {
                val ratio = (normalizedSpeed / 0.04f).coerceIn(0f, 1f)
                (ratio * 0.8f) + 1.0f
            }
            CURVE_DECELERATE -> {
                val ratio = (normalizedSpeed / 0.04f).coerceIn(0f, 1f)
                1.0f - (ratio * 0.5f)
            }
            else -> 1.0f
        }
    }

    private fun maxCurveFactor(): Float {
        return if (sensCurve == CURVE_ACCELERATE) 1.8f else 1.0f
    }

    private fun inSensArea(x: Float, displayWidth: Int): Boolean {
        if (displayWidth <= 0 || sensArea == AREA_ALL) return true
        val half = displayWidth * 0.5f
        return when (sensArea) {
            AREA_LEFT -> x < half
            AREA_RIGHT -> x >= half
            else -> true
        }
    }

    private fun inBounds(ev: TouchEvent): Boolean {
        return ev.displayWidth > 0 && ev.displayHeight > 0 &&
               ev.dispX >= 0 && ev.dispX < ev.displayWidth &&
               ev.dispY >= 0 && ev.dispY < ev.displayHeight
    }

    private fun noteHwTime(timestampUs: Long) {
        if (timestampUs <= 0) return
        lastHwTimeMs = timestampUs / 1000L
    }

    private fun eventTime(): Long {
        val uptime = SystemClock.uptimeMillis()
        val hw = lastHwTimeMs
        val isHardwareMonotonic = (hw in (uptime - 2000L)..uptime)
        return if (isHardwareMonotonic) hw else uptime
    }

    private fun normPressure(p: Int): Float {
        if (p <= 0) return 1.0f
        val f = p.toFloat()
        if (f > maxPressureSeen) maxPressureSeen = f
        return (f / maxPressureSeen).coerceIn(0.0f, 1.0f)
    }

    private fun normSize(s: Int): Float {
        if (s <= 0) return 1.0f
        val f = s.toFloat()
        if (f > maxTouchMajorSeen) maxTouchMajorSeen = f
        return (f / maxTouchMajorSeen).coerceIn(0.0f, 1.0f)
    }

    private fun inject(
        dTime: Long,
        evTime: Long,
        action: Int,
        pointerCount: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>
    ): Boolean {
        val eTime = if (evTime > lastInjectEventTime) evTime else lastInjectEventTime + 1
        lastInjectEventTime = eTime
        val down = if (dTime > eTime) eTime else dTime

        val event = MotionEvent.obtain(
            down, eTime, action, pointerCount,
            props, coords, 0, 0, 1.0f, 1.0f, 0, 0, 4098, 0
        )
        injectQueue.put(event)
        return true
    }

    private fun emit(action: Int, pointerIdMatch: Long? = null) {
        val totalActive = activePointers.size
        if (totalActive == 0) return

        var index = 0
        var actionIndex = 0
        for ((key, ptr) in activePointers) {
            if (index >= MAX_POINTERS) break
            val prop = emitProps[index]
            prop.clear()
            prop.id = ptr.id
            prop.toolType = MotionEvent.TOOL_TYPE_FINGER

            val coord = emitCoords[index]
            coord.clear()
            coord.x = ptr.x
            coord.y = ptr.y
            coord.pressure = ptr.pressure
            coord.size = ptr.size

            if (pointerIdMatch != null && key == pointerIdMatch) {
                actionIndex = index
            }
            index++
        }
        if (index == 0) return

        var resolvedAction = action
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            resolvedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }

        inject(downTime, eventTime(), resolvedAction, index, emitProps, emitCoords)
    }

    private fun flushPendingMove() {
        if (moveDirty && activePointers.isNotEmpty()) {
            emit(MotionEvent.ACTION_MOVE, null)
        }
        moveDirty = false
    }

    private fun startPointer(key: Long, ev: TouchEvent) {
        val dx = if (ev.dispXf.isNaN()) ev.dispX.toFloat() else ev.dispXf
        val dy = if (ev.dispYf.isNaN()) ev.dispY.toFloat() else ev.dispYf
        val inArea = inSensArea(dx, ev.displayWidth)
        beginPointer(key, dx, dy, normPressure(ev.pressure), normSize(ev.touchMajor), inArea)
    }

    private fun beginPointer(key: Long, x: Float, y: Float, pressure: Float, size: Float, inArea: Boolean) {
        flushPendingMove()
        val id = allocId()
        if (id < 0) return

        if (activePointers.isEmpty()) {
            downTime = eventTime()
        }
        val ptr = PointerState(
            id = id,
            x = x, y = y,
            rawX = x, rawY = y,
            pressure = pressure, size = size,
            inSensArea = inArea,
            accumX = x, accumY = y
        )
        activePointers[key] = ptr
        val isFirst = (activePointers.size == 1)
        emit(if (isFirst) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, key)
        moveDirty = false
    }

    private fun integrateMove(ptr: PointerState, ev: TouchEvent) {
        val dx = if (ev.dispXf.isNaN()) ev.dispX.toFloat() else ev.dispXf
        val dy = if (ev.dispYf.isNaN()) ev.dispY.toFloat() else ev.dispYf

        val deltaX = dx - ptr.rawX
        val deltaY = dy - ptr.rawY
        ptr.rawX = dx
        ptr.rawY = dy

        val inArea = ptr.inSensArea
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        val totalDelta = absX + absY

        val normSpeed = if (ev.displayWidth > 0) totalDelta / ev.displayWidth.toFloat() else 0f
        val gain = if (inArea && totalDelta > 1e-4f) {
            biasedGain(sensX, sensY, absY / totalDelta) * curveFactor(normSpeed)
        } else {
            1.0f
        }

        val nextAccumX = (deltaX * gain) + ptr.accumX
        val nextAccumY = (deltaY * gain) + ptr.accumY

        val clampedX: Float
        val clampedY: Float

        if (relativeAimEnabled && inArea && ev.displayWidth > 0 && ev.displayHeight > 0) {
            val maxFactor = maxOf(sensX, sensY) * maxCurveFactor() - 1.0f
            val overflow = if (maxFactor >= 0f) maxFactor else 0f
            val overflowW = ev.displayWidth * overflow
            val overflowH = ev.displayHeight * overflow
            clampedX = nextAccumX.coerceIn(-overflowW, (ev.displayWidth - 1) + overflowW)
            clampedY = nextAccumY.coerceIn(-overflowH, (ev.displayHeight - 1) + overflowH)
        } else {
            val w = ev.displayWidth.toFloat()
            val h = ev.displayHeight.toFloat()
            clampedX = if (w > 0f) nextAccumX.coerceIn(0f, w - 1f) else nextAccumX
            clampedY = if (h > 0f) nextAccumY.coerceIn(0f, h - 1f) else nextAccumY
        }

        ptr.accumX = clampedX
        ptr.accumY = clampedY

        if (euroEnabled) {
            val t = eventTime()
            ptr.x = ptr.filterX.filter(clampedX, t, euroMinCutoff, euroBeta)
            ptr.y = ptr.filterY.filter(clampedY, t, euroMinCutoff, euroBeta)
        } else {
            ptr.x = clampedX
            ptr.y = clampedY
        }

        ptr.pressure = normPressure(ev.pressure)
        ptr.size = normSize(ev.touchMajor)
        moveDirty = true
    }

    @Synchronized
    fun onFrame() {
        flushPendingMove()
    }

    @Synchronized
    fun onSample(ev: TouchEvent) {
        if (ev.dispX == Int.MIN_VALUE || ev.dispY == Int.MIN_VALUE) return
        noteHwTime(ev.timestamp)
        val key = (ev.slot.toLong() and 0xFFFFFFFFL) or (ev.deviceId.toLong() shl 32)
        val act = ev.action

        when (act) {
            TouchEvent.ACTION_DOWN -> {
                if (activePointers.containsKey(key)) return
                if (inBounds(ev)) {
                    startPointer(key, ev)
                } else {
                    pendingPointers.add(key)
                }
            }

            TouchEvent.ACTION_MOVE -> {
                val ptr = activePointers[key]
                if (ptr != null) {
                    integrateMove(ptr, ev)
                } else if (pendingPointers.contains(key) && inBounds(ev)) {
                    pendingPointers.remove(key)
                    startPointer(key, ev)
                }
            }

            TouchEvent.ACTION_UP -> {
                if (activePointers.containsKey(key)) {
                    flushPendingMove()
                    val isLast = (activePointers.size == 1)
                    emit(if (isLast) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, key)
                    val removed = activePointers.remove(key)
                    if (removed != null) {
                        usedIds[removed.id] = false
                    }
                    moveDirty = false
                } else {
                    pendingPointers.remove(key)
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        activePointers.clear()
        pendingPointers.clear()
        Arrays.fill(usedIds, false)
        moveDirty = false
        lastHwTimeMs = 0L
        lastInjectEventTime = 0L
        consecutiveErrors = 0
        while (injectQueue.isNotEmpty()) {
            runCatching { injectQueue.poll()?.recycle() }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        injectThread?.interrupt()
        injectThread = null
        reset()
    }
}
