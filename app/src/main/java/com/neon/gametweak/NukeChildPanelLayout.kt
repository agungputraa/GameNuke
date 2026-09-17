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

/** Detachable child chrome: restrained, layered and intentionally different from the main cockpit. */
class NukeChildPanelLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val shell = Path(); private val inner = Path(); private val rail = Path(); private val accentRail = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .9f*d; color = android.graphics.Color.argb(200, 83, 245, 138)
    }
    private val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = .55f*d; color = android.graphics.Color.argb(105, 57, 134, 92)
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.15f*d; color = android.graphics.Color.argb(215, 56, 217, 209)
        strokeCap = Paint.Cap.SQUARE
    }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = android.graphics.Color.argb(225, 83, 245, 138)
    }
    init { setWillNotDraw(false); clipChildren = false; clipToPadding = false }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        rebuild(w.toFloat(),h.toFloat())
        if (w <= 1 || h <= 1) {
            fill.shader = null
            return
        }
        fill.shader = LinearGradient(0f,0f,w.toFloat(),h.toFloat(), intArrayOf(
            android.graphics.Color.rgb(5,16,11), NukeHudPalette.Void, android.graphics.Color.rgb(7,17,13)
        ), floatArrayOf(0f,.52f,1f), Shader.TileMode.CLAMP)
    }

    private fun rebuild(w:Float,h:Float){
        shell.rewind(); inner.rewind(); rail.rewind(); accentRail.rewind(); if(w<3f||h<3f)return
        val c=min(w,h)*.034f
        shell.moveTo(c,0f); shell.lineTo(w*.63f,0f); shell.lineTo(w*.69f,c*.82f)
        shell.lineTo(w-c*1.55f,c*.82f); shell.lineTo(w,c*2.1f); shell.lineTo(w,h-c*1.1f)
        shell.lineTo(w-c,h); shell.lineTo(c,h); shell.lineTo(0f,h-c); shell.lineTo(0f,c); shell.close()
        inner.moveTo(c*.85f,c*1.35f); inner.lineTo(w-c*1.45f,c*1.35f); inner.lineTo(w-c*.72f,c*1.95f)
        inner.lineTo(w-c*.72f,h-c*1.05f); inner.lineTo(c*.72f,h-c*1.05f); inner.lineTo(c*.72f,c*1.95f); inner.close()
        rail.moveTo(c*1.15f,c*1.06f); rail.lineTo(w*.60f,c*1.06f)
        rail.moveTo(w*.73f,c*1.06f); rail.lineTo(w-c*1.7f,c*1.06f)
        accentRail.moveTo(c*.78f, h*.30f); accentRail.lineTo(c*.78f, h*.50f)
        accentRail.moveTo(w-c*.78f, h*.56f); accentRail.lineTo(w-c*.78f, h*.76f)
        accentRail.moveTo(c*1.2f,h-c*.72f); accentRail.lineTo(w*.28f,h-c*.72f)
        accentRail.moveTo(w*.72f,h-c*.72f); accentRail.lineTo(w-c*1.25f,h-c*.72f)
    }

    override fun onDraw(canvas: Canvas){
        super.onDraw(canvas)
        canvas.drawPath(shell,fill)
        canvas.drawPath(shell,border)
        canvas.drawPath(inner,fine)
        canvas.drawPath(rail,fine)
        canvas.drawPath(accentRail,accent)
        val r = 1.45f*d
        canvas.drawCircle(1.15f*d, height*.20f, r, node)
        canvas.drawCircle(width-1.15f*d, height*.82f, r, node)
    }
}
