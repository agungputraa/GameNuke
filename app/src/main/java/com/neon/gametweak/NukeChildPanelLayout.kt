package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Lightweight shared chrome for detachable child panels.
 * Static Canvas only: layered reactor chassis, sparse circuitry and emerald energy rails.
 */
class NukeChildPanelLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val shell = Path()
    private val inner = Path()
    private val topRail = Path()
    private val trace = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val raised = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = NukeHudPalette.PanelRaised }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .95f * d; color = NukeHudPalette.Green
    }
    private val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .55f * d; color = NukeHudPalette.OutlineBright
    }
    private val energy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.35f * d; color = NukeHudPalette.Green
        strokeCap = Paint.Cap.SQUARE
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .65f * d
        color = android.graphics.Color.argb(80, 85, 245, 176)
    }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = NukeHudPalette.Green }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .45f*d
        color = android.graphics.Color.argb(16, 85, 245, 176)
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w.toFloat(), h.toFloat())
        if (w <= 1 || h <= 1) { fill.shader = null; return }
        fill.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                android.graphics.Color.rgb(3, 10, 7),
                NukeHudPalette.Panel,
                android.graphics.Color.rgb(2, 7, 5)
            ),
            floatArrayOf(0f, .52f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun rebuild(w: Float, h: Float) {
        shell.rewind(); inner.rewind(); topRail.rewind(); trace.rewind()
        if (w < 3f || h < 3f) return
        val c = min(w, h) * .032f
        // Asymmetric esports silhouette with top command-notch and clipped lower corners.
        shell.moveTo(c * 1.2f, 0f)
        shell.lineTo(w * .38f, 0f)
        shell.lineTo(w * .42f, c * .62f)
        shell.lineTo(w * .76f, c * .62f)
        shell.lineTo(w * .80f, 0f)
        shell.lineTo(w - c * 1.45f, 0f)
        shell.lineTo(w, c * 1.4f)
        shell.lineTo(w, h - c * 1.35f)
        shell.lineTo(w - c * 1.15f, h)
        shell.lineTo(c * 1.15f, h)
        shell.lineTo(0f, h - c * 1.15f)
        shell.lineTo(0f, c * 1.3f)
        shell.close()

        val i = c * .72f
        inner.moveTo(i * 1.7f, i)
        inner.lineTo(w * .37f, i)
        inner.moveTo(w * .43f, i + c * .58f)
        inner.lineTo(w * .75f, i + c * .58f)
        inner.moveTo(w * .81f, i)
        inner.lineTo(w - i * 1.7f, i)
        inner.moveTo(i, h - i * 1.4f)
        inner.lineTo(w - i, h - i * 1.4f)

        topRail.moveTo(c * 1.45f, c * .7f)
        topRail.lineTo(w * .31f, c * .7f)
        topRail.moveTo(w * .84f, c * .7f)
        topRail.lineTo(w - c * 1.65f, c * .7f)

        trace.moveTo(c * .95f, h * .30f)
        trace.lineTo(c * 2.1f, h * .30f)
        trace.lineTo(c * 2.8f, h * .35f)
        trace.lineTo(w * .21f, h * .35f)
        trace.moveTo(w - c * .95f, h * .67f)
        trace.lineTo(w - c * 2.1f, h * .67f)
        trace.lineTo(w - c * 2.8f, h * .62f)
        trace.lineTo(w * .79f, h * .62f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 2 || height <= 2) return
        canvas.drawPath(shell, fill)

        // Two quiet inner bays create depth without shadows or blur.
        val bayH = height * .055f
        canvas.drawRect(width * .43f, 2f*d, width * .75f, bayH, raised)
        canvas.drawPath(shell, border)
        canvas.drawPath(inner, fine)
        canvas.drawPath(topRail, energy)
        canvas.drawPath(trace, tracePaint)

        // Sparse grid, only a handful of lines, no frame-by-frame work.
        val step = 32f * d
        var x = step
        while (x < width) {
            canvas.drawLine(x, height * .18f, x, height * .88f, grid)
            x += step
        }
        var y = height * .22f
        while (y < height * .88f) {
            canvas.drawLine(8f*d, y, width - 8f*d, y, grid)
            y += step
        }

        val r = 1.55f * d
        canvas.drawCircle(1.45f*d, height*.20f, r, node)
        canvas.drawCircle(width - 1.45f*d, height*.80f, r, node)
    }
}
