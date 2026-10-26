package com.jcrw.translator

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

class CaptionPositionActivity : Activity() {
    private lateinit var editor: PositionEditor
    private val prefs by lazy { CompanionPreferences(this) }
    private fun tx(tr: String, en: String) = if (prefs.uiLanguage == "en") en else tr

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShortcutState.blocked = true
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.isFocusableInTouchMode = true
        editor = PositionEditor()
        setContentView(editor)
        editor.isFocusable = true
        editor.isFocusableInTouchMode = true
        editor.requestFocus()
    }

    override fun onBackPressed() = save()
    override fun onPause() { ShortcutState.blocked = false; super.onPause() }
    private fun save() {
        prefs.overlayX = editor.xPos
        prefs.overlayY = editor.yPos
        Toast.makeText(this, tx("Altyazı konumu kaydedildi", "Subtitle position saved"), Toast.LENGTH_SHORT).show()
        finish()
    }

    inner class PositionEditor : View(this) {
        var xPos = prefs.overlayX
        var yPos = prefs.overlayY
        private var lastX = 0f
        private var lastY = 0f
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = prefs.captionBackgroundColor }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = prefs.captionTextColor
            textSize = prefs.captionTextSize * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create(prefs.captionFont, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
        private val captionBounds = RectF()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(0x66000000)
            val cx = xPos * width
            val cy = yPos * height
            val sample = tx("Çevrilmiş altyazı burada görünür", "Translated subtitle appears here")
            val maxTextWidth = width * .84f
            val measured = text.measureText(sample).coerceAtMost(maxTextWidth)
            captionBounds.set(cx - measured / 2 - 24, cy - text.textSize - 16, cx + measured / 2 + 24, cy + 16)
            c.drawRoundRect(captionBounds, 14f, 14f, box)
            c.drawText(sample, cx, cy, text)
            c.drawText(tx("Sürükleyerek konumlandır • yön tuşlarıyla ince ayar • BACK kaydet", "Drag to position • D-pad for fine adjustment • BACK saves"), 24f, 42f, hint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; return true }
                MotionEvent.ACTION_MOVE -> {
                    xPos = (xPos + (e.x - lastX) / width).coerceIn(.08f, .92f)
                    yPos = (yPos + (e.y - lastY) / height).coerceIn(.08f, .92f)
                    lastX = e.x; lastY = e.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP -> { performClick(); return true }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val step = if (event.isShiftPressed) .02f else .005f
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> xPos = (xPos - step).coerceIn(.08f, .92f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> xPos = (xPos + step).coerceIn(.08f, .92f)
                KeyEvent.KEYCODE_DPAD_UP -> yPos = (yPos - step).coerceIn(.08f, .92f)
                KeyEvent.KEYCODE_DPAD_DOWN -> yPos = (yPos + step).coerceIn(.08f, .92f)
                KeyEvent.KEYCODE_BACK -> { save(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { save(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
            invalidate(); return true
        }
    }
}
