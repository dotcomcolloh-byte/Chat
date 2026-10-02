package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class DirectoryUserDto(val userId: String, val username: String?, val fullName: String?)

class UserDirectoryApi(private val client: HttpClient) {
    suspend fun search(query: String): List<DirectoryUserDto> {
        val response = client.get("${ApiConfig.baseUrl}/api/users/search") { parameter("q", query) }
        return if (response.status.isSuccess()) response.body() else emptyList()
    }

    /** Exact (case-insensitive) username lookup, used when a message's @mention is tapped. Null = no such user; throws on network/server errors. */
    suspend fun lookupByUsername(username: String): DirectoryUserDto? {
        val response = client.get("${ApiConfig.baseUrl}/api/users/by-username/$username")
        return when {
            response.status.isSuccess() -> response.body()
            response.status == HttpStatusCode.NotFound -> null
            else -> error("Directory lookup failed: ${response.status}")
        }
    }

    suspend fun lookup(userId: String): DirectoryUserDto? {
        val response = client.get("${ApiConfig.baseUrl}/api/users/$userId")
        return if (response.status.isSuccess()) response.body() else null
    }
}
