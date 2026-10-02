package com.telefam.posts

import com.telefam.chat.PickedFile
import com.telefam.data.api.PostsApi
import com.telefam.data.api.UserDirectoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CreatePostState(
    val video: PickedFile? = null,
    val sendPath: String? = null,           // compressed copy actually uploaded
    val thumbnail: PickedFile? = null,
    val frameStrip: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(),
    val durationMs: Long = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,                // 0 = end of video (untouched)
    val description: String = "",
    val hashtags: List<String> = emptyList(),
    val taggedFriends: List<TagFriend> = emptyList(),
    val commenting: CommentingOption = CommentingOption.EVERYONE,
    val privacy: PostPrivacy = PostPrivacy.PUBLIC,
    val subscriberOnly: Boolean = false,
    val song: SongResult? = null,
    val embed: EmbedOption = EmbedOption.ALLOW,
    val upload: UploadProgress = UploadProgress()
)

class CreatePostViewModel(
    private val postsApi: PostsApi,
    private val directoryApi: UserDirectoryApi,
    private val platformContext: Any? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _state = MutableStateFlow(CreatePostState())
    val state: StateFlow<CreatePostState> = _state

    val songSearch = SongSearchApi()
    private val uploader = ChunkedUploadManager(postsApi, platformContext)
    private var uploadJob: Job? = null

    init {
        scope.launch { uploader.progress.collect { p -> _state.value = _state.value.copy(upload = p) } }
    }

    private fun update(f: (CreatePostState) -> CreatePostState) { _state.value = f(_state.value) }

    fun pickVideo(file: PickedFile) {
        val durationMs = (file.durationSeconds ?: 0) * 1000L
        update { it.copy(video = file, durationMs = durationMs, trimStartMs = 0, trimEndMs = 0) }
        scope.launch(Dispatchers.Default) {
            val frames = PostPlatformSupport.extractFrameStrip(file.path, durationMs, 10)
            update { it.copy(frameStrip = frames) }
        }
    }

    fun clearVideo() = update { it.copy(video = null, sendPath = null, frameStrip = emptyList(), durationMs = 0, trimStartMs = 0, trimEndMs = 0) }
    fun pickThumbnail(file: PickedFile) = update { it.copy(thumbnail = file) }
    fun setTrim(startMs: Long, endMs: Long) = update { it.copy(trimStartMs = startMs, trimEndMs = endMs) }
    fun setDescription(text: String) = update { it.copy(description = text.take(2000)) }
    fun setHashtags(tags: List<String>) = update {
        it.copy(hashtags = tags.map { t -> t.trim().removePrefix("#") }.filter(String::isNotBlank).distinct().take(30))
    }
    fun addTaggedFriend(friend: TagFriend) = update {
        if (it.taggedFriends.any { f -> f.userId == friend.userId } || it.taggedFriends.size >= 20) it
        else it.copy(taggedFriends = it.taggedFriends + friend)
    }
    fun removeTaggedFriend(userId: String) = update { it.copy(taggedFriends = it.taggedFriends.filterNot { f -> f.userId == userId }) }
    fun setCommenting(o: CommentingOption) = update { it.copy(commenting = o) }
    fun setPrivacy(o: PostPrivacy) = update { it.copy(privacy = o) }
    fun setSubscriberOnly(value: Boolean) = update { it.copy(subscriberOnly = value) }
    fun setSong(s: SongResult?) = update { it.copy(song = s) }
    fun setEmbed(o: EmbedOption) = update { it.copy(embed = o) }

    suspend fun searchFriends(query: String): List<TagFriend> =
        runCatching { directoryApi.search(query) }.getOrDefault(emptyList())
            .map { TagFriend(it.userId, it.fullName ?: it.username ?: "Unknown", it.username) }

    /** Uploads the video (chunked + resumable), waits for workers, then finalizes metadata. */
    fun post(onDone: (Boolean, String?) -> Unit) {
        val s = _state.value
        val video = s.video ?: return onDone(false, "Pick a video first")
        if (uploadJob?.isActive == true) return
        uploadJob = scope.launch {
            val postId = uploader.upload(video, onCompressing = { sendPath -> update { it.copy(sendPath = sendPath) } })
            if (postId == null) {
                onDone(false, _state.value.upload.error ?: "Upload failed")
                return@launch
            }
            val ok = runCatching {
                postsApi.finalizePost(
                    FinalizePostRequest(
                        postId = postId,
                        description = s.description.ifBlank { null },
                        hashtags = s.hashtags,
                        taggedUserIds = s.taggedFriends.map { it.userId },
                        commenting = s.commenting.name,
                        privacy = s.privacy.name,
                        embedAllowed = s.embed.allowed,
                        songTitle = s.song?.trackName,
                        songArtist = s.song?.artistName,
                        songPreviewUrl = s.song?.previewUrl,
                        songArtworkUrl = s.song?.artworkUrl100,
                        trimStartMs = s.trimStartMs,
                        trimEndMs = if (s.trimEndMs > 0) s.trimEndMs else s.durationMs,
                        subscriberOnly = s.subscriberOnly
                    )
                ).status.value in 200..299
            }.getOrDefault(false)
            onDone(ok, if (ok) null else "Couldn't publish post")
        }
    }
}
