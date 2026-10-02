package com.telefam.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * All tables use Exposed's typed DSL. There is no string-concatenated SQL
 * anywhere in this codebase — every value bound to a query goes through
 * Exposed's prepared-statement parameter binding, which is what actually
 * prevents SQL injection (not input filtering, which is defense-in-depth only).
 */

object Users : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val emailVerified = bool("email_verified").default(false)
    val passwordHash = varchar("password_hash", 255) // bcrypt, null-able for social-only accounts
        .nullable()
    val fullName = varchar("full_name", 100).nullable()
    val username = varchar("username", 30).uniqueIndex().nullable()
    val phoneCountryCode = varchar("phone_country_code", 8).nullable() // e.g. +254
    val phoneNumber = varchar("phone_number", 20).nullable()
    val gender = varchar("gender", 20).nullable()
    val dateOfBirth = datetime("date_of_birth").nullable()
    val bio = varchar("bio", 150).nullable()
    /** Optional public website link shown on the profile (validated http/https URL). */
    val website = varchar("website", 255).nullable()
    val profileImageMediaId = uuid("profile_image_media_id").nullable()
    val profileComplete = bool("profile_complete").default(false)
    val googleSub = varchar("google_sub", 255).nullable().uniqueIndex()
    val appleSub = varchar("apple_sub", 255).nullable().uniqueIndex()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    /** Last time the user went offline (all devices). Only ever shown to people allowed by PrivacySettings.whoCanSeeLastSeen. */
    val lastSeenAt = datetime("last_seen_at").nullable()
    /** Self-reported profile location, shown on the public profile ("Nairobi, Kenya") and used for nearby suggestions. */
    val locationName = varchar("location_name", 120).nullable()
    val locationLat = double("location_lat").nullable()
    val locationLng = double("location_lng").nullable()
    /** Username cooldown bookkeeping — a username may only change once every 7 days. */
    val usernameChangedAt = datetime("username_changed_at").nullable()
    /** ACTIVE | DEACTIVATED | DELETED — deactivated/deleted accounts cannot log in or appear anywhere. */
    val accountStatus = varchar("account_status", 20).default("ACTIVE")
    /** When true, a successful password login requires a 6-digit email OTP as the second factor. */
    val twoFactorEnabled = bool("two_factor_enabled").default(false)
    /** Email the account owner whenever a new session is created. */
    val loginAlertsEnabled = bool("login_alerts_enabled").default(true)
}

/** Tracks failed login attempts per account for the 3-strike lockout. Server-only, never exposed. */
object LoginAttempts : UUIDTable("login_attempts") {
    val userId = uuid("user_id")
    val failedCount = integer("failed_count").default(0)
    val lockedUntil = datetime("locked_until").nullable()
    val lastAttemptAt = datetime("last_attempt_at")
}

/** OTP codes — hashed at rest, never stored in plaintext. */
object OtpCodes : UUIDTable("otp_codes") {
    val userId = uuid("user_id")
    val purpose = varchar("purpose", 30) // SIGNUP_VERIFY, PASSWORD_RESET, LOGIN_2FA
    val codeHash = varchar("code_hash", 255)
    val expiresAt = datetime("expires_at")
    val consumed = bool("consumed").default(false)
    val failedAttempts = integer("failed_attempts").default(0)
    val resendCount = integer("resend_count").default(0)
    val lastSentAt = datetime("last_sent_at")
    val cooldownUntil = datetime("cooldown_until").nullable() // 5hr lock after 5 fails/resends
    val createdAt = datetime("created_at")
}

/** Refresh token store — enables rotation + reuse detection (revoke whole chain on reuse). */
object RefreshTokens : UUIDTable("refresh_tokens") {
    val userId = uuid("user_id")
    val tokenHash = varchar("token_hash", 255).uniqueIndex() // SHA-256 of the token, never raw
    val familyId = uuid("family_id") // groups a rotation chain
    val revoked = bool("revoked").default(false)
    val replacedByHash = varchar("replaced_by_hash", 255).nullable()
    val expiresAt = datetime("expires_at")
    val createdAt = datetime("created_at")
    val deviceInfo = varchar("device_info", 255).nullable()
}

/** Content fingerprints for uploaded media — enables reuse/dedup instead of reprocessing. */
object MediaAssets : UUIDTable("media_assets") {
    val ownerId = uuid("owner_id")
    val sha256 = varchar("sha256", 64).index()
    val perceptualHash = varchar("perceptual_hash", 64).index() // pHash, tolerant to re-encode
    val storagePath = varchar("storage_path", 500)
    val mimeType = varchar("mime_type", 100)
    val width = integer("width")
    val height = integer("height")
    val byteSize = long("byte_size")
    val createdAt = datetime("created_at")
}

/** One row per user. AccessLevel values: ANYONE, CONTACTS, NOBODY (validated server-side, see PrivacyEnums.kt). */
object PrivacySettings : UUIDTable("privacy_settings") {
    val userId = uuid("user_id").uniqueIndex()
    val messageRequests = varchar("message_requests", 20).default("ANYONE")
    val whoCanCallMe = varchar("who_can_call_me", 20).default("ANYONE")
    val whoCanScreenshotChats = varchar("who_can_screenshot_chats", 20).default("ANYONE")
    val whoCanShareChats = varchar("who_can_share_chats", 20).default("ANYONE")
    val whoCanCopyMessages = varchar("who_can_copy_messages", 20).default("ANYONE")
    val whoCanDownloadMedia = varchar("who_can_download_media", 20).default("ANYONE")
    val whoCanSeeLastSeen = varchar("who_can_see_last_seen", 20).default("ANYONE")
    // --- Detailed visibility center (Settings -> Privacy) ---
    val privateAccount = bool("private_account").default(false)
    val whoCanFollowMe = varchar("who_can_follow_me", 20).default("ANYONE")
    val allowProfileDiscovery = bool("allow_profile_discovery").default(true)
    val whoCanSeePosts = varchar("who_can_see_posts", 20).default("ANYONE")
    val whoCanSeeLikes = varchar("who_can_see_likes", 20).default("ANYONE")
    val whoCanComment = varchar("who_can_comment", 20).default("ANYONE")
    val whoCanSeeFollowing = varchar("who_can_see_following", 20).default("ANYONE")
    val whoCanMentionMe = varchar("who_can_mention_me", 20).default("ANYONE")
    val whoCanTagMe = varchar("who_can_tag_me", 20).default("ANYONE")
    val whoCanSeeActivity = varchar("who_can_see_activity", 20).default("ANYONE")
    val whoCanSeeFollowers = varchar("who_can_see_followers", 20).default("ANYONE")
    val chatsTheme = varchar("chats_theme", 20).default("LIGHT")
    val messageBubbleColour = varchar("message_bubble_colour", 20).default("RED")
    val updatedAt = datetime("updated_at")
}

/** A pending/accepted/declined message request between two users who aren't yet friends/contacts. */
object MessageRequests : UUIDTable("message_requests") {
    val senderId = uuid("sender_id")
    val receiverId = uuid("receiver_id")
    val status = varchar("status", 20).default("PENDING") // PENDING, ACCEPTED, DECLINED
    val previewText = varchar("preview_text", 200).nullable()
    val createdAt = datetime("created_at")
    val respondedAt = datetime("responded_at").nullable()
}

object BlockedUsers : UUIDTable("blocked_users") {
    val blockerId = uuid("blocker_id")
    val blockedId = uuid("blocked_id")
    val createdAt = datetime("created_at")
}

object ArchivedChats : UUIDTable("archived_chats") {
    val userId = uuid("user_id")
    val chatId = uuid("chat_id")
    val archivedAt = datetime("archived_at")
}

/** Per-(user, peer) preferences that should sync across the user's own devices. Mute only - disappearing-messages TTL is agreed via an encrypted control envelope between peers, not stored here (see E2EE README section). */
object ChatPreferences : UUIDTable("chat_preferences") {
    val userId = uuid("user_id")
    val peerId = uuid("peer_id")
    val mutedUntil = datetime("muted_until").nullable()
    val updatedAt = datetime("updated_at")
}

object Reports : UUIDTable("reports") {
    val reporterId = uuid("reporter_id")
    val reportedUserId = uuid("reported_user_id")
    val reason = varchar("reason", 500)
    val createdAt = datetime("created_at")
}

/** Free subscriptions ("Subscribers" on the profile) — a follow-like but distinct relationship. */
object Subscriptions : UUIDTable("subscriptions") {
    val subscriberId = uuid("subscriber_id")
    val creatorId = uuid("creator_id")
    val createdAt = datetime("created_at")
    init { uniqueIndex(subscriberId, creatorId) }
}

/**
 * Server-synced app preferences (content, accessibility, time management) so a
 * user's settings follow them across devices. The client also caches them locally;
 * enforcement (quality choice, autoplay, time limits) happens client-side.
 */
object AppSettings : UUIDTable("app_settings") {
    val userId = uuid("user_id").uniqueIndex()
    val contentLanguage = varchar("content_language", 10).default("en")
    val videoQuality = varchar("video_quality", 10).default("AUTO") // AUTO | LOW | MEDIUM | HIGH
    val autoplay = bool("autoplay").default(true)
    val dataSaver = bool("data_saver").default(false)
    val sensitiveContent = bool("sensitive_content").default(true)
    val suggestedContent = bool("suggested_content").default(true)
    val textSize = varchar("text_size", 10).default("MEDIUM") // SMALL | MEDIUM | LARGE | XLARGE
    val captionsEnabled = bool("captions_enabled").default(false)
    val reducedMotion = bool("reduced_motion").default(false)
    val highContrast = bool("high_contrast").default(false)
    val dailyLimitMinutes = integer("daily_limit_minutes").default(0) // 0 = no limit
    val breakReminderMinutes = integer("break_reminder_minutes").default(0) // 0 = off
    val quietModeEnabled = bool("quiet_mode_enabled").default(false)
    val updatedAt = datetime("updated_at")
}

/** "Report a problem" / support requests from Settings. */
object ProblemReports : UUIDTable("problem_reports") {
    val userId = uuid("user_id")
    val category = varchar("category", 30)
    val description = varchar("description", 2000)
    val appVersion = varchar("app_version", 40).default("")
    val attachmentMediaId = uuid("attachment_media_id").nullable()
    val status = varchar("status", 20).default("OPEN") // OPEN | IN_PROGRESS | RESOLVED
    val createdAt = datetime("created_at")
}

/** An in-flight email change: the OTP (purpose EMAIL_CHANGE) is verified against this row. */
object PendingEmailChanges : UUIDTable("pending_email_changes") {
    val userId = uuid("user_id").uniqueIndex()
    val newEmail = varchar("new_email", 255)
    val createdAt = datetime("created_at")
}
