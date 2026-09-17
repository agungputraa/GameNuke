package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
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
    private val sweepMatrix = Matrix()
    private var sweepShader: LinearGradient? = null
    private var sweepHalfWidth = 0f
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) {
            sweepShader = null
            sweepPaint.shader = null
            return
        }
        sweepHalfWidth = w * 0.16f
        sweepShader = LinearGradient(
            -sweepHalfWidth, 0f, sweepHalfWidth, 0f,
            intArrayOf(
                android.graphics.Color.argb(0, 83, 245, 138),
                android.graphics.Color.argb(199, 56, 217, 209),
                android.graphics.Color.argb(235, 83, 245, 138),
                android.graphics.Color.argb(0, 83, 245, 138),
            ),
            floatArrayOf(0f, 0.35f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        ).also { sweepPaint.shader = it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cy = height * 0.5f
        canvas.drawLine(0f, cy, width.toFloat(), cy, basePaint)
        val seg = sweepHalfWidth.takeIf { it > 0f } ?: (width * 0.16f)
        val center = -seg + progress * (width + seg * 2f)
        val shader = sweepShader
        if (shader != null) {
            sweepMatrix.reset()
            sweepMatrix.setTranslate(center, 0f)
            shader.setLocalMatrix(sweepMatrix)
        }
        sweepPaint.strokeWidth = (resources.displayMetrics.density * 1.6f).coerceAtLeast(1f)
        canvas.drawLine(center - seg, cy, center + seg, cy, sweepPaint)
    }
}
