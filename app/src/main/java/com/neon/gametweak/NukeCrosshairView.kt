package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * Lightweight non-touchable tactical crosshair drawn above the game.
 * All offsets are physical pixels so 1 px calibration remains exact across density changes.
 */
class NukeCrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Style {
        DOT,
        CLASSIC_CROSS,
        CIRCLE_DOT,
        CHEVRON,
        SNIPER_T,
        BOX_BRACKET,
        DYNAMIC_GAP,
    }

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
        color = Color.BLACK
    }

    var enabledCrosshair: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }
    var style: Style = Style.CLASSIC_CROSS
        set(value) {
            field = value
            invalidate()
        }
    var crosshairColor: Int = Color.rgb(0, 230, 118)
        set(value) {
            field = value
            invalidate()
        }
    var sizeDp: Float = 22f
        set(value) {
            field = value.coerceIn(8f, 64f)
            invalidate()
        }
    var gapDp: Float = 6f
        set(value) {
            field = value.coerceIn(0f, 28f)
            invalidate()
        }
    var thicknessDp: Float = 2f
        set(value) {
            field = value.coerceIn(1f, 6f)
            invalidate()
        }
    var opacity: Float = .95f
        set(value) {
            field = value.coerceIn(.05f, 1f)
            invalidate()
        }
    var offsetXPx: Float = 0f
        set(value) {
            field = value.coerceIn(-1200f, 1200f)
            invalidate()
        }
    var offsetYPx: Float = 0f
        set(value) {
            field = value.coerceIn(-1200f, 1200f)
            invalidate()
        }
    var outline: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabledCrosshair || width <= 0 || height <= 0) return

        val d = density
        val cx = width / 2f + offsetXPx
        val cy = height / 2f + offsetYPx
        val half = sizeDp * d
        val baseGap = gapDp * d
        val stroke = thicknessDp * d
        val dynamicFactor = if (style == Style.DYNAMIC_GAP) {
            val phase = (SystemClock.uptimeMillis() % 900L) / 900f
            .5f + .5f * sin(phase * Math.PI.toFloat() * 2f)
        } else {
            0f
        }
        val gap = if (style == Style.DYNAMIC_GAP) baseGap + dynamicFactor * max(2f * d, half * .16f) else baseGap

        paint.color = crosshairColor
        paint.alpha = (255f * opacity).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke

        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = stroke + max(1.25f * d, stroke * .8f)
        outlinePaint.alpha = (175f * opacity).toInt().coerceIn(0, 205)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (outline) canvas.drawLine(x1, y1, x2, y2, outlinePaint)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        fun circle(radius: Float) {
            if (outline) canvas.drawCircle(cx, cy, radius, outlinePaint)
            canvas.drawCircle(cx, cy, radius, paint)
        }

        fun dot(radiusScale: Float = 1f) {
            paint.style = Paint.Style.FILL
            outlinePaint.style = Paint.Style.FILL
            val radius = max(1.65f * d, stroke * .82f) * radiusScale
            if (outline) canvas.drawCircle(cx, cy, radius + 1.1f * d, outlinePaint)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            outlinePaint.style = Paint.Style.STROKE
        }

        when (style) {
            Style.DOT -> dot(1.15f)

            Style.CLASSIC_CROSS, Style.DYNAMIC_GAP -> {
                line(cx - half, cy, cx - gap, cy)
                line(cx + gap, cy, cx + half, cy)
                line(cx, cy - half, cx, cy - gap)
                line(cx, cy + gap, cx, cy + half)
                dot(.8f)
            }

            Style.CIRCLE_DOT -> {
                circle((half * .62f).coerceAtLeast(4f * d))
                dot(.85f)
            }

            Style.CHEVRON -> {
                val wing = half * .78f
                val top = cy - half * .52f
                val bottom = cy + half * .22f
                line(cx, top, cx - wing, bottom)
                line(cx, top, cx + wing, bottom)
                dot(.72f)
            }

            Style.SNIPER_T -> {
                line(cx - half, cy - gap, cx + half, cy - gap)
                line(cx, cy - gap, cx, cy + half)
                line(cx - half * .55f, cy + half * .58f, cx + half * .55f, cy + half * .58f)
                dot(.65f)
            }

            Style.BOX_BRACKET -> {
                val side = half * .82f
                val arm = half * .38f
                line(cx - side, cy - side, cx - side + arm, cy - side)
                line(cx - side, cy - side, cx - side, cy - side + arm)
                line(cx + side, cy - side, cx + side - arm, cy - side)
                line(cx + side, cy - side, cx + side, cy - side + arm)
                line(cx - side, cy + side, cx - side + arm, cy + side)
                line(cx - side, cy + side, cx - side, cy + side - arm)
                line(cx + side, cy + side, cx + side - arm, cy + side)
                line(cx + side, cy + side, cx + side, cy + side - arm)
                dot(.72f)
            }
        }

        if (style == Style.DYNAMIC_GAP && isShown) postInvalidateOnAnimation()
    }
}
