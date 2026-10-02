package com.telefam.creator

import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.RlsContext
import com.telefam.db.Users
import com.telefam.posts.Follows
import com.telefam.posts.PostLikes
import com.telefam.posts.PostReshares
import com.telefam.posts.PostViews
import com.telefam.posts.Posts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

// ---------- DTOs (wire format — mirrored by the shared KMP client) ----------

@Serializable
data class MetricDelta(val value: Long, val deltaPercent: Double? = null)

@Serializable
data class DashboardOverviewDto(
    val views: MetricDelta,
    val engagement: MetricDelta,
    val likes: MetricDelta,
    val shares: MetricDelta
)

@Serializable
data class ContentPerformanceItemDto(
    val postId: String,
    val description: String? = null,
    val publishedAt: String? = null,
    val views: Long,
    val likes: Long,
    val shares: Long
)

@Serializable
data class AudienceInsightsDto(
    val topCountries: List<String> = emptyList(),
    val topAgeRange: String? = null,
    val topActiveTime: String? = null
)

@Serializable
data class DashboardDto(
    val periodDays: Int,
    val overview: DashboardOverviewDto,
    val topContent: List<ContentPerformanceItemDto> = emptyList(),
    val audience: AudienceInsightsDto = AudienceInsightsDto()
)

@Serializable
data class AnalyticsPointDto(val date: String, val views: Long, val likes: Long, val shares: Long)

@Serializable
data class AnalyticsDto(
    val periodDays: Int,
    val totals: DashboardOverviewDto,
    val series: List<AnalyticsPointDto> = emptyList()
)

@Serializable
data class EligibilityRequirementDto(
    val key: String,
    val title: String,
    val subtitle: String,
    /** MET | NOT_MET | IN_PROGRESS */
    val status: String,
    val current: Long = 0,
    val target: Long = 0
)

@Serializable
data class EligibilityDto(
    val requirements: List<EligibilityRequirementDto>,
    val additionalCriteria: List<EligibilityRequirementDto>,
    val allMet: Boolean,
    val monetizationStatus: String // NONE | UNDER_REVIEW | APPROVED | REJECTED
)

@Serializable
data class MonetizationStatusDto(
    /** NONE | UNDER_REVIEW | APPROVED | REJECTED */
    val status: String,
    val submittedAt: String? = null,
    val reviewedAt: String? = null,
    val rejectionReasons: List<String> = emptyList(),
    /** Progress snapshot captured at submission (for the "Your progress" section). */
    val followersCurrent: Long = 0,
    val followersTarget: Long = 10000,
    val watchHoursCurrent: Long = 0,
    val watchHoursTarget: Long = 4000,
    val contentCompliance: String = "OK" // OK | NEEDS_IMPROVEMENT
)

@Serializable
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

@Serializable
data class StarTransactionDto(
    val id: String,
    val fromUserId: String,
    val fromName: String?,
    val fromUsername: String? = null,
    val fromAvatarUrl: String? = null,
    val fromVerified: Boolean = false,
    val stars: Long,
    val amountCents: Long,
    val createdAt: String
)

@Serializable
data class StarSupporterDto(
    val userId: String,
    val name: String?,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
    val totalStars: Long,
    val giftCount: Long
)

@Serializable
data class StarGoalDto(val title: String, val targetStars: Long)

/**
 * Read-only creator analytics + monetization state. Owner-scoped: every query
 * reads only the requesting user's rows, and the new tables are RLS-enforced.
 */
class CreatorService(private val badgeService: com.telefam.verification.BadgeService = com.telefam.verification.BadgeService) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val FOLLOWERS_TARGET = 1000L
        const val WATCH_MINUTES_TARGET = 4000L
        const val PUBLIC_VIDEOS_TARGET = 10L
        const val USD_CENTS_PER_STAR = 1L
        val ALLOWED_PERIODS = setOf(7, 30, 365)
    }

    // ---------------------------------------------------------------- dashboard

    suspend fun dashboard(userId: UUID, periodDays: Int): DashboardDto {
        val period = if (periodDays in ALLOWED_PERIODS) periodDays else 7
        val overview = overview(userId, period)
        val content = topContent(userId, 3)
        val audience = audienceInsights(userId)
        return DashboardDto(period, overview, content, audience)
    }

    suspend fun contentPerformance(userId: UUID): List<ContentPerformanceItemDto> = topContent(userId, 100)

    suspend fun analytics(userId: UUID, periodDays: Int): AnalyticsDto {
        val period = if (periodDays in ALLOWED_PERIODS) periodDays else 7
        val totals = overview(userId, period)
        val now = LocalDateTime.now()
        val start = now.minusDays(period.toLong())
        val postIds = ownedPostIds(userId)
        if (postIds.isEmpty()) return AnalyticsDto(period, totals)
        val series = dbQuery {
            val views = PostViews.select(PostViews.viewedAt, PostViews.postId)
                .where { (PostViews.postId inList postIds) and (PostViews.viewedAt greaterEq start) }
                .map { it[PostViews.viewedAt].toLocalDate() to it[PostViews.postId] }
            val likes = PostLikes.select(PostLikes.createdAt).where {
                (PostLikes.postId inList postIds) and (PostLikes.createdAt greaterEq start)
            }.map { it[PostLikes.createdAt].toLocalDate() }
            val shares = PostReshares.select(PostReshares.createdAt).where {
                (PostReshares.postId inList postIds) and (PostReshares.createdAt greaterEq start)
            }.map { it[PostReshares.createdAt].toLocalDate() }
            val v = views.groupingBy { it.first }.eachCount()
            val l = likes.groupingBy { it }.eachCount()
            val s = shares.groupingBy { it }.eachCount()
            (0 until period).map { now.minusDays((period - 1 - it).toLong()).toLocalDate() }
                .map { d -> AnalyticsPointDto(d.toString(), (v[d] ?: 0).toLong(), (l[d] ?: 0).toLong(), (s[d] ?: 0).toLong()) }
        }
        return AnalyticsDto(period, totals, series)
    }

    private suspend fun ownedPostIds(userId: UUID): List<UUID> = dbQuery {
        Posts.select(Posts.id).where {
            (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
        }.map { it[Posts.id].value }
    }

    private fun delta(current: Long, previous: Long): Double? = when {
        previous > 0 -> ((current - previous).toDouble() / previous.toDouble()) * 100.0
        else -> null // no baseline — UI shows "— 0%"
    }

    private suspend fun overview(userId: UUID, periodDays: Int): DashboardOverviewDto {
        val now = LocalDateTime.now()
        val start = now.minusDays(periodDays.toLong())
        val prevStart = start.minusDays(periodDays.toLong())
        val postIds = ownedPostIds(userId)
        if (postIds.isEmpty()) {
            val zero = MetricDelta(0, null)
            return DashboardOverviewDto(zero, zero, zero, zero)
        }
        return dbQuery {
            fun viewsBetween(from: LocalDateTime, to: LocalDateTime): Long =
                PostViews.select(PostViews.postId.count()).where {
                    (PostViews.postId inList postIds) and (PostViews.viewedAt greaterEq from) and
                        (PostViews.viewedAt less to)
                }.first()[PostViews.postId.count()]

            fun likesBetween(from: LocalDateTime, to: LocalDateTime): Long =
                PostLikes.select(PostLikes.postId.count()).where {
                    (PostLikes.postId inList postIds) and (PostLikes.createdAt greaterEq from) and
                        (PostLikes.createdAt less to)
                }.first()[PostLikes.postId.count()]

            fun sharesBetween(from: LocalDateTime, to: LocalDateTime): Long =
                PostReshares.select(PostReshares.postId.count()).where {
                    (PostReshares.postId inList postIds) and (PostReshares.createdAt greaterEq from) and
                        (PostReshares.createdAt less to)
                }.first()[PostReshares.postId.count()]

            val v = viewsBetween(start, now); val vPrev = viewsBetween(prevStart, start)
            val l = likesBetween(start, now); val lPrev = likesBetween(prevStart, start)
            val s = sharesBetween(start, now); val sPrev = sharesBetween(prevStart, start)
            DashboardOverviewDto(
                views = MetricDelta(v, delta(v, vPrev)),
                engagement = MetricDelta(l + s, delta(l + s, lPrev + sPrev)),
                likes = MetricDelta(l, delta(l, lPrev)),
                shares = MetricDelta(s, delta(s, sPrev))
            )
        }
    }

    private suspend fun topContent(userId: UUID, limit: Int): List<ContentPerformanceItemDto> = dbQuery {
        val posts = Posts.selectAll().where {
            (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
        }.orderBy(Posts.publishedAt, SortOrder.DESC).limit(limit).toList()
        posts.map { p ->
            val pid = p[Posts.id].value
            ContentPerformanceItemDto(
                postId = pid.toString(),
                description = p[Posts.description],
                publishedAt = p[Posts.publishedAt]?.toString(),
                views = PostViews.select(PostViews.postId.count()).where { PostViews.postId eq pid }
                    .first()[PostViews.postId.count()],
                likes = PostLikes.select(PostLikes.postId.count()).where { PostLikes.postId eq pid }
                    .first()[PostLikes.postId.count()],
                shares = PostReshares.select(PostReshares.postId.count()).where { PostReshares.postId eq pid }
                    .first()[PostReshares.postId.count()]
            )
        }
    }

    private suspend fun audienceInsights(userId: UUID): AudienceInsightsDto = dbQuery {
        val postIds = Posts.select(Posts.id).where {
            (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
        }.map { it[Posts.id].value }
        if (postIds.isEmpty()) return@dbQuery AudienceInsightsDto()
        val viewers = PostViews.select(PostViews.viewerId, PostViews.viewedAt)
            .where { PostViews.postId inList postIds }.toList()
        if (viewers.isEmpty()) return@dbQuery AudienceInsightsDto()

        val viewerIds = viewers.map { it[PostViews.viewerId] }.distinct()
        val users = Users.select(Users.id, Users.locationName, Users.dateOfBirth)
            .where { Users.id inList viewerIds }.toList()

        val topCountries = users.mapNotNull { u ->
            u[Users.locationName]?.substringAfterLast(",")?.trim()?.takeIf { it.isNotEmpty() }
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }

        val now = LocalDateTime.now()
        val topAgeRange = users.mapNotNull { it[Users.dateOfBirth] }
            .map { dob -> java.time.Period.between(dob.toLocalDate(), now.toLocalDate()).years }
            .map { age -> when (age) { in 0..17 -> "Under 18"; in 18..24 -> "18-24"; in 25..34 -> "25-34"; in 35..44 -> "35-44"; in 45..54 -> "45-54"; else -> "55+" } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        val topActiveTime = viewers.groupingBy { it[PostViews.viewedAt].hour / 4 }.eachCount()
            .maxByOrNull { it.value }?.key?.let { bucket ->
                val startH = bucket * 4
                "%02d:00 - %02d:00".format(startH, (startH + 4) % 24)
            }

        AudienceInsightsDto(topCountries, topAgeRange, topActiveTime)
    }

    // ---------------------------------------------------------------- monetization

    private suspend fun metrics(userId: UUID): Triple<Long, Long, Long> {
        val followers = dbQuery {
            Follows.select(Follows.followerId.count()).where { Follows.followeeId eq userId }
                .first()[Follows.followerId.count()]
        }
        val watchMinutes = dbQuery {
            val postIds = Posts.select(Posts.id).where {
                (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED")
            }.map { it[Posts.id].value }
            if (postIds.isEmpty()) 0L else
                PostViews.select(PostViews.watchedMs.sum()).where {
                    (PostViews.postId inList postIds) and
                        (PostViews.viewedAt greaterEq LocalDateTime.now().minusDays(60))
                }.first()[PostViews.watchedMs.sum()]?.let { it / 60000L } ?: 0L
        }
        val publicVideos = dbQuery {
            Posts.select(Posts.id.count()).where {
                (Posts.ownerId eq userId) and (Posts.status eq "PUBLISHED") and (Posts.privacy eq "PUBLIC")
            }.first()[Posts.id.count()]
        }
        return Triple(followers, watchMinutes, publicVideos)
    }

    private suspend fun hasUnresolvedReports(userId: UUID): Boolean = dbQuery {
        val postIds = Posts.select(Posts.id).where { Posts.ownerId eq userId }.map { it[Posts.id].value }
        if (postIds.isEmpty()) false else
            com.telefam.posts.PostReports.select(com.telefam.posts.PostReports.id)
                .where { com.telefam.posts.PostReports.postId inList postIds }.limit(1).any()
    }

    suspend fun eligibility(userId: UUID): EligibilityDto {
        val (followers, watchMinutes, publicVideos) = metrics(userId)
        val compliant = !hasUnresolvedReports(userId)
        val accountOk = dbQuery {
            Users.select(Users.accountStatus).where { Users.id eq userId }.first()[Users.accountStatus] == "ACTIVE"
        }
        val status = applicationStatus(userId).status
        val requirements = listOf(
            EligibilityRequirementDto("FOLLOWERS", "Followers", "Reach required number of followers",
                if (followers >= FOLLOWERS_TARGET) "MET" else "NOT_MET", followers, FOLLOWERS_TARGET),
            EligibilityRequirementDto("WATCH_TIME", "Watch Time (Last 60 Days)", "Get required watch time on your content",
                if (watchMinutes >= WATCH_MINUTES_TARGET) "MET" else "NOT_MET", watchMinutes, WATCH_MINUTES_TARGET),
            EligibilityRequirementDto("CONTENT", "Content", "Publish minimum number of public videos",
                if (publicVideos >= PUBLIC_VIDEOS_TARGET) "MET" else "NOT_MET", publicVideos, PUBLIC_VIDEOS_TARGET),
            EligibilityRequirementDto("GUIDELINES", "Community Guidelines", "Follow our Community Guidelines",
                if (compliant) "MET" else "NOT_MET"),
            EligibilityRequirementDto("ACCOUNT_STATUS", "Account Status", "Your account must be in good standing",
                if (accountOk) "MET" else "NOT_MET")
        )
        val additional = listOf(
            EligibilityRequirementDto("ORIGINAL", "Original Content", "Your content must be original and authentic", "MET"),
            EligibilityRequirementDto("ENGAGEMENT", "Engagement", "Maintain authentic engagement on your content",
                if (followers >= FOLLOWERS_TARGET) "MET" else "IN_PROGRESS")
        )
        return EligibilityDto(requirements, additional,
            requirements.all { it.status == "MET" } && additional.all { it.status == "MET" }, status)
    }

    suspend fun applicationStatus(userId: UUID): MonetizationStatusDto {
        val row = RlsContext.asUser(userId) {
            MonetizationApplications.selectAll().where { MonetizationApplications.userId eq userId }.firstOrNull()
        } ?: return MonetizationStatusDto("NONE")

        val reasons: List<String> = runCatching {
            json.decodeFromString<List<String>>(row[MonetizationApplications.rejectionReasons])
        }.getOrDefault(emptyList())

        // "Your progress" snapshot: metrics captured at submission if present, else live metrics.
        val snapshot = runCatching {
            json.decodeFromString<Map<String, Long>>(row[MonetizationApplications.progressSnapshot])
        }.getOrDefault(emptyMap())
        val (liveFollowers, liveWatchMinutes, _) = metrics(userId)
        val followers = snapshot["followers"] ?: liveFollowers
        val watchMinutes = snapshot["watchMinutes"] ?: liveWatchMinutes

        return MonetizationStatusDto(
            status = row[MonetizationApplications.status],
            submittedAt = row[MonetizationApplications.submittedAt].toString(),
            reviewedAt = row[MonetizationApplications.reviewedAt]?.toString(),
            rejectionReasons = reasons,
            followersCurrent = followers,
            followersTarget = 10000L,
            watchHoursCurrent = watchMinutes / 60L,
            watchHoursTarget = 4000L,
            contentCompliance = if (hasUnresolvedReports(userId)) "NEEDS_IMPROVEMENT" else "OK"
        )
    }

    /**
     * Submit an application. Monetization is invite-only with manual review — this
     * ALWAYS creates/keeps an UNDER_REVIEW row; there is intentionally no code path
     * that sets APPROVED here (approvals happen out-of-band by a reviewer).
     */
    suspend fun apply(userId: UUID): MonetizationStatusDto {
        val elig = eligibility(userId)
        if (!elig.allMet) return applicationStatus(userId)
        val existing = RlsContext.asUser(userId) {
            MonetizationApplications.selectAll().where { MonetizationApplications.userId eq userId }.firstOrNull()
        }
        if (existing != null && existing[MonetizationApplications.status] == "UNDER_REVIEW") {
            return applicationStatus(userId)
        }
        val (followers, watchMinutes, videos) = metrics(userId)
        val snapshot = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("followers", kotlinx.serialization.json.JsonPrimitive(followers))
                put("watchMinutes", kotlinx.serialization.json.JsonPrimitive(watchMinutes))
                put("publicVideos", kotlinx.serialization.json.JsonPrimitive(videos))
            }
        )
        val now = LocalDateTime.now()
        RlsContext.asUser(userId) {
            if (existing == null) {
                MonetizationApplications.insert {
                    it[MonetizationApplications.userId] = userId
                    it[status] = "UNDER_REVIEW"
                    it[submittedAt] = now
                    it[progressSnapshot] = snapshot
                }
            } else {
                // Re-application after a rejection resets the review cycle.
                MonetizationApplications.update({ MonetizationApplications.userId eq userId }) {
                    it[status] = "UNDER_REVIEW"
                    it[submittedAt] = now
                    it[reviewedAt] = null
                    it[rejectionReasons] = "[]"
                    it[progressSnapshot] = snapshot
                }
            }
        }
        return applicationStatus(userId)
    }

    // ---------------------------------------------------------------- stars

    suspend fun starsOverview(userId: UUID, periodDays: Int): StarsOverviewDto {
        val period = if (periodDays in ALLOWED_PERIODS) periodDays else 7
        val now = LocalDateTime.now()
        val start = now.minusDays(period.toLong())
        val prevStart = start.minusDays(period.toLong())
        return RlsContext.asUser(userId) {
            val all = StarTransactions.selectAll().where { StarTransactions.receiverId eq userId }.toList()
            val inPeriod = all.filter { !it[StarTransactions.createdAt].isBefore(start) }
            val prevPeriod = all.filter {
                !it[StarTransactions.createdAt].isBefore(prevStart) && it[StarTransactions.createdAt].isBefore(start)
            }
            val earnings = inPeriod.sumOf { it[StarTransactions.amountCents] }
            val stars = inPeriod.sumOf { it[StarTransactions.stars] }
            val supporters = inPeriod.map { it[StarTransactions.senderId] }.distinct().size.toLong()
            val dailyAvg = if (period > 0) earnings / period else earnings

            val prevEarnings = prevPeriod.sumOf { it[StarTransactions.amountCents] }
            val prevStars = prevPeriod.sumOf { it[StarTransactions.stars] }
            val prevSupporters = prevPeriod.map { it[StarTransactions.senderId] }.distinct().size.toLong()
            val prevDailyAvg = if (period > 0) prevEarnings / period else prevEarnings

            val goal = StarGoals.selectAll().where { StarGoals.userId eq userId }.firstOrNull()
            StarsOverviewDto(
                periodDays = period,
                totalEarningsCentsAllTime = all.sumOf { it[StarTransactions.amountCents] },
                totalStarsAllTime = all.sumOf { it[StarTransactions.stars] },
                earningsCents = earnings,
                starsReceived = stars,
                supporters = supporters,
                dailyAverageCents = dailyAvg,
                earningsDeltaPercent = delta(earnings, prevEarnings),
                starsDeltaPercent = delta(stars, prevStars),
                supportersDeltaPercent = delta(supporters, prevSupporters),
                dailyAvgDeltaPercent = delta(dailyAvg, prevDailyAvg),
                goalTitle = goal?.get(StarGoals.title),
                goalTargetStars = goal?.get(StarGoals.targetStars),
                goalProgressStars = all.sumOf { it[StarTransactions.stars] }
            )
        }
    }

    suspend fun starTransactions(userId: UUID, limit: Int = 50): List<StarTransactionDto> {
        val rows = RlsContext.asUser(userId) {
            StarTransactions.selectAll().where { StarTransactions.receiverId eq userId }
                .orderBy(StarTransactions.createdAt, SortOrder.DESC).limit(limit).toList()
        }
        val senderIds = rows.map { it[StarTransactions.senderId] }.toSet()
        val senders = dbQuery {
            Users.select(Users.id, Users.fullName, Users.username, Users.profileImageMediaId)
                .where { Users.id inList senderIds.toList() }
                .associate { it[Users.id].value to it }
        }
        val verified = badgeService.verifiedUserIds(senderIds)
        return rows.map { r ->
            val sid = r[StarTransactions.senderId]
            val u = senders[sid]
            StarTransactionDto(
                id = r[StarTransactions.id].value.toString(),
                fromUserId = sid.toString(),
                fromName = u?.get(Users.fullName),
                fromUsername = u?.get(Users.username),
                fromAvatarUrl = u?.get(Users.profileImageMediaId)?.let { "/api/media/$it" },
                fromVerified = verified.contains(sid),
                stars = r[StarTransactions.stars],
                amountCents = r[StarTransactions.amountCents],
                createdAt = r[StarTransactions.createdAt].toString()
            )
        }
    }

    suspend fun starSupporters(userId: UUID): List<StarSupporterDto> {
        val grouped = RlsContext.asUser(userId) {
            StarTransactions.selectAll().where { StarTransactions.receiverId eq userId }.toList()
                .groupBy { it[StarTransactions.senderId] }
                .map { (sender, txs) -> sender to (txs.sumOf { it[StarTransactions.stars] } to txs.size.toLong()) }
                .sortedByDescending { it.second.first }
        }
        if (grouped.isEmpty()) return emptyList()
        val ids = grouped.map { it.first }
        val users = dbQuery {
            Users.select(Users.id, Users.fullName, Users.username, Users.profileImageMediaId)
                .where { Users.id inList ids }.associate { it[Users.id].value to it }
        }
        val verified = badgeService.verifiedUserIds(ids.toSet())
        return grouped.map { (id, agg) ->
            val u = users[id]
            StarSupporterDto(
                userId = id.toString(),
                name = u?.get(Users.fullName),
                username = u?.get(Users.username),
                avatarUrl = u?.get(Users.profileImageMediaId)?.let { "/api/media/$it" },
                isVerified = verified.contains(id),
                totalStars = agg.first,
                giftCount = agg.second
            )
        }
    }

    suspend fun setStarGoal(userId: UUID, title: String, targetStars: Long) {
        val cleanTitle = title.take(120)
        val target = targetStars.coerceIn(1, 100_000_000L)
        RlsContext.asUser(userId) {
            val exists = StarGoals.selectAll().where { StarGoals.userId eq userId }.any()
            if (exists) {
                StarGoals.update({ StarGoals.userId eq userId }) {
                    it[StarGoals.title] = cleanTitle
                    it[StarGoals.targetStars] = target
                    it[updatedAt] = LocalDateTime.now()
                }
            } else {
                StarGoals.insert {
                    it[StarGoals.userId] = userId
                    it[StarGoals.title] = cleanTitle
                    it[StarGoals.targetStars] = target
                    it[updatedAt] = LocalDateTime.now()
                }
            }
        }
    }
}
