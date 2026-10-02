package com.telefam.campaigns

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
 * State + loading for Create Campaign, Buy Stars and Series. Offline-first like the
 * rest of the app: config, balance, packages, own videos and series are cached in
 * AppCache and shown immediately when the network is unavailable; spends and
 * purchases require connectivity and fail with an explicit retry state — stars are
 * money, so nothing is ever optimistically debited client-side.
 */
class CampaignViewModel(
    private val api: CampaignApi,
    private val feedApi: FeedApi,
    driverFactory: DatabaseDriverFactory,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LocalDatabase.getInstance(driverFactory).appCacheQueries

    data class CampaignUiState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val config: CampaignConfigDto? = null,
        /** The owner's own published videos, for the horizontal "select a video" row. */
        val videos: List<com.telefam.posts.FeedPostDto> = emptyList(),
        val videosLoading: Boolean = false,
        val selectedType: String = "PROFILE_BOOST",
        val selectedPostId: String? = null,
        val selectedTierId: String? = null,
        val days: Int = 1,
        val linkUrl: String = "",
        val linkAction: String = "VISIT",
        // ---- Audience targeting ----
        val audienceMode: String = "AUTO", // AUTO | CUSTOM
        val audienceCountries: Set<String> = emptySet(),
        val audienceAgeMin: Int = 18,
        val audienceAgeMax: Int = 65,
        val audienceAgeEnabled: Boolean = false,
        val audienceGender: String? = null, // MALE | FEMALE | null = all
        val audienceInterests: Set<String> = emptySet(),
        val boosting: Boolean = false,
        /** Set when the wallet is short — the UI routes to Buy Stars. */
        val insufficient: Pair<Long, Long>? = null, // balance to required
        val boosted: CampaignDto? = null,
        val boostError: String? = null
    )

    data class BuyStarsState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val data: StarPackagesDto? = null,
        val balanceStars: Long = 0,
        /** Package chosen on the Buy Stars list, shown on the Checkout screen. */
        val pendingPackageId: String? = null,
        val startingPayment: Boolean = false,
        val payment: InitiatedStarPurchaseDto? = null,
        val paymentStatus: StarPurchaseStatusDto? = null,
        val paymentError: String? = null
    ) {
        val pendingPackage: StarPackageDto? get() = data?.packages?.firstOrNull { it.id == pendingPackageId }
    }

    data class SeriesUiState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val series: List<SeriesDto> = emptyList(),
        val videos: List<com.telefam.posts.FeedPostDto> = emptyList(),
        val selectedPostIds: Set<String> = emptySet(),
        val name: String = "",
        val description: String = "",
        val costPerDayStars: Long = 300,
        val balanceStars: Long = 0,
        val creating: Boolean = false,
        val insufficient: Pair<Long, Long>? = null,
        val created: SeriesDto? = null,
        val createError: String? = null
    )

    private val _campaign = MutableStateFlow(CampaignUiState())
    val campaign: StateFlow<CampaignUiState> = _campaign

    private val _buyStars = MutableStateFlow(BuyStarsState())
    val buyStars: StateFlow<BuyStarsState> = _buyStars

    private val _series = MutableStateFlow(SeriesUiState())
    val series: StateFlow<SeriesUiState> = _series

    // ---- My Campaigns dashboard ----
    data class MyCampaignsUiState(
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: Boolean = false,
        val campaigns: List<CampaignDto> = emptyList(),
        val balanceStars: Long = 0,
        val busyId: String? = null, // campaign being paused/resumed/canceled
        val analytics: CampaignAnalyticsDto? = null,
        val analyticsLoading: Boolean = false,
        val actionError: String? = null
    )

    private val _myCampaigns = MutableStateFlow(MyCampaignsUiState())
    val myCampaigns: StateFlow<MyCampaignsUiState> = _myCampaigns

    private inline fun <reified T> readCache(key: String): T? = runCatching {
        cache.selectValue(key).executeAsOneOrNull()?.cachedValue?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> writeCache(key: String, value: T) = runCatching {
        cache.upsertValue(key, json.encodeToString(kotlinx.serialization.serializer<T>(), value), com.telefam.chat.currentTimeMillis())
    }

    private fun newIdempotencyKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).joinToString("") { chars[Random.nextInt(chars.length)].toString() }
    }

    // ---------------------------------------------------------------- campaign

    fun loadCampaign(userId: String) {
        scope.launch {
            _campaign.value = _campaign.value.copy(loading = true, error = false)
            val cfg = runCatching { api.config() }
            cfg.onSuccess { writeCache("campaign_config", it) }
            val config = cfg.getOrNull() ?: readCache("campaign_config")
            _campaign.value = _campaign.value.copy(
                loading = false,
                offline = cfg.isFailure && config != null,
                error = cfg.isFailure && config == null,
                config = config,
                selectedTierId = _campaign.value.selectedTierId
                    ?: config?.tiers?.get(_campaign.value.selectedType)?.firstOrNull()?.id
            )
            loadMyVideos(userId)
        }
    }

    private suspend fun loadMyVideos(userId: String) {
        if (userId.isBlank()) return
        _campaign.value = _campaign.value.copy(videosLoading = true)
        val res = runCatching { feedApi.userPosts(userId, limit = 50) }
        res.onSuccess {
            writeCache("campaign_my_videos", it.items)
            _campaign.value = _campaign.value.copy(videos = it.items, videosLoading = false)
        }.onFailure {
            _campaign.value = _campaign.value.copy(
                videos = readCache("campaign_my_videos") ?: emptyList(), videosLoading = false
            )
        }
    }

    fun selectType(type: String) {
        val tiers = _campaign.value.config?.tiers?.get(type)
        _campaign.value = _campaign.value.copy(
            selectedType = type,
            selectedTierId = tiers?.firstOrNull()?.id,
            insufficient = null, boosted = null, boostError = null
        )
    }

    fun selectPost(postId: String) { _campaign.value = _campaign.value.copy(selectedPostId = postId) }
    fun selectTier(tierId: String) { _campaign.value = _campaign.value.copy(selectedTierId = tierId) }
    fun setDays(days: Int) {
        val max = _campaign.value.config?.maxDays ?: 30
        _campaign.value = _campaign.value.copy(days = days.coerceIn(1, max))
    }
    fun setLinkUrl(url: String) { _campaign.value = _campaign.value.copy(linkUrl = url) }
    fun setLinkAction(action: String) { _campaign.value = _campaign.value.copy(linkAction = action) }
    fun clearBoostResult() { _campaign.value = _campaign.value.copy(boosted = null, boostError = null, insufficient = null) }

    // ---- audience targeting ----

    fun setAudienceMode(mode: String) { _campaign.value = _campaign.value.copy(audienceMode = mode) }
    fun toggleAudienceCountry(iso2: String) {
        val cur = _campaign.value.audienceCountries
        _campaign.value = _campaign.value.copy(
            audienceCountries = if (iso2 in cur) cur - iso2 else cur + iso2
        )
    }
    fun setAudienceAgeEnabled(enabled: Boolean) {
        _campaign.value = _campaign.value.copy(audienceAgeEnabled = enabled)
    }
    fun setAudienceAgeRange(min: Int, max: Int) {
        _campaign.value = _campaign.value.copy(
            audienceAgeMin = min.coerceIn(13, 100), audienceAgeMax = max.coerceIn(13, 100)
        )
    }
    fun setAudienceGender(gender: String?) { _campaign.value = _campaign.value.copy(audienceGender = gender) }
    fun toggleAudienceInterest(interest: String) {
        val cur = _campaign.value.audienceInterests
        _campaign.value = _campaign.value.copy(
            audienceInterests = if (interest in cur) cur - interest else if (cur.size < 8) cur + interest else cur
        )
    }

    /** The audience payload for createCampaign; null = automatic targeting. */
    fun audiencePayload(): AudienceDto? {
        val s = _campaign.value
        if (s.audienceMode != "CUSTOM") return null
        return AudienceDto(
            mode = "CUSTOM",
            countries = s.audienceCountries.toList(),
            ageMin = if (s.audienceAgeEnabled) s.audienceAgeMin else null,
            ageMax = if (s.audienceAgeEnabled) s.audienceAgeMax else null,
            gender = s.audienceGender,
            interests = s.audienceInterests.toList()
        )
    }

    fun audienceSummary(): String = audiencePayload()?.summary()
        ?: "Automatic — Telefam picks the most relevant viewers"

    /** Total stars for the current selection — display only; the server recomputes it. */
    fun selectedTotalStars(): Long {
        val s = _campaign.value
        val tier = s.config?.tiers?.get(s.selectedType)?.firstOrNull { it.id == s.selectedTierId } ?: return 0
        return tier.starsPerDay * s.days
    }

    /** Attempt the boost. Offline / failure shows a retry state; 402 routes to Buy Stars. */
    fun boost() {
        val s = _campaign.value
        val postId = s.selectedPostId ?: return
        val tierId = s.selectedTierId ?: return
        if (s.boosting) return
        _campaign.value = s.copy(boosting = true, boostError = null, insufficient = null, boosted = null)
        scope.launch {
            when (val res = runCatching {
                api.createCampaign(
                    type = s.selectedType, postId = postId, tierId = tierId, days = s.days,
                    linkUrl = s.linkUrl.ifBlank { null },
                    linkAction = if (s.selectedType == "GET_SALES") s.linkAction else null,
                    audience = audiencePayload(),
                    idempotencyKey = newIdempotencyKey()
                )
            }.getOrElse { SpendResult.Failed(it.message ?: "network error") }) {
                is SpendResult.Ok -> {
                    val newBalance = ((_campaign.value.config?.balanceStars ?: 0) - res.value.totalStars).coerceAtLeast(0)
                    _campaign.value = _campaign.value.copy(
                        boosting = false, boosted = res.value,
                        config = _campaign.value.config?.copy(balanceStars = newBalance)
                    )
                    _campaign.value.config?.let { writeCache("campaign_config", it) }
                }
                is SpendResult.InsufficientStars -> _campaign.value = _campaign.value.copy(
                    boosting = false, insufficient = res.balanceStars to res.requiredStars
                )
                is SpendResult.Failed -> _campaign.value = _campaign.value.copy(
                    boosting = false, boostError = res.message
                )
            }
        }
    }

    // ---------------------------------------------------------------- buy stars

    fun loadBuyStars() {
        scope.launch {
            _buyStars.value = _buyStars.value.copy(loading = true, error = false)
            val pkgs = runCatching { api.packages() }
            val bal = runCatching { api.balance() }
            pkgs.onSuccess { writeCache("star_packages", it) }
            bal.onSuccess { writeCache("star_balance", it) }
            val data = pkgs.getOrNull() ?: readCache("star_packages")
            val balance = bal.getOrNull()?.balanceStars ?: readCache<StarBalanceDto>("star_balance")?.balanceStars ?: 0
            _buyStars.value = _buyStars.value.copy(
                loading = false,
                offline = pkgs.isFailure && data != null,
                error = pkgs.isFailure && data == null,
                data = data, balanceStars = balance
            )
        }
    }

    /** Select a package (Buy on the list) — the Checkout screen shows it before paying. */
    fun selectPackage(packageId: String) {
        _buyStars.value = _buyStars.value.copy(pendingPackageId = packageId, paymentError = null)
    }

    /** Start a purchase; the UI then opens the returned checkout URL in the in-app web view. */
    fun startPurchase() {
        val packageId = _buyStars.value.pendingPackageId ?: return
        if (_buyStars.value.startingPayment) return
        _buyStars.value = _buyStars.value.copy(startingPayment = true, paymentError = null, paymentStatus = null)
        scope.launch {
            val res = runCatching { api.initiatePurchase(packageId, newIdempotencyKey()) }
            _buyStars.value = _buyStars.value.copy(
                startingPayment = false,
                payment = res.getOrNull(),
                paymentError = res.exceptionOrNull()?.let { "Couldn't start checkout. Check your connection and try again." }
            )
        }
    }

    fun clearPaymentError() { _buyStars.value = _buyStars.value.copy(paymentError = null, payment = null, paymentStatus = null) }

    /** Poll the server — which verifies with the provider — until PAID / FAILED. */
    fun pollPurchase() {
        val id = _buyStars.value.payment?.purchaseId ?: return
        scope.launch {
            repeat(30) {
                val status = runCatching { api.confirmPurchase(id) }.getOrNull()
                if (status != null) {
                    _buyStars.value = _buyStars.value.copy(
                        paymentStatus = status,
                        balanceStars = if (status.status == "PAID") status.balanceStars else _buyStars.value.balanceStars
                    )
                    if (status.status == "PAID") {
                        writeCache("star_balance", StarBalanceDto(status.balanceStars))
                        return@launch
                    }
                    if (status.status == "FAILED") return@launch
                }
                delay(3_000)
            }
        }
    }

    // ---------------------------------------------------------------- series

    fun loadSeries(userId: String) {
        scope.launch {
            _series.value = _series.value.copy(loading = true, error = false)
            val list = runCatching { api.mySeries() }
            val cfg = runCatching { api.config() }
            list.onSuccess { writeCache("campaign_series", it) }
            val videos = runCatching { feedApi.userPosts(userId, limit = 50) }
            videos.onSuccess { writeCache("campaign_series_videos", it.items) }
            val seriesList = list.getOrNull() ?: readCache("campaign_series")
            _series.value = _series.value.copy(
                loading = false,
                offline = list.isFailure && seriesList != null,
                error = list.isFailure && seriesList == null,
                series = seriesList ?: emptyList(),
                videos = videos.getOrNull()?.items ?: readCache("campaign_series_videos") ?: emptyList(),
                costPerDayStars = cfg.getOrNull()?.seriesCostPerDayStars
                    ?: readCache<CampaignConfigDto>("campaign_config")?.seriesCostPerDayStars ?: 300,
                balanceStars = cfg.getOrNull()?.balanceStars
                    ?: readCache<CampaignConfigDto>("campaign_config")?.balanceStars ?: 0
            )
        }
    }

    fun toggleSeriesVideo(postId: String) {
        val current = _series.value.selectedPostIds
        _series.value = _series.value.copy(
            selectedPostIds = if (postId in current) current - postId else current + postId
        )
    }

    fun setSeriesName(name: String) { _series.value = _series.value.copy(name = name.take(50)) }
    fun setSeriesDescription(desc: String) { _series.value = _series.value.copy(description = desc.take(200)) }
    fun clearSeriesResult() { _series.value = _series.value.copy(created = null, createError = null, insufficient = null) }

    fun createSeries() {
        val s = _series.value
        if (s.creating || s.name.isBlank() || s.selectedPostIds.isEmpty()) return
        _series.value = s.copy(creating = true, createError = null, insufficient = null, created = null)
        scope.launch {
            when (val res = runCatching {
                api.createSeries(s.name, s.description, s.selectedPostIds.toList(), 1, newIdempotencyKey())
            }.getOrElse { SpendResult.Failed(it.message ?: "network error") }) {
                is SpendResult.Ok -> _series.value = _series.value.copy(
                    creating = false, created = res.value, selectedPostIds = emptySet(),
                    name = "", description = ""
                )
                is SpendResult.InsufficientStars -> _series.value = _series.value.copy(
                    creating = false, insufficient = res.balanceStars to res.requiredStars
                )
                is SpendResult.Failed -> _series.value = _series.value.copy(
                    creating = false, createError = res.message
                )
            }
        }
    }

    // ---------------------------------------------------------------- my campaigns (management dashboard)

    fun loadMyCampaigns() {
        scope.launch {
            _myCampaigns.value = _myCampaigns.value.copy(loading = true, error = false)
            val list = runCatching { api.myCampaigns() }
            val bal = runCatching { api.balance() }
            list.onSuccess { writeCache("my_campaigns", it) }
            bal.onSuccess { writeCache("star_balance", it) }
            val campaigns = list.getOrNull() ?: readCache("my_campaigns")
            _myCampaigns.value = _myCampaigns.value.copy(
                loading = false,
                offline = list.isFailure && campaigns != null,
                error = list.isFailure && campaigns == null,
                campaigns = campaigns ?: emptyList(),
                balanceStars = bal.getOrNull()?.balanceStars
                    ?: readCache<StarBalanceDto>("star_balance")?.balanceStars
                    ?: _myCampaigns.value.balanceStars
            )
        }
    }

    fun loadAnalytics(campaignId: String) {
        _myCampaigns.value = _myCampaigns.value.copy(analyticsLoading = true, analytics = null)
        scope.launch {
            val res = runCatching { api.analytics(campaignId) }
            _myCampaigns.value = _myCampaigns.value.copy(
                analyticsLoading = false,
                analytics = res.getOrNull(),
                actionError = if (res.isFailure) "Couldn't load analytics. Check your connection." else null
            )
        }
    }

    fun dismissAnalytics() { _myCampaigns.value = _myCampaigns.value.copy(analytics = null) }
    fun clearActionError() { _myCampaigns.value = _myCampaigns.value.copy(actionError = null) }

    private fun campaignAction(campaignId: String, action: suspend CampaignApi.() -> CampaignDto) {
        if (_myCampaigns.value.busyId != null || _myCampaigns.value.offline) return
        _myCampaigns.value = _myCampaigns.value.copy(busyId = campaignId, actionError = null)
        scope.launch {
            val res = runCatching { api.action() }
            res.onSuccess { updated ->
                val bal = runCatching { api.balance() }.getOrNull()
                _myCampaigns.value = _myCampaigns.value.copy(
                    busyId = null,
                    campaigns = _myCampaigns.value.campaigns.map { if (it.id == updated.id) updated else it },
                    balanceStars = bal?.balanceStars ?: _myCampaigns.value.balanceStars
                )
            }.onFailure {
                _myCampaigns.value = _myCampaigns.value.copy(
                    busyId = null,
                    actionError = "Action failed. Check your connection and try again."
                )
            }
        }
    }

    fun pauseCampaign(campaignId: String) = campaignAction(campaignId) { pauseCampaign(campaignId) }
    fun resumeCampaign(campaignId: String) = campaignAction(campaignId) { resumeCampaign(campaignId) }
    fun cancelCampaign(campaignId: String) = campaignAction(campaignId) { cancelCampaign(campaignId) }
}
