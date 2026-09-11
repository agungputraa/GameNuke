package frb.axeron.server.touch

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * High-level coordinator bridging libtouch.so with NukeTouchInjector and DisplayTransform.
 * Runs inside NukeShellDaemon under UID 2000 (shell) with GID 1004 (input).
 *
 * Implements Red Corner's exact one-shot grab safety net (GRAB_GRACE_MS = 3000ms).
 */
object NukeTouchService {
    private const val TAG = "NukeTouchService"
    private const val GRAB_GRACE_MS = 3000L

    private val displayTransform = DisplayTransform()
    private val injector = NukeTouchInjector()

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

    fun start(libPath: String? = null): Int {
        if (listening) return 1
        val touch = TouchListener.INSTANCE
        if (!touch.isLoaded) {
            var loaded = false
            if (!libPath.isNullOrBlank()) {
                loaded = touch.load(libPath)
            }
            if (!loaded) {
                loaded = touch.loadLibrary("touch")
            }
            if (!loaded) {
                // Check standard fallback paths on device
                val fallbacks = listOf(
                    "/data/local/tmp/libtouch.so",
                    "/system/lib64/libtouch.so"
                )
                for (fb in fallbacks) {
                    if (touch.load(fb)) {
                        loaded = true
                        break
                    }
                }
            }
            if (!loaded) {
                Log.e(TAG, "Cannot start touch service: libtouch.so could not be loaded")
                return -1
            }
        }

        displayTransform.start()
        injector.start()

        TouchListener.setSink { rawEvent ->
            eventReceivedSinceGrab = true
            if (rawEvent.action == TouchEvent.ACTION_FRAME) {
                injector.onFrame()
            } else {
                val px = displayTransform.toDisplayPixelsF(rawEvent.normX, rawEvent.normY)
                if (px != null) {
                    rawEvent.dispX = px[0].toInt()
                    rawEvent.dispY = px[1].toInt()
                    rawEvent.dispXf = px[0]
                    rawEvent.dispYf = px[1]
                    rawEvent.displayWidth = displayTransform.snapshot.width
                    rawEvent.displayHeight = displayTransform.snapshot.height
                    rawEvent.rotation = displayTransform.snapshot.rotation
                    injector.onSample(rawEvent)
                }
            }
        }

        val res = touch.nativeStart()
        if (res >= 0) {
            listening = true
            eventReceivedSinceGrab = false

            // CRITICAL SAFETY GUARD:
            // Only enable hardware grab IF injector is verified and ready!
            if (injector.isInjectionReady()) {
                touch.nativeSetGrab(true)
                grabActive = true
                Log.i(TAG, "Touch listener started and grab enabled for $res device(s)")

                // Schedule Red Corner exact one-shot safety net:
                // If after 3000ms NOT A SINGLE hardware event was ever received, release grab.
                // Once events are received, grab stays active for seamless in-game sensitivity.
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

    fun stop() {
        if (!listening) return
        grabSafetyTimer?.cancel(true)
        grabSafetyTimer = null

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

    /**
     * Critical fail-safe: immediately drops hardware grab and restores native touch
     * if any injection error occurs. Prevents any possibility of screen freeze.
     */
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
}
