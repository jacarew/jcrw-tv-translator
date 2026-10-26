package com.jcrw.translator

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

class RegionSelectionActivity: Activity() {
    private lateinit var picker: Picker
    private fun tx(tr:String,en:String)=if(CompanionPreferences(this).uiLanguage=="en")en else tr
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ShortcutState.blocked=true;window.setBackgroundDrawableResource(android.R.color.transparent);window.decorView.isFocusableInTouchMode=true;picker=Picker();setContentView(picker);picker.isFocusable=true;picker.isFocusableInTouchMode=true;picker.requestFocus()}
    override fun onPause(){ShortcutState.blocked=false;super.onPause()}
    override fun onBackPressed(){save()}
    private fun save(){CompanionPreferences(this).subtitleRegion=picker.region;Toast.makeText(this,tx("Altyazı alanı kaydedildi","Subtitle area saved"),Toast.LENGTH_SHORT).show();finish()}
    inner class Picker:View(this){
        var region=CompanionPreferences(this@RegionSelectionActivity).subtitleRegion
        private var resize=false
        private var downAt=0L
        private var touchX=0f; private var touchY=0f; private var touchResize=false
        private val fill=Paint().apply{color=0x3342A5F5;style=Paint.Style.FILL}
        private val border=Paint().apply{color=0xFFFFD54F.toInt();style=Paint.Style.STROKE;strokeWidth=5f}
        private val label=Paint().apply{color=Color.WHITE;textSize=26f;setShadowLayer(4f,1f,1f,Color.BLACK)}
        private val regionBounds=RectF()
        private val handleBounds=RectF()
        private fun change(dx:Float,dy:Float){if(resize){region.right=(region.right+dx).coerceIn(region.left+.08f,1f);region.bottom=(region.bottom+dy).coerceIn(region.top+.04f,1f)}else{val w=region.width();val h=region.height();val l=(region.left+dx).coerceIn(0f,1f-w);val t=(region.top+dy).coerceIn(0f,1f-h);region=RectF(l,t,l+w,t+h)};invalidate()}
        private fun isConfirm(code:Int)=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_BUTTON_A||code==KeyEvent.KEYCODE_BUTTON_X
        override fun onDraw(c:Canvas){super.onDraw(c);regionBounds.set(region.left*width,region.top*height,region.right*width,region.bottom*height);c.drawRoundRect(regionBounds,12f,12f,fill);c.drawRoundRect(regionBounds,12f,12f,border);handleBounds.set(regionBounds.right-24,regionBounds.bottom-24,regionBounds.right+8,regionBounds.bottom+8);fill.color=0xFFFFD54F.toInt();c.drawRoundRect(handleBounds,5f,5f,fill);fill.color=0x3342A5F5;val hint=if(prefsLang()=="en")"Drag inside to move • drag lower-right corner to resize • D-pad + X/OK • BACK save" else "İçinden sürükle: taşı • sağ alt köşeyi sürükle: boyutlandır • Kumanda: yön + X/OK • BACK kaydet";c.drawText(hint,24f,46f,label);c.drawText("Genişlik %${"%.0f".format(region.width()*100)}   Yükseklik %${"%.0f".format(region.height()*100)}   Mod: ${if(resize)"BOYUT" else "TAŞI"}",24f,82f,label)}
        private fun prefsLang()=CompanionPreferences(this@RegionSelectionActivity).uiLanguage
        override fun onKeyDown(code:Int,event:KeyEvent):Boolean{if(isConfirm(code)){if(event.repeatCount==0)downAt=android.os.SystemClock.uptimeMillis();return true};val s=if(event.isShiftPressed).02f else .01f;when(code){KeyEvent.KEYCODE_DPAD_LEFT->change(-s,0f);KeyEvent.KEYCODE_DPAD_RIGHT->change(s,0f);KeyEvent.KEYCODE_DPAD_UP->change(0f,-s);KeyEvent.KEYCODE_DPAD_DOWN->change(0f,s);KeyEvent.KEYCODE_BACK->{save();return true};else->return super.onKeyDown(code,event)};return true}
        override fun onKeyUp(code:Int,event:KeyEvent):Boolean{if(isConfirm(code)){val held=android.os.SystemClock.uptimeMillis()-downAt;if(held>650)save() else {resize=!resize;invalidate();Toast.makeText(this@RegionSelectionActivity,if(resize)tx("Boyutlandırma açık: ←→ genişlik, ↑↓ yükseklik","Resize mode: ←→ width, ↑↓ height") else tx("Taşıma açık","Move mode"),Toast.LENGTH_SHORT).show()};return true};return super.onKeyUp(code,event)}
        override fun onTouchEvent(e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{touchX=e.x;touchY=e.y;val px=region.right*width;val py=region.bottom*height;touchResize=e.x>px-48&&e.y>py-48;parent?.requestDisallowInterceptTouchEvent(true);return true};MotionEvent.ACTION_MOVE->{val dx=(e.x-touchX)/width;val dy=(e.y-touchY)/height;val was=resize;resize=touchResize;change(dx,dy);resize=was;touchX=e.x;touchY=e.y;return true};MotionEvent.ACTION_UP->{performClick();return true}};return true}
        override fun performClick():Boolean{super.performClick();return true}
    }
}
