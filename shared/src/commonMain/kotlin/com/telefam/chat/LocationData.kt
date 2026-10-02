package com.telefam.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(json: String): LocationData? = runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/** Standard OSM "slippy map" tile math (wiki.openstreetmap.org/wiki/Slippy_map_tilenames) - free, keyless tiles, no paid map token. */
object MapTiles {
    const val ZOOM = 16

    private fun lonToTileX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom)
    private fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = lat * PI / 180.0
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)
    }

    /** URLs for a 3x3 tile grid centered on (lat, lon), composited client-side into one map image. */
    fun centeredGridUrls(lat: Double, lon: Double, zoom: Int = ZOOM): List<List<String>> {
        val centerTileX = floor(lonToTileX(lon, zoom)).toInt()
        val centerTileY = floor(latToTileY(lat, zoom)).toInt()
        return (-1..1).map { dy -> (-1..1).map { dx -> tileUrl(centerTileX + dx, centerTileY + dy, zoom) } }
    }

    private fun tileUrl(x: Int, y: Int, zoom: Int): String = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
}
