package com.telefam.campaigns

import com.telefam.db.BlockedUsers
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.Users
import com.telefam.payments.PayPalClient
import com.telefam.payments.PaystackClient
import com.telefam.payments.PricingCatalog
import com.telefam.posts.Posts
import com.telefam.config.AppConfig
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import java.net.URI
import java.net.IDN
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

// ---------- Wire DTOs (mirrored by the shared KMP client) ----------

@Serializable
data class StarBalanceDto(val balanceStars: Long)

@Serializable
data class StarPackageDto(
    val id: String,
    val stars: Long,
    val amountMinor: Long,
    val formattedAmount: String,
    val currency: String,
    val popular: Boolean = false
)

@Serializable
data class StarPackagesDto(
    val provider: String,          // PAYSTACK | PAYPAL — single method, server-decided by country
    val providerLabel: String,
    val currency: String,
    val packages: List<StarPackageDto>
)

@Serializable
data class InitiatedStarPurchaseDto(
    val purchaseId: String,
    val provider: String,
    val checkoutUrl: String,
    val reference: String,
    val stars: Long,
    val formattedAmount: String,
    val currency: String,
    val reused: Boolean = false
)

@Serializable
data class StarPurchaseStatusDto(
    val purchaseId: String,
    /** PENDING | PAID | FAILED — authoritative, provider-verified. */
    val status: String,
    val balanceStars: Long,
    val failureReason: String? = null
)

@Serializable
data class CampaignTierDto(
    val id: String,
    val minReach: Long,
    val maxReach: Long, // -1 = unbounded ("1M+")
    val starsPerDay: Long
)

@Serializable
data class CampaignConfigDto(
    val balanceStars: Long,
    /** type -> tiers. SERIES_VIDEOS has no tiers; it is priced per day via seriesCostPerDayStars. */
    val tiers: Map<String, List<CampaignTierDto>>,
    val seriesCostPerDayStars: Long,
    val maxDays: Int
)

@Serializable
data class AudienceDto(
    /** AUTO | CUSTOM */
    val mode: String = "AUTO",
    /** ISO-3166 alpha-2 codes; empty = worldwide. */
    val countries: List<String> = emptyList(),
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    /** MALE | FEMALE | null = all. */
    val gender: String? = null,
    val interests: List<String> = emptyList()
)

@Serializable
data class CreateCampaignRequest(
    val type: String,
    val postId: String,
    val tierId: String,
    val days: Int,
    val linkUrl: String? = null,
    val linkAction: String? = null,
    val audience: AudienceDto? = null
)

@Serializable
data class CampaignDto(
    val id: String,
    val type: String,
    val postId: String? = null,
    val seriesId: String? = null,
    val linkUrl: String? = null,
    val linkAction: String? = null,
    val tierId: String,
    val minReach: Long,
    val maxReach: Long,
    val starsPerDay: Long,
    val days: Int,
    val totalStars: Long,
    val status: String,
    /** Unique viewers reached (deduped per viewer). */
    val servedImpressions: Long,
    /** Deduped clicks on the sponsored CTA. */
    val clicks: Long = 0,
    val refundedStars: Long = 0,
    val audience: AudienceDto = AudienceDto(),
    val startsAt: String,
    val endsAt: String
)

/** Advertiser analytics: deduped funnel counts per campaign. */
@Serializable
data class CampaignAnalyticsDto(
    val campaignId: String,
    val impressions: Long,
    val clicks: Long,
    val profileVisits: Long,
    val follows: Long,
    val destinationClicks: Long,
    /** clicks / impressions, 0..1. */
    val ctr: Double,
    val uniqueViewers: Long,
    val starsSpent: Long,
    val status: String
)

@Serializable
data class CreateSeriesRequest(
    val name: String,
    val description: String = "",
    val postIds: List<String>,
    val days: Int = 1
)

@Serializable
data class SeriesDto(
    val id: String,
    val name: String,
    val description: String,
    val costPerDayStars: Long,
    val videoCount: Int,
    val status: String,
    val activeUntil: String,
    val coverThumbnailUrl: String? = null,
    val categoryLabel: String = ""
)

/** Thrown when the wallet can't cover a spend; the route maps it to 402 + the balance. */
class InsufficientStarsException(val balanceStars: Long, val requiredStars: Long) : Exception()

/**
 * Stars wallet + paid campaigns ("Boost"). Every star movement is ledger-backed and
 * idempotent; every payment is settled only against the provider's own API; every
 * campaign ends by wall-clock expiry OR by reaching its tier's reach cap, whichever
 * comes first.
 */
class CampaignService(
    private val paystack: PaystackClient = PaystackClient(),
    private val paypal: PayPalClient = PayPalClient(),
    private val signedUrls: com.telefam.posts.SignedUrlService? = null
) {
    companion object {
        /** Fixed star store catalog — the client renders what the server returns. */
        val STAR_PACKAGES: List<Pair<Long, Boolean>> = listOf(
            5L to true, 10L to false, 20L to false, 50L to false, 100L to false, 200L to false,
            500L to false, 1_000L to false, 2_000L to false, 5_000L to false, 10_000L to false, 50_000L to false
        )

        /** Minor-unit price of ONE star per currency (KES 100 = Ksh 1 per star, as in the store UI). */
        private val STAR_PRICE_MINOR: Map<String, Long> = mapOf(
            "KES" to 100, "NGN" to 100, "GHS" to 100, "ZAR" to 100,
            "USD" to 1, "EUR" to 1, "GBP" to 1
        )

        const val SERIES_COST_PER_DAY_STARS = 300L
        const val MAX_DAYS = 30

        /** Reach tiers per campaign type — server-side single source of truth. */
        val TIERS: Map<String, List<CampaignTierDto>> = mapOf(
            "VIEWS" to listOf(
                CampaignTierDto("v1", 1_500, 2_500, 50),
                CampaignTierDto("v2", 2_500, 5_000, 150),
                CampaignTierDto("v3", 6_000, 10_000, 300),
                CampaignTierDto("v4", 20_000, 50_000, 700),
                CampaignTierDto("v5", 300_000, -1, 3_000)
            ),
            "PROFILE_BOOST" to listOf(
                CampaignTierDto("p1", 1_500, 2_500, 50),
                CampaignTierDto("p2", 2_500, 5_000, 150),
                CampaignTierDto("p3", 6_000, 10_000, 300),
                CampaignTierDto("p4", 20_000, 50_000, 700),
                CampaignTierDto("p5", 300_000, -1, 3_000)
            ),
            "LIKES_COMMENTS" to listOf(
                CampaignTierDto("e1", 1_500, 2_500, 50),
                CampaignTierDto("e2", 2_500, 5_000, 150),
                CampaignTierDto("e3", 6_000, 10_000, 300),
                CampaignTierDto("e4", 20_000, 50_000, 700),
                CampaignTierDto("e5", 300_000, -1, 3_000)
            ),
            "FOLLOWERS" to listOf(
                CampaignTierDto("f1", 1_000, 2_500, 75),
                CampaignTierDto("f2", 2_500, 5_000, 200),
                CampaignTierDto("f3", 6_000, 10_000, 400),
                CampaignTierDto("f4", 10_000, 20_000, 700),
                CampaignTierDto("f5", 20_000, 50_000, 1_200),
                CampaignTierDto("f6", 50_000, 100_000, 2_000),
                CampaignTierDto("f7", 100_000, 500_000, 5_000)
            ),
            "GET_SALES" to listOf(
                CampaignTierDto("s1", 2_500, 5_000, 150),
                CampaignTierDto("s2", 5_000, 15_000, 400),
                CampaignTierDto("s3", 15_000, 30_000, 800),
                CampaignTierDto("s4", 30_000, 100_000, 1_500),
                CampaignTierDto("s5", 100_000, 300_000, 3_000),
                CampaignTierDto("s6", 300_000, -1, 6_000)
            )
        )

        val CAMPAIGN_TYPES = TIERS.keys + "SERIES_VIDEOS"
        val LINK_ACTIONS = setOf("VISIT", "DOWNLOAD", "BUY", "GET_IN_TOUCH", "SIGN_UP", "WATCH")

        /** Interest tags advertisers can target; matched against the boosted post's hashtags. */
        val INTERESTS = listOf(
            "MUSIC", "COMEDY", "DANCE", "SPORTS", "GAMING", "FOOD", "TRAVEL", "FASHION",
            "BEAUTY", "FITNESS", "TECH", "EDUCATION", "BUSINESS", "ART", "NEWS", "LIFESTYLE"
        )

        /** Sponsored event kinds the analytics funnel understands. */
        val EVENT_KINDS = setOf("IMPRESSION", "CLICK", "PROFILE_VISIT", "FOLLOW", "DESTINATION_CLICK")
    }

    private val DIAL_TO_COUNTRY = mapOf(
        "+254" to "KE", "+255" to "TZ", "+256" to "UG", "+250" to "RW",
        "+234" to "NG", "+233" to "GH", "+27" to "ZA",
        "+1" to "US", "+44" to "GB", "+49" to "DE", "+33" to "FR", "+39" to "IT",
        "+34" to "ES", "+31" to "NL", "+353" to "IE", "+61" to "AU", "+91" to "IN"
    )

    /** ISO-3166 alpha-2 country codes accepted by custom campaign targeting. */
    private val ISO_COUNTRIES: Set<String> =
        java.util.Locale.getISOCountries().map { it.uppercase(Locale.ROOT) }.toSet()

    private suspend fun countryOf(userId: UUID): String = dbQuery {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?.get(Users.phoneCountryCode)?.let { DIAL_TO_COUNTRY[it] } ?: "US"
    }

    private fun starPriceMinor(currency: String) = STAR_PRICE_MINOR[currency] ?: STAR_PRICE_MINOR.getValue("USD")

    // ---------------------------------------------------------------- balance

    suspend fun balance(userId: UUID): StarBalanceDto = StarBalanceDto(balanceOf(userId))

    private suspend fun balanceOf(userId: UUID): Long = dbQuery {
        StarWallets.selectAll().where { StarWallets.userId eq userId }.singleOrNull()
            ?.get(StarWallets.balanceStars) ?: 0L
    }

    // ---------------------------------------------------------------- star purchases

    suspend fun packages(userId: UUID): StarPackagesDto {
        val country = countryOf(userId)
        val provider = PricingCatalog.providerFor(country)
        val currency = PricingCatalog.currencyFor(country)
        val perStar = starPriceMinor(currency)
        return StarPackagesDto(
            provider = provider.name,
            providerLabel = if (provider == PricingCatalog.Provider.PAYSTACK) "Paystack" else "PayPal",
            currency = currency,
            packages = STAR_PACKAGES.map { (stars, popular) ->
                val amount = stars * perStar
                StarPackageDto("stars_$stars", stars, amount, PricingCatalog.format(currency, amount), currency, popular)
            }
        )
    }

    /**
     * Create a PENDING purchase + provider checkout. Idempotent: the same
     * Idempotency-Key returns the existing attempt unchanged (reused = true).
     */
    suspend fun initiatePurchase(userId: UUID, packageId: String, idempotencyKey: String): InitiatedStarPurchaseDto {
        val existing = dbQuery {
            StarPurchases.selectAll().where { StarPurchases.idempotencyKey eq idempotencyKey }.singleOrNull()
        }
        if (existing != null) {
            require(existing[StarPurchases.userId] == userId) { "idempotency key belongs to another account" }
            return InitiatedStarPurchaseDto(
                existing[StarPurchases.id].value.toString(), existing[StarPurchases.provider],
                existing[StarPurchases.checkoutUrl] ?: "", existing[StarPurchases.providerRef],
                existing[StarPurchases.stars],
                PricingCatalog.format(existing[StarPurchases.currency], existing[StarPurchases.amountMinor]),
                existing[StarPurchases.currency], reused = true
            )
        }
        val stars = packageId.removePrefix("stars_").toLongOrNull()
            ?: throw IllegalArgumentException("unknown package")
        require(STAR_PACKAGES.any { it.first == stars }) { "unknown package" }

        val country = countryOf(userId)
        val provider = PricingCatalog.providerFor(country)
        val currency = PricingCatalog.currencyFor(country)
        val amountMinor = stars * starPriceMinor(currency)
        val purchaseId = UUID.randomUUID()
        val email = dbQuery { Users.selectAll().where { Users.id eq userId }.single()[Users.email] }

        val (ref, url) = when (provider) {
            PricingCatalog.Provider.PAYSTACK -> {
                val init = paystack.initialize(
                    email = email, amountMinor = amountMinor, currency = currency,
                    reference = "tfs_${purchaseId.toString().replace("-", "")}",
                    callbackUrl = "${AppConfig.publicBaseUrl}/stars/callback"
                )
                init.reference to init.authorizationUrl
            }
            PricingCatalog.Provider.PAYPAL -> {
                val order = paypal.createOrder(
                    amountMinor = amountMinor, currency = currency,
                    returnUrl = "${AppConfig.publicBaseUrl}/stars/paypal/return",
                    cancelUrl = "${AppConfig.publicBaseUrl}/stars/paypal/cancel"
                )
                order.orderId to order.approveUrl
            }
        }

        dbQuery {
            StarPurchases.insert {
                it[id] = purchaseId; it[StarPurchases.userId] = userId
                it[StarPurchases.packageId] = packageId; it[StarPurchases.stars] = stars
                it[StarPurchases.amountMinor] = amountMinor; it[StarPurchases.currency] = currency
                it[countryCode] = country; it[StarPurchases.provider] = provider.name
                it[providerRef] = ref; it[checkoutUrl] = url; it[status] = "PENDING"
                it[StarPurchases.idempotencyKey] = idempotencyKey
                it[createdAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
            }
        }
        return InitiatedStarPurchaseDto(
            purchaseId.toString(), provider.name, url, ref, stars,
            PricingCatalog.format(currency, amountMinor), currency
        )
    }

    /** Client returned from checkout — we never trust the client, we ask the provider. */
    suspend fun confirmPurchase(userId: UUID, purchaseId: UUID): StarPurchaseStatusDto {
        val row = dbQuery {
            StarPurchases.selectAll().where {
                (StarPurchases.id eq purchaseId) and (StarPurchases.userId eq userId)
            }.singleOrNull()
        } ?: throw NoSuchElementException("purchase not found")
        return settleWithProvider(row)
    }

    private suspend fun settleWithProvider(row: ResultRow): StarPurchaseStatusDto {
        val purchaseId = row[StarPurchases.id].value
        val userId = row[StarPurchases.userId]
        val ref = row[StarPurchases.providerRef]
        val expectedAmount = row[StarPurchases.amountMinor]
        val expectedCurrency = row[StarPurchases.currency]
        if (row[StarPurchases.status] in listOf("PAID", "FAILED")) {
            return StarPurchaseStatusDto(purchaseId.toString(), row[StarPurchases.status], balanceOf(userId),
                row[StarPurchases.failureReason])
        }

        val outcome = when (row[StarPurchases.provider]) {
            "PAYSTACK" -> paystack.verify(ref).let { Triple(it.paid, it.amountMinor to it.currency, it.failureReason) }
            else -> {
                val captured = runCatching { paypal.captureOrder(ref) }.getOrElse { paypal.getOrder(ref) }
                Triple(captured.paid, captured.amountMinor to captured.currency, if (captured.paid) null else captured.status)
            }
        }
        val (paid, amountCurrency, failure) = outcome
        // Amount/currency mismatch = tampering or provider error: never credit.
        val amountOk = amountCurrency.first == expectedAmount && amountCurrency.second.equals(expectedCurrency, true)
        val newStatus = when {
            paid && amountOk -> "PAID"
            paid && !amountOk -> "FAILED"
            failure != null && failure != "PENDING" && failure != "CREATED" && failure != "APPROVED" -> "FAILED"
            else -> "PENDING"
        }
        dbQuery {
            StarPurchases.update({ StarPurchases.id eq purchaseId }) {
                it[status] = newStatus
                it[failureReason] = if (newStatus == "FAILED") (failure ?: "amount_mismatch") else null
                it[updatedAt] = LocalDateTime.now()
            }
        }
        if (newStatus == "PAID") creditPurchase(purchaseId)
        return StarPurchaseStatusDto(purchaseId.toString(), newStatus, balanceOf(userId),
            if (newStatus == "FAILED") (failure ?: "amount_mismatch") else null)
    }

    /** Post the ledger credit exactly once per purchase (guarded by creditedAt + unique key). */
    private suspend fun creditPurchase(purchaseId: UUID) {
        dbQuery {
            val row = StarPurchases.selectAll().where { StarPurchases.id eq purchaseId }.single()
            if (row[StarPurchases.creditedAt] != null) return@dbQuery // already credited
            val userId = row[StarPurchases.userId]
            val stars = row[StarPurchases.stars]
            StarLedgerEntries.insert {
                it[StarLedgerEntries.userId] = userId
                it[delta] = stars
                it[reason] = "PURCHASE"
                it[referenceId] = purchaseId.toString()
                it[idempotencyKey] = "purchase:$purchaseId"
                it[createdAt] = LocalDateTime.now()
            }
            ensureWallet(userId)
            StarWallets.update({ StarWallets.userId eq userId }) {
                it[balanceStars] = balanceStars + stars
                it[updatedAt] = LocalDateTime.now()
            }
            StarPurchases.update({ StarPurchases.id eq purchaseId }) {
                it[creditedAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
            }
        }
    }

    // ---------------- Webhooks (signature-verified, replay-safe, provider re-checked) ----------------

    suspend fun handlePaystackWebhook(rawBody: ByteArray, signature: String?) {
        if (!paystack.isValidWebhookSignature(rawBody, signature)) return
        val body = String(rawBody)
        val ref = Regex("\"reference\"\\s*:\\s*\"(tfs_[^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        val event = Regex("\"event\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return
        applyWebhook("PAYSTACK", ref, event == "charge.success", hashOf(rawBody))
    }

    suspend fun handlePayPalWebhook(headers: Map<String, String>, rawBody: String) {
        if (!paypal.isValidWebhook(headers, rawBody)) return
        val row = findPayPalRow(rawBody) ?: return
        applyWebhook("PAYPAL", row[StarPurchases.providerRef],
            rawBody.contains("PAYMENT.CAPTURE.COMPLETED") || rawBody.contains("CHECKOUT.ORDER.APPROVED"),
            hashOf(rawBody.toByteArray()))
    }

    private suspend fun findPayPalRow(rawBody: String): ResultRow? {
        val orderId = Regex("\"order_id\"\\s*:\\s*\"([^\"]+)\"").find(rawBody)?.groupValues?.get(1)
            ?: Regex("\"id\"\\s*:\\s*\"([A-Z0-9]{17})\"").find(rawBody)?.groupValues?.get(1) ?: return null
        return dbQuery {
            StarPurchases.selectAll().where {
                (StarPurchases.providerRef eq orderId) and (StarPurchases.provider eq "PAYPAL")
            }.singleOrNull()
        }
    }

    private suspend fun applyWebhook(provider: String, ref: String, paidHint: Boolean, payloadHash: String) {
        val row = dbQuery {
            StarPurchases.selectAll().where {
                (StarPurchases.providerRef eq ref) and (StarPurchases.provider eq provider)
            }.singleOrNull()
        } ?: return
        if (row[StarPurchases.lastWebhookHash] == payloadHash) return // replay
        dbQuery {
            StarPurchases.update({ StarPurchases.id eq row[StarPurchases.id] }) {
                it[lastWebhookHash] = payloadHash; it[updatedAt] = LocalDateTime.now()
            }
        }
        if (paidHint) settleWithProvider(row)
    }

    // ---------------------------------------------------------------- campaigns

    suspend fun config(userId: UUID): CampaignConfigDto = CampaignConfigDto(
        balanceStars = balanceOf(userId),
        tiers = TIERS,
        seriesCostPerDayStars = SERIES_COST_PER_DAY_STARS,
        maxDays = MAX_DAYS
    )

    /**
     * Validate an advertiser destination without ever fetching it server-side.
     * The URL is opened by the client, so the important guarantees are: HTTPS only,
     * a real host, no embedded credentials, no malformed authority/port, and no
     * control/whitespace characters. Do not truncate: truncation can turn a valid
     * URL into a different destination, so oversized input is rejected instead.
     */
    private fun validateLink(url: String): String {
        val clean = url.trim()
        require(clean.isNotEmpty() && clean.length <= 600) { "invalid link" }
        require(clean.none { it.isISOControl() || it.isWhitespace() }) { "invalid link characters" }

        val uri = runCatching { URI(clean) }.getOrElse {
            throw IllegalArgumentException("invalid link URL")
        }
        require(uri.scheme?.lowercase(Locale.ROOT) == "https") { "link must be an https:// URL" }
        require(uri.rawUserInfo == null) { "link credentials are not allowed" }
        require(!uri.host.isNullOrBlank()) { "invalid link host" }
        require(uri.port == -1 || uri.port in 1..65535) { "invalid link port" }

        // URI#getHost() can be null for malformed/unescaped Unicode hostnames.
        // Convert each DNS label to ASCII and validate the resulting hostname.
        val host = uri.host.trimEnd('.').lowercase(Locale.ROOT)
        require(host.isNotEmpty() && host.length <= 253) { "invalid link host" }
        val asciiHost = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrElse {
            throw IllegalArgumentException("invalid link host")
        }
        require(asciiHost.length <= 253) { "invalid link host" }
        val labels = asciiHost.split('.')
        require(labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }) { "invalid link host" }
        return clean
    }

    /**
     * Create a campaign and debit the wallet atomically. The conditional UPDATE
     * (balance >= cost) is the concurrency guard — two racing boosts can never
     * overdraw, and the unique idempotency key makes retries free.
     */
    suspend fun createCampaign(userId: UUID, req: CreateCampaignRequest, idempotencyKey: String): CampaignDto {
        dbQuery {
            Campaigns.selectAll().where { Campaigns.idempotencyKey eq idempotencyKey }.singleOrNull()
        }?.let { return toDto(it) }

        val type = req.type.uppercase()
        require(type in TIERS.keys) { "unknown campaign type" }
        val tier = TIERS.getValue(type).firstOrNull { it.id == req.tierId }
            ?: throw IllegalArgumentException("unknown tier")
        val days = req.days.coerceIn(1, MAX_DAYS)
        val postId = runCatching { UUID.fromString(req.postId) }.getOrNull()
            ?: throw IllegalArgumentException("a video is required")
        // Boosting works only on your own published video.
        val ownsPost = dbQuery {
            Posts.selectAll().where {
                (Posts.id eq postId) and (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
            }.any()
        }
        require(ownsPost) { "video not found" }
        val link = if (type == "GET_SALES") validateLink(req.linkUrl ?: throw IllegalArgumentException("link required")) else null
        val action = if (type == "GET_SALES") {
            val a = (req.linkAction ?: "VISIT").uppercase()
            require(a in LINK_ACTIONS) { "unknown link action" }
            a
        } else null
        val total = tier.starsPerDay * days
        val audience = normalizeAudience(req.audience)

        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        val created = dbQuery {
            ensureWallet(userId)
            // Atomic conditional debit: exactly one concurrent writer wins.
            val debited = StarWallets.update({
                (StarWallets.userId eq userId) and (StarWallets.balanceStars greaterEq total)
            }) {
                it[balanceStars] = balanceStars - total
                it[updatedAt] = now
            }
            if (debited == 0) return@dbQuery false
            StarLedgerEntries.insert {
                it[StarLedgerEntries.userId] = userId
                it[delta] = -total
                it[reason] = "CAMPAIGN_SPEND"
                it[referenceId] = id.toString()
                it[StarLedgerEntries.idempotencyKey] = "campaign:$idempotencyKey"
                it[createdAt] = now
            }
            Campaigns.insert {
                it[Campaigns.id] = id; it[ownerId] = userId; it[Campaigns.type] = type
                it[Campaigns.postId] = postId; it[linkUrl] = link; it[linkAction] = action
                it[tierId] = tier.id; it[minReach] = tier.minReach
                it[maxReach] = if (tier.maxReach < 0) Long.MAX_VALUE else tier.maxReach
                it[starsPerDay] = tier.starsPerDay; it[Campaigns.days] = days
                it[totalStars] = total; it[status] = "ACTIVE"
                it[audienceMode] = audience.mode
                it[targetCountries] = audience.countries.takeIf { it.isNotEmpty() }?.joinToString(",")
                it[ageMin] = audience.ageMin; it[ageMax] = audience.ageMax
                it[targetGender] = audience.gender
                it[targetInterests] = audience.interests.takeIf { i -> i.isNotEmpty() }?.joinToString(",")
                it[startsAt] = now; it[endsAt] = now.plusDays(days.toLong())
                it[Campaigns.idempotencyKey] = idempotencyKey; it[createdAt] = now
            }
            true
        }
        if (!created) {
            throw InsufficientStarsException(balanceOf(userId), total)
        }
        return dbQuery { toDto(Campaigns.selectAll().where { Campaigns.id eq id }.single()) }
    }

    /**
     * Strictly validate advertiser-supplied targeting. Nothing invalid is silently
     * dropped or clamped: the client receives a validation error while the existing
     * audience UI/request shape remains unchanged.
     */
    private fun normalizeAudience(raw: AudienceDto?): AudienceDto {
        val a = raw ?: return AudienceDto()
        val mode = a.mode.trim().uppercase(Locale.ROOT)
        require(mode == "AUTO" || mode == "CUSTOM") { "unknown audience mode" }

        if (mode == "AUTO") {
            require(a.countries.isEmpty()) { "AUTO audience cannot include countries" }
            require(a.ageMin == null && a.ageMax == null) { "AUTO audience cannot include age filters" }
            require(a.gender == null) { "AUTO audience cannot include gender filter" }
            require(a.interests.isEmpty()) { "AUTO audience cannot include interests" }
            return AudienceDto()
        }

        require(a.countries.size <= 20) { "maximum 20 countries" }
        val countries = a.countries.map {
            val code = it.trim().uppercase(Locale.ROOT)
            require(code.matches(Regex("^[A-Z]{2}$"))) { "invalid country code" }
            require(ISO_COUNTRIES.contains(code)) { "invalid country code" }
            code
        }.distinct()

        val ageMin = a.ageMin
        val ageMax = a.ageMax
        require(ageMin == null || ageMin in 13..100) { "ageMin must be between 13 and 100" }
        require(ageMax == null || ageMax in 13..100) { "ageMax must be between 13 and 100" }
        require(ageMin == null || ageMax == null || ageMin <= ageMax) { "ageMin must be <= ageMax" }

        val gender = a.gender?.trim()?.uppercase(Locale.ROOT)
        require(gender == null || gender == "MALE" || gender == "FEMALE") { "invalid gender" }

        require(a.interests.size <= 8) { "maximum 8 interests" }
        val interests = a.interests.map {
            val interest = it.trim().uppercase(Locale.ROOT)
            require(interest in INTERESTS) { "invalid interest" }
            interest
        }.distinct()

        return AudienceDto("CUSTOM", countries, ageMin, ageMax, gender, interests)
    }

    private fun audienceOf(row: ResultRow): AudienceDto = AudienceDto(
        mode = row[Campaigns.audienceMode],
        countries = row[Campaigns.targetCountries]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        ageMin = row[Campaigns.ageMin],
        ageMax = row[Campaigns.ageMax],
        gender = row[Campaigns.targetGender],
        interests = row[Campaigns.targetInterests]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    )

    suspend fun myCampaigns(userId: UUID): List<CampaignDto> {
        val rows = dbQuery {
            Campaigns.selectAll().where { Campaigns.ownerId eq userId }
                .orderBy(Campaigns.createdAt, SortOrder.DESC).limit(100).toList()
        }
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[Campaigns.id].value }
        val clickCount = SponsoredEvents.campaignId.count()
        val clicks = dbQuery {
            SponsoredEvents.select(SponsoredEvents.campaignId, clickCount)
                .where { (SponsoredEvents.campaignId inList ids) and (SponsoredEvents.kind eq "CLICK") }
                .groupBy(SponsoredEvents.campaignId)
                .associate { it[SponsoredEvents.campaignId] to it[clickCount] }
        }
        val refunds = dbQuery {
            StarLedgerEntries.select(StarLedgerEntries.referenceId, StarLedgerEntries.delta)
                .where {
                    (StarLedgerEntries.referenceId inList ids.map { it.toString() }) and
                        (StarLedgerEntries.reason eq "CAMPAIGN_REFUND")
                }.groupingBy { it[StarLedgerEntries.referenceId] }
                .fold(0L) { acc, e -> acc + e[StarLedgerEntries.delta] }
        }
        return rows.map { toDto(it, clicks[it[Campaigns.id].value] ?: 0L, refunds[it[Campaigns.id].value.toString()] ?: 0L) }
    }

    // ---------------------------------------------------------------- management: pause / resume / cancel

    private suspend fun ownedCampaign(userId: UUID, campaignId: UUID): ResultRow = dbQuery {
        Campaigns.selectAll().where { (Campaigns.id eq campaignId) and (Campaigns.ownerId eq userId) }
            .singleOrNull()
    } ?: throw NoSuchElementException("campaign not found")

    /** Pause an ACTIVE campaign. While paused it never serves and its clock is frozen. */
    suspend fun pauseCampaign(userId: UUID, campaignId: UUID): CampaignDto {
        val row = ownedCampaign(userId, campaignId)
        require(row[Campaigns.status] == "ACTIVE") { "only an active campaign can be paused" }
        dbQuery {
            Campaigns.update({ Campaigns.id eq campaignId }) {
                it[status] = "PAUSED"; it[pausedAt] = LocalDateTime.now()
            }
        }
        return campaignDto(userId, campaignId)
    }

    /** Resume a PAUSED campaign — endsAt shifts forward by the paused duration. */
    suspend fun resumeCampaign(userId: UUID, campaignId: UUID): CampaignDto {
        val row = ownedCampaign(userId, campaignId)
        require(row[Campaigns.status] == "PAUSED") { "only a paused campaign can be resumed" }
        dbQuery {
            val pausedAt = row[Campaigns.pausedAt] ?: LocalDateTime.now()
            val pausedSeconds = java.time.Duration.between(pausedAt, LocalDateTime.now()).seconds.coerceAtLeast(0)
            Campaigns.update({ Campaigns.id eq campaignId }) {
                it[status] = "ACTIVE"; it[Campaigns.pausedAt] = null
                it[endsAt] = row[Campaigns.endsAt].plusSeconds(pausedSeconds)
            }
        }
        return campaignDto(userId, campaignId)
    }

    /**
     * Cancel an ACTIVE or PAUSED campaign and refund the unused FULL days pro-rata.
     * The refund is a ledger entry guarded by a unique idempotency key, so a double
     * cancel can never refund twice.
     */
    suspend fun cancelCampaign(userId: UUID, campaignId: UUID): CampaignDto {
        val row = ownedCampaign(userId, campaignId)
        ensureWallet(userId) // idempotent; outside the transaction (it is suspend)
        dbQuery {
            val status = row[Campaigns.status]
            require(status == "ACTIVE" || status == "PAUSED") { "this campaign can no longer be canceled" }
            val effectiveNow = row[Campaigns.pausedAt] ?: LocalDateTime.now()
            val totalDays = row[Campaigns.days].toLong()
            val usedSeconds = java.time.Duration.between(row[Campaigns.startsAt], effectiveNow).seconds.coerceAtLeast(0)
            val usedDays = (usedSeconds / 86_400) + 1 // the day that started is consumed
            val unusedDays = (totalDays - usedDays).coerceIn(0, totalDays)
            val refund = unusedDays * row[Campaigns.starsPerDay]
            Campaigns.update({ Campaigns.id eq campaignId }) {
                it[Campaigns.status] = "CANCELED"; it[Campaigns.pausedAt] = null
            }
            if (refund > 0) {
                StarWallets.update({ StarWallets.userId eq userId }) {
                    it[balanceStars] = balanceStars + refund; it[updatedAt] = LocalDateTime.now()
                }
                runCatching {
                    StarLedgerEntries.insert {
                        it[StarLedgerEntries.userId] = userId
                        it[delta] = refund
                        it[reason] = "CAMPAIGN_REFUND"
                        it[referenceId] = campaignId.toString()
                        it[idempotencyKey] = "campaign_refund:$campaignId"
                        it[createdAt] = LocalDateTime.now()
                    }
                }
            }
        }
        return campaignDto(userId, campaignId)
    }

    private suspend fun campaignDto(userId: UUID, campaignId: UUID): CampaignDto =
        myCampaigns(userId).first { it.id == campaignId.toString() }

    /** Full advertiser analytics for one owned campaign (deduped funnel). */
    suspend fun analytics(userId: UUID, campaignId: UUID): CampaignAnalyticsDto {
        val row = ownedCampaign(userId, campaignId)
        val kindCount = SponsoredEvents.kind.count()
        val counts = dbQuery {
            SponsoredEvents.select(SponsoredEvents.kind, kindCount)
                .where { SponsoredEvents.campaignId eq campaignId }
                .groupBy(SponsoredEvents.kind)
                .associate { it[SponsoredEvents.kind] to it[kindCount] }
        }
        fun k(kind: String) = counts[kind] ?: 0L
        val impressions = k("IMPRESSION")
        val clicks = k("CLICK")
        val refunds = dbQuery {
            StarLedgerEntries.select(StarLedgerEntries.delta).where {
                (StarLedgerEntries.referenceId eq campaignId.toString()) and
                    (StarLedgerEntries.reason eq "CAMPAIGN_REFUND")
            }.sumOf { it[StarLedgerEntries.delta] }
        }
        return CampaignAnalyticsDto(
            campaignId = campaignId.toString(),
            impressions = impressions,
            clicks = clicks,
            profileVisits = k("PROFILE_VISIT"),
            follows = k("FOLLOW"),
            destinationClicks = k("DESTINATION_CLICK"),
            ctr = if (impressions > 0) clicks.toDouble() / impressions else 0.0,
            uniqueViewers = impressions, // impressions are deduped per viewer = unique reach
            starsSpent = row[Campaigns.totalStars] - refunds,
            status = row[Campaigns.status]
        )
    }

    private fun toDto(row: ResultRow, clicks: Long = 0, refundedStars: Long = 0) = CampaignDto(
        id = row[Campaigns.id].value.toString(),
        type = row[Campaigns.type],
        postId = row[Campaigns.postId]?.toString(),
        seriesId = row[Campaigns.seriesId]?.toString(),
        linkUrl = row[Campaigns.linkUrl],
        linkAction = row[Campaigns.linkAction],
        tierId = row[Campaigns.tierId],
        minReach = row[Campaigns.minReach],
        maxReach = if (row[Campaigns.maxReach] == Long.MAX_VALUE) -1 else row[Campaigns.maxReach],
        starsPerDay = row[Campaigns.starsPerDay],
        days = row[Campaigns.days],
        totalStars = row[Campaigns.totalStars],
        status = row[Campaigns.status],
        servedImpressions = row[Campaigns.servedImpressions],
        clicks = clicks,
        refundedStars = refundedStars,
        audience = audienceOf(row),
        startsAt = row[Campaigns.startsAt].toString(),
        endsAt = row[Campaigns.endsAt].toString()
    )

    // ---------------------------------------------------------------- series

    suspend fun createSeries(userId: UUID, req: CreateSeriesRequest, idempotencyKey: String): SeriesDto {
        dbQuery {
            VideoSeries.selectAll().where { VideoSeries.idempotencyKey eq idempotencyKey }.singleOrNull()
        }?.let { return seriesDto(it) }

        val name = req.name.trim().take(50)
        require(name.isNotEmpty()) { "series name is required" }
        val description = req.description.trim().take(200)
        val postIds = req.postIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.distinct()
        require(postIds.isNotEmpty()) { "select at least one video" }
        val days = req.days.coerceIn(1, MAX_DAYS)
        val owned = dbQuery {
            Posts.select(Posts.id).where {
                (Posts.id inList postIds) and (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
            }.count()
        }
        require(owned.toInt() == postIds.size) { "only your own published videos can join a series" }
        val total = SERIES_COST_PER_DAY_STARS * days

        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        val created = dbQuery {
            ensureWallet(userId)
            val debited = StarWallets.update({
                (StarWallets.userId eq userId) and (StarWallets.balanceStars greaterEq total)
            }) {
                it[balanceStars] = balanceStars - total; it[updatedAt] = now
            }
            if (debited == 0) return@dbQuery false
            StarLedgerEntries.insert {
                it[StarLedgerEntries.userId] = userId; it[delta] = -total
                it[reason] = "SERIES_SPEND"; it[referenceId] = id.toString()
                it[StarLedgerEntries.idempotencyKey] = "series:$idempotencyKey"; it[createdAt] = now
            }
            VideoSeries.insert {
                it[VideoSeries.id] = id; it[ownerId] = userId; it[VideoSeries.name] = name
                it[VideoSeries.description] = description; it[costPerDayStars] = SERIES_COST_PER_DAY_STARS
                it[status] = "ACTIVE"; it[activeUntil] = now.plusDays(days.toLong())
                it[VideoSeries.idempotencyKey] = idempotencyKey; it[createdAt] = now
            }
            postIds.forEachIndexed { index, pid ->
                SeriesItems.insert {
                    it[seriesId] = id; it[postId] = pid; it[position] = index
                }
            }
            true
        }
        if (!created) throw InsufficientStarsException(balanceOf(userId), total)
        return dbQuery { seriesDto(VideoSeries.selectAll().where { VideoSeries.id eq id }.single()) }
    }

    suspend fun mySeries(userId: UUID): List<SeriesDto> = dbQuery {
        VideoSeries.selectAll().where { VideoSeries.ownerId eq userId }
            .orderBy(VideoSeries.createdAt, SortOrder.DESC).limit(50).map { seriesDto(it) }
    }

    private fun seriesDto(row: ResultRow): SeriesDto {
        val id = row[VideoSeries.id].value
        val items = SeriesItems.selectAll().where { SeriesItems.seriesId eq id }
            .orderBy(SeriesItems.position, SortOrder.ASC).toList()
        val thumb = items.firstOrNull()?.get(SeriesItems.postId)?.let { pid -> signedUrls?.sign(pid, "THUMBNAIL") }
        return SeriesDto(
            id = id.toString(), name = row[VideoSeries.name], description = row[VideoSeries.description],
            costPerDayStars = row[VideoSeries.costPerDayStars], videoCount = items.size,
            status = row[VideoSeries.status], activeUntil = row[VideoSeries.activeUntil].toString(),
            coverThumbnailUrl = thumb, categoryLabel = items.size.toString() + " videos"
        )
    }

    // ---------------------------------------------------------------- sponsored delivery

    /**
     * Campaigns eligible to be shown to this viewer right now. Eligibility =
     * active window + remaining unique reach + not the owner + no block pair +
     * audience targeting (country / age / gender from the viewer's profile,
     * interests matched against the boosted post's hashtags). Targeting is
     * enforced here, in SQL + server code — never trusted to the client.
     */
    suspend fun activeSponsoredForViewer(viewerId: UUID, limit: Int): List<ResultRow> {
        val now = LocalDateTime.now()
        val blocked = dbQuery {
            BlockedUsers.selectAll().where {
                (BlockedUsers.blockerId eq viewerId) or (BlockedUsers.blockedId eq viewerId)
            }.flatMap { listOf(it[BlockedUsers.blockerId], it[BlockedUsers.blockedId]) }.toSet() - viewerId
        }
        // Viewer attributes used by targeting. Viewers with unset attributes match
        // everything (an advertiser's filter never excludes unknowns).
        val viewer = dbQuery {
            Users.select(Users.phoneCountryCode, Users.gender, Users.dateOfBirth)
                .where { Users.id eq viewerId }.singleOrNull()
        }
        val viewerCountry = viewer?.get(Users.phoneCountryCode)?.let { DIAL_TO_COUNTRY[it] }
        val viewerGender = viewer?.get(Users.gender)?.uppercase()
        val viewerAge = viewer?.get(Users.dateOfBirth)?.let {
            java.time.Period.between(it.toLocalDate(), now.toLocalDate()).years
        }
        val candidates = dbQuery {
            Campaigns.selectAll().where {
                (Campaigns.status eq "ACTIVE") and (Campaigns.endsAt greaterEq now) and
                    (Campaigns.servedImpressions less Campaigns.maxReach) and
                    (Campaigns.ownerId neq viewerId) and Campaigns.postId.isNotNull()
            }.orderBy(Campaigns.startsAt, SortOrder.DESC).limit(40).toList()
        }.filter { it[Campaigns.ownerId] !in blocked }

        val targeted = candidates.filter { matchesAudience(it, viewerCountry, viewerGender, viewerAge) }
        // Interest targeting compares against the boosted post's hashtags — one
        // batched lookup for the surviving candidates.
        val needInterests = targeted.filter {
            !it[Campaigns.targetInterests].isNullOrBlank()
        }
        if (needInterests.isEmpty()) return targeted.shuffled().take(limit)
        val hashtagsByPost = dbQuery {
            Posts.select(Posts.id, Posts.hashtags).where {
                Posts.id inList needInterests.mapNotNull { it[Campaigns.postId] }
            }.associate { it[Posts.id].value to (it[Posts.hashtags] ?: "") }
        }
        return targeted.filter { row ->
            val interests = row[Campaigns.targetInterests]?.split(",")?.filter { it.isNotBlank() } ?: return@filter true
            val tags = hashtagsByPost[row[Campaigns.postId]]?.lowercase() ?: return@filter false
            interests.any { tags.contains(it.lowercase()) }
        }.shuffled().take(limit)
    }

    private fun matchesAudience(row: ResultRow, viewerCountry: String?, viewerGender: String?, viewerAge: Int?): Boolean {
        if (row[Campaigns.audienceMode] != "CUSTOM") return true
        val countries = row[Campaigns.targetCountries]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        // Unknown viewer attributes are treated as matching (never silently excluded).
        if (countries.isNotEmpty() && viewerCountry != null && viewerCountry !in countries) return false
        row[Campaigns.ageMin]?.let { min -> if (viewerAge != null && viewerAge < min) return false }
        row[Campaigns.ageMax]?.let { max -> if (viewerAge != null && viewerAge > max) return false }
        row[Campaigns.targetGender]?.let { g -> if (viewerGender != null && viewerGender != g) return false }
        return true
    }

    /**
     * Records a sponsored event only when the server can prove that this viewer was
     * actually delivered the campaign. IMPRESSIONs additionally consume one unit of
     * unique reach with an atomic SQL increment, so concurrent viewers cannot both
     * overshoot the reach cap. The client is never trusted to supply the delivery proof.
     */
    suspend fun recordEvent(viewerId: UUID, campaignId: UUID, kind: String) {
        if (kind !in EVENT_KINDS) return
        dbQuery {
            val now = LocalDateTime.now()
            val campaign = Campaigns.selectAll().where { Campaigns.id eq campaignId }.singleOrNull()
                ?: return@dbQuery
            if (campaign[Campaigns.status] != "ACTIVE") return@dbQuery

            // A campaign ID alone is not proof of delivery. FeedService creates this
            // server-side record only after the sponsored item has actually been
            // selected and validated for this viewer. Expire stale proofs so an old
            // feed response cannot be replayed much later.
            SponsoredDeliveries.selectAll().where {
                (SponsoredDeliveries.campaignId eq campaignId) and
                    (SponsoredDeliveries.viewerId eq viewerId) and
                    (SponsoredDeliveries.deliveredAt greaterEq now.minusMinutes(30))
            }.singleOrNull() ?: return@dbQuery

            // Secondary events must follow a real impression. This prevents a client
            // from fabricating clicks/follows/profile visits by knowing only a campaign ID.
            if (kind != "IMPRESSION") {
                SponsoredEvents.selectAll().where {
                    (SponsoredEvents.campaignId eq campaignId) and
                        (SponsoredEvents.viewerId eq viewerId) and
                        (SponsoredEvents.kind eq "IMPRESSION")
                }.singleOrNull() ?: return@dbQuery
            }

            if (kind == "IMPRESSION") {
                // Insert the dedupe key first. A duplicate impression does not consume
                // another reach unit. A unique-index conflict is expected under retries.
                val inserted = SponsoredEvents.insertIgnore {
                    it[SponsoredEvents.campaignId] = campaignId
                    it[SponsoredEvents.viewerId] = viewerId
                    it[SponsoredEvents.kind] = kind
                    it[createdAt] = now
                }.insertedCount > 0
                if (!inserted) return@dbQuery

                // IMPORTANT: increment the counter in SQL, not by read-modify-write.
                // The WHERE clause makes the reach cap part of the same atomic update.
                val updated = Campaigns.update({
                    (Campaigns.id eq campaignId) and
                        (Campaigns.status eq "ACTIVE") and
                        (Campaigns.servedImpressions less Campaigns.maxReach)
                }) {
                    it[servedImpressions] = Campaigns.servedImpressions + 1
                }

                // If another concurrent impression reached the cap first, rollback the
                // event insert too. The transaction must remain all-or-nothing.
                if (updated != 1) {
                    throw IllegalStateException("sponsored impression lost reach race")
                }

                // Mark exhaustion in a separate statement after the atomic increment.
                // A concurrent increment cannot pass the `less maxReach` predicate once
                // the cap is reached, so the counter itself can never overshoot.
                Campaigns.update({
                    (Campaigns.id eq campaignId) and
                        (Campaigns.status eq "ACTIVE") and
                        (Campaigns.servedImpressions greaterEq Campaigns.maxReach)
                }) {
                    it[status] = "EXHAUSTED"
                }
            } else {
                // Secondary events remain deduped per viewer/kind. They do not affect reach.
                SponsoredEvents.insertIgnore {
                    it[SponsoredEvents.campaignId] = campaignId
                    it[SponsoredEvents.viewerId] = viewerId
                    it[SponsoredEvents.kind] = kind
                    it[createdAt] = now
                }
            }
        }
    }

    /**
     * Creates server-side proof that a sponsored item was actually delivered to the
     * viewer. This is called only after FeedService has validated the final post DTO.
     */
    suspend fun markSponsoredDelivery(viewerId: UUID, campaignId: UUID): Boolean =
        dbQuery {
            val now = LocalDateTime.now()
            val campaign = Campaigns.selectAll().where { Campaigns.id eq campaignId }.singleOrNull()
                ?: return@dbQuery false
            if (campaign[Campaigns.status] != "ACTIVE" ||
                campaign[Campaigns.endsAt] < now ||
                campaign[Campaigns.servedImpressions] >= campaign[Campaigns.maxReach]
            ) return@dbQuery false

            // Re-check the final delivery boundary inside the same server-side
            // proof operation. FeedService already performs these checks, but this
            // second check closes races where post visibility/status changes between
            // feed construction and proof creation.
            val postId = campaign[Campaigns.postId] ?: return@dbQuery false
            val post = Posts.selectAll().where {
                (Posts.id eq postId) and
                    (Posts.status eq "PUBLISHED") and
                    (Posts.privacy eq "PUBLIC") and
                    (Posts.ownerId neq viewerId)
            }.singleOrNull() ?: return@dbQuery false
            if (post[Posts.status] != "PUBLISHED" || post[Posts.privacy] != "PUBLIC") return@dbQuery false

            SponsoredDeliveries.insertIgnore {
                it[SponsoredDeliveries.campaignId] = campaignId
                it[SponsoredDeliveries.viewerId] = viewerId
                it[deliveredAt] = now
            }
            // Refresh the proof on every real delivery so a previously delivered
            // campaign can be interacted with after the 30-minute replay window.
            SponsoredDeliveries.update({
                (SponsoredDeliveries.campaignId eq campaignId) and
                    (SponsoredDeliveries.viewerId eq viewerId)
            }) {
                it[deliveredAt] = now
            }
            true
        }

    /** Time-based expiry sweep — campaigns past their window and series past their paid day(s). */
    suspend fun sweepExpired() {
        val now = LocalDateTime.now()
        dbQuery {
            Campaigns.update({ (Campaigns.status eq "ACTIVE") and (Campaigns.endsAt less now) }) {
                it[status] = "EXPIRED"
            }
            VideoSeries.update({ (VideoSeries.status eq "ACTIVE") and (VideoSeries.activeUntil less now) }) {
                it[status] = "EXPIRED"
            }
            // Delivery proofs are short-lived security state, not analytics history.
            SponsoredDeliveries.deleteWhere {
                SponsoredDeliveries.deliveredAt less now.minusDays(1)
            }
        }
    }

    private fun ensureWallet(userId: UUID) {
        if (StarWallets.selectAll().where { StarWallets.userId eq userId }.none()) {
            runCatching {
                StarWallets.insert {
                    it[StarWallets.userId] = userId; it[balanceStars] = 0; it[updatedAt] = LocalDateTime.now()
                }
            }
        }
    }

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
