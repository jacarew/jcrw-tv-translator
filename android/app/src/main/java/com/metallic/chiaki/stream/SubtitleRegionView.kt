// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** TV remote and pointer friendly subtitle sampling rectangle. Coordinates are normalized to the view. */
class SubtitleRegionView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val rect = RectF(.15f, .77f, .85f, .91f)
	private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3342A5F5 }
	private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 24f; setShadowLayer(3f, 1f, 1f, 0xFF000000.toInt()) }
	private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); style = Paint.Style.FILL }
	private var moving = false
	private var resizing = false
	private var lastX = 0f
	private var lastY = 0f
	private var pointerResize = false
	var onRegionChanged: ((RectF) -> Unit)? = null
	var isSelecting: Boolean = false
		set(value) { field = value; isFocusable = value; isFocusableInTouchMode = value; invalidate() }

	init { isFocusable = false }

	fun toggleResizeMode()
	{
		resizing = !resizing
		invalidate()
	}

	fun setNormalizedRegion(region: RectF)
	{
		rect.set(clamp(region.left), clamp(region.top), clamp(region.right), clamp(region.bottom))
		invalidate()
	}

	fun normalizedRegion() = RectF(rect)

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(!isSelecting) return
		val box = screenRect()
		canvas.drawRoundRect(box, 10f, 10f, fill)
		canvas.drawRoundRect(box, 10f, 10f, border)
		canvas.drawCircle(box.right, box.bottom, 14f, handle)
		val mode = if(resizing) "Boyutlandır" else "Taşı"
		canvas.drawText("Altyazı alanı • $mode • OK ile değiştir", box.left + 8f, max(26f, box.top - 12f), label)
	}

	private fun screenRect() = RectF(rect.left * width, rect.top * height, rect.right * width, rect.bottom * height)

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if(!isSelecting) return false
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN -> {
				lastX = event.x; lastY = event.y
				pointerResize = screenRect().let { event.x > it.right - 40 && event.y > it.bottom - 40 }
				moving = !pointerResize
				return true
			}
			MotionEvent.ACTION_MOVE -> {
				val dx = (event.x - lastX) / max(1f, width.toFloat())
				val dy = (event.y - lastY) / max(1f, height.toFloat())
				if(pointerResize) resize(dx, dy) else if(moving) move(dx, dy)
				lastX = event.x; lastY = event.y
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { moving = false; pointerResize = false; return true }
		}
		return true
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean
	{
		if(!isSelecting) return super.onKeyDown(keyCode, event)
		if(keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
		{
			toggleResizeMode(); return true
		}
		val step = if(event.isShiftPressed) .02f else .008f
		when(keyCode)
		{
			KeyEvent.KEYCODE_DPAD_LEFT -> if(resizing) resize(-step, 0f) else move(-step, 0f)
			KeyEvent.KEYCODE_DPAD_RIGHT -> if(resizing) resize(step, 0f) else move(step, 0f)
			KeyEvent.KEYCODE_DPAD_UP -> if(resizing) resize(0f, -step) else move(0f, -step)
			KeyEvent.KEYCODE_DPAD_DOWN -> if(resizing) resize(0f, step) else move(0f, step)
			else -> return super.onKeyDown(keyCode, event)
		}
		return true
	}

	private fun move(dx: Float, dy: Float)
	{
		val w = rect.width(); val h = rect.height()
		val left = min(1f - w, max(0f, rect.left + dx))
		val top = min(1f - h, max(0f, rect.top + dy))
		rect.set(left, top, left + w, top + h); changed()
	}

	private fun resize(dw: Float, dh: Float)
	{
		rect.right = min(1f, max(rect.left + .08f, rect.right + dw))
		rect.bottom = min(1f, max(rect.top + .04f, rect.bottom + dh)); changed()
	}

	private fun changed() { invalidate(); onRegionChanged?.invoke(RectF(rect)) }
	private fun clamp(value: Float) = min(1f, max(0f, value))
}
