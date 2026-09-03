package com.neon.gametweak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Universal Accessibility Gesture Engine for Game Nuke Macro.
 * Serves as the high-compatibility fallback engine when Shizuku/ADB is not active,
 * and coordinates gesture dispatch for gaming automation.
 */
class NukeMacroService : AccessibilityService() {

    companion object {
        private const val TAG = "NukeMacroService"

        @Volatile
        var instance: NukeMacroService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        fun isAccessibilityPermissionGranted(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${NukeMacroService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedServiceName)
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Dispatch a low-latency simulated tap at (x, y) coordinates.
         */
        fun performTap(x: Float, y: Float, durationMs: Long = 20L, onComplete: ((Boolean) -> Unit)? = null): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(10L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            }, null)
        }

        /**
         * Dispatch a smooth simulated swipe gesture from (startX, startY) to (endX, endY).
         */
        fun performSwipe(
            startX: Float, startY: Float,
            endX: Float, endY: Float,
            durationMs: Long = 120L,
            onComplete: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(20L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            }, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Nuke Macro Accessibility Engine connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener; actions are dispatched on-demand via controller
    }

    override fun onInterrupt() {
        Log.w(TAG, "Nuke Macro Accessibility Engine interrupted")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
        Log.i(TAG, "Nuke Macro Accessibility Engine destroyed")
    }
}
