package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Hardware-friendly mechanical chassis for the floating Game Nuke cockpit.
 *
 * 3.5.1 deliberately avoids a permanent software layer, shadow blur and an infinite ValueAnimator.
 * Those effects looked attractive but forced the whole overlay to redraw continuously and could
 * produce severe jank / large software buffers on OEM WindowManager overlays. Geometry is cached
 * on size changes and the service performs a short GPU-backed entrance animation instead.
 */
class NukeHudFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density.coerceAtLeast(1f)
    private val chassis = Path()
    private val innerRail = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(3, 9, 8)
    }
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        color = Color.argb(34, 47, 205, 158)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.argb(188, 53, 201, 155)
    }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = .85f * density
        color = Color.argb(105, 62, 169, 137)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 68, 220, 174)
    }
    private val sideGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 48, 202, 153) }
    private val sideMagenta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 210, 57, 136) }
    private val sideViolet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 115, 78, 220) }

    private var bridgeDepth = 0f
    private var segmentWidth = 0f
    private var segmentHeight = 0f
    private var segmentStartY = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Keep this view hardware accelerated. Never force LAYER_TYPE_SOFTWARE for a WindowManager HUD.
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry(w.toFloat(), h.toFloat())
        fillPaint.shader = if (w > 1 && h > 1) {
            LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                intArrayOf(Color.rgb(9, 22, 17), Color.rgb(3, 8, 7), Color.rgb(6, 17, 13)),
                null,
                Shader.TileMode.CLAMP,
            )
        } else null
    }

    private fun rebuildGeometry(w: Float, h: Float) {
        chassis.rewind()
        innerRail.rewind()
        if (w <= 2f || h <= 2f) return

        val c = max(9f * density, minOf(w, h) * .048f)
        val wing = (c * .78f).coerceAtMost(w * .07f)
        val bridgeHalf = (w * .12f).coerceIn(24f * density, 92f * density)
        bridgeDepth = (c * .55f).coerceAtMost(h * .07f)

        chassis.moveTo(c * 1.5f, 0f)
        chassis.lineTo(w / 2f - bridgeHalf, 0f)
        chassis.lineTo(w / 2f - bridgeHalf * .72f, bridgeDepth)
        chassis.lineTo(w / 2f + bridgeHalf * .72f, bridgeDepth)
        chassis.lineTo(w / 2f + bridgeHalf, 0f)
        chassis.lineTo(w - c * 1.5f, 0f)
        chassis.lineTo(w - c * .45f, c * .62f)
        chassis.lineTo(w, c * 1.25f)
        chassis.lineTo(w, h * .28f)
        chassis.lineTo(w - wing, h * .34f)
        chassis.lineTo(w - wing, h * .66f)
        chassis.lineTo(w, h * .72f)
        chassis.lineTo(w, h - c * 1.18f)
        chassis.lineTo(w - c * .55f, h - c * .55f)
        chassis.lineTo(w - c * 1.55f, h)
        chassis.lineTo(w / 2f + bridgeHalf * .78f, h)
        chassis.lineTo(w / 2f + bridgeHalf * .54f, h - bridgeDepth * .75f)
        chassis.lineTo(w / 2f - bridgeHalf * .54f, h - bridgeDepth * .75f)
        chassis.lineTo(w / 2f - bridgeHalf * .78f, h)
        chassis.lineTo(c * 1.55f, h)
        chassis.lineTo(c * .55f, h - c * .55f)
        chassis.lineTo(0f, h - c * 1.18f)
        chassis.lineTo(0f, h * .72f)
        chassis.lineTo(wing, h * .66f)
        chassis.lineTo(wing, h * .34f)
        chassis.lineTo(0f, h * .28f)
        chassis.lineTo(0f, c * 1.25f)
        chassis.lineTo(c * .45f, c * .62f)
        chassis.close()

        innerRail.moveTo(c * 1.8f, c * .62f)
        innerRail.lineTo(w * .34f, c * .62f)
        innerRail.moveTo(w * .66f, c * .62f)
        innerRail.lineTo(w - c * 1.8f, c * .62f)
        innerRail.moveTo(c * 1.8f, h - c * .62f)
        innerRail.lineTo(w * .38f, h - c * .62f)
        innerRail.moveTo(w * .62f, h - c * .62f)
        innerRail.lineTo(w - c * 1.8f, h - c * .62f)

        segmentWidth = 5.5f * density
        segmentHeight = (h * .075f).coerceIn(5f * density, 11f * density)
        segmentStartY = h * .34f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        // Two inexpensive static strokes give a glow impression without software blur/shadow layers.
        canvas.drawPath(chassis, outerGlowPaint)
        canvas.drawPath(chassis, fillPaint)
        canvas.drawPath(chassis, borderPaint)
        canvas.drawPath(innerRail, railPaint)

        if (h >= 90f * density && w >= 150f * density) {
            val leftX = 2.2f * density
            val rightX = w - 2.2f * density - segmentWidth
            repeat(5) { index ->
                val y = segmentStartY + index * (segmentHeight + 2.2f * density)
                val paint = when (index) {
                    0, 1 -> sideGreen
                    2, 3 -> sideMagenta
                    else -> sideViolet
                }
                canvas.drawRect(leftX, y, leftX + segmentWidth, y + segmentHeight, paint)
                canvas.drawRect(rightX, y, rightX + segmentWidth, y + segmentHeight, paint)
            }
        }

        canvas.drawCircle(w * .5f, bridgeDepth * .55f, 1.8f * density, nodePaint)
    }
}
