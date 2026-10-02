package com.telefam.app.calls

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the backend's high-priority **data** messages. For an incoming call we show the
 * full-screen ringing notification immediately — FCM starts this process even when the app
 * was swiped away, and the full-screen intent shows over the lockscreen. The message carries
 * call metadata only; accepting still goes through the normal authenticated signaling socket.
 */
class TelefamFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["kind"]) {
            "notification" -> {
                // Server-side activity event: render it, and let the tap deep-link
                // to the origin via MainActivity (action com.telefam.app.notifications.OPEN).
                if (!com.telefam.data.AuthSession.hasActiveSession()) return
                val title = data["title"] ?: "Telefam"
                val body = data["body"] ?: return
                ActivityNotificationHelper.show(
                    applicationContext,
                    notificationId = data["notificationId"] ?: System.currentTimeMillis().toString(),
                    title = title,
                    body = body,
                    targetType = data["targetType"],
                    targetId = data["targetId"]
                )
            }
            "incoming_call" -> {
                val callId = data["callId"] ?: return
                val callerId = data["callerId"] ?: return
                val callerName = data["callerName"] ?: "Telefam user"
                val callType = data["callType"] ?: "audio"
                IncomingCallNotification.show(applicationContext, callId, callerId, callerName, callType)
            }
            "chat_message" -> {
                // Content-free wake-up: pull + decrypt the mailbox locally, then render the
                // real notification with Reply / Mark-as-read. If the user is not signed in
                // on this device, nothing is shown (no stale notifications after logout).
                val senderId = data["senderId"] ?: return
                if (!com.telefam.data.AuthSession.hasActiveSession()) return
                val senderName = data["senderName"] ?: "Telefam user"
                scope.launch {
                    runCatching {
                        val previews = ChatPushBridge.pullAndSummarize(applicationContext)
                        val text = previews[senderId]
                        if (text != null) {
                            ChatNotificationHelper.show(applicationContext, senderId, senderName, text)
                        } else {
                            // Nothing decryptable for this sender (e.g. control-only traffic) — no noise.
                        }
                    }
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Upload once the user session is available; the API is idempotent.
        scope.launch {
            runCatching {
                com.telefam.app.PushTokenUploader.upload(applicationContext, token)
            }
        }
    }
}
