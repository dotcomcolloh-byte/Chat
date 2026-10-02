package com.telefam.posts

import com.telefam.config.AppConfig
import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MediaAssets
import com.telefam.db.MessageRequests
import com.telefam.db.RlsContext
import com.telefam.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class FeedVariantDto(val kind: String, val url: String, val width: Int = 0, val height: Int = 0)

@Serializable
data class FeedPostDto(
    val postId: String,
    val ownerId: String,
    val ownerUsername: String?,
    val ownerFullName: String?,
    val ownerAvatarUrl: String?,
    val ownerFollowerCount: Long = 0,
    val description: String?,
    val hashtags: List<String> = emptyList(),
    val taggedUserIds: List<String> = emptyList(),
    val commenting: String = "EVERYONE",
    val privacy: String = "PUBLIC",
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songPreviewUrl: String? = null,
    val songArtworkUrl: String? = null,
    val durationMs: Long = 0,
    val createdAt: String = "",
    val likeCount: Long = 0,
    val viewCount: Long = 0,
    val reshareCount: Long = 0,
    val commentCount: Long = 0, // commenting ships later; field reserved so clients are stable
    val viewerLiked: Boolean = false,
    val viewerSaved: Boolean = false,
    val viewerFollowing: Boolean = false,
    val isOwner: Boolean = false,
    /** Backend-granted, unexpired verification badge for the post owner. */
    val ownerVerified: Boolean = false,
    val downloadsAllowed: Boolean = true,
    val subscriberOnly: Boolean = false,
    val reshareOfId: String? = null,
    val thumbnailUrl: String? = null,
    val shareUrl: String = "",
    val variants: List<FeedVariantDto> = emptyList(),
    /** Sponsored (paid boost) markers — set only by the server when a campaign injected this post. */
    val sponsored: Boolean = false,
    val sponsoredCampaignId: String? = null,
    /** CTA label + behavior for the sponsored button (Watch / Visit / Follow / Buy / ...). */
    val sponsoredCta: String? = null,
    /** WATCH | OPEN_LINK | FOLLOW */
    val sponsoredAction: String? = null,
    val sponsoredLink: String? = null
)

@Serializable
data class FeedPageDto(
    val items: List<FeedPostDto>,
    val nextCursor: String? = null,
    val serverTime: String = ""
)

@Serializable
data class ViewRecordRequest(val watchedMs: Long = 0, val completed: Boolean = false)

@Serializable
data class CountResponse(val count: Long)

@Serializable
data class ReshareRequest(val channel: String = "EXTERNAL") // EXTERNAL | CHAT | REPOST

@Serializable
data class PostReportRequest(val reason: String, val details: String? = null)

@Serializable
data class EditPostRequest(
    val description: String? = null,
    val hashtags: List<String>? = null,
    val taggedUserIds: List<String>? = null,
    val commenting: String? = null,
    val privacy: String? = null,
    val embedAllowed: Boolean? = null,
    val downloadsAllowed: Boolean? = null,
    val subscriberOnly: Boolean? = null
)

@Serializable
data class DownloadsAllowedRequest(val allowed: Boolean)

/**
 * Everything the Feeds screen reads or mutates. Privacy is enforced here in SQL,
 * never in the client: PRIVATE posts are owner-only, FRIENDS posts require an
 * accepted relationship, and blocked pairs are excluded in BOTH directions
 * (blocking is symmetric for content visibility, same as messaging).
 *
 * Read queries run on the privileged connection (Posts is not RLS-scoped; the
 * engagement tables carry a NULL-escape policy so aggregate counts work).
 * All engagement WRITES go through RlsContext.asUser so the database itself
 * verifies the row belongs to the acting user.
 */
class FeedService(
    private val signedUrls: SignedUrlService,
    /** When present, active paid campaigns are mixed into feed pages as sponsored items. */
    private val campaigns: com.telefam.campaigns.CampaignService? = null
) {

    enum class Tab { FOR_YOU, FRIENDS, FOLLOWING, NEW_CREATORS }

    // ---------- relationship helpers ----------

    /** Friend = accepted message request in either direction, or mutual follow. */
    private fun friendIdsOf(userId: UUID): Set<UUID> {
        val accepted = MessageRequests.selectAll().where {
            ((MessageRequests.senderId eq userId) or (MessageRequests.receiverId eq userId)) and
                (MessageRequests.status eq "ACCEPTED")
        }.map { if (it[MessageRequests.senderId] == userId) it[MessageRequests.receiverId] else it[MessageRequests.senderId] }
        val following = Follows.selectAll().where { Follows.followerId eq userId }.map { it[Follows.followeeId] }.toSet()
        val followers = Follows.selectAll().where { Follows.followeeId eq userId }.map { it[Follows.followerId] }.toSet()
        return (accepted + (following intersect followers)).toSet()
    }

    /** Users whose content [userId] must never see, and who must never see [userId]'s content.
     *  Read inside the user's own RLS session — the policies intentionally expose exactly
     *  these two directions to the session user. */
    private suspend fun blockedPairs(userId: UUID): Set<UUID> = RlsContext.asUser(userId) {
        BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { if (it[BlockedUsers.blockerId] == userId) it[BlockedUsers.blockedId] else it[BlockedUsers.blockerId] }
            .toSet()
    }

    private fun Op<Boolean>.andNotBlocked(posts: Posts, blocked: Set<UUID>): Op<Boolean> {
        var op = this
        for (b in blocked) op = op and (posts.ownerId neq b)
        return op
    }

    // ---------- feed queries ----------

    private data class Cursor(val publishedAt: LocalDateTime, val id: UUID)

    private fun decodeCursor(raw: String?): Cursor? = raw?.split('_')?.let { parts ->
        if (parts.size != 2) null
        else runCatching { Cursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1])) }.getOrNull()
    }

    /** Stable keyset pagination over (publishedAt, id). */
    private fun cursorOp(cursor: Cursor): Op<Boolean> =
        (Posts.publishedAt less cursor.publishedAt) or
            ((Posts.publishedAt eq cursor.publishedAt) and (Posts.id less EntityID(cursor.id, Posts)))

    private fun encodeCursor(publishedAt: LocalDateTime, id: UUID) = "${publishedAt}_$id"

    /** Make user-entered search text literal for SQL LIKE (%, _, and \\ are wildcards/escapes). */
    private fun escapeLikePattern(value: String): String =
        value.replace("\\\\", "\\\\\\\\").replace("%", "\\\\%").replace("_", "\\\\_").trim().take(100)

    suspend fun feed(userId: UUID, tab: Tab, cursorRaw: String?, limitRaw: Int?): FeedPageDto {
        val limit = (limitRaw ?: AppConfig.feedPageSize).coerceIn(1, 30)
        val blocked = blockedPairs(userId)
        val cursor = decodeCursor(cursorRaw)
        val relationFilter: Op<Boolean> = when (tab) {
            Tab.FOR_YOU, Tab.NEW_CREATORS -> Op.TRUE
            Tab.FRIENDS -> {
                val friends = dbQuery { friendIdsOf(userId) }
                if (friends.isEmpty()) return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
                Posts.ownerId inList friends.toList()
            }
            Tab.FOLLOWING -> {
                val following = dbQuery {
                    Follows.selectAll().where { Follows.followerId eq userId }.map { it[Follows.followeeId] }
                }
                if (following.isEmpty()) return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
                Posts.ownerId inList following
            }
        }

        var op: Op<Boolean> = (Posts.status eq "PUBLISHED") and Posts.publishedAt.isNotNull() and relationFilter
        // Visibility: PRIVATE is owner-only; FRIENDS requires a relationship.
        val friends = if (tab == Tab.FRIENDS) null else dbQuery { friendIdsOf(userId) }
        op = op and (
            (Posts.privacy eq "PUBLIC") or
                (Posts.ownerId eq userId) or
                ((Posts.privacy eq "FRIENDS") and
                    (if (friends.isNullOrEmpty()) Op.FALSE else (Posts.ownerId inList (friends + userId).toList())))
            )
        op = op.andNotBlocked(Posts, blocked)
        // Keep the personalization exclusion in SQL so a user with a large
        // not-interested history does not load that entire history into memory.
        op = op and (Posts.id notInSubQuery NotInterested.select(NotInterested.postId).where {
            NotInterested.userId eq userId
        })
        if (cursor != null) op = op and cursorOp(cursor)

        // Over-fetch so NOT_INTERESTED and new-creator filtering still fill a full page.
        val rows = dbQuery {
            Posts.selectAll().where(op)
                .orderBy(Posts.publishedAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(if (tab == Tab.NEW_CREATORS) limit * 3 else limit * 2)
                .toList()
        }

        val newCreatorOwners: Set<UUID> = if (tab == Tab.NEW_CREATORS && rows.isNotEmpty()) {
            val ownerIds = rows.map { it[Posts.ownerId] }.distinct()
            val now = LocalDateTime.now()
            val ownerPostDates = dbQuery {
                Posts.selectAll().where {
                    (Posts.ownerId inList ownerIds) and (Posts.status eq "PUBLISHED") and Posts.publishedAt.isNotNull()
                }.map { it[Posts.ownerId] to it[Posts.publishedAt] }
            }
            ownerPostDates.groupBy({ it.first }, { it.second })
                .filterValues { dates ->
                    val count = dates.size
                    val first = dates.filterNotNull().minOrNull()
                    count <= 5 || (first != null && first.isAfter(now.minusDays(30)))
                }.keys
        } else emptySet()

        val filtered = rows.asSequence()
            .filter { tab != Tab.NEW_CREATORS || it[Posts.ownerId] in newCreatorOwners }
            .take(limit).toList()

        val nextCursor = if (filtered.size >= limit && filtered.isNotEmpty()) {
            val last = filtered.last()
            encodeCursor(last[Posts.publishedAt] ?: last[Posts.createdAt], last[Posts.id].value)
        } else null

        return FeedPageDto(withSponsored(toDtos(filtered, userId), userId), nextCursor, LocalDateTime.now().toString())
    }

    /**
     * Mixes one active paid campaign into an organic page as a sponsored item.
     * Eligibility (active window, remaining reach, block pairs, not the viewer's own
     * campaign) is decided in CampaignService; expiry is server-enforced by wall-clock
     * (endsAt sweep) and by reach (impressions flip the campaign to EXHAUSTED).
     */
    private suspend fun withSponsored(items: List<FeedPostDto>, viewerId: UUID): List<FeedPostDto> {
        val campaignService = campaigns ?: return items
        val campaign = runCatching { campaignService.activeSponsoredForViewer(viewerId, 1) }
            .getOrNull()?.firstOrNull() ?: return items
        val campaignId = campaign[com.telefam.campaigns.Campaigns.id].value
        if (items.any { it.sponsoredCampaignId == campaignId.toString() }) return items
        val postId = campaign[com.telefam.campaigns.Campaigns.postId] ?: return items
        val postRow = dbQuery {
            Posts.selectAll().where {
                (Posts.id eq postId) and (Posts.status eq "PUBLISHED") and (Posts.privacy eq "PUBLIC")
            }.firstOrNull()
        } ?: return items
        if (items.any { it.postId == postId.toString() }) return items
        val dto = toDtos(listOf(postRow), viewerId).firstOrNull() ?: return items
        if (dto.subscriberOnly || dto.variants.isEmpty()) return items // never promote locked/unplayable media
        val (cta, action) = when (campaign[com.telefam.campaigns.Campaigns.type]) {
            "PROFILE_BOOST", "FOLLOWERS" -> "Follow" to "FOLLOW"
            "GET_SALES" -> when (campaign[com.telefam.campaigns.Campaigns.linkAction]) {
                "DOWNLOAD" -> "Download" to "OPEN_LINK"
                "BUY" -> "Buy" to "OPEN_LINK"
                "GET_IN_TOUCH" -> "Get in Touch" to "OPEN_LINK"
                "SIGN_UP" -> "Sign Up" to "OPEN_LINK"
                "WATCH" -> "Watch" to "OPEN_LINK"
                else -> "Visit" to "OPEN_LINK"
            }
            else -> "Watch" to "WATCH"
        }
        val sponsored = dto.copy(
            sponsored = true,
            sponsoredCampaignId = campaignId.toString(),
            sponsoredCta = cta,
            sponsoredAction = action,
            sponsoredLink = campaign[com.telefam.campaigns.Campaigns.linkUrl]
        )

        // Create server-side delivery proof only after the final sponsored item has
        // passed the same visibility/media checks used to build the response. The
        // client continues using the existing campaignId/event API; no UI or wire
        // model change is required.
        val deliveryRecorded = runCatching {
            campaignService.markSponsoredDelivery(viewerId, campaignId)
        }.getOrDefault(false)
        if (!deliveryRecorded) return items

        val mutable = items.toMutableList()
        // Sponsored placement: after the first 4 organic videos (index 4).
        mutable.add(kotlin.math.min(4, mutable.size), sponsored)
        return mutable
    }

    /** Search by hashtag (#tag or bare tag) and/or free text against description.
     *  Same visibility rules as feeds — search never leaks PRIVATE/FRIENDS content. */
    suspend fun search(userId: UUID, queryRaw: String, cursorRaw: String?, limitRaw: Int?): FeedPageDto {
        val limit = (limitRaw ?: AppConfig.feedPageSize * 2).coerceIn(1, 50)
        val q = queryRaw.trim().take(100)
        if (q.isEmpty()) return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
        val blocked = blockedPairs(userId)
        val friends = dbQuery { friendIdsOf(userId) }
        val cursor = decodeCursor(cursorRaw)

        // Search terms are treated as literal text, never as SQL LIKE wildcards.
        // Hashtags are stored as space-separated normalized tokens, so a hashtag search
        // must match a complete token (not e.g. "#art" inside "#cartoon").
        val searchTerm = escapeLikePattern(q.removePrefix("#").lowercase())
        val hashtagOp: Op<Boolean> = if (searchTerm.isBlank()) {
            Op.FALSE
        } else {
            (Posts.hashtags.lowerCase() eq searchTerm) or
                (Posts.hashtags.lowerCase() like "$searchTerm %") or
                (Posts.hashtags.lowerCase() like "% $searchTerm %") or
                (Posts.hashtags.lowerCase() like "% $searchTerm")
        }
        val textOp: Op<Boolean> = if (q.startsWith("#")) {
            hashtagOp
        } else {
            (Posts.description.lowerCase() like "%$searchTerm%") or
                hashtagOp or
                (Posts.songTitle.lowerCase() like "%$searchTerm%") or
                (Posts.songArtist.lowerCase() like "%$searchTerm%")
        }
        var op: Op<Boolean> = (Posts.status eq "PUBLISHED") and Posts.publishedAt.isNotNull() and textOp and (
            (Posts.privacy eq "PUBLIC") or
                (Posts.ownerId eq userId) or
                ((Posts.privacy eq "FRIENDS") and
                    if (friends.isEmpty()) Op.FALSE else (Posts.ownerId inList (friends + userId).toList()))
            )
        op = op.andNotBlocked(Posts, blocked)
        op = op and (Posts.id notInSubQuery NotInterested.select(NotInterested.postId).where {
            NotInterested.userId eq userId
        })
        if (cursor != null) op = op and cursorOp(cursor)
        val rows = dbQuery {
            Posts.selectAll().where(op)
                .orderBy(Posts.publishedAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(limit).toList()
        }
        val next = if (rows.size >= limit && rows.isNotEmpty()) {
            val last = rows.last()
            encodeCursor(last[Posts.publishedAt] ?: last[Posts.createdAt], last[Posts.id].value)
        } else null
        return FeedPageDto(toDtos(rows, userId), next, LocalDateTime.now().toString())
    }

    /** Another user's posts, for the profile screen's watch flow — same visibility rules. */
    suspend fun userPosts(requesterId: UUID, ownerId: UUID, cursorRaw: String?, limitRaw: Int?): FeedPageDto {
        val blocked = blockedPairs(requesterId)
        if (ownerId in blocked) return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
        val isOwner = requesterId == ownerId
        val friends = if (isOwner) emptySet() else dbQuery { friendIdsOf(requesterId) }
        val visibility: Op<Boolean> =
            if (isOwner) Op.TRUE
            else (Posts.privacy eq "PUBLIC") or
                ((Posts.privacy eq "FRIENDS") and if (ownerId in friends) Op.TRUE else Op.FALSE)
        val cursor = decodeCursor(cursorRaw)
        var op: Op<Boolean> = (Posts.ownerId eq ownerId) and (Posts.status eq "PUBLISHED") and Posts.publishedAt.isNotNull() and visibility
        op = op and (Posts.id notInSubQuery NotInterested.select(NotInterested.postId).where {
            NotInterested.userId eq requesterId
        })
        if (cursor != null) op = op and cursorOp(cursor)
        val limit = (limitRaw ?: AppConfig.feedPageSize * 2).coerceIn(1, 50)
        val rows = dbQuery {
            Posts.selectAll().where(op)
                .orderBy(Posts.publishedAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(limit).toList()
        }
        val next = if (rows.size >= limit && rows.isNotEmpty()) {
            val last = rows.last()
            encodeCursor(last[Posts.publishedAt] ?: last[Posts.createdAt], last[Posts.id].value)
        } else null
        return FeedPageDto(toDtos(rows, requesterId), next, LocalDateTime.now().toString())
    }

    // ---------- owner profile tabs (Posts / Reshared / Locked / Saved) ----------

    enum class ProfileTab { POSTS, RESHARED, LOCKED, SAVED }

    /**
     * The profile grid tabs. RESHARED = the owner's own reposts (reshare_of_id set);
     * LOCKED = the owner's PRIVATE posts, visible to the owner only;
     * SAVED = posts the owner saved from anyone, visible to the owner only.
     * A non-owner asking for LOCKED or SAVED always gets an empty page.
     */
    suspend fun userTabPosts(requesterId: UUID, ownerId: UUID, tab: ProfileTab, cursorRaw: String?, limitRaw: Int?): FeedPageDto {
        if (tab == ProfileTab.POSTS) return userPosts(requesterId, ownerId, cursorRaw, limitRaw)
        val isOwner = requesterId == ownerId
        if ((tab == ProfileTab.LOCKED || tab == ProfileTab.SAVED) && !isOwner) {
            return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
        }
        val blocked = blockedPairs(requesterId)
        if (ownerId in blocked) return FeedPageDto(emptyList(), null, LocalDateTime.now().toString())
        val cursor = decodeCursor(cursorRaw)
        val limit = (limitRaw ?: AppConfig.feedPageSize * 2).coerceIn(1, 50)

        var op: Op<Boolean> = when (tab) {
            ProfileTab.RESHARED -> (Posts.ownerId eq ownerId) and (Posts.status eq "PUBLISHED") and
                Posts.reshareOfId.isNotNull() and Posts.publishedAt.isNotNull() and
                if (isOwner) Op.TRUE else (Posts.privacy eq "PUBLIC")
            ProfileTab.LOCKED -> (Posts.ownerId eq ownerId) and (Posts.status eq "PUBLISHED") and
                (Posts.privacy eq "PRIVATE") and Posts.publishedAt.isNotNull()
            ProfileTab.SAVED -> (Posts.status eq "PUBLISHED") and Posts.publishedAt.isNotNull() and
                (Posts.id inSubQuery PostSaves.select(PostSaves.postId).where { PostSaves.userId eq ownerId })
            ProfileTab.POSTS -> Op.TRUE // handled above
        }
        op = op.andNotBlocked(Posts, blocked)
        if (cursor != null) op = op and cursorOp(cursor)
        val rows = dbQuery {
            Posts.selectAll().where(op)
                .orderBy(Posts.publishedAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(limit).toList()
        }
        val next = if (rows.size >= limit && rows.isNotEmpty()) {
            val last = rows.last()
            encodeCursor(last[Posts.publishedAt] ?: last[Posts.createdAt], last[Posts.id].value)
        } else null
        return FeedPageDto(toDtos(rows, requesterId), next, LocalDateTime.now().toString())
    }

    // ---------- engagement mutations (RLS-scoped writes) ----------

    suspend fun recordView(userId: UUID, postId: UUID, watchedMs: Long, completed: Boolean) = RlsContext.asUser(userId) {
        val existing = PostViews.selectAll().where { (PostViews.postId eq postId) and (PostViews.viewerId eq userId) }.singleOrNull()
        if (existing == null) {
            PostViews.insert {
                it[PostViews.id] = UUID.randomUUID()
                it[PostViews.postId] = postId
                it[viewerId] = userId
                it[PostViews.watchedMs] = watchedMs.coerceAtLeast(0)
                it[PostViews.completed] = completed
                it[viewedAt] = LocalDateTime.now()
            }
        } else {
            PostViews.update({ PostViews.id eq existing[PostViews.id] }) {
                it[PostViews.watchedMs] = maxOf(existing[PostViews.watchedMs], watchedMs.coerceAtLeast(0))
                it[PostViews.completed] = existing[PostViews.completed] || completed
                it[viewedAt] = LocalDateTime.now()
            }
        }
    }

    suspend fun setLike(userId: UUID, postId: UUID, like: Boolean): Long {
        var newLike = false
        RlsContext.asUser(userId) {
            val exists = PostLikes.selectAll().where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.any()
            if (like && !exists) {
                PostLikes.insert {
                    it[PostLikes.id] = UUID.randomUUID(); it[PostLikes.postId] = postId
                    it[PostLikes.userId] = userId; it[createdAt] = LocalDateTime.now()
                }
                newLike = true
            } else if (!like && exists) {
                PostLikes.deleteWhere { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
            }
        }
        if (newLike) {
            val ownerId = dbQuery {
                Posts.selectAll().where { Posts.id eq postId }.singleOrNull()?.get(Posts.ownerId)
            }
            if (ownerId != null && ownerId != userId) {
                com.telefam.notifications.NotificationService.notify(
                    userId = ownerId,
                    type = com.telefam.notifications.NotificationTypes.LIKE,
                    title = "New like",
                    body = "liked your post",
                    actorId = userId,
                    targetType = com.telefam.notifications.NotificationTargets.POST,
                    targetId = postId.toString()
                )
            }
        }
        return dbQuery { PostLikes.selectAll().where { PostLikes.postId eq postId }.count() }
    }

    suspend fun setSave(userId: UUID, postId: UUID, save: Boolean): Long {
        RlsContext.asUser(userId) {
            val exists = PostSaves.selectAll().where { (PostSaves.postId eq postId) and (PostSaves.userId eq userId) }.any()
            if (save && !exists) {
                PostSaves.insert {
                    it[PostSaves.id] = UUID.randomUUID(); it[PostSaves.postId] = postId
                    it[PostSaves.userId] = userId; it[createdAt] = LocalDateTime.now()
                }
            } else if (!save && exists) {
                PostSaves.deleteWhere { (PostSaves.postId eq postId) and (PostSaves.userId eq userId) }
            }
        }
        return dbQuery { PostSaves.selectAll().where { PostSaves.postId eq postId }.count() }
    }

    suspend fun reshare(userId: UUID, postId: UUID, channel: String): Long {
        val ch = channel.uppercase().let { if (it in setOf("EXTERNAL", "CHAT", "REPOST")) it else "EXTERNAL" }
        RlsContext.asUser(userId) {
            PostReshares.insert {
                it[PostReshares.id] = UUID.randomUUID(); it[PostReshares.postId] = postId
                it[PostReshares.userId] = userId; it[PostReshares.channel] = ch
                it[createdAt] = LocalDateTime.now()
            }
        }
        return dbQuery { PostReshares.selectAll().where { PostReshares.postId eq postId }.count() }
    }

    suspend fun notInterested(userId: UUID, postId: UUID) = RlsContext.asUser(userId) {
        val exists = NotInterested.selectAll().where { (NotInterested.userId eq userId) and (NotInterested.postId eq postId) }.any()
        if (!exists) {
            NotInterested.insert {
                it[NotInterested.id] = UUID.randomUUID(); it[NotInterested.userId] = userId
                it[NotInterested.postId] = postId; it[createdAt] = LocalDateTime.now()
            }
        }
    }

    suspend fun report(userId: UUID, postId: UUID, reason: String, details: String?) = RlsContext.asUser(userId) {
        val cleanReason = reason.trim().take(60)
        require(cleanReason.isNotEmpty()) { "reason required" }
        PostReports.insert {
            it[PostReports.id] = UUID.randomUUID(); it[PostReports.postId] = postId
            it[reporterId] = userId; it[PostReports.reason] = cleanReason
            it[PostReports.details] = details?.take(1000); it[createdAt] = LocalDateTime.now()
        }
    }

    suspend fun setFollow(userId: UUID, targetId: UUID, follow: Boolean) {
        if (userId == targetId) return
        RlsContext.asUser(userId) {
            val exists = Follows.selectAll().where { (Follows.followerId eq userId) and (Follows.followeeId eq targetId) }.any()
            if (follow && !exists) {
                Follows.insert {
                    it[Follows.id] = UUID.randomUUID(); it[followerId] = userId
                    it[followeeId] = targetId; it[createdAt] = LocalDateTime.now()
                }
            } else if (!follow && exists) {
                Follows.deleteWhere { (Follows.followerId eq userId) and (Follows.followeeId eq targetId) }
            }
        }
    }

    // ---------- owner operations ----------

    suspend fun ownedPost(userId: UUID, postId: UUID): FeedPostDto? {
        val row = dbQuery {
            Posts.selectAll().where { (Posts.id eq postId) and (Posts.ownerId eq userId) and (Posts.status neq "DELETED") }.firstOrNull()
        } ?: return null
        return toDtos(listOf(row), userId).firstOrNull()
    }

    suspend fun editPost(userId: UUID, postId: UUID, req: EditPostRequest): Boolean {
        if (req.subscriberOnly == true) {
            val hasActivePlan = dbQuery {
                com.telefam.subscriptions.SubscriptionPlans.selectAll().where {
                    (com.telefam.subscriptions.SubscriptionPlans.creatorId eq userId) and
                        (com.telefam.subscriptions.SubscriptionPlans.isActive eq true)
                }.any()
            }
            require(hasActivePlan) { "Create an active subscription plan before enabling subscriber-only content" }
        }
        val owned = dbQuery {
            Posts.selectAll().where { (Posts.id eq postId) and (Posts.ownerId eq userId) and (Posts.status neq "DELETED") }.any()
        }
        if (!owned) return false
        dbQuery {
            Posts.update({ Posts.id eq postId }) { st ->
                req.description?.let { d -> st[description] = d.take(2000) }
                req.hashtags?.let { tags ->
                    st[hashtags] = tags.map { it.trim().removePrefix("#").replace(Regex("[^\\p{L}\\p{N}_]"), "") }
                        .filter { it.isNotBlank() }.distinct().take(30).joinToString(" ")
                }
                req.taggedUserIds?.let { ids ->
                    st[taggedUserIds] = ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                        .distinct().take(20).joinToString(",", "[", "]")
                }
                req.commenting?.uppercase()?.let { if (it in setOf("EVERYONE", "FRIENDS", "OFF")) st[commenting] = it }
                req.privacy?.uppercase()?.let { if (it in setOf("PUBLIC", "FRIENDS", "PRIVATE")) st[privacy] = it }
                req.embedAllowed?.let { st[embedAllowed] = it }
                req.downloadsAllowed?.let { st[downloadsAllowed] = it }
                req.subscriberOnly?.let { st[subscriberOnly] = it }
            }
        }
        return true
    }

    /** Owner delete: soft-delete the row and remove the derived media files so storage doesn't leak. */
    suspend fun deletePost(userId: UUID, postId: UUID): Boolean {
        val row = dbQuery {
            Posts.selectAll().where { (Posts.id eq postId) and (Posts.ownerId eq userId) and (Posts.status neq "DELETED") }.firstOrNull()
        } ?: return false
        dbQuery { Posts.update({ Posts.id eq postId }) { it[status] = "DELETED" } }
        val paths = dbQuery { PostMedia.selectAll().where { PostMedia.postId eq postId }.map { it[PostMedia.publicPath] } }
        for (p in paths) runCatching { File(p).delete() }
        dbQuery { PostMedia.deleteWhere { PostMedia.postId eq postId } }
        com.telefam.notifications.NotificationService.notify(
            userId = userId,
            type = com.telefam.notifications.NotificationTypes.REMOVED_POST,
            title = "Post removed",
            body = "Your post was removed and is no longer visible.",
            targetType = com.telefam.notifications.NotificationTargets.NOTIFICATIONS,
            targetId = null
        )
        return true
    }

    // ---------- mapping ----------

    /**
     * Batch DTO mapping. The old implementation performed multiple database queries for
     * every post (owner, media, counts, viewer state, follower count), which made a
     * 30-item feed hundreds of queries per request. This version keeps the same response
     * contract and UI while reducing the work to a small fixed number of queries per page.
     */
    private suspend fun toDtos(rows: List<ResultRow>, viewerId: UUID): List<FeedPostDto> {
        if (rows.isEmpty()) return emptyList()

        val postIds = rows.map { it[Posts.id].value }.distinct()
        val ownerIds = rows.map { it[Posts.ownerId] }.distinct()

        val owners = dbQuery {
            Users.selectAll().where { Users.id inList ownerIds }
                .associateBy { it[Users.id].value }
        }
        val verifiedOwners = com.telefam.verification.BadgeService.verifiedUserIds(ownerIds)

        val mediaByPost = dbQuery {
            PostMedia.selectAll().where { PostMedia.postId inList postIds }
                .groupBy { it[PostMedia.postId] }
        }

        val likeCounts = dbQuery {
            PostLikes.selectAll().where { PostLikes.postId inList postIds }
                .map { it[PostLikes.postId] }
                .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }
        val viewCounts = dbQuery {
            PostViews.selectAll().where { PostViews.postId inList postIds }
                .map { it[PostViews.postId] }
                .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }
        val reshareCounts = dbQuery {
            PostReshares.selectAll().where { PostReshares.postId inList postIds }
                .map { it[PostReshares.postId] }
                .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }
        val commentCounts = dbQuery {
            com.telefam.comments.PostComments.selectAll().where {
                (com.telefam.comments.PostComments.postId inList postIds) and
                    com.telefam.comments.PostComments.deletedAt.isNull()
            }
                .map { it[com.telefam.comments.PostComments.postId] }
                .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }
        val followerCounts = dbQuery {
            Follows.selectAll().where { Follows.followeeId inList ownerIds }
                .map { it[Follows.followeeId] }
                .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        }

        val (likedIds, savedIds, followedIds) = RlsContext.asUser(viewerId) {
            Triple(
                PostLikes.selectAll().where {
                    (PostLikes.userId eq viewerId) and (PostLikes.postId inList postIds)
                }.map { it[PostLikes.postId] }.toSet(),
                PostSaves.selectAll().where {
                    (PostSaves.userId eq viewerId) and (PostSaves.postId inList postIds)
                }.map { it[PostSaves.postId] }.toSet(),
                Follows.selectAll().where {
                    (Follows.followerId eq viewerId) and (Follows.followeeId inList ownerIds)
                }.map { it[Follows.followeeId] }.toSet()
            )
        }

        val friendIds = dbQuery { friendIdsOf(viewerId) }
        val now = LocalDateTime.now()
        val entitledOwnerIds = dbQuery {
            if (ownerIds.isEmpty()) emptySet() else com.telefam.subscriptions.PaidSubscriptions.selectAll().where {
                (com.telefam.subscriptions.PaidSubscriptions.subscriberId eq viewerId) and
                    (com.telefam.subscriptions.PaidSubscriptions.creatorId inList ownerIds) and
                    (
                        ((com.telefam.subscriptions.PaidSubscriptions.status eq "ACTIVE") and
                            com.telefam.subscriptions.PaidSubscriptions.currentPeriodEnd.isNotNull() and
                            (com.telefam.subscriptions.PaidSubscriptions.currentPeriodEnd greaterEq now)) or
                        ((com.telefam.subscriptions.PaidSubscriptions.status eq "PAST_DUE") and
                            (com.telefam.subscriptions.PaidSubscriptions.graceUntil greaterEq now))
                    )
            }.map { it[com.telefam.subscriptions.PaidSubscriptions.creatorId] }.toSet()
        }

        val profileMediaIds = owners.values.mapNotNull { it[Users.profileImageMediaId] }.distinct()
        val validAvatarMediaIds = if (profileMediaIds.isEmpty()) emptySet() else dbQuery {
            MediaAssets.selectAll().where { MediaAssets.id inList profileMediaIds }
                .map { it[MediaAssets.id].value }.toSet()
        }

        return rows.map { row ->
            val postId = row[Posts.id].value
            val ownerId = row[Posts.ownerId]
            val owner = owners[ownerId]
            val isSubscriberOnly = row[Posts.subscriberOnly]
            val canViewSubscriberContent = ownerId == viewerId || ownerId in entitledOwnerIds
            val locked = isSubscriberOnly && !canViewSubscriberContent
            val media = if (locked) emptyList() else mediaByPost[postId].orEmpty()
            val variants = media
                .filter { it[PostMedia.kind].startsWith("VIDEO_") }
                .map {
                    FeedVariantDto(
                        it[PostMedia.kind],
                        signedUrls.sign(postId, it[PostMedia.kind], if (isSubscriberOnly) viewerId else null),
                        it[PostMedia.width] ?: 0,
                        it[PostMedia.height] ?: 0
                    )
                }
                .sortedByDescending { it.height }
            val thumbnail = media.firstOrNull { it[PostMedia.kind] == "THUMBNAIL" }
                ?.let { signedUrls.sign(postId, "THUMBNAIL", if (isSubscriberOnly) viewerId else null) }

            val profileMediaId = owner?.get(Users.profileImageMediaId)
            val avatar = if (profileMediaId != null && profileMediaId in validAvatarMediaIds) {
                "/api/feeds/avatar/$ownerId"
            } else null

            val viewerFollowing = ownerId == viewerId || followedIds.contains(ownerId) || friendIds.contains(ownerId)
            val hashtags = row[Posts.hashtags].split(' ').filter { it.isNotBlank() }
            val tagged = row[Posts.taggedUserIds]
                .removePrefix("[").removeSuffix("]")
                .split(',').filter { it.isNotBlank() }

            FeedPostDto(
                ownerVerified = ownerId in verifiedOwners,
                postId = postId.toString(),
                ownerId = ownerId.toString(),
                ownerUsername = owner?.get(Users.username),
                ownerFullName = owner?.get(Users.fullName),
                ownerAvatarUrl = avatar,
                ownerFollowerCount = followerCounts[ownerId] ?: 0,
                description = if (locked) null else row[Posts.description],
                hashtags = if (locked) emptyList() else hashtags,
                taggedUserIds = if (locked) emptyList() else tagged,
                commenting = row[Posts.commenting],
                privacy = row[Posts.privacy],
                songTitle = if (locked) null else row[Posts.songTitle],
                songArtist = if (locked) null else row[Posts.songArtist],
                songPreviewUrl = if (locked) null else row[Posts.songPreviewUrl],
                songArtworkUrl = if (locked) null else row[Posts.songArtworkUrl],
                durationMs = if (locked) 0 else row[Posts.durationMs],
                createdAt = (row[Posts.publishedAt] ?: row[Posts.createdAt]).toString(),
                likeCount = likeCounts[postId] ?: 0,
                viewCount = viewCounts[postId] ?: 0,
                reshareCount = reshareCounts[postId] ?: 0,
                commentCount = commentCounts[postId] ?: 0,
                viewerLiked = postId in likedIds,
                viewerSaved = postId in savedIds,
                viewerFollowing = viewerFollowing,
                isOwner = ownerId == viewerId,
                downloadsAllowed = row[Posts.downloadsAllowed],
                subscriberOnly = isSubscriberOnly,
                reshareOfId = row[Posts.reshareOfId]?.toString(),
                thumbnailUrl = thumbnail,
                shareUrl = "${AppConfig.publicBaseUrl.trimEnd('/')}/p/$postId",
                variants = variants
            )
        }
    }

}
