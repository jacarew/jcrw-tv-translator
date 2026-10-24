// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object AppUpdateChecker
{
	private const val RELEASES_API = "https://api.github.com/repos/jacarew/jcrw-tv-translator/releases/latest"
	private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
	private const val PREFS_NAME = "jcrw_update_check"
	private const val LAST_CHECK_KEY = "last_successful_check"

	fun check(activity: AppCompatActivity)
	{
		val prefs = activity.getSharedPreferences(PREFS_NAME, 0)
		val now = System.currentTimeMillis()
		if(now - prefs.getLong(LAST_CHECK_KEY, 0L) < CHECK_INTERVAL_MS)
			return

		Thread({
			var connection: HttpURLConnection? = null
			try
			{
				connection = URL(RELEASES_API).openConnection() as HttpURLConnection
				connection.connectTimeout = 8000
				connection.readTimeout = 8000
				connection.setRequestProperty("Accept", "application/vnd.github+json")
				connection.setRequestProperty("User-Agent", "Jcrw-Android-TV")
				if(connection.responseCode !in 200..299)
					return@Thread

				val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
				val tag = release.optString("tag_name")
				val releaseUrl = release.optString("html_url")
				val latestCode = tag.removePrefix("v").toIntOrNull() ?: return@Thread
				prefs.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
				val currentCode = @Suppress("DEPRECATION") activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
				if(latestCode <= currentCode || releaseUrl.isBlank())
					return@Thread

				Handler(Looper.getMainLooper()).post {
					if(activity.isFinishing || activity.isDestroyed)
						return@post
					MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.app_update_available_title)
						.setMessage(activity.getString(R.string.app_update_available_message, tag))
						.setNegativeButton(R.string.app_update_later, null)
						.setPositiveButton(R.string.app_update_download) { _, _ ->
							activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
						}
						.show()
				}
			}
			catch(_: Exception)
			{
				// Updates are optional; network or parsing failures must not interrupt startup.
			}
			finally
			{
				connection?.disconnect()
			}
		}, "jcrw-update-check").start()
	}
}
