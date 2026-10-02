package com.telefam.calls

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/** Registered push devices. Tokens are delivery addresses only — no message content is ever pushed. */
object DeviceTokens : Table("device_tokens") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(com.telefam.db.Users.id, onDelete = ReferenceOption.CASCADE)
    val platform = varchar("platform", 10) // "android" | "ios"
    val token = varchar("token", 512)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, platform, token)
        index(false, userId)
    }
}

data class DeviceTokenRow(val userId: UUID, val platform: String, val token: String)

/**
 * Push delivery for calls. The ONLY thing ever pushed is call metadata
 * (callId / callType / caller identity) — enough for the OS to surface the native
 * incoming-call UI on a locked, backgrounded or killed app. Media and ringtone
 * never leave the two devices.
 *
 * Android: FCM high-priority **data** message -> the app's FirebaseMessagingService shows
 *   a full-screen-intent call notification (works from locked/backgrounded; the priority
 *   bump wakes the process even when the app was swiped away).
 * iOS: APNs **voip** push (PushKit token) -> CallKit `reportNewIncomingCall`, which is the
 *   only Apple-sanctioned way to show the incoming-call screen on a locked/killed app.
 */
interface CallPushNotifier {
    suspend fun notifyIncomingCall(calleeId: UUID, callId: String, callType: String, callerId: UUID, callerName: String)

    /**
     * Content-free "new message" wake-up: the push carries NO message text (messages are
     * end-to-end encrypted — the server never sees plaintext). It only nudges the device to
     * pull its mailbox; the app then decrypts locally and shows the real notification with
     * Reply / Mark-as-read actions.
     */
    suspend fun notifyNewMessage(recipientId: UUID, senderId: UUID, senderName: String) {}

    /**
     * Activity notification push (likes, comments, followers, payments, logins…).
     * Carries a deep-link payload (targetType/targetId/notificationId) so tapping
     * the push redirects straight to the origin inside the app.
     */
    suspend fun notifyNotification(
        recipientId: UUID, title: String, body: String,
        targetType: String?, targetId: String?, notificationId: String
    ) {}
}

/** No-op fallback so the server still runs when push credentials are not configured yet. */
object NoopCallPushNotifier : CallPushNotifier {
    override suspend fun notifyIncomingCall(calleeId: UUID, callId: String, callType: String, callerId: UUID, callerName: String) {}
}

/** FCM v1 HTTP API notifier. Enabled only when [AppConfig.fcmServiceAccountJson] is set. */
class FcmCallPushNotifier : CallPushNotifier {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http = HttpClient(CIO) { install(ContentNegotiation) { json(json) } }

    @Volatile private var credentials: com.google.auth.oauth2.GoogleCredentials? = null

    private fun accessToken(): String {
        val creds = credentials ?: synchronized(this) {
            credentials ?: com.google.auth.oauth2.GoogleCredentials
                .fromStream(AppConfig.fcmServiceAccountJson.byteInputStream())
                .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                .also { credentials = it }
        }
        if (creds.accessToken == null || creds.accessToken.expirationTime.time - System.currentTimeMillis() < 60_000) {
            creds.refresh()
        }
        return creds.accessToken.tokenValue
    }

    override suspend fun notifyIncomingCall(calleeId: UUID, callId: String, callType: String, callerId: UUID, callerName: String) {
        val devices = dbQuery {
            DeviceTokens.selectAll().where { DeviceTokens.userId eq calleeId }
                .map { DeviceTokenRow(calleeId, it[DeviceTokens.platform], it[DeviceTokens.token]) }
        }
        for (device in devices) {
            runCatching { send(device, callId, callType, callerId.toString(), callerName) }
        }
    }

    private suspend fun send(device: DeviceTokenRow, callId: String, callType: String, callerId: String, callerName: String) {
        val body = buildString {
            append("""{"message":{"token":""").append(device.token).append('"')
            // Data-only: the client renders the call UI itself, so behavior is identical
            // foreground/background and no server-chosen text/sound is involved.
            append(""","data":{"kind":"incoming_call","callId":""").append(callId)
                .append(""","callType":""").append(callType)
                .append(""","callerId":""").append(callerId)
                .append(""","callerName":""").append(callerName.replace("\"", "'")).append(""""}""")
            if (device.platform == "android") {
                // HIGH priority is what lets FCM start the app process when it was swiped away.
                append(""","android":{"priority":"HIGH","ttl":"45s"}""")
            } else {
                // VoIP pushes go over the APNs push-type "voip" to the .voip topic.
                append(""","apns":{"headers":{"apns-push-type":"voip","apns-priority":"10","apns-topic":"""")
                    .append(AppConfig.iosBundleId).append(""".voip"},"payload":{}}""")
            }
            append("}}")
        }
        val response = http.post("https://fcm.googleapis.com/v1/projects/${AppConfig.fcmProjectId}/messages:send") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.value == 404 || response.status.value == 410) {
            // Token is dead (app uninstalled / rotated) — prune it.
            dbQuery { DeviceTokens.deleteWhere { (DeviceTokens.userId eq device.userId) and (token eq device.token) } }
        }
    }

    override suspend fun notifyNewMessage(recipientId: UUID, senderId: UUID, senderName: String) {
        val devices = dbQuery {
            DeviceTokens.selectAll().where { DeviceTokens.userId eq recipientId }
                .map { DeviceTokenRow(recipientId, it[DeviceTokens.platform], it[DeviceTokens.token]) }
        }
        for (device in devices) {
            runCatching { sendChatWake(device, senderId.toString(), senderName) }
        }
    }

    /** Alert push with a deep-link payload; the app navigates to the origin on tap. */
    private suspend fun sendNotification(
        device: DeviceTokenRow, title: String, body: String,
        targetType: String?, targetId: String?, notificationId: String
    ) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "'")
        val body_ = buildString {
            append("""{"message":{"token":""").append(device.token).append('"')
            append(""","data":{"kind":"notification","title":""").append(esc(title))
                .append(""","body":""").append(esc(body))
                .append(""","notificationId":""").append(notificationId).append('"')
            if (targetType != null) append(""","targetType":""").append(targetType).append('"')
            if (targetId != null) append(""","targetId":""").append(targetId).append('"')
            append("}")
            if (device.platform == "android") {
                append(""","android":{"priority":"HIGH","ttl":"86400s"}""")
            } else {
                append(""","apns":{"headers":{"apns-push-type":"alert","apns-priority":"10","apns-topic":"""")
                    .append(AppConfig.iosBundleId)
                    .append(""""},"payload":{"aps":{"alert":{"title":""").append(esc(title))
                    .append(""","body":""").append(esc(body)).append(""""},"sound":"default"}}}""")
            }
            append("}}")
        }
        val response = http.post("https://fcm.googleapis.com/v1/projects/${AppConfig.fcmProjectId}/messages:send") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(body_)
        }
        if (response.status.value == 404 || response.status.value == 410) {
            dbQuery { DeviceTokens.deleteWhere { (DeviceTokens.userId eq device.userId) and (token eq device.token) } }
        }
    }

    override suspend fun notifyNotification(
        recipientId: UUID, title: String, body: String,
        targetType: String?, targetId: String?, notificationId: String
    ) {
        val devices = dbQuery {
            DeviceTokens.selectAll().where { DeviceTokens.userId eq recipientId }
                .map { DeviceTokenRow(recipientId, it[DeviceTokens.platform], it[DeviceTokens.token]) }
        }
        for (device in devices) {
            runCatching { sendNotification(device, title, body, targetType, targetId, notificationId) }
        }
    }

    /** Data-only wake-up; the app renders the actual notification after local decryption. */
    private suspend fun sendChatWake(device: DeviceTokenRow, senderId: String, senderName: String) {
        val body = buildString {
            append("""{"message":{"token":""").append(device.token).append('"')
            append(""","data":{"kind":"chat_message","senderId":""").append(senderId)
                .append(""","senderName":""").append(senderName.replace("\"", "'")).append(""""}""")
            if (device.platform == "android") {
                append(""","android":{"priority":"HIGH","ttl":"300s"}""")
            } else {
                // Regular APNs alert push (VoIP pushes are reserved for calls per Apple policy).
                append(""","apns":{"headers":{"apns-push-type":"alert","apns-priority":"10","apns-topic":"""")
                    .append(AppConfig.iosBundleId)
                    .append(""""},"payload":{"aps":{"alert":{"title":""").append(senderName.replace("\"", "'"))
                    .append("""","body":"New message"},"sound":"default","content-available":1}}}""")
            }
            append("}}")
        }
        val response = http.post("https://fcm.googleapis.com/v1/projects/${AppConfig.fcmProjectId}/messages:send") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.value == 404 || response.status.value == 410) {
            dbQuery { DeviceTokens.deleteWhere { (DeviceTokens.userId eq device.userId) and (token eq device.token) } }
        }
    }
}

@Serializable
data class RegisterDeviceRequest(val platform: String, val token: String)

fun pushNotifierFromConfig(): CallPushNotifier =
    if (AppConfig.fcmServiceAccountJson.isNotBlank() && AppConfig.fcmProjectId.isNotBlank()) {
        runCatching { FcmCallPushNotifier() }.getOrDefault(NoopCallPushNotifier)
    } else NoopCallPushNotifier
