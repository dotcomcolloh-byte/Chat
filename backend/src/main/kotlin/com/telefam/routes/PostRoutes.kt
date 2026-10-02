package com.telefam.routes

import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.posts.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class InitUploadRequest(val filename: String, val mime: String, val sizeBytes: Long)

@Serializable
data class InitUploadResponse(val sessionId: String, val chunkSize: Long, val maxBytes: Long)

@Serializable
data class ChunkAck(val receivedBytes: Long, val complete: Boolean)

@Serializable
data class UploadStatusDto(val status: String, val receivedBytes: Long)

@Serializable
data class CompleteResponse(val postId: String, val status: String)

@Serializable
data class PipelineStatusDto(val status: String)

@Serializable
data class FinalizePostRequest(
    val postId: String,
    val description: String? = null,
    val hashtags: List<String> = emptyList(),
    val taggedUserIds: List<String> = emptyList(),
    val commenting: String = "EVERYONE",
    val privacy: String = "PUBLIC",
    val embedAllowed: Boolean = true,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songPreviewUrl: String? = null,
    val songArtworkUrl: String? = null,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val subscriberOnly: Boolean = false
)

fun Route.postRoutes(
    uploadService: ChunkedUploadService,
    signedUrls: SignedUrlService
) {
    rateLimit(RateLimitName("posts")) {
        authenticate("auth-jwt") {
            route("/api/posts") {

                // --- Chunked resumable upload ---
                post("/uploads/init") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<InitUploadRequest>()
                    val result = uploadService.init(userId, req.filename, req.mime, req.sizeBytes)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid upload"))
                    call.respond(
                        InitUploadResponse(result.sessionId.toString(), result.chunkSize.toLong(), result.maxBytes)
                    )
                }

                // Binary chunk body; offset in header so retries are idempotent.
                put("/uploads/{sessionId}/chunk") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val sessionId = call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val offset = call.request.header("X-Chunk-Offset")?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Missing X-Chunk-Offset"))
                    val bytes = call.receive<ByteArray>()
                    when (val r = uploadService.writeChunk(userId, sessionId, offset, bytes)) {
                        is ChunkedUploadService.ChunkResult.Accepted ->
                            call.respond(ChunkAck(r.receivedBytes, r.complete))
                        is ChunkedUploadService.ChunkResult.Conflict ->
                            // Resume signal: client re-syncs to server-authoritative offset.
                            call.respond(HttpStatusCode.Conflict, mapOf("expectedOffset" to r.expectedOffset.toString()))
                        is ChunkedUploadService.ChunkResult.Rejected ->
                            call.respond(HttpStatusCode.BadRequest, mapOf("message" to r.reason))
                    }
                }

                get("/uploads/{sessionId}/status") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val sessionId = call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val s = uploadService.status(userId, sessionId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(UploadStatusDto(s.first, s.second))
                }

                post("/uploads/{sessionId}/complete") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val sessionId = call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val postId = uploadService.complete(userId, sessionId)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Upload incomplete"))
                    call.respond(CompleteResponse(postId.toString(), "PROCESSING"))
                }

                // --- Post metadata (description, hashtags, tags, song, options) ---
                post("/finalize") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val req = call.receive<FinalizePostRequest>()
                    val postId = runCatching { UUID.fromString(req.postId) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)

                    val post = dbQuery {
                        Posts.selectAll().where { (Posts.id eq postId) and (Posts.ownerId eq userId) }.firstOrNull()
                    } ?: return@post call.respond(HttpStatusCode.NotFound)

                    val sessionStatus = dbQuery {
                        UploadSessions.selectAll()
                            .where { UploadSessions.id eq post[Posts.uploadSessionId]!! }.firstOrNull()
                            ?.get(UploadSessions.status)
                    }
                    // Only allow publishing once the worker finished (READY) — quarantine content never goes live.
                    if (sessionStatus != "READY") {
                        return@post call.respond(HttpStatusCode.Conflict, mapOf("message" to "Video still processing"))
                    }

                    val cleanHashtags = req.hashtags
                        .map { it.trim().removePrefix("#").replace(Regex("[^\\p{L}\\p{N}_]"), "") }
                        .filter { it.isNotBlank() }.distinct().take(30)
                    val cleanTags = req.taggedUserIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                        .distinct().take(20)
                    val commenting = req.commenting.uppercase().let { if (it in setOf("EVERYONE", "FRIENDS", "OFF")) it else "EVERYONE" }
                    val privacy = req.privacy.uppercase().let { if (it in setOf("PUBLIC", "FRIENDS", "PRIVATE")) it else "PUBLIC" }
                    if (req.subscriberOnly) {
                        val hasActivePlan = dbQuery {
                            com.telefam.subscriptions.SubscriptionPlans.selectAll().where {
                                (com.telefam.subscriptions.SubscriptionPlans.creatorId eq userId) and
                                    (com.telefam.subscriptions.SubscriptionPlans.isActive eq true)
                            }.any()
                        }
                        if (!hasActivePlan) return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("message" to "Create an active subscription plan before publishing subscriber-only content")
                        )
                    }

                    dbQuery {
                        Posts.update({
                            (Posts.id eq postId) and
                                (Posts.ownerId eq userId) and
                                (Posts.status eq "DRAFT")
                        }) {
                            it[description] = req.description?.take(2000)
                            it[hashtags] = cleanHashtags.joinToString(" ")
                            it[taggedUserIds] = cleanTags.joinToString(",", "[", "]")
                            it[Posts.commenting] = commenting
                            it[Posts.privacy] = privacy
                            it[Posts.subscriberOnly] = req.subscriberOnly
                            it[embedAllowed] = req.embedAllowed
                            it[songTitle] = req.songTitle?.take(200)
                            it[songArtist] = req.songArtist?.take(200)
                            it[songPreviewUrl] = req.songPreviewUrl?.take(500)
                            it[songArtworkUrl] = req.songArtworkUrl?.take(500)
                            it[trimStartMs] = req.trimStartMs.coerceAtLeast(0)
                            it[trimEndMs] = req.trimEndMs.coerceAtLeast(0)
                            it[status] = "PUBLISHED"
                            it[publishedAt] = LocalDateTime.now()
                        }
                    }
                    call.respond(mapOf("postId" to postId.toString(), "status" to "PUBLISHED"))

                    // Notify tagged users — tap redirects to the post.
                    for (tagged in cleanTags) {
                        com.telefam.notifications.NotificationService.notify(
                            userId = tagged,
                            type = com.telefam.notifications.NotificationTypes.TAG,
                            title = "You were tagged",
                            body = "tagged you in a post",
                            actorId = userId,
                            targetType = com.telefam.notifications.NotificationTargets.POST,
                            targetId = postId.toString()
                        )
                    }
                }

                /** Upload pipeline status for the client progress UI: UPLOADING handled client-side,
                 *  here we report QUARANTINED/PROCESSING/READY so it can show "Processing..." → "Uploaded". */
                get("/{postId}/pipeline-status") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val row = dbQuery {
                        Posts.selectAll().where { (Posts.id eq postId) and (Posts.ownerId eq userId) }.firstOrNull()
                    } ?: return@get call.respond(HttpStatusCode.NotFound)
                    val sessionStatus = dbQuery {
                        UploadSessions.selectAll()
                            .where { UploadSessions.id eq row[Posts.uploadSessionId]!! }.firstOrNull()
                            ?.get(UploadSessions.status)
                    } ?: "FAILED"
                    call.respond(mapOf("status" to sessionStatus))
                }

                // --- Signed playback manifest: which qualities exist + signed URLs for each ---
                get("/{postId}/manifest") {
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val post = dbQuery { Posts.selectAll().where { Posts.id eq postId }.firstOrNull() }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    // Authorization: PRIVATE posts are owner-only; FRIENDS enforced by social graph (hook).
                    if (post[Posts.privacy] == "PRIVATE") {
                        val uid = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                        if (post[Posts.ownerId] != uid) return@get call.respond(HttpStatusCode.Forbidden)
                    }
                    val viewerId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    if (post[Posts.subscriberOnly] && !dbQuery {
                            com.telefam.subscriptions.SubscriptionEntitlements.hasAccess(viewerId, post[Posts.ownerId])
                        }) return@get call.respond(HttpStatusCode.Forbidden)
                    if (post[Posts.status] != "PUBLISHED") return@get call.respond(HttpStatusCode.Conflict)
                    val media = dbQuery { PostMedia.selectAll().where { PostMedia.postId eq postId }.toList() }
                    call.respond(mapOf(
                        "durationMs" to post[Posts.durationMs].toString(),
                        "embedAllowed" to post[Posts.embedAllowed].toString(),
                        "variants" to media.map {
                            mapOf(
                                "kind" to it[PostMedia.kind],
                                "url" to signedUrls.sign(postId, it[PostMedia.kind],
                                    if (post[Posts.subscriberOnly]) viewerId else null),
                                "width" to (it[PostMedia.width]?.toString() ?: ""),
                                "height" to (it[PostMedia.height]?.toString() ?: "")
                            )
                        }
                    ))
                }

            }
        }

        // --- Signed delivery endpoint: the only way bytes leave public storage.
        // NOT behind JWT — the HMAC signature + expiry IS the access control, so
        // native video players (which can't attach auth headers) can stream directly.
        route("/api/posts/media") {
            get("/{postId}/{kind}") {
                val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val kind = call.parameters["kind"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val e = call.request.queryParameters["e"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val s = call.request.queryParameters["s"] ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val viewerId = call.request.queryParameters["u"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (!signedUrls.verify(postId, kind, e, s, viewerId)) return@get call.respond(HttpStatusCode.Unauthorized)
                val post = dbQuery { Posts.selectAll().where { Posts.id eq postId }.firstOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                if (post[Posts.subscriberOnly] && (viewerId == null || !dbQuery {
                        com.telefam.subscriptions.SubscriptionEntitlements.hasAccess(viewerId, post[Posts.ownerId])
                    })) return@get call.respond(HttpStatusCode.Forbidden)

                val row = dbQuery {
                    PostMedia.selectAll().where { (PostMedia.postId eq postId) and (PostMedia.kind eq kind) }.firstOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound)
                val file = File(row[PostMedia.publicPath])
                if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
                call.respondFile(file)
            }
        }
    }
}
