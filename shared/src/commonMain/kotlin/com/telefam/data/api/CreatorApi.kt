package com.telefam.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

// ---------- Wire DTOs mirroring backend CreatorService responses exactly ----------

@kotlinx.serialization.Serializable
data class MetricDeltaDto(val value: Long, val deltaPercent: Double? = null)

@kotlinx.serialization.Serializable
data class DashboardOverviewDto(
    val views: MetricDeltaDto,
    val engagement: MetricDeltaDto,
    val likes: MetricDeltaDto,
    val shares: MetricDeltaDto
)

@kotlinx.serialization.Serializable
data class ContentPerformanceItemDto(
    val postId: String,
    val description: String? = null,
    val publishedAt: String? = null,
    val views: Long,
    val likes: Long,
    val shares: Long
)

@kotlinx.serialization.Serializable
data class AudienceInsightsDto(
    val topCountries: List<String> = emptyList(),
    val topAgeRange: String? = null,
    val topActiveTime: String? = null
)

@kotlinx.serialization.Serializable
data class DashboardDto(
    val periodDays: Int,
    val overview: DashboardOverviewDto,
    val topContent: List<ContentPerformanceItemDto> = emptyList(),
    val audience: AudienceInsightsDto = AudienceInsightsDto()
)

@kotlinx.serialization.Serializable
data class AnalyticsPointDto(val date: String, val views: Long, val likes: Long, val shares: Long)

@kotlinx.serialization.Serializable
data class AnalyticsDto(
    val periodDays: Int,
    val totals: DashboardOverviewDto,
    val series: List<AnalyticsPointDto> = emptyList()
)

@kotlinx.serialization.Serializable
data class EligibilityRequirementDto(
    val key: String,
    val title: String,
    val subtitle: String,
    /** MET | NOT_MET | IN_PROGRESS */
    val status: String,
    val current: Long = 0,
    val target: Long = 0
)

@kotlinx.serialization.Serializable
data class EligibilityDto(
    val requirements: List<EligibilityRequirementDto>,
    val additionalCriteria: List<EligibilityRequirementDto>,
    val allMet: Boolean,
    val monetizationStatus: String // NONE | UNDER_REVIEW | APPROVED | REJECTED
)

@kotlinx.serialization.Serializable
data class MonetizationStatusDto(
    /** NONE | UNDER_REVIEW | APPROVED | REJECTED */
    val status: String,
    val submittedAt: String? = null,
    val reviewedAt: String? = null,
    val rejectionReasons: List<String> = emptyList(),
    val followersCurrent: Long = 0,
    val followersTarget: Long = 10000,
    val watchHoursCurrent: Long = 0,
    val watchHoursTarget: Long = 4000,
    val contentCompliance: String = "OK" // OK | NEEDS_IMPROVEMENT
)

@kotlinx.serialization.Serializable
data class StarsOverviewDto(
    val periodDays: Int,
    val totalEarningsCentsAllTime: Long,
    val totalStarsAllTime: Long,
    val earningsCents: Long,
    val starsReceived: Long,
    val supporters: Long,
    val dailyAverageCents: Long,
    val earningsDeltaPercent: Double? = null,
    val starsDeltaPercent: Double? = null,
    val supportersDeltaPercent: Double? = null,
    val dailyAvgDeltaPercent: Double? = null,
    val goalTitle: String? = null,
    val goalTargetStars: Long? = null,
    val goalProgressStars: Long = 0
)

@kotlinx.serialization.Serializable
data class StarTransactionDto(
    val id: String,
    val fromUserId: String,
    val fromName: String? = null,
    val fromUsername: String? = null,
    val fromAvatarUrl: String? = null,
    val fromVerified: Boolean = false,
    val stars: Long,
    val amountCents: Long,
    val createdAt: String
)

@kotlinx.serialization.Serializable
data class StarSupporterDto(
    val userId: String,
    val name: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
    val totalStars: Long,
    val giftCount: Long
)

/** Client for the creator-program endpoints (dashboard, monetization, stars). */
class CreatorApi(private val client: HttpClient) {
    private val base get() = ApiConfig.baseUrl.trimEnd('/')

    suspend fun dashboard(periodDays: Int): DashboardDto =
        client.get("$base/api/creator/dashboard") { parameter("periodDays", periodDays) }.body()

    suspend fun analytics(periodDays: Int): AnalyticsDto =
        client.get("$base/api/creator/analytics") { parameter("periodDays", periodDays) }.body()

    suspend fun contentPerformance(): List<ContentPerformanceItemDto> =
        client.get("$base/api/creator/content").body()

    suspend fun eligibility(): EligibilityDto =
        client.get("$base/api/creator/monetization/eligibility").body()

    suspend fun monetizationStatus(): MonetizationStatusDto =
        client.get("$base/api/creator/monetization/status").body()

    suspend fun applyForMonetization(): MonetizationStatusDto =
        client.post("$base/api/creator/monetization/apply").body()

    suspend fun starsOverview(periodDays: Int): StarsOverviewDto =
        client.get("$base/api/creator/stars/overview") { parameter("periodDays", periodDays) }.body()

    suspend fun starTransactions(limit: Int = 50): List<StarTransactionDto> =
        client.get("$base/api/creator/stars/transactions") { parameter("limit", limit) }.body()

    suspend fun starSupporters(): List<StarSupporterDto> =
        client.get("$base/api/creator/stars/supporters").body()

    suspend fun setStarGoal(title: String, targetStars: Long) {
        client.put("$base/api/creator/stars/goal") {
            contentType(ContentType.Application.Json)
            setBody(StarGoalRequest(title, targetStars))
        }
    }
}

@kotlinx.serialization.Serializable
data class StarGoalRequest(val title: String, val targetStars: Long)
