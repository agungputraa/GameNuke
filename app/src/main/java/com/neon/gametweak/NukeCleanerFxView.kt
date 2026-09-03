package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.sin

/** Small rocket/particle animation shown only while the real cleaner is running. */
class NukeCleanerFxView @JvmOverloads constructor(
    context:Context, attrs:AttributeSet?=null, defStyleAttr:Int=0,
):View(context,attrs,defStyleAttr){
    private val d=resources.displayMetrics.density
    private val rocket=Path(); private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase=0f; private var running=false; private var animator:ValueAnimator?=null
    init { visibility = INVISIBLE }
    fun startCleaning(){
        if(running)return;running=true;visibility=VISIBLE
        animator=ValueAnimator.ofFloat(0f,1f).apply{duration=920L;repeatCount=ValueAnimator.INFINITE;interpolator=AccelerateDecelerateInterpolator();addUpdateListener{phase=it.animatedValue as Float;invalidate()};start()}
    }
    fun stopCleaning(){running=false;animator?.cancel();animator=null;phase=0f;visibility=INVISIBLE;invalidate()}
    override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        super.onDraw(c);if(!running)return;val w=width.toFloat();val h=height.toFloat();if(w<2||h<2)return
        val y=h*.72f-h*.44f*phase;val x=w*.5f+sin(phase*6.28f)*w*.03f;val s=11f*d
        p.style=Paint.Style.FILL;p.color=NukeHudPalette.Green
        rocket.rewind();rocket.moveTo(x,y-s);rocket.lineTo(x+s*.62f,y+s*.35f);rocket.lineTo(x,y+s*.1f);rocket.lineTo(x-s*.62f,y+s*.35f);rocket.close();c.drawPath(rocket,p)
        p.color=NukeHudPalette.Cyan;c.drawCircle(x,y+s*.48f,3.2f*d*(1f-phase*.45f),p)
        p.color=android.graphics.Color.argb(150, android.graphics.Color.red(NukeHudPalette.Violet), android.graphics.Color.green(NukeHudPalette.Violet), android.graphics.Color.blue(NukeHudPalette.Violet))
        for(i in 0..4){val py=y+s*.7f+i*5f*d+phase*7f*d;c.drawCircle(x+(i-2)*3.2f*d,py,1.3f*d,p)}
    }
}
