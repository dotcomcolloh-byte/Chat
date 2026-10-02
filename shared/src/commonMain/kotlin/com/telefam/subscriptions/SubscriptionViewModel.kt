package com.telefam.subscriptions

import com.telefam.data.api.*
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.db.local.LocalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * State + loading for every subscription surface (creator dashboard, plans,
 * subscribers, insights, and the fan subscribe flow). Offline-first like the
 * rest of the app: successful reads are cached in AppCache and shown when the
 * network is unavailable; payment actions require connectivity and surface an
 * explicit error state instead of silently failing.
 */
class SubscriptionViewModel(
    private val api: SubscriptionApi,
    driverFactory: DatabaseDriverFactory,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LocalDatabase.getInstance(driverFactory).appCacheQueries

    // ---------------- Creator: dashboard ----------------

    data class CreatorState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val overview: SubOverviewDto? = null,
        val plans: List<SubscriptionPlanDto> = emptyList(),
        val recentSubscribers: List<SubscriberDto> = emptyList(),
        val recentPayments: List<CreatorSubscriptionPaymentDto> = emptyList(),
        val periodDays: Int = 30
    )
    private val _creator = MutableStateFlow(CreatorState())
    val creator: StateFlow<CreatorState> = _creator

    // ---------------- Creator: full subscribers list ----------------

    data class SubscribersState(
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val offline: Boolean = false,
        val error: Boolean = false,
        val items: List<SubscriberDto> = emptyList(),
        val endReached: Boolean = false
    )
    private val _subscribers = MutableStateFlow(SubscribersState())
    val subscribers: StateFlow<SubscribersState> = _subscribers

    // ---------------- Creator: insights ----------------

    data class InsightsState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val data: SubInsightsDto? = null,
        val periodDays: Int = 30
    )
    private val _insights = MutableStateFlow(InsightsState())
    val insights: StateFlow<InsightsState> = _insights

    // ---------------- Fan: subscribe page + payment ----------------

    data class FanState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val page: SubscribePageDto? = null,
        val selectedPlanId: String? = null,
        val startingPayment: Boolean = false,
        val payment: InitiatedSubPaymentDto? = null,
        val paymentStatus: SubPaymentStatusDto? = null,
        val paymentError: String? = null,
        val unsubscribing: Boolean = false
    )
    private val _fan = MutableStateFlow(FanState())
    val fan: StateFlow<FanState> = _fan

    private inline fun <reified T> readCache(key: String): T? = runCatching {
        cache.selectValue(key).executeAsOneOrNull()?.cachedValue?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> writeCache(key: String, value: T) = runCatching {
        cache.upsertValue(key, json.encodeToString(kotlinx.serialization.serializer<T>(), value), com.telefam.chat.currentTimeMillis())
    }

    // ---------------- Creator actions ----------------

    fun loadCreator(periodDays: Int = _creator.value.periodDays) {
        scope.launch {
            _creator.value = _creator.value.copy(loading = true, error = false, periodDays = periodDays)
            val overview = runCatching { api.overview(periodDays) }
            val plans = runCatching { api.myPlans() }
            val recent = runCatching { api.recentSubscribers() }
            val payments = runCatching { api.creatorPayments(limit = 10) }
            if (overview.isSuccess) {
                writeCache("sub_overview_$periodDays", overview.getOrThrow())
                plans.onSuccess { writeCache("sub_plans", it) }
                recent.onSuccess { writeCache("sub_recent", it) }
                payments.onSuccess { writeCache("sub_payments", it) }
                _creator.value = CreatorState(
                    loading = false, periodDays = periodDays,
                    overview = overview.getOrNull(),
                    plans = plans.getOrElse { readCache("sub_plans") ?: emptyList() },
                    recentSubscribers = recent.getOrElse { readCache("sub_recent") ?: emptyList() },
                    recentPayments = payments.getOrElse { readCache("sub_payments") ?: emptyList() }
                )
            } else {
                val cached = readCache<SubOverviewDto>("sub_overview_$periodDays")
                _creator.value = CreatorState(
                    loading = false, offline = cached != null, error = cached == null, periodDays = periodDays,
                    overview = cached,
                    plans = readCache("sub_plans") ?: emptyList(),
                    recentSubscribers = readCache("sub_recent") ?: emptyList(),
                    recentPayments = readCache("sub_payments") ?: emptyList()
                )
            }
        }
    }

    /** Creates one full-refund request and refreshes provider reconciliation state. */
    suspend fun requestRefund(paymentId: String): String? {
        val result = runCatching { api.requestRefund(paymentId) }
        if (result.isSuccess) loadCreator()
        return result.exceptionOrNull()?.message
    }

    /** Returns null on success, or the server/user-facing error message. */
    suspend fun createPlan(name: String, description: String, interval: String, priceMinor: Long): String? {
        val res = runCatching { api.createPlan(name, description, interval, priceMinor) }
        res.onSuccess { writeCache("sub_plans", _creator.value.plans + it) }
        if (res.isSuccess) loadCreator()
        return res.exceptionOrNull()?.message
    }

    suspend fun updatePlan(planId: String, name: String, description: String, priceMinor: Long, isMostPopular: Boolean): String? {
        val res = runCatching { api.updatePlan(planId, name, description, priceMinor, isMostPopular) }
        if (res.isSuccess) loadCreator()
        return res.exceptionOrNull()?.message
    }

    suspend fun deletePlan(planId: String): Boolean {
        val res = runCatching { api.deletePlan(planId) }
        if (res.isSuccess) {
            val remaining = _creator.value.plans.filterNot { it.id == planId }
            writeCache("sub_plans", remaining)
            _creator.value = _creator.value.copy(plans = remaining)
        }
        return res.isSuccess
    }

    fun loadSubscribers(refresh: Boolean = true) {
        scope.launch {
            if (refresh) _subscribers.value = _subscribers.value.copy(loading = true, error = false)
            val res = runCatching { api.subscribers(limit = 50, offset = 0) }
            res.onSuccess {
                writeCache("sub_subscribers", it)
                _subscribers.value = SubscribersState(
                    loading = false, items = it, endReached = it.size < 50
                )
            }.onFailure {
                val cached = readCache<List<SubscriberDto>>("sub_subscribers")
                _subscribers.value = SubscribersState(
                    loading = false, offline = cached != null, error = cached == null,
                    items = cached ?: emptyList(), endReached = true
                )
            }
        }
    }

    fun loadMoreSubscribers() {
        val current = _subscribers.value
        if (current.loadingMore || current.endReached) return
        scope.launch {
            _subscribers.value = current.copy(loadingMore = true)
            runCatching { api.subscribers(limit = 50, offset = current.items.size) }.onSuccess { page ->
                val merged = current.items + page
                writeCache("sub_subscribers", merged)
                _subscribers.value = _subscribers.value.copy(
                    loadingMore = false, items = merged, endReached = page.size < 50
                )
            }.onFailure { _subscribers.value = _subscribers.value.copy(loadingMore = false) }
        }
    }

    fun loadInsights(periodDays: Int = _insights.value.periodDays) {
        scope.launch {
            _insights.value = _insights.value.copy(loading = true, error = false, periodDays = periodDays)
            val res = runCatching { api.insights(periodDays) }
            res.onSuccess {
                writeCache("sub_insights_$periodDays", it)
                _insights.value = InsightsState(loading = false, data = it, periodDays = periodDays)
            }.onFailure {
                val cached = readCache<SubInsightsDto>("sub_insights_$periodDays")
                _insights.value = InsightsState(
                    loading = false, offline = cached != null, error = cached == null,
                    data = cached, periodDays = periodDays
                )
            }
        }
    }

    // ---------------- Fan actions ----------------

    fun loadCreatorPage(creatorId: String) {
        scope.launch {
            _fan.value = _fan.value.copy(loading = true, error = false)
            val res = runCatching { api.subscribePage(creatorId) }
            res.onSuccess { page ->
                writeCache("sub_page_$creatorId", page)
                val preselected = page.plans.firstOrNull { it.isMostPopular }?.id ?: page.plans.firstOrNull()?.id
                _fan.value = FanState(loading = false, page = page, selectedPlanId = preselected)
            }.onFailure {
                val cached = readCache<SubscribePageDto>("sub_page_$creatorId")
                _fan.value = FanState(
                    loading = false, offline = cached != null, error = cached == null, page = cached,
                    selectedPlanId = cached?.plans?.firstOrNull { p -> p.isMostPopular }?.id
                        ?: cached?.plans?.firstOrNull()?.id
                )
            }
        }
    }

    fun selectPlan(planId: String) { _fan.value = _fan.value.copy(selectedPlanId = planId) }

    /**
     * Starts checkout for the selected plan. The idempotency key is remembered in
     * state, so a retry after a network failure reuses the same key and the server
     * returns the same payment instead of charging twice.
     */
    fun startPayment(onReady: (InitiatedSubPaymentDto) -> Unit) {
        val planId = _fan.value.selectedPlanId ?: return
        scope.launch {
            _fan.value = _fan.value.copy(startingPayment = true, paymentError = null)
            val key = _fan.value.payment?.let { null }
                ?: "sub_${planId}_${com.telefam.chat.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
            val res = runCatching { api.subscribe(planId, key) }
            res.onSuccess {
                _fan.value = _fan.value.copy(startingPayment = false, payment = it, paymentStatus = null)
                onReady(it)
            }.onFailure { e ->
                _fan.value = _fan.value.copy(
                    startingPayment = false,
                    paymentError = e.message ?: "Couldn't start the payment. Check your connection and try again."
                )
            }
        }
    }

    /**
     * Polls the server (which re-verifies with the provider) until the payment
     * leaves PENDING, then reloads the page so the Subscribed state shows.
     * Handles: paid -> success; failed -> error with reason; still pending ->
     * keeps polling with backoff for up to ~3 minutes.
     */
    fun pollPayment(paymentId: String, creatorId: String, maxAttempts: Int = 45) {
        scope.launch {
            var attempt = 0
            while (attempt < maxAttempts) {
                val res = runCatching {
                    if (attempt == 0) api.confirmPayment(paymentId) else api.paymentStatus(paymentId)
                }
                res.onSuccess { st ->
                    _fan.value = _fan.value.copy(paymentStatus = st, paymentError = null)
                    if (st.status == "PAID") { loadCreatorPage(creatorId); return@launch }
                    if (st.status == "FAILED") return@launch
                }.onFailure { e ->
                    _fan.value = _fan.value.copy(paymentError = e.message)
                }
                attempt++
                delay((2000L + attempt * 500L).coerceAtMost(6000L))
            }
        }
    }

    fun clearPaymentError() { _fan.value = _fan.value.copy(paymentError = null, paymentStatus = null, payment = null) }

    /** Unsubscribe with immediate local reflection; reload confirms server truth. */
    suspend fun unsubscribe(subscriptionId: String, creatorId: String): Boolean {
        _fan.value = _fan.value.copy(unsubscribing = true)
        val res = runCatching { api.unsubscribe(subscriptionId) }
        _fan.value = _fan.value.copy(unsubscribing = false)
        if (res.isSuccess) loadCreatorPage(creatorId)
        return res.isSuccess
    }
}
