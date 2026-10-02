package com.telefam.privacy

import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MessageRequests
import com.telefam.db.PrivacySettings
import com.telefam.db.RlsContext
import com.telefam.db.Users
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who may see whose online / last-seen status, plus durable storage of the last-seen timestamp.
 *
 * Rules (all enforced server-side; the client only ever receives what it is allowed to see):
 *  - a user always sees their own status;
 *  - nobody sees the status of someone who blocked them, and nobody sees the status of someone they blocked;
 *  - otherwise the TARGET's [PrivacySettings.whoCanSeeLastSeen] decides: ANYONE, CONTACTS (an accepted
 *    message request exists between the two) or NOBODY.
 */
class PresenceService(private val cacheTtlMillis: Long = 30_000) {

    private data class Verdict(val allowed: Boolean, val expiresAt: Long)
    private val cache = ConcurrentHashMap<Pair<UUID, UUID>, Verdict>()

    suspend fun canSee(viewer: UUID, target: UUID): Boolean {
        if (viewer == target) return true
        val key = viewer to target
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.allowed }
        val allowed = evaluate(viewer, target)
        cache[key] = Verdict(allowed, now + cacheTtlMillis)
        return allowed
    }

    /** Called when [target] changes their setting (or blocks someone): old verdicts about them are void immediately. */
    fun invalidate(target: UUID) {
        cache.keys.removeIf { it.second == target }
    }

    // Runs as the TARGET: RLS then exposes the target's own privacy row, every block row involving the target
    // (as blocker, or as the blocked party) and the target's message requests - exactly the data needed here.
    private suspend fun evaluate(viewer: UUID, target: UUID): Boolean = RlsContext.asUser(target) {
        val blocked = BlockedUsers.selectAll().where {
            ((BlockedUsers.blockerId eq target) and (BlockedUsers.blockedId eq viewer)) or
                ((BlockedUsers.blockerId eq viewer) and (BlockedUsers.blockedId eq target))
        }.any()
        if (blocked) return@asUser false

        val level = PrivacySettings.selectAll().where { PrivacySettings.userId eq target }
            .singleOrNull()?.get(PrivacySettings.whoCanSeeLastSeen) ?: AccessLevel.ANYONE.name
        when (level) {
            AccessLevel.NOBODY.name -> false
            AccessLevel.CONTACTS.name -> MessageRequests.selectAll().where {
                (MessageRequests.status eq RequestStatus.ACCEPTED.name) and (
                    ((MessageRequests.senderId eq viewer) and (MessageRequests.receiverId eq target)) or
                        ((MessageRequests.senderId eq target) and (MessageRequests.receiverId eq viewer))
                    )
            }.any()
            else -> true
        }
    }

    suspend fun saveLastSeen(userId: UUID, epochMillis: Long) {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
        dbQuery { Users.update({ Users.id eq userId }) { it[Users.lastSeenAt] = at } }
    }

    suspend fun loadLastSeen(userId: UUID): Long? = dbQuery {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.lastSeenAt)
            ?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
    }
}
