package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.connect.ConnectService
import com.telefam.connect.ContactHashUploadRequest
import com.telefam.connect.FollowRateLimitException
import com.telefam.connect.LocationUpdateRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable private data class RateLimitedBody(val secondsRemaining: Long)

fun Route.connectRoutes(connectService: ConnectService) {
    authenticate("auth-jwt") {
        route("/api/connect") {

            // ---- follow graph ----
            // Dedicated "search"-tier IP limiter as a second layer under the DB-backed
            // per-account minute/hour budgets enforced inside ConnectService.
            rateLimit(RateLimitName("search")) {
                post("/follow/{userId}") {
                    val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                    try {
                        call.respond(HttpStatusCode.OK, connectService.setFollow(me, target, true))
                    } catch (e: FollowRateLimitException) {
                        call.respond(HttpStatusCode.TooManyRequests, RateLimitedBody(e.secondsRemaining))
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Unavailable"))
                    }
                }

                delete("/follow/{userId}") {
                    val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                    call.respond(HttpStatusCode.OK, connectService.setFollow(me, target, false))
                }
            }

            get("/follow-state/{userId}") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                call.respond(HttpStatusCode.OK, connectService.followState(me, target))
            }

            // ---- subscriptions ("Subscribers" on the profile) ----
            post("/subscribe/{userId}") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                try {
                    call.respond(HttpStatusCode.OK, connectService.setSubscribe(me, target, true))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Unavailable"))
                }
            }

            delete("/subscribe/{userId}") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                call.respond(HttpStatusCode.OK, connectService.setSubscribe(me, target, false))
            }

            // ---- discovery ----
            get("/suggestions") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                call.respond(HttpStatusCode.OK, connectService.suggestions(me, offset, limit))
            }

            get("/search") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val q = call.request.queryParameters["q"].orEmpty()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                call.respond(HttpStatusCode.OK, connectService.search(me, q, offset, limit))
            }

            get("/search/related") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(HttpStatusCode.OK, connectService.relatedSearch(me, q))
            }

            // ---- contact hashing ----
            post("/contacts/hashes") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<ContactHashUploadRequest>()
                call.respond(HttpStatusCode.OK, connectService.uploadContactHashes(me, req))
            }

            // ---- location ----
            post("/location") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<LocationUpdateRequest>()
                try {
                    connectService.updateLocation(me, req)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid location"))
                }
            }

            // ---- profile + connection lists ----
            get("/profile/{userId}") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                val profile = connectService.profile(me, target)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                call.respond(HttpStatusCode.OK, profile)
            }

            get("/profile/{userId}/list/{kind}") {
                val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val target = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                val kind = when (call.parameters["kind"]?.lowercase()) {
                    "followers" -> ConnectService.ListKind.FOLLOWERS
                    "following" -> ConnectService.ListKind.FOLLOWING
                    "friends" -> ConnectService.ListKind.FRIENDS
                    "subscribers" -> ConnectService.ListKind.SUBSCRIBERS
                    else -> return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid list kind"))
                }
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                call.respond(HttpStatusCode.OK, connectService.connectionList(me, target, kind, offset, limit))
            }
        }
    }
}
