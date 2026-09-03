package com.neon.gametweak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** A lightweight local HUD energy sweep. Only this thin layer redraws; the full cockpit stays static. */
class NukeAmbientFxView @JvmOverloads constructor(context: Context, attrs: AttributeSet?=null): View(context, attrs) {
    private val d=resources.displayMetrics.density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=.7f*d }
    private var phase=0f
    private var animator:ValueAnimator?=null
    override fun onAttachedToWindow(){ super.onAttachedToWindow(); if(!isInEditMode) startFx() }
    override fun onDetachedFromWindow(){ animator?.cancel(); animator=null; super.onDetachedFromWindow() }
    private fun startFx(){ if(animator!=null)return; animator=ValueAnimator.ofFloat(0f,1f).apply{
        duration=3200L; repeatCount=ValueAnimator.INFINITE; interpolator=LinearInterpolator()
        addUpdateListener{phase=it.animatedValue as Float; postInvalidateOnAnimation()}; start()
    }}
    override fun onDraw(c:Canvas){ super.onDraw(c); val w=width.toFloat(); val h=height.toFloat(); if(w<=0)return
        val x=(w*phase); p.shader=LinearGradient(x-70*d,0f,x+70*d,0f,intArrayOf(Color.TRANSPARENT,Color.argb(70,70,255,137),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        c.drawLine(8*d,h*.12f,w-8*d,h*.12f,p)
        c.drawLine(18*d,h*.88f,w-18*d,h*.88f,p); p.shader=null
    }
}
