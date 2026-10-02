package com.telefam.config

import io.github.cdimascio.dotenv.dotenv

/**
 * Single source of truth for all environment-driven configuration.
 * Nothing here is hardcoded — every value comes from `.env` (see .env.example).
 * Fails fast at startup if a required var is missing, so bad config never
 * reaches production silently.
 */
object AppConfig {
    private val env = dotenv {
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }

    private fun required(key: String): String =
        env[key] ?: System.getenv(key) ?: error("Missing required env var: $key")

    private fun optional(key: String, default: String): String =
        env[key] ?: System.getenv(key) ?: default

    // --- Database ---
    val databaseUrl: String get() = required("DATABASE_URL")
    val databaseUser: String get() = required("DATABASE_USER")
    val databasePassword: String get() = required("DATABASE_PASSWORD")
    val databasePoolSize: Int get() = optional("DATABASE_POOL_SIZE", "10").toInt()

    // --- JWT ---
    val jwtAccessSecret: String get() = required("JWT_ACCESS_SECRET")
    val jwtRefreshSecret: String get() = required("JWT_REFRESH_SECRET")
    val jwtIssuer: String get() = optional("JWT_ISSUER", "telefam")
    val jwtAudience: String get() = optional("JWT_AUDIENCE", "telefam-users")
    val jwtAccessTtlMinutes: Long get() = optional("JWT_ACCESS_TTL_MINUTES", "15").toLong()
    val jwtRefreshTtlDays: Long get() = optional("JWT_REFRESH_TTL_DAYS", "30").toLong()

    // --- Google Sign-In ---
    val googleClientIdAndroid: String get() = required("GOOGLE_CLIENT_ID_ANDROID")
    val googleClientIdWeb: String get() = required("GOOGLE_CLIENT_ID_WEB") // audience used for backend verify

    // --- Apple Sign In ---
    val appleClientId: String get() = required("APPLE_CLIENT_ID") // your Services ID / bundle id
    val appleKeysUrl: String get() = optional("APPLE_KEYS_URL", "https://appleid.apple.com/auth/keys")
    val appleIssuer: String get() = optional("APPLE_ISSUER", "https://appleid.apple.com")

    // --- Resend (email OTP / transactional) ---
    val resendApiKey: String get() = required("RESEND_API_KEY")
    val resendFromAddress: String get() = required("RESEND_FROM_ADDRESS")

    // --- Media storage (isolated bucket/dir, sandboxed) ---
    val mediaStoragePath: String get() = required("MEDIA_STORAGE_PATH")
    val mediaMaxUploadBytes: Long get() = optional("MEDIA_MAX_UPLOAD_BYTES", "8388608").toLong() // 8MB
    val mediaBlobMaxBytes: Long get() = optional("MEDIA_BLOB_MAX_BYTES", "33554432").toLong() // 32MB encrypted blobs
    val mediaMaxDimension: Int get() = optional("MEDIA_MAX_DIMENSION", "1600").toInt()

    // --- Security thresholds (server-enforced, silent to client) ---
    val maxLoginAttempts: Int get() = optional("MAX_LOGIN_ATTEMPTS", "3").toInt()
    val loginLockoutMinutes: Long get() = optional("LOGIN_LOCKOUT_MINUTES", "30").toLong()
    val maxOtpAttempts: Int get() = optional("MAX_OTP_ATTEMPTS", "5").toInt()
    val otpCooldownHours: Long get() = optional("OTP_COOLDOWN_HOURS", "5").toLong()
    val otpResendIntervalSeconds: Long get() = optional("OTP_RESEND_INTERVAL_SECONDS", "60").toLong()
    val otpResendMaxCount: Int get() = optional("OTP_RESEND_MAX_COUNT", "5").toInt()
    val otpTtlMinutes: Long get() = optional("OTP_TTL_MINUTES", "10").toLong()

    // --- Post video pipeline (chunked upload -> quarantine -> workers -> public storage) ---
    val postMaxUploadBytes: Long get() = optional("POST_MAX_UPLOAD_BYTES", "104857600").toLong() // 100MB
    val postChunkSizeBytes: Int get() = optional("POST_CHUNK_SIZE_BYTES", "4194304").toInt() // 4MB chunks
    val postQuarantinePath: String get() = required("POST_QUARANTINE_PATH") // never served
    val postWorkPath: String get() = required("POST_WORK_PATH") // ffmpeg scratch space
    val postPublicStoragePath: String get() = required("POST_PUBLIC_STORAGE_PATH") // signed-URL delivery only
    val postSigningSecret: String get() = required("POST_SIGNING_SECRET")
    val postSignedUrlTtlSeconds: Long get() = optional("POST_SIGNED_URL_TTL_SECONDS", "300").toLong()
    val postWorkerCount: Int get() = optional("POST_WORKER_COUNT", "2").toInt() // caps CPU-heavy ffmpeg jobs
    val postMaxDurationMs: Long get() = optional("POST_MAX_DURATION_MS", "600000").toLong() // 10 min
    val ffmpegPath: String get() = optional("FFMPEG_PATH", "ffmpeg")
    val ffprobePath: String get() = optional("FFPROBE_PATH", "ffprobe")

    // --- Feeds / CDN ---
    /** When set (e.g. https://cdn.telefam.app), signed media URLs are minted on the CDN
     *  origin instead of the app server — the CDN must validate the same HMAC signature.
     *  Empty = serve through this server's /api/posts/media endpoint. */
    val cdnBaseUrl: String get() = optional("CDN_BASE_URL", "")
    /** Public web base used to build shareable post links ($PUBLIC_BASE_URL/p/{postId}). */
    val publicBaseUrl: String get() = optional("PUBLIC_BASE_URL", "https://telefam.app")
    val feedPageSize: Int get() = optional("FEED_PAGE_SIZE", "10").toInt()

    // --- Payments: Paystack (supported African countries) ---
    val paystackSecretKey: String get() = required("PAYSTACK_SECRET_KEY")
    /** Base64-encoded 32-byte AES key. Without it, saved-card renewals stay disabled. */
    val paymentTokenEncryptionKey: String get() = optional("PAYMENT_TOKEN_ENCRYPTION_KEY", "")
    val subscriptionGraceDays: Long get() =
        optional("SUBSCRIPTION_BILLING_GRACE_DAYS", "7").toLongOrNull()?.coerceIn(0, 30) ?: 7L
    val subscriptionMaxRenewalRetries: Int get() =
        optional("SUBSCRIPTION_MAX_RENEWAL_RETRIES", "3").toIntOrNull()?.coerceIn(0, 5) ?: 3
    /** Base URL kept configurable so tests can point at a stub. */
    val paystackBaseUrl: String get() = optional("PAYSTACK_BASE_URL", "https://api.paystack.co")

    // --- Payments: PayPal (rest of the world) ---
    val paypalClientId: String get() = required("PAYPAL_CLIENT_ID")
    val paypalClientSecret: String get() = required("PAYPAL_CLIENT_SECRET")
    /** https://api-m.paypal.com (live) or https://api-m.sandbox.paypal.com (sandbox). */
    val paypalBaseUrl: String get() = optional("PAYPAL_BASE_URL", "https://api-m.paypal.com")
    /** Webhook id from the PayPal developer dashboard, used for webhook signature verification. */
    val paypalWebhookId: String get() = required("PAYPAL_WEBHOOK_ID")

    // --- Verification / AI review ---
    /** Moonshot (Kimi) API key — used ONLY server-side for document review. Never shipped to clients. */
    val kimiApiKey: String get() = required("KIMI_API_KEY")
    val kimiBaseUrl: String get() = optional("KIMI_BASE_URL", "https://api.moonshot.cn/v1")
    val kimiModel: String get() = optional("KIMI_MODEL", "moonshot-v1-32k-vision-preview")
    /** How long a granted verification badge stays valid before it expires and must be renewed. */
    val verificationBadgeTtlDays: Long get() = optional("VERIFICATION_BADGE_TTL_DAYS", "365").toLong()
    /** Renewal grace period after the paid verification term ends. */
    val verificationBillingGraceDays: Long get() = optional("VERIFICATION_BILLING_GRACE_DAYS", "7").toLong()
    /** Days after which a stuck/failed verification becomes refund-eligible (refund policy). */
    val verificationRefundDays: Long get() = optional("VERIFICATION_REFUND_DAYS", "3").toLong()
    /** HMAC secret used to sign liveness challenges so clients cannot forge completion. */
    val livenessChallengeSecret: String get() = required("LIVENESS_CHALLENGE_SECRET")

    // --- Wallet / earnings settlement ---
    /** How long an earning stays PENDING before it can settle to AVAILABLE. Backend-configurable; never hardcoded client-side. */
    val settlementPeriodHours: Long get() =
        optional("SETTLEMENT_PERIOD_HOURS", "72").toLongOrNull()?.coerceIn(0, 24 * 90) ?: 72L
    /** Telefam platform fee on applicable monetization earnings, in percent. */
    val platformFeePercent: Int get() =
        optional("PLATFORM_FEE_PERCENT", "20").toIntOrNull()?.coerceIn(0, 50) ?: 20
    /** Minimum withdrawal per currency, minor units (fallback used when currency unlisted). */
    val minWithdrawalMinorDefault: Long get() = optional("MIN_WITHDRAWAL_MINOR", "1000").toLong()
    /** Automatic subscriber refund window. Backend-configurable policy rule. */
    val subscriptionRefundWindowMinutes: Long get() =
        optional("SUBSCRIPTION_REFUND_WINDOW_MINUTES", "15").toLongOrNull()?.coerceIn(0, 24 * 60) ?: 15L
    /** Base64-encoded 32-byte AES key encrypting payout-method details at rest. */
    val payoutDetailEncryptionKey: String get() = optional("PAYOUT_DETAIL_ENCRYPTION_KEY", "")
    /** HMAC secret verifying payout-provider webhooks (M-Pesa/bank aggregator/PayPal payouts). */
    val payoutWebhookSecret: String get() = optional("PAYOUT_WEBHOOK_SECRET", "")
    /** Withdrawals at or above this amount (minor units, ~USD/EUR/GBP reference) ALWAYS go to
     *  manual security review regardless of the computed risk level. Default: 500.00. */
    val largeWithdrawalReviewMinor: Long get() =
        optional("LARGE_WITHDRAWAL_REVIEW_MINOR", "50000").toLongOrNull()?.coerceAtLeast(0) ?: 50_000L
    /** How often the settlement worker scans for due earnings (seconds). */
    val settlementSweepSeconds: Long get() = optional("SETTLEMENT_SWEEP_SECONDS", "60").toLong()

    val port: Int get() = optional("PORT", "8080").toInt()

    // --- Calls (WebRTC P2P: media never crosses this server) ---
    /** TURN relay for restrictive NATs. Empty = STUN-only (fine for dev). */
    val turnUrl: String get() = optional("TURN_URL", "")
    val turnUsername: String get() = optional("TURN_USERNAME", "")
    val turnCredential: String get() = optional("TURN_CREDENTIAL", "")
    /** Firebase project that owns the FCM sender used for incoming-call pushes. */
    val fcmProjectId: String get() = optional("FCM_PROJECT_ID", "")
    /** Full service-account JSON (one line) used to mint FCM v1 access tokens. Empty = pushes disabled. */
    val fcmServiceAccountJson: String get() = optional("FCM_SERVICE_ACCOUNT_JSON", "")
    /** iOS bundle id, used to build the .voip APNs topic for CallKit pushes. */
    val iosBundleId: String get() = optional("IOS_BUNDLE_ID", "com.telefam.app")
}
