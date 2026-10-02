package com.telefam.routes

import com.telefam.auth.ApiError
import com.telefam.e2ee.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

@Serializable data class ReplenishRequest(val deviceId: Int, val preKeys: List<PreKeyEntry>)
@Serializable data class RotateSignedPreKeyRequest(val deviceId: Int, val keyId: Int, val publicKey: String, val signature: String)

fun Route.e2eeRoutes(service: E2EEService) {
    authenticate("auth-jwt") {
        route("/api/e2ee") {

            post("/keys/register") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<RegisterDeviceRequest>()
                service.registerDevice(userId, req)
                call.respond(HttpStatusCode.OK, mapOf("status" to "registered"))
            }

            post("/keys/replenish") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<ReplenishRequest>()
                service.replenishOneTimePreKeys(userId, req.deviceId, req.preKeys)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            get("/keys/count/{deviceId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val deviceId = call.parameters["deviceId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid device id"))
                call.respond(HttpStatusCode.OK, mapOf("remaining" to service.remainingOneTimePreKeyCount(userId, deviceId)))
            }

            post("/keys/rotate-signed") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<RotateSignedPreKeyRequest>()
                service.rotateSignedPreKey(userId, req.deviceId, req.keyId, req.publicKey, req.signature)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            get("/keys/devices/{userId}") {
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                call.respond(HttpStatusCode.OK, service.listDevices(targetId))
            }

            get("/keys/bundle/{userId}/{deviceId}") {
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid user id"))
                val deviceId = call.parameters["deviceId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid device id"))
                val bundle = service.fetchPreKeyBundle(targetId, deviceId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No such device"))
                call.respond(HttpStatusCode.OK, bundle)
            }

            post("/messages") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val req = call.receive<SendEnvelopeRequest>()
                val id = service.sendEnvelope(userId, req)
                call.respond(HttpStatusCode.Created, mapOf("envelopeId" to id.toString()))
            }

            get("/messages/{deviceId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val deviceId = call.parameters["deviceId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid device id"))
                call.respond(HttpStatusCode.OK, service.fetchPendingEnvelopes(userId, deviceId))
            }

            delete("/messages/{envelopeId}") {
                val userId = UUID.fromString(call.principal<UserIdPrincipal>()!!.name)
                val envelopeId = call.parameters["envelopeId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Invalid envelope id"))
                service.ackEnvelope(userId, envelopeId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ack"))
            }
        }
    }
}
