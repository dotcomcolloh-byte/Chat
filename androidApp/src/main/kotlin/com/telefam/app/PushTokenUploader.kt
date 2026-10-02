package com.telefam.app

import android.content.Context
import com.telefam.data.AuthSession
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.createTelefamHttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uploads the FCM registration token to the backend so calls can ring when the app is closed. */
object PushTokenUploader {
    private val mutex = Mutex()
    private var lastUploaded: String? = null

    suspend fun upload(context: Context, token: String) {
        mutex.withLock {
            if (token == lastUploaded) return
            if (!AuthSession.hasActiveSession()) {
                // No session yet (fresh install): MainActivity re-attempts after login.
                return
            }
            val client = createTelefamHttpClient()
            runCatching {
                client.post("${ApiConfig.baseUrl.trimEnd('/')}/api/calls/devices") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"platform":"android","token":"$token"}""")
                }
            }.onSuccess { lastUploaded = token }
            client.close()
        }
    }
}
