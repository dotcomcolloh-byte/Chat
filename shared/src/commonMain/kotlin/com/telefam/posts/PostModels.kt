package com.telefam.posts

import kotlinx.serialization.Serializable

enum class CommentingOption(val label: String) { EVERYONE("Everyone"), FRIENDS("People I follow"), OFF("No one") }

enum class PostPrivacy(val label: String) { PUBLIC("Public"), FRIENDS("Friends"), PRIVATE("Only me") }

enum class EmbedOption(val label: String, val allowed: Boolean) {
    ALLOW("Allow embedding", true), DISALLOW("Don't allow embedding", false)
}

/** Client-side pipeline state shown on the Create Post screen. */
enum class UploadStage { IDLE, COMPRESSING, UPLOADING, PROCESSING, UPLOADED, FAILED }

@Serializable
data class InitUploadRequest(val filename: String, val mime: String, val sizeBytes: Long)

@Serializable
data class InitUploadResponse(val sessionId: String, val chunkSize: Long, val maxBytes: Long)

@Serializable
data class ChunkAck(val receivedBytes: Long = 0, val complete: Boolean = false)

@Serializable
data class UploadStatusDto(val status: String, val receivedBytes: Long = 0)

@Serializable
data class CompleteResponse(val postId: String, val status: String)

@Serializable
data class PipelineStatusDto(val status: String)

@Serializable
data class FinalizePostRequest(
    val postId: String,
    val description: String? = null,
    val hashtags: List<String> = emptyList(),
    val taggedUserIds: List<String> = emptyList(),
    val commenting: String = "EVERYONE",
    val privacy: String = "PUBLIC",
    val embedAllowed: Boolean = true,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songPreviewUrl: String? = null,
    val songArtworkUrl: String? = null,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val subscriberOnly: Boolean = false
)

/** A friend that can be tagged on the post (from the user directory). */
data class TagFriend(val userId: String, val displayName: String, val username: String?)

/** A song from the public iTunes Search API. */
@Serializable
data class SongResult(
    val trackId: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val artworkUrl100: String = "",
    val previewUrl: String = ""
)

@Serializable
private data class ItunesSearchResponse(val resultCount: Int = 0, val results: List<SongResult> = emptyList())
