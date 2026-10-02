package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.campaigns.InsufficientStarsException
import com.telefam.comments.CommentException
import com.telefam.comments.CommentReportRequest
import com.telefam.comments.CommentService
import com.telefam.comments.CreateCommentRequest
import com.telefam.comments.EditCommentRequest
import com.telefam.comments.GiftStarsRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Comment endpoints. Like feedRoutes, everything sits behind JWT auth and the "posts"
 * rate limit bucket; every visibility/privacy decision is re-derived server-side in
 * CommentService on each call — the client is presentation only.
 */
fun Route.commentRoutes(commentService: CommentService) {
    rateLimit(RateLimitName("posts")) {
        authenticate("auth-jwt") {

            suspend fun ApplicationCall.respondGuarded(block: suspend () -> Any?) {
                try {
                    val result = block()
                    if (result == null || result is Unit) respond(HttpStatusCode.OK, ApiError("ok"))
                    else respond(result)
                } catch (e: InsufficientStarsException) {
                    respond(
                        HttpStatusCode.PaymentRequired,
                        mapOf(
                            "code" to "INSUFFICIENT_STARS",
                            "message" to "Not enough stars",
                            "balanceStars" to e.balanceStars.toString(),
                            "requiredStars" to e.requiredStars.toString()
                        )
                    )
                } catch (e: CommentException) {
                    respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.message ?: "Error"))
                } catch (e: Exception) {
                    application.environment.log.error("Comment request failed", e)
                    respond(HttpStatusCode.InternalServerError, ApiError("Something went wrong"))
                }
            }

            fun ApplicationCall.uuidParam(name: String): UUID? =
                parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

            fun ApplicationCall.userId(): UUID = UUID.fromString(principal<UserIdPrincipal>()!!.name)

            route("/api/feeds/{postId}/comments") {
                // GET ?cursor=&limit= — first page also carries the pinned comment.
                get {
                    val postId = call.uuidParam("postId") ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded {
                        commentService.list(
                            call.userId(), postId,
                            call.request.queryParameters["cursor"],
                            call.request.queryParameters["limit"]?.toIntOrNull()
                        )
                    }
                }

                post {
                    val postId = call.uuidParam("postId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<CreateCommentRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid body"))
                    call.respondGuarded { commentService.create(call.userId(), postId, req) }
                }

                get("/search") {
                    val postId = call.uuidParam("postId") ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val q = call.request.queryParameters["q"]?.trim().orEmpty()
                    call.respondGuarded {
                        commentService.search(
                            call.userId(), postId, q,
                            call.request.queryParameters["cursor"],
                            call.request.queryParameters["limit"]?.toIntOrNull()
                        )
                    }
                }

                /** Lazy-loaded replies under one thread root: ?cursor=&limit=. */
                get("/{rootId}/replies") {
                    val postId = call.uuidParam("postId") ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val rootId = call.uuidParam("rootId") ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded {
                        commentService.replies(
                            call.userId(), postId, rootId,
                            call.request.queryParameters["cursor"],
                            call.request.queryParameters["limit"]?.toIntOrNull()
                        )
                    }
                }
            }

            route("/api/comments") {
                /** Authenticated delivery of PHOTO comment media (visibility-checked). */
                get("/media/{commentId}") {
                    val commentId = call.uuidParam("commentId") ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val file = commentService.photoFile(call.userId(), commentId)
                        if (file == null) call.respond(HttpStatusCode.NotFound)
                        else {
                            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                            call.respondFile(file)
                        }
                    } catch (e: CommentException) {
                        call.respond(HttpStatusCode.fromValue(e.statusCode), ApiError(e.message ?: "Error"))
                    }
                }

                put("/{commentId}") {
                    val commentId = call.uuidParam("commentId") ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<EditCommentRequest>() }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError("invalid body"))
                    call.respondGuarded { commentService.edit(call.userId(), commentId, req.body) }
                }

                delete("/{commentId}") {
                    val commentId = call.uuidParam("commentId") ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded { commentService.delete(call.userId(), commentId) }
                }

                post("/{commentId}/like") {
                    val commentId = call.uuidParam("commentId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded { mapOf("likeCount" to commentService.setLike(call.userId(), commentId, true)) }
                }

                delete("/{commentId}/like") {
                    val commentId = call.uuidParam("commentId") ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded { mapOf("likeCount" to commentService.setLike(call.userId(), commentId, false)) }
                }

                post("/{commentId}/pin") {
                    val commentId = call.uuidParam("commentId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded { commentService.setPinned(call.userId(), commentId, true) }
                }

                delete("/{commentId}/pin") {
                    val commentId = call.uuidParam("commentId") ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respondGuarded { commentService.setPinned(call.userId(), commentId, false) }
                }

                post("/{commentId}/report") {
                    val commentId = call.uuidParam("commentId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<CommentReportRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid body"))
                    call.respondGuarded { commentService.report(call.userId(), commentId, req) }
                }

                /** Star gift to the comment author. Header: Idempotency-Key (required). */
                post("/{commentId}/stars") {
                    val commentId = call.uuidParam("commentId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<GiftStarsRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid body"))
                    val idem = call.request.header("Idempotency-Key")?.trim()?.take(128)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Idempotency-Key header required"))
                    call.respondGuarded { commentService.giftStars(call.userId(), commentId, req.stars, idem) }
                }
            }
        }
    }
}
