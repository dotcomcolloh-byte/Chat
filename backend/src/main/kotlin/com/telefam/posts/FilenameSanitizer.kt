package com.telefam.posts

import java.text.Normalizer

/**
 * User-provided filenames are never trusted as paths.
 * - Stored form is always a random UUID; the original name is kept only for display.
 * - The display name itself is normalized, stripped of control chars, path separators,
 *   and traversal segments, and length-capped.
 * - The on-disk extension comes from a whitelist keyed on the *validated* media type,
 *   never from the user's filename.
 */
object FilenameSanitizer {

    private val allowedVideoExt = mapOf(
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv"
    )

    fun sanitizeDisplayName(raw: String?): String {
        if (raw.isNullOrBlank()) return "video"
        var name = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        // strip control chars and any path separators / traversal
        name = name.replace(Regex("[\\p{Cntrl}]"), "")
            .replace('\\', '_')
            .replace('/', '_')
            .replace("..", "_")
            .trim()
        if (name.isBlank() || name == "." || name == "_") return "video"
        return name.take(120)
    }

    /** Extension for quarantine storage, derived from validated mime — never from the filename. */
    fun extForMime(mime: String): String? = allowedVideoExt[mime.lowercase()]

    fun isAllowedVideoMime(mime: String): Boolean = allowedVideoExt.containsKey(mime.lowercase())
}
