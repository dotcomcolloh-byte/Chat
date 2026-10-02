package com.telefam.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val modelJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** EVENT attachment payload (travels inside the encrypted ChatPayload text field). */
@Serializable
data class EventData(
    val title: String,
    val startsAtEpochMillis: Long,
    val locationLabel: String? = null,
    val notes: String? = null
) {
    fun encode(): String = modelJson.encodeToString(this)
    companion object {
        fun decode(raw: String): EventData? = runCatching { modelJson.decodeFromString<EventData>(raw) }.getOrNull()
    }
}

/** FILE attachment metadata (original name/mime/size; the bytes travel as media). */
@Serializable
data class FileMeta(
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0
) {
    fun encode(): String = modelJson.encodeToString(this)
    companion object {
        fun decode(raw: String): FileMeta? = runCatching { modelJson.decodeFromString<FileMeta>(raw) }.getOrNull()
    }
}

/**
 * Reactions are stored as a JSON object: emoji -> list of reacting userIds.
 * One reaction per user per emoji; a user may react with several distinct emojis.
 */
object Reactions {
    fun decode(raw: String?): MutableMap<String, MutableList<String>> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val parsed = runCatching {
            modelJson.decodeFromString<Map<String, List<String>>>(raw)
        }.getOrNull() ?: return mutableMapOf()
        return parsed.mapValues { it.value.toMutableList() }.toMutableMap()
    }

    fun encode(map: Map<String, List<String>>): String? {
        val clean = map.filterValues { it.isNotEmpty() }
        return if (clean.isEmpty()) null else modelJson.encodeToString(clean)
    }

    /** Returns the new encoded map. Adding an emoji the user already used removes it (toggle). */
    fun toggle(raw: String?, emoji: String, userId: String): String? {
        val map = decode(raw)
        val list = map.getOrPut(emoji) { mutableListOf() }
        if (userId in list) list.remove(userId) else list.add(userId)
        if (list.isEmpty()) map.remove(emoji)
        return encode(map)
    }

    /** Receiver-side apply: `remove` clears the sender's reaction instead of adding. */
    fun apply(raw: String?, emoji: String, userId: String, remove: Boolean): String? {
        val map = decode(raw)
        val list = map.getOrPut(emoji) { mutableListOf() }
        if (remove) list.remove(userId) else if (userId !in list) list.add(userId)
        if (list.isEmpty()) map.remove(emoji)
        return encode(map)
    }

    fun summary(raw: String?): List<Pair<String, Int>> =
        decode(raw).map { it.key to it.value.size }.sortedByDescending { it.second }

    fun includesUser(raw: String?, emoji: String, userId: String): Boolean =
        decode(raw)[emoji]?.contains(userId) == true
}
