package com.telefam.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PollData(
    val id: String,
    val question: String,
    val options: List<String>,
    val allowMultiple: Boolean,
    val votes: Map<Int, List<String>> = emptyMap() // optionIndex -> voter userIds
) {
    fun totalVoters(): Int = votes.values.flatten().distinct().size

    fun percentFor(optionIndex: Int): Int {
        val total = totalVoters()
        if (total == 0) return 0
        val votesForOption = votes[optionIndex]?.size ?: 0
        return (votesForOption * 100) / total
    }

    fun hasVoted(userId: String): Boolean = votes.values.any { userId in it }

    fun withVote(userId: String, selected: List<Int>): PollData {
        val cleared = votes.mapValues { (_, voters) -> voters.filterNot { it == userId } }
        val updated = cleared.toMutableMap()
        selected.forEach { idx -> updated[idx] = (updated[idx] ?: emptyList()) + userId }
        return copy(votes = updated)
    }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String): PollData? = runCatching { Json.decodeFromString(serializer(), json) }.getOrNull()
    }
}

@Serializable
data class PollVoteUpdate(val pollMessageId: String, val selectedOptionIndexes: List<Int>)
