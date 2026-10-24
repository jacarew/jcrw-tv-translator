// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlin.math.min
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

private data class PendingSubtitleSpeech(val text: String, val pitch: Float, val volume: Float, val utteranceId: String, val gender: String, val voiceName: String)
private data class QueuedSubtitleSpeech(val epoch: Int, var speech: PendingSubtitleSpeech? = null, var audioFile: File? = null, var preparing: Boolean = false, var preparationFailed: Boolean = false, var failureMessage: String? = null, var failed: Boolean = false)

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 3000L
		private val geminiTtsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()
	private val sessionHintHandler = Handler(Looper.getMainLooper())
	private val sessionHintRunnable = Runnable { showSessionHintIfDue() }
	private val subtitleRegionHoldHandler = Handler(Looper.getMainLooper())
	private var subtitleRegionSavedByHold = false
	private var suppressShortcutMenuReveal = false
	private var pendingControllerShortcutKeyCode: Int? = null
	private var pendingControllerShortcutFromMotion = false
	private var pendingControllerShortcutTriggered = false
	private val controllerDpadPulseReleases = mutableMapOf<Int, Runnable>()
	private val controllerShortcutRunnable = Runnable {
		val keyCode = pendingControllerShortcutKeyCode
		if(keyCode != null)
		{
			pendingControllerShortcutTriggered = true
			toggleControllerShortcut(keyCode)
		}
	}
	private val controllerDpadPulseRelease = Handler(Looper.getMainLooper())
	private var openingSubtitleRegionKeyCode: Int? = null
	private var subtitleRegionOpenedByHoldKeyCode: Int? = null
	private var lastRemoteOkPressAt = 0L
	private var subtitlePermissionRequestInFlight = false
	private val subtitlePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		subtitlePermissionRequestInFlight = false
		val data = result.data
		if(result.resultCode == RESULT_OK && data != null && SubtitleProjectionHolder.prepare(this, result.resultCode, data))
		{
			pendingSubtitleProjection = SubtitleProjectionHolder.take()
			viewModel.preferences.subtitleTranslationEnabled = true
			if(!viewModel.preferences.subtitleRegionConfigured) beginSubtitleRegionSelection()
			else startPendingSubtitleCapture()
		}
		else
		{
			suppressShortcutMenuReveal = true
			binding.subtitleTranslateSwitch.isChecked = false
			suppressShortcutMenuReveal = false
			showTopToast("Ekran yakalama izni verilmedi.")
		}
	}
	private val openSubtitleRegionRunnable = Runnable {
		openingSubtitleRegionKeyCode?.let { keyCode ->
			if(!binding.subtitleRegionView.isSelecting) beginSubtitleRegionSelection()
			subtitleRegionOpenedByHoldKeyCode = keyCode
			openingSubtitleRegionKeyCode = null
		}
	}
	private val saveSubtitleRegionRunnable = Runnable {
		if(binding.subtitleRegionView.isSelecting)
		{
			saveSubtitleRegionSelection()
			subtitleRegionSavedByHold = true
		}
	}
	private var subtitleCapture: SubtitleCapture? = null
	private var pendingSubtitleProjection: android.media.projection.MediaProjection? = null
	private var subtitleGeneration = 0
	private var displayedSubtitle = ""
	private var lastRecognizedSubtitle = ""
	private val translatedSubtitleCache = LinkedHashMap<String, String>()
	private var textToSpeech: TextToSpeech? = null
	private var textToSpeechReady = false
	private var activeSubtitleSpeechId: String? = null
	private val subtitleSpeechQueue = java.util.ArrayDeque<QueuedSubtitleSpeech>()
	@Volatile private var subtitleSpeechQueueEpoch = 0
	private var geminiSpeechPlayer: MediaPlayer? = null
	private var geminiSpeechFile: File? = null
	private val clearTranslatedSubtitle = Runnable {
		displayedSubtitle = ""
		binding.translatedSubtitleText.text = ""
		binding.translatedSubtitleText.isGone = true
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		binding.subtitleVoiceoverSwitch.isCheckable = true
		binding.subtitleTranslateSwitch.isCheckable = true
		binding.subtitleRegionSwitch.isCheckable = true
		binding.subtitleVoiceoverSwitch.isChecked = viewModel.preferences.subtitleVoiceoverEnabled
		binding.subtitleTranslateSwitch.isChecked = false
		binding.subtitleRegionSwitch.isChecked = binding.subtitleRegionView.isSelecting
		updateQuickMenuLabels()
		binding.subtitleVoiceoverSwitch.addOnCheckedChangeListener { _, isChecked ->
			viewModel.preferences.subtitleVoiceoverEnabled = isChecked
			if(!isChecked)
			{
				textToSpeech?.stop()
				clearSubtitleSpeechQueue()
			}
			updateQuickMenuLabels()
			if(!suppressShortcutMenuReveal) showOverlay()
		}
		binding.subtitleTranslateSwitch.addOnCheckedChangeListener { _, isChecked ->
			if(isChecked)
			{
				if(subtitleCapture == null) requestSubtitleTranslationPermission()
			}
			else if(subtitleCapture != null || pendingSubtitleProjection != null) stopSubtitleCapture()
			updateQuickMenuLabels()
			if(!suppressShortcutMenuReveal) showOverlay()
		}
		binding.subtitleRegionSwitch.addOnCheckedChangeListener { _, selecting ->
			if(selecting) beginSubtitleRegionSelection()
			else if(binding.subtitleRegionView.isSelecting) saveSubtitleRegionSelection()
			updateQuickMenuLabels()
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}
		binding.hideOverlayButton.setOnClickListener { hideOverlay() }
		binding.autoOpenMenuButton.setOnClickListener { showTopToast("Menü yalnızca TV kumandasındaki OK tuşuna iki kez basınca açılır.") }
		binding.subtitleAppearanceButton.setOnClickListener { showSubtitleAppearanceDialog() }
		updateAutoOpenMenuLabel()
		binding.subtitleOcrResolutionButton.setOnClickListener { showOcrResolutionDialog() }
		updateOcrResolutionButton()
		binding.subtitleRegionView.setNormalizedRegion(viewModel.preferences.subtitleRegion)
		binding.translatedSubtitleText.isVisible = viewModel.preferences.subtitleCaptionVisible
		binding.subtitleRegionView.onRegionChanged = {
			viewModel.preferences.subtitleRegion = it
			positionTranslatedSubtitle()
		}
		applySubtitleAppearance()
		textToSpeech = TextToSpeech(this) { status ->
			if(status == TextToSpeech.SUCCESS)
			{
				textToSpeech?.setAudioAttributes(AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_GAME)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build())
				textToSpeech?.setLanguage(Locale.forLanguageTag(viewModel.preferences.subtitleTargetLanguage))
				viewModel.preferences.subtitleVoiceName.takeIf { it.isNotBlank() }?.let { selectedVoice ->
					textToSpeech?.voices?.firstOrNull { it.name == selectedVoice }?.let { textToSpeech?.voice = it }
				}
				textToSpeech?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
					override fun onStart(utteranceId: String?) {}
					override fun onDone(utteranceId: String?) { finishSubtitleSpeech(utteranceId) }
					override fun onError(utteranceId: String?) { finishSubtitleSpeech(utteranceId) }
				})
				textToSpeechReady = true
				startNextSubtitleSpeech()
			}
		}
		if(viewModel.preferences.subtitleTranslationEnabled) SubtitleProjectionHolder.take()?.let { projection ->
			pendingSubtitleProjection = projection
			if(!viewModel.preferences.subtitleRegionConfigured)
			{
				binding.subtitleRegionView.isSelecting = true
				binding.subtitleRegionSwitch.isChecked = true
				showOverlay()
				binding.subtitleRegionView.requestFocus()
				Toast.makeText(this, "Çeviri başlamadan önce altyazı alanını seçip OK tuşunu basılı tutarak kaydet.", Toast.LENGTH_LONG).show()
			}
			else startPendingSubtitleCapture()
		}

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerState
				.subscribe { viewModel.input.touchControllerState = it }
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		showOverlay(automatic = true)
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		// Keep Remote Play alive while Android displays screen-capture consent.
		if(!subtitlePermissionRequestInFlight) viewModel.session.pause()
		stopSubtitleCapture()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		sessionHintHandler.removeCallbacksAndMessages(null)
		stopSubtitleCapture()
		subtitleRegionHoldHandler.removeCallbacksAndMessages(null)
		controllerDpadPulseReleases.values.forEach { controllerDpadPulseRelease.removeCallbacks(it) }
		controllerDpadPulseReleases.keys.toList().forEach { viewModel.input.dispatchDpadKey(it, false) }
		controllerDpadPulseReleases.clear()
		textToSpeech?.stop()
		clearSubtitleSpeechQueue()
		textToSpeech?.shutdown()
		textToSpeech = null
		controlsDisposable.dispose()
	}

	private fun requestSubtitleTranslationPermission()
	{
		val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
		subtitlePermissionRequestInFlight = true
		try
		{
			subtitlePermissionLauncher.launch(manager.createScreenCaptureIntent())
		}
		catch(error: Exception)
		{
			subtitlePermissionRequestInFlight = false
			showTopToast("Ekran yakalama izni açılamadı: ${error.message ?: "hata"}")
		}
	}

	private fun startSubtitleCapture(projection: android.media.projection.MediaProjection)
	{
		try
		{
			subtitleCapture = SubtitleCapture(this, binding.subtitleRegionView, binding.translatedSubtitleText, viewModel.preferences) { recognized -> onSubtitleRecognized(recognized) }.also { it.start(projection) }
			suppressShortcutMenuReveal = true
			binding.subtitleTranslateSwitch.isChecked = true
			suppressShortcutMenuReveal = false
			showTopToast(getString(R.string.subtitle_translation_shortcut_on))
		}
		catch(error: Exception)
		{
			suppressShortcutMenuReveal = false
			projection.stop()
			stopSubtitleCapture()
			showTopToast("Altyazı yakalama başlatılamadı: ${error.message ?: "hata"}")
		}
	}

	private fun positionTranslatedSubtitle()
	{
		if(!::binding.isInitialized) return
		val view = binding.translatedSubtitleText
		// Position only after the text is visible and measured. Measuring it while
		// GONE gives a zero-sized view and can leave the next caption off-screen.
		view.post {
			view.doOnLayout { measuredView ->
				val bounds = binding.mainStreamLayout
				if(bounds.width == 0 || bounds.height == 0) return@doOnLayout
				val params = measuredView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@doOnLayout
				params.gravity = Gravity.TOP or Gravity.LEFT
				params.leftMargin = (bounds.width * viewModel.preferences.subtitleCaptionX / 100f - measuredView.width / 2f)
					.toInt().coerceIn(0, (bounds.width - measuredView.width).coerceAtLeast(0))
				params.topMargin = (bounds.height * viewModel.preferences.subtitleCaptionY / 100f - measuredView.height / 2f)
					.toInt().coerceIn(0, (bounds.height - measuredView.height).coerceAtLeast(0))
				measuredView.layoutParams = params
			}
		}
	}

	private fun saveSubtitleRegionSelection()
	{
		binding.subtitleRegionView.isSelecting = false
		binding.subtitleRegionSwitch.isChecked = false
		viewModel.preferences.subtitleRegionConfigured = true
		binding.subtitleTranslateSwitch.requestFocus()
		startPendingSubtitleCapture()
	}

	private fun beginSubtitleRegionSelection()
	{
		binding.subtitleRegionView.isSelecting = true
		binding.subtitleRegionSwitch.isChecked = true
		showOverlay()
		binding.subtitleRegionView.requestFocus()
		Toast.makeText(this, "Oklarla altyazı alanını ayarla; kaydetmek için OK tuşunu basılı tut.", Toast.LENGTH_LONG).show()
	}

	private fun onSubtitleRecognized(text: String)
	{
		if(text.isBlank())
		{
			return
		}
		val speakerName = extractSpeakerLabel(text)
		val dialogue = stripSpeakerLabels(text)
		val normalized = normalizeSubtitle(dialogue)
		val previousNormalized = normalizeSubtitle(lastRecognizedSubtitle)
		if(normalized.isBlank() || subtitleSimilarity(normalized, previousNormalized) >= 0.80) return
		lastRecognizedSubtitle = dialogue
		val generation = ++subtitleGeneration
		val speechReservation = if(viewModel.preferences.subtitleVoiceoverEnabled)
			QueuedSubtitleSpeech(subtitleSpeechQueueEpoch).also { subtitleSpeechQueue.addLast(it) }
		else null
		uiVisibilityHandler.removeCallbacks(clearTranslatedSubtitle)
		val cached = translatedSubtitleCache[normalized]
		val translation = if(cached != null) java.util.concurrent.CompletableFuture.completedFuture(cached)
			else SubtitleTranslator(viewModel.preferences).translate(dialogue, viewModel.preferences.subtitleSourceLanguage)
		translation.whenComplete { translated, error ->
			runOnUiThread {
				if(isFinishing) return@runOnUiThread
				if(error != null)
				{
					if(generation == subtitleGeneration) Toast.makeText(this, "Çeviri başarısız: ${error.cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
					speechReservation?.failed = true
					startNextSubtitleSpeech()
					return@runOnUiThread
				}
				val result = translated.orEmpty()
				if(cached == null && result.isNotBlank())
				{
					translatedSubtitleCache[normalized] = result
					while(translatedSubtitleCache.size > 80) translatedSubtitleCache.remove(translatedSubtitleCache.keys.first())
				}
				if(generation == subtitleGeneration)
				{
					displayedSubtitle = if(speakerName != null) "$speakerName: $result" else result
					binding.translatedSubtitleText.text = displayedSubtitle
					binding.translatedSubtitleText.isVisible = viewModel.preferences.subtitleCaptionVisible && displayedSubtitle.isNotBlank()
					if(binding.translatedSubtitleText.isVisible) positionTranslatedSubtitle()
					uiVisibilityHandler.postDelayed(clearTranslatedSubtitle, 5_000L)
				}
				if(speechReservation != null && subtitleSpeechQueue.contains(speechReservation) && speechReservation.epoch == subtitleSpeechQueueEpoch)
				{
					val speechInput = if(speakerName != null) "$speakerName: $result" else result
					speechReservation.speech = createSubtitleSpeech(speechInput, generation)
					if(speechReservation.speech == null) speechReservation.failed = true
					else if(viewModel.preferences.subtitleSpeechProvider == "gemini") prepareGeminiSubtitleSpeech(speechReservation)
					startNextSubtitleSpeech()
				}
			}
		}
	}

	private fun stripSpeakerLabels(text: String): String = text.lineSequence().mapNotNull { line ->
		val value = line.trim()
		if(value.isEmpty()) return@mapNotNull null
		val colon = value.indexOf(':')
		if(colon in 1..40 && value.substring(0, colon).matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’\\-\\[\\]]{0,39}")))
			value.substring(colon + 1).trim().takeIf { it.isNotEmpty() }
		else value
	}.joinToString("\n")

	private fun extractSpeakerLabel(text: String): String? = text.lineSequence()
		.map { it.trim() }
		.firstNotNullOfOrNull { line ->
			val colon = line.indexOf(':')
			if(colon in 1..40 && line.substring(0, colon).matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’\\-\\[\\]]{0,39}")))
				line.substring(0, colon).trim().takeIf { it.isNotBlank() }
			else null
		}

	private fun normalizeSubtitle(text: String): String = text.lowercase(Locale.ROOT)
		.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

	private fun subtitleSimilarity(first: String, second: String): Double
	{
		if(first.isBlank() || second.isBlank()) return 0.0
		val previous = IntArray(second.length + 1) { it }
		for(i in first.indices)
		{
			val current = IntArray(second.length + 1)
			current[0] = i + 1
			for(j in second.indices)
			{
				val substitutionCost = if(first[i] == second[j]) 0 else 1
				current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + substitutionCost)
			}
			for(j in current.indices) previous[j] = current[j]
		}
		val maxLength = maxOf(first.length, second.length)
		return 1.0 - previous[second.length].toDouble() / maxLength
	}

	private fun showTopToast(message: String)
	{
		Toast.makeText(this, message, Toast.LENGTH_LONG).apply {
			setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, (24 * resources.displayMetrics.density).toInt())
			show()
		}
	}

	private fun showSessionHintIfDue()
	{
		if(isFinishing || isDestroyed || !::binding.isInitialized) return
		val preferences = viewModel.preferences
		if(!preferences.streamHintsEnabled) return
		val now = System.currentTimeMillis()
		if(now - preferences.streamHintLastShownAt < 6L * 60L * 60L * 1000L) return
		val tips = arrayOf(
			getString(R.string.stream_hint_dpad_subtitles),
			getString(R.string.stream_hint_dpad_voice),
			getString(R.string.stream_hint_double_ok)
		)
		val index = preferences.streamHintIndex % tips.size
		val snackbar = Snackbar.make(binding.root, tips[index], Snackbar.LENGTH_LONG)
			.setAction(getString(R.string.stream_hints_disable)) { preferences.streamHintsEnabled = false }
			.setDuration(12_000)
		val layoutParams = snackbar.view.layoutParams
		if(layoutParams is FrameLayout.LayoutParams)
		{
			layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
			layoutParams.topMargin = (16 * resources.displayMetrics.density).toInt()
			snackbar.view.layoutParams = layoutParams
		}
		snackbar.show()
		preferences.streamHintIndex = (index + 1) % tips.size
		preferences.streamHintLastShownAt = now
	}

	private fun canStartControllerSubtitleShortcut(): Boolean =
		viewModel.preferences.subtitleControllerShortcutsEnabled && binding.overlay.isGone && !binding.subtitleRegionView.isSelecting

	private fun beginControllerSubtitleShortcut(keyCode: Int, fromMotion: Boolean)
	{
		if(pendingControllerShortcutKeyCode == null)
		{
			pendingControllerShortcutKeyCode = keyCode
			pendingControllerShortcutFromMotion = fromMotion
			pendingControllerShortcutTriggered = false
			subtitleRegionHoldHandler.removeCallbacks(controllerShortcutRunnable)
			subtitleRegionHoldHandler.postDelayed(controllerShortcutRunnable, 700L)
		}
	}

	private fun finishControllerSubtitleShortcut(keyCode: Int, fromMotion: Boolean)
	{
		if(pendingControllerShortcutKeyCode != keyCode || pendingControllerShortcutFromMotion != fromMotion) return
		subtitleRegionHoldHandler.removeCallbacks(controllerShortcutRunnable)
		val wasTriggered = pendingControllerShortcutTriggered
		pendingControllerShortcutKeyCode = null
		pendingControllerShortcutFromMotion = false
		pendingControllerShortcutTriggered = false
		if(!wasTriggered) pulseControllerDpad(keyCode)
	}

	private fun pulseControllerDpad(keyCode: Int)
	{
		viewModel.input.dispatchDpadKey(keyCode, true)
		controllerDpadPulseReleases.remove(keyCode)?.let(controllerDpadPulseRelease::removeCallbacks)
		val release = Runnable {
			viewModel.input.dispatchDpadKey(keyCode, false)
			controllerDpadPulseReleases.remove(keyCode)
		}
		controllerDpadPulseReleases[keyCode] = release
		controllerDpadPulseRelease.postDelayed(release, 80L)
	}

	private fun toggleControllerShortcut(keyCode: Int)
	{
		suppressShortcutMenuReveal = true
		try
		{
			when(keyCode)
			{
				KeyEvent.KEYCODE_DPAD_DOWN -> {
					val enabled = !binding.subtitleTranslateSwitch.isChecked
					// Re-enabling screen capture invokes Android's consent activity. Do not
					// reopen the quick menu when returning from that system prompt.
					binding.subtitleTranslateSwitch.isChecked = enabled
					if(!enabled) showTopToast(getString(R.string.subtitle_translation_shortcut_off))
					else if(subtitleCapture == null) showTopToast("Altyazı çevirisi açılıyor…")
				}
				KeyEvent.KEYCODE_DPAD_UP -> {
					val enabled = !binding.subtitleVoiceoverSwitch.isChecked
					binding.subtitleVoiceoverSwitch.isChecked = enabled
					showTopToast(getString(if(enabled) R.string.subtitle_voiceover_shortcut_on else R.string.subtitle_voiceover_shortcut_off))
				}
			}
		}
		finally { suppressShortcutMenuReveal = false }
	}

	private fun updateQuickMenuLabels()
	{
		if(!::binding.isInitialized) return
		binding.subtitleVoiceoverSwitch.text = if(binding.subtitleVoiceoverSwitch.isChecked) "Ses: Açık" else "Ses: Kapalı"
		binding.subtitleTranslateSwitch.text = if(binding.subtitleTranslateSwitch.isChecked) "Çeviri: Açık" else "Çeviri: Kapalı"
		binding.subtitleRegionSwitch.text = if(binding.subtitleRegionSwitch.isChecked) "Alanı kaydet" else "OCR alanı"
	}

	private fun updateOcrResolutionButton()
	{
		if(::binding.isInitialized) binding.subtitleOcrResolutionButton.text = "OCR: ${viewModel.preferences.subtitleOcrMaxWidth} px"
	}

	private fun showOcrResolutionDialog()
	{
		val widths = intArrayOf(96, 128, 160, 240, 320, 480, 720)
		val labels = widths.map { "$it px" }.toTypedArray()
		val selected = widths.indexOf(viewModel.preferences.subtitleOcrMaxWidth).coerceAtLeast(0)
		AlertDialog.Builder(this)
			.setTitle(R.string.subtitle_ocr_resolution_title)
			.setSingleChoiceItems(labels, selected) { dialog, which ->
				val width = widths[which]
				viewModel.preferences.subtitleOcrMaxWidth = width
				updateOcrResolutionButton()
				subtitleCapture?.setResolution(width)
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun createSubtitleSpeech(translated: String, generation: Int): PendingSubtitleSpeech?
	{
		if(!viewModel.preferences.subtitleVoiceoverEnabled) return null
		val speaker = Regex("^\\s*([^:\\r\\n]{1,40}):").find(translated)?.groupValues?.get(1)?.trim().orEmpty()
		val mode = viewModel.preferences.subtitleVoiceMode
		val detectedGender = if(mode == "single") null else viewModel.preferences.voiceGenderForSpeaker(speaker)
		val gender = when {
			mode == "single" -> "neutral"
			detectedGender != null -> detectedGender
			else -> viewModel.preferences.subtitleVoiceGender
		}
		val mappedCharacter = mode == "character" && detectedGender != null
		val spokenText = translated.lineSequence()
			.map { it.replace(Regex("^\\s*[^:\\r\\n]{1,40}:\\s*"), "").trim() }
			.filter { it.isNotBlank() }.joinToString(" ")
		if(spokenText.isBlank()) return null
		val pitch = when(gender) { "female" -> 1.12f; "male" -> 0.92f; else -> 1.0f }
		val volume = viewModel.preferences.subtitleVoiceVolume / 100f
		val voiceName = when {
			mode == "single" -> viewModel.preferences.geminiSpeechVoice
			mappedCharacter -> viewModel.preferences.voiceNameForSpeaker(speaker)
				?: if(gender == "female") viewModel.preferences.geminiFemaleVoice else viewModel.preferences.geminiMaleVoice
			gender == "female" -> viewModel.preferences.geminiFemaleVoice
			else -> viewModel.preferences.geminiMaleVoice
		}
		return PendingSubtitleSpeech(spokenText, pitch, volume, "subtitle-$generation", gender, voiceName)
	}

	private fun prepareGeminiSubtitleSpeech(item: QueuedSubtitleSpeech)
	{
		val speech = item.speech ?: return
		if(item.preparing || item.audioFile != null || item.preparationFailed || item.failed) return
		item.preparing = true
		val epoch = item.epoch
		geminiTtsExecutor.submit {
			var file: File? = null
			try
			{
				if(epoch == subtitleSpeechQueueEpoch)
					file = GeminiSpeechSynthesizer(this, viewModel.preferences).synthesize(speech.text, speech.gender, speech.voiceName)
			}
			catch(error: Exception) { item.failureMessage = error.message?.take(110) ?: error.javaClass.simpleName }
			runOnUiThread {
				if(epoch != subtitleSpeechQueueEpoch || !subtitleSpeechQueue.contains(item) || isFinishing)
				{
					file?.delete()
					return@runOnUiThread
				}
				item.preparing = false
				if(file != null) item.audioFile = file
				else
				{
					item.preparationFailed = true
					val reason = runCatching { errorOrFallbackMessage(item) }.getOrDefault("istek başarısız")
					showTopToast("Gemini TTS: $reason — cihaz TTS kullanılacak.")
				}
				startNextSubtitleSpeech()
			}
		}
	}

	private fun errorOrFallbackMessage(item: QueuedSubtitleSpeech): String = item.failureMessage ?: "yanıt alınamadı"

	private fun startAndroidSubtitleSpeech(speech: PendingSubtitleSpeech)
	{
		val tts = textToSpeech
		if(tts == null || !textToSpeechReady)
		{
			finishSubtitleSpeech(speech.utteranceId)
			return
		}
		tts.setPitch(speech.pitch)
		val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speech.volume) }
		activeSubtitleSpeechId = speech.utteranceId
		val result = tts.speak(speech.text, TextToSpeech.QUEUE_FLUSH, parameters, speech.utteranceId)
		if(result == TextToSpeech.ERROR) finishSubtitleSpeech(speech.utteranceId)
	}

	private fun startGeminiSubtitleSpeech(speech: PendingSubtitleSpeech, audioFile: File)
	{
		geminiSpeechFile = audioFile
		try
		{
			geminiSpeechPlayer?.release()
			val player = MediaPlayer()
			geminiSpeechPlayer = player
			player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
			player.setVolume(speech.volume, speech.volume)
			player.setDataSource(audioFile.absolutePath)
			player.setOnPreparedListener { if(activeSubtitleSpeechId == speech.utteranceId) it.start() else it.release() }
			player.setOnCompletionListener {
				it.release()
				geminiSpeechPlayer = null
				audioFile.delete()
				geminiSpeechFile = null
				finishSubtitleSpeech(speech.utteranceId)
			}
			player.setOnErrorListener { failedPlayer, _, _ ->
				failedPlayer.release()
				geminiSpeechPlayer = null
				audioFile.delete()
				geminiSpeechFile = null
				showTopToast("Gemini sesi açılamadı; cihaz TTS kullanılacak.")
				startAndroidSubtitleSpeech(speech)
				true
			}
			player.prepareAsync()
		}
		catch(error: Exception)
		{
			audioFile.delete()
			geminiSpeechFile = null
				showTopToast("Gemini TTS başarısız; cihaz TTS kullanılacak.")
			startAndroidSubtitleSpeech(speech)
		}
	}

	private fun stopGeminiSpeech()
	{
		geminiSpeechPlayer?.let { player ->
			try { player.stop() } catch(_: Exception) {}
			player.release()
		}
		geminiSpeechPlayer = null
		geminiSpeechFile?.delete()
		geminiSpeechFile = null
	}

	private fun finishSubtitleSpeech(utteranceId: String?)
	{
		runOnUiThread {
			if(utteranceId == null || activeSubtitleSpeechId != utteranceId) return@runOnUiThread
			activeSubtitleSpeechId = null
			subtitleSpeechQueue.peekFirst()?.audioFile?.delete()
			subtitleSpeechQueue.pollFirst()
			startNextSubtitleSpeech()
		}
	}

	private fun startNextSubtitleSpeech()
	{
		if(activeSubtitleSpeechId != null) return
		val next = subtitleSpeechQueue.peekFirst() ?: return
		if(next.epoch != subtitleSpeechQueueEpoch || next.failed)
		{
			next.audioFile?.delete()
			subtitleSpeechQueue.pollFirst()
			startNextSubtitleSpeech()
			return
		}
		val speech = next.speech ?: return
		if(viewModel.preferences.subtitleSpeechProvider == "gemini")
		{
			if(next.audioFile == null && !next.preparationFailed)
			{
				prepareGeminiSubtitleSpeech(next)
				return
			}
			activeSubtitleSpeechId = speech.utteranceId
			if(next.preparationFailed) startAndroidSubtitleSpeech(speech)
			else startGeminiSubtitleSpeech(speech, next.audioFile ?: return)
		}
		else
		{
			if(!textToSpeechReady) return
			activeSubtitleSpeechId = speech.utteranceId
			startAndroidSubtitleSpeech(speech)
		}
	}

	private fun clearSubtitleSpeechQueue()
	{
		subtitleSpeechQueueEpoch++
		subtitleSpeechQueue.forEach { it.audioFile?.delete() }
		subtitleSpeechQueue.clear()
		activeSubtitleSpeechId = null
		stopGeminiSpeech()
	}

	private fun startPendingSubtitleCapture()
	{
		val projection = pendingSubtitleProjection ?: return
		pendingSubtitleProjection = null
		startSubtitleCapture(projection)
	}

	private fun stopSubtitleCapture()
	{
		uiVisibilityHandler.removeCallbacks(clearTranslatedSubtitle)
		textToSpeech?.stop()
		clearSubtitleSpeechQueue()
		subtitleCapture?.stop()
		subtitleCapture = null
		subtitleGeneration++
		displayedSubtitle = ""
		lastRecognizedSubtitle = ""
		if(::binding.isInitialized)
		{
			binding.subtitleTranslateSwitch.isChecked = false
			binding.translatedSubtitleText.text = ""
			binding.translatedSubtitleText.isGone = true
			pendingSubtitleProjection?.stop()
			pendingSubtitleProjection = null
		}
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable {
		hideSystemUI()
	}

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay(automatic = true)
	}

	private fun showOverlay(automatic: Boolean = false)
	{
		// The in-stream menu has one intentional entry point: double-OK on the
		// TV remote. Lifecycle, connection, system-UI and shortcut events never open it.
		if(automatic || !allowOverlayOpenFromDoubleOk) return
		val wasHidden = binding.overlay.isGone
		binding.overlay.isVisible = true
		if(wasHidden)
			binding.subtitleTranslateSwitch.requestFocus()
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private var allowOverlayOpenFromDoubleOk = false
	private fun openOverlayFromDoubleOk()
	{
		allowOverlayOpenFromDoubleOk = true
		try { showOverlay() }
		finally { allowOverlayOpenFromDoubleOk = false }
	}

	private fun hideOverlay()
	{
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	private fun updateAutoOpenMenuLabel()
	{
		if(::binding.isInitialized) binding.autoOpenMenuButton.text = "Menü: çift OK ile açılır"
	}

	private fun applySubtitleAppearance()
	{
		if(!::binding.isInitialized) return
		val prefs = viewModel.preferences
		binding.translatedSubtitleText.textSize = prefs.subtitleCaptionSize.toFloat()
		binding.translatedSubtitleText.setTextColor(prefs.subtitleCaptionTextColor)
		binding.translatedSubtitleText.typeface = android.graphics.Typeface.create(prefs.subtitleCaptionFont, android.graphics.Typeface.BOLD)
		binding.translatedSubtitleText.setBackgroundColor(prefs.subtitleCaptionBackgroundColor)
		positionTranslatedSubtitle()
	}

	private fun showSubtitleAppearanceDialog()
	{
		val labels = arrayOf(
			"OCR altyazı alanını seç / boyutlandır",
			"Ekranda altyazı: ${if(viewModel.preferences.subtitleCaptionVisible) "Açık" else "Kapalı"}",
			getString(R.string.subtitle_caption_size_label), getString(R.string.subtitle_caption_color_label),
			getString(R.string.subtitle_caption_bg_label), getString(R.string.subtitle_caption_font_label),
			"Çevrilmiş yazının konumunu ayarla", "Seslendirme ayarları")
		MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_caption_edit_title).setItems(labels) { _, which ->
			when(which) {
				0 -> beginSubtitleRegionSelection()
				1 -> {
					viewModel.preferences.subtitleCaptionVisible = !viewModel.preferences.subtitleCaptionVisible
					binding.translatedSubtitleText.isVisible = viewModel.preferences.subtitleCaptionVisible && displayedSubtitle.isNotBlank()
					if(binding.translatedSubtitleText.isVisible) positionTranslatedSubtitle()
					showSubtitleAppearanceDialog()
				}
				2 -> chooseCaptionSize()
				3 -> chooseCaptionColor(false)
				4 -> chooseCaptionColor(true)
				5 -> chooseCaptionFont()
				6 -> showCaptionPositionEditor()
				7 -> showSubtitleVoiceSettingsDialog()
			}
		}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showSubtitleVoiceSettingsDialog()
	{
		val prefs = viewModel.preferences
		val labels = arrayOf("Seslendirme: ${if(prefs.subtitleVoiceoverEnabled) "Açık" else "Kapalı"}",
			"Ses motoru: ${when(prefs.subtitleSpeechProvider) { "gemini" -> "Gemini"; "edge" -> "Edge TTS"; else -> "Cihaz TTS" }}",
			"Ses profili: ${when(prefs.subtitleVoiceMode) { "single" -> "Tek ses"; "gender" -> "Kadın / erkek"; else -> "Karakter eşleştirmesi" }}",
			"Cinsiyet: ${when(prefs.subtitleVoiceGender) { "female" -> "Kadın"; "male" -> "Erkek"; else -> "Otomatik" }}",
			"Tek ses: ${prefs.geminiSpeechVoice}", "Kadın Gemini sesi: ${prefs.geminiFemaleVoice}",
			"Erkek Gemini sesi: ${prefs.geminiMaleVoice}", "Ses düzeyi: ${prefs.subtitleVoiceVolume}%")
		MaterialAlertDialogBuilder(this).setTitle("Seslendirme ayarları").setItems(labels) { _, which ->
			when(which)
			{
				0 -> { binding.subtitleVoiceoverSwitch.isChecked = !binding.subtitleVoiceoverSwitch.isChecked; showSubtitleVoiceSettingsDialog() }
				1 -> MaterialAlertDialogBuilder(this).setTitle("Ses motoru")
					.setSingleChoiceItems(arrayOf("Cihaz TTS (ücretsiz)", "Edge TTS (ücretsiz, çevrim içi; ayrı motor gerekir)", "Gemini TTS (API anahtarı gerekir)"), when(prefs.subtitleSpeechProvider) { "edge" -> 1; "gemini" -> 2; else -> 0 }) { dialog, selected ->
						prefs.subtitleSpeechProvider = arrayOf("android", "edge", "gemini")[selected]
						if(selected == 1) MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_edge_tts_setup_title).setMessage(R.string.subtitle_edge_tts_setup_summary)
							.setPositiveButton("TTS ayarlarını aç") { _, _ -> try { startActivity(Intent("android.settings.TTS_SETTINGS")) } catch(_: Exception) { showTopToast(getString(R.string.subtitle_edge_tts_settings_unavailable)) } }
							.setNegativeButton(android.R.string.cancel, null).show()
						if(selected == 2) MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_gemini_tts_notice_title)
							.setMessage(R.string.subtitle_gemini_tts_notice).setPositiveButton(android.R.string.ok, null).show()
						dialog.dismiss(); showSubtitleVoiceSettingsDialog()
					}.setNegativeButton(android.R.string.cancel, null).show()
				2 -> MaterialAlertDialogBuilder(this).setTitle("Ses profili")
					.setSingleChoiceItems(arrayOf("Karakter eşleştirmesi", "Kadın / erkek", "Tek ses"), when(prefs.subtitleVoiceMode) { "gender" -> 1; "single" -> 2; else -> 0 }) { dialog, selected ->
						prefs.subtitleVoiceMode = arrayOf("character", "gender", "single")[selected]; dialog.dismiss(); showSubtitleVoiceSettingsDialog()
					}.setNegativeButton(android.R.string.cancel, null).show()
				3 -> MaterialAlertDialogBuilder(this).setTitle("Varsayılan ses cinsiyeti")
					.setSingleChoiceItems(arrayOf("Otomatik", "Kadın", "Erkek"), when(prefs.subtitleVoiceGender) { "female" -> 1; "male" -> 2; else -> 0 }) { dialog, selected ->
						prefs.subtitleVoiceGender = arrayOf("auto", "female", "male")[selected]; dialog.dismiss(); showSubtitleVoiceSettingsDialog()
					}.setNegativeButton(android.R.string.cancel, null).show()
				4, 5, 6 -> {
					val voices = arrayOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
					val current = when(which) { 5 -> prefs.geminiFemaleVoice; 6 -> prefs.geminiMaleVoice; else -> prefs.geminiSpeechVoice }
					val title = when(which) { 5 -> "Kadın Gemini sesi"; 6 -> "Erkek Gemini sesi"; else -> "Tek Gemini sesi" }
					MaterialAlertDialogBuilder(this).setTitle(title)
						.setSingleChoiceItems(voices, voices.indexOf(current).coerceAtLeast(0)) { dialog, selected ->
							when(which) { 5 -> prefs.geminiFemaleVoice = voices[selected]; 6 -> prefs.geminiMaleVoice = voices[selected]; else -> prefs.geminiSpeechVoice = voices[selected] }
							dialog.dismiss(); showSubtitleVoiceSettingsDialog()
						}.setNegativeButton(android.R.string.cancel, null).show()
				}
				7 -> {
					val seek = SeekBar(this).apply { max = 100; progress = prefs.subtitleVoiceVolume }
					val container = android.widget.FrameLayout(this).apply {
						setPadding((24 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (24 * resources.displayMetrics.density).toInt(), 0)
						addView(seek)
					}
					MaterialAlertDialogBuilder(this).setTitle("Ses düzeyi").setView(container)
						.setPositiveButton("Kaydet") { _, _ -> prefs.subtitleVoiceVolume = seek.progress; showSubtitleVoiceSettingsDialog() }
						.setNegativeButton(android.R.string.cancel, null).show()
				}
			}
		}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun chooseCaptionSize()
	{
		val sizes = intArrayOf(8, 10, 12, 14, 18, 22, 26, 30, 36, 42, 48)
		MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_caption_size_label)
			.setSingleChoiceItems(sizes.map { "$it sp" }.toTypedArray(), sizes.indexOf(viewModel.preferences.subtitleCaptionSize)) { dialog, which ->
				viewModel.preferences.subtitleCaptionSize = sizes[which]; applySubtitleAppearance(); dialog.dismiss()
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun chooseCaptionColor(background: Boolean)
	{
		val colors = if(background) intArrayOf(0xD9000000.toInt(), 0x99000000.toInt(), 0xD9071530.toInt(), android.graphics.Color.TRANSPARENT)
		else intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.YELLOW, android.graphics.Color.CYAN, 0xFFFF80AB.toInt(), android.graphics.Color.GREEN)
		val names = if(background) arrayOf("Siyah • %85", "Siyah • %60", "Lacivert • %85", "Saydam")
		else arrayOf("Beyaz", "Sarı", "Camgöbeği", "Pembe", "Yeşil")
		val current = if(background) viewModel.preferences.subtitleCaptionBackgroundColor else viewModel.preferences.subtitleCaptionTextColor
		MaterialAlertDialogBuilder(this).setTitle(if(background) R.string.subtitle_caption_bg_label else R.string.subtitle_caption_color_label)
			.setSingleChoiceItems(names, colors.indexOf(current).coerceAtLeast(0)) { dialog, which ->
				if(background) viewModel.preferences.subtitleCaptionBackgroundColor = colors[which] else viewModel.preferences.subtitleCaptionTextColor = colors[which]
				applySubtitleAppearance(); dialog.dismiss()
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun chooseCaptionFont()
	{
		val names = arrayOf("Sans Serif", "Sans Serif Kalın", "Sans Serif Siyah", "Dar Sans Serif", "Serif", "Serif Kalın", "Monospace", "Çizgi roman havası", "Küçük büyük harf")
		val values = arrayOf("sans-serif", "sans-serif-medium", "sans-serif-black", "sans-serif-condensed", "serif", "serif", "monospace", "casual", "sans-serif-smallcaps")
		MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_caption_font_label)
			.setSingleChoiceItems(names, values.indexOf(viewModel.preferences.subtitleCaptionFont).coerceAtLeast(0)) { dialog, which ->
				viewModel.preferences.subtitleCaptionFont = values[which]; applySubtitleAppearance(); dialog.dismiss()
			}.setNegativeButton(android.R.string.cancel, null).show()
	}

	private fun showCaptionPositionEditor()
	{
		val prefs = viewModel.preferences
		val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
		val editor = object: View(this) {
			var posX = prefs.subtitleCaptionX / 100f
			var posY = prefs.subtitleCaptionY / 100f
			private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
				color = prefs.subtitleCaptionTextColor
				textSize = prefs.subtitleCaptionSize * resources.displayMetrics.scaledDensity
				typeface = android.graphics.Typeface.create(prefs.subtitleCaptionFont, android.graphics.Typeface.BOLD)
				textAlign = android.graphics.Paint.Align.CENTER
			}
			private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = prefs.subtitleCaptionBackgroundColor }
			private val hint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 24f; setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK) }
			private var lastX = 0f
			private var lastY = 0f
			init { isFocusable = true; isFocusableInTouchMode = true }
			override fun onDraw(canvas: android.graphics.Canvas) {
				super.onDraw(canvas)
				canvas.drawColor(0x99000000.toInt())
				val cx = posX * width; val cy = posY * height
				val sample = "Çevrilmiş altyazı burada görünür"
				val measured = text.measureText(sample).coerceAtMost(width * .84f)
				canvas.drawRoundRect(cx - measured / 2 - 24, cy - text.textSize - 16, cx + measured / 2 + 24, cy + 16, 14f, 14f, back)
				canvas.drawText(sample, cx, cy, text)
				hint.textSize = 24f
				canvas.drawText("Yön tuşlarıyla taşı • OK veya Geri kaydet", width / 2f, 42f, hint)
			}
			fun move(code: Int) {
				val step = .005f
				when(code) {
					KeyEvent.KEYCODE_DPAD_LEFT -> posX = (posX - step).coerceIn(.08f, .92f)
					KeyEvent.KEYCODE_DPAD_RIGHT -> posX = (posX + step).coerceIn(.08f, .92f)
					KeyEvent.KEYCODE_DPAD_UP -> posY = (posY - step).coerceIn(.08f, .92f)
					KeyEvent.KEYCODE_DPAD_DOWN -> posY = (posY + step).coerceIn(.08f, .92f)
				}
				invalidate()
			}
			override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
				when(event.action) {
					android.view.MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
					android.view.MotionEvent.ACTION_MOVE -> { posX = (posX + (event.x-lastX)/width).coerceIn(.08f,.92f); posY = (posY + (event.y-lastY)/height).coerceIn(.08f,.92f); lastX=event.x; lastY=event.y; invalidate(); return true }
				}
				return true
			}
		}
		dialog.setContentView(editor)
		dialog.setOnKeyListener { _, keyCode, event ->
			if(event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
			if(keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) { editor.move(keyCode); true }
			else if(keyCode in intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
				prefs.subtitleCaptionX = (editor.posX * 100).toInt(); prefs.subtitleCaptionY = (editor.posY * 100).toInt(); applySubtitleAppearance(); dialog.dismiss(); true
			} else false
		}
		dialog.setOnCancelListener { prefs.subtitleCaptionX = (editor.posX * 100).toInt(); prefs.subtitleCaptionY = (editor.posY * 100).toInt(); applySubtitleAppearance() }
		dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
		dialog.show()
		dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		editor.requestFocus()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
		if(state == StreamStateConnected)
		{
			showOverlay(automatic = true)
			sessionHintHandler.removeCallbacks(sessionHintRunnable)
			sessionHintHandler.postDelayed(sessionHintRunnable, 2500L)
		}
		else sessionHintHandler.removeCallbacks(sessionHintRunnable)

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}

			else -> {}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		val source = event.source or (event.device?.sources ?: 0)
		val fromGameController = (source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
			(source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
		val controllerShortcutKey = event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
		if(fromGameController && controllerShortcutKey && (canStartControllerSubtitleShortcut() || pendingControllerShortcutKeyCode != null))
		{
			if(pendingControllerShortcutFromMotion) return true
			if(pendingControllerShortcutKeyCode == null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
				beginControllerSubtitleShortcut(event.keyCode, fromMotion = false)
			if(pendingControllerShortcutKeyCode == event.keyCode && !pendingControllerShortcutFromMotion)
			{
				if(event.action == KeyEvent.ACTION_UP) finishControllerSubtitleShortcut(event.keyCode, fromMotion = false)
				return true
			}
			if(pendingControllerShortcutKeyCode != null) return true
		}
		if(event.keyCode == KeyEvent.KEYCODE_BACK && fromGameController)
			return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
		if(event.keyCode == KeyEvent.KEYCODE_MENU)
		{
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && binding.overlay.isVisible) hideOverlay()
			return true
		}
		val directionKey = event.keyCode in intArrayOf(
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
		val regionOpenKey = directionKey || event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
		if(subtitleRegionOpenedByHoldKeyCode == event.keyCode)
		{
			if(event.action == KeyEvent.ACTION_UP)
			{
				subtitleRegionOpenedByHoldKeyCode = null
				lastRemoteOkPressAt = 0L
			}
			return true
		}
		if(!binding.subtitleRegionView.isSelecting && binding.overlay.isGone && regionOpenKey && !fromGameController)
		{
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
			{
				openingSubtitleRegionKeyCode = event.keyCode
				subtitleRegionHoldHandler.removeCallbacks(openSubtitleRegionRunnable)
				subtitleRegionHoldHandler.postDelayed(openSubtitleRegionRunnable, 650L)
			}
			else if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && openingSubtitleRegionKeyCode == event.keyCode)
			{
				subtitleRegionHoldHandler.removeCallbacks(openSubtitleRegionRunnable)
				beginSubtitleRegionSelection()
				subtitleRegionOpenedByHoldKeyCode = event.keyCode
				openingSubtitleRegionKeyCode = null
			}
			else if(event.action == KeyEvent.ACTION_UP && openingSubtitleRegionKeyCode == event.keyCode)
			{
				subtitleRegionHoldHandler.removeCallbacks(openSubtitleRegionRunnable)
				openingSubtitleRegionKeyCode = null
				if(event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
				{
					val now = android.os.SystemClock.uptimeMillis()
					if(lastRemoteOkPressAt != 0L && now - lastRemoteOkPressAt <= 650L)
					{
						lastRemoteOkPressAt = 0L
						openOverlayFromDoubleOk()
					}
					else lastRemoteOkPressAt = now
				}
			}
			if(event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) return true
			if((openingSubtitleRegionKeyCode == event.keyCode || subtitleRegionOpenedByHoldKeyCode == event.keyCode) && event.repeatCount > 0) return true
		}
		if(binding.subtitleRegionView.isSelecting && event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER))
		{
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
			{
				subtitleRegionSavedByHold = false
				subtitleRegionHoldHandler.removeCallbacks(saveSubtitleRegionRunnable)
				subtitleRegionHoldHandler.postDelayed(saveSubtitleRegionRunnable, 700L)
			}
			else if(event.action == KeyEvent.ACTION_UP)
			{
				subtitleRegionHoldHandler.removeCallbacks(saveSubtitleRegionRunnable)
				if(!subtitleRegionSavedByHold) binding.subtitleRegionView.toggleResizeMode()
				subtitleRegionSavedByHold = false
			}
			return true
		}
		if(binding.subtitleRegionView.isSelecting && event.keyCode in intArrayOf(
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
			KeyEvent.KEYCODE_DPAD_DOWN))
		{
			if(event.action == KeyEvent.ACTION_DOWN) binding.subtitleRegionView.dispatchKeyEvent(event)
			return true
		}
		if(event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && binding.subtitleRegionView.isSelecting)
		{
			saveSubtitleRegionSelection()
			return true
		}

		if(event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && binding.overlay.isVisible)
		{
			hideOverlay()
			return true
		}

		if(binding.overlay.isVisible && binding.overlay.hasFocus())
		{
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
			if(super.dispatchKeyEvent(event))
				return true
		}

		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}
	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		// Buffer controller D-pad up/down while a hold may invoke a subtitle shortcut.
		// Other axes (sticks, triggers, left/right D-pad) continue to the stream immediately.
		val source = event.source
		val fromGameController = source and android.view.InputDevice.SOURCE_CLASS_JOYSTICK == android.view.InputDevice.SOURCE_CLASS_JOYSTICK ||
			source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
		if(fromGameController)
		{
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val directionKey = when {
				dpadY < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
				dpadY > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
				else -> null
			}
			if(pendingControllerShortcutKeyCode != null && !pendingControllerShortcutFromMotion)
				return viewModel.input.onGenericMotionEvent(event, suppressDpadY = true) || super.dispatchGenericMotionEvent(event)

			if(pendingControllerShortcutFromMotion)
			{
				val pendingKey = pendingControllerShortcutKeyCode
				if(directionKey == null)
				{
					finishControllerSubtitleShortcut(pendingKey ?: return true, fromMotion = true)
					return viewModel.input.onGenericMotionEvent(event, suppressDpadY = true) || super.dispatchGenericMotionEvent(event)
				}
				if(directionKey != pendingKey)
				{
					subtitleRegionHoldHandler.removeCallbacks(controllerShortcutRunnable)
					pendingControllerShortcutKeyCode = null
					pendingControllerShortcutTriggered = false
					beginControllerSubtitleShortcut(directionKey, fromMotion = true)
				}
				return viewModel.input.onGenericMotionEvent(event, suppressDpadY = true) || super.dispatchGenericMotionEvent(event)
			}

			if(directionKey != null && canStartControllerSubtitleShortcut())
			{
				beginControllerSubtitleShortcut(directionKey, fromMotion = true)
				return viewModel.input.onGenericMotionEvent(event, suppressDpadY = true) || super.dispatchGenericMotionEvent(event)
			}
		}
		// Handle controller axes before the focused video surface can consume them.
		return viewModel.input.onGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
	}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
