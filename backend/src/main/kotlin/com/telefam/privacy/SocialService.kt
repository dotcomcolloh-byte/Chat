package com.telefam.privacy

import com.telefam.db.ArchivedChats
import com.telefam.db.BlockedUsers
import com.telefam.db.MessageRequests
import com.telefam.db.RlsContext
import com.telefam.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.*

@Serializable
data class MessageRequestDto(
    val id: String,
    val fromUserId: String,
    val fromName: String?,
    val previewText: String?,
    val createdAt: String,
    val fromVerified: Boolean = false
)

@Serializable data class BlockedUserDto(
    val userId: String,
    val name: String?,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
)
@Serializable data class ArchivedChatDto(val chatId: String, val archivedAt: String)

/** [onBlockChanged] (blocker, blocked) lets realtime presence drop/restore visibility the moment a block changes. */
class SocialService(private val onBlockChanged: suspend (blocker: UUID, blocked: UUID) -> Unit = { _, _ -> }) {

    suspend fun listPendingRequests(userId: UUID): List<MessageRequestDto> {
        // Phase 1 (single transaction): raw rows + sender names. No nested blocking calls.
        val rows = RlsContext.asUser(userId) {
            MessageRequests
                .selectAll().where { (MessageRequests.receiverId eq userId) and (MessageRequests.status eq RequestStatus.PENDING.name) }
                .orderBy(MessageRequests.createdAt to SortOrder.DESC)
                .map { row ->
                    val sender = Users.selectAll().where { Users.id eq row[MessageRequests.senderId] }.singleOrNull()
                    Triple(row, sender?.get(Users.fullName), row[MessageRequests.senderId])
                }
        }
        // Phase 2 (suspendable): badge lookups — never runBlocking inside a db transaction.
        return rows.map { (row, senderName, senderId) ->
            MessageRequestDto(
                id = row[MessageRequests.id].value.toString(),
                fromUserId = senderId.toString(),
                fromName = senderName,
                previewText = row[MessageRequests.previewText],
                createdAt = row[MessageRequests.createdAt].toString(),
                fromVerified = runCatching { com.telefam.verification.BadgeService.isVerified(senderId) }.getOrDefault(false)
            )
        }
    }

    /**
     * Two-sided block state for a conversation — drives the chat UI:
     *  - iBlocked:   I blocked them → composer replaced by "You blocked this person · Unblock".
     *  - blockedMe:  they blocked me → composer replaced by "You can't contact this person".
     * Both directions are readable under the blocked_users RLS policies.
     */
    @Serializable
    data class BlockStatusDto(val iBlocked: Boolean, val blockedMe: Boolean)

    suspend fun blockStatus(userId: UUID, peerId: UUID): BlockStatusDto = RlsContext.asUser(userId) {
        val iBlocked = BlockedUsers.selectAll().where {
            (BlockedUsers.blockerId eq userId) and (BlockedUsers.blockedId eq peerId)
        }.any()
        val blockedMe = BlockedUsers.selectAll().where {
            (BlockedUsers.blockerId eq peerId) and (BlockedUsers.blockedId eq userId)
        }.any()
        BlockStatusDto(iBlocked, blockedMe)
    }

    suspend fun respondToRequest(userId: UUID, requestId: UUID, accept: Boolean): Boolean = RlsContext.asUser(userId) {
        val row = MessageRequests.selectAll().where {
            (MessageRequests.id eq requestId) and (MessageRequests.receiverId eq userId) and (MessageRequests.status eq RequestStatus.PENDING.name)
        }.singleOrNull() ?: return@asUser false

        MessageRequests.update({ MessageRequests.id eq requestId }) {
            it[status] = if (accept) RequestStatus.ACCEPTED.name else RequestStatus.DECLINED.name
            it[respondedAt] = LocalDateTime.now()
        }
        true
    }

    suspend fun blockUser(blockerId: UUID, blockedId: UUID) {
        insertBlock(blockerId, blockedId)
        onBlockChanged(blockerId, blockedId)
    }

    suspend fun unblockUser(blockerId: UUID, blockedId: UUID) {
        deleteBlock(blockerId, blockedId)
        onBlockChanged(blockerId, blockedId)
    }

    private suspend fun insertBlock(blockerId: UUID, blockedId: UUID) = RlsContext.asUser(blockerId) {
        val alreadyBlocked = BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId) }.any()
        if (!alreadyBlocked) {
            BlockedUsers.insert {
                it[BlockedUsers.blockerId] = blockerId
                it[BlockedUsers.blockedId] = blockedId
                it[BlockedUsers.createdAt] = LocalDateTime.now()
            }
        }
    }

    private suspend fun deleteBlock(blockerId: UUID, blockedId: UUID) = RlsContext.asUser(blockerId) {
        BlockedUsers.deleteWhere { sql -> with(sql) { (BlockedUsers.blockerId eq blockerId) and (BlockedUsers.blockedId eq blockedId) } }
    }

    suspend fun listBlocked(userId: UUID): List<BlockedUserDto> {
        val rows = RlsContext.asUser(userId) {
            BlockedUsers.selectAll().where { BlockedUsers.blockerId eq userId }
                .orderBy(BlockedUsers.createdAt to SortOrder.DESC)
                .map { row ->
                    val blocked = Users.selectAll().where { Users.id eq row[BlockedUsers.blockedId] }.singleOrNull()
                    Triple(row[BlockedUsers.blockedId], blocked?.get(Users.fullName), blocked?.get(Users.username)) to
                        (blocked?.get(Users.profileImageMediaId) != null)
                }
        }
        return rows.map { (info, hasAvatar) ->
            val (blockedId, name, username) = info
            BlockedUserDto(
                userId = blockedId.toString(),
                name = name,
                username = username,
                avatarUrl = if (hasAvatar) "/api/feeds/avatar/$blockedId" else null,
                isVerified = runCatching { com.telefam.verification.BadgeService.isVerified(blockedId) }.getOrDefault(false),
            )
        }
    }

    suspend fun archiveChat(userId: UUID, chatId: UUID) = RlsContext.asUser(userId) {
        val exists = ArchivedChats.selectAll().where { (ArchivedChats.userId eq userId) and (ArchivedChats.chatId eq chatId) }.any()
        if (!exists) {
            ArchivedChats.insert {
                it[ArchivedChats.userId] = userId
                it[ArchivedChats.chatId] = chatId
                it[ArchivedChats.archivedAt] = LocalDateTime.now()
            }
        }
    }

    suspend fun unarchiveChat(userId: UUID, chatId: UUID) = RlsContext.asUser(userId) {
        ArchivedChats.deleteWhere { sql -> with(sql) { (ArchivedChats.userId eq userId) and (ArchivedChats.chatId eq chatId) } }
    }

    suspend fun listArchived(userId: UUID): List<ArchivedChatDto> = RlsContext.asUser(userId) {
        ArchivedChats.selectAll().where { ArchivedChats.userId eq userId }
            .orderBy(ArchivedChats.archivedAt to SortOrder.DESC)
            .map { ArchivedChatDto(chatId = it[ArchivedChats.chatId].toString(), archivedAt = it[ArchivedChats.archivedAt].toString()) }
    }
}
