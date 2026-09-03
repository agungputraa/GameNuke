package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Lightweight non-touchable tactical crosshair drawn above the game. */
class NukeCrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class Style { DOT, CROSS, CIRCLE, TACTIC }

    private val density: Float
        get() {
            val confDpi = resources.configuration.densityDpi
            if (confDpi > 0) return confDpi / 160f
            return resources.displayMetrics.density.coerceAtLeast(1f)
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(180, 0, 0, 0)
    }

    var enabledCrosshair: Boolean = false
        set(value) { field = value; visibility = if (value) VISIBLE else GONE; invalidate() }
    var style: Style = Style.CROSS
        set(value) { field = value; invalidate() }
    var crosshairColor: Int = Color.rgb(46, 220, 165)
        set(value) { field = value; invalidate() }
    var sizeDp: Float = 22f
        set(value) { field = value.coerceIn(8f, 64f); invalidate() }
    var gapDp: Float = 6f
        set(value) { field = value.coerceIn(0f, 28f); invalidate() }
    var thicknessDp: Float = 1.8f
        set(value) { field = value.coerceIn(1f, 6f); invalidate() }
    var opacity: Float = .95f
        set(value) { field = value.coerceIn(.2f, 1f); invalidate() }
    var offsetXPx: Float = 0f
        set(value) { field = value.coerceIn(-600f, 600f); invalidate() }
    var offsetYPx: Float = 0f
        set(value) { field = value.coerceIn(-600f, 600f); invalidate() }
    var centerDot: Boolean = true
        set(value) { field = value; invalidate() }
    var outline: Boolean = true
        set(value) { field = value; invalidate() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabledCrosshair || width <= 0 || height <= 0) return
        val cx = width / 2f + offsetXPx
        val cy = height / 2f + offsetYPx
        val half = sizeDp * density
        val gap = gapDp * density
        val stroke = thicknessDp * density
        paint.color = crosshairColor
        paint.alpha = (255f * opacity).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = stroke + max(1.4f * density, stroke * .9f)
        outlinePaint.alpha = (190f * opacity).toInt().coerceIn(0, 210)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (outline) canvas.drawLine(x1, y1, x2, y2, outlinePaint)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        fun circle(radius: Float) {
            if (outline) canvas.drawCircle(cx, cy, radius, outlinePaint)
            canvas.drawCircle(cx, cy, radius, paint)
        }

        when (style) {
            Style.DOT -> Unit
            Style.CROSS -> {
                line(cx - half, cy, cx - gap, cy)
                line(cx + gap, cy, cx + half, cy)
                line(cx, cy - half, cx, cy - gap)
                line(cx, cy + gap, cx, cy + half)
            }
            Style.CIRCLE -> circle((half * .62f).coerceAtLeast(3f * density))
            Style.TACTIC -> {
                val r = (half * .64f).coerceAtLeast(5f * density)
                circle(r)
                line(cx - half, cy, cx - r - gap * .25f, cy)
                line(cx + r + gap * .25f, cy, cx + half, cy)
                line(cx, cy - half, cx, cy - r - gap * .25f)
                line(cx, cy + r + gap * .25f, cx, cy + half)
            }
        }

        if (centerDot || style == Style.DOT) {
            paint.style = Paint.Style.FILL
            outlinePaint.style = Paint.Style.FILL
            val radius = max(1.7f * density, stroke * .85f)
            if (outline) canvas.drawCircle(cx, cy, radius + 1.2f * density, outlinePaint)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
