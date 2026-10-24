// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.jcrw.translator

import android.util.Base64
import android.content.Context

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Creates one subtitle voice clip in the background; callers are responsible for playing and deleting it. */
class GeminiSpeechSynthesizer(context: Context, private val preferences: CompanionPreferences)
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
		val body = JSONObject()
			.put("model", "gemini-3.8-flash-lite-tts")
			.put("input", JSONArray().put(JSONObject()
				.put("type", "user_input")
				.put("content", JSONArray().put(JSONObject()
					.put("type", "text")
					.put("text", text)
					.put("annotations", JSONArray().put(JSONObject()
						.put("type", "speech_metadata")
						.put("style", style)))))))
			.put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/wav"))
			.put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voiceName))))
		val connection = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as HttpURLConnection).apply {
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
			val steps = JSONObject(responseText).optJSONArray("steps") ?: JSONArray()
			var encodedAudio: String? = null
			for(i in 0 until steps.length())
			{
				val step = steps.optJSONObject(i) ?: continue
				if(step.optString("type") != "model_output") continue
				val content = step.optJSONArray("content") ?: continue
				for(j in 0 until content.length())
				{
					val item = content.optJSONObject(j) ?: continue
					if(item.optString("type") == "audio") encodedAudio = item.optString("data").takeIf { it.isNotBlank() }
				}
			}
			val bytes = encodedAudio?.let { Base64.decode(it, Base64.DEFAULT) }
				?: throw IllegalStateException("Gemini yanıtında ses bulunamadı")
			return File(cacheDir, "jcrw-tts-${UUID.randomUUID()}.wav").also { it.writeBytes(bytes) }
		}
		finally { connection.disconnect() }
	}
}
