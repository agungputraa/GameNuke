package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/** Plain circular edge bubble: one surface, one border and the particle mark supplied by its child. */
class NukeEdgeRailLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
): FrameLayout(context, attrs, defStyleAttr) {
    private val d: Float
        get() = (resources.configuration.densityDpi / 160f).takeIf { it > 0f } ?: resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = NukeHudPalette.Green }
    init { setWillNotDraw(false) }
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){
        super.onSizeChanged(w,h,ow,oh)
        if(w<=1||h<=1){ fill.shader = null; return }
        val radius = minOf(w,h)*.5f
        fill.shader = RadialGradient(
            w*.42f,
            h*.38f,
            radius,
            // Android requires one position for every color. Keep the intended three-stop
            // falloff instead of constructing a two-color/three-position gradient at runtime.
            intArrayOf(NukeHudPalette.PanelRaised, NukeHudPalette.Panel, NukeHudPalette.Void),
            floatArrayOf(0f,.72f,1f),
            Shader.TileMode.CLAMP,
        )
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        stroke.strokeWidth = 1.1f * d
        val radius = (minOf(width,height)*.5f - 1.8f*d).coerceAtLeast(1f)
        canvas.drawCircle(width*.5f,height*.5f,radius,fill)
        canvas.drawCircle(width*.5f,height*.5f,radius,stroke)
    }
}
