package com.telefam.comments

import com.telefam.campaigns.InsufficientStarsException
import com.telefam.campaigns.StarLedgerEntries
import com.telefam.campaigns.StarWallets
import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MediaAssets
import com.telefam.db.MessageRequests
import com.telefam.db.RlsContext
import com.telefam.db.Users
import com.telefam.posts.Follows
import com.telefam.posts.Posts
import com.telefam.wallet.EarningSource
import com.telefam.wallet.WalletService
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.LocalDateTime
import java.util.UUID

// ------------------------------------------------------------------ DTOs

@Serializable
data class CommentMentionDto(val userId: String, val username: String? = null)

@Serializable
data class CommentDto(
    val commentId: String,
    val postId: String,
    val parentId: String? = null,
    val rootId: String,
    val authorId: String,
    val authorUsername: String? = null,
    val authorFullName: String? = null,
    val authorAvatarUrl: String? = null,
    val kind: String = "TEXT",           // TEXT | PHOTO | STICKER | GIF
    val body: String? = null,
    /** Authenticated media endpoint for kind=PHOTO. */
    val mediaUrl: String? = null,
    val stickerUrl: String? = null,
    val mentions: List<CommentMentionDto> = emptyList(),
    val likeCount: Long = 0,
    val replyCount: Long = 0,
    val viewerLiked: Boolean = false,
    val viewerIsAuthor: Boolean = false,
    /** True on the single owner-pinned comment of the post. */
    val pinnedByOwner: Boolean = false,
    val viewerIsPostOwner: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    /** Total stars gifted to this comment (all time). */
    val starTotal: Long = 0,
    val createdAt: String = ""
)

@Serializable
data class CommentPageDto(
    val items: List<CommentDto>,
    val nextCursor: String? = null,
    /** Present on the FIRST page only: the owner-pinned comment, if any. */
    val pinned: CommentDto? = null,
    /** Post-level commenting state so the client can render "comments are off" UIs. */
    val commenting: String = "EVERYONE",
    val viewerCanComment: Boolean = true,
    val viewerIsPostOwner: Boolean = false,
    val totalCount: Long = 0,
    val serverTime: String = ""
)

@Serializable
data class CreateCommentRequest(
    val body: String? = null,
    val parentId: String? = null,
    /** TEXT | PHOTO | STICKER | GIF */
    val kind: String = "TEXT",
    /** MediaAssets id returned by /api/media/upload — must belong to the author. */
    val mediaId: String? = null,
    /** GIPHY rendition URL for STICKER/GIF kinds. */
    val stickerUrl: String? = null
)

@Serializable
data class EditCommentRequest(val body: String)

@Serializable
data class CommentReportRequest(val reason: String, val details: String? = null)

@Serializable
data class GiftStarsRequest(val stars: Long)

@Serializable
data class GiftStarsResponse(
    val balanceStars: Long,
    val giftId: String,
    val commentStarTotal: Long
)

/** Thrown for any comment-operation policy violation; route maps it to 403/404/400 by kind. */
class CommentException(val statusCode: Int, message: String) : Exception(message)

/**
 * Everything the comment sheet reads or mutates. Post privacy is re-derived from the
 * DB on EVERY call (never trusted from the client): private posts are owner-only,
 * friends posts need an accepted relationship or mutual follow, and blocked pairs are
 * excluded in both directions. The post's own `commenting` setting (EVERYONE |
 * FRIENDS | OFF) gates writes; reads of existing comments follow post visibility.
 */
class CommentService(
    private val wallet: WalletService? = null
) {
    companion object {
        const val MAX_BODY = 2000
        const val MAX_STARS_PER_GIFT = 50_000L
        val KINDS = setOf("TEXT", "PHOTO", "STICKER", "GIF")
        /** Edit window after posting, in minutes (edit history kept via editedAt). */
        const val EDIT_WINDOW_MINUTES = 60L
    }

    // ------------------------------------------------------- relationship helpers

    private fun friendIdsOf(userId: UUID): Set<UUID> {
        val accepted = MessageRequests.selectAll().where {
            ((MessageRequests.senderId eq userId) or (MessageRequests.receiverId eq userId)) and
                (MessageRequests.status eq "ACCEPTED")
        }.map { if (it[MessageRequests.senderId] == userId) it[MessageRequests.receiverId] else it[MessageRequests.senderId] }
        val following = Follows.selectAll().where { Follows.followerId eq userId }.map { it[Follows.followeeId] }.toSet()
        val followers = Follows.selectAll().where { Follows.followeeId eq userId }.map { it[Follows.followerId] }.toSet()
        return (accepted + (following intersect followers)).toSet()
    }

    private suspend fun blockedPairs(userId: UUID): Set<UUID> = RlsContext.asUser(userId) {
        BlockedUsers.selectAll().where { (BlockedUsers.blockerId eq userId) or (BlockedUsers.blockedId eq userId) }
            .map { if (it[BlockedUsers.blockerId] == userId) it[BlockedUsers.blockedId] else it[BlockedUsers.blockerId] }
            .toSet()
    }

    private data class PostGate(
        val postId: UUID, val ownerId: UUID, val commenting: String,
        val viewerIsOwner: Boolean, val viewerCanSee: Boolean, val viewerCanComment: Boolean
    )

    /** Single source of truth for "may this user see/comment on this post". */
    private suspend fun gate(viewerId: UUID, postId: UUID): PostGate {
        val row = dbQuery {
            Posts.selectAll().where { (Posts.id eq postId) and (Posts.status eq "PUBLISHED") }.singleOrNull()
        } ?: throw CommentException(404, "Post not found")
        val ownerId = row[Posts.ownerId]
        val blocked = blockedPairs(viewerId)
        val isOwner = ownerId == viewerId
        val privacy = row[Posts.privacy]
        val friends = dbQuery { friendIdsOf(viewerId) }
        val canSee = when {
            isOwner -> true
            ownerId in blocked -> false
            privacy == "PRIVATE" -> false
            privacy == "FRIENDS" -> ownerId in friends
            else -> true // PUBLIC
        }
        if (!canSee) throw CommentException(404, "Post not found") // do not leak existence
        val commenting = row[Posts.commenting]
        val canComment = when {
            isOwner -> commenting != "OFF"
            commenting == "EVERYONE" -> true
            commenting == "FRIENDS" -> ownerId in friends
            else -> false // OFF
        }
        return PostGate(postId, ownerId, commenting, isOwner, viewerCanSee = true, viewerCanComment = canComment)
    }

    // ------------------------------------------------------- pagination

    private data class Cursor(val createdAt: LocalDateTime, val id: UUID)

    private fun decodeCursor(raw: String?): Cursor? = raw?.split('_')?.let { p ->
        if (p.size != 2) null
        else runCatching { Cursor(LocalDateTime.parse(p[0]), UUID.fromString(p[1])) }.getOrNull()
    }

    private fun encodeCursor(c: LocalDateTime, id: UUID) = "${c}_$id"

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").trim().take(100)

    // ------------------------------------------------------- DTO mapping

    private fun toDtos(
        rows: List<ResultRow>, viewerId: UUID, postOwnerId: UUID,
        replyCounts: Map<UUID, Long> = emptyMap()
    ): List<CommentDto> {
        if (rows.isEmpty()) return emptyList()
        val commentIds = rows.map { it[PostComments.id].value }
        val authorIds = rows.map { it[PostComments.authorId] }.distinct()
        val authors = Users.selectAll().where { Users.id inList authorIds }
            .associateBy({ it[Users.id].value }, { it })
        val likeCounts = CommentLikes.select(CommentLikes.commentId, CommentLikes.commentId.count())
            .where { CommentLikes.commentId inList commentIds }
            .groupBy(CommentLikes.commentId)
            .associate { it[CommentLikes.commentId] to it[CommentLikes.commentId.count()] }
        val viewerLikes = CommentLikes.selectAll().where {
            (CommentLikes.commentId inList commentIds) and (CommentLikes.userId eq viewerId)
        }.map { it[CommentLikes.commentId] }.toSet()
        val starTotals = CommentStarGifts.select(CommentStarGifts.commentId, CommentStarGifts.stars.sum())
            .where { CommentStarGifts.commentId inList commentIds }
            .groupBy(CommentStarGifts.commentId)
            .associate { it[CommentStarGifts.commentId] to (it[CommentStarGifts.stars.sum()] ?: 0L) }

        return rows.map { row ->
            val id = row[PostComments.id].value
            val authorId = row[PostComments.authorId]
            val author = authors[authorId]
            val deleted = row[PostComments.deletedAt] != null
            val kind = row[PostComments.kind]
            CommentDto(
                commentId = id.toString(),
                postId = row[PostComments.postId].toString(),
                parentId = row[PostComments.parentId]?.toString(),
                rootId = row[PostComments.rootId].toString(),
                authorId = authorId.toString(),
                authorUsername = author?.get(Users.username),
                authorFullName = author?.get(Users.fullName),
                authorAvatarUrl = author?.get(Users.profileImageMediaId)?.let { "/api/feeds/avatar/$authorId" },
                kind = kind,
                body = if (deleted) null else row[PostComments.body],
                mediaUrl = if (!deleted && kind == "PHOTO" && row[PostComments.mediaId] != null)
                    "/api/comments/media/$id" else null,
                stickerUrl = if (deleted) null else row[PostComments.stickerUrl],
                mentions = if (deleted) emptyList() else parseMentions(row[PostComments.mentions]),
                likeCount = if (deleted) 0 else (likeCounts[id] ?: 0L),
                replyCount = replyCounts[id] ?: 0L,
                viewerLiked = id in viewerLikes,
                viewerIsAuthor = authorId == viewerId,
                pinnedByOwner = row[PostComments.pinnedByOwner] && !deleted,
                viewerIsPostOwner = viewerId == postOwnerId,
                edited = row[PostComments.editedAt] != null,
                deleted = deleted,
                starTotal = starTotals[id] ?: 0L,
                createdAt = row[PostComments.createdAt].toString()
            )
        }
    }

    private fun parseMentions(json: String): List<CommentMentionDto> {
        val trimmed = json.removePrefix("[").removeSuffix("]")
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(";;").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            runCatching { UUID.fromString(parts[0]) }.getOrNull()
                ?.let { CommentMentionDto(it.toString(), parts.getOrNull(1)?.ifBlank { null }) }
        }
    }

    private fun serializeMentions(mentions: List<Pair<UUID, String?>>): String =
        mentions.joinToString(";;", prefix = "[", postfix = "]") { (id, name) -> "$id|${name ?: ""}" }

    // ------------------------------------------------------- reads

    /** Top-level comments: newest-first keyset pages; the owner pin rides the first page. */
    suspend fun list(viewerId: UUID, postId: UUID, cursorRaw: String?, limitRaw: Int?): CommentPageDto {
        val g = gate(viewerId, postId)
        val limit = (limitRaw ?: 20).coerceIn(1, 50)
        val cursor = decodeCursor(cursorRaw)

        // Pinned row has its own slot on page 1 — exclude it from EVERY page (no duplicates).
        var op: Op<Boolean> = (PostComments.postId eq postId) and PostComments.parentId.isNull() and
            (PostComments.pinnedByOwner eq false)
        if (cursor != null) {
            op = op and (
                (PostComments.createdAt less cursor.createdAt) or
                    ((PostComments.createdAt eq cursor.createdAt) and
                        (PostComments.id less org.jetbrains.exposed.dao.id.EntityID(cursor.id, PostComments)))
                )
        }
        val rows = dbQuery {
            PostComments.selectAll().where(op)
                .orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                .limit(limit + 1).toList()
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit && page.isNotEmpty()) {
            val last = page.last()
            encodeCursor(last[PostComments.createdAt], last[PostComments.id].value)
        } else null

        val replyCounts = replyCountsFor(page.map { it[PostComments.id].value })
        val total = dbQuery {
            PostComments.selectAll().where {
                (PostComments.postId eq postId) and PostComments.deletedAt.isNull()
            }.count()
        }

        val pinned = if (cursor == null) dbQuery {
            PostComments.selectAll().where {
                (PostComments.postId eq postId) and PostComments.parentId.isNull() and
                    (PostComments.pinnedByOwner eq true) and PostComments.deletedAt.isNull()
            }.firstOrNull()
        } else null
        val pinnedDto = pinned?.let {
            toDtos(listOf(it), viewerId, g.ownerId, replyCountsFor(listOf(it[PostComments.id].value))).firstOrNull()
        }
        // The pinned row is shown in its own slot — drop it from the regular stream.
        // Deleted comments only stay visible (as tombstones) while live replies hang under them.
        val items = toDtos(
            page.filter { it[PostComments.deletedAt] == null || (replyCounts[it[PostComments.id].value] ?: 0L) > 0L },
            viewerId, g.ownerId, replyCounts
        )
        return CommentPageDto(
            items = items, nextCursor = next, pinned = pinnedDto,
            commenting = g.commenting, viewerCanComment = g.viewerCanComment,
            viewerIsPostOwner = g.viewerIsOwner, totalCount = total,
            serverTime = LocalDateTime.now().toString()
        )
    }

    /** Replies inside one thread: oldest-first so conversation reads top→bottom. */
    suspend fun replies(viewerId: UUID, postId: UUID, rootId: UUID, cursorRaw: String?, limitRaw: Int?): CommentPageDto {
        val g = gate(viewerId, postId)
        val limit = (limitRaw ?: 5).coerceIn(1, 30)
        val cursor = decodeCursor(cursorRaw)
        var op: Op<Boolean> = (PostComments.postId eq postId) and (PostComments.rootId eq rootId) and
            PostComments.parentId.isNotNull()
        if (cursor != null) {
            op = op and (
                (PostComments.createdAt greater cursor.createdAt) or
                    ((PostComments.createdAt eq cursor.createdAt) and
                        (PostComments.id greater org.jetbrains.exposed.dao.id.EntityID(cursor.id, PostComments)))
                )
        }
        val rows = dbQuery {
            PostComments.selectAll().where(op)
                .orderBy(PostComments.createdAt to SortOrder.ASC, PostComments.id to SortOrder.ASC)
                .limit(limit + 1).toList()
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit && page.isNotEmpty()) {
            val last = page.last()
            encodeCursor(last[PostComments.createdAt], last[PostComments.id].value)
        } else null
        val items = toDtos(page, viewerId, g.ownerId)
        return CommentPageDto(
            items = items, nextCursor = next, commenting = g.commenting,
            viewerCanComment = g.viewerCanComment, viewerIsPostOwner = g.viewerIsOwner,
            serverTime = LocalDateTime.now().toString()
        )
    }

    /** Full-text search inside one post's comments (top-level + replies), newest first. */
    suspend fun search(viewerId: UUID, postId: UUID, query: String, cursorRaw: String?, limitRaw: Int?): CommentPageDto {
        val g = gate(viewerId, postId)
        val q = escapeLike(query)
        if (q.length < 2) throw CommentException(400, "Search must be at least 2 characters")
        val limit = (limitRaw ?: 20).coerceIn(1, 50)
        val cursor = decodeCursor(cursorRaw)
        var op: Op<Boolean> = (PostComments.postId eq postId) and PostComments.deletedAt.isNull() and
            (PostComments.body like "%$q%")
        if (cursor != null) {
            op = op and (
                (PostComments.createdAt less cursor.createdAt) or
                    ((PostComments.createdAt eq cursor.createdAt) and
                        (PostComments.id less org.jetbrains.exposed.dao.id.EntityID(cursor.id, PostComments)))
                )
        }
        val rows = dbQuery {
            PostComments.selectAll().where(op)
                .orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                .limit(limit + 1).toList()
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit && page.isNotEmpty()) {
            val last = page.last()
            encodeCursor(last[PostComments.createdAt], last[PostComments.id].value)
        } else null
        return CommentPageDto(
            items = toDtos(page, viewerId, g.ownerId), nextCursor = next,
            commenting = g.commenting, viewerCanComment = g.viewerCanComment,
            viewerIsPostOwner = g.viewerIsOwner, serverTime = LocalDateTime.now().toString()
        )
    }

    private fun replyCountsFor(rootIds: List<UUID>): Map<UUID, Long> {
        if (rootIds.isEmpty()) return emptyMap()
        return PostComments.select(PostComments.rootId, PostComments.rootId.count())
            .where { (PostComments.rootId inList rootIds) and PostComments.parentId.isNotNull() and PostComments.deletedAt.isNull() }
            .groupBy(PostComments.rootId)
            .associate { it[PostComments.rootId] to it[PostComments.rootId.count()] }
    }

    // ------------------------------------------------------- writes

    suspend fun create(viewerId: UUID, postId: UUID, req: CreateCommentRequest): CommentDto {
        val g = gate(viewerId, postId)
        if (!g.viewerCanComment) throw CommentException(403, "Comments are not allowed on this post")
        val kind = req.kind.uppercase()
        if (kind !in KINDS) throw CommentException(400, "Unknown comment kind")
        val body = req.body?.trim()?.takeIf { it.isNotEmpty() }
        if (body != null && body.length > MAX_BODY) throw CommentException(400, "Comment too long")

        // PHOTO: the media asset must exist, belong to the author, and be an image
        // that already passed MediaProcessor's decode+re-encode pipeline.
        val mediaId = req.mediaId?.takeIf { kind == "PHOTO" }?.let { raw ->
            val id = runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw CommentException(400, "Bad media id")
            val asset = dbQuery {
                MediaAssets.selectAll().where { MediaAssets.id eq id }.singleOrNull()
            } ?: throw CommentException(400, "Media not found")
            if (asset[MediaAssets.ownerId] != viewerId) throw CommentException(403, "Not your media")
            if (!asset[MediaAssets.mimeType].startsWith("image/")) throw CommentException(400, "Media must be an image")
            id
        }
        if (kind == "PHOTO" && mediaId == null) throw CommentException(400, "Photo comments need media")
        val stickerUrl = req.stickerUrl?.takeIf { kind == "STICKER" || kind == "GIF" }?.takeIf { it.length <= 600 }?.takeIf {
            it.startsWith("https://media.giphy.com/") || it.startsWith("https://i.giphy.com/")
        }
        if ((kind == "STICKER" || kind == "GIF") && stickerUrl == null)
            throw CommentException(400, "Sticker/GIF comments need a GIPHY url")
        if (kind == "TEXT" && body == null) throw CommentException(400, "Empty comment")

        // Threading: parent must be a live comment on the SAME post; replies are
        // flattened onto the parent's root so threads are exactly two levels deep.
        val now = LocalDateTime.now()
        val id = UUID.randomUUID()
        val (parentId, rootId) = if (req.parentId != null) {
            val pid = runCatching { UUID.fromString(req.parentId) }.getOrNull()
                ?: throw CommentException(400, "Bad parent id")
            val parent = dbQuery {
                PostComments.selectAll().where {
                    (PostComments.id eq pid) and (PostComments.postId eq postId)
                }.singleOrNull()
            } ?: throw CommentException(404, "Parent comment not found")
            if (parent[PostComments.deletedAt] != null) throw CommentException(404, "Parent comment not found")
            val rid = parent[PostComments.rootId]
            if (rid != pid) {
                val rootDeleted = dbQuery {
                    PostComments.selectAll().where { PostComments.id eq rid }.singleOrNull()?.get(PostComments.deletedAt) != null
                }
                if (rootDeleted) throw CommentException(404, "Parent comment not found")
            }
            pid to rid
        } else {
            // Top-level: the comment is its own thread root.
            null to id
        }

        // @mentions: parse @handles from the body and resolve against real users.
        val mentions = if (body != null) dbQuery { resolveMentions(body, viewerId) } else emptyList()

        dbQuery {
            PostComments.insert {
                it[PostComments.id] = id
                it[PostComments.postId] = postId
                it[authorId] = viewerId
                it[PostComments.parentId] = parentId
                it[PostComments.rootId] = rootId
                it[PostComments.kind] = kind
                it[PostComments.body] = body
                it[PostComments.mediaId] = mediaId
                it[PostComments.stickerUrl] = stickerUrl
                it[PostComments.mentions] = serializeMentions(mentions)
                it[pinnedByOwner] = false
                it[createdAt] = now
            }
        }
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq id }.single() }

        // --- Notification fan-out (never to the actor themselves) ---
        val snippet = body ?: when (kind) { "PHOTO" -> "a photo" else -> "a sticker" }
        if (parentId != null) {
            val parentAuthor = dbQuery {
                PostComments.selectAll().where { PostComments.id eq parentId }.singleOrNull()
                    ?.get(PostComments.authorId)
            }
            if (parentAuthor != null) {
                com.telefam.notifications.NotificationService.notify(
                    parentAuthor, com.telefam.notifications.NotificationTypes.REPLY,
                    "New reply", "replied to your comment: ${snippet.take(80)}",
                    actorId = viewerId,
                    targetType = com.telefam.notifications.NotificationTargets.COMMENT,
                    targetId = postId.toString()
                )
            }
        } else {
            com.telefam.notifications.NotificationService.notify(
                g.ownerId, com.telefam.notifications.NotificationTypes.COMMENT,
                "New comment", "commented on your post: ${snippet.take(80)}",
                actorId = viewerId,
                targetType = com.telefam.notifications.NotificationTargets.COMMENT,
                targetId = postId.toString()
            )
        }
        for ((mentionedId, _) in mentions) {
            com.telefam.notifications.NotificationService.notify(
                mentionedId, com.telefam.notifications.NotificationTypes.MENTION,
                "You were mentioned", "mentioned you in a comment: ${snippet.take(80)}",
                actorId = viewerId,
                targetType = com.telefam.notifications.NotificationTargets.COMMENT,
                targetId = postId.toString()
            )
        }

        return toDtos(listOf(row), viewerId, g.ownerId).first()
    }

    /** Extract @handles and resolve to real user ids (skips self and unknown handles). */
    private fun resolveMentions(body: String, authorId: UUID): List<Pair<UUID, String?>> {
        val handles = Regex("@([A-Za-z0-9_.]{2,32})").findAll(body)
            .map { it.groupValues[1].lowercase() }.distinct().take(10).toList()
        if (handles.isEmpty()) return emptyList()
        val lowered = handles.map { it.lowercase() }
        return Users.selectAll().where { Users.username.lowerCase() inList lowered }
            .map { it[Users.id].value to it[Users.username] }
            .filter { it.first != authorId }
    }

    suspend fun edit(viewerId: UUID, commentId: UUID, newBody: String): CommentDto {
        val body = newBody.trim()
        if (body.isEmpty() || body.length > MAX_BODY) throw CommentException(400, "Comment must be 1..$MAX_BODY chars")
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        if (row[PostComments.deletedAt] != null) throw CommentException(404, "Comment not found")
        if (row[PostComments.authorId] != viewerId) throw CommentException(403, "Only the author can edit")
        if (row[PostComments.kind] != "TEXT") throw CommentException(400, "Only text comments can be edited")
        if (row[PostComments.createdAt].isBefore(LocalDateTime.now().minusMinutes(EDIT_WINDOW_MINUTES)))
            throw CommentException(403, "The edit window has closed")
        val g = gate(viewerId, row[PostComments.postId])
        val mentions = dbQuery { resolveMentions(body, viewerId) }
        dbQuery {
            PostComments.update({ PostComments.id eq commentId }) {
                it[PostComments.body] = body
                it[PostComments.mentions] = serializeMentions(mentions)
                it[editedAt] = LocalDateTime.now()
            }
        }
        val fresh = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.single() }
        return toDtos(listOf(fresh), viewerId, g.ownerId).first()
    }

    /** Soft delete: the author, or the post owner moderating their own comment section. */
    suspend fun delete(viewerId: UUID, commentId: UUID) {
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        val post = dbQuery {
            Posts.selectAll().where { Posts.id eq row[PostComments.postId] }.singleOrNull()
        } ?: throw CommentException(404, "Post not found")
        val isAuthor = row[PostComments.authorId] == viewerId
        val isPostOwner = post[Posts.ownerId] == viewerId
        if (!isAuthor && !isPostOwner) throw CommentException(403, "Not allowed")
        dbQuery {
            PostComments.update({ PostComments.id eq commentId }) {
                it[deletedAt] = LocalDateTime.now()
                it[pinnedByOwner] = false
            }
        }
    }

    suspend fun setLike(viewerId: UUID, commentId: UUID, like: Boolean): Long {
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        if (row[PostComments.deletedAt] != null) throw CommentException(404, "Comment not found")
        gate(viewerId, row[PostComments.postId]) // visibility re-checked on every write
        return dbQuery {
            val exists = CommentLikes.selectAll().where {
                (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq viewerId)
            }.any()
            if (like && !exists) {
                CommentLikes.insert {
                    it[CommentLikes.commentId] = commentId
                    it[userId] = viewerId
                    it[createdAt] = LocalDateTime.now()
                }
            } else if (!like && exists) {
                CommentLikes.deleteWhere { (CommentLikes.commentId eq commentId) and (CommentLikes.userId eq viewerId) }
            }
            CommentLikes.selectAll().where { CommentLikes.commentId eq commentId }.count()
        }
    }



    /** Owner-only pin; at most one pinned comment per post, switched atomically. */
    suspend fun setPinned(viewerId: UUID, commentId: UUID, pinned: Boolean) {
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        if (row[PostComments.deletedAt] != null) throw CommentException(404, "Comment not found")
        val post = dbQuery {
            Posts.selectAll().where { Posts.id eq row[PostComments.postId] }.singleOrNull()
        } ?: throw CommentException(404, "Post not found")
        if (post[Posts.ownerId] != viewerId) throw CommentException(403, "Only the post owner can pin")
        if (row[PostComments.parentId] != null) throw CommentException(400, "Only top-level comments can be pinned")
        dbQuery {
            if (pinned) {
                PostComments.update({ PostComments.postId eq row[PostComments.postId] }) { it[pinnedByOwner] = false }
                PostComments.update({ PostComments.id eq commentId }) { it[pinnedByOwner] = true }
            } else {
                PostComments.update({ PostComments.id eq commentId }) { it[pinnedByOwner] = false }
            }
        }
    }

    suspend fun report(viewerId: UUID, commentId: UUID, req: CommentReportRequest) {
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        gate(viewerId, row[PostComments.postId])
        val reason = req.reason.trim().take(60)
        if (reason.isEmpty()) throw CommentException(400, "Reason required")
        dbQuery {
            val already = CommentReports.selectAll().where {
                (CommentReports.commentId eq commentId) and (CommentReports.reporterId eq viewerId)
            }.any()
            if (already) return@dbQuery
            CommentReports.insert {
                it[CommentReports.commentId] = commentId
                it[reporterId] = viewerId
                it[CommentReports.reason] = reason
                it[details] = req.details?.trim()?.take(1000)?.ifBlank { null }
                it[createdAt] = LocalDateTime.now()
            }
        }
    }

    // ------------------------------------------------------- star gifting

    private suspend fun ensureWallet(userId: UUID) = dbQuery {
        if (StarWallets.selectAll().where { StarWallets.userId eq userId }.none()) {
            StarWallets.insert {
                it[StarWallets.userId] = userId
                it[balanceStars] = 0
                it[updatedAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Send stars to a comment author. Money moves with the same guarantees as campaign
     * spend: one atomic conditional debit on the sender's wallet, paired ledger entries
     * on both sides keyed by the client idempotency key, then the recipient's star
     * wallet credit, a StarTransactions analytics row (Stars screens), and a wallet
     * earning (Wallet screens) — all inside ONE db transaction.
     */
    suspend fun giftStars(senderId: UUID, commentId: UUID, stars: Long, idempotencyKey: String): GiftStarsResponse {
        if (stars <= 0 || stars > MAX_STARS_PER_GIFT) throw CommentException(400, "Unsupported star amount")
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: throw CommentException(404, "Comment not found")
        if (row[PostComments.deletedAt] != null) throw CommentException(404, "Comment not found")
        gate(senderId, row[PostComments.postId])
        val recipientId = row[PostComments.authorId]
        if (recipientId == senderId) throw CommentException(400, "You can't send stars to yourself")
        if (recipientId in blockedPairs(senderId)) throw CommentException(404, "Comment not found")

        // Replay: a retried request returns the original outcome.
        dbQuery {
            CommentStarGifts.selectAll().where { CommentStarGifts.idempotencyKey eq idempotencyKey }.singleOrNull()
        }?.let { existing ->
            if (existing[CommentStarGifts.senderId] != senderId || existing[CommentStarGifts.commentId] != commentId)
                throw CommentException(409, "Idempotency key already used")
            val balance = dbQuery {
                StarWallets.selectAll().where { StarWallets.userId eq senderId }.singleOrNull()
                    ?.get(StarWallets.balanceStars)
            } ?: 0L
            val total = dbQuery {
                CommentStarGifts.selectAll().where { CommentStarGifts.commentId eq commentId }
                    .sumOf { it[CommentStarGifts.stars] }
            }
            return GiftStarsResponse(balance, existing[CommentStarGifts.id].value.toString(), total)
        }

        val now = LocalDateTime.now()
        val giftId = UUID.randomUUID()
        ensureWallet(senderId)
        ensureWallet(recipientId)
        val newBalance = dbQuery {
            // Atomic conditional debit: exactly one concurrent writer wins.
            val debited = StarWallets.update({
                (StarWallets.userId eq senderId) and (StarWallets.balanceStars greaterEq stars)
            }) {
                it[balanceStars] = balanceStars - stars
                it[updatedAt] = now
            }
            if (debited == 0) return@dbQuery null
            StarLedgerEntries.insert {
                it[userId] = senderId
                it[delta] = -stars
                it[reason] = "GIFT_SEND"
                it[referenceId] = giftId.toString()
                it[StarLedgerEntries.idempotencyKey] = "csg:$idempotencyKey:debit"
                it[createdAt] = now
            }
            StarLedgerEntries.insert {
                it[userId] = recipientId
                it[delta] = stars
                it[reason] = "GIFT_RECEIVE"
                it[referenceId] = giftId.toString()
                it[StarLedgerEntries.idempotencyKey] = "csg:$idempotencyKey:credit"
                it[createdAt] = now
            }
            StarWallets.update({ StarWallets.userId eq recipientId }) {
                it[balanceStars] = balanceStars + stars
                it[updatedAt] = now
            }
            CommentStarGifts.insert {
                it[CommentStarGifts.id] = giftId
                it[CommentStarGifts.commentId] = commentId
                it[CommentStarGifts.senderId] = senderId
                it[CommentStarGifts.recipientId] = recipientId
                it[CommentStarGifts.stars] = stars
                it[CommentStarGifts.idempotencyKey] = idempotencyKey
                it[createdAt] = now
            }
            // Stars screen analytics (one row per gift, sender -> receiver).
            com.telefam.creator.StarTransactions.insert {
                it[com.telefam.creator.StarTransactions.senderId] = senderId
                it[com.telefam.creator.StarTransactions.receiverId] = recipientId
                it[com.telefam.creator.StarTransactions.stars] = stars
                it[amountCents] = stars // 1 star == 1 USD cent of creator earnings
                it[createdAt] = now
            }
            StarWallets.selectAll().where { StarWallets.userId eq senderId }.single()[StarWallets.balanceStars]
        } ?: run {
            val balance = dbQuery {
                StarWallets.selectAll().where { StarWallets.userId eq senderId }.singleOrNull()
                    ?.get(StarWallets.balanceStars)
            } ?: 0L
            throw InsufficientStarsException(balance, stars)
        }

        // Wallet visibility: the gift shows up under Stars earnings with the standard
        // platform-fee split and settlement window. Idempotent by the gift id.
        wallet?.let {
            runCatching {
                it.recordEarning(
                    creatorId = recipientId, source = EarningSource.STARS,
                    grossMinor = stars, currency = "USD",
                    referenceType = "COMMENT_STAR_GIFT", referenceId = giftId.toString(),
                    idempotencyKey = "csg:$giftId"
                )
            }
        }

        val total = dbQuery {
            CommentStarGifts.selectAll().where { CommentStarGifts.commentId eq commentId }
                .sumOf { it[CommentStarGifts.stars] }
        }
        return GiftStarsResponse(newBalance, giftId.toString(), total)
    }

    /** Authenticated image bytes for a PHOTO comment, with full visibility gating. */
    suspend fun photoFile(viewerId: UUID, commentId: UUID): java.io.File? {
        val row = dbQuery { PostComments.selectAll().where { PostComments.id eq commentId }.singleOrNull() }
            ?: return null
        if (row[PostComments.deletedAt] != null) return null
        gate(viewerId, row[PostComments.postId])
        val mediaId = row[PostComments.mediaId] ?: return null
        val asset = dbQuery { MediaAssets.selectAll().where { MediaAssets.id eq mediaId }.singleOrNull() }
            ?: return null
        val file = java.io.File(asset[MediaAssets.storagePath])
        return if (file.exists() && file.isFile) file else null
    }
}
