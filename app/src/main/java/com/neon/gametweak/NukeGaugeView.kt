package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Lightweight animated 250-degree telemetry speedometer. */
class NukeGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val arc = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(70, 140, 180, 170)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(53, 201, 155)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(125, 154, 145)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private var displayed = 0f
    private var animator: ValueAnimator? = null
    var label: String = "LOAD"
        set(value) { field = value; invalidate() }

    fun setValue(value: Int, animated: Boolean = true) {
        val target = value.coerceIn(0, 100).toFloat()
        animator?.cancel()
        if (!animated) {
            displayed = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayed, target).apply {
            duration = 520L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 1f) return
        val cx = width / 2f
        val cy = height / 2f + size * 0.04f
        val radius = size * 0.36f
        val stroke = size * 0.055f
        trackPaint.strokeWidth = stroke
        valuePaint.strokeWidth = stroke
        needlePaint.strokeWidth = size * 0.018f
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val start = 145f
        val sweep = 250f
        canvas.drawArc(arc, start, sweep, false, trackPaint)
        val used = sweep * (displayed / 100f)
        canvas.drawArc(arc, start, used, false, valuePaint)

        // Segmentation gives the dial a cockpit/instrument feel.
        val tickOuter = radius + stroke * 0.9f
        val tickInner = radius + stroke * 0.25f
        val tickPaint = trackPaint
        tickPaint.strokeWidth = maxOf(1f * density, size * 0.009f)
        repeat(11) { i ->
            val deg = Math.toRadians((start + sweep * i / 10f).toDouble())
            val x1 = cx + cos(deg).toFloat() * tickInner
            val y1 = cy + sin(deg).toFloat() * tickInner
            val x2 = cx + cos(deg).toFloat() * tickOuter
            val y2 = cy + sin(deg).toFloat() * tickOuter
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        val needleDeg = Math.toRadians((start + used).toDouble())
        canvas.drawLine(
            cx,
            cy,
            cx + cos(needleDeg).toFloat() * radius * 0.78f,
            cy + sin(needleDeg).toFloat() * radius * 0.78f,
            needlePaint,
        )
        canvas.drawCircle(cx, cy, size * 0.028f, valuePaint.apply { style = Paint.Style.FILL })
        valuePaint.style = Paint.Style.STROKE

        textPaint.textSize = size * 0.19f
        canvas.drawText("${displayed.toInt()}%", cx, cy + size * 0.23f, textPaint)
        labelPaint.textSize = size * 0.085f
        canvas.drawText(label, cx, cy + size * 0.34f, labelPaint)
    }
}
