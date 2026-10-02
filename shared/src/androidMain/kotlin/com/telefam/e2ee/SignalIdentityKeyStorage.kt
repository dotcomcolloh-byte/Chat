package com.telefam.e2ee

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Signal Protocol long-term identity PRIVATE key never leaves this Keystore-backed
 * store and never crosses the network — only identityPublicKey (via SignalEngine's
 * RegisterDeviceRequestDto) is ever sent to the server. Separate encrypted file from
 * the auth TokenStorage so a leak/bug in one doesn't expose the other.
 */
class SignalIdentityKeyStorage(context: Context) {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "telefam_signal_identity", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var identityKeyPairBytes: ByteArray?
        get() = prefs.getString(KEY_IDENTITY, null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        set(value) { prefs.edit().putString(KEY_IDENTITY, value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }).apply() }

    var registrationId: Int?
        get() = if (prefs.contains(KEY_REG_ID)) prefs.getInt(KEY_REG_ID, 0) else null
        set(value) { prefs.edit().putInt(KEY_REG_ID, value ?: 0).apply() }

    var localDeviceId: Int
        get() = prefs.getInt(KEY_DEVICE_ID, 1)
        set(value) { prefs.edit().putInt(KEY_DEVICE_ID, value).apply() }

    companion object {
        private const val KEY_IDENTITY = "identity_key_pair"
        private const val KEY_REG_ID = "registration_id"
        private const val KEY_DEVICE_ID = "local_device_id"
    }
}
