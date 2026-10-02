package com.telefam.posts

import com.telefam.config.AppConfig
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-signed, expiring delivery URLs for public post media.
 * The public storage root is NEVER exposed as a static directory; every playback
 * request must carry a signature = HMAC_SHA256(secret, "mediaId:kind:expires")
 * and an expiry timestamp. This gives access control on retrieval (who can get
 * a URL, for how long) without a proxy hop through the app server for bytes.
 */
class SignedUrlService {

    private val secret get() = AppConfig.postSigningSecret
    private val ttlSeconds get() = AppConfig.postSignedUrlTtlSeconds

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Returns a signed delivery URL like /api/posts/media/{postId}/{kind}?e=..&s=..
     *  When CDN_BASE_URL is configured the URL is minted on the CDN origin (same HMAC
     *  contract), so byte delivery scales off the app server with zero client changes. */
    fun sign(postId: UUID, kind: String, viewerId: UUID? = null): String {
        // Subscriber-only links are short lived, viewer-bound, and always use
        // the origin handler (never a CDN cache that could bypass entitlement checks).
        val expires = Instant.now().epochSecond + if (viewerId == null) ttlSeconds else minOf(ttlSeconds, 60)
        val payload = if (viewerId == null) "$postId:$kind:$expires" else "$postId:$kind:$expires:$viewerId"
        val sig = hmac(payload)
        val viewer = viewerId?.let { "&u=$it" }.orEmpty()
        val path = "/api/posts/media/$postId/$kind?e=$expires&s=$sig$viewer"
        val cdn = AppConfig.cdnBaseUrl.trimEnd('/')
        return if (cdn.isEmpty() || viewerId != null) path else cdn + path
    }

    fun verify(postId: UUID, kind: String, expires: Long, signature: String, viewerId: UUID? = null): Boolean {
        if (expires < Instant.now().epochSecond) return false
        val payload = if (viewerId == null) "$postId:$kind:$expires" else "$postId:$kind:$expires:$viewerId"
        val expected = hmac(payload)
        // constant-time comparison to avoid signature oracle via timing
        if (expected.length != signature.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor signature[i].code)
        return diff == 0
    }
}
