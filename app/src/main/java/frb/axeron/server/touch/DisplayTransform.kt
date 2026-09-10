package frb.axeron.server.touch

import android.graphics.Point
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * Monitors screen rotation and resolution, transforming normalized touch coordinates [0..1]
 * from libtouch.so (evdev hardware) into physical display screen pixels with 100% 1:1 Red Corner alignment.
 *
 * Handles:
 * 1. Surface.ROTATION_0, 90, 180, 270 with exact evdev orientation matrices.
 * 2. Aspect ratio letterbox / offset calibration.
 * 3. ThreadLocal allocation-free coordinate transformation for 0ms latency.
 */
class DisplayTransform {
    companion object {
        private const val TAG = "NukeDisplayTransform"
        private const val POLL_MS = 200L

        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
    }

    data class Snapshot(
        val rotation: Int = 0,
        val width: Int = 1220,
        val height: Int = 2712,
        val physW: Int = 1220,
        val physH: Int = 2712
    )

    @Volatile
    var snapshot = Snapshot()
        private set

    @Volatile
    private var running = false
    private var poller: Thread? = null

    private val localBuffer = ThreadLocal.withInitial { FloatArray(2) }

    private var getServiceMethod: Method? = null
    private var windowManager: Any? = null
    private var displayManager: Any? = null

    init {
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            getServiceMethod = smClass.getMethod("getService", String::class.java)
            initWindowManager()
            initDisplayManager()
        }
        refreshNow()
    }

    private fun getService(name: String): IBinder? = runCatching {
        getServiceMethod?.invoke(null, name) as? IBinder
    }.getOrNull()

    private fun initWindowManager() {
        val binder = getService("window") ?: return
        val stubClass = Class.forName("android.view.IWindowManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        windowManager = asInterface.invoke(null, binder)
    }

    private fun initDisplayManager() {
        val binder = getService("display") ?: return
        val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        displayManager = asInterface.invoke(null, binder)
    }

    fun refreshNow() {
        val currentSnap = snapshot
        var rot = currentSnap.rotation

        // 1. Rotation & Logical Dimensions via DisplayManager (DisplayInfo)
        val dm = displayManager
        var dmRot = -1
        var dmLW = 0
        var dmLH = 0
        if (dm != null) {
            runCatching {
                val getDisplayInfo = dm.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                val info = getDisplayInfo.invoke(dm, 0)
                if (info != null) {
                    val rotField = info.javaClass.getField("rotation")
                    val lwField = info.javaClass.getField("logicalWidth")
                    val lhField = info.javaClass.getField("logicalHeight")
                    dmRot = rotField.getInt(info)
                    dmLW = lwField.getInt(info)
                    dmLH = lhField.getInt(info)
                }
            }
        }

        if (dmRot >= 0) {
            rot = dmRot
        } else {
            val wm = windowManager
            if (wm != null) {
                runCatching {
                    val getRot = wm.javaClass.getMethod("getDefaultDisplayRotation")
                    rot = getRot.invoke(wm) as Int
                }
            }
        }

        // 2. Base Display Size via WindowManager
        var w = 0
        var h = 0
        val wm = windowManager
        if (wm != null) {
            runCatching {
                val getBase = wm.javaClass.getMethod("getBaseDisplaySize", Int::class.javaPrimitiveType, Point::class.java)
                val p = Point()
                getBase.invoke(wm, 0, p)
                if (p.x > 0 && p.y > 0) {
                    w = p.x
                    h = p.y
                }
            }
        }

        // Fallback from DisplayInfo logical dimensions if getBaseDisplaySize failed
        if (w <= 0 || h <= 0) {
            if (dmLW > 0 && dmLH > 0) {
                if (rot == ROTATION_90 || rot == ROTATION_270) {
                    w = minOf(dmLW, dmLH)
                    h = maxOf(dmLW, dmLH)
                } else {
                    w = dmLW
                    h = dmLH
                }
            }
        }

        // 3. Initial (Physical) Display Size via WindowManager
        var physW = 0
        var physH = 0
        if (wm != null) {
            runCatching {
                val getInit = wm.javaClass.getMethod("getInitialDisplaySize", Int::class.javaPrimitiveType, Point::class.java)
                val p = Point()
                getInit.invoke(wm, 0, p)
                if (p.x > 0 && p.y > 0) {
                    physW = p.x
                    physH = p.y
                }
            }
        }
        if (physW <= 0 || physH <= 0) {
            physW = w
            physH = h
        }

        // Fallback defaults from existing snapshot or typical phone resolution
        if (w <= 0 || h <= 0) {
            w = if (currentSnap.width > 0) minOf(currentSnap.width, currentSnap.height) else 1220
            h = if (currentSnap.height > 0) maxOf(currentSnap.width, currentSnap.height) else 2712
            physW = w
            physH = h
        }

        // Standardize: ensure w, h, physW, physH are portrait-base before applying rotation swap
        val baseW = minOf(w, h)
        val baseH = maxOf(w, h)
        val basePhysW = minOf(physW, physH)
        val basePhysH = maxOf(physW, physH)

        // Swap dimensions for landscape orientation (ROTATION_90 or ROTATION_270)
        val isLandscape = (rot == ROTATION_90 || rot == ROTATION_270)
        val finalW = if (isLandscape) baseH else baseW
        val finalH = if (isLandscape) baseW else baseH
        val finalPhysW = if (isLandscape) basePhysH else basePhysW
        val finalPhysH = if (isLandscape) basePhysW else basePhysH

        val newSnap = Snapshot(rot, finalW, finalH, finalPhysW, finalPhysH)
        if (newSnap != currentSnap) {
            snapshot = newSnap
            Log.i(TAG, "Display snapshot updated: rot=$rot, logical=${finalW}x${finalH}, physical=${finalPhysW}x${finalPhysH}")
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        refreshNow()
        poller = thread(name = "nuke-touch-display", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(POLL_MS)
                    if (running) refreshNow()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        poller?.interrupt()
        poller = null
    }

    /**
     * Transforms normalized evdev coordinates [0..1] to current display screen pixels.
     * Implements the exact 1:1 Red Corner bytecode orientation matrix.
     */
    fun toDisplayPixelsF(normX: Float, normY: Float): FloatArray? {
        val snap = snapshot
        val b = snap.width
        val c = snap.height
        val d = snap.physW
        val e = snap.physH

        if (b <= 0 || c <= 0 || d <= 0 || e <= 0 || normX < 0f || normY < 0f) {
            return null
        }

        var f = normX.coerceIn(0f, 1f)
        var f2 = normY.coerceIn(0f, 1f)

        // 1:1 Red Corner rotation transformation matrix
        when (snap.rotation) {
            ROTATION_90 -> {
                // Device rotated 90° counterclockwise (top is on the left)
                // Red Corner bytecode (0033-0037):
                // v11 = 1f - f; v9 = f2; v12 = 1f - f; v11 = v9
                val oldF = f
                f = f2
                f2 = 1.0f - oldF
            }
            ROTATION_180 -> {
                // Device rotated 180° (upside down)
                // Red Corner bytecode (002e-0030):
                // v11 = 1f - f; v12 = 1f - f2
                f = 1.0f - f
                f2 = 1.0f - f2
            }
            ROTATION_270 -> {
                // Device rotated 270° counterclockwise (top is on the right - standard landscape gaming)
                // Red Corner bytecode (002a-002c):
                // v7 = 1f - f2; v12 = f; v11 = 1f - f2
                val oldF = f
                f = 1.0f - f2
                f2 = oldF
            }
            // ROTATION_0: Portrait, f and f2 remain unchanged
        }

        // 1:1 Red Corner letterbox and aspect ratio scaling
        val physW = d.toFloat()
        val physH = e.toFloat()
        val w = b.toFloat()
        val h = c.toFloat()

        val minScale = minOf(physW / w, physH / h)
        val offsetX = (physW - (w * minScale)) / 2.0f
        val offsetY = (physH - (h * minScale)) / 2.0f

        val outX = (((f * physW) - offsetX) / minScale).coerceIn(0f, w - 1f)
        val outY = (((f2 * physH) - offsetY) / minScale).coerceIn(0f, h - 1f)

        val buf = localBuffer.get() ?: FloatArray(2).also { localBuffer.set(it) }
        buf[0] = outX
        buf[1] = outY
        return buf
    }
}
