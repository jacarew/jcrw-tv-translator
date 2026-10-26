package com.jcrw.translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View

/** Small, bundled coffee-support animation shown only when the user opens support info. */
class CoffeeSupportGifView(context: Context) : View(context) {
    private val movie = context.resources.openRawResource(R.raw.coffee_support).use { Movie.decodeStream(it) }
    private val startedAt = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gif = movie ?: return
        val duration = gif.duration().coerceAtLeast(100)
        gif.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        val scale = minOf(width.toFloat() / gif.width(), height.toFloat() / gif.height())
        val left = (width - gif.width() * scale) / 2f
        val top = (height - gif.height() * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        gif.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
