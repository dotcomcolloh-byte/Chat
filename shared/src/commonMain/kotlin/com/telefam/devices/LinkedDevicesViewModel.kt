package com.telefam.devices

import com.telefam.data.AuthSession
import com.telefam.data.api.DeviceLinkApi
import com.telefam.data.api.LinkChallengeDto
import com.telefam.data.api.LinkChallengeInvalidException
import com.telefam.data.api.SessionDto
import com.telefam.data.api.SettingsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Linked devices screen end-to-end:
 *
 *  - Devices tab: lists live sessions (refresh-token families) and revokes them.
 *  - QR tab (owner): mints a 30-second challenge, counts it down, auto-refreshes
 *    on expiry (each refresh invalidates the previous QR server-side), and shows
 *    the Accept / Decline prompt when another device scans. Accept is only valid
 *    within 5 minutes of the scan — enforced server-side, mirrored here.
 *  - Scan tab (new device): submits the scanned payload, waits for the owner's
 *    Accept, then hands the fresh session tokens to the host for redirect.
 *
 * Every network call is wrapped so transient connectivity loss shows an inline
 * retry state instead of a banner, and background polling simply keeps trying.
 */
class LinkedDevicesViewModel(
    private val api: DeviceLinkApi,
    private val settingsApi: SettingsApi,
    /** Scanner side: invoked once with the new session tokens so the host can adopt them and redirect. */
    private val onSessionLinked: (accessToken: String, refreshToken: String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    enum class Tab { DEVICES, QR, SCAN }

    data class State(
        val tab: Tab = Tab.DEVICES,
        // --- Devices list ---
        val sessions: List<SessionDto> = emptyList(),
        val sessionsLoading: Boolean = false,
        val sessionsError: String? = null,
        val removingSessionId: String? = null,
        // --- QR (owner) ---
        val challenge: LinkChallengeDto? = null,
        val qrSecondsRemaining: Long = 0,
        val qrRefreshing: Boolean = false,
        val qrError: String? = null,
        /** Set when another device has scanned the QR and is awaiting approval. */
        val scannerLabel: String? = null,
        val awaitingAccept: Boolean = false,
        val acceptSecondsRemaining: Long = 0,
        val acceptBusy: Boolean = false,
        val justLinked: Boolean = false,
        // --- Scan (new device) ---
        val scanBusy: Boolean = false,
        val scanError: String? = null,
        val waitingForApproval: Boolean = false,
        val redirecting: Boolean = false,
        /** 0f..1f progress for the redirect indicator. */
        val redirectProgress: Float = 0f
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var qrTicker: Job? = null
    private var scanPoller: Job? = null
    private var scannedChallengeId: String? = null
    private var scannedSecret: String? = null

    fun selectTab(tab: Tab) {
        _state.value = _state.value.copy(tab = tab, qrError = null, scanError = null)
        when (tab) {
            Tab.DEVICES -> loadSessions()
            Tab.QR -> ensureChallenge()
            Tab.SCAN -> Unit
        }
    }

    // ---------------------------------------------------------------- devices

    fun loadSessions() {
        scope.launch {
            _state.value = _state.value.copy(sessionsLoading = _state.value.sessions.isEmpty(), sessionsError = null)
            runCatching { settingsApi.listSessions(AuthSession.refreshToken) }
                .onSuccess { _state.value = _state.value.copy(sessions = it, sessionsLoading = false) }
                .onFailure {
                    _state.value = _state.value.copy(
                        sessionsLoading = false,
                        // Keep the last-known list on screen; only flag an error when there's nothing to show.
                        sessionsError = if (_state.value.sessions.isEmpty())
                            "Couldn't load devices. Check your connection and try again." else null
                    )
                }
        }
    }

    fun removeSession(sessionId: String) {
        if (_state.value.removingSessionId != null) return
        scope.launch {
            _state.value = _state.value.copy(removingSessionId = sessionId)
            runCatching { settingsApi.revokeSession(sessionId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        sessions = _state.value.sessions.filterNot { it.sessionId == sessionId },
                        removingSessionId = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        removingSessionId = null,
                        sessionsError = "Couldn't remove that device. Try again."
                    )
                }
        }
    }

    fun clearSessionsError() {
        _state.value = _state.value.copy(sessionsError = null)
    }

    // ---------------------------------------------------------------- QR (owner)

    /** Creates the first challenge or refreshes an expired one. */
    fun ensureChallenge() {
        val current = _state.value
        if (current.qrRefreshing) return
        // Reuse a still-valid challenge (e.g. user switched tabs and came back).
        if (current.challenge != null && current.qrSecondsRemaining > 2) return
        refreshChallenge()
    }

    /** Mints a brand-new QR code; the previous one is invalidated server-side. */
    fun refreshChallenge() {
        if (_state.value.qrRefreshing) return
        qrTicker?.cancel()
        scope.launch {
            _state.value = _state.value.copy(
                qrRefreshing = true, qrError = null,
                scannerLabel = null, awaitingAccept = false, acceptSecondsRemaining = 0
            )
            runCatching { api.createChallenge() }
                .onSuccess { challenge ->
                    _state.value = _state.value.copy(
                        challenge = challenge,
                        qrSecondsRemaining = challenge.expiresInSeconds,
                        qrRefreshing = false
                    )
                    startQrTicker(challenge)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        qrRefreshing = false,
                        qrError = "Couldn't generate a QR code. Check your connection and try again."
                    )
                }
        }
    }

    private fun startQrTicker(challenge: LinkChallengeDto) {
        qrTicker = scope.launch {
            var pollAfterSeconds = 0L
            while (isActive) {
                delay(1_000)
                val s = _state.value
                if (s.tab != Tab.QR) continue
                val remaining = s.qrSecondsRemaining - 1

                // Poll the server while the QR is live: detects a scan (Accept prompt)
                // and confirms acceptance.
                if (pollAfterSeconds-- <= 0L && s.challenge != null && !s.justLinked) {
                    pollAfterSeconds = 2 // every ~2s
                    runCatching { api.challengeStatus(challenge.challengeId) }
                        .onSuccess { status ->
                            when (status.status) {
                                "SCANNED" -> _state.value = _state.value.copy(
                                    awaitingAccept = true,
                                    scannerLabel = status.scannerLabel,
                                    acceptSecondsRemaining = status.acceptSecondsRemaining
                                )
                                "ACCEPTED", "CONSUMED" -> {
                                    _state.value = _state.value.copy(
                                        justLinked = true, awaitingAccept = false,
                                        acceptSecondsRemaining = 0
                                    )
                                    loadSessions() // the new device appears in the list
                                    delay(1_800) // let the "Device linked" confirmation show
                                    _state.value = _state.value.copy(justLinked = false)
                                    refreshChallenge()
                                }
                                "EXPIRED", "INVALIDATED" -> _state.value = _state.value.copy(qrSecondsRemaining = 0)
                            }
                        }
                        // Offline: silently skip this poll; the countdown keeps running
                        // from the server-provided expiry so nothing goes stale client-side.
                }

                val now = _state.value
                if (remaining <= 0 && !now.awaitingAccept && !now.justLinked) {
                    // Countdown hit zero: refresh immediately (invalidates the old code).
                    refreshChallenge()
                    return@launch
                }
                _state.value = _state.value.copy(
                    qrSecondsRemaining = maxOf(0L, remaining),
                    acceptSecondsRemaining =
                        if (now.awaitingAccept) maxOf(0L, now.acceptSecondsRemaining - 1) else 0L,
                    // The accept window passed without a decision: drop the prompt.
                    awaitingAccept = if (now.awaitingAccept && now.acceptSecondsRemaining <= 1) false else now.awaitingAccept,
                    scannerLabel = if (now.awaitingAccept && now.acceptSecondsRemaining <= 1) null else now.scannerLabel
                )
            }
        }
    }

    fun acceptLink() {
        val challenge = _state.value.challenge ?: return
        if (_state.value.acceptBusy) return
        scope.launch {
            _state.value = _state.value.copy(acceptBusy = true)
            runCatching { api.accept(challenge.challengeId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        acceptBusy = false, awaitingAccept = false,
                        justLinked = true, acceptSecondsRemaining = 0
                    )
                    loadSessions()
                    delay(1_800)
                    _state.value = _state.value.copy(justLinked = false)
                    refreshChallenge()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        acceptBusy = false, awaitingAccept = false,
                        qrError = if (e is LinkChallengeInvalidException)
                            "This link request expired before it was approved."
                        else "Couldn't approve this device. Check your connection and try again."
                    )
                }
        }
    }

    fun declineLink() {
        val challenge = _state.value.challenge ?: return
        scope.launch {
            runCatching { api.decline(challenge.challengeId) }
            _state.value = _state.value.copy(
                awaitingAccept = false, scannerLabel = null, acceptSecondsRemaining = 0
            )
            // The old QR is now dead (its challenge was invalidated) — mint a fresh one.
            refreshChallenge()
        }
    }

    // ---------------------------------------------------------------- scan (new device)

    /** Parses `telefam://link-device?c=<id>&k=<secret>` and presents it to the owner. */
    fun onQrScanned(raw: String) {
        if (_state.value.scanBusy || _state.value.waitingForApproval || _state.value.redirecting) return
        val parsed = parsePayload(raw)
        if (parsed == null) {
            _state.value = _state.value.copy(scanError = "That code isn't a Telefam device link.")
            return
        }
        val (challengeId, secret) = parsed
        _state.value = _state.value.copy(scanBusy = true, scanError = null)
        scope.launch {
            runCatching { api.scan(challengeId, secret, deviceLabel()) }
                .onSuccess {
                    scannedChallengeId = challengeId
                    scannedSecret = secret
                    _state.value = _state.value.copy(scanBusy = false, waitingForApproval = true)
                    startScanPoller()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        scanBusy = false,
                        scanError = if (e is LinkChallengeInvalidException)
                            "This QR code has expired. Ask for a fresh code and scan again."
                        else "Couldn't reach the server. Check your connection and try again."
                    )
                }
        }
    }

    fun cancelWaiting() {
        scanPoller?.cancel()
        scannedChallengeId = null
        scannedSecret = null
        _state.value = _state.value.copy(waitingForApproval = false, scanError = null)
    }

    private fun startScanPoller() {
        scanPoller?.cancel()
        scanPoller = scope.launch {
            val challengeId = scannedChallengeId ?: return@launch
            val secret = scannedSecret ?: return@launch
            while (isActive) {
                delay(2_000)
                try {
                    val result = api.fetchResult(challengeId, secret)
                    when (result.status) {
                        "ACCEPTED" -> {
                            val access = result.accessToken
                            val refresh = result.refreshToken
                            if (access != null && refresh != null) {
                                _state.value = _state.value.copy(waitingForApproval = false, redirecting = true, redirectProgress = 0f)
                                // Hand the session to the host, then animate the redirect.
                                onSessionLinked(access, refresh)
                                var p = 0f
                                while (p < 1f && isActive) {
                                    delay(120)
                                    p += 0.08f
                                    _state.value = _state.value.copy(redirectProgress = minOf(1f, p))
                                }
                                return@launch
                            }
                        }
                        else -> Unit // PENDING / SCANNED: keep waiting
                    }
                } catch (e: LinkChallengeInvalidException) {
                    _state.value = _state.value.copy(
                        waitingForApproval = false,
                        scanError = "The link request was declined or expired. Scan a fresh code to try again."
                    )
                    return@launch
                } catch (e: Exception) {
                    // Offline blip: keep polling — the 5-minute accept window runs server-side.
                }
            }
        }
    }

    fun clearScanError() {
        _state.value = _state.value.copy(scanError = null)
    }

    private fun deviceLabel(): String =
        "${platformName()} ${deviceModel()}".trim().ifBlank { "Linked device" }

    /** Best-effort platform/model label, overridden per platform if needed. */
    private fun platformName(): String = com.telefam.devices.PlatformInfo.name
    private fun deviceModel(): String = com.telefam.devices.PlatformInfo.model

    companion object {
        fun parsePayload(raw: String): Pair<String, String>? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("telefam://link-device")) return null
            val query = trimmed.substringAfter('?', "")
            val params = query.split('&')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
                }.toMap()
            val id = params["c"]?.takeIf { it.length in 32..40 }
            val secret = params["k"]?.takeIf { it.length in 32..128 }
            return if (id != null && secret != null) id to secret else null
        }
    }
}
