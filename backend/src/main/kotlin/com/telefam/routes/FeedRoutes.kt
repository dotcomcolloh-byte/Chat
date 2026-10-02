package com.telefam.routes

import com.telefam.config.AppConfig
import com.telefam.db.MediaAssets
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import com.telefam.posts.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import java.util.UUID

/**
 * Feed endpoints. Everything sits behind JWT auth and the "posts" rate limit bucket.
 * The client never decides visibility — FeedService re-derives it from the DB on
 * every request, so a tampered client cannot widen what it can see.
 */
fun Route.feedRoutes(feedService: FeedService) {
    rateLimit(RateLimitName("posts")) {
        authenticate("auth-jwt") {
            route("/api/feeds") {

                // GET /api/feeds/{for-you|friends|following|new-creators}?cursor=&limit=
                get("/{tab}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val tab = when (call.parameters["tab"]) {
                        "for-you" -> FeedService.Tab.FOR_YOU
                        "friends" -> FeedService.Tab.FRIENDS
                        "following" -> FeedService.Tab.FOLLOWING
                        "new-creators" -> FeedService.Tab.NEW_CREATORS
                        else -> return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Unknown feed tab"))
                    }
                    call.respond(feedService.feed(userId, tab, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull()))
                }

                get("/search") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val q = call.request.queryParameters["q"]?.trim().orEmpty()
                    if (q.length !in 2..100) {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Search must be 2 to 100 characters"))
                    }
                    call.respond(feedService.search(userId, q, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull()))
                }

                /** Profile screen reuse: a user's posts, visibility-filtered for the requester. */
                get("/user/{userId}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val ownerId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(feedService.userPosts(userId, ownerId, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull()))
                }

                /** Owner profile grid tabs: posts | reshared | locked | saved. */
                get("/user/{userId}/tab/{tab}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val ownerId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val tab = when (call.parameters["tab"]?.lowercase()) {
                        "posts" -> FeedService.ProfileTab.POSTS
                        "reshared" -> FeedService.ProfileTab.RESHARED
                        "locked" -> FeedService.ProfileTab.LOCKED
                        "saved" -> FeedService.ProfileTab.SAVED
                        else -> return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Unknown profile tab"))
                    }
                    call.respond(feedService.userTabPosts(userId, ownerId, tab, call.request.queryParameters["cursor"], call.request.queryParameters["limit"]?.toIntOrNull()))
                }

                post("/{postId}/view") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = call.receive<ViewRecordRequest>()
                    feedService.recordView(userId, postId, req.watchedMs, req.completed)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }

                post("/{postId}/like") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respond(CountResponse(feedService.setLike(userId, postId, true)))
                }
                delete("/{postId}/like") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respond(CountResponse(feedService.setLike(userId, postId, false)))
                }

                post("/{postId}/save") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respond(CountResponse(feedService.setSave(userId, postId, true)))
                }
                delete("/{postId}/save") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    call.respond(CountResponse(feedService.setSave(userId, postId, false)))
                }

                post("/{postId}/reshare") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<ReshareRequest>() }.getOrDefault(ReshareRequest())
                    call.respond(CountResponse(feedService.reshare(userId, postId, req.channel)))
                }

                post("/{postId}/not-interested") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    feedService.notInterested(userId, postId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }

                post("/{postId}/report") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = call.receive<PostReportRequest>()
                    runCatching { feedService.report(userId, postId, req.reason, req.details) }
                        .onFailure { return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid report")) }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "received"))
                }

                post("/{postId}/follow") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val target = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    feedService.setFollow(userId, target, true)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }
                delete("/{postId}/follow") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val target = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    feedService.setFollow(userId, target, false)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }

                /** Profile pictures, authenticated. Only images that passed MediaProcessor's
                 *  strict decode+re-encode are ever served. */
                get("/avatar/{userId}") {
                    val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val mediaId = dbQuery { Users.selectAll().where { Users.id eq target }.firstOrNull()?.get(Users.profileImageMediaId) }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val asset = dbQuery { MediaAssets.selectAll().where { MediaAssets.id eq mediaId }.firstOrNull() }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val file = File(asset[MediaAssets.storagePath])
                    if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                    call.respondFile(file)
                }
            }

            // --- Owner post management (edit / delete / restrict downloads) ---
            route("/api/posts") {
                get("/{postId}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val dto = feedService.ownedPost(userId, postId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(dto)
                }

                put("/{postId}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val req = call.receive<EditPostRequest>()
                    val ok = runCatching { feedService.editPost(userId, postId, req) }
                        .getOrElse { return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to (it.message ?: "Invalid post update"))) }
                    if (!ok) return@put call.respond(HttpStatusCode.NotFound)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
                }

                delete("/{postId}") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    if (!feedService.deletePost(userId, postId)) return@delete call.respond(HttpStatusCode.NotFound)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                }

                put("/{postId}/downloads") {
                    val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val postId = call.parameters["postId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val req = call.receive<DownloadsAllowedRequest>()
                    val ok = feedService.editPost(userId, postId, EditPostRequest(downloadsAllowed = req.allowed))
                    if (!ok) return@put call.respond(HttpStatusCode.NotFound)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "updated", "downloadsAllowed" to req.allowed.toString()))
                }
            }
        }
    }
}
