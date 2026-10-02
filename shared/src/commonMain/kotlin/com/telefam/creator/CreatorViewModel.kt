package com.telefam.creator

import com.telefam.data.api.*
import com.telefam.data.offline.ActionResult
import com.telefam.data.offline.OfflineActionRepository
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * State + loading for the creator screens (Dashboard, Analytics, Content
 * Performance, Monetization, Stars). Offline-first like the rest of the app:
 * every successful response is cached in AppCache and shown immediately when
 * the network is unavailable; the goal write falls back to the outbox queue.
 */
class CreatorViewModel(
    private val api: CreatorApi,
    private val offlineRepository: OfflineActionRepository,
    driverFactory: DatabaseDriverFactory,
    private val onQueuedForRetry: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LocalDatabase.getInstance(driverFactory).appCacheQueries

    // ---- Dashboard ----
    data class DashboardState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val data: DashboardDto? = null
    )
    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard: StateFlow<DashboardState> = _dashboard

    // ---- Analytics ----
    data class AnalyticsState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val data: AnalyticsDto? = null
    )
    private val _analytics = MutableStateFlow(AnalyticsState())
    val analytics: StateFlow<AnalyticsState> = _analytics

    // ---- Content performance (View All) ----
    data class ContentState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val items: List<ContentPerformanceItemDto> = emptyList()
    )
    private val _content = MutableStateFlow(ContentState())
    val content: StateFlow<ContentState> = _content

    // ---- Monetization ----
    data class MonetizationState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val eligibility: EligibilityDto? = null,
        val status: MonetizationStatusDto? = null,
        val applying: Boolean = false
    )
    private val _monetization = MutableStateFlow(MonetizationState())
    val monetization: StateFlow<MonetizationState> = _monetization

    // ---- Stars ----
    data class StarsState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val overview: StarsOverviewDto? = null,
        val transactions: List<StarTransactionDto> = emptyList(),
        val supporters: List<StarSupporterDto> = emptyList()
    )
    private val _stars = MutableStateFlow(StarsState())
    val stars: StateFlow<StarsState> = _stars

    private inline fun <reified T> readCache(key: String): T? = runCatching {
        cache.selectValue(key).executeAsOneOrNull()?.cachedValue?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> writeCache(key: String, value: T) = runCatching {
        cache.upsertValue(key, json.encodeToString(kotlinx.serialization.serializer<T>(), value), com.telefam.chat.currentTimeMillis())
    }

    fun loadDashboard(periodDays: Int) {
        scope.launch {
            _dashboard.value = _dashboard.value.copy(loading = true, error = false)
            val res = runCatching { api.dashboard(periodDays) }
            res.onSuccess {
                writeCache("creator_dashboard_$periodDays", it)
                _dashboard.value = DashboardState(loading = false, data = it)
            }.onFailure {
                val cached = readCache<DashboardDto>("creator_dashboard_$periodDays")
                _dashboard.value = DashboardState(
                    loading = false, offline = cached != null, error = cached == null, data = cached
                )
            }
        }
    }

    fun loadAnalytics(periodDays: Int) {
        scope.launch {
            _analytics.value = _analytics.value.copy(loading = true, error = false)
            val res = runCatching { api.analytics(periodDays) }
            res.onSuccess {
                writeCache("creator_analytics_$periodDays", it)
                _analytics.value = AnalyticsState(loading = false, data = it)
            }.onFailure {
                val cached = readCache<AnalyticsDto>("creator_analytics_$periodDays")
                _analytics.value = AnalyticsState(
                    loading = false, offline = cached != null, error = cached == null, data = cached
                )
            }
        }
    }

    fun loadContent() {
        scope.launch {
            _content.value = _content.value.copy(loading = true, error = false)
            val res = runCatching { api.contentPerformance() }
            res.onSuccess {
                writeCache("creator_content", it)
                _content.value = ContentState(loading = false, items = it)
            }.onFailure {
                val cached = readCache<List<ContentPerformanceItemDto>>("creator_content")
                _content.value = ContentState(
                    loading = false, offline = cached != null, error = cached == null,
                    items = cached ?: emptyList()
                )
            }
        }
    }

    fun loadMonetization() {
        scope.launch {
            _monetization.value = _monetization.value.copy(loading = true, error = false)
            val elig = runCatching { api.eligibility() }
            val status = runCatching { api.monetizationStatus() }
            if (elig.isSuccess && status.isSuccess) {
                writeCache("creator_monetization_elig", elig.getOrThrow())
                writeCache("creator_monetization_status", status.getOrThrow())
                _monetization.value = MonetizationState(
                    loading = false, eligibility = elig.getOrNull(), status = status.getOrNull()
                )
            } else {
                val cachedElig = readCache<EligibilityDto>("creator_monetization_elig")
                val cachedStatus = readCache<MonetizationStatusDto>("creator_monetization_status")
                _monetization.value = MonetizationState(
                    loading = false,
                    offline = cachedElig != null,
                    error = cachedElig == null,
                    eligibility = cachedElig,
                    status = cachedStatus
                )
            }
        }
    }

    /** Submit the monetization application. Returns true when the server accepted it. */
    suspend fun applyForMonetization(): Boolean {
        _monetization.value = _monetization.value.copy(applying = true)
        val res = runCatching { api.applyForMonetization() }
        _monetization.value = _monetization.value.copy(applying = false)
        res.onSuccess {
            writeCache("creator_monetization_status", it)
            _monetization.value = _monetization.value.copy(status = it)
        }
        return res.isSuccess
    }

    fun loadStars(periodDays: Int) {
        scope.launch {
            _stars.value = _stars.value.copy(loading = true, error = false)
            val overview = runCatching { api.starsOverview(periodDays) }
            val txs = runCatching { api.starTransactions(10) }
            val supporters = runCatching { api.starSupporters() }
            if (overview.isSuccess) {
                writeCache("creator_stars_overview_$periodDays", overview.getOrThrow())
                txs.onSuccess { writeCache("creator_stars_txs", it) }
                supporters.onSuccess { writeCache("creator_stars_supporters", it) }
                _stars.value = StarsState(
                    loading = false,
                    overview = overview.getOrNull(),
                    transactions = txs.getOrElse { readCache("creator_stars_txs") ?: emptyList() },
                    supporters = supporters.getOrElse { readCache("creator_stars_supporters") ?: emptyList() }
                )
            } else {
                val cached = readCache<StarsOverviewDto>("creator_stars_overview_$periodDays")
                _stars.value = StarsState(
                    loading = false,
                    offline = cached != null,
                    error = cached == null,
                    overview = cached,
                    transactions = readCache("creator_stars_txs") ?: emptyList(),
                    supporters = readCache("creator_stars_supporters") ?: emptyList()
                )
            }
        }
    }

    /** Set the Stars goal; queues to the outbox for silent retry when offline. */
    suspend fun setStarGoal(title: String, targetStars: Long): Boolean {
        val body = json.encodeToString(StarGoalRequest.serializer(), StarGoalRequest(title, targetStars))
        return when (offlineRepository.performOrQueue("/api/creator/stars/goal", HttpMethod.Put, body)) {
            is ActionResult.SentImmediately -> {
                val period = _stars.value.overview?.periodDays ?: 7
                loadStars(period)
                true
            }
            is ActionResult.QueuedForRetry -> {
                onQueuedForRetry()
                // Optimistic local echo so the UI reflects the goal while offline.
                _stars.value.overview?.let { o ->
                    _stars.value = _stars.value.copy(
                        overview = o.copy(goalTitle = title, goalTargetStars = targetStars)
                    )
                }
                true
            }
            else -> false
        }
    }
}
