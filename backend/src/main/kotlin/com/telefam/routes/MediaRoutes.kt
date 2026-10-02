package com.telefam.routes

import com.telefam.config.AppConfig
import com.telefam.media.MediaProcessResult
import com.telefam.media.MediaProcessor
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.*

fun Route.mediaRoutes(mediaProcessor: MediaProcessor) {
    rateLimit(RateLimitName("media")) {
        authenticate("auth-jwt") {
            route("/api/media") {
                post("/upload") {
                    val principal = call.principal<UserIdPrincipal>()!!
                    val userId = UUID.fromString(principal.name)

                    var bytes: ByteArray? = null
                    var mime: String? = null

                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem && bytes == null) {
                            mime = part.contentType?.toString()
                            bytes = part.streamProvider().readBytes()
                        }
                        part.dispose()
                    }

                    if (bytes == null || mime == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "No file provided"))
                    }

                    when (val result = mediaProcessor.processAndStore(userId, bytes!!, mime!!)) {
                        is MediaProcessResult.Success -> call.respond(
                            HttpStatusCode.OK,
                            mapOf("mediaId" to result.mediaId.toString(), "reused" to result.reused)
                        )
                        is MediaProcessResult.Rejected -> call.respond(
                            HttpStatusCode.BadRequest, mapOf("message" to result.reason)
                        )
                    }
                }

                // --- Encrypted blob channel (large-media transfer) ---
                // These payloads are client-side AEAD ciphertext produced before upload; the server
                // cannot decode them, so strict image re-encoding (which would destroy ciphertext)
                // does not apply. Security here comes from: auth required on both directions,
                // a hard size cap, a random unguessable id (capability URL), and storage as an
                // opaque file outside any publicly served path. No content sniffing, no execution.
                route("/blob") {
                    post {
                        call.principal<UserIdPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                        val bytes = call.receive<ByteArray>()
                        if (bytes.isEmpty()) {
                            return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Empty blob"))
                        }
                        if (bytes.size > AppConfig.mediaBlobMaxBytes) {
                            return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("message" to "Blob too large"))
                        }
                        val id = UUID.randomUUID()
                        val dir = File(AppConfig.mediaStoragePath, "blobs").apply { mkdirs() }
                        // Write-then-move so a concurrent reader can never observe a partial file.
                        val tmp = File(dir, "$id.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(File(dir, "$id.blob"))) {
                            tmp.delete()
                            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "Store failed"))
                        }
                        call.respond(HttpStatusCode.OK, mapOf("mediaId" to id.toString()))
                    }

                    get("/{id}") {
                        call.principal<UserIdPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Bad id"))
                        val file = File(File(AppConfig.mediaStoragePath, "blobs"), "$id.blob")
                        if (!file.exists() || !file.isFile) {
                            return@get call.respond(HttpStatusCode.NotFound, mapOf("message" to "Not found"))
                        }
                        call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                    }
                }
            }
        }
    }
}
