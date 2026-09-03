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
 * Completely rebuilt fixed cockpit chrome.
 *
 * It intentionally avoids a single Game-Corner-like silhouette. Game Nuke uses a nuclear
 * containment language: central reactor cradle, asymmetric cut rails, vent fins, and a separated
 * command deck. Only small child views animate; this frame itself is static for low HUD overhead.
 */
class NukeMainCockpitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val shell = Path()
    private val inner = Path()
    private val bridge = Path()
    private val deck = Path()
    private val leftBay = Path()
    private val rightBay = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val raised = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = NukeHudPalette.PanelRaised }
    private val bay = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = NukeHudPalette.Panel }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.0f * d; color = android.graphics.Color.argb(190, 83, 245, 138)
    }
    private val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .58f * d; color = android.graphics.Color.argb(115, 71, 138, 101)
    }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.Green }
    private val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.Cyan }
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.Blue }
    private val violet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NukeHudPalette.Violet }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w.toFloat(), h.toFloat())
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                android.graphics.Color.rgb(10, 25, 18),
                NukeHudPalette.Void,
                android.graphics.Color.rgb(5, 14, 10),
            ),
            floatArrayOf(0f, .48f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun rebuild(w: Float, h: Float) {
        shell.rewind(); inner.rewind(); bridge.rewind(); deck.rewind(); leftBay.rewind(); rightBay.rewind()
        if (w <= 2f || h <= 2f) return
        val c = min(w, h) * .036f
        val bodyTop = h * .18f
        val deckTop = h * .74f
        val reactorGap = w * .071f

        // Nuclear containment silhouette: long low profile, split reactor crown, off-axis corner cuts.
        shell.moveTo(c * 1.3f, h * .07f)
        shell.lineTo(w * .29f, h * .07f)
        shell.lineTo(w * .335f, h * .015f)
        shell.lineTo(w * .5f - reactorGap, h * .015f)
        shell.lineTo(w * .5f - reactorGap * .72f, 0f)
        shell.lineTo(w * .5f + reactorGap * .72f, 0f)
        shell.lineTo(w * .5f + reactorGap, h * .015f)
        shell.lineTo(w * .67f, h * .015f)
        shell.lineTo(w * .715f, h * .07f)
        shell.lineTo(w - c * 1.6f, h * .07f)
        shell.lineTo(w, h * .145f)
        shell.lineTo(w - c * .38f, deckTop - c * .25f)
        shell.lineTo(w, deckTop + c * .5f)
        shell.lineTo(w - c * 1.45f, h - c * .28f)
        shell.lineTo(c * 1.55f, h - c * .28f)
        shell.lineTo(0f, deckTop + c * .62f)
        shell.lineTo(c * .42f, deckTop - c * .22f)
        shell.lineTo(0f, h * .15f)
        shell.close()

        bridge.moveTo(c * 2f, h * .078f)
        bridge.lineTo(w * .31f, h * .078f)
        bridge.lineTo(w * .35f, h * .035f)
        bridge.lineTo(w * .65f, h * .035f)
        bridge.lineTo(w * .69f, h * .078f)
        bridge.lineTo(w - c * 2f, h * .078f)
        bridge.lineTo(w - c * 1.1f, bodyTop)
        bridge.lineTo(c * 1.1f, bodyTop)
        bridge.close()

        leftBay.moveTo(c * .9f, bodyTop)
        leftBay.lineTo(w * .29f, bodyTop)
        leftBay.lineTo(w * .315f, bodyTop + h * .055f)
        leftBay.lineTo(w * .298f, deckTop - h * .025f)
        leftBay.lineTo(c * 1.45f, deckTop)
        leftBay.lineTo(c * .52f, deckTop - h * .085f)
        leftBay.close()

        rightBay.moveTo(w - c * .9f, bodyTop)
        rightBay.lineTo(w * .71f, bodyTop)
        rightBay.lineTo(w * .685f, bodyTop + h * .055f)
        rightBay.lineTo(w * .702f, deckTop - h * .025f)
        rightBay.lineTo(w - c * 1.45f, deckTop)
        rightBay.lineTo(w - c * .52f, deckTop - h * .085f)
        rightBay.close()

        deck.moveTo(c * 1.2f, deckTop)
        deck.lineTo(w - c * 1.2f, deckTop)
        deck.lineTo(w - c * .28f, deckTop + h * .075f)
        deck.lineTo(w - c * 1.8f, h - c * .35f)
        deck.lineTo(c * 1.8f, h - c * .35f)
        deck.lineTo(c * .28f, deckTop + h * .075f)
        deck.close()

        inner.moveTo(c * 1.7f, bodyTop + 2f * d)
        inner.lineTo(w * .29f, bodyTop + 2f * d)
        inner.moveTo(w * .71f, bodyTop + 2f * d)
        inner.lineTo(w - c * 1.7f, bodyTop + 2f * d)
        inner.moveTo(c * 2.0f, deckTop - 1.5f * d)
        inner.lineTo(w - c * 2.0f, deckTop - 1.5f * d)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 2 || height <= 2) return
        canvas.drawPath(shell, paint)
        canvas.drawPath(shell, stroke)
        canvas.drawPath(bridge, raised)
        canvas.drawPath(bridge, fine)
        canvas.drawPath(leftBay, bay)
        canvas.drawPath(leftBay, fine)
        canvas.drawPath(rightBay, bay)
        canvas.drawPath(rightBay, fine)
        canvas.drawPath(deck, bay)
        canvas.drawPath(deck, stroke)
        canvas.drawPath(inner, fine)

        // Side energy fins. Green remains dominant while cyan/blue/violet are small telemetry cues.
        val p = arrayOf(green, green, cyan, blue, violet)
        val y0 = height * .39f
        val segH = height * .031f
        val xL = 10f * d
        val xR = width - 15f * d
        p.forEachIndexed { i, color ->
            val y = y0 + i * (segH + 2.6f * d)
            canvas.save(); canvas.rotate(-9f, xL, y)
            canvas.drawRoundRect(xL, y, xL + 5.2f * d, y + segH, 1.2f * d, 1.2f * d, color)
            canvas.restore()
            canvas.save(); canvas.rotate(9f, xR, y)
            canvas.drawRoundRect(xR, y, xR + 5.2f * d, y + segH, 1.2f * d, 1.2f * d, color)
            canvas.restore()
        }
    }
}
