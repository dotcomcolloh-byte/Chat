package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

// ---------- Wire DTOs mirroring backend CampaignService exactly ----------

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
    val provider: String,
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
    val status: String, // PENDING | PAID | FAILED
    val balanceStars: Long,
    val failureReason: String? = null
)

@Serializable
data class CampaignTierDto(
    val id: String,
    val minReach: Long,
    val maxReach: Long, // -1 = unbounded ("1M+")
    val starsPerDay: Long
) {
    val reachLabel: String
        get() {
            fun fmt(n: Long): String = when {
                n >= 1_000_000 -> if (n % 1_000_000 == 0L) "${n / 1_000_000}M" else "%.1fM".format(n / 1_000_000.0)
                n >= 1_000 -> if (n % 1_000 == 0L) "%,d".format(n) else "%,d".format(n)
                else -> n.toString()
            }
            return if (maxReach < 0) "${fmt(minReach)} - 1M+" else "${fmt(minReach)} - ${fmt(maxReach)}"
        }
}

@Serializable
data class CampaignConfigDto(
    val balanceStars: Long,
    val tiers: Map<String, List<CampaignTierDto>>,
    val seriesCostPerDayStars: Long,
    val maxDays: Int
)

@Serializable
data class AudienceDto(
    val mode: String = "AUTO", // AUTO | CUSTOM
    val countries: List<String> = emptyList(),
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val gender: String? = null, // MALE | FEMALE | null = all
    val interests: List<String> = emptyList()
) {
    val isAutomatic: Boolean get() = mode == "AUTO"

    fun summary(): String {
        if (isAutomatic) return "Automatic — Telefam picks the most relevant viewers"
        val parts = mutableListOf<String>()
        if (countries.isNotEmpty()) parts.add(countries.take(3).joinToString(", ") + if (countries.size > 3) " +${countries.size - 3}" else "")
        if (ageMin != null || ageMax != null) parts.add("Age ${ageMin ?: 13}–${ageMax ?: 65}")
        gender?.let { parts.add(it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
        if (interests.isNotEmpty()) parts.add(interests.take(2).joinToString(", ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } } + if (interests.size > 2) " +${interests.size - 2}" else "")
        return if (parts.isEmpty()) "Custom audience" else parts.joinToString(" · ")
    }
}

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
    val servedImpressions: Long,
    val clicks: Long = 0,
    val refundedStars: Long = 0,
    val audience: AudienceDto = AudienceDto(),
    val startsAt: String,
    val endsAt: String
) {
    val reachLabel: String
        get() {
            fun fmt(n: Long): String = when {
                n >= 1_000_000 -> if (n % 1_000_000 == 0L) "${n / 1_000_000}M" else "%.1fM".format(n / 1_000_000.0)
                else -> "%,d".format(n)
            }
            return if (maxReach < 0) "${fmt(minReach)} – 1M+" else "${fmt(minReach)} – ${fmt(maxReach)}"
        }
    val isRunning: Boolean get() = status == "ACTIVE" || status == "PAUSED"
}

@Serializable
data class CampaignAnalyticsDto(
    val campaignId: String,
    val impressions: Long,
    val clicks: Long,
    val profileVisits: Long,
    val follows: Long,
    val destinationClicks: Long,
    val ctr: Double,
    val uniqueViewers: Long,
    val starsSpent: Long,
    val status: String
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

@Serializable
private data class CreateCampaignBody(
    val type: String,
    val postId: String,
    val tierId: String,
    val days: Int,
    val linkUrl: String? = null,
    val linkAction: String? = null,
    val audience: AudienceDto? = null
)

@Serializable
private data class CreateSeriesBody(
    val name: String,
    val description: String,
    val postIds: List<String>,
    val days: Int
)

@Serializable
private data class PurchaseBody(val packageId: String)

@Serializable
private data class SponsoredEventBody(val kind: String)

/** Result of a spend attempt — 402 means the wallet is short and the UI opens Buy Stars. */
sealed class SpendResult<out T> {
    data class Ok<T>(val value: T) : SpendResult<T>()
    data class InsufficientStars(val balanceStars: Long, val requiredStars: Long) : SpendResult<Nothing>()
    data class Failed(val message: String) : SpendResult<Nothing>()
}

/** Client for the Stars store and paid campaign endpoints. */
class CampaignApi(private val client: HttpClient) {
    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun balance(): StarBalanceDto = client.get("$base/api/stars/balance").body()

    suspend fun packages(): StarPackagesDto = client.get("$base/api/stars/packages").body()

    suspend fun initiatePurchase(packageId: String, idempotencyKey: String): InitiatedStarPurchaseDto =
        client.post("$base/api/stars/purchase") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(PurchaseBody(packageId))
        }.body()

    suspend fun confirmPurchase(purchaseId: String): StarPurchaseStatusDto =
        client.post("$base/api/stars/purchase/$purchaseId/confirm").body()

    suspend fun config(): CampaignConfigDto = client.get("$base/api/campaigns/config").body()

    suspend fun myCampaigns(): List<CampaignDto> = client.get("$base/api/campaigns/mine").body()

    suspend fun mySeries(): List<SeriesDto> = client.get("$base/api/campaigns/series").body()

    suspend fun recordSponsoredEvent(campaignId: String, kind: String) {
        client.post("$base/api/campaigns/$campaignId/event") {
            contentType(ContentType.Application.Json)
            setBody(SponsoredEventBody(kind))
        }
    }

    suspend fun analytics(campaignId: String): CampaignAnalyticsDto =
        client.get("$base/api/campaigns/$campaignId/analytics").body()

    suspend fun pauseCampaign(campaignId: String): CampaignDto =
        client.post("$base/api/campaigns/$campaignId/pause").body()

    suspend fun resumeCampaign(campaignId: String): CampaignDto =
        client.post("$base/api/campaigns/$campaignId/resume").body()

    suspend fun cancelCampaign(campaignId: String): CampaignDto =
        client.post("$base/api/campaigns/$campaignId/cancel").body()

    suspend fun createCampaign(
        type: String, postId: String, tierId: String, days: Int,
        linkUrl: String?, linkAction: String?, audience: AudienceDto?, idempotencyKey: String
    ): SpendResult<CampaignDto> {
        val res: HttpResponse = client.post("$base/api/campaigns") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(CreateCampaignBody(type, postId, tierId, days, linkUrl, linkAction, audience))
        }
        return when (res.status) {
            HttpStatusCode.Created, HttpStatusCode.OK -> SpendResult.Ok(res.body())
            HttpStatusCode.PaymentRequired -> SpendResult.InsufficientStars(
                balanceStars = res.headers["x-balance-stars"]?.toLongOrNull()
                    ?: parseBodyLong(res, "balanceStars") ?: 0L,
                requiredStars = parseBodyLong(res, "requiredStars") ?: 0L
            )
            else -> SpendResult.Failed("HTTP ${res.status.value}")
        }
    }

    suspend fun createSeries(
        name: String, description: String, postIds: List<String>, days: Int, idempotencyKey: String
    ): SpendResult<SeriesDto> {
        val res: HttpResponse = client.post("$base/api/campaigns/series") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idempotencyKey)
            setBody(CreateSeriesBody(name, description, postIds, days))
        }
        return when (res.status) {
            HttpStatusCode.Created, HttpStatusCode.OK -> SpendResult.Ok(res.body())
            HttpStatusCode.PaymentRequired -> SpendResult.InsufficientStars(
                balanceStars = parseBodyLong(res, "balanceStars") ?: 0L,
                requiredStars = parseBodyLong(res, "requiredStars") ?: 0L
            )
            else -> SpendResult.Failed("HTTP ${res.status.value}")
        }
    }

    private suspend fun parseBodyLong(res: HttpResponse, key: String): Long? = runCatching {
        Regex("\"$key\"\\s*:\\s*\"?(\\d+)\"?").find(res.bodyAsText())?.groupValues?.get(1)?.toLong()
    }.getOrNull()
}
