package com.telefam.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Serves the app's legal documents. Unauthenticated by design: Terms and the Privacy
 * Policy must be reachable before an account exists (e.g. from the sign-up screen).
 * Content ships with the binary, so it works offline on the server side and is always
 * the current version — the client handles its own loading/error states.
 */
fun Route.legalRoutes() {
    route("/api/legal") {
        get("/{doc}") {
            val file = when (call.parameters["doc"]?.lowercase()) {
                "terms" -> "terms.md"
                "privacy" -> "privacy.md"
                "guidelines" -> "guidelines.md"
                "data" -> "data.md"
                "licenses" -> "licenses.md"
                else -> null
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("message" to "Unknown document"))
            val text = object {}.javaClass.getResourceAsStream("/legal/$file")?.bufferedReader()?.readText()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("message" to "Document unavailable"))
            call.respondText(text, ContentType.Text.Plain)
        }
    }
}
