package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Tiny ambient energy sweep used as a contained motion accent.
 * Only this 2-4dp rail redraws, so the full HUD never enters an infinite render loop.
 */
class NukeEnergyRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(61, 83, 245, 138)
        strokeWidth = resources.displayMetrics.density
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cy = height * 0.5f
        canvas.drawLine(0f, cy, width.toFloat(), cy, basePaint)
        val seg = width * 0.16f
        val center = (-seg + progress * (width + seg * 2f))
        val left = center - seg
        val right = center + seg
        sweepPaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(
                android.graphics.Color.argb(0, 83, 245, 138),
                android.graphics.Color.argb(199, 56, 217, 209),
                android.graphics.Color.argb(235, 83, 245, 138),
                android.graphics.Color.argb(0, 83, 245, 138),
            ),
            floatArrayOf(0f, 0.35f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        sweepPaint.strokeWidth = (resources.displayMetrics.density * 1.6f).coerceAtLeast(1f)
        canvas.drawLine(left, cy, right, cy, sweepPaint)
        sweepPaint.shader = null
    }
}
