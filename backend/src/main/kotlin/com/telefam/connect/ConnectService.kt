package com.telefam.connect

import com.telefam.config.AppConfig
import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MediaAssets
import com.telefam.db.MessageRequests
import com.telefam.db.RlsContext
import com.telefam.db.Users
import com.telefam.posts.Follows
import com.telefam.posts.PostLikes
import com.telefam.posts.Posts
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.*

// ---------- DTOs (wire contract with the shared KMP client) ----------

@Serializable
data class ConnectUserDto(
    val userId: String,
    val username: String? = null,
    val fullName: String? = null,
    val avatarUrl: String? = null,
    val mutualConnections: Int = 0,
    /** FROM_CONTACTS | FRIEND_OF_FRIEND | NEARBY | NEW_USER | SEARCH */
    val suggestionReason: String? = null,
    val friendDegree: Int? = null,
    val distanceKm: Double? = null,
    val isNewUser: Boolean = false,
    val viewerFollowing: Boolean = false,
    val viewerFollowedBy: Boolean = false,
    val isFriend: Boolean = false,
    /** True only while a backend-granted badge is unexpired (see BadgeService). */
    val isVerified: Boolean = false,
    /** True for the backend-controlled official Telefam account (follow-only, no calls). */
    val isOfficialAccount: Boolean = false
)

@Serializable
data class ConnectPageDto(
    val items: List<ConnectUserDto>,
    val nextOffset: Int? = null,
    val serverTime: String = ""
)

@Serializable
data class ProfileDetailsDto(
    val userId: String,
    val username: String? = null,
    val fullName: String? = null,
    val bio: String? = null,
    val locationName: String? = null,
    val avatarUrl: String? = null,
    /** Optional public website link shown on the profile. */
    val website: String? = null,
    val profileUrl: String = "",
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val friendsCount: Long = 0,
    val likesCount: Long = 0,
    val postsCount: Long = 0,
    val subscriberCount: Long = 0,
    val isOwner: Boolean = false,
    val viewerFollowing: Boolean = false,
    val viewerFollowedBy: Boolean = false,
    val viewerSubscribed: Boolean = false,
    val isFriend: Boolean = false,
    val isBlockedByViewer: Boolean = false,
    /** Backend-granted, time-boxed verification badge. */
    val isVerified: Boolean = false,
    /** True for the backend-controlled official Telefam account (followers-only profile). */
    val isOfficialAccount: Boolean = false,
    // NOTE: the internal trust/risk classification (riskLevelOf) is deliberately NOT
    // part of this DTO — moderation signals stay server-side and never reach clients.
    val joinedAt: String = ""
)

@Serializable
data class FollowStateDto(
    val viewerFollowing: Boolean,
    val viewerFollowedBy: Boolean,
    val isFriend: Boolean,
    val followerCount: Long
)

@Serializable
data class ContactHashEntry(val type: String, val value: String)

@Serializable
data class ContactHashUploadRequest(
    val hashes: List<ContactHashEntry>,
    /** Full replace semantics: the server stores exactly this set for the caller. */
    val replaceAll: Boolean = true
)

@Serializable
data class ContactHashUploadResponse(val stored: Int, val matchedUsers: Int)

@Serializable
data class LocationUpdateRequest(val lat: Double, val lng: Double, val name: String? = null)

@Serializable
data class RelatedSearchDto(val terms: List<String>)

/** Thrown when a follow action trips the per-minute / per-hour / risk limits. */
class FollowRateLimitException(val secondsRemaining: Long) : Exception()

/**
 * Contacts / Discover / Profile backend.
 *
 * - Follow graph with idempotent writes (unique index + existence check; safe to
 *   retry or replay from the client outbox), per-minute AND per-hour rate limits,
 *   and a behavioural risk score maintained for both the follower and the followee.
 * - Suggestions merge four real signals: device-contact hash matches, friends-of-
 *   friends (BFS to depth 3), location proximity, and the daily-refreshing pool of
 *   newly joined users. "Friend" = mutual follow OR an accepted message request.
 */
class ConnectService {

    companion object {
        const val FOLLOW_MAX_PER_MINUTE = 20
        const val FOLLOW_MAX_PER_HOUR = 200
        /** Sustained velocity above this inside an hour is treated as bot-like. */
        const val FOLLOW_BOT_HOURLY_THRESHOLD = 60
        const val NEARBY_RADIUS_KM = 25.0
        const val NEW_USER_WINDOW_DAYS = 7L
        const val MAX_SUGGESTION_POOL = 400
    }

    // ---------- hashing (must match shared/.../connect/ContactHash.kt exactly) ----------

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }
    fun normalizeEmail(raw: String): String = raw.trim().lowercase()
    fun phoneHash(raw: String) = sha256Hex("phone:" + normalizePhone(raw))
    fun emailHash(raw: String) = sha256Hex("email:" + normalizeEmail(raw))

    // ---------- relationship primitives ----------

    private fun followingIds(userId: UUID): Set<UUID> =
        Follows.selectAll().where { Follows.followerId eq userId }.map { it[Follows.followeeId] }.toSet()

    private fun followerIds(userId: UUID): Set<UUID> =
        Follows.selectAll().where { Follows.followeeId eq userId }.map { it[Follows.followerId] }.toSet()

    /** Friend = mutual follow, or an accepted message request in either direction. */
    fun friendIdsOf(userId: UUID): Set<UUID> {
        val accepted = MessageRequests.selectAll().where {
            ((MessageRequests.senderId eq userId) or (MessageRequests.receiverId eq userId)) and
                (MessageRequests.status eq "ACCEPTED")
        }.map { if (it[MessageRequests.senderId] == userId) it[MessageRequests.receiverId] else it[MessageRequests.senderId] }
        return (accepted + (followingIds(userId) intersect followerIds(userId))).toSet()
    }

    private suspend fun blockedPairs(userId: UUID): Set<UUID> = RlsContext.asUser(userId) {
        BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { if (it[BlockedUsers.blockerId] == userId) it[BlockedUsers.blockedId] else it[BlockedUsers.blockerId] }
            .toSet()
    }

    // ---------- follow / unfollow (idempotent + rate limited + risk scored) ----------

    /**
     * Idempotent: re-following an already-followed user (client retry, outbox replay)
     * is a silent no-op success, as is un-following someone not followed.
     */
    suspend fun setFollow(userId: UUID, targetId: UUID, follow: Boolean): FollowStateDto {
        require(userId != targetId) { "cannot follow yourself" }
        // The official account follows nobody — it is a followers-only profile.
        require(!com.telefam.official.OfficialAccountService.isOfficial(userId)) { "unavailable" }
        val blocked = blockedPairs(userId)
        require(targetId !in blocked) { "unavailable" }
        val targetExists = dbQuery {
            Users.selectAll().where { (Users.id eq targetId) and (Users.profileComplete eq true) }.any()
        }
        require(targetExists) { "unavailable" }

        if (follow) enforceFollowBudget(userId)

        var newEdge = false
        RlsContext.asUser(userId) {
            val exists = Follows.selectAll().where {
                (Follows.followerId eq userId) and (Follows.followeeId eq targetId)
            }.any()
            if (follow && !exists) {
                Follows.insert {
                    it[Follows.id] = UUID.randomUUID(); it[followerId] = userId
                    it[followeeId] = targetId; it[createdAt] = LocalDateTime.now()
                }
                newEdge = true
            } else if (!follow && exists) {
                Follows.deleteWhere { (Follows.followerId eq userId) and (Follows.followeeId eq targetId) }
            }
        }

        if (follow) {
            bumpFollowCounters(userId)
            // Score BOTH sides of the new edge.
            recomputeRiskScore(userId)
            recomputeRiskScore(targetId)
            if (newEdge) {
                // "X started following you" — with Follow back when the target hasn't followed yet.
                val alreadyFollowsBack = dbQuery {
                    Follows.selectAll().where {
                        (Follows.followerId eq targetId) and (Follows.followeeId eq userId)
                    }.any()
                }
                com.telefam.notifications.NotificationService.notify(
                    userId = targetId,
                    type = com.telefam.notifications.NotificationTypes.NEW_FOLLOWER,
                    title = "New follower",
                    body = "started following you",
                    actorId = userId,
                    canFollowBack = !alreadyFollowsBack,
                    targetType = com.telefam.notifications.NotificationTargets.PROFILE,
                    targetId = userId.toString()
                )
            }
        }
        return followState(userId, targetId)
    }

    suspend fun followState(userId: UUID, targetId: UUID): FollowStateDto = dbQuery {
        val following = Follows.selectAll().where {
            (Follows.followerId eq userId) and (Follows.followeeId eq targetId)
        }.any()
        val followedBy = Follows.selectAll().where {
            (Follows.followerId eq targetId) and (Follows.followeeId eq userId)
        }.any()
        val acceptedChat = MessageRequests.selectAll().where {
            (MessageRequests.status eq "ACCEPTED") and (
                ((MessageRequests.senderId eq userId) and (MessageRequests.receiverId eq targetId)) or
                    ((MessageRequests.senderId eq targetId) and (MessageRequests.receiverId eq userId))
                )
        }.any()
        val followers = Follows.selectAll().where { Follows.followeeId eq targetId }.count()
        FollowStateDto(
            viewerFollowing = following,
            viewerFollowedBy = followedBy,
            isFriend = (following && followedBy) || acceptedChat,
            followerCount = followers
        )
    }

    /** Fixed-window per-minute + per-hour budget. Failing either rejects with secondsRemaining. */
    private suspend fun enforceFollowBudget(userId: UUID) = RlsContext.asUser(userId) {
        val now = LocalDateTime.now()
        val row = FollowRateLimits.selectAll().where { FollowRateLimits.userId eq userId }.singleOrNull()
        if (row == null) {
            FollowRateLimits.insert {
                it[FollowRateLimits.userId] = userId
                it[minuteWindowStart] = now; it[minuteCount] = 0
                it[hourWindowStart] = now; it[hourCount] = 0
                it[updatedAt] = now
            }
            return@asUser
        }
        val minuteStart = row[FollowRateLimits.minuteWindowStart]
        val hourStart = row[FollowRateLimits.hourWindowStart]
        val minuteCount = if (minuteStart.isAfter(now.minusMinutes(1))) row[FollowRateLimits.minuteCount] else 0
        val hourCount = if (hourStart.isAfter(now.minusHours(1))) row[FollowRateLimits.hourCount] else 0

        if (minuteCount >= FOLLOW_MAX_PER_MINUTE) {
            throw FollowRateLimitException(java.time.Duration.between(now, minuteStart.plusMinutes(1)).seconds.coerceAtLeast(1))
        }
        if (hourCount >= FOLLOW_MAX_PER_HOUR) {
            throw FollowRateLimitException(java.time.Duration.between(now, hourStart.plusHours(1)).seconds.coerceAtLeast(1))
        }
        // Behavioural cap: a flagged account still following at bot velocity is cut off hard.
        val risk = riskScoreOf(userId)
        if (risk.first >= 70 && hourCount >= FOLLOW_BOT_HOURLY_THRESHOLD) {
            throw FollowRateLimitException(java.time.Duration.between(now, hourStart.plusHours(1)).seconds.coerceAtLeast(60))
        }
    }

    private suspend fun bumpFollowCounters(userId: UUID) = RlsContext.asUser(userId) {
        val now = LocalDateTime.now()
        val row = FollowRateLimits.selectAll().where { FollowRateLimits.userId eq userId }.singleOrNull() ?: return@asUser
        val minuteStart = row[FollowRateLimits.minuteWindowStart]
        val hourStart = row[FollowRateLimits.hourWindowStart]
        FollowRateLimits.update({ FollowRateLimits.userId eq userId }) {
            if (minuteStart.isAfter(now.minusMinutes(1))) it[minuteCount] = row[FollowRateLimits.minuteCount] + 1
            else { it[FollowRateLimits.minuteWindowStart] = now; it[minuteCount] = 1 }
            if (hourStart.isAfter(now.minusHours(1))) it[hourCount] = row[FollowRateLimits.hourCount] + 1
            else { it[FollowRateLimits.hourWindowStart] = now; it[hourCount] = 1 }
            it[updatedAt] = now
        }
    }

    // ---------- risk scoring ----------

    private fun riskScoreOf(userId: UUID): Pair<Int, List<String>> {
        val row = UserRiskScores.selectAll().where { UserRiskScores.userId eq userId }.singleOrNull()
            ?: return 0 to emptyList()
        return row[UserRiskScores.score] to row[UserRiskScores.signals].split(',').filter { it.isNotBlank() }
    }

    /**
     * Heuristics (each adds points):
     *  - FOLLOW_BURST: >60 follows/hour (+40)
     *  - NEW_ACCOUNT: account <3 days old and already following heavily (+15)
     *  - CHURN: follows+unfollows heavily — approximated by a low follow-back ratio on a
     *    large out-degree (+15)
     *  - MASS_FOLLOWED_NEW: a brand-new account with a sudden large follower inflow,
     *    the classic bought-followers shape (+25, applied to the followee)
     */
    private suspend fun recomputeRiskScore(userId: UUID) = dbQuery {
        val now = LocalDateTime.now()
        var score = 0
        val signals = mutableListOf<String>()

        val user = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: return@dbQuery
        val accountAgeDays = java.time.Duration.between(user[Users.createdAt], now).toDays()

        val rl = FollowRateLimits.selectAll().where { FollowRateLimits.userId eq userId }.singleOrNull()
        val hourCount = rl?.let {
            if (it[FollowRateLimits.hourWindowStart].isAfter(now.minusHours(1))) it[FollowRateLimits.hourCount] else 0
        } ?: 0
        if (hourCount > FOLLOW_BOT_HOURLY_THRESHOLD) { score += 40; signals += "FOLLOW_BURST" }

        val following = Follows.selectAll().where { Follows.followerId eq userId }.count()
        val followers = Follows.selectAll().where { Follows.followeeId eq userId }.count()
        if (accountAgeDays < 3 && following > 50) { score += 15; signals += "NEW_ACCOUNT" }
        if (following >= 200 && followers * 5 < following) { score += 15; signals += "LOW_FOLLOWBACK" }
        if (accountAgeDays < 7 && followers > 500) { score += 25; signals += "MASS_FOLLOWED_NEW" }

        score = score.coerceIn(0, 100)
        val existing = UserRiskScores.selectAll().where { UserRiskScores.userId eq userId }.singleOrNull()
        if (existing == null) {
            UserRiskScores.insert {
                it[UserRiskScores.userId] = userId; it[UserRiskScores.score] = score
                it[UserRiskScores.signals] = signals.joinToString(","); it[updatedAt] = now
            }
        } else {
            UserRiskScores.update({ UserRiskScores.userId eq userId }) {
                it[UserRiskScores.score] = score; it[UserRiskScores.signals] = signals.joinToString(","); it[updatedAt] = now
            }
        }
    }

    private fun riskLevelOf(userId: UUID): String {
        val s = riskScoreOf(userId).first
        return if (s >= 70) "HIGH" else if (s >= 30) "MEDIUM" else "LOW"
    }

    // ---------- contact hashes ----------

    suspend fun uploadContactHashes(userId: UUID, req: ContactHashUploadRequest): ContactHashUploadResponse {
        val clean = req.hashes.asSequence()
            .map { it.type.uppercase() to it.value.lowercase().trim() }
            .filter { (it.first == "PHONE" || it.first == "EMAIL") && it.second.length == 64 && it.second.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            .distinct()
            .take(10_000)
            .toList()
        RlsContext.asUser(userId) {
            if (req.replaceAll) ContactHashes.deleteWhere { ContactHashes.userId eq userId }
            val now = LocalDateTime.now()
            for ((type, value) in clean) {
                val exists = ContactHashes.selectAll().where {
                    (ContactHashes.userId eq userId) and (ContactHashes.hashType eq type) and (ContactHashes.hashValue eq value)
                }.any()
                if (!exists) {
                    ContactHashes.insert {
                        it[ContactHashes.userId] = userId; it[hashType] = type
                        it[hashValue] = value; it[uploadedAt] = now
                    }
                }
            }
        }
        val matches = dbQuery { contactMatchedUserIds(userId) }
        return ContactHashUploadResponse(stored = clean.size, matchedUsers = matches.size)
    }

    /** Users on Telefam whose own phone/email hashes appear in the caller's address book. */
    private fun contactMatchedUserIds(userId: UUID): Set<UUID> {
        val myHashes = ContactHashes.selectAll().where { ContactHashes.userId eq userId }
            .map { it[ContactHashes.hashType] to it[ContactHashes.hashValue] }.toSet()
        if (myHashes.isEmpty()) return emptySet()
        // Hash every complete profile's own phone/email and intersect. Bounded by the
        // suggestion pool; hashes are one-way so this never exposes raw numbers.
        return Users.selectAll().where { (Users.profileComplete eq true) and (Users.id neq userId) }
            .limit(50_000)
            .mapNotNull { u ->
                val phone = u[Users.phoneNumber]
                val cc = u[Users.phoneCountryCode]
                val email = u[Users.email]
                val hashes = buildSet {
                    if (!phone.isNullOrBlank()) {
                        add(phoneHash(phone))
                        if (!cc.isNullOrBlank()) add(phoneHash(normalizePhone(cc) + normalizePhone(phone)))
                    }
                    if (!email.isNullOrBlank()) add(emailHash(email))
                }
                if (hashes.any { h -> ("PHONE" to h) in myHashes || ("EMAIL" to h) in myHashes }) u[Users.id].value else null
            }.toSet()
    }

    // ---------- location ----------

    suspend fun updateLocation(userId: UUID, req: LocationUpdateRequest) = dbQuery {
        require(req.lat in -90.0..90.0 && req.lng in -180.0..180.0) { "invalid coordinates" }
        Users.update({ Users.id eq userId }) {
            it[locationLat] = req.lat; it[locationLng] = req.lng
            it[locationName] = req.name?.take(120); it[updatedAt] = LocalDateTime.now()
        }
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    // ---------- suggestions ----------

    private data class Candidate(
        val userId: UUID,
        var score: Int,
        var reason: String,
        var degree: Int? = null,
        var distanceKm: Double? = null,
        var isNew: Boolean = false
    )

    /**
     * Merged "People you may know": contacts → friends-of-friends (depth ≤ 3) →
     * nearby → new users. Computed on demand so every pull reflects today's graph —
     * newly joined users surface within a day of joining by construction.
     */
    suspend fun suggestions(userId: UUID, offset: Int, limitRaw: Int?): ConnectPageDto {
        val limit = (limitRaw ?: 20).coerceIn(1, 50)
        val blocked = blockedPairs(userId)
        val myFollowing = dbQuery { followingIds(userId) }
        val myFriends = dbQuery { friendIdsOf(userId) }
        val candidates = mutableMapOf<UUID, Candidate>()

        fun add(id: UUID, score: Int, reason: String, degree: Int? = null, distanceKm: Double? = null, isNew: Boolean = false) {
            if (id == userId || id in blocked || id in myFollowing) return
            val c = candidates.getOrPut(id) { Candidate(id, 0, reason) }
            if (score > c.score) { c.score = score; c.reason = reason }
            if (degree != null && (c.degree == null || degree < c.degree!!)) c.degree = degree
            if (distanceKm != null) c.distanceKm = distanceKm
            if (isNew) c.isNew = true
        }

        // 1) From your contacts (strongest signal).
        val contactMatches = dbQuery { contactMatchedUserIds(userId) }
        contactMatches.forEach { add(it, 100, "FROM_CONTACTS") }

        // 2) Friends-of-friends, BFS to depth 3 over the friend graph.
        var frontier = myFriends.toSet()
        val visited = (myFriends + userId).toMutableSet()
        for (depth in 2..4) { // depth 1 is the user's direct friends, excluded from suggestions
            if (frontier.isEmpty() || candidates.size > MAX_SUGGESTION_POOL) break
            val next = mutableSetOf<UUID>()
            for (f in frontier) {
                val fof = dbQuery { friendIdsOf(f) }
                for (id in fof) if (visited.add(id)) next.add(id)
            }
            next.forEach { add(it, if (depth == 2) 50 else 30, "FRIEND_OF_FRIEND", degree = depth - 1) }
            frontier = next
        }

        // 3) Nearby (only when the caller has shared their own location).
        val me = dbQuery { Users.selectAll().where { Users.id eq userId }.singleOrNull() }
        val myLat = me?.get(Users.locationLat); val myLng = me?.get(Users.locationLng)
        if (myLat != null && myLng != null) {
            dbQuery {
                Users.selectAll().where {
                    (Users.profileComplete eq true) and (Users.id neq userId) and
                        Users.locationLat.isNotNull() and Users.locationLng.isNotNull()
                }.limit(10_000).toList()
            }.forEach { u ->
                val d = haversineKm(myLat, myLng, u[Users.locationLat]!!, u[Users.locationLng]!!)
                if (d <= NEARBY_RADIUS_KM) add(u[Users.id].value, 40, "NEARBY", distanceKm = d.roundToInt().toDouble())
            }
        }

        // 4) New users — the pool refreshes daily as people join.
        val newCutoff = LocalDateTime.now().minusDays(NEW_USER_WINDOW_DAYS)
        dbQuery {
            Users.selectAll().where {
                (Users.profileComplete eq true) and (Users.id neq userId) and (Users.createdAt greaterEq newCutoff)
            }.orderBy(Users.createdAt to SortOrder.DESC).limit(200).toList()
        }.forEach { u -> add(u[Users.id].value, 10, "NEW_USER", isNew = true) }

        // 5) Registered users fallback: contacts must remain useful even when a
        // person has no uploaded address-book hashes, mutuals, or location. Fill the
        // remaining suggestion pool from complete Telefam profiles, newest first.
        // This keeps the existing ranking signals intact while ensuring ordinary
        // registered users can actually appear in Contacts.
        if (candidates.size < MAX_SUGGESTION_POOL) {
            val needed = MAX_SUGGESTION_POOL - candidates.size
            val excluded = (blocked + myFollowing + candidates.keys).toList()
            dbQuery {
                val base = (Users.profileComplete eq true) and (Users.id neq userId)
                val query = if (excluded.isEmpty()) {
                    Users.selectAll().where { base }
                } else {
                    Users.selectAll().where { base and (Users.id notInList excluded) }
                }
                query.orderBy(Users.createdAt to SortOrder.DESC).limit(needed).toList()
            }.forEach { u -> add(u[Users.id].value, 1, "REGISTERED_USER") }
        }

        // Mutual connections: shared direct friends.
        val ranked = candidates.values.map { c ->
            val theirFriends = dbQuery { friendIdsOf(c.userId) }
            val mutual = (myFriends intersect theirFriends).size
            c.score += mutual * 5
            c to mutual
        }.sortedWith(compareByDescending<Pair<Candidate, Int>> { it.first.score }.thenByDescending { it.second })

        val page = ranked.drop(offset).take(limit)
        val dtos = toConnectDtos(page.map { it.first.userId }, userId, myFollowing, myFriends).map { dto ->
            val cand = candidates[dto.userId.let(UUID::fromString)]!!
            dto.copy(
                mutualConnections = ranked.first { it.first.userId == cand.userId }.second,
                suggestionReason = cand.reason,
                friendDegree = cand.degree,
                distanceKm = cand.distanceKm,
                isNewUser = cand.isNew
            )
        }
        val nextOffset = if (ranked.size > offset + limit) offset + limit else null
        return ConnectPageDto(dtos, nextOffset, LocalDateTime.now().toString())
    }

    // ---------- search ----------

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** People search: username prefix OR full-name contains. Same block-hiding rules as the directory. */
    suspend fun search(userId: UUID, queryRaw: String, offset: Int, limitRaw: Int?): ConnectPageDto {
        val q = queryRaw.trim().take(60)
        if (q.length < 2) return ConnectPageDto(emptyList(), null, LocalDateTime.now().toString())
        val limit = (limitRaw ?: 20).coerceIn(1, 50)
        val blocked = blockedPairs(userId)
        val pattern = escapeLike(q.lowercase())
        val rows = dbQuery {
            Users.selectAll().where {
                (Users.profileComplete eq true) and (Users.id neq userId) and (
                    (Users.username.lowerCase() like "$pattern%") or
                        (Users.fullName.lowerCase() like "%$pattern%")
                    )
            }.orderBy(Users.username to SortOrder.ASC).limit(500).toList()
        }.filter { it[Users.id].value !in blocked }
        val myFollowing = dbQuery { followingIds(userId) }
        val myFriends = dbQuery { friendIdsOf(userId) }
        val pageRows = rows.drop(offset).take(limit)
        val dtos = toConnectDtos(pageRows.map { it[Users.id].value }, userId, myFollowing, myFriends)
            .map { it.copy(suggestionReason = "SEARCH") }
        val nextOffset = if (rows.size > offset + limit) offset + limit else null
        return ConnectPageDto(dtos, nextOffset, LocalDateTime.now().toString())
    }

    /** Related search: usernames + hashtags adjacent to what the person typed. */
    suspend fun relatedSearch(userId: UUID, queryRaw: String): RelatedSearchDto {
        val q = queryRaw.trim().lowercase().take(60)
        if (q.length < 2) return RelatedSearchDto(emptyList())
        val pattern = escapeLike(q.removePrefix("#"))
        val usernames = dbQuery {
            Users.selectAll().where {
                (Users.profileComplete eq true) and (Users.username.lowerCase() like "$pattern%")
            }.orderBy(Users.username to SortOrder.ASC).limit(5).map { "@${it[Users.username]}" }
        }
        val hashtags = dbQuery {
            Posts.selectAll().where { (Posts.status eq "PUBLISHED") and (Posts.hashtags.lowerCase() like "%$pattern%") }
                .limit(200).flatMap { it[Posts.hashtags].lowercase().split(' ') }
                .filter { it.startsWith(pattern) && it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { "#${it.key}" }
        }
        return RelatedSearchDto((usernames + hashtags).distinct().take(8))
    }

    // ---------- profile + lists ----------

    suspend fun profile(requesterId: UUID, targetId: UUID): ProfileDetailsDto? {
        val blocked = blockedPairs(requesterId)
        if (targetId in blocked && targetId != requesterId) {
            // If the requester blocked them, the profile still opens (so they can unblock);
            // if THEY blocked the requester, the profile is hidden.
            val blockedMe = RlsContext.asUser(requesterId) {
                BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq targetId) and (BlockedUsers.blockedId eq requesterId) }.any()
            }
            if (blockedMe) return null
        }
        val row = dbQuery { Users.selectAll().where { (Users.id eq targetId) and (Users.accountStatus eq "ACTIVE") }.singleOrNull() } ?: return null
        val state = if (requesterId == targetId) FollowStateDto(false, false, false, dbQuery {
            Follows.selectAll().where { Follows.followeeId eq targetId }.count()
        }) else followState(requesterId, targetId)

        val followingCount = dbQuery { Follows.selectAll().where { Follows.followerId eq targetId }.count() }
        val friendsCount = dbQuery { friendIdsOf(targetId).size.toLong() }
        val likesCount = dbQuery {
            Posts.join(PostLikes, JoinType.INNER, Posts.id, PostLikes.postId).selectAll().where {
                (Posts.ownerId eq targetId) and (Posts.status eq "PUBLISHED")
            }.count()
        }
        val postsCount = dbQuery {
            Posts.selectAll().where { (Posts.ownerId eq targetId) and (Posts.status eq "PUBLISHED") }.count()
        }
        val blockedByViewer = RlsContext.asUser(requesterId) {
            BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq requesterId) and (BlockedUsers.blockedId eq targetId) }.any()
        }
        val avatar = avatarPath(targetId, row[Users.profileImageMediaId])
        val subscriberCount = dbQuery { com.telefam.db.Subscriptions.selectAll().where { com.telefam.db.Subscriptions.creatorId eq targetId }.count() }
        val viewerSubscribed = RlsContext.asUser(requesterId) {
            com.telefam.db.Subscriptions.selectAll().where {
                (com.telefam.db.Subscriptions.subscriberId eq requesterId) and (com.telefam.db.Subscriptions.creatorId eq targetId)
            }.any()
        }
        val verified = com.telefam.verification.BadgeService.isVerified(targetId)
        val official = com.telefam.official.OfficialAccountService.isOfficial(targetId)
        return ProfileDetailsDto(
            userId = targetId.toString(),
            username = row[Users.username],
            fullName = row[Users.fullName],
            bio = row[Users.bio],
            locationName = if (official) null else row[Users.locationName],
            avatarUrl = avatar,
            website = row[Users.website],
            profileUrl = "${AppConfig.publicBaseUrl.trimEnd('/')}/u/${row[Users.username] ?: targetId}",
            followerCount = state.followerCount,
            // Followers-only profile: the official account follows nobody and has no friends tab.
            followingCount = if (official) 0 else followingCount,
            friendsCount = if (official) 0 else friendsCount,
            likesCount = likesCount,
            postsCount = postsCount,
            subscriberCount = subscriberCount,
            isOwner = requesterId == targetId,
            viewerFollowing = state.viewerFollowing,
            viewerFollowedBy = state.viewerFollowedBy,
            viewerSubscribed = viewerSubscribed,
            isFriend = state.isFriend,
            isBlockedByViewer = blockedByViewer,
            isVerified = verified || official,
            isOfficialAccount = official,
            joinedAt = row[Users.createdAt].toString()
        )
    }

    enum class ListKind { FOLLOWERS, FOLLOWING, FRIENDS, SUBSCRIBERS }

    /**
     * Paginated connection lists. Followers/following/subscribers page at the DATABASE
     * level (LIMIT/OFFSET over a narrow projection, blocked users excluded in SQL), so a
     * creator with millions of followers never materializes the full list. FRIENDS is a
     * computed set (mutual follow OR accepted message request), so it is derived first
     * and then paged — friend sets are inherently far smaller than follower sets.
     * Over-fetches one row to detect whether another page exists.
     */
    suspend fun connectionList(requesterId: UUID, targetId: UUID, kind: ListKind, offset: Int, limitRaw: Int?): ConnectPageDto {
        val limit = (limitRaw ?: 20).coerceIn(1, 50)
        val blocked = blockedPairs(requesterId)
        val blockedList = blocked.toList()

        fun Query.excludeBlocked(column: Column<UUID>): Query =
            if (blockedList.isEmpty()) this else this.andWhere { column notInList blockedList }

        // For DB-paged kinds: fetch limit+1 rows to compute nextOffset without a COUNT.
        var hasMore = false
        val ids: List<UUID> = when (kind) {
            ListKind.FOLLOWERS -> dbQuery {
                Follows.select(Follows.followerId).where { Follows.followeeId eq targetId }
                    .excludeBlocked(Follows.followerId)
                    .orderBy(Follows.createdAt to SortOrder.DESC)
                    .limit(limit + 1).offset(offset.toLong())
                    .map { it[Follows.followerId] }
                    .also { hasMore = it.size > limit }
                    .take(limit)
            }
            ListKind.FOLLOWING -> dbQuery {
                Follows.select(Follows.followeeId).where { Follows.followerId eq targetId }
                    .excludeBlocked(Follows.followeeId)
                    .orderBy(Follows.createdAt to SortOrder.DESC)
                    .limit(limit + 1).offset(offset.toLong())
                    .map { it[Follows.followeeId] }
                    .also { hasMore = it.size > limit }
                    .take(limit)
            }
            ListKind.SUBSCRIBERS -> dbQuery {
                com.telefam.db.Subscriptions.select(com.telefam.db.Subscriptions.subscriberId)
                    .where { com.telefam.db.Subscriptions.creatorId eq targetId }
                    .excludeBlocked(com.telefam.db.Subscriptions.subscriberId)
                    .orderBy(com.telefam.db.Subscriptions.createdAt to SortOrder.DESC)
                    .limit(limit + 1).offset(offset.toLong())
                    .map { it[com.telefam.db.Subscriptions.subscriberId] }
                    .also { hasMore = it.size > limit }
                    .take(limit)
            }
            ListKind.FRIENDS -> {
                val visible = dbQuery { friendIdsOf(targetId) }.filter { it !in blocked }
                hasMore = visible.size > offset + limit
                visible.drop(offset).take(limit)
            }
        }
        val myFollowing = dbQuery { followingIds(requesterId) }
        val myFriends = dbQuery { friendIdsOf(requesterId) }
        val dtos = toConnectDtos(ids, requesterId, myFollowing, myFriends)
        val nextOffset = if (hasMore) offset + limit else null
        return ConnectPageDto(dtos, nextOffset, LocalDateTime.now().toString())
    }

    // ---------- subscribe / unsubscribe (idempotent, same block rules as follow) ----------

    @Serializable
    data class SubscribeStateDto(val viewerSubscribed: Boolean, val subscriberCount: Long)

    suspend fun setSubscribe(userId: UUID, targetId: UUID, subscribe: Boolean): SubscribeStateDto {
        require(userId != targetId) { "cannot subscribe to yourself" }
        val blocked = blockedPairs(userId)
        require(targetId !in blocked) { "unavailable" }
        val targetExists = dbQuery {
            Users.selectAll().where { (Users.id eq targetId) and (Users.profileComplete eq true) and (Users.accountStatus eq "ACTIVE") }.any()
        }
        require(targetExists) { "unavailable" }
        var newSubscription = false
        RlsContext.asUser(userId) {
            val exists = com.telefam.db.Subscriptions.selectAll().where {
                (com.telefam.db.Subscriptions.subscriberId eq userId) and (com.telefam.db.Subscriptions.creatorId eq targetId)
            }.any()
            if (subscribe && !exists) {
                com.telefam.db.Subscriptions.insert {
                    it[com.telefam.db.Subscriptions.id] = UUID.randomUUID()
                    it[subscriberId] = userId; it[creatorId] = targetId; it[createdAt] = LocalDateTime.now()
                }
                newSubscription = true
            } else if (!subscribe && exists) {
                com.telefam.db.Subscriptions.deleteWhere { (com.telefam.db.Subscriptions.subscriberId eq userId) and (com.telefam.db.Subscriptions.creatorId eq targetId) }
            }
        }
        val count = dbQuery { com.telefam.db.Subscriptions.selectAll().where { com.telefam.db.Subscriptions.creatorId eq targetId }.count() }
        if (newSubscription) {
            com.telefam.notifications.NotificationService.notify(
                userId = targetId,
                type = com.telefam.notifications.NotificationTypes.SUBSCRIPTION,
                title = "New subscriber",
                body = "subscribed to your channel",
                actorId = userId,
                targetType = com.telefam.notifications.NotificationTargets.SUBSCRIPTIONS,
                targetId = targetId.toString()
            )
        }
        return SubscribeStateDto(subscribe, count)
    }

    // ---------- shared mapping ----------

    private suspend fun avatarPath(userId: UUID, profileMediaId: UUID?): String? {
        if (profileMediaId == null) return null
        val valid = dbQuery {
            MediaAssets.selectAll().where { MediaAssets.id eq profileMediaId }.any()
        }
        return if (valid) "/api/feeds/avatar/$userId" else null
    }

    private suspend fun toConnectDtos(
        ids: List<UUID>,
        viewerId: UUID,
        viewerFollowing: Set<UUID>,
        viewerFriends: Set<UUID>
    ): List<ConnectUserDto> {
        if (ids.isEmpty()) return emptyList()
        val users = dbQuery { Users.selectAll().where { Users.id inList ids }.associateBy { it[Users.id].value } }
        val followedByViewer = ids.toSet()
        val followsViewer: Set<UUID> = dbQuery {
            Follows.selectAll().where {
                (Follows.followerId inList ids) and (Follows.followeeId eq viewerId)
            }.map { it[Follows.followerId] }.toSet()
        }
        val verified = kotlinx.coroutines.runBlocking { com.telefam.verification.BadgeService.verifiedUserIds(ids) }
        val officialId = com.telefam.official.OfficialAccountService.idOrNull()
        return ids.mapNotNull { id ->
            val u = users[id] ?: return@mapNotNull null
            ConnectUserDto(
                userId = id.toString(),
                username = u[Users.username],
                fullName = u[Users.fullName],
                avatarUrl = avatarPath(id, u[Users.profileImageMediaId]),
                viewerFollowing = id in viewerFollowing,
                viewerFollowedBy = id in followsViewer && id in followedByViewer,
                isFriend = id in viewerFriends,
                isVerified = id in verified || id == officialId,
                isOfficialAccount = id == officialId
            )
        }
    }
}
