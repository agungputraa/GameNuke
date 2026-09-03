package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

class NukeSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val samples = ArrayDeque<Float>(48)
    private val path = Path()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Color.argb(45, 0, 230, 168)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0, 230, 168)
    }

    fun push(value: Int) {
        if (samples.size >= 48) samples.removeFirst()
        samples.addLast(value.coerceIn(0, 100).toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 1f || h <= 1f) return
        canvas.drawLine(0f, h * .25f, w, h * .25f, gridPaint)
        canvas.drawLine(0f, h * .5f, w, h * .5f, gridPaint)
        canvas.drawLine(0f, h * .75f, w, h * .75f, gridPaint)
        if (samples.size < 2) return
        val step = w / (samples.size - 1).coerceAtLeast(1)
        path.rewind()
        samples.forEachIndexed { index, sample ->
            val x = step * index
            val y = h - (sample / 100f) * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
