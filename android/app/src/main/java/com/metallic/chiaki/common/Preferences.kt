// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

enum class ControllerButton(val id: String, @StringRes val title: Int, val mask: UInt)
{
	CROSS("cross", R.string.controller_button_cross, ControllerState.BUTTON_CROSS),
	MOON("moon", R.string.controller_button_moon, ControllerState.BUTTON_MOON),
	BOX("box", R.string.controller_button_box, ControllerState.BUTTON_BOX),
	PYRAMID("pyramid", R.string.controller_button_pyramid, ControllerState.BUTTON_PYRAMID),
	DPAD_UP("dpad_up", R.string.controller_button_dpad_up, ControllerState.BUTTON_DPAD_UP),
	DPAD_DOWN("dpad_down", R.string.controller_button_dpad_down, ControllerState.BUTTON_DPAD_DOWN),
	DPAD_LEFT("dpad_left", R.string.controller_button_dpad_left, ControllerState.BUTTON_DPAD_LEFT),
	DPAD_RIGHT("dpad_right", R.string.controller_button_dpad_right, ControllerState.BUTTON_DPAD_RIGHT),
	L1("l1", R.string.controller_button_l1, ControllerState.BUTTON_L1),
	R1("r1", R.string.controller_button_r1, ControllerState.BUTTON_R1),
	L2("l2", R.string.controller_button_l2, 0U),
	R2("r2", R.string.controller_button_r2, 0U),
	L3("l3", R.string.controller_button_l3, ControllerState.BUTTON_L3),
	R3("r3", R.string.controller_button_r3, ControllerState.BUTTON_R3),
	SHARE("share", R.string.controller_button_share, ControllerState.BUTTON_SHARE),
	OPTIONS("options", R.string.controller_button_options, ControllerState.BUTTON_OPTIONS),
	TOUCHPAD("touchpad", R.string.controller_button_touchpad, ControllerState.BUTTON_TOUCHPAD),
	PS("ps", R.string.controller_button_ps, ControllerState.BUTTON_PS)
}

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
		val voiceGames = linkedMapOf(
			"spiderman" to "Marvel's Spider-Man",
			"spiderman_miles" to "Marvel's Spider-Man: Miles Morales",
			"god_of_war" to "God of War Ragnarök",
			"last_of_us" to "The Last of Us Part II",
			"ghost_of_tsushima" to "Ghost of Tsushima",
			"horizon" to "Horizon Forbidden West",
			"gtav" to "Grand Theft Auto V",
			"uncharted" to "Uncharted 4"
		)
	}

	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val translationSecretStore = TranslationSecretStore(context)
	private val geminiSpeechSecretStore = TranslationSecretStore(context, "jcrw_gemini_speech_key", "subtitle_gemini_tts_api_key_encrypted")
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	init
	{
		// TV defaults requested for this build: hide the touch controls and use
		// the PlayStation face-button orientation on first launch after upgrade.
		val defaultsAppliedKey = "tv_ps5_defaults_applied_v2"
		if(!sharedPreferences.getBoolean(defaultsAppliedKey, false))
		{
			sharedPreferences.edit()
				.putBoolean(onScreenControlsEnabledKey, false)
				.putBoolean(swapCrossMoonKey, true)
				.putBoolean(defaultsAppliedKey, true)
				.apply()
		}
		// The old 1080p default was stored as an explicit preference on some TVs.
		// Move that old default to 720p once, while preserving any other choice.
		val resolution720pMigrationKey = "resolution_default_720p_migration_v1"
		if(!sharedPreferences.getBoolean(resolution720pMigrationKey, false))
		{
			val editor = sharedPreferences.edit()
			if(sharedPreferences.getString(resolutionKey, null) == Resolution.RES_1080P.value)
				editor.putString(resolutionKey, Resolution.RES_720P.value)
			editor.putBoolean(resolution720pMigrationKey, true).apply()
		}
	}

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, true)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	var translationProvider: String
		get() = sharedPreferences.getString(translationProviderKey, "deepl")?.takeUnless { it == "device" } ?: "deepl"
		set(value) { sharedPreferences.edit().putString(translationProviderKey, value).apply() }
	val translationProviderKey = "subtitle_translation_provider"
	var subtitleOcrEngine: String
		get() = sharedPreferences.getString(subtitleOcrEngineKey, "mlkit") ?: "mlkit"
		set(value) { sharedPreferences.edit().putString(subtitleOcrEngineKey, value).apply() }
	val subtitleOcrEngineKey = "subtitle_ocr_engine"
	var subtitleOcrMaxWidth: Int
		get() = sharedPreferences.getInt(subtitleOcrMaxWidthKey, 720).coerceIn(96, 720)
		set(value) { sharedPreferences.edit().putInt(subtitleOcrMaxWidthKey, value.coerceIn(96, 720)).apply() }
	val subtitleOcrMaxWidthKey = "subtitle_ocr_max_width"
	var appLanguage: String
		get() = sharedPreferences.getString(appLanguageKey, "tr") ?: "tr"
		set(value) { sharedPreferences.edit().putString(appLanguageKey, value).apply() }
	val appLanguageKey = "ui_language"
	var subtitleTargetLanguage: String
		get() = sharedPreferences.getString(subtitleTargetLanguageKey, "tr") ?: "tr"
		set(value) { sharedPreferences.edit().putString(subtitleTargetLanguageKey, value).apply() }
	val subtitleTargetLanguageKey = "subtitle_target_language"
	val translationApiKeyKey = "subtitle_translation_api_key"
	val translationEndpointKey = "subtitle_translation_endpoint"
	var translationEndpoint: String
		get() = sharedPreferences.getString(translationEndpointKey, "https://libretranslate.com/translate") ?: "https://libretranslate.com/translate"
		set(value) { sharedPreferences.edit().putString(translationEndpointKey, value).apply() }
	var translationApiKey: String
		get() = translationSecretStore.read()
		set(value) { translationSecretStore.write(value) }
	var subtitleRegion: RectF
		get() = RectF(
			sharedPreferences.getFloat("subtitle_region_left", .15f),
			sharedPreferences.getFloat("subtitle_region_top", .77f),
			sharedPreferences.getFloat("subtitle_region_right", .85f),
			sharedPreferences.getFloat("subtitle_region_bottom", .91f))
		set(value) { sharedPreferences.edit()
			.putFloat("subtitle_region_left", value.left).putFloat("subtitle_region_top", value.top)
			.putFloat("subtitle_region_right", value.right).putFloat("subtitle_region_bottom", value.bottom).apply() }
	var subtitleRegionConfigured: Boolean
		get() = sharedPreferences.getBoolean("subtitle_region_configured", false)
		set(value) { sharedPreferences.edit().putBoolean("subtitle_region_configured", value).apply() }
	var subtitleTranslationEnabled: Boolean
		get() = sharedPreferences.getBoolean("subtitle_translation_enabled", true)
		set(value) { sharedPreferences.edit().putBoolean("subtitle_translation_enabled", value).apply() }
	var streamHintsEnabled: Boolean
		get() = sharedPreferences.getBoolean("stream_hints_enabled", true)
		set(value) { sharedPreferences.edit().putBoolean("stream_hints_enabled", value).apply() }
	var streamHintIndex: Int
		get() = sharedPreferences.getInt("stream_hint_index", 0)
		set(value) { sharedPreferences.edit().putInt("stream_hint_index", value.coerceAtLeast(0)).apply() }
	var streamHintLastShownAt: Long
		get() = sharedPreferences.getLong("stream_hint_last_shown_at", 0L)
		set(value) { sharedPreferences.edit().putLong("stream_hint_last_shown_at", value.coerceAtLeast(0L)).apply() }
	var subtitleVoiceoverEnabled: Boolean
		get() = sharedPreferences.getBoolean("subtitle_voiceover_enabled", false)
		set(value) { sharedPreferences.edit().putBoolean("subtitle_voiceover_enabled", value).apply() }
	var subtitleCaptionSize: Int
		get() = sharedPreferences.getInt("subtitle_caption_size", 25).coerceIn(8, 48)
		set(value) { sharedPreferences.edit().putInt("subtitle_caption_size", value.coerceIn(8, 48)).apply() }
	var subtitleCaptionTextColor: Int
		get() = sharedPreferences.getInt("subtitle_caption_text_color", android.graphics.Color.WHITE)
		set(value) { sharedPreferences.edit().putInt("subtitle_caption_text_color", value).apply() }
	var subtitleCaptionBackgroundColor: Int
		get() = sharedPreferences.getInt("subtitle_caption_background_color", 0xD9000000.toInt())
		set(value) { sharedPreferences.edit().putInt("subtitle_caption_background_color", value).apply() }
	var subtitleCaptionFont: String
		get() = sharedPreferences.getString("subtitle_caption_font", "sans-serif-medium") ?: "sans-serif-medium"
		set(value) { sharedPreferences.edit().putString("subtitle_caption_font", value).apply() }
	var subtitleCaptionX: Int
		get() = sharedPreferences.getInt("subtitle_caption_x", 50).coerceIn(0, 100)
		set(value) { sharedPreferences.edit().putInt("subtitle_caption_x", value.coerceIn(0, 100)).apply() }
	var subtitleCaptionY: Int
		get() = sharedPreferences.getInt("subtitle_caption_y", 8).coerceIn(0, 100)
		set(value) { sharedPreferences.edit().putInt("subtitle_caption_y", value.coerceIn(0, 100)).apply() }
	var subtitleCaptionVisible: Boolean
		get() = sharedPreferences.getBoolean("subtitle_caption_visible", true)
		set(value) { sharedPreferences.edit().putBoolean("subtitle_caption_visible", value).apply() }
	var subtitleControllerShortcutsEnabled: Boolean
		get() = sharedPreferences.getBoolean(subtitleControllerShortcutsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(subtitleControllerShortcutsEnabledKey, value).apply() }
	val subtitleControllerShortcutsEnabledKey = "subtitle_controller_shortcuts_enabled"
	var subtitleVoiceVolume: Int
		get() = sharedPreferences.getInt(subtitleVoiceVolumeKey, 80).coerceIn(0, 100)
		set(value) { sharedPreferences.edit().putInt(subtitleVoiceVolumeKey, value.coerceIn(0, 100)).apply() }
	val subtitleVoiceVolumeKey = "subtitle_voiceover_volume"
	var subtitleSpeechProvider: String
		get() = sharedPreferences.getString(subtitleSpeechProviderKey, "android") ?: "android"
		set(value) { sharedPreferences.edit().putString(subtitleSpeechProviderKey, value).apply() }
	val subtitleSpeechProviderKey = "subtitle_voiceover_provider"
	var subtitleVoiceMode: String
		get() = sharedPreferences.getString(subtitleVoiceModeKey, "character") ?: "character"
		set(value) { sharedPreferences.edit().putString(subtitleVoiceModeKey, value).apply() }
	val subtitleVoiceModeKey = "subtitle_voiceover_mode"
	var geminiSpeechApiKey: String
		get() = geminiSpeechSecretStore.read()
		set(value) { geminiSpeechSecretStore.write(value) }
	val geminiSpeechApiKeyKey = "subtitle_gemini_tts_api_key"
	var geminiSpeechVoice: String
		get() = sharedPreferences.getString(geminiSpeechVoiceKey, "Kore") ?: "Kore"
		set(value) { sharedPreferences.edit().putString(geminiSpeechVoiceKey, value).apply() }
	val geminiSpeechVoiceKey = "subtitle_gemini_tts_voice"
	var geminiFemaleVoice: String
		get() = sharedPreferences.getString(geminiFemaleVoiceKey, "Kore") ?: "Kore"
		set(value) { sharedPreferences.edit().putString(geminiFemaleVoiceKey, value).apply() }
	val geminiFemaleVoiceKey = "subtitle_gemini_tts_female_voice"
	var geminiMaleVoice: String
		get() = sharedPreferences.getString(geminiMaleVoiceKey, "Puck") ?: "Puck"
		set(value) { sharedPreferences.edit().putString(geminiMaleVoiceKey, value).apply() }
	val geminiMaleVoiceKey = "subtitle_gemini_tts_male_voice"
	var subtitleVoiceGame: String
		get() = sharedPreferences.getString(subtitleVoiceGameKey, "") ?: ""
		set(value) { sharedPreferences.edit().putString(subtitleVoiceGameKey, value).apply() }
	val subtitleVoiceGameKey = "subtitle_voiceover_game"
	var voiceRosterGame: String
		get() = sharedPreferences.getString("subtitle_voice_roster_game", "") ?: ""
		set(value) { sharedPreferences.edit().putString("subtitle_voice_roster_game", value).apply() }
	var voiceRosterJson: String
		get() = sharedPreferences.getString("subtitle_voice_roster_json", "[]") ?: "[]"
		set(value) { sharedPreferences.edit().putString("subtitle_voice_roster_json", value).apply() }
	private fun voiceCharacterForSpeaker(speaker: String): org.json.JSONObject?
	{
		if(speaker.isBlank() || voiceRosterGame != subtitleVoiceGame) return null
		val normalized = speaker.trim().lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
		return try {
			val roster = org.json.JSONArray(voiceRosterJson)
			(0 until roster.length()).asSequence().mapNotNull { roster.optJSONObject(it) }.firstOrNull { character ->
				val names = listOf(character.optString("name")) + (character.optJSONArray("aliases")?.let { aliases -> (0 until aliases.length()).map { aliases.optString(it) } } ?: emptyList())
				names.any { it.trim().lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "") == normalized }
			}
		} catch(_: Exception) { null }
	}
	fun voiceGenderForSpeaker(speaker: String): String? = voiceCharacterForSpeaker(speaker)?.optString("gender")?.takeIf { it == "female" || it == "male" }
	fun voiceNameForSpeaker(speaker: String): String? = voiceCharacterForSpeaker(speaker)?.optString("voice")?.takeIf { it.isNotBlank() }
	var subtitleVoiceGender: String
		get() = sharedPreferences.getString(subtitleVoiceGenderKey, "auto") ?: "auto"
		set(value) { sharedPreferences.edit().putString(subtitleVoiceGenderKey, value).apply() }
	val subtitleVoiceGenderKey = "subtitle_voiceover_gender"
	var subtitleVoiceName: String
		get() = sharedPreferences.getString(subtitleVoiceNameKey, "") ?: ""
		set(value) { sharedPreferences.edit().putString(subtitleVoiceNameKey, value).apply() }
	val subtitleVoiceNameKey = "subtitle_voiceover_voice"
	val subtitleSourceLanguageKey = "subtitle_source_language"
	var subtitleSourceLanguage: String
		get() = sharedPreferences.getString(subtitleSourceLanguageKey, "en") ?: "en"
		set(value) { sharedPreferences.edit().putString(subtitleSourceLanguageKey, value).apply() }

	private fun controllerMappingKey(button: ControllerButton) = "controller_mapping_${button.id}"
	fun controllerButtonKeyCode(button: ControllerButton): Int? = sharedPreferences.getInt(controllerMappingKey(button), -1).takeIf { it >= 0 }
	val controllerButtonMappings: Map<Int, ControllerButton>
		get() = ControllerButton.values().mapNotNull { button ->
			controllerButtonKeyCode(button)?.let { it to button }
		}.toMap()

	fun setControllerButtonMapping(button: ControllerButton, keyCode: Int)
	{
		val editor = sharedPreferences.edit()
		// One physical input should not trigger two different PlayStation buttons.
		ControllerButton.values().filter { it != button && controllerButtonKeyCode(it) == keyCode }
			.forEach { editor.remove(controllerMappingKey(it)) }
		editor.putInt(controllerMappingKey(button), keyCode).apply()
	}

	fun clearControllerButtonMapping(button: ControllerButton) = sharedPreferences.edit().remove(controllerMappingKey(button)).apply()
	fun clearControllerButtonMappings() = sharedPreferences.edit().also { editor ->
		ControllerButton.values().forEach { editor.remove(controllerMappingKey(it)) }
	}.apply()

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(50000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}
}
