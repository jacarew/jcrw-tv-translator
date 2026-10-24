// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One-time game-cast lookup. The returned name/gender list is stored locally by the caller. */
object GeminiCharacterRoster
{
	private val voices = listOf("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat")

	fun scan(gameName: String, apiKey: String): JSONArray
	{
		if(gameName.isBlank()) throw IllegalArgumentException("Önce oynanacak oyunu seçin")
		if(apiKey.isBlank()) throw IllegalArgumentException("Gemini API anahtarını ayarlara girin")
		val prompt = "For the video game \"$gameName\", list up to 30 important named speaking characters. Return only a JSON array with objects containing name (canonical character name in English), subtitleLabels (array of likely exact speaker labels shown before a colon in game subtitles, including aliases such as Spider-Man if the character is Peter Parker), and gender (female, male, or unknown). Do not include actors, descriptions, or invented names. Include only characters whose gender is reasonably established."
		val body = JSONObject()
			.put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
			.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
		val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 8000
			readTimeout = 20000
			doOutput = true
			setRequestProperty("Content-Type", "application/json; charset=utf-8")
		}
		try
		{
			connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
			val code = connection.responseCode
			val response = (if(code in 200..299) connection.inputStream else connection.errorStream)
				?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			if(code !in 200..299) throw IllegalStateException("Gemini karakter taraması başarısız ($code): ${response.take(180)}")
			val modelText = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
				.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
			val arrayText = modelText.substringAfter('[', "[").substringBeforeLast(']', "]")
			val parsed = JSONArray("[$arrayText]")
			val characters = mutableListOf<JSONObject>()
			for(i in 0 until parsed.length())
			{
				val entry = parsed.optJSONObject(i) ?: continue
				val name = entry.optString("name").trim()
				val gender = entry.optString("gender").lowercase()
				if(name.isNotEmpty())
				{
					val normalizedGender = if(gender == "female" || gender == "male") gender else "unknown"
					val labels = entry.optJSONArray("subtitleLabels") ?: JSONArray()
					val aliases = JSONArray()
					for(labelIndex in 0 until labels.length()) labels.optString(labelIndex).trim().takeIf { it.isNotEmpty() }?.let(aliases::put)
					characters += JSONObject().put("name", name).put("aliases", aliases).put("gender", normalizedGender)
				}
			}
			if(characters.isEmpty()) throw IllegalStateException("Gemini kullanılabilir karakter adı döndürmedi")
			// A distinct Gemini voice is assigned to every canonical character; aliases reuse that character's voice.
			val orderedVoices = voices.shuffled(java.util.Random(gameName.hashCode().toLong()))
			characters.forEachIndexed { index, character -> character.put("voice", orderedVoices[index % orderedVoices.size]) }
			val result = JSONArray()
			characters.forEach(result::put)
			return result
		}
		finally { connection.disconnect() }
	}
}
