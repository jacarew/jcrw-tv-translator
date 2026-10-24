// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.metallic.chiaki.common.Preferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Holds the user-approved capture session between Settings and the next stream screen. */
object SubtitleProjectionHolder
{
	@Volatile private var projection: MediaProjection? = null
	@Volatile private var stopCallback: MediaProjection.Callback? = null

	val hasPreparedProjection: Boolean get() = projection != null

	@Synchronized fun prepare(context: Context, resultCode: Int, data: Intent): Boolean
	{
		stop()
		val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
		val granted = manager.getMediaProjection(resultCode, data) ?: return false
		val callback = object: MediaProjection.Callback()
		{
			override fun onStop()
			{
				synchronized(this@SubtitleProjectionHolder)
				{
					if(projection === granted) { projection = null; stopCallback = null }
				}
			}
		}
		granted.registerCallback(callback, Handler(Looper.getMainLooper()))
		projection = granted
		stopCallback = callback
		return true
	}

	@Synchronized fun take(): MediaProjection?
	{
		val current = projection ?: return null
		stopCallback?.let { current.unregisterCallback(it) }
		projection = null
		stopCallback = null
		return current
	}

	@Synchronized fun stop()
	{
		val current = projection
		stopCallback?.let { current?.unregisterCallback(it) }
		projection = null
		stopCallback = null
		current?.stop()
	}
}

/** Samples the chosen screen rectangle at a low rate, recognizes text locally, and returns only changed lines. */
class SubtitleCapture(
	context: Context,
	private val region: SubtitleRegionView,
	private val translatedCaption: View,
	private val preferences: Preferences,
	private val onRecognizedText: (String) -> Unit)
{
	private val appContext = context.applicationContext
	private val recognizer = if(preferences.subtitleOcrEngine == "mlkit") TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
	private var tesseract: TessBaseAPI? = null
	private val thread = HandlerThread("subtitle-screen-ocr").apply { start() }
	private val handler = Handler(thread.looper)
	private val mainHandler = Handler(Looper.getMainLooper())
	private var projection: MediaProjection? = null
	private var projectionCallback: MediaProjection.Callback? = null
	private var display: VirtualDisplay? = null
	private var imageReader: ImageReader? = null
	@Volatile private var previousText = ""
	@Volatile private var previousNormalizedText = ""
	@Volatile private var sampleInFlight = false
	@Volatile private var engineUnavailable = false
	private var lastOcrSignature: IntArray? = null
	private var lastOcrAtMs = 0L
	private val stopped = AtomicBoolean(false)
	private val clearText = Runnable {
		if(previousText.isNotEmpty())
		{
			previousText = ""
			previousNormalizedText = ""
			mainHandler.post { onRecognizedText("") }
		}
	}

	fun start(grantedProjection: MediaProjection)
	{
		val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		val metrics = DisplayMetrics()
		@Suppress("DEPRECATION")
		wm.defaultDisplay.getRealMetrics(metrics)
		// Let the user lower the OCR input resolution as far as 96 px for performance experiments.
		val captureWidth = minOf(metrics.widthPixels, preferences.subtitleOcrMaxWidth).coerceAtLeast(96)
		val captureHeight = (metrics.heightPixels.toFloat() * captureWidth / metrics.widthPixels).toInt().coerceAtLeast(54)
		val density = metrics.densityDpi
		val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
		imageReader = reader
		reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, handler)
		projection = grantedProjection
		projectionCallback = object: MediaProjection.Callback() {}
		projection?.registerCallback(projectionCallback!!, handler)
		display = projection?.createVirtualDisplay(
			"Jcrw subtitle OCR", captureWidth, captureHeight, density,
			DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
	}

	fun setResolution(maxWidth: Int)
	{
		preferences.subtitleOcrMaxWidth = maxWidth
		handler.post {
			if(stopped.get()) return@post
			if(sampleInFlight)
			{
				handler.postDelayed({ setResolution(maxWidth) }, 120L)
				return@post
			}
			val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			val metrics = DisplayMetrics()
			@Suppress("DEPRECATION")
			wm.defaultDisplay.getRealMetrics(metrics)
			val width = minOf(metrics.widthPixels, maxWidth).coerceAtLeast(96)
			val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(54)
			val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
			reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, handler)
			display?.setSurface(null)
			val previous = imageReader
			imageReader = reader
			previous?.setOnImageAvailableListener(null, null)
			previous?.close()
			display?.resize(width, height, metrics.densityDpi)
			display?.setSurface(reader.surface)
			lastOcrSignature = null
			lastOcrAtMs = 0L
		}
	}

	private fun onImageAvailable(source: ImageReader)
	{
		val image = try { source.acquireLatestImage() } catch(_: Exception) { null } ?: return
		if(sampleInFlight) { image.close(); return }
		sampleInFlight = true
		// Detach after one frame. Sampling resumes only after OCR completes and a delay.
		display?.setSurface(null)
		val crop = try { cropSelectedRegion(image) } catch(_: Exception) { null } finally { image.close() }
		if(crop == null) { resumeSamplingLater(); return }
		val signature = sampleSignature(crop)
		val now = SystemClock.elapsedRealtime()
		if(!subtitleImageChanged(signature) && now - lastOcrAtMs < 5_000L)
		{
			crop.recycle()
			resumeSamplingLater()
			return
		}
		lastOcrSignature = signature
		lastOcrAtMs = now
		if(recognizer != null)
		{
			recognizer.process(InputImage.fromBitmap(crop, 0))
				.addOnSuccessListener { result -> handleRecognizedText(result.text) }
				.addOnFailureListener { }
				.addOnCompleteListener { crop.recycle(); resumeSamplingLater() }
		}
		else
		{
			try
			{
				val api = tesseract ?: initializeTesseract().also { tesseract = it }
				api.setImage(crop)
				handleRecognizedText(api.getUTF8Text().orEmpty())
			}
			catch(_: Exception)
			{
				engineUnavailable = true
				mainHandler.post { Toast.makeText(appContext, "Tesseract modeli indirilemedi. İnternet bağlantısını kontrol edip çeviriyi yeniden başlatın.", Toast.LENGTH_LONG).show() }
			}
			finally { crop.recycle(); resumeSamplingLater() }
		}
	}

	/** Cheap sampled luminance fingerprint; full OCR only runs when the selected subtitle image changes. */
	private fun sampleSignature(bitmap: Bitmap): IntArray
	{
		val columns = 24
		val rows = 12
		val result = IntArray(columns * rows)
		var index = 0
		for(row in 0 until rows)
		{
			val y = ((row + .5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
			for(column in 0 until columns)
			{
				val x = ((column + .5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
				val pixel = bitmap.getPixel(x, y)
				val red = (pixel shr 16) and 0xff
				val green = (pixel shr 8) and 0xff
				val blue = pixel and 0xff
				result[index++] = (red * 299 + green * 587 + blue * 114) / 1000
			}
		}
		return result
	}

	private fun subtitleImageChanged(current: IntArray): Boolean
	{
		val previous = lastOcrSignature ?: return true
		if(previous.size != current.size) return true
		var totalDifference = 0L
		for(index in current.indices) totalDifference += kotlin.math.abs(current[index] - previous[index])
		// Ignore small background/animation shifts; OCR only when the subtitle area changes substantially.
		return totalDifference.toFloat() / current.size >= 9.0f
	}

	private fun handleRecognizedText(rawText: String)
	{
		val text = rawText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
				if(text.isNotEmpty())
				{
					val normalized = normalizeSubtitle(text)
					if(normalized != previousNormalizedText)
					{
						handler.removeCallbacks(clearText)
						previousText = text
						previousNormalizedText = normalized
						mainHandler.post { onRecognizedText(text) }
						handler.postDelayed(clearText, 5_000L)
					}
				}
				// Empty OCR frames do not restart the timeout; each new caption owns one five-second window.
	}

	private fun initializeTesseract(): TessBaseAPI
	{
		val dataDir = File(appContext.filesDir, "tesseract")
		val tessDataDir = File(dataDir, "tessdata")
		val model = File(tessDataDir, "eng.traineddata")
		if(!model.exists())
		{
			tessDataDir.mkdirs()
			val connection = (URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata").openConnection() as HttpURLConnection).apply {
				connectTimeout = 15000
				readTimeout = 30000
			}
			val temporaryModel = File(tessDataDir, "eng.traineddata.part")
			try
			{
				connection.inputStream.use { input -> temporaryModel.outputStream().use { input.copyTo(it) } }
				if(!temporaryModel.renameTo(model)) throw IllegalStateException("Tesseract modeli kaydedilemedi")
			}
			catch(error: Exception) { temporaryModel.delete(); throw error }
			finally { connection.disconnect() }
		}
		return TessBaseAPI().also { api ->
			if(!api.init(dataDir.absolutePath, "eng")) { api.recycle(); throw IllegalStateException("Tesseract başlatılamadı") }
		}
	}

	private fun resumeSamplingLater()
	{
		sampleInFlight = false
		handler.postDelayed({
			if(!stopped.get() && !engineUnavailable) display?.setSurface(imageReader?.surface)
		}, 1_500L)
	}

	private fun cropSelectedRegion(image: Image): Bitmap?
	{
		val plane = image.planes.firstOrNull() ?: return null
		val pixelStride = plane.pixelStride
		val rowStride = plane.rowStride
		val paddedWidth = rowStride / pixelStride
		val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
		plane.buffer.rewind()
		full.copyPixelsFromBuffer(plane.buffer)
		val selected = region.normalizedRegion()
		val left = (selected.left * image.width).toInt().coerceIn(0, image.width - 1)
		val top = (selected.top * image.height).toInt().coerceIn(0, image.height - 1)
		val right = (selected.right * image.width).toInt().coerceIn(left + 1, image.width)
		val bottom = (selected.bottom * image.height).toInt().coerceIn(top + 1, image.height)
		var crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
		// MediaProjection also captures our own translated caption. If it overlaps
		// the OCR selection, black it out in the OCR bitmap to prevent feedback loops.
		if(translatedCaption.isShown && translatedCaption.width > 0 && translatedCaption.height > 0)
		{
			val regionLocation = IntArray(2)
			val captionLocation = IntArray(2)
			region.getLocationOnScreen(regionLocation)
			translatedCaption.getLocationOnScreen(captionLocation)
			val scaleX = image.width.toFloat() / region.width.coerceAtLeast(1)
			val scaleY = image.height.toFloat() / region.height.coerceAtLeast(1)
			val capLeft = ((captionLocation[0] - regionLocation[0]) * scaleX).toInt()
			val capTop = ((captionLocation[1] - regionLocation[1]) * scaleY).toInt()
			val capRight = capLeft + (translatedCaption.width * scaleX).toInt()
			val capBottom = capTop + (translatedCaption.height * scaleY).toInt()
			val overlapLeft = maxOf(left, capLeft)
			val overlapTop = maxOf(top, capTop)
			val overlapRight = minOf(right, capRight)
			val overlapBottom = minOf(bottom, capBottom)
			if(overlapLeft < overlapRight && overlapTop < overlapBottom)
			{
				crop = crop.copy(Bitmap.Config.ARGB_8888, true).also { original ->
					crop.recycle()
					Canvas(original).drawRect(
						(overlapLeft - left).toFloat(), (overlapTop - top).toFloat(),
						(overlapRight - left).toFloat(), (overlapBottom - top).toFloat(),
						Paint().apply { color = android.graphics.Color.BLACK })
				}
			}
		}
		full.recycle()
		return crop
	}

	private fun normalizeSubtitle(text: String): String = text
		.lowercase()
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
		.trim()

	fun stop()
	{
		if(!stopped.compareAndSet(false, true)) return
		handler.removeCallbacksAndMessages(null)
		display?.release(); display = null
		imageReader?.setOnImageAvailableListener(null, null)
		imageReader?.close(); imageReader = null
		projection?.let { current ->
			projectionCallback?.let { current.unregisterCallback(it) }
			current.stop()
		}
		projection = null
		projectionCallback = null
		recognizer?.close()
		tesseract?.recycle(); tesseract = null
		thread.quitSafely()
	}
}
