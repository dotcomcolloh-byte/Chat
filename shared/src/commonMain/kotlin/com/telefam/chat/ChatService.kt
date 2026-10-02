package com.telefam.chat

import com.telefam.data.api.MediaBlobApi
import com.telefam.data.api.UserDirectoryApi
import com.telefam.e2ee.BlobCrypto
import com.telefam.e2ee.ContentCategory
import com.telefam.e2ee.MessageRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Everything inside the ciphertext. The server only ever relays the encrypted form of this. */
@Serializable
data class ChatPayload(
    val id: String,
    val category: String,
    val text: String? = null,
    val mediaB64: String? = null,
    val mediaExt: String? = null,
    val durationSeconds: Int? = null,
    val viewOnce: Boolean = false,
    val ttlSeconds: Long = 0,
    val createdAt: Long,
    /** Sender's current privacy policies [screenshot, share, copy, download] - cached by the receiver so restrictions hold offline. */
    val pol: List<String>? = null,
    // --- Reply / quote ---
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val replySender: String? = null,
    // --- Forwarding ---
    val forwarded: Boolean = false,
    // --- Large media via the dedicated encrypted-blob channel (mutually exclusive with mediaB64) ---
    val mediaId: String? = null,
    val mediaKey: String? = null
)

@Serializable
data class ControlMessage(
    val type: String,
    val seconds: Long? = null,
    val state: String? = null,
    val ids: List<String>? = null
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val STATE_RANK = mapOf("SENDING" to 0, "SENT" to 1, "DELIVERED" to 2, "READ" to 3)

/** Above this size, media goes through the encrypted blob channel instead of inline base64. */
private const val INLINE_MEDIA_LIMIT_BYTES = 256 * 1024

class ChatService(
    private val messageRepository: MessageRepository,
    private val local: ChatLocalRepository,
    private val settings: ChatSettingsLocalRepository,
    private val directory: UserDirectoryApi,
    private val blobApi: MediaBlobApi? = null,
    private val myUserId: () -> String?,
    private val myPolicies: () -> List<String>,
    private val isBlocked: (String) -> Boolean
) {
    private fun newId(): String = (1..24).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    // ---------- Settings helpers ----------
    fun disappearingSeconds(peerId: String): Long = settings.get(peerId)?.disappearingMessagesSeconds ?: 0L

    private fun saveSettings(peerId: String, disappearing: Long? = null, policies: List<String>? = null) {
        val e = settings.get(peerId)
        settings.upsert(
            peerId, e?.mutedUntil, disappearing ?: e?.disappearingMessagesSeconds ?: 0L, e?.cachedTheme, e?.cachedBubbleColour,
            policies?.getOrNull(0) ?: e?.cachedPeerScreenshotPolicy ?: "ANYONE",
            policies?.getOrNull(1) ?: e?.cachedPeerSharePolicy ?: "ANYONE",
            policies?.getOrNull(2) ?: e?.cachedPeerCopyPolicy ?: "ANYONE",
            policies?.getOrNull(3) ?: e?.cachedPeerDownloadPolicy ?: "ANYONE",
            currentTimeMillis()
        )
    }

    /** Called when I change the timer: apply locally, then tell the peer through an encrypted control message so both devices expire in sync. */
    suspend fun setDisappearing(peerId: String, seconds: Long) {
        saveSettings(peerId, disappearing = seconds)
        queueControl(peerId, ControlMessage("SET_DISAPPEARING", seconds = seconds))
    }

    // ---------- Sending ----------
    /**
     * Optimistic send: writes the local row FIRST in state SENDING (so it renders instantly with a
     * clock and survives offline/app-kill), then encrypts + delivers. The state machine on the row
     * drives the ticks: SENDING -> SENT (server accepted) -> DELIVERED -> READ (peer receipts).
     */
    suspend fun send(
        peerId: String, category: String, text: String? = null, mediaPath: String? = null,
        durationSeconds: Int? = null, viewOnce: Boolean = false, ttlOverrideSeconds: Long? = null,
        replyTo: CachedMessageItem? = null, forwarded: Boolean = false
    ): CachedMessageItem {
        val now = currentTimeMillis()
        val ttl = ttlOverrideSeconds ?: disappearingSeconds(peerId)
        val item = CachedMessageItem(
            id = newId(), peerId = peerId, outgoing = true, contentCategory = category, textOrCaption = text,
            mediaLocalPath = mediaPath, mediaDurationSeconds = durationSeconds?.toLong(), viewOnce = viewOnce, viewOnceOpened = false,
            createdAt = now, expiresAt = if (ttl > 0) now + ttl * 1000 else null, deliveryState = "SENDING",
            replyToId = replyTo?.id, replyPreview = replyTo?.let { replyPreviewOf(it) },
            replySender = replyTo?.let { if (it.outgoing) "You" else null }, // receiver resolves the peer name locally
            forwarded = forwarded
        )
        local.insertMessage(item)
        trySend(item, ttl)
        return item
    }

    private fun replyPreviewOf(m: CachedMessageItem): String {
        val base = when (m.contentCategory) {
            "TEXT" -> m.textOrCaption ?: ""
            "IMAGE" -> "Photo"
            "VIDEO", "CLIP" -> "Video"
            "VOICE", "AUDIO" -> "Voice message"
            "GIF" -> "GIF"
            "STICKER" -> "Sticker"
            "FILE" -> FileMeta.decode(m.textOrCaption ?: "")?.name ?: "File"
            "EVENT" -> EventData.decode(m.textOrCaption ?: "")?.title ?: "Event"
            "LOCATION" -> "Location"
            "CONTACT" -> ContactData.decode(m.textOrCaption ?: "")?.name ?: "Contact"
            "POLL" -> "Poll"
            else -> m.textOrCaption ?: ""
        }
        return if (base.length > 80) base.take(80) else base
    }

    /** Forwards a message to one or more peers as a brand-new encrypted send (re-encrypted per recipient). */
    suspend fun forward(message: CachedMessageItem, targetPeerIds: List<String>) {
        // Media must exist locally to forward; pull the blob first if it was never downloaded.
        var mediaPath = message.mediaLocalPath
        if (mediaPath == null && message.mediaRemoteId != null) {
            mediaPath = fetchRemoteMedia(message.id)?.mediaLocalPath
        }
        for (target in targetPeerIds.distinct()) {
            send(
                target, message.contentCategory, text = message.textOrCaption, mediaPath = mediaPath,
                durationSeconds = message.mediaDurationSeconds?.toInt(), forwarded = true
            )
        }
    }

    private suspend fun queueControl(peerId: String, control: ControlMessage) {
        val item = CachedMessageItem(
            newId(), peerId, true, "CONTROL_OUT", json.encodeToString(control), null, null, false, false,
            currentTimeMillis(), null, "SENDING"
        )
        local.insertMessage(item)
        trySend(item, 0)
    }

    private fun wireCategory(stored: String): ContentCategory = when (stored) {
        "CONTROL_OUT" -> ContentCategory.CONTROL
        "POLL_VOTE_OUT" -> ContentCategory.POLL_VOTE
        "FLAG_OUT" -> ContentCategory.FLAG
        else -> ContentCategory.valueOf(stored)
    }

    /** Encrypt + send. Any failure leaves the row in SENDING so it is retried later (never lost, never falsely "sent"). */
    private suspend fun trySend(item: CachedMessageItem, ttlSeconds: Long): Boolean {
        return try {
            var mediaB64: String? = null
            var mediaId: String? = null
            var mediaKey: String? = null
            if (item.mediaLocalPath != null) {
                val media = readLocalFile(item.mediaLocalPath) ?: error("media missing")
                if (media.size > INLINE_MEDIA_LIMIT_BYTES && blobApi != null && !item.contentCategory.endsWith("_OUT")) {
                    // Large-media channel: encrypt with a fresh per-file key, upload ciphertext,
                    // and carry only the key + blob id inside the Signal-encrypted payload.
                    val (keyNonce, ciphertext) = BlobCrypto.encrypt(media)
                    mediaId = blobApi.upload(ciphertext) ?: error("blob upload failed")
                    mediaKey = keyNonce
                } else {
                    mediaB64 = com.telefam.e2ee.base64Encode(media)
                }
            }
            val cat = wireCategory(item.contentCategory)
            val payload = ChatPayload(
                id = item.id, category = cat.name, text = item.textOrCaption,
                mediaB64 = mediaB64, mediaExt = item.mediaLocalPath?.substringAfterLast('.', "bin"),
                durationSeconds = item.mediaDurationSeconds?.toInt(), viewOnce = item.viewOnce,
                ttlSeconds = ttlSeconds, createdAt = item.createdAt, pol = myPolicies(),
                replyToId = item.replyToId, replyPreview = item.replyPreview, replySender = item.replySender,
                forwarded = item.forwarded, mediaId = mediaId, mediaKey = mediaKey
            )
            messageRepository.sendToUser(item.peerId, cat, item.viewOnce, json.encodeToString(payload).encodeToByteArray())

            when {
                item.contentCategory.endsWith("_OUT") -> local.deleteMessage(item.id) // control traffic leaves no trace
                else -> {
                    local.updateDeliveryState(item.id, "SENT")
                    // View-once I sent: my own copy is wiped as soon as it is delivered.
                    if (item.viewOnce && item.contentCategory != "TEXT") local.consumeViewOnce(item.id)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Retries everything still SENDING. Returns true if something is still pending (so the worker schedules another attempt). */
    suspend fun retryPending(): Boolean {
        for (item in local.pendingOutgoing()) {
            val ttl = if (item.expiresAt != null) ((item.expiresAt - item.createdAt) / 1000) else 0L
            trySend(item, ttl)
        }
        return local.pendingOutgoing().isNotEmpty()
    }

    suspend fun sendPollVote(peerId: String, message: CachedMessageItem, poll: PollData, selected: List<Int>) {
        val me = myUserId() ?: return
        local.updatePayloadText(message.id, poll.withVote(me, selected).encode())
        val vote = PollVoteUpdate(message.id, selected)
        val item = CachedMessageItem(
            newId(), peerId, true, "POLL_VOTE_OUT", Json.encodeToString(vote), null, null, false, false, currentTimeMillis(), null, "SENDING"
        )
        local.insertMessage(item)
        trySend(item, 0)
    }

    /** "Flagged in chats": a restricted action was attempted - record it locally and tell the protected party (encrypted). */
    suspend fun flagAttempt(peerId: String, action: String) {
        local.insertMessage(
            CachedMessageItem(newId(), peerId, false, "FLAG", "FLAG:$action:ME", null, null, false, false, currentTimeMillis(), null, "READ")
        )
        val item = CachedMessageItem(
            newId(), peerId, true, "FLAG_OUT", "FLAG:$action:PEER", null, null, false, false, currentTimeMillis(), null, "SENDING"
        )
        local.insertMessage(item)
        trySend(item, 0)
    }

    // ---------- Message actions (all synced through encrypted control envelopes) ----------

    /** Edit one of my text messages: applies locally and pushes the new text to the peer (encrypted). */
    suspend fun editMessage(message: CachedMessageItem, newText: String) {
        if (!message.outgoing || message.deletedForEveryone) return
        val now = currentTimeMillis()
        local.setEdited(message.id, newText, now)
        queueControl(message.peerId, ControlMessage("EDIT", state = newText, ids = listOf(message.id)))
    }

    /** Delete for everyone: tombstones the message locally AND sends an encrypted delete to the peer. */
    suspend fun deleteForEveryone(message: CachedMessageItem) {
        if (!message.outgoing) return
        local.markDeletedForEveryone(message.id)
        queueControl(message.peerId, ControlMessage("DELETE", ids = listOf(message.id)))
    }

    /** Delete for me only: wipes the local copy (and its media file), nothing is sent. */
    fun deleteForMe(message: CachedMessageItem) = local.deleteMessage(message.id)

    /** Toggle my reaction on a message; syncs to the peer so both sides render the same chips. */
    suspend fun toggleReaction(message: CachedMessageItem, emoji: String) {
        val me = myUserId() ?: return
        val had = Reactions.includesUser(message.reactions, emoji, me)
        local.setReactions(message.id, Reactions.toggle(message.reactions, emoji, me))
        queueControl(message.peerId, ControlMessage("REACTION", state = (if (had) "-" else "+") + emoji, ids = listOf(message.id)))
    }

    /** Pin/unpin in both participants' clients (encrypted control sync). */
    suspend fun setPinned(message: CachedMessageItem, pinned: Boolean) {
        local.setPinned(message.id, pinned)
        queueControl(message.peerId, ControlMessage("PIN", state = if (pinned) "1" else "0", ids = listOf(message.id)))
    }

    /** Star/save is intentionally local-only (like other messengers): your private collection. */
    fun toggleStarred(message: CachedMessageItem) = local.setStarred(message.id, !message.starred)

    fun searchMessages(peerId: String, query: String): List<CachedMessageItem> = local.searchMessages(peerId, query)
    fun starredMessages(peerId: String): List<CachedMessageItem> = local.starredMessages(peerId)
    fun pinnedMessages(peerId: String): List<CachedMessageItem> = local.pinnedMessages(peerId)

    /**
     * Downloads + decrypts a blob-media message whose bytes were never fetched (or whose file was
     * wiped). Returns the updated row, or null if the download/decrypt failed (UI shows a retry).
     */
    suspend fun fetchRemoteMedia(messageId: String): CachedMessageItem? {
        val msg = local.getById(messageId) ?: return null
        if (msg.mediaLocalPath != null) return msg
        val blobId = msg.mediaRemoteId ?: return null
        val key = msg.mediaKey ?: return null
        val api = blobApi ?: return null
        val ciphertext = api.download(blobId) ?: return null
        val plaintext = BlobCrypto.decrypt(key, ciphertext) ?: return null // tampered or wrong key: never render
        val path = "${AppFiles.baseDir}/media_${msg.id}.${msg.mediaLocalPath?.substringAfterLast('.', "") ?: blobExt(msg)}"
        if (!writeLocalFile(path, plaintext)) return null
        local.updateMediaLocalPath(msg.id, path)
        return local.getById(msg.id)
    }

    private fun blobExt(msg: CachedMessageItem): String = when (msg.contentCategory) {
        "IMAGE", "STICKER" -> "img"
        "GIF" -> "gif"
        "VIDEO" -> "mp4"
        "VOICE", "AUDIO" -> "m4a"
        else -> "bin"
    }

    /** Marks everything from this peer as read and tells them (drives the double-tick). */
    suspend fun markChatRead(peerId: String) {
        val unread = local.loadPage(peerId, Long.MAX_VALUE, 200).filter {
            !it.outgoing && it.deliveryState != "READ" && !it.contentCategory.startsWith("FLAG") && !it.deletedForEveryone
        }
        if (unread.isEmpty()) return
        unread.forEach { local.updateDeliveryState(it.id, "READ") }
        queueControl(peerId, ControlMessage("RECEIPT", state = "READ", ids = unread.map { it.id }))
    }

    // ---------- Receiving ----------
    /** Pulls, decrypts and stores everything in this device's mailbox. Returns the peers whose conversations changed. */
    suspend fun pollInbox(): Set<String> {
        val changed = mutableSetOf<String>()
        val inbox = try { messageRepository.pullAndDecryptInbox() } catch (e: Exception) { return changed }
        val delivered = mutableMapOf<String, MutableList<String>>()

        for (msg in inbox) {
            val sender = msg.senderId
            if (isBlocked(sender)) continue
            val payload = runCatching { json.decodeFromString<ChatPayload>(msg.plaintext.decodeToString()) }.getOrNull() ?: continue

            ensurePeer(sender)
            payload.pol?.let { saveSettings(sender, policies = it) }

            when (payload.category) {
                "CONTROL" -> handleControl(sender, payload, changed)
                "POLL_VOTE" -> {
                    val vote = runCatching { json.decodeFromString<PollVoteUpdate>(payload.text ?: "") }.getOrNull()
                    val msgRow = vote?.let { local.getById(it.pollMessageId) }
                    val poll = msgRow?.textOrCaption?.let { PollData.decode(it) }
                    if (vote != null && msgRow != null && poll != null) {
                        local.updatePayloadText(msgRow.id, poll.withVote(sender, vote.selectedOptionIndexes).encode()); changed += sender
                    }
                }
                "FLAG" -> {
                    local.insertMessage(CachedMessageItem(payload.id, sender, false, "FLAG", payload.text, null, null, false, false, currentTimeMillis(), null, "READ"))
                    changed += sender
                }
                else -> {
                    if (local.getById(payload.id) != null) continue // dedup: a retry of something we already stored
                    val now = currentTimeMillis()
                    var mediaPath: String? = null
                    if (payload.mediaB64 != null) {
                        val path = "${AppFiles.baseDir}/media_${payload.id}.${payload.mediaExt ?: "bin"}"
                        if (writeLocalFile(path, com.telefam.e2ee.base64Decode(payload.mediaB64))) mediaPath = path
                    } else if (payload.mediaId != null && payload.mediaKey != null && blobApi != null) {
                        // Large media: fetch the ciphertext blob now and decrypt it with the payload key.
                        val ciphertext = blobApi.download(payload.mediaId)
                        val plaintext = ciphertext?.let { BlobCrypto.decrypt(payload.mediaKey, it) }
                        if (plaintext != null) {
                            val path = "${AppFiles.baseDir}/media_${payload.id}.${payload.mediaExt ?: "bin"}"
                            if (writeLocalFile(path, plaintext)) mediaPath = path
                        }
                        // If the fetch failed, the row is stored with mediaRemoteId and no path —
                        // the UI renders a tap-to-retry placeholder that calls fetchRemoteMedia().
                    }
                    local.insertMessage(
                        CachedMessageItem(
                            payload.id, sender, false, payload.category, payload.text, mediaPath, payload.durationSeconds?.toLong(),
                            payload.viewOnce, false, payload.createdAt, if (payload.ttlSeconds > 0) now + payload.ttlSeconds * 1000 else null, "DELIVERED",
                            replyToId = payload.replyToId, replyPreview = payload.replyPreview,
                            replySender = payload.replySender, forwarded = payload.forwarded,
                            mediaRemoteId = payload.mediaId, mediaKey = payload.mediaKey
                        )
                    )
                    delivered.getOrPut(sender) { mutableListOf() }.add(payload.id)
                    changed += sender
                }
            }
        }
        for ((peer, ids) in delivered) queueControl(peer, ControlMessage("RECEIPT", state = "DELIVERED", ids = ids))
        return changed
    }

    private fun handleControl(sender: String, payload: ChatPayload, changed: MutableSet<String>) {
        val control = runCatching { json.decodeFromString<ControlMessage>(payload.text ?: "") }.getOrNull() ?: return
        when (control.type) {
            "SET_DISAPPEARING" -> { saveSettings(sender, disappearing = control.seconds ?: 0L); changed += sender }
            "RECEIPT" -> control.ids?.forEach { id ->
                val row = local.getById(id) ?: return@forEach
                val newRank = STATE_RANK[control.state] ?: return@forEach
                if (row.outgoing && newRank > (STATE_RANK[row.deliveryState] ?: 0)) { local.updateDeliveryState(id, control.state!!); changed += sender }
            }
            "DELETE" -> control.ids?.forEach { id ->
                if (local.getById(id) != null) { local.markDeletedForEveryone(id); changed += sender }
            }
            "EDIT" -> {
                val id = control.ids?.firstOrNull()
                val newText = control.state
                if (id != null && newText != null && local.getById(id)?.outgoing == false) {
                    local.setEdited(id, newText, payload.createdAt); changed += sender
                }
            }
            "REACTION" -> {
                val id = control.ids?.firstOrNull()
                val raw = control.state
                if (id != null && raw != null && raw.length > 1) {
                    val row = local.getById(id)
                    if (row != null) {
                        local.setReactions(id, Reactions.apply(row.reactions, raw.substring(1), sender, remove = raw.startsWith("-")))
                        changed += sender
                    }
                }
            }
            "PIN" -> {
                val id = control.ids?.firstOrNull()
                if (id != null && local.getById(id) != null) {
                    local.setPinned(id, control.state == "1"); changed += sender
                }
            }
        }
    }

    private suspend fun ensurePeer(peerId: String) {
        if (local.getPeer(peerId) != null) return
        val user = runCatching { directory.lookup(peerId) }.getOrNull()
        local.upsertPeer(peerId, user?.fullName ?: user?.username ?: "Unknown", user?.username)
    }

    fun sweepExpired(): Int = local.sweepExpired(currentTimeMillis())
}
