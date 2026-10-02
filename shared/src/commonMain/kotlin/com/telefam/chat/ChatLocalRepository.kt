package com.telefam.chat

import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase

data class CachedMessageItem(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val contentCategory: String,
    val textOrCaption: String?,
    val mediaLocalPath: String?,
    val mediaDurationSeconds: Long?,
    val viewOnce: Boolean,
    val viewOnceOpened: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val deliveryState: String,
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val replySender: String? = null,
    val editedAt: Long? = null,
    val pinned: Boolean = false,
    val starred: Boolean = false,
    val deletedForEveryone: Boolean = false,
    /** JSON object: emoji -> list of userIds who reacted. */
    val reactions: String? = null,
    val forwarded: Boolean = false,
    /** Set while media lives on the server as an encrypted blob (large-media channel). */
    val mediaRemoteId: String? = null,
    val mediaKey: String? = null
)

data class ConversationSummary(
    val peerId: String,
    val displayName: String,
    val lastCategory: String?,
    val lastText: String?,
    val lastAt: Long?,
    val lastOutgoing: Boolean,
    val lastViewOnce: Boolean,
    /** Non-blank when an unsent draft exists for this conversation. */
    val draftText: String? = null,
    /** Server-verified badge snapshot (display-only; the backend is authoritative). */
    val isVerified: Boolean = false,
    /** Unread incoming messages in this conversation (drives the list badge). */
    val unreadCount: Long = 0
)

private fun CachedMessage.toItem(): CachedMessageItem = CachedMessageItem(
    id = id, peerId = peerId, outgoing = outgoing == 1L, contentCategory = contentCategory,
    textOrCaption = textOrCaption, mediaLocalPath = mediaLocalPath, mediaDurationSeconds = mediaDurationSeconds,
    viewOnce = viewOnce == 1L, viewOnceOpened = viewOnceOpened == 1L,
    createdAt = createdAt, expiresAt = expiresAt, deliveryState = deliveryState,
    replyToId = replyToId, replyPreview = replyPreview, replySender = replySender,
    editedAt = editedAt, pinned = pinned == 1L, starred = starred == 1L,
    deletedForEveryone = deletedForEveryone == 1L, reactions = reactions,
    forwarded = forwarded == 1L, mediaRemoteId = mediaRemoteId, mediaKey = mediaKey
)

private fun SearchMessages.toItem(): CachedMessageItem = CachedMessageItem(
    id = id, peerId = peerId, outgoing = outgoing == 1L, contentCategory = contentCategory,
    textOrCaption = textOrCaption, mediaLocalPath = mediaLocalPath, mediaDurationSeconds = mediaDurationSeconds,
    viewOnce = viewOnce == 1L, viewOnceOpened = viewOnceOpened == 1L,
    createdAt = createdAt, expiresAt = expiresAt, deliveryState = deliveryState,
    replyToId = replyToId, replyPreview = replyPreview, replySender = replySender,
    editedAt = editedAt, pinned = pinned == 1L, starred = starred == 1L,
    deletedForEveryone = deletedForEveryone == 1L, reactions = reactions,
    forwarded = forwarded == 1L, mediaRemoteId = mediaRemoteId, mediaKey = mediaKey
)

private fun SelectSearchablePage.toItem(): CachedMessageItem = CachedMessageItem(
    id = id, peerId = peerId, outgoing = outgoing == 1L, contentCategory = contentCategory,
    textOrCaption = textOrCaption, mediaLocalPath = mediaLocalPath, mediaDurationSeconds = mediaDurationSeconds,
    viewOnce = viewOnce == 1L, viewOnceOpened = viewOnceOpened == 1L,
    createdAt = createdAt, expiresAt = expiresAt, deliveryState = deliveryState,
    replyToId = replyToId, replyPreview = replyPreview, replySender = replySender,
    editedAt = editedAt, pinned = pinned == 1L, starred = starred == 1L,
    deletedForEveryone = deletedForEveryone == 1L, reactions = reactions,
    forwarded = forwarded == 1L, mediaRemoteId = mediaRemoteId, mediaKey = mediaKey
)

class ChatLocalRepository(driverFactory: DatabaseDriverFactory) {
    private val queries = LocalDatabase.getInstance(driverFactory).chatLocalQueries

    fun insertMessage(item: CachedMessageItem) {
        queries.insertMessage(
            item.id, item.peerId, if (item.outgoing) 1L else 0L, item.contentCategory,
            item.textOrCaption, item.mediaLocalPath, item.mediaDurationSeconds,
            if (item.viewOnce) 1L else 0L, if (item.viewOnceOpened) 1L else 0L,
            item.createdAt, item.expiresAt, item.deliveryState,
            item.replyToId, item.replyPreview, item.replySender, item.editedAt,
            if (item.pinned) 1L else 0L, if (item.starred) 1L else 0L,
            if (item.deletedForEveryone) 1L else 0L, item.reactions,
            if (item.forwarded) 1L else 0L, item.mediaRemoteId, item.mediaKey
        )
    }

    fun updateDeliveryState(id: String, state: String) = queries.updateDeliveryState(state, id)
    fun markViewOnceOpened(id: String) = queries.markViewOnceOpened(id)

    /** Pagination: pass the createdAt of the oldest message currently shown to load the next older page. */
    fun loadPage(peerId: String, beforeEpochMillis: Long, pageSize: Long): List<CachedMessageItem> =
        queries.selectPage(peerId, beforeEpochMillis, pageSize).executeAsList().map { it.toItem() }

    /** Also removes the media files, so "clear chat" and expiry never leave decrypted media orphaned on disk. */
    fun clearChat(peerId: String) {
        loadPage(peerId, Long.MAX_VALUE, 100_000).forEach { it.mediaLocalPath?.let(::deleteLocalFile) }
        queries.clearChat(peerId)
    }

    fun deleteMessage(id: String) {
        queries.selectById(id).executeAsOneOrNull()?.mediaLocalPath?.let(::deleteLocalFile)
        queries.deleteMessage(id)
    }

    fun getById(id: String): CachedMessageItem? = queries.selectById(id).executeAsOneOrNull()?.toItem()

    /** True consumption of a view-once item: flag it opened AND wipe its payload + media file from this device. */
    fun consumeViewOnce(id: String) {
        queries.selectById(id).executeAsOneOrNull()?.mediaLocalPath?.let(::deleteLocalFile)
        queries.consumeViewOnce(id)
    }

    fun updatePayloadText(id: String, text: String) = queries.updatePayloadText(text, id)

    // --- Message metadata mutations ---
    fun setEdited(id: String, newText: String, editedAt: Long) = queries.setEdited(newText, editedAt, id)
    fun setPinned(id: String, pinned: Boolean) = queries.setPinned(if (pinned) 1L else 0L, id)
    fun setStarred(id: String, starred: Boolean) = queries.setStarred(if (starred) 1L else 0L, id)
    fun setReactions(id: String, reactionsJson: String?) = queries.setReactions(reactionsJson, id)
    fun updateMediaLocalPath(id: String, path: String) = queries.updateMediaLocalPath(path, id)

    /** Tombstone: keep the row (so both sides render "message deleted" in place) but wipe every trace of content. */
    fun markDeletedForEveryone(id: String) {
        queries.selectById(id).executeAsOneOrNull()?.mediaLocalPath?.let(::deleteLocalFile)
        queries.markDeletedForEveryone(id)
    }

    fun pendingOutgoing(): List<CachedMessageItem> = queries.selectPendingOutgoing().executeAsList().map { it.toItem() }

    // --- Search / collections ---

    /**
     * Searches the COMPLETE local history of [peerId] — not just the page currently shown in the chat.
     * Results are newest-first. Matching is case-insensitive (full Unicode), literal (no wildcard characters),
     * and looks inside structured messages too (file name, event title, poll question, contact name...).
     * Runs synchronously on the caller's thread: call it from a background dispatcher.
     */
    fun searchMessages(peerId: String, query: String, limit: Int = MAX_SEARCH_RESULTS): List<CachedMessageItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val hits = ArrayList<CachedMessageItem>()
        if (needle.all { it.code < 128 }) {
            // Fast path: SQLite LIKE is case-insensitive for ASCII, which is exactly what this query needs.
            val escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            queries.searchMessages(peerId, escaped, SQL_CANDIDATE_LIMIT).executeAsList().forEach { row ->
                val item = row.toItem()
                if (MessageSearch.matches(item, needle)) hits += item
                if (hits.size >= limit) return hits
            }
        } else {
            // Non-ASCII: SQLite cannot case-fold, so page through the whole history and compare in Kotlin.
            var offset = 0L
            while (hits.size < limit) {
                val page = queries.selectSearchablePage(peerId, SCAN_PAGE_SIZE, offset).executeAsList()
                if (page.isEmpty()) break
                for (row in page) {
                    val item = row.toItem()
                    if (MessageSearch.matches(item, needle)) hits += item
                    if (hits.size >= limit) break
                }
                offset += page.size
            }
        }
        return hits
    }

    /** Every message from [createdAt] (inclusive) up to the newest, newest-first — used to open the window on an old message. */
    fun messagesSince(peerId: String, createdAt: Long): List<CachedMessageItem> =
        queries.selectSince(peerId, createdAt).executeAsList().map { it.toItem() }

    /** True when there are stored messages older than [beforeEpochMillis] for this chat. */
    fun hasOlderThan(peerId: String, beforeEpochMillis: Long): Boolean =
        queries.selectPage(peerId, beforeEpochMillis, 1L).executeAsList().isNotEmpty()

    fun starredMessages(peerId: String): List<CachedMessageItem> =
        queries.selectStarred(peerId).executeAsList().map { it.toItem() }

    fun pinnedMessages(peerId: String): List<CachedMessageItem> =
        queries.selectPinned(peerId).executeAsList().map { it.toItem() }

    // --- Peers & conversation list ---
    fun upsertPeer(peerId: String, displayName: String, username: String?) =
        queries.upsertPeer(peerId, displayName, username, currentTimeMillis())

    /** Snapshot of the peer's server-side verification state, refreshed when profiles are fetched. */
    fun setPeerVerified(peerId: String, verified: Boolean) =
        queries.setPeerVerified(if (verified) 1 else 0, peerId)

    fun getPeer(peerId: String) = queries.selectPeer(peerId).executeAsOneOrNull()

    fun conversations(): List<ConversationSummary> {
        val drafts = queries.selectAllDrafts().executeAsList().associate { it.peerId to it.draftText }
        return queries.selectConversations().executeAsList().map {
            ConversationSummary(it.peerId, it.displayName, it.lastCategory, it.lastText, it.lastAt, it.lastOutgoing == 1L, it.lastViewOnce == 1L, drafts[it.peerId], it.isVerified == 1L, it.unreadCount)
        }
    }

    /** Whole-conversation delete: wipes messages + media + drafts + settings, then the peer row.
     *  Used by the conversation-list selection mode (local delete; archive goes server-side). */
    fun deleteConversation(peerId: String) {
        clearChat(peerId)
        queries.deleteDraft(peerId)
        queries.deleteChatSettings(peerId)
        queries.deletePeer(peerId)
    }

    // --- Drafts: saved automatically as the user types, restored on re-entry, never sent automatically ---
    fun getDraft(peerId: String): String? = queries.selectDraft(peerId).executeAsOneOrNull()?.draftText

    fun saveDraft(peerId: String, text: String) {
        if (text.isBlank()) queries.deleteDraft(peerId)
        else queries.upsertDraft(peerId, text, currentTimeMillis())
    }

    fun clearDraft(peerId: String) = queries.deleteDraft(peerId)

    /** Call periodically (e.g. on opening the chat, and on a timer while it's open) to wipe expired
     * disappearing messages from THIS device. The peer's device runs the same sweep independently
     * against its own copy, since both agreed the same TTL via an encrypted control envelope -
     * that's how "wiped for both parties" is achieved without the server ever storing plaintext. */
    fun sweepExpired(nowEpochMillis: Long): Int {
        val expired = queries.selectExpired(nowEpochMillis).executeAsList()
        expired.forEach { it.mediaLocalPath?.let(::deleteLocalFile); queries.deleteMessage(it.id) }
        return expired.size
    }

    fun mediaItems(peerId: String, limit: Long = 500): List<CachedMessageItem> =
        loadPage(peerId, Long.MAX_VALUE, limit).filter { it.contentCategory in setOf("IMAGE", "VIDEO", "GIF", "STICKER") && !it.deletedForEveryone }

    fun docItems(peerId: String, limit: Long = 500): List<CachedMessageItem> =
        loadPage(peerId, Long.MAX_VALUE, limit).filter { it.contentCategory == "FILE" && !it.deletedForEveryone }

    /** URLs found in cached text messages for the "Links" tab (same on-device detector the chat bubbles use). */
    fun linkItems(peerId: String, limit: Long = 500): List<Pair<CachedMessageItem, String>> =
        loadPage(peerId, Long.MAX_VALUE, limit).flatMap { msg ->
            if (msg.contentCategory != "TEXT" || msg.deletedForEveryone) emptyList()
            else msg.textOrCaption
                ?.let { MessageParser.parseCached(it).entities }
                ?.filter { it.type == EntityType.URL }
                ?.map { msg to it.raw }
                ?: emptyList()
        }

    private companion object {
        const val MAX_SEARCH_RESULTS = 1_000
        const val SQL_CANDIDATE_LIMIT = 5_000L
        const val SCAN_PAGE_SIZE = 500L
    }
}

/** Local per-chat settings: mute cache, agreed disappearing-messages TTL, cached peer privacy policy for offline enforcement. */
class ChatSettingsLocalRepository(driverFactory: DatabaseDriverFactory) {
    private val queries = LocalDatabase.getInstance(driverFactory).chatLocalQueries

    fun get(peerId: String) = queries.selectChatSettings(peerId).executeAsOneOrNull()

    fun upsert(
        peerId: String, mutedUntil: Long?, disappearingSeconds: Long,
        theme: String?, bubbleColour: String?,
        screenshotPolicy: String, sharePolicy: String, copyPolicy: String, downloadPolicy: String,
        nowEpochMillis: Long
    ) = queries.upsertChatSettings(
        peerId, mutedUntil, disappearingSeconds, theme, bubbleColour,
        screenshotPolicy, sharePolicy, copyPolicy, downloadPolicy, nowEpochMillis
    )
}

/** What text of a stored message is searchable, and whether a query matches it. */
object MessageSearch {
    /** Plain text a user would expect search to cover; null for messages with nothing searchable. */
    fun searchableText(item: CachedMessageItem): String? {
        val raw = item.textOrCaption ?: return null
        return when (item.contentCategory) {
            "FILE" -> FileMeta.decode(raw)?.name
            "EVENT" -> EventData.decode(raw)?.let { listOfNotNull(it.title, it.locationLabel, it.notes).joinToString(" ") }
            "POLL" -> PollData.decode(raw)?.let { (listOf(it.question) + it.options).joinToString(" ") }
            "CONTACT" -> ContactData.decode(raw)?.let { "${it.name} ${it.phoneNumber}" }
            "LOCATION" -> LocationData.decode(raw)?.label
            "CONTROL_OUT", "POLL_VOTE_OUT", "FLAG" -> null
            else -> raw // TEXT and media captions
        }
    }

    fun matches(item: CachedMessageItem, query: String): Boolean =
        searchableText(item)?.contains(query, ignoreCase = true) == true
}
