// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.util.Base64
import android.content.Context
import com.metallic.chiaki.common.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Creates one subtitle voice clip in the background; callers are responsible for playing and deleting it. */
class GeminiSpeechSynthesizer(context: Context, private val preferences: Preferences)
{
	private val cacheDir = context.applicationContext.cacheDir
	fun synthesize(text: String, gender: String, voiceName: String): File
	{
		val key = preferences.geminiSpeechApiKey.trim().takeIf { it.isNotEmpty() }
			?: throw IllegalStateException("Gemini TTS için Ayarlar bölümüne API anahtarı girin")
		val genderStyle = when(gender) {
			"male" -> "Use a masculine-sounding Turkish voice."
			"female" -> "Use a feminine-sounding Turkish voice."
			else -> "Use a natural Turkish voice without forcing a gendered quality."
		}
		val style = "$genderStyle Speak expressively with natural emphasis and emotion that fits the dialogue; stress important words, use realistic conversational rhythm and brief pauses, and avoid flat or exaggerated delivery. Keep the wording unchanged."
		// Use Google's GenerateContent REST schema. Keep this request compatible
		// with the documented Gemini 3.8 TTS endpoint used by current API keys.
		val body = JSONObject()
			.put("contents", JSONArray().put(JSONObject()
				.put("role", "user")
				.put("parts", JSONArray().put(JSONObject()
					.put("text", text)
					.put("speech_metadata", JSONObject().put("style", style))))))
			.put("generationConfig", JSONObject()
				.put("responseModalities", JSONArray().put("AUDIO"))
				.put("speechConfig", JSONObject()
					.put("languageCode", "tr-TR")
					.put("voiceConfig", JSONObject().put("voice", voiceName))))
		val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite-tts:generateContent").openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 8000
			readTimeout = 25000
			doOutput = true
			setRequestProperty("x-goog-api-key", key)
			setRequestProperty("Content-Type", "application/json; charset=utf-8")
		}
		try
		{
			connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
			val code = connection.responseCode
			val responseText = (if(code in 200..299) connection.inputStream else connection.errorStream)
				?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			if(code !in 200..299) throw IllegalStateException("Gemini TTS API hatası ($code): ${responseText.take(220)}")
			val candidates = JSONObject(responseText).optJSONArray("candidates") ?: JSONArray()
			var encodedAudio: String? = null
			for(i in 0 until candidates.length())
			{
				val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
				for(j in 0 until parts.length())
				{
					val inlineData = parts.optJSONObject(j)?.optJSONObject("inlineData") ?: continue
					if(inlineData.optString("mimeType").startsWith("audio/"))
						encodedAudio = inlineData.optString("data").takeIf { it.isNotBlank() }
				}
			}
			val bytes = encodedAudio?.let { Base64.decode(it, Base64.DEFAULT) }
				?: throw IllegalStateException("Gemini yanıtında ses bulunamadı")
			return File(cacheDir, "jcrw-tts-${UUID.randomUUID()}.wav").also { it.writeBytes(bytes) }
		}
		finally { connection.disconnect() }
	}
}
