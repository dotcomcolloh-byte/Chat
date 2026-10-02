package com.telefam.data

/**
 * In-memory session state, now backed by real persistent secure storage
 * (TokenStorage — Android Keystore / iOS Keychain) instead of only living in
 * memory. Call AuthSession.init(tokenStorage) once at app launch to restore
 * whatever session survived the restart; every subsequent assignment to
 * accessToken/refreshToken/currentUserId is written straight through to that
 * storage, so nothing extra needs to be remembered at each call site.
 */
object AuthSession {
    private var storage: TokenStorage? = null
    private var restoring = false

    var accessToken: String? = null
        set(value) {
            field = value
            if (!restoring) storage?.saveAccessToken(value)
        }

    var refreshToken: String? = null
        set(value) {
            field = value
            if (!restoring) storage?.saveRefreshToken(value)
        }

    var currentUserId: String? = null
        set(value) {
            field = value
            if (!restoring) storage?.saveUserId(value)
        }

    /** Call once at app launch, before building the UI, so a restart resumes an existing session. */
    fun init(tokenStorage: TokenStorage) {
        storage = tokenStorage
        restoring = true
        accessToken = tokenStorage.loadAccessToken()
        refreshToken = tokenStorage.loadRefreshToken()
        currentUserId = tokenStorage.loadUserId()
        restoring = false
    }

    fun hasActiveSession(): Boolean = accessToken != null

    /** Call on logout, or when the backend reports the refresh token was reused/revoked (see JwtService). */
    fun clear() {
        accessToken = null
        refreshToken = null
        currentUserId = null
        storage?.clear()
    }
}
