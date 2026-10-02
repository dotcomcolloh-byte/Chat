package com.telefam.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContactData(val name: String, val phoneNumber: String) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(json: String): ContactData? = runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
    }
}
