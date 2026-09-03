package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Compact animated gauge used for real CPU/RAM values. */
class NukeSpeedometerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet?=null, defStyleAttr:Int=0,
): View(context,attrs,defStyleAttr){
    private val d=resources.displayMetrics.density
    private var value=0f
    private var displayValue=0f
    private var label="CPU"
    private var available=false
    private val arc=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private val tick=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1f*d}
    private val needle=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeWidth=2f*d;color=NukeHudPalette.Text}
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD)}
    private var animator:ValueAnimator?=null
    fun setLabel(v:String){label=v;invalidate()}
    fun setValue(v:Int){setValue(v.toFloat())}
    fun setValue(v:Float){
        available=true
        value=v.coerceIn(0f,100f); animator?.cancel(); animator=ValueAnimator.ofFloat(displayValue,value).apply{
            duration=360L; interpolator=DecelerateInterpolator(); addUpdateListener{displayValue=it.animatedValue as Float;invalidate()};start()
        }
    }
    fun setUnavailable(){
        available=false
        animator?.cancel(); animator=null
        invalidate()
    }
    override fun onDetachedFromWindow(){ animator?.cancel(); animator=null; super.onDetachedFromWindow() }
    override fun onDraw(c:Canvas){
        super.onDraw(c); val w=width.toFloat();val h=height.toFloat();if(w<2f||h<2f)return
        val r=min(w,h)*.38f; val cx=w/2f; val cy=h*.54f; val box=RectF(cx-r,cy-r,cx+r,cy+r)
        arc.strokeWidth=5f*d; arc.color=android.graphics.Color.argb(100,63,86,72);c.drawArc(box,145f,250f,false,arc)
        arc.color=when{displayValue>=88f->NukeHudPalette.Danger;displayValue>=72f->NukeHudPalette.Amber;else->NukeHudPalette.Green}
        if(available) c.drawArc(box,145f,250f*(displayValue/100f),false,arc)
        for(i in 0..10){
            val a=Math.toRadians((145f+25f*i).toDouble()); val inR=r*.82f; val outR=r*.94f
            tick.color=if(available && i*10<=displayValue)android.graphics.Color.argb(190,83,245,138) else android.graphics.Color.argb(80,109,134,119)
            c.drawLine(cx+cos(a).toFloat()*inR,cy+sin(a).toFloat()*inR,cx+cos(a).toFloat()*outR,cy+sin(a).toFloat()*outR,tick)
        }
        if(available){
            val angle=Math.toRadians((145f+250f*(displayValue/100f)).toDouble());
            c.drawLine(cx,cy,cx+cos(angle).toFloat()*r*.64f,cy+sin(angle).toFloat()*r*.64f,needle)
        }
        text.color=NukeHudPalette.Text;text.textSize=9f*d;c.drawText(label,cx,cy-r*.18f,text)
        text.color=if(available)arc.color else NukeHudPalette.Muted;text.textSize=15f*d;c.drawText(if(available)"${displayValue.toInt()}%" else "--",cx,cy+r*.35f,text)
    }
}
