package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.creator.CreatorService
import com.telefam.creator.StarGoalDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/**
 * Creator-program endpoints. All of them are strictly owner-scoped: the user id
 * comes from the JWT principal (never from the request), and the new tables are
 * RLS-enforced at the database level as well.
 */
fun Route.creatorRoutes(creatorService: CreatorService) {
    authenticate("auth-jwt") {
        route("/api/creator") {

            get("/dashboard") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val period = call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 7
                call.respond(HttpStatusCode.OK, creatorService.dashboard(userId, period))
            }

            get("/analytics") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val period = call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 7
                call.respond(HttpStatusCode.OK, creatorService.analytics(userId, period))
            }

            get("/content") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, creatorService.contentPerformance(userId))
            }

            get("/monetization/eligibility") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, creatorService.eligibility(userId))
            }

            get("/monetization/status") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, creatorService.applicationStatus(userId))
            }

            post("/monetization/apply") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val result = creatorService.apply(userId)
                if (result.status == "UNDER_REVIEW") call.respond(HttpStatusCode.OK, result)
                else call.respond(HttpStatusCode.Conflict, ApiError("Requirements not met yet"))
            }

            get("/stars/overview") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val period = call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 7
                call.respond(HttpStatusCode.OK, creatorService.starsOverview(userId, period))
            }

            get("/stars/transactions") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                call.respond(HttpStatusCode.OK, creatorService.starTransactions(userId, limit))
            }

            get("/stars/supporters") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                call.respond(HttpStatusCode.OK, creatorService.starSupporters(userId))
            }

            put("/stars/goal") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val body = call.receive<StarGoalDto>()
                if (body.targetStars <= 0) {
                    return@put call.respond(HttpStatusCode.BadRequest, ApiError("Target must be positive"))
                }
                creatorService.setStarGoal(userId, body.title, body.targetStars)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
    }
}
