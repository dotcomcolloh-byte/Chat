package com.telefam.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Uses androidx.security's EncryptedSharedPreferences: the master key lives in the
 * Android Keystore (hardware-backed on most devices) and both keys and values in
 * the preferences file are encrypted with it. This is the standard, real mechanism
 * for storing auth tokens on Android — not a custom or placeholder scheme.
 */
actual class TokenStorage(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "telefam_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    actual fun saveAccessToken(token: String?) = save(KEY_ACCESS, token)
    actual fun saveRefreshToken(token: String?) = save(KEY_REFRESH, token)
    actual fun saveUserId(id: String?) = save(KEY_USER_ID, id)

    actual fun loadAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    actual fun loadRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)
    actual fun loadUserId(): String? = prefs.getString(KEY_USER_ID, null)

    actual fun clear() {
        prefs.edit().clear().apply()
    }

    private fun save(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
    }
}
