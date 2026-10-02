package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class MuteRequestBody(val peerId: String, val mutedUntilEpochSeconds: Long?)
@Serializable data class ChatPreferenceDto(val peerId: String, val mutedUntil: String?)
@Serializable data class ReportRequestBody(val reportedUserId: String, val reason: String)

class ChatPreferencesApi(private val client: HttpClient) {
    suspend fun setMute(peerId: String, mutedUntilEpochSeconds: Long?) =
        client.post("${ApiConfig.baseUrl}/api/chat-prefs/mute") {
            contentType(ContentType.Application.Json); setBody(MuteRequestBody(peerId, mutedUntilEpochSeconds))
        }

    suspend fun getPreference(peerId: String) = client.get("${ApiConfig.baseUrl}/api/chat-prefs/$peerId")
    suspend fun listAll() = client.get("${ApiConfig.baseUrl}/api/chat-prefs")
}

class ReportApi(private val client: HttpClient) {
    suspend fun submit(reportedUserId: String, reason: String) =
        client.post("${ApiConfig.baseUrl}/api/reports") {
            contentType(ContentType.Application.Json); setBody(ReportRequestBody(reportedUserId, reason))
        }
}
