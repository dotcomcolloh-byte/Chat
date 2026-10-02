package com.telefam.campaigns

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Stars wallet + paid campaigns. Money and stars move ONLY through these rules:
 *  - Star balances are ledger-derived; the wallet row is a cached total that is
 *    updated in the same transaction as its ledger entry (never client-set).
 *  - Star purchases become PAID exclusively via provider-side verification
 *    (Paystack /transaction/verify, PayPal capture+GET) or signature-validated
 *    webhooks — amounts are server-computed from the package catalog, and a
 *    provider amount/currency mismatch is never credited.
 *  - Campaign spend is atomic: the balance check, the debit ledger entry and the
 *    campaign row are written in ONE transaction. Every mutating call carries an
 *    Idempotency-Key with a unique index, so retries can never double-charge.
 * All tables are RLS-enforced in db/rls_policies.sql.
 */

/** Cached star balance per user (one row per user). Source of truth is the ledger. */
object StarWallets : UUIDTable("star_wallets") {
    val userId = uuid("user_id").uniqueIndex()
    val balanceStars = long("balance_stars").default(0)
    val updatedAt = datetime("updated_at")
}

/**
 * Append-only star ledger. `delta` is signed (purchases positive, campaign spend
 * negative). `idempotencyKey` is unique — a retried request can never post twice.
 */
object StarLedgerEntries : UUIDTable("star_ledger_entries") {
    val userId = uuid("user_id").index()
    val delta = long("delta")
    /** PURCHASE | CAMPAIGN_SPEND | CAMPAIGN_REFUND | SERIES_SPEND */
    val reason = varchar("reason", 24)
    /** purchase id / campaign id / series id this entry belongs to. */
    val referenceId = varchar("reference_id", 64).nullable()
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = datetime("created_at")
}

/** One row per star purchase attempt. Status is provider-verified only. */
object StarPurchases : UUIDTable("star_purchases") {
    val userId = uuid("user_id").index()
    val packageId = varchar("package_id", 24) // catalog id, e.g. "stars_100"
    val stars = long("stars")
    val amountMinor = long("amount_minor") // server-computed from the catalog
    val currency = varchar("currency", 8)
    val countryCode = varchar("country_code", 4)
    val provider = varchar("provider", 16) // PAYSTACK | PAYPAL
    val providerRef = varchar("provider_ref", 128).uniqueIndex()
    /** PENDING | PAID | FAILED — never settable by the client. */
    val status = varchar("status", 12).default("PENDING").index()
    val checkoutUrl = varchar("checkout_url", 600).nullable()
    /** SHA-256 of the last webhook payload applied — replay guard. */
    val lastWebhookHash = varchar("last_webhook_hash", 64).nullable()
    val failureReason = varchar("failure_reason", 300).nullable()
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    /** Set when the ledger credit was posted — a purchase credits exactly once. */
    val creditedAt = datetime("credited_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

/** A paid boost campaign. Ends by wall-clock time AND by reach (served impressions). */
object Campaigns : UUIDTable("campaigns") {
    val ownerId = uuid("owner_id").index()
    /** PROFILE_BOOST | GET_SALES | SERIES_VIDEOS | LIKES_COMMENTS | VIEWS | FOLLOWERS */
    val type = varchar("type", 20)
    val postId = uuid("post_id").nullable().index()
    val seriesId = uuid("series_id").nullable()
    /** GET_SALES only: the external destination link. Validated server-side. */
    val linkUrl = varchar("link_url", 600).nullable()
    /** VISIT | DOWNLOAD | BUY | GET_IN_TOUCH | SIGN_UP | WATCH — the CTA on sponsored items. */
    val linkAction = varchar("link_action", 16).nullable()
    val tierId = varchar("tier_id", 24)
    val minReach = long("min_reach")
    /** Reach cap that ends the campaign early (Long.MAX_VALUE for the 1M+ tier). */
    val maxReach = long("max_reach")
    val starsPerDay = long("stars_per_day")
    val days = integer("days")
    val totalStars = long("total_stars")
    /** ACTIVE | PAUSED | EXHAUSTED (unique reach met) | EXPIRED (time up) | CANCELED */
    val status = varchar("status", 12).default("ACTIVE").index()
    /** Deduped unique impressions served (one per viewer per campaign). */
    val servedImpressions = long("served_impressions").default(0)
    // ---- Audience targeting (server-enforced at delivery time) ----
    /** AUTO (server picks eligible viewers) | CUSTOM (advertiser-defined filters). */
    val audienceMode = varchar("audience_mode", 12).default("AUTO")
    /** CSV of ISO-3166 alpha-2 country codes; NULL/empty = all countries. */
    val targetCountries = varchar("target_countries", 400).nullable()
    /** Inclusive age bounds derived from viewer date_of_birth; NULL = no limit. */
    val ageMin = integer("age_min").nullable()
    val ageMax = integer("age_max").nullable()
    /** MALE | FEMALE | NULL (all genders). Viewers with unset gender match everything. */
    val targetGender = varchar("target_gender", 12).nullable()
    /** CSV of interest tags; matched against the post's hashtags/category. NULL = any. */
    val targetInterests = varchar("target_interests", 600).nullable()
    /** Set while PAUSED; resume shifts endsAt by the paused duration. */
    val pausedAt = datetime("paused_at").nullable()
    val startsAt = datetime("starts_at")
    val endsAt = datetime("ends_at").index()
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = datetime("created_at")
}

/** A video series: a named, paid-per-day collection of the owner's own posts. */
object VideoSeries : UUIDTable("video_series") {
    val ownerId = uuid("owner_id").index()
    val name = varchar("name", 50)
    val description = varchar("description", 200).default("")
    val costPerDayStars = long("cost_per_day_stars")
    /** ACTIVE | EXPIRED | ARCHIVED */
    val status = varchar("status", 12).default("ACTIVE")
    val activeUntil = datetime("active_until").index()
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val createdAt = datetime("created_at")
}

/** Ordered videos inside a series. Only posts owned by the series owner. */
object SeriesItems : UUIDTable("series_items") {
    val seriesId = uuid("series_id").index()
    val postId = uuid("post_id")
    val position = integer("position").default(0)
    init {
        uniqueIndex("uq_series_post", seriesId, postId)
    }
}

/**
 * Sponsored delivery events, deduped per (campaign, viewer, kind) — the unique
 * index is what makes "expire when estimated unique reach is met" accurate and
 * powers the advertiser funnel (impressions → clicks → follows / destination actions).
 */
object SponsoredEvents : UUIDTable("sponsored_events") {
    val campaignId = uuid("campaign_id").index()
    val viewerId = uuid("viewer_id")
    /** IMPRESSION | CLICK | PROFILE_VISIT | FOLLOW | DESTINATION_CLICK */
    val kind = varchar("kind", 20)
    val createdAt = datetime("created_at")
    init {
        uniqueIndex("uq_sponsored_event", campaignId, viewerId, kind)
    }
}

/**
 * Server-side proof that a sponsored campaign was actually delivered to a viewer.
 * The client never supplies or chooses this record; FeedService creates it only after
 * the sponsored item has passed the same server-side visibility/media checks used for
 * the feed response. The event endpoint requires this proof before accepting events.
 */
object SponsoredDeliveries : UUIDTable("sponsored_deliveries") {
    val campaignId = uuid("campaign_id").index()
    val viewerId = uuid("viewer_id").index()
    val deliveredAt = datetime("delivered_at").index()
    init {
        uniqueIndex("uq_sponsored_delivery", campaignId, viewerId)
    }
}
