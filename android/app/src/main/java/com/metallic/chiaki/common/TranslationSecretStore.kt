// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.preference.PreferenceManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Keeps the optional provider key encrypted at rest with an Android Keystore AES key. */
class TranslationSecretStore(context: Context, private val alias: String = "chiaki_subtitle_translation_key", private val keyName: String = "subtitle_translation_api_key_encrypted")
{
	private val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	fun read(): String
	{
		val encoded = preferences.getString(keyName, null) ?: return ""
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ""
		return try
		{
			val packed = Base64.decode(encoded, Base64.NO_WRAP)
			val iv = packed.copyOfRange(0, 12)
			val encrypted = packed.copyOfRange(12, packed.size)
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
			String(cipher.doFinal(encrypted), Charsets.UTF_8)
		}
		catch(_: Exception) { "" }
	}

	fun write(value: String)
	{
		if(value.isBlank())
		{
			preferences.edit().remove(keyName).apply()
			return
		}
		check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Encrypted API keys require Android 6.0 or newer" }
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, getKey())
		val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		val packed = cipher.iv + encrypted
		preferences.edit().putString(keyName, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
	}

	private fun getKey(): java.security.Key
	{
		val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(keyStore.getKey(alias, null) as? java.security.Key)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setRandomizedEncryptionRequired(true)
			.build())
		return generator.generateKey()
	}
}
