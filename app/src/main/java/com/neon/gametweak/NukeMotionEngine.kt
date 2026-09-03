package com.neon.gametweak

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/**
 * Physics motion for compact HUD surfaces.
 * Existing springs are reused/cancelled per View+property so rapid taps cannot stack animators or
 * cause visible jitter. The map is weak and therefore never owns an overlay View after detach.
 */
object NukeMotionEngine {
    private val running = WeakHashMap<View, MutableMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    fun reveal(view: View, fromScale: Float = .92f, fromY: Float = 18f) {
        cancel(view)
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.translationY = fromY
        view.animate().alpha(1f).setDuration(120L).start()
        spring(view, DynamicAnimation.SCALE_X, 1f, 520f, .70f)
        spring(view, DynamicAnimation.SCALE_Y, 1f, 520f, .70f)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, 560f, .74f)
    }

    fun press(view: View, pressed: Boolean) {
        val target = if (pressed) .962f else 1f
        val stiffness = if (pressed) 780f else 540f
        spring(view, DynamicAnimation.SCALE_X, target, stiffness, .78f)
        spring(view, DynamicAnimation.SCALE_Y, target, stiffness, .78f)
    }

    fun settle(view: View) {
        spring(view, DynamicAnimation.SCALE_X, 1f, 480f, .80f)
        spring(view, DynamicAnimation.SCALE_Y, 1f, 480f, .80f)
    }

    fun pop(view: View) {
        view.scaleX = .90f
        view.scaleY = .90f
        spring(view, DynamicAnimation.SCALE_X, 1f, 650f, .62f)
        spring(view, DynamicAnimation.SCALE_Y, 1f, 650f, .62f)
    }

    fun cancel(view: View) {
        synchronized(running) { running.remove(view)?.values?.forEach { it.cancel() } }
    }

    private fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        position: Float,
        stiffness: Float,
        damping: Float,
    ) {
        val animation = synchronized(running) {
            val perView = running.getOrPut(view) { mutableMapOf() }
            perView.remove(property)?.cancel()
            SpringAnimation(view, property).also { perView[property] = it }
        }
        animation.spring = SpringForce(position).apply {
            this.stiffness = stiffness
            dampingRatio = damping
        }
        animation.addEndListener { _, _, _, _ ->
            synchronized(running) {
                val perView = running[view]
                if (perView?.get(property) === animation) {
                    perView.remove(property)
                    if (perView.isEmpty()) running.remove(view)
                }
            }
        }
        animation.start()
    }
}
