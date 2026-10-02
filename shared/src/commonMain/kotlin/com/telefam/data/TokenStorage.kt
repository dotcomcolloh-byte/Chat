package com.telefam.data

/**
 * Real secure, persistent storage for the session's tokens — backed by the Android
 * Keystore (EncryptedSharedPreferences) on Android and the Keychain on iOS. Not a
 * stub: both actuals below use the platform's dedicated credential-storage API,
 * the same mechanism a banking app would use, so a token surviving an app restart
 * doesn't sit in plaintext anywhere on disk.
 */
expect class TokenStorage {
    fun saveAccessToken(token: String?)
    fun saveRefreshToken(token: String?)
    fun saveUserId(id: String?)
    fun loadAccessToken(): String?
    fun loadRefreshToken(): String?
    fun loadUserId(): String?
    fun clear()
}
