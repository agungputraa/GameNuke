package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.ceil

/** Vertical segmented premium meter with a green -> cyan -> blue -> violet accent ladder. */
class NukeSegmentMeterView @JvmOverloads constructor(context:Context, attrs:AttributeSet?=null, defStyleAttr:Int=0): View(context,attrs,defStyleAttr){
    private val d=resources.displayMetrics.density
    private val off=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(55,87,128,107)}
    private val colors=intArrayOf(Color.rgb(102,255,145),Color.rgb(68,236,146),Color.rgb(49,210,194),Color.rgb(62,132,244),Color.rgb(131,74,235))
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown=0f; private var anim:ValueAnimator?=null
    fun setValue(v:Int, animated:Boolean=true){val target=v.coerceIn(0,100).toFloat();anim?.cancel(); if(!animated){shown=target;invalidate();return};anim=ValueAnimator.ofFloat(shown,target).apply{duration=520;interpolator=PathInterpolator(.16f,.86f,.25f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){super.onDraw(c); val count=8; val gap=3.5f*d; val bh=(height-gap*(count-1))/count; val active=ceil(count*(shown/100f)).toInt(); for(i in 0 until count){val y=height-(i+1)*bh-i*gap; p.color= if(i<active) colors[(i*colors.size/count).coerceIn(0,colors.lastIndex)] else off.color; c.save(); c.skew(-.10f,0f); c.drawRect(3f*d,y,width-3f*d,y+bh,p); c.restore()}}
}
