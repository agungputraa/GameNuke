package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/** Compact custom icon+label tile. It visually distinguishes instant toggles from child-panel tools. */
class NukeHudToolView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class Interaction { PANEL, TOGGLE, ACTION }

    private val d = resources.displayMetrics.density
    private var icon: Drawable? = null
    private var label = "TOOL"
    private var active = 0f
    private var interaction = Interaction.PANEL
    private var checked = false
    private var supported = true
    private val shell = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = .8f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setIconResource(id: Int) {
        icon = ContextCompat.getDrawable(context, id)?.mutate()
        tintIcon()
        invalidate()
    }

    fun setLabel(v: String) { label = v; contentDescription = v; invalidate() }
    fun setInteraction(v: Interaction) { interaction = v; invalidate() }
    fun setCheckedState(v: Boolean) { checked = v; isActivated = v; invalidate() }
    fun setSupported(v: Boolean) { supported = v; alpha = if (v) 1f else .52f; tintIcon(); invalidate() }

    override fun setActivated(v: Boolean) {
        if (isActivated == v) return
        super.setActivated(v)
        ValueAnimator.ofFloat(active, if (v) 1f else 0f).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener { active = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> NukeMotionEngine.press(this, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NukeMotionEngine.press(this, false)
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 2 || h < 2) return
        val cut = min(7f * d, h * .15f)
        shell.rewind()
        shell.moveTo(cut, 0f); shell.lineTo(w - cut * .5f, 0f); shell.lineTo(w, cut)
        shell.lineTo(w, h - cut * .65f); shell.lineTo(w - cut, h); shell.lineTo(cut * .5f, h)
        shell.lineTo(0f, h - cut); shell.lineTo(0f, cut * .65f); shell.close()
        val on = checked || active > .45f
        fill.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                android.graphics.Color.rgb(if (on) 10 else 7, if (on) 33 else 15, if (on) 21 else 11),
                android.graphics.Color.rgb(3, 9, 7),
            ), null, Shader.TileMode.CLAMP,
        )
        c.drawPath(shell, fill)
        edge.color = when {
            !supported -> android.graphics.Color.argb(70, 125, 135, 128)
            on -> android.graphics.Color.argb(210, 83, 245, 138)
            else -> android.graphics.Color.argb(70, 83, 245, 138)
        }
        c.drawPath(shell, edge)

        if (on && supported) {
            indicator.color = android.graphics.Color.argb(220, 56, 217, 209)
            c.drawRoundRect(RectF(w * .28f, h - 2.7f * d, w * .72f, h - 1.2f * d), d, d, indicator)
        }

        val s = min(16f * d, h * .36f).toInt()
        val cx = (w / 2f).toInt()
        val top = (h * .12f).toInt()
        icon?.setBounds(cx - s / 2, top, cx + s / 2, top + s)
        icon?.alpha = if (supported) 240 else 110
        icon?.draw(c)

        text.textSize = (if (w < 70f * d) 7.0f else 7.8f) * d
        text.color = when {
            !supported -> NukeHudPalette.MutedDeep
            on -> NukeHudPalette.Green
            else -> NukeHudPalette.Text
        }
        val y = h * .76f - (text.ascent() + text.descent()) / 2f
        c.drawText(label, w / 2f, y, text)

        // Interaction grammar: panel = chevron, action = energy spark, toggle = real mini switch.
        when (interaction) {
            Interaction.TOGGLE -> {
                val tw = 15f * d; val th = 7f * d
                val right = w - 4f * d; val top = 4f * d
                val track = RectF(right - tw, top, right, top + th)
                indicator.color = when {
                    !supported -> android.graphics.Color.argb(70, 120, 130, 124)
                    checked -> android.graphics.Color.argb(190, 44, 198, 102)
                    else -> android.graphics.Color.argb(120, 74, 91, 82)
                }
                c.drawRoundRect(track, th / 2f, th / 2f, indicator)
                val knob = th * .42f
                val kx = if (checked) track.right - th * .52f else track.left + th * .52f
                indicator.color = if (supported) NukeHudPalette.Text else NukeHudPalette.MutedDeep
                c.drawCircle(kx, track.centerY(), knob, indicator)
            }
            Interaction.PANEL -> {
                edge.color = if (supported) NukeHudPalette.Cyan else NukeHudPalette.MutedDeep
                edge.strokeWidth = 1.15f * d
                val x = w - 6.5f * d
                val y = 7.5f * d
                c.drawLine(x - 2.4f * d, y - 2.5f * d, x, y, edge)
                c.drawLine(x, y, x - 2.4f * d, y + 2.5f * d, edge)
                edge.strokeWidth = .8f * d
            }
            Interaction.ACTION -> {
                edge.color = if (supported) NukeHudPalette.Amber else NukeHudPalette.MutedDeep
                edge.strokeWidth = 1f * d
                val x = w - 7f * d
                val y = 7f * d
                c.drawLine(x - 3f * d, y, x + 3f * d, y, edge)
                c.drawLine(x, y - 3f * d, x, y + 3f * d, edge)
                c.drawLine(x - 2f * d, y - 2f * d, x + 2f * d, y + 2f * d, edge)
                c.drawLine(x - 2f * d, y + 2f * d, x + 2f * d, y - 2f * d, edge)
                edge.strokeWidth = .8f * d
            }
        }
    }

    private fun tintIcon() {
        icon?.setTint(if (supported) NukeHudPalette.Text else NukeHudPalette.MutedDeep)
    }
}
