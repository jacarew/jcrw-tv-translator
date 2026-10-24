package com.jcrw.translator

import android.content.Context
import android.graphics.RectF
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class CompanionPreferences(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    var ocrEngine: String get() = prefs.getString("ocr_engine", "mlkit") ?: "mlkit"; set(v) = prefs.edit().putString("ocr_engine", v).apply()
    var ocrWidth: Int get() = prefs.getInt("ocr_width", 320).coerceIn(96, 720); set(v) = prefs.edit().putInt("ocr_width", v.coerceIn(96, 720)).apply()
    var scanIntervalMs: Int get() = prefs.getInt("ocr_scan_interval_ms", 1500).coerceIn(750, 5000); set(v) = prefs.edit().putInt("ocr_scan_interval_ms",v.coerceIn(750,5000)).apply()
    var performanceProfileApplied: Boolean get() = prefs.getBoolean("performance_profile_v1",false); set(v) = prefs.edit().putBoolean("performance_profile_v1",v).apply()
    var translationProvider: String get() = prefs.getString("translation_provider", "deepl") ?: "deepl"; set(v) = prefs.edit().putString("translation_provider", v).apply()
    var translationEndpoint: String get() = prefs.getString("translation_endpoint", "https://libretranslate.com/translate") ?: "https://libretranslate.com/translate"; set(v) = prefs.edit().putString("translation_endpoint", v).apply()
    var subtitleSourceLanguage: String get() = prefs.getString("source_language", "en") ?: "en"; set(v) = prefs.edit().putString("source_language", v).apply()
    var subtitleTargetLanguage: String get() = prefs.getString("target_language", "tr") ?: "tr"; set(v) = prefs.edit().putString("target_language", v).apply()
    var subtitleRegion: RectF
        get() = RectF(prefs.getFloat("region_left", .12f), prefs.getFloat("region_top", .72f), prefs.getFloat("region_right", .88f), prefs.getFloat("region_bottom", .94f))
        set(v) { prefs.edit().putFloat("region_left",v.left).putFloat("region_top",v.top).putFloat("region_right",v.right).putFloat("region_bottom",v.bottom).apply() }
    var overlayY: Float get() = prefs.getFloat("overlay_y", .08f); set(v) = prefs.edit().putFloat("overlay_y", v.coerceIn(.02f,.9f)).apply()
    var overlayX: Float get() = prefs.getFloat("overlay_x", .5f); set(v) = prefs.edit().putFloat("overlay_x", v.coerceIn(.02f,.98f)).apply()
    var captionTextSize: Int get() = prefs.getInt("caption_text_size", 25).coerceIn(8, 48); set(v) = prefs.edit().putInt("caption_text_size",v.coerceIn(8,48)).apply()
    var captionTextColor: Int get() = prefs.getInt("caption_text_color", android.graphics.Color.WHITE); set(v) = prefs.edit().putInt("caption_text_color",v).apply()
    var captionBackgroundColor: Int get() = prefs.getInt("caption_background_color", 0xD9000000.toInt()); set(v) = prefs.edit().putInt("caption_background_color",v).apply()
    var captionFont: String get() = prefs.getString("caption_font", "sans-serif-medium") ?: "sans-serif-medium"; set(v) = prefs.edit().putString("caption_font",v).apply()
    var quickShortcutEnabled: Boolean get() = prefs.getBoolean("quick_shortcut_enabled", true); set(v) = prefs.edit().putBoolean("quick_shortcut_enabled",v).apply()
    var speechEnabled: Boolean get() = prefs.getBoolean("speech_enabled", false); set(v) = prefs.edit().putBoolean("speech_enabled",v).apply()
    var speechProvider: String get() = prefs.getString("speech_provider", "android") ?: "android"; set(v) = prefs.edit().putString("speech_provider",v).apply()
    var speechGender: String get() = prefs.getString("speech_gender", "female") ?: "female"; set(v) = prefs.edit().putString("speech_gender",v).apply()
    var speechVoice: String get() = prefs.getString("speech_voice", "") ?: ""; set(v) = prefs.edit().putString("speech_voice",v).apply()
    var speechVolume: Int get() = prefs.getInt("speech_volume",80).coerceIn(0,100); set(v) = prefs.edit().putInt("speech_volume",v.coerceIn(0,100)).apply()
    var speechRate: Float get() = prefs.getFloat("speech_rate",1f).coerceIn(.5f,2f); set(v) = prefs.edit().putFloat("speech_rate",v.coerceIn(.5f,2f)).apply()
    var translationApiKey: String get() = readSecret("translation_key"); set(v) = writeSecret("translation_key",v)
    var geminiSpeechApiKey: String get() = readSecret("gemini_tts_key"); set(v) = writeSecret("gemini_tts_key",v)
    var geminiSpeechVoice: String get() = prefs.getString("gemini_tts_voice", "Kore") ?: "Kore"; set(v) = prefs.edit().putString("gemini_tts_voice",v).apply()
    var geminiFemaleVoice: String get() = prefs.getString("gemini_female_voice", "Kore") ?: "Kore"; set(v) = prefs.edit().putString("gemini_female_voice",v).apply()
    var geminiMaleVoice: String get() = prefs.getString("gemini_male_voice", "Puck") ?: "Puck"; set(v) = prefs.edit().putString("gemini_male_voice",v).apply()
    var uiLanguage: String get() = prefs.getString("ui_language", "tr") ?: "tr"; set(v) = prefs.edit().putString("ui_language",v).apply()
    var licenseAccepted: Boolean get() = prefs.getBoolean("license_accepted",false); set(v) = prefs.edit().putBoolean("license_accepted",v).apply()
    var captureRunning: Boolean get() = prefs.getBoolean("capture_running",false); set(v) = prefs.edit().putBoolean("capture_running",v).apply()
    var captureStatus: String get() = prefs.getString("capture_status","Hazır • Çeviri başlamadı") ?: "Hazır • Çeviri başlamadı"; set(v) = prefs.edit().putString("capture_status",v).apply()
    var captureHeartbeat: Long get() = prefs.getLong("capture_heartbeat",0L); set(v) = prefs.edit().putLong("capture_heartbeat",v).apply()
    var translationSource: String get() = prefs.getString("quick_translation_source", "en") ?: "en"; set(v) = prefs.edit().putString("quick_translation_source",v).apply()
    private fun readSecret(name:String):String {
        return try {
            val encoded=prefs.getString(name,null) ?: return ""
            val packed=Base64.decode(encoded,Base64.NO_WRAP); val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(name),GCMParameterSpec(128,packed.copyOfRange(0,12))); String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8)
        } catch(_:Exception){""}
    }
    private fun writeSecret(name:String,value:String) {
        if(value.isBlank()){prefs.edit().remove(name).apply();return}; val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(name)); val bytes=c.iv+c.doFinal(value.toByteArray());prefs.edit().putString(name,Base64.encodeToString(bytes,Base64.NO_WRAP)).apply()
    }
    private fun key(name:String):java.security.Key {
        val alias="jcrw_companion_$name";val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}; (ks.getKey(alias,null) as? java.security.Key)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return gen.generateKey()
    }
}
