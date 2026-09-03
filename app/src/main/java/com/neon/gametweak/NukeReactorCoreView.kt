package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** Compact particle/atom reactor mark shared by the circular edge handle and cockpit. */
class NukeReactorCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class Status { OFFLINE, DEGRADED, ONLINE }

    private val d: Float
        get() = (resources.configuration.densityDpi / 160f).takeIf { it > 0f } ?: resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var pulse = .34f
    private var animator: ValueAnimator? = null
    private var status = Status.OFFLINE

    fun setStatus(value: Status) {
        if (status == value) return
        status = value
        when (value) {
            Status.ONLINE -> ignite()
            Status.DEGRADED -> ignite(2_600L)
            Status.OFFLINE -> extinguish()
        }
        alpha = if (value == Status.OFFLINE) .58f else 1f
        invalidate()
    }

    fun ignite(durationMs: Long = 1_900L) {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(.20f, 1f, .20f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun extinguish() {
        animator?.cancel()
        animator = null
        pulse = .24f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        val accent = when (status) {
            Status.ONLINE -> NukeHudPalette.Green
            Status.DEGRADED -> NukeHudPalette.Amber
            Status.OFFLINE -> NukeHudPalette.MutedDeep
        }

        edge.color = withAlpha(if (status == Status.ONLINE) NukeHudPalette.Cyan else accent, (120 + 100 * pulse).toInt())
        edge.strokeWidth = 1.05f * d
        val orbit = RectF(w * .19f, h * .37f, w * .81f, h * .63f)
        listOf(0f, 60f, 120f).forEach { degrees ->
            c.save()
            c.rotate(degrees, w * .5f, h * .5f)
            c.drawOval(orbit, edge)
            c.restore()
        }
        fill.color = withAlpha(accent, (165 + 90 * pulse).toInt())
        c.drawCircle(w * .5f, h * .5f, min(w, h) * .085f, fill)
        c.drawCircle(w * .80f, h * .50f, min(w, h) * .042f, fill)
        edge.strokeWidth = 1f * d
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
