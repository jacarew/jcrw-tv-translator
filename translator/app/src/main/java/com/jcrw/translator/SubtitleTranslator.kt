// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.jcrw.translator


import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Provider adapter used by the subtitle OCR/overlay pipeline. Never sends frame images, only OCR text. */
class SubtitleTranslator(private val preferences: CompanionPreferences)
{
	companion object {
		private val networkExecutor = Executors.newSingleThreadExecutor()
		private fun <T> failedFuture(error: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(error) }
	}

	fun translate(text: String, sourceLanguage: String = "en"): CompletableFuture<String>
	{
		val input = text.trim()
		if(input.isEmpty()) return CompletableFuture.completedFuture("")
		val lines = input.lines()
		val speakerPattern = Regex("^(\\s*[^:\\r\\n]{1,40}:\\s*)(.*)$")
		val prefixes = mutableListOf<String>()
		val dialogue = mutableListOf<String>()
		var pendingSpeaker = ""
		for(line in lines)
		{
			val match = speakerPattern.matchEntire(line)
			if(match != null)
			{
				val prefix = pendingSpeaker + match.groupValues[1]
				val body = match.groupValues[2]
				if(body.isBlank()) pendingSpeaker = prefix
				else { prefixes += prefix; dialogue += body; pendingSpeaker = "" }
			}
			else if(line.isNotBlank())
			{
				prefixes += pendingSpeaker
				dialogue += line
				pendingSpeaker = ""
			}
		}
		if(dialogue.isEmpty()) return CompletableFuture.completedFuture(input)
		val bodyText = dialogue.joinToString("\n")
		val translated = translateBody(bodyText, sourceLanguage)
		if(prefixes.all { it.isEmpty() }) return translated
		return translated.thenApply { result ->
			val translatedLines = result.lines()
			dialogue.indices.joinToString("\n") { index ->
				prefixes[index] + (translatedLines.getOrNull(index) ?: translatedLines.lastOrNull().orEmpty())
			}
		}
	}

	private fun translateBody(input: String, sourceLanguage: String): CompletableFuture<String>
	{
		return when(preferences.translationProvider)
		{
			"mymemory" -> networkTranslation { translateWithMyMemory(input, sourceLanguage) }
			"openai" -> networkTranslation { translateWithOpenAi(input) }
			"gemini" -> networkTranslation { translateWithGemini(input) }
			"deepl" -> networkTranslation { translateWithDeepL(input) }
			"libretranslate" -> networkTranslation { translateWithLibre(input) }
			else -> failedFuture(IllegalArgumentException("Bilinmeyen çeviri sağlayıcısı"))
		}
	}

	private fun networkTranslation(block: () -> String): CompletableFuture<String> = CompletableFuture.supplyAsync(block, networkExecutor)

	private fun requireApiKey(): String = preferences.translationApiKey.trim().takeIf { it.isNotEmpty() }
		?: throw IllegalStateException("Seçilen sağlayıcı için Ayarlar bölümüne API anahtarı girilmeli")

	private fun translateWithOpenAi(text: String): String
	{
		val body = JSONObject().put("model", "gpt-4.1-mini")
			.put("instructions", "Translate the supplied video-game subtitle into natural ${targetLanguageName()}. Return only the translation, preserving line breaks and names.")
			.put("input", text)
		val response = request("https://api.openai.com/v1/responses", "Bearer ${requireApiKey()}", body.toString(), "application/json")
		val root = JSONObject(response)
		val output = root.optJSONArray("output") ?: JSONArray()
		for(i in 0 until output.length())
		{
			val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
			for(j in 0 until content.length())
			{
				val item = content.optJSONObject(j) ?: continue
				if(item.optString("type") == "output_text") return item.optString("text")
			}
		}
		throw IllegalStateException("OpenAI yanıtında çevrilmiş metin bulunamadı")
	}

	private fun translateWithGemini(text: String): String
	{
		val apiKey = URLEncoder.encode(requireApiKey(), "UTF-8")
		val prompt = "Translate this video-game subtitle into natural ${targetLanguageName()}. Return only the translation, preserving line breaks and names:\n$text"
		val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
		val response = request("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey", null, body.toString(), "application/json")
		return JSONObject(response).getJSONArray("candidates").getJSONObject(0)
			.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
	}

	private fun translateWithDeepL(text: String): String
	{
		val apiKey = requireApiKey()
		val body = "text=${form(text)}&target_lang=${form(deepLTargetCode())}"
		// Legacy DeepL API Free keys end in :fx and use the free host. New Developer
		// keys use api.deepl.com, as shown in DeepL's current developer quickstart.
		val apiHost = if(apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
		val response = request("https://$apiHost/v2/translate", "DeepL-Auth-Key $apiKey", body, "application/x-www-form-urlencoded")
		return JSONObject(response).getJSONArray("translations").getJSONObject(0).getString("text")
	}

	private fun translateWithLibre(text: String): String
	{
		val body = JSONObject().put("q", text).put("source", "auto").put("target", preferences.subtitleTargetLanguage.lowercase()).put("format", "text")
		val key = preferences.translationApiKey.trim()
		if(key.isNotEmpty()) body.put("api_key", key)
		val response = request(preferences.translationEndpoint.trim(), null, body.toString(), "application/json")
		return JSONObject(response).getString("translatedText")
	}

	private fun translateWithMyMemory(text: String, sourceLanguage: String): String
	{
		val source = when(sourceLanguage.trim().lowercase()) { "", "auto", "autodetect" -> "autodetect"; else -> sourceLanguage.trim().lowercase() }
		val target = preferences.subtitleTargetLanguage.trim().lowercase()
		val output = text.lines().map { line ->
			if(line.isBlank()) return@map line
			if(line.toByteArray(Charsets.UTF_8).size > 500) {
				throw IllegalArgumentException("MyMemory ücretsiz servisi satır başına en fazla 500 bayt kabul ediyor. Daha kısa altyazı alanı seçin veya başka bir motor kullanın.")
			}
			val url = "https://api.mymemory.translated.net/get?q=${form(line)}&langpair=${form("$source|$target")}&mt=1"
			val connection = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = 8000
				readTimeout = 12000
				setRequestProperty("Accept", "application/json")
				setRequestProperty("User-Agent", "Jcrw-Translator-Android-TV")
			}
			try {
				val code = connection.responseCode
				val stream = if(code in 200..299) connection.inputStream else connection.errorStream
				val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
				val root = JSONObject(response)
				if(code !in 200..299 || root.optInt("responseStatus", code) != 200) {
					throw IllegalStateException(root.optString("responseDetails").ifBlank { "MyMemory isteği başarısız oldu (HTTP $code)." })
				}
				root.optJSONObject("responseData")?.optString("translatedText")
					?.takeIf { it.isNotBlank() }
					?: throw IllegalStateException("MyMemory yanıtında çeviri bulunamadı.")
			} finally {
				connection.disconnect()
			}
		}
		return output.joinToString("\n")
	}

	private fun deepLTargetCode(): String = when(preferences.subtitleTargetLanguage.lowercase())
	{
		"en" -> "EN-US"
		"pt" -> "PT-PT"
		"zh" -> "ZH-HANS"
		else -> preferences.subtitleTargetLanguage.uppercase()
	}

	private fun targetLanguageName(): String = when(preferences.subtitleTargetLanguage.lowercase())
	{
		"tr" -> "Turkish"; "en" -> "English"; "ja" -> "Japanese"; "fr" -> "French"
		"de" -> "German"; "es" -> "Spanish"; "it" -> "Italian"; "pt" -> "Portuguese"
		"ko" -> "Korean"; "zh" -> "Chinese"; "ru" -> "Russian"; "nl" -> "Dutch"
		"pl" -> "Polish"; else -> "Turkish"
	}

	private fun form(value: String) = URLEncoder.encode(value, "UTF-8")

	private fun request(url: String, authorization: String?, body: String, contentType: String): String
	{
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 8000
			readTimeout = 12000
			doOutput = true
			setRequestProperty("Content-Type", "$contentType; charset=utf-8")
			if(authorization != null) setRequestProperty("Authorization", authorization)
		}
		try
		{
			connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			val code = connection.responseCode
			val stream = if(code in 200..299) connection.inputStream else connection.errorStream
			val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			if(code !in 200..299) throw IllegalStateException("Çeviri API hatası ($code): ${response.take(300)}")
			return response
		}
		finally { connection.disconnect() }
	}
}
