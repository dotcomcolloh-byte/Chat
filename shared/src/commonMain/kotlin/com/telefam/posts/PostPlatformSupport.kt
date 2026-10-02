package com.telefam.posts

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform hooks for the Create Post pipeline.
 *  - readChunk: random-access reads so uploads resume without loading 100MB into memory.
 *  - compressIfNeeded: client-side compression before upload; returns the path to send
 *    (compressed copy, or the original when compression is unnecessary/unavailable).
 *  - extractFrameStrip: decodes [count] evenly-spaced frames (keyframe-aligned where
 *    the container allows) for the trim timeline's iframe strip.
 */
expect object PostPlatformSupport {
    fun readChunk(path: String, offset: Long, length: Int): ByteArray
    fun fileSize(path: String): Long
    suspend fun compressIfNeeded(path: String, mime: String, sizeBytes: Long, context: Any?): String
    fun extractFrameStrip(path: String, durationMs: Long, count: Int): List<ImageBitmap>
}
