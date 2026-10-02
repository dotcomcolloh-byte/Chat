package com.telefam.chat

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class NominatimResult(val display_name: String, val lat: String, val lon: String)

/** OpenStreetMap's free, keyless place search - no paid API token required. */
class PlaceSearchApi(private val client: HttpClient) {
    suspend fun search(query: String): List<LocationData> {
        if (query.isBlank()) return emptyList()
        val response = client.get("https://nominatim.openstreetmap.org/search") {
            parameter("q", query)
            parameter("format", "json")
            parameter("limit", "8")
            header(HttpHeaders.UserAgent, "Telefam/1.0")
        }
        val results: List<NominatimResult> = response.body()
        return results.mapNotNull { r ->
            val lat = r.lat.toDoubleOrNull() ?: return@mapNotNull null
            val lon = r.lon.toDoubleOrNull() ?: return@mapNotNull null
            LocationData(lat, lon, r.display_name)
        }
    }
}
