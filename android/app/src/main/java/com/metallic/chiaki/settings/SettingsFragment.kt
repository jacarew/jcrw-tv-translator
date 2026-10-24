// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.widget.Toast
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ControllerButton
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.stream.SubtitleProjectionHolder
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.onScreenControlsEnabledKey -> preferences.onScreenControlsEnabled
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		"subtitle_translation_enabled" -> preferences.subtitleTranslationEnabled
		"subtitle_voiceover_enabled" -> preferences.subtitleVoiceoverEnabled
		"subtitle_caption_visible" -> preferences.subtitleCaptionVisible
		"stream_hints_enabled" -> preferences.streamHintsEnabled
		preferences.subtitleControllerShortcutsEnabledKey -> preferences.subtitleControllerShortcutsEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.onScreenControlsEnabledKey -> preferences.onScreenControlsEnabled = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			"subtitle_translation_enabled" -> preferences.subtitleTranslationEnabled = value
			"subtitle_voiceover_enabled" -> preferences.subtitleVoiceoverEnabled = value
			"subtitle_caption_visible" -> preferences.subtitleCaptionVisible = value
			"stream_hints_enabled" -> preferences.streamHintsEnabled = value
			preferences.subtitleControllerShortcutsEnabledKey -> preferences.subtitleControllerShortcutsEnabled = value
		}
	}

	override fun getInt(key: String?, defValue: Int) = when(key)
	{
		preferences.subtitleVoiceVolumeKey -> preferences.subtitleVoiceVolume
		"subtitle_caption_size" -> preferences.subtitleCaptionSize
		"subtitle_caption_x" -> preferences.subtitleCaptionX
		"subtitle_caption_y" -> preferences.subtitleCaptionY
		else -> defValue
	}

	override fun putInt(key: String?, value: Int)
	{
		when(key) {
			preferences.subtitleVoiceVolumeKey -> preferences.subtitleVoiceVolume = value
			"subtitle_caption_size" -> preferences.subtitleCaptionSize = value
			"subtitle_caption_x" -> preferences.subtitleCaptionX = value
			"subtitle_caption_y" -> preferences.subtitleCaptionY = value
		}
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		preferences.translationProviderKey -> preferences.translationProvider
		preferences.subtitleOcrEngineKey -> preferences.subtitleOcrEngine
		preferences.subtitleOcrMaxWidthKey -> preferences.subtitleOcrMaxWidth.toString()
		preferences.appLanguageKey -> preferences.appLanguage
		preferences.subtitleTargetLanguageKey -> preferences.subtitleTargetLanguage
		preferences.translationApiKeyKey -> ""
		preferences.translationEndpointKey -> preferences.translationEndpoint
		preferences.subtitleSourceLanguageKey -> preferences.subtitleSourceLanguage
		preferences.subtitleVoiceGenderKey -> preferences.subtitleVoiceGender
		preferences.subtitleVoiceNameKey -> preferences.subtitleVoiceName
		preferences.subtitleSpeechProviderKey -> preferences.subtitleSpeechProvider
		preferences.subtitleVoiceModeKey -> preferences.subtitleVoiceMode
		preferences.geminiSpeechVoiceKey -> preferences.geminiSpeechVoice
		preferences.geminiFemaleVoiceKey -> preferences.geminiFemaleVoice
		preferences.geminiMaleVoiceKey -> preferences.geminiMaleVoice
		preferences.subtitleVoiceGameKey -> preferences.subtitleVoiceGame
		"subtitle_caption_text_color" -> String.format("#%08X", preferences.subtitleCaptionTextColor)
		"subtitle_caption_background_color" -> String.format("#%08X", preferences.subtitleCaptionBackgroundColor)
		"subtitle_caption_font" -> preferences.subtitleCaptionFont
		preferences.geminiSpeechApiKeyKey -> ""
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			preferences.translationProviderKey -> preferences.translationProvider = value ?: "deepl"
			preferences.subtitleOcrEngineKey -> preferences.subtitleOcrEngine = value ?: "mlkit"
			preferences.subtitleOcrMaxWidthKey -> preferences.subtitleOcrMaxWidth = value?.toIntOrNull() ?: 720
			preferences.appLanguageKey -> preferences.appLanguage = value ?: "tr"
			preferences.subtitleTargetLanguageKey -> preferences.subtitleTargetLanguage = value ?: "tr"
			preferences.translationApiKeyKey -> preferences.translationApiKey = value ?: ""
			preferences.translationEndpointKey -> preferences.translationEndpoint = value ?: ""
			preferences.subtitleSourceLanguageKey -> preferences.subtitleSourceLanguage = value ?: "en"
			preferences.subtitleVoiceGenderKey -> preferences.subtitleVoiceGender = value ?: "female"
			preferences.subtitleVoiceNameKey -> preferences.subtitleVoiceName = value ?: ""
			preferences.subtitleSpeechProviderKey -> preferences.subtitleSpeechProvider = value ?: "android"
			preferences.subtitleVoiceModeKey -> preferences.subtitleVoiceMode = value ?: "character"
			preferences.geminiSpeechVoiceKey -> preferences.geminiSpeechVoice = value ?: "Kore"
			preferences.geminiFemaleVoiceKey -> preferences.geminiFemaleVoice = value ?: "Kore"
			preferences.geminiMaleVoiceKey -> preferences.geminiMaleVoice = value ?: "Puck"
			preferences.subtitleVoiceGameKey -> preferences.subtitleVoiceGame = value ?: ""
			preferences.geminiSpeechApiKeyKey -> preferences.geminiSpeechApiKey = value ?: ""
			"subtitle_caption_text_color" -> try { preferences.subtitleCaptionTextColor = android.graphics.Color.parseColor(value ?: "#FFFFFFFF") } catch(_: Exception) {}
			"subtitle_caption_background_color" -> try { preferences.subtitleCaptionBackgroundColor = android.graphics.Color.parseColor(value ?: "#CC000000") } catch(_: Exception) {}
			"subtitle_caption_font" -> preferences.subtitleCaptionFont = value ?: "sans-serif-medium"
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
		private const val PREPARE_SUBTITLE_CAPTURE_REQUEST = 2
		private const val INSTALL_EDGE_TTS_REQUEST = 3
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }
	private lateinit var preferences: Preferences
	private var controllerMappingPreference: Preference? = null
	private var voiceDiscoveryTts: TextToSpeech? = null
	private var pendingEdgeTtsApk: File? = null
	private var edgeInstallerLaunched = false
	private var edgeTtsInstallInProgress = false

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
		controllerMappingPreference = preferenceScreen.findPreference("controller_button_mapping")
		controllerMappingPreference?.setOnPreferenceClickListener { showControllerMappingDialog(); true }

		preferenceScreen.findPreference<ListPreference>(preferences.translationProviderKey)?.let { provider ->
			provider.entryValues = arrayOf("deepl", "openai", "gemini", "libretranslate")
			provider.entries = if(preferences.appLanguage == "en")
				arrayOf("DeepL API — free quota, key required", "OpenAI / GPT — key required, may cost money", "Google Gemini — key required, free quota may apply", "LibreTranslate — free, depends on server")
			else arrayOf("DeepL API — ücretsiz kota, anahtar gerekli", "OpenAI / GPT — anahtar gerekli, ücretli olabilir", "Google Gemini — anahtar gerekli, ücretsiz kota olabilir", "LibreTranslate — ücretsiz, sunucuya bağlı")
			provider.setOnPreferenceChangeListener { _, newValue ->
				if(newValue in listOf("deepl", "openai", "gemini"))
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.subtitle_provider_key_notice_title)
						.setMessage(R.string.subtitle_provider_key_notice)
						.setPositiveButton(android.R.string.ok, null)
						.show()
				true
			}
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleOcrEngineKey)?.let { engine ->
			engine.entryValues = arrayOf("mlkit", "tesseract")
			engine.entries = if(preferences.appLanguage == "en") arrayOf("ML Kit OCR — free, on device", "Tesseract OCR — free, on device")
			else arrayOf("ML Kit OCR — ücretsiz, cihazda", "Tesseract OCR — ücretsiz, cihazda")
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleOcrMaxWidthKey)?.let { resolution ->
			val widths = arrayOf("96", "128", "160", "240", "320", "480", "720")
			resolution.entryValues = widths
			resolution.entries = widths.map { "${it} px" }.toTypedArray()
			resolution.value = preferences.subtitleOcrMaxWidth.toString()
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleSourceLanguageKey)?.let { language ->
			language.entryValues = arrayOf("en", "ja", "fr", "de", "es", "it", "pt", "ko", "zh")
			language.entries = if(preferences.appLanguage == "en") arrayOf("English", "Japanese", "French", "German", "Spanish", "Italian", "Portuguese", "Korean", "Chinese")
			else arrayOf("İngilizce", "Japonca", "Fransızca", "Almanca", "İspanyolca", "İtalyanca", "Portekizce", "Korece", "Çince")
		}
		preferenceScreen.findPreference<ListPreference>(preferences.appLanguageKey)?.let { language ->
			language.entryValues = arrayOf("tr", "en")
			language.entries = arrayOf("Türkçe", "English")
			language.setOnPreferenceChangeListener { _, newValue ->
				preferences.appLanguage = newValue as? String ?: "tr"
				AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(preferences.appLanguage))
				true
			}
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleTargetLanguageKey)?.let { language ->
			val targetValues = arrayOf("tr", "en", "ja", "fr", "de", "es", "it", "pt", "ko", "zh", "ru", "nl", "pl")
			language.entryValues = targetValues
			language.entries = if(preferences.appLanguage == "en") arrayOf("Turkish", "English", "Japanese", "French", "German", "Spanish", "Italian", "Portuguese", "Korean", "Chinese", "Russian", "Dutch", "Polish")
			else arrayOf("Türkçe", "İngilizce", "Japonca", "Fransızca", "Almanca", "İspanyolca", "İtalyanca", "Portekizce", "Korece", "Çince", "Rusça", "Felemenkçe", "Lehçe")
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceGenderKey)?.let { gender ->
			gender.entryValues = arrayOf("female", "male")
			gender.entries = if(preferences.appLanguage == "en") arrayOf("Female voice style", "Male voice style") else arrayOf("Kadın sesi", "Erkek sesi")
			if(preferences.subtitleVoiceGender !in setOf("female", "male")) gender.value = "female"
		}
		val localVoicePreference = preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceNameKey)
		val geminiVoicePreference = preferenceScreen.findPreference<ListPreference>(preferences.geminiSpeechVoiceKey)
		val geminiFemaleVoicePreference = preferenceScreen.findPreference<ListPreference>(preferences.geminiFemaleVoiceKey)
		val geminiMaleVoicePreference = preferenceScreen.findPreference<ListPreference>(preferences.geminiMaleVoiceKey)
		val voiceModePreference = preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceModeKey)
		val gamePreference = preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceGameKey)
		val characterScanPreference = preferenceScreen.findPreference<Preference>("subtitle_voice_character_scan")
		val geminiKeyPreference = preferenceScreen.findPreference<EditTextPreference>(preferences.geminiSpeechApiKeyKey)
		val edgeTtsSetupPreference = preferenceScreen.findPreference<Preference>("subtitle_edge_tts_setup")
		fun updateSpeechPreferenceVisibility(provider: String, mode: String)
		{
			localVoicePreference?.isVisible = provider == "android" || provider == "edge"
			edgeTtsSetupPreference?.isVisible = provider == "edge"
			geminiVoicePreference?.isVisible = provider == "gemini"
			geminiFemaleVoicePreference?.isVisible = provider == "gemini" && mode != "single"
			geminiMaleVoicePreference?.isVisible = provider == "gemini" && mode != "single"
			geminiKeyPreference?.isVisible = provider == "gemini"
			voiceModePreference?.isVisible = true
			gamePreference?.isVisible = mode == "character"
			characterScanPreference?.isVisible = mode == "character"
		}
		voiceModePreference?.let { mode ->
			mode.entryValues = arrayOf("character", "gender", "single")
			mode.entries = if(preferences.appLanguage == "en") arrayOf("Different voice for each character", "Separate female and male voices", "One expressive voice for everyone")
			else arrayOf("Her karaktere ayrı ses", "Kadın ve erkek için ayrı ses", "Herkese tek, vurgulu ses")
			mode.value = preferences.subtitleVoiceMode
			mode.setOnPreferenceChangeListener { _, selected ->
				updateSpeechPreferenceVisibility(preferences.subtitleSpeechProvider, selected as? String ?: "character")
				true
			}
		}
		updateSpeechPreferenceVisibility(preferences.subtitleSpeechProvider, preferences.subtitleVoiceMode)
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleSpeechProviderKey)?.let { provider ->
			provider.entryValues = arrayOf("android", "edge", "gemini")
			provider.entries = if(preferences.appLanguage == "en") arrayOf("Android TTS — free, on device", "Edge TTS — free, online; separate engine required", "Gemini TTS — API key required, online")
			else arrayOf("Android TTS — ücretsiz, cihazda", "Edge TTS — ücretsiz, çevrim içi; ayrı motor gerekir", "Gemini TTS — anahtar gerekli, çevrim içi")
			provider.setOnPreferenceChangeListener { _, selected ->
				if(selected == "gemini") AlertDialog.Builder(requireContext())
					.setTitle(R.string.subtitle_gemini_tts_notice_title)
					.setMessage(R.string.subtitle_gemini_tts_notice)
					.setPositiveButton(android.R.string.ok, null).show()
				if(selected == "edge") AlertDialog.Builder(requireContext())
					.setTitle(R.string.subtitle_edge_tts_setup_title)
					.setMessage(R.string.subtitle_edge_tts_setup_summary)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.subtitle_edge_tts_install) { _, _ -> startEdgeTtsSetup() }.show()
				updateSpeechPreferenceVisibility(selected as? String ?: "android", preferences.subtitleVoiceMode)
				true
			}
		}
		preferenceScreen.findPreference<Preference>("subtitle_edge_tts_setup")?.setOnPreferenceClickListener {
			startEdgeTtsSetup()
			true
		}
		val geminiVoices = arrayOf("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat")
		listOfNotNull(geminiVoicePreference, geminiFemaleVoicePreference, geminiMaleVoicePreference).forEach { voice ->
			voice.entryValues = geminiVoices
			voice.entries = geminiVoices
		}
		geminiKeyPreference?.let { key ->
			key.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
				editText.setText("")
				editText.hint = if(preferences.geminiSpeechApiKey.isBlank()) "Gemini API anahtarı" else "Kayıtlı anahtar var; değiştirmek için yaz"
			}
			key.summary = if(preferences.geminiSpeechApiKey.isBlank()) getString(R.string.subtitle_gemini_tts_key_summary) else "Anahtar bu cihazda şifreli kayıtlı"
			key.setOnPreferenceChangeListener { _, newValue ->
				val value = (newValue as? String).orEmpty()
				preferences.geminiSpeechApiKey = value
				key.summary = if(value.isBlank()) getString(R.string.subtitle_gemini_tts_key_summary) else "Anahtar bu cihazda şifreli kayıtlı"
				true
			}
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceNameKey)?.let { voicePreference ->
			voicePreference.entryValues = arrayOf("")
			voicePreference.entries = arrayOf(if(preferences.appLanguage == "en") "System default" else "Sistem varsayılanı")
			voiceDiscoveryTts = TextToSpeech(requireContext()) { status ->
				val tts = voiceDiscoveryTts
				if(status != TextToSpeech.SUCCESS || tts == null || !isAdded) return@TextToSpeech
				val targetLanguage = java.util.Locale.forLanguageTag(preferences.subtitleTargetLanguage).language
				val available = tts.voices.orEmpty().sortedBy { it.name }
				val matching = available.filter { it.locale.language == targetLanguage }.ifEmpty { available }
				voicePreference.entryValues = (listOf("") + matching.map { it.name }).toTypedArray()
				voicePreference.entries = (listOf(if(preferences.appLanguage == "en") "System default" else "Sistem varsayılanı") + matching.map { it.name.replace('_', ' ').replace('-', ' ') }).toTypedArray()
				if(preferences.subtitleVoiceName !in voicePreference.entryValues.map(CharSequence::toString)) voicePreference.value = ""
				voicePreference.summary = if(matching.isEmpty()) getString(R.string.subtitle_voice_choice_unavailable) else getString(R.string.subtitle_voice_choice_summary)
			}
		}
		preferenceScreen.findPreference<Preference>("help_and_legal")?.setOnPreferenceClickListener {
			showHelpAndLegal()
			true
		}
		preferenceScreen.findPreference<Preference>("setup_tutorial")?.setOnPreferenceClickListener {
			showSetupTutorial()
			true
		}
		preferenceScreen.findPreference<ListPreference>(preferences.subtitleVoiceGameKey)?.let { game ->
			val ids = arrayOf("") + Preferences.voiceGames.keys.toTypedArray()
			val names = arrayOf(if(preferences.appLanguage == "en") "Choose a game" else "Oyun seç") + Preferences.voiceGames.values.toTypedArray()
			game.entryValues = ids
			game.entries = names
			if(preferences.subtitleVoiceGame !in ids) game.value = ""
			game.setOnPreferenceChangeListener { _, selected ->
				val id = selected as? String ?: ""
				preferences.subtitleVoiceGame = id
				preferenceScreen.findPreference<Preference>("subtitle_voice_character_scan")?.summary = if(id.isBlank()) getString(R.string.subtitle_voice_scan_choose_game) else getString(R.string.subtitle_voice_scan_summary)
				true
			}
		}
		preferenceScreen.findPreference<Preference>("subtitle_voice_character_scan")?.let { scan ->
			fun updateScanSummary()
			{
				val gameId = preferences.subtitleVoiceGame
				val count = runCatching { org.json.JSONArray(preferences.voiceRosterJson).length() }.getOrDefault(0)
				scan.summary = when {
					gameId.isBlank() -> getString(R.string.subtitle_voice_scan_choose_game)
					preferences.voiceRosterGame == gameId && count > 0 -> getString(R.string.subtitle_voice_scan_ready, count)
					else -> getString(R.string.subtitle_voice_scan_summary)
				}
			}
			updateScanSummary()
			scan.setOnPreferenceClickListener {
				val gameId = preferences.subtitleVoiceGame
				val gameName = Preferences.voiceGames[gameId].orEmpty()
				if(gameId.isBlank())
				{
					Toast.makeText(requireContext(), R.string.subtitle_voice_scan_choose_game, Toast.LENGTH_LONG).show()
					return@setOnPreferenceClickListener true
				}
				if(preferences.geminiSpeechApiKey.isBlank())
				{
					AlertDialog.Builder(requireContext()).setTitle(R.string.subtitle_gemini_tts_key_title)
						.setMessage(R.string.subtitle_voice_scan_key_required).setPositiveButton(android.R.string.ok, null).show()
					return@setOnPreferenceClickListener true
				}
				AlertDialog.Builder(requireContext()).setTitle(R.string.subtitle_voice_scan_title)
					.setMessage(getString(R.string.subtitle_voice_scan_confirm, gameName))
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.subtitle_voice_scan_start) { _, _ ->
						Toast.makeText(requireContext(), R.string.subtitle_voice_scan_working, Toast.LENGTH_LONG).show()
						Thread {
							try
							{
								val roster = com.metallic.chiaki.session.GeminiCharacterRoster.scan(gameName, preferences.geminiSpeechApiKey)
								preferences.voiceRosterGame = gameId
								preferences.voiceRosterJson = roster.toString()
								activity?.runOnUiThread { updateScanSummary(); Toast.makeText(requireContext(), getString(R.string.subtitle_voice_scan_done, roster.length()), Toast.LENGTH_LONG).show() }
							}
							catch(error: Exception)
							{
								activity?.runOnUiThread { Toast.makeText(requireContext(), getString(R.string.subtitle_voice_scan_failed, error.message ?: ""), Toast.LENGTH_LONG).show() }
							}
						}.start()
					}.show()
				true
			}
		}
		preferenceScreen.findPreference<EditTextPreference>(preferences.translationApiKeyKey)?.let { key ->
			key.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
				editText.setText("")
				editText.hint = if(preferences.appLanguage == "en") {
					if(preferences.translationApiKey.isBlank()) "Enter API key" else "A key is saved; enter a new one to replace it"
				} else if(preferences.translationApiKey.isBlank()) "API anahtarını gir" else "Kayıtlı anahtar var; değiştirmek için yaz"
			}
			key.summary = if(preferences.translationApiKey.isBlank()) {
				if(preferences.appLanguage == "en") "Optional; key is encrypted on this device" else "İsteğe bağlı; anahtar cihazda şifreli saklanır"
			} else if(preferences.appLanguage == "en") "Key saved encrypted (open to replace)" else "Anahtar şifreli olarak kayıtlı (değiştirmek için aç)"
			key.setOnPreferenceChangeListener { _, newValue ->
				val newKey = (newValue as? String).orEmpty()
				if(newKey.isBlank()) preferences.translationApiKey = ""
				key.summary = if(newKey.isBlank()) {
					if(preferences.appLanguage == "en") "Optional; key is encrypted on this device" else "İsteğe bağlı; anahtar cihazda şifreli saklanır"
				} else if(preferences.appLanguage == "en") "Key saved encrypted (open to replace)" else "Anahtar şifreli olarak kayıtlı (değiştirmek için aç)"
				true
			}
		}

		preferenceScreen.findPreference<Preference>("subtitle_translation_prepare")?.let { capture ->
			fun updateSummary()
			{
				capture.summary = if(SubtitleProjectionHolder.hasPreparedProjection)
					getString(R.string.subtitle_translation_prepare_ready)
				else getString(R.string.subtitle_translation_prepare_summary)
			}
			updateSummary()
			capture.setOnPreferenceClickListener {
				if(SubtitleProjectionHolder.hasPreparedProjection)
				{
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.subtitle_translation_prepare_title)
						.setMessage(R.string.subtitle_translation_prepare_ready)
						.setPositiveButton(R.string.subtitle_translation_prepare_again) { _, _ -> requestSubtitleCapturePermission() }
						.setNegativeButton(R.string.subtitle_translation_prepare_stop) { _, _ ->
							SubtitleProjectionHolder.stop()
							updateSummary()
						}
						.setNeutralButton(android.R.string.cancel, null)
						.show()
				}
				else requestSubtitleCapturePermission()
				true
			}
		}
	}

	private fun requestSubtitleCapturePermission()
	{
		val manager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
		startActivityForResult(manager.createScreenCaptureIntent(), PREPARE_SUBTITLE_CAPTURE_REQUEST)
	}

	private fun showHelpAndLegal()
	{
		val text = TextView(requireContext()).apply {
			setPadding(28, 20, 28, 20)
			text = getString(R.string.help_guide_and_legal_content)
			textSize = 16f
			autoLinkMask = android.text.util.Linkify.WEB_URLS
			movementMethod = LinkMovementMethod.getInstance()
		}
		val scroll = ScrollView(requireContext()).apply { addView(text) }
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.help_and_legal_title)
			.setView(scroll)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun showSetupTutorial()
	{
		val text = TextView(requireContext()).apply {
			setPadding(28, 20, 28, 20)
			text = getString(R.string.setup_tutorial_content)
			textSize = 16f
			autoLinkMask = android.text.util.Linkify.WEB_URLS
			movementMethod = LinkMovementMethod.getInstance()
		}
		val scroll = ScrollView(requireContext()).apply { addView(text) }
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.setup_tutorial_title)
			.setView(scroll)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun showControllerMappingDialog()
	{
		val buttons = ControllerButton.values()
		val labels = buttons.map { button ->
			val assignedKey = preferences.controllerButtonKeyCode(button)?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
			getString(R.string.controller_mapping_assigned, getString(button.title), assignedKey ?: getString(R.string.controller_mapping_default))
		}.toTypedArray()

		AlertDialog.Builder(requireContext())
			.setTitle(R.string.controller_mapping_pick_title)
			.setItems(labels) { _, index -> startControllerCapture(buttons[index]) }
			.setNeutralButton(R.string.controller_mapping_reset_all) { _, _ ->
				preferences.clearControllerButtonMappings()
				Toast.makeText(requireContext(), R.string.controller_mapping_reset_done, Toast.LENGTH_SHORT).show()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun startControllerCapture(button: ControllerButton)
	{
		val settingsActivity = activity as? SettingsActivity ?: return
		var captured = false
		val listeningDialog = AlertDialog.Builder(requireContext())
			.setTitle(button.title)
			.setMessage(R.string.controller_mapping_listen_summary)
			.setNegativeButton(android.R.string.cancel) { _, _ -> settingsActivity.cancelControllerButtonCapture() }
			.create()
		// Dialog windows receive controller keys before Activity.dispatchKeyEvent.
		// Listen here as well so the physical face buttons are actually captured.
		listeningDialog.setOnKeyListener { _, _, event -> settingsActivity.captureControllerEvent(event) }
		listeningDialog.setOnCancelListener { if(!captured) settingsActivity.cancelControllerButtonCapture() }
		listeningDialog.setOnDismissListener { if(!captured) settingsActivity.cancelControllerButtonCapture() }
		settingsActivity.captureNextControllerButton { keyCode ->
			captured = true
			preferences.setControllerButtonMapping(button, keyCode)
			listeningDialog.dismiss()
			val keyName = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
			Toast.makeText(requireContext(), getString(R.string.controller_mapping_assigned, getString(button.title), keyName), Toast.LENGTH_LONG).show()
			// The target picker dismisses when a row is selected. Reopen it after
			// recording so the user can map several buttons without re-entering settings.
			showControllerMappingDialog()
		}
		listeningDialog.show()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		voiceDiscoveryTts?.shutdown()
		voiceDiscoveryTts = null
		disposable.dispose()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportDisposable.clear()
		exportAndShareAllSettings(activity).addTo(exportDisposable)
	}

	private fun startEdgeTtsSetup()
	{
		val engineInstalled = runCatching { voiceDiscoveryTts?.engines.orEmpty().any { it.name.contains("edge", ignoreCase = true) } }.getOrDefault(false)
		if(engineInstalled)
		{
			openSystemTtsSettings()
			return
		}
		if(edgeTtsInstallInProgress) return
		edgeTtsInstallInProgress = true
		Toast.makeText(requireContext(), R.string.subtitle_edge_tts_downloading, Toast.LENGTH_LONG).show()
		val appContext = requireContext().applicationContext
		Thread {
			var downloaded: File? = null
			var failure: String? = null
			try
			{
				val releaseConnection = (URL("https://api.github.com/repos/yynag/edge-tts-android/releases/latest").openConnection() as HttpURLConnection).apply {
					connectTimeout = 20000; readTimeout = 30000; setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "Jcrw-TV")
				}
				if(releaseConnection.responseCode !in 200..299) throw IllegalStateException("GitHub indirme listesi açılamadı (HTTP ${releaseConnection.responseCode}).")
				val releaseText = releaseConnection.inputStream.bufferedReader().use { it.readText() }
				releaseConnection.disconnect()
				val assets = org.json.JSONObject(releaseText).getJSONArray("assets")
				val apkAssets = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
					.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
				val abiNames = Build.SUPPORTED_ABIS.map { it.lowercase() }
				val asset = abiNames.firstNotNullOfOrNull { abi -> apkAssets.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) } }
					?: apkAssets.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
					?: apkAssets.singleOrNull()
					?: throw IllegalStateException(appContext.getString(R.string.subtitle_edge_tts_asset_unavailable))
				val expectedSize = asset.optLong("size", -1L)
				val folder = File(appContext.cacheDir, "edge_tts_installer").apply { mkdirs() }
				downloaded = File(folder, "edge-tts-engine.apk")
				val apkConnection = (URL(asset.getString("browser_download_url")).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 60000; setRequestProperty("User-Agent", "Jcrw-TV") }
				if(apkConnection.responseCode !in 200..299) throw IllegalStateException("Edge TTS dosyası indirilemedi (HTTP ${apkConnection.responseCode}).")
				apkConnection.inputStream.use { input -> downloaded!!.outputStream().use { output -> input.copyTo(output) } }
				apkConnection.disconnect()
				if(downloaded!!.length() < 100_000L || (expectedSize > 0 && downloaded!!.length() != expectedSize))
					throw IllegalStateException("Edge TTS APK'sı eksik indi (beklenen: $expectedSize bayt, gelen: ${downloaded!!.length()} bayt).")
				val archiveInfo = appContext.packageManager.getPackageArchiveInfo(downloaded!!.absolutePath, 0)
					?: throw IllegalStateException("İndirilen dosya geçerli bir Android APK'sı değil.")
				val archive = java.util.zip.ZipFile(downloaded!!)
				val hasDex = archive.entries().asSequence().any { it.name.matches(Regex("classes(\\d+)?\\.dex")) }
				archive.close()
				if(!hasDex) throw IllegalStateException("APK içinde uygulama kodu bulunamadı; GitHub dosyası hatalı olabilir.")
				if((archiveInfo.applicationInfo?.minSdkVersion ?: 1) > Build.VERSION.SDK_INT)
					throw IllegalStateException("Bu Edge TTS sürümü TV'nin Android sürümünü desteklemiyor.")
			}
			catch(error: Exception) { failure = error.message ?: error.javaClass.simpleName; downloaded?.delete(); downloaded = null }
			val resultFile = downloaded
			val resultFailure = failure
			activity?.runOnUiThread {
				edgeTtsInstallInProgress = false
				if(!isAdded) { resultFile?.delete(); return@runOnUiThread }
						if(resultFile == null) Toast.makeText(requireContext(), getString(R.string.subtitle_edge_tts_download_failed, resultFailure ?: ""), Toast.LENGTH_LONG).show()
				else { pendingEdgeTtsApk = resultFile; launchEdgeTtsInstallerIfAllowed() }
			}
		}.start()
	}

	private fun launchEdgeTtsInstallerIfAllowed()
	{
		val apk = pendingEdgeTtsApk?.takeIf { it.exists() } ?: return
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !requireContext().packageManager.canRequestPackageInstalls())
		{
			startActivity(Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", android.net.Uri.parse("package:${requireContext().packageName}")))
			return
		}
		try
		{
			val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", apk)
			val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.putExtra(Intent.EXTRA_RETURN_RESULT, true)
			edgeInstallerLaunched = true
			startActivityForResult(intent, INSTALL_EDGE_TTS_REQUEST)
		}
		catch(error: Exception) { Toast.makeText(requireContext(), getString(R.string.subtitle_edge_tts_download_failed, error.message ?: ""), Toast.LENGTH_LONG).show() }
	}

	private fun openSystemTtsSettings()
	{
		try { startActivity(Intent("android.settings.TTS_SETTINGS")) }
		catch(_: Exception) { Toast.makeText(requireContext(), R.string.subtitle_edge_tts_settings_unavailable, Toast.LENGTH_LONG).show() }
	}

	override fun onResume()
	{
		super.onResume()
		if(!edgeInstallerLaunched && pendingEdgeTtsApk?.exists() == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireContext().packageManager.canRequestPackageInstalls())
			launchEdgeTtsInstallerIfAllowed()
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PREPARE_SUBTITLE_CAPTURE_REQUEST)
		{
			val granted = resultCode == Activity.RESULT_OK && data != null &&
				SubtitleProjectionHolder.prepare(requireContext(), resultCode, data)
			val capture = preferenceScreen.findPreference<Preference>("subtitle_translation_prepare")
			capture?.summary = getString(if(granted) R.string.subtitle_translation_prepare_ready else R.string.subtitle_translation_prepare_summary)
			Toast.makeText(requireContext(), getString(if(granted) R.string.subtitle_translation_prepare_granted_toast else R.string.subtitle_translation_prepare_denied_toast), Toast.LENGTH_LONG).show()
			return
		}
		if(requestCode == INSTALL_EDGE_TTS_REQUEST)
		{
			edgeInstallerLaunched = false
			val status = data?.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
			if(resultCode == Activity.RESULT_OK || status == android.content.pm.PackageInstaller.STATUS_SUCCESS)
			{
				pendingEdgeTtsApk?.delete()
				pendingEdgeTtsApk = null
				openSystemTtsSettings()
			}
			else
			{
				val detail = data?.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
				Toast.makeText(requireContext(), "Edge TTS kurulamadı${if(detail.isNotBlank()) ": $detail" else ". APK önbellekte bırakıldı."}", Toast.LENGTH_LONG).show()
			}
			return
		}
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, disposable)
			}
		}
	}
}
