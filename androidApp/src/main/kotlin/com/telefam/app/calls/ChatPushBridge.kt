package com.telefam.app.calls

import android.content.Context
import com.telefam.chat.ChatService
import com.telefam.chat.ChatSettingsLocalRepository
import com.telefam.chat.ChatLocalRepository
import com.telefam.data.api.E2EEApi
import com.telefam.data.api.MediaBlobApi
import com.telefam.data.api.UserDirectoryApi
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import com.telefam.e2ee.AndroidSignalEngine
import com.telefam.e2ee.MessageRepository
import com.telefam.e2ee.SignalIdentityKeyStorage
import com.telefam.data.api.HttpClientHolder

/**
 * Cold-start bridge used by the FCM service when a chat wake-up arrives while the main
 * activity stack does not exist yet. Rebuilds the minimal E2EE stack, pulls + decrypts the
 * mailbox, persists messages locally (single source of truth), and returns a per-sender
 * preview map so the notification shows the REAL decrypted text.
 *
 * All failures are swallowed (runCatching at call site): a failed wake-up only means the
 * message arrives on the next foreground poll — nothing is lost, since the server keeps
 * envelopes until explicitly acked.
 */
object ChatPushBridge {

    /** Pulls the mailbox, decrypts, stores, and returns senderId -> latest text preview. */
    suspend fun pullAndSummarize(context: Context): Map<String, String> {
        val client = HttpClientHolder.client
        val dbFactory = DatabaseDriverFactory(context.applicationContext)
        val engine = AndroidSignalEngine(LocalDatabase.getInstance(dbFactory), SignalIdentityKeyStorage(context.applicationContext))
        val repo = MessageRepository(engine, E2EEApi(client), localDeviceId = 1)
        val local = ChatLocalRepository(dbFactory)
        val settings = ChatSettingsLocalRepository(dbFactory)
        val service = ChatService(
            repo, local, settings, UserDirectoryApi(client),
            blobApi = MediaBlobApi(client),
            myUserId = { com.telefam.data.AuthSession.currentUserId },
            myPolicies = { listOf("ANYONE", "ANYONE", "ANYONE", "ANYONE") },
            isBlocked = { false } // server already filters blocked senders authoritatively
        )
        val changed = service.pollInbox()
        // Latest incoming text per changed sender, for the notification body.
        return changed.mapNotNull { peerId ->
            val latest = local.loadPage(peerId, Long.MAX_VALUE, 20)
                .firstOrNull { !it.outgoing && !it.deletedForEveryone }
                ?: return@mapNotNull null
            val preview = when (latest.contentCategory) {
                "TEXT" -> latest.textOrCaption ?: "New message"
                "IMAGE" -> "Photo"
                "VIDEO", "CLIP" -> "Video"
                "VOICE", "AUDIO" -> "Voice message"
                "POLL" -> "Poll"
                "LOCATION" -> "Location"
                "CONTACT" -> "Contact"
                "FILE" -> "File"
                "STICKER" -> "Sticker"
                "GIF" -> "GIF"
                else -> latest.textOrCaption ?: "New message"
            }
            peerId to preview
        }.toMap()
    }
}
