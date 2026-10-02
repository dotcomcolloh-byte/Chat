package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.RlsContext
import com.telefam.db.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.util.*

@Serializable data class DirectoryUser(val userId: String, val username: String?, val fullName: String?)

/** Escapes LIKE wildcards so user input can only ever be a literal prefix (no wildcard scanning of the whole table). */
private val USERNAME_RE = Regex("[a-z0-9_]{2,32}")

private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun Route.userDirectoryRoutes() {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("search")) { // dedicated search limiter; stricter than general API but not OTP-limited
            route("/api/users") {
                get("/search") {
                    val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
                    if (q.length < 3 || q.length > 30) {
                        return@get call.respond(HttpStatusCode.BadRequest, ApiError("Enter at least 3 characters"))
                    }
                    val pattern = escapeLike(q) + "%"
                    // Runs as the caller so RLS can let us see "who blocked me" (needed to hide them) without exposing anyone else's block list.
                    val results = RlsContext.asUser(me) {
                        Users.selectAll().where {
                            (Users.profileComplete eq true) and (Users.id neq me) and
                                (Users.username like pattern) and
                                (Users.id notInSubQuery BlockedUsers.select(BlockedUsers.blockerId).where { BlockedUsers.blockedId eq me })
                        }.limit(10).map { DirectoryUser(it[Users.id].value.toString(), it[Users.username], it[Users.fullName]) }
                    }
                    call.respond(HttpStatusCode.OK, results)
                }

                // Exact username lookup for tapped @mentions. Same visibility rules as search: incomplete profiles
                // and people who blocked the caller are "not found".
                get("/by-username/{username}") {
                    val me = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                    val name = call.parameters["username"]?.trim()?.lowercase().orEmpty()
                    if (!USERNAME_RE.matches(name)) {
                        return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                    }
                    val user = RlsContext.asUser(me) {
                        Users.selectAll().where {
                            (Users.profileComplete eq true) and (Users.username eq name) and
                                (Users.id notInSubQuery BlockedUsers.select(BlockedUsers.blockerId).where { BlockedUsers.blockedId eq me })
                        }.singleOrNull()
                    } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                    call.respond(HttpStatusCode.OK, DirectoryUser(user[Users.id].value.toString(), user[Users.username], user[Users.fullName]))
                }

                get("/{id}") {
                    val target = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                    val user = dbQuery { Users.selectAll().where { Users.id eq target }.singleOrNull() }
                        ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
                    call.respond(HttpStatusCode.OK, DirectoryUser(target.toString(), user[Users.username], user[Users.fullName]))
                }
            }
        }
    }
}
