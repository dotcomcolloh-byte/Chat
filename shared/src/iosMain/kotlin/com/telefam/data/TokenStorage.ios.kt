package com.telefam.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.*
import platform.darwin.noErr

/**
 * Real Keychain Services usage — SecItemAdd/CopyMatching/Delete — the same API a
 * banking or password-manager app uses on iOS. kSecAttrAccessibleAfterFirstUnlock
 * keeps tokens available to background tasks (e.g. the outbox sync worker) once the
 * device has been unlocked post-boot, without being readable while still locked.
 */
@OptIn(ExperimentalForeignApi::class)
actual class TokenStorage {

    actual fun saveAccessToken(token: String?) = save(KEY_ACCESS, token)
    actual fun saveRefreshToken(token: String?) = save(KEY_REFRESH, token)
    actual fun saveUserId(id: String?) = save(KEY_USER_ID, id)

    actual fun loadAccessToken(): String? = load(KEY_ACCESS)
    actual fun loadRefreshToken(): String? = load(KEY_REFRESH)
    actual fun loadUserId(): String? = load(KEY_USER_ID)

    actual fun clear() {
        listOf(KEY_ACCESS, KEY_REFRESH, KEY_USER_ID).forEach { deleteItem(it) }
    }

    private fun baseQuery(key: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to key
    )

    private fun save(key: String, value: String?) {
        deleteItem(key) // upsert: remove any existing item first, Keychain add fails on duplicate
        if (value == null) return

        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = baseQuery(key) + mapOf(
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock
        )
        SecItemAdd(query.toCFDictionary(), null)
    }

    private fun load(key: String): String? {
        val query = baseQuery(key) + mapOf(
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne
        )
        memScoped {
            val result = alloc<kotlinx.cinterop.COpaquePointerVar>()
            val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
            if (status != noErr) return null
            val data = result.value?.let { platform.Foundation.CFBridgingRelease(it) } as? NSData ?: return null
            return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
        }
    }

    private fun deleteItem(key: String) {
        SecItemDelete(baseQuery(key).toCFDictionary())
    }

    /** Minimal Kotlin-Map -> CFDictionary bridge for the small, fixed set of keys Keychain calls need here. */
    private fun Map<Any?, Any?>.toCFDictionary(): platform.CoreFoundation.CFDictionaryRef? =
        platform.Foundation.CFBridgingRetain(this) as platform.CoreFoundation.CFDictionaryRef?

    companion object {
        private const val SERVICE = "com.telefam.app.session"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
    }
}
