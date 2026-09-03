package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/** Compact Canvas chart for read-only per-core clock telemetry. */
class NukeCpuCoreChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.PanelSoft }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.Cyan }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NukeHudPalette.Text
        textSize = 8.5f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NukeHudPalette.Green
        textSize = 8f * density
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NukeHudPalette.Muted
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
    }
    private var clocks: List<NukeGamingShellGateway.CpuClock> = emptyList()

    fun submit(rows: List<NukeGamingShellGateway.CpuClock>) {
        clocks = rows.take(16)
        contentDescription = if (clocks.isEmpty()) {
            "CPU core clock telemetry unavailable"
        } else {
            clocks.joinToString { "CPU${it.core} ${it.mhz} megahertz" }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (clocks.isEmpty()) {
            canvas.drawText("CORE CLOCK DATA UNAVAILABLE", width / 2f, height / 2f, empty)
            return
        }

        val columns = if (width >= 420f * density) 4 else 2
        val rowCount = max(1, ceil(clocks.size / columns.toFloat()).toInt())
        val gap = 9f * density
        val cellWidth = (width - gap * (columns - 1)) / columns
        val cellHeight = height / rowCount.toFloat()
        val maxKhz = clocks.maxOfOrNull { it.khz }?.coerceAtLeast(1L) ?: 1L
        val barHeight = 5f * density
        val radius = 2.5f * density

        clocks.forEachIndexed { index, clock ->
            val column = index % columns
            val row = index / columns
            val left = column * (cellWidth + gap)
            val top = row * cellHeight
            val textBaseline = top + 13f * density
            canvas.drawText("CPU${clock.core}", left, textBaseline, label)
            canvas.drawText("${clock.mhz} MHz", left + cellWidth, textBaseline, value)

            val trackTop = top + 19f * density
            val trackRect = RectF(left, trackTop, left + cellWidth, trackTop + barHeight)
            canvas.drawRoundRect(trackRect, radius, radius, track)
            val progress = (clock.khz.toFloat() / maxKhz.toFloat()).coerceIn(.04f, 1f)
            val activeRect = RectF(left, trackTop, left + cellWidth * progress, trackTop + barHeight)
            active.color = if (progress >= .88f) NukeHudPalette.Green else NukeHudPalette.Cyan
            canvas.drawRoundRect(activeRect, radius, radius, active)
        }
    }
}
