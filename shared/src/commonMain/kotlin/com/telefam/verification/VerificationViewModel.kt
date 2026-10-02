package com.telefam.verification

import com.telefam.data.api.VerificationApi
import com.telefam.data.api.ApiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the whole Get Verified flow. Every authoritative decision comes from the
 * server — the ViewModel polls /api/verification/status and never advances past
 * payment on its own.
 */
class VerificationViewModel(private val api: VerificationApi) {

    data class UiState(
        val loading: Boolean = true,
        val pricing: PricingResponseDto? = null,
        val status: VerificationStatusDto = VerificationStatusDto(state = "NONE"),
        val selectedPlan: String = "ANNUAL",
        val payment: InitiatedPaymentDto? = null,
        val paymentError: String? = null,
        val challenge: LivenessChallengeDto? = null,
        val error: String? = null
    )

    private val scope = CoroutineScope(Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun load() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val pricing = runCatching { api.pricing() }.getOrNull()
                val status = api.status()
                _state.value = _state.value.copy(loading = false, pricing = pricing, status = status)
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message)
            }
        }
    }

    fun selectPlan(plan: String) { _state.value = _state.value.copy(selectedPlan = plan) }

    /** Opens checkout. The app then polls confirmPayment — the server decides the outcome. */
    fun startPayment(onOpenCheckout: (String) -> Unit) {
        val plan = _state.value.selectedPlan
        scope.launch {
            _state.value = _state.value.copy(paymentError = null)
            runCatching { api.initiatePayment(plan) }.fold(
                onSuccess = {
                    _state.value = _state.value.copy(payment = it)
                    onOpenCheckout(it.checkoutUrl)
                    pollPayment(it.paymentId)
                },
                onFailure = { _state.value = _state.value.copy(paymentError = it.message) }
            )
        }
    }

    /** The server re-verifies with the provider on each poll; PAID unlocks liveness. */
    fun pollPayment(paymentId: String) {
        scope.launch {
            repeat(40) { // ~4 minutes max
                val st = runCatching { api.confirmPayment(paymentId) }.getOrNull() ?: return@repeat
                if (st.status == "PAID" || st.status == "FAILED" || st.status == "REFUNDED") {
                    load(); return@launch
                }
                delay(6000)
            }
            load()
        }
    }

    fun startLiveness() {
        scope.launch {
            runCatching { api.startLiveness() }.fold(
                onSuccess = { _state.value = _state.value.copy(challenge = it, error = null) },
                onFailure = { _state.value = _state.value.copy(error = it.message) }
            )
        }
    }

    fun completeLiveness(timestampsMs: List<Long>, selfieBytes: ByteArray) {
        val challenge = _state.value.challenge ?: return
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching {
                val selfieId = api.uploadArtifact(selfieBytes, "image/jpeg", "liveness_selfie.jpg")
                api.completeLiveness(LivenessCompletionRequest(challenge.signature, timestampsMs, selfieId))
            }.fold(
                onSuccess = { load() },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) }
            )
        }
    }

    fun submitId(frontBytes: ByteArray, backBytes: ByteArray) {
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching {
                val front = api.uploadArtifact(frontBytes, "image/jpeg", "id_front.jpg")
                val back = api.uploadArtifact(backBytes, "image/jpeg", "id_back.jpg")
                api.submitId(front, back)
            }.fold(
                onSuccess = { pollReview() },
                onFailure = { _state.value = _state.value.copy(loading = false, error = it.message) }
            )
        }
    }

    /** Watches SUBMITTED -> PENDING_REVIEW -> APPROVED / REJECTED / ADMIN_REVIEW. */
    private fun pollReview() {
        scope.launch {
            repeat(60) {
                val st = runCatching { api.status() }.getOrNull() ?: return@repeat
                _state.value = _state.value.copy(loading = false, status = st)
                if (st.state !in listOf("SUBMITTED", "PENDING_REVIEW")) return@launch
                delay(5000)
            }
        }
    }

    fun retry() {
        scope.launch {
            runCatching { api.retry() }.onSuccess { load() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun refund() {
        val paymentId = _state.value.status.paymentId ?: return
        scope.launch {
            runCatching { api.refund(paymentId) }.onSuccess { load() }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }
}
