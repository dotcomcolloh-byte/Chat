package com.telefam.wallet

import com.telefam.data.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Wallet state holder. Everything displayed comes from the backend ledger via
 * WalletApi — the ViewModel never computes balances, fees, settlement completion
 * or payout outcomes itself.
 */
class WalletViewModel(private val api: WalletApi) {
    private val scope = CoroutineScope(Dispatchers.Default)

    data class WalletState(
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
        val balance: WalletBalanceDto? = null,
        val overview: List<WalletOverviewSourceDto> = emptyList(),
        val overviewPeriodDays: Int = 30,
        val chartPeriodDays: Int = 7,
        val chart: List<WalletChartPointDto> = emptyList(),
        val chartMaxMinor: Long = 0L,
        val transactions: List<WalletTransactionDto> = emptyList(),
        val transactionFilter: String = "ALL",
        val transactionsHasMore: Boolean = false,
        val transactionsLoadingMore: Boolean = false,
        val payoutSummary: PayoutSummaryDto? = null,
        val methods: List<PayoutMethodDto> = emptyList(),
        val pendingBreakdown: WalletPendingBreakdownDto? = null
    )

    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = _state

    private var txCursor: String? = null

    fun load() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.balance() }.onSuccess { _state.value = _state.value.copy(balance = it) }
            runCatching { api.overview(_state.value.overviewPeriodDays) }
                .onSuccess { _state.value = _state.value.copy(overview = it) }
            loadChart(_state.value.chartPeriodDays)
            loadTransactions(_state.value.transactionFilter, reset = true)
            runCatching { api.payouts() }.onSuccess { _state.value = _state.value.copy(payoutSummary = it) }
            runCatching { api.methods() }.onSuccess { _state.value = _state.value.copy(methods = it) }
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(refreshing = true)
            load()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    fun setOverviewPeriod(periodDays: Int) {
        _state.value = _state.value.copy(overviewPeriodDays = periodDays)
        scope.launch {
            runCatching { api.overview(periodDays) }
                .onSuccess { _state.value = _state.value.copy(overview = it) }
        }
    }

    fun loadChart(periodDays: Int) {
        scope.launch {
            runCatching { api.chart(periodDays) }.onSuccess { points ->
                _state.value = _state.value.copy(
                    chartPeriodDays = periodDays,
                    chart = points,
                    chartMaxMinor = points.maxOfOrNull { it.amountMinor } ?: 0L
                )
            }
        }
    }

    fun loadTransactions(filter: String, reset: Boolean = false) {
        scope.launch {
            if (reset) txCursor = null
            runCatching { api.transactions(filter, if (reset) null else txCursor) }.onSuccess { page ->
                txCursor = page.nextCursor
                _state.value = _state.value.copy(
                    transactionFilter = filter,
                    transactions = if (reset) page.items else _state.value.transactions + page.items,
                    transactionsHasMore = page.hasMore,
                    transactionsLoadingMore = false
                )
            }
        }
    }

    fun loadMoreTransactions() {
        val s = _state.value
        if (!s.transactionsHasMore || s.transactionsLoadingMore) return
        _state.value = s.copy(transactionsLoadingMore = true)
        loadTransactions(s.transactionFilter, reset = false)
    }

    fun loadPendingBreakdown() {
        scope.launch {
            runCatching { api.pendingBreakdown() }
                .onSuccess { _state.value = _state.value.copy(pendingBreakdown = it) }
        }
    }
}
