package com.telefam.privacy

import com.telefam.db.ChatPreferences
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Reports
import com.telefam.db.RlsContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.*

@Serializable data class MuteRequest(val peerId: String, val mutedUntilEpochSeconds: Long?) // null = unmute
@Serializable data class ChatPreferenceDto(val peerId: String, val mutedUntil: String?)
@Serializable data class ReportRequest(val reportedUserId: String, val reason: String)

class ChatPreferencesService {

    suspend fun setMute(userId: UUID, peerId: UUID, mutedUntilEpochSeconds: Long?) = RlsContext.asUser(userId) {
        val now = LocalDateTime.now()
        val mutedUntil = mutedUntilEpochSeconds?.let {
            LocalDateTime.ofEpochSecond(it, 0, java.time.ZoneOffset.UTC)
        }
        val existing = ChatPreferences.selectAll().where { (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }.singleOrNull()
        if (existing == null) {
            ChatPreferences.insert {
                it[ChatPreferences.userId] = userId
                it[ChatPreferences.peerId] = peerId
                it[ChatPreferences.mutedUntil] = mutedUntil
                it[ChatPreferences.updatedAt] = now
            }
        } else {
            ChatPreferences.update({ (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }) {
                it[ChatPreferences.mutedUntil] = mutedUntil
                it[ChatPreferences.updatedAt] = now
            }
        }
    }

    suspend fun getPreference(userId: UUID, peerId: UUID): ChatPreferenceDto = RlsContext.asUser(userId) {
        val row = ChatPreferences.selectAll().where { (ChatPreferences.userId eq userId) and (ChatPreferences.peerId eq peerId) }.singleOrNull()
        ChatPreferenceDto(peerId.toString(), row?.get(ChatPreferences.mutedUntil)?.toString())
    }

    suspend fun listAllPreferences(userId: UUID): List<ChatPreferenceDto> = RlsContext.asUser(userId) {
        ChatPreferences.selectAll().where { ChatPreferences.userId eq userId }
            .map { ChatPreferenceDto(it[ChatPreferences.peerId].toString(), it[ChatPreferences.mutedUntil]?.toString()) }
    }
}

class ReportService {
    /** Rate-limited at the route layer (shares the "otp"-style limiter) to prevent report-spam abuse. */
    suspend fun submitReport(reporterId: UUID, reportedUserId: UUID, reason: String) = RlsContext.asUser(reporterId) {
        Reports.insert {
            it[Reports.reporterId] = reporterId
            it[Reports.reportedUserId] = reportedUserId
            it[Reports.reason] = reason.take(500)
            it[Reports.createdAt] = LocalDateTime.now()
        }
    }
}
