package com.neon.gametweak

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/** Rich action cell for child panels; replaces rectangular Button usage. */
class NukeActionTileView @JvmOverloads constructor(
    context:Context, attrs:AttributeSet?=null, defStyleAttr:Int=0,
):View(context,attrs,defStyleAttr){
    private val d=resources.displayMetrics.density
    private val sd=resources.displayMetrics.scaledDensity.coerceAtMost(d*1.30f)
    private var title="ACTION";private var subtitle="";private var icon:Drawable?=null;private var danger=false
    private val path=Path();private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=.8f*d}
    private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
    init{isClickable=true;isFocusable=true}
    fun configure(title:String,subtitle:String,iconRes:Int,danger:Boolean=false){this.title=title;this.subtitle=subtitle;this.danger=danger;icon=ContextCompat.getDrawable(context,iconRes)?.mutate()?.apply{setTint(if(danger)NukeHudPalette.Danger else NukeHudPalette.Text)};contentDescription=title;invalidate()}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->NukeMotionEngine.press(this,true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->NukeMotionEngine.press(this,false)};return super.onTouchEvent(e)}
    override fun onDraw(c:Canvas){super.onDraw(c);val w=width.toFloat();val h=height.toFloat();if(w<2||h<2)return;val cut=9f*d;path.rewind();path.moveTo(cut,0f);path.lineTo(w*.72f,0f);path.lineTo(w*.77f,cut*.45f);path.lineTo(w-cut*.55f,cut*.45f);path.lineTo(w,cut);path.lineTo(w,h-cut*.7f);path.lineTo(w-cut,h);path.lineTo(cut*.5f,h);path.lineTo(0f,h-cut);path.lineTo(0f,cut*.6f);path.close();fill.shader=LinearGradient(0f,0f,w,h,intArrayOf(NukeHudPalette.PanelRaised,NukeHudPalette.Void),null,Shader.TileMode.CLAMP);c.drawPath(path,fill);edge.color=if(danger)android.graphics.Color.argb(170,240,100,100) else android.graphics.Color.argb(125,74,171,108);c.drawPath(path,edge)
        val s=(21f*d).toInt();val top=((h-s)/2).toInt();icon?.setBounds((11f*d).toInt(),top,(11f*d).toInt()+s,top+s);icon?.draw(c)
        t.textAlign=Paint.Align.LEFT;t.textSize=10f*sd;t.color=if(danger)NukeHudPalette.Danger else NukeHudPalette.Text;t.typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);c.drawText(title,43f*d,h*.42f,t)
        t.textSize=7.4f*sd;t.color=NukeHudPalette.Muted;t.typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);c.drawText(subtitle.take(44),43f*d,h*.70f,t)
        t.textAlign=Paint.Align.CENTER;t.textSize=13f*d;t.color=if(danger)NukeHudPalette.Danger else NukeHudPalette.Green;c.drawText("›",w-14f*d,h*.57f,t)
    }
}
