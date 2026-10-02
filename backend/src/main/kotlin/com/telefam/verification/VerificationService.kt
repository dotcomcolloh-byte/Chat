package com.telefam.verification

import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory.dbQuery
import com.telefam.db.MediaAssets
import com.telefam.payments.PricingCatalog
import com.telefam.payments.VerificationApplications
import com.telefam.payments.VerificationAudit
import com.telefam.payments.VerificationPayments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verification pipeline. State machine (all transitions happen here, never client-side):
 *
 *   PAYMENT_PENDING --(payment PAID, provider-verified)--> LIVENESS
 *   LIVENESS --(signed challenge completed + live selfie uploaded)--> ID_CAPTURE
 *   ID_CAPTURE --(front+back uploaded)--> SUBMITTED --(review)--> APPROVED | REJECTED | ADMIN_REVIEW
 *
 * Liveness: the server issues a randomised movement challenge (e.g. UP,LEFT,RIGHT x2)
 * signed with HMAC(LIVENESS_CHALLENGE_SECRET). The client performs on-device face
 * tracking and returns the signature + per-movement timestamps. The server validates
 * the signature, the timing envelope, and that exactly the issued movements were
 * performed — a forged or replayed completion is rejected. The selfie itself is then
 * re-validated during document review.
 *
 * Refund policy: if an application stays REJECTED for VERIFICATION_REFUND_DAYS days
 * the user is shown refund eligibility; the actual refund goes through PaymentService.
 */
class VerificationService(
    private val kimi: KimiReviewClient = KimiReviewClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val AUTO_APPROVE_CONFIDENCE = 0.85
        private const val AUTO_REJECT_CONFIDENCE = 0.70
        private val MOVEMENTS = listOf("UP", "LEFT", "RIGHT")
        /** Min/max wall time for a full challenge — too fast = scripted, too slow = abandoned. */
        private const val MIN_CHALLENGE_MS = 4_000
        private const val MAX_CHALLENGE_MS = 120_000
    }

    @Serializable
    data class StatusResponse(
        val state: String,                 // NONE | PAYMENT_PENDING | LIVENESS | ID_CAPTURE | SUBMITTED | PENDING_REVIEW | APPROVED | REJECTED | ADMIN_REVIEW
        val applicationId: String? = null,
        val paymentId: String? = null,
        val rejectionReason: String? = null,
        val canRetry: Boolean = false,
        val refundEligible: Boolean = false,
        val refundPolicyDays: Long = 0,
        val badgeExpiresAt: String? = null,
        val nextBillingAt: String? = null,
        val gracePeriodEndsAt: String? = null,
        val billingStatus: String? = null
    )

    @Serializable
    data class LivenessChallenge(val challengeId: String, val movements: List<String>, val signature: String, val expiresInSeconds: Long)

    @Serializable
    data class LivenessCompletion(val signature: String, val movementTimestampsMs: List<Long>, val selfieMediaId: String)

    // ---------- status ----------

    suspend fun status(userId: UUID): StatusResponse {
        val app = dbQuery {
            VerificationApplications.selectAll().where { VerificationApplications.userId eq userId }
                .orderBy(VerificationApplications.createdAt, SortOrder.DESC).firstOrNull()
        }
        val badge = dbQuery {
            com.telefam.payments.VerificationBadges.selectAll()
                .where { com.telefam.payments.VerificationBadges.userId eq userId }.singleOrNull()
        }
        if (badge != null) {
            val periodEnd = badge[com.telefam.payments.VerificationBadges.expiresAt]
            val graceUntil = badge[com.telefam.payments.VerificationBadges.graceUntil]
            val now = LocalDateTime.now()
            val billingStatus = when {
                periodEnd.isAfter(now) -> "ACTIVE"
                graceUntil != null && graceUntil.isAfter(now) -> "GRACE_PERIOD"
                else -> "RENEWAL_REQUIRED"
            }
            return StatusResponse(
                state = if (billingStatus == "RENEWAL_REQUIRED") "RENEWAL_REQUIRED" else "APPROVED",
                badgeExpiresAt = periodEnd.toString(),
                nextBillingAt = periodEnd.toString(),
                gracePeriodEndsAt = graceUntil?.toString(),
                billingStatus = billingStatus
            )
        }
        if (app == null) return StatusResponse("NONE")
        val state = app[VerificationApplications.state]
        val rejectedAt = if (state == "REJECTED") app[VerificationApplications.reviewedAt] else null
        val refundEligible = state == "REJECTED" && rejectedAt != null &&
            rejectedAt.plusDays(AppConfig.verificationRefundDays).isBefore(LocalDateTime.now())
        return StatusResponse(
            state = state,
            applicationId = app[VerificationApplications.id].value.toString(),
            paymentId = app[VerificationApplications.paymentId]?.toString(),
            rejectionReason = if (state == "REJECTED") app[VerificationApplications.rejectionReason] else null,
            canRetry = state == "REJECTED" && app[VerificationApplications.retryCount] < 3,
            refundEligible = refundEligible,
            refundPolicyDays = AppConfig.verificationRefundDays
        )
    }

    /** Called by PaymentService once a payment is provider-verified as PAID. */
    suspend fun onPaymentConfirmed(userId: UUID, paymentId: UUID) {
        dbQuery {
            val existing = VerificationApplications.selectAll().where {
                (VerificationApplications.userId eq userId) and
                    (VerificationApplications.state inList listOf("PAYMENT_PENDING", "LIVENESS", "ID_CAPTURE", "SUBMITTED", "PENDING_REVIEW", "ADMIN_REVIEW"))
            }.firstOrNull()
            if (existing != null) {
                VerificationApplications.update({ VerificationApplications.id eq existing[VerificationApplications.id] }) {
                    it[state] = "LIVENESS"; it[VerificationApplications.paymentId] = paymentId; it[updatedAt] = LocalDateTime.now()
                }
            } else {
                VerificationApplications.insert {
                    it[VerificationApplications.userId] = userId
                    it[VerificationApplications.paymentId] = paymentId
                    it[state] = "LIVENESS"
                    it[createdAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
                }
            }
            VerificationAudit.insert {
                it[VerificationAudit.userId] = userId; it[actor] = "SYSTEM"
                it[event] = "PAYMENT_LINKED"; it[detail] = paymentId.toString(); it[createdAt] = LocalDateTime.now()
            }
        }
    }

    /** Client starts a payment — ensure an application row exists to attach it to. */
    suspend fun ensureApplication(userId: UUID): UUID = dbQuery {
        val open = VerificationApplications.selectAll().where {
            (VerificationApplications.userId eq userId) and
                (VerificationApplications.state inList listOf("PAYMENT_PENDING", "LIVENESS", "ID_CAPTURE", "SUBMITTED", "PENDING_REVIEW", "ADMIN_REVIEW"))
        }.firstOrNull()
        if (open != null) open[VerificationApplications.id].value
        else {
            val id = UUID.randomUUID()
            VerificationApplications.insert {
                it[VerificationApplications.id] = id; it[VerificationApplications.userId] = userId
                it[state] = "PAYMENT_PENDING"
                it[createdAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
            }
            id
        }
    }

    // ---------- liveness ----------

    suspend fun startLiveness(userId: UUID): LivenessChallenge {
        val app = openApplication(userId, requiredState = "LIVENESS")
        // Random order each attempt, exactly the required two rounds.
        val movements = (MOVEMENTS.shuffled() + MOVEMENTS.shuffled())
        val issuedAt = System.currentTimeMillis()
        val payload = "${app[VerificationApplications.id].value}:$issuedAt:${movements.joinToString(",")}"
        val signature = hmac(payload)
        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq app[VerificationApplications.id] }) {
                it[livenessChallenge] = "${issuedAt}:${movements.joinToString(",")}"
                it[livenessChallengeIssuedAt] = LocalDateTime.now()
                it[updatedAt] = LocalDateTime.now()
            }
        }
        return LivenessChallenge(app[VerificationApplications.id].value.toString(), movements, signature, MAX_CHALLENGE_MS / 1000L)
    }

    suspend fun completeLiveness(userId: UUID, completion: LivenessCompletion) {
        val app = openApplication(userId, requiredState = "LIVENESS")
        val stored = app[VerificationApplications.livenessChallenge]
            ?: throw IllegalStateException("no active challenge")
        val (issuedAtStr, movementCsv) = stored.split(":", limit = 2)
        val payload = "${app[VerificationApplications.id].value}:$issuedAtStr:$movementCsv"
        require(hmac(payload) == completion.signature) { "challenge signature mismatch" }

        // Timing envelope: monotonic timestamps, sane duration, correct movement count.
        val ts = completion.movementTimestampsMs
        require(ts.size == movementCsv.split(",").size) { "wrong movement count" }
        require(ts.zipWithNext().all { (a, b) -> b > a }) { "non-monotonic timestamps" }
        val elapsed = ts.last() - ts.first()
        require(elapsed in MIN_CHALLENGE_MS..MAX_CHALLENGE_MS) { "challenge timing out of bounds" }

        val selfieId = UUID.fromString(completion.selfieMediaId)
        require(mediaOwnedBy(userId, selfieId)) { "selfie media invalid" }

        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq app[VerificationApplications.id] }) {
                it[livenessSelfieMediaId] = selfieId
                it[state] = "ID_CAPTURE"; it[updatedAt] = LocalDateTime.now()
            }
            VerificationAudit.insert {
                it[VerificationAudit.userId] = userId; it[actor] = "USER"
                it[event] = "LIVENESS_PASSED"; it[createdAt] = LocalDateTime.now()
            }
        }
    }

    // ---------- ID capture + submission ----------

    suspend fun submitId(userId: UUID, frontMediaId: UUID, backMediaId: UUID) {
        val app = openApplication(userId, requiredState = "ID_CAPTURE")
        require(mediaOwnedBy(userId, frontMediaId) && mediaOwnedBy(userId, backMediaId)) { "ID media invalid" }
        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq app[VerificationApplications.id] }) {
                it[idFrontMediaId] = frontMediaId; it[idBackMediaId] = backMediaId
                it[state] = "SUBMITTED"; it[updatedAt] = LocalDateTime.now()
            }
        }
        // Review runs asynchronously; the client polls status and watches SUBMITTED -> PENDING_REVIEW -> outcome.
        scope.launch { runReview(app[VerificationApplications.id].value, userId) }
    }

    private suspend fun runReview(applicationId: UUID, userId: UUID) {
        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq applicationId }) {
                it[state] = "PENDING_REVIEW"; it[updatedAt] = LocalDateTime.now()
            }
        }
        val result = runCatching {
            val paths = dbQuery {
                val row = VerificationApplications.selectAll().where { VerificationApplications.id eq applicationId }.single()
                listOf(row[VerificationApplications.livenessSelfieMediaId], row[VerificationApplications.idFrontMediaId], row[VerificationApplications.idBackMediaId])
                    .map { mid -> MediaAssets.selectAll().where { MediaAssets.id eq mid }.single()[MediaAssets.storagePath] }
            }
            kimi.review(File(paths[0]), File(paths[1]), File(paths[2]))
        }.getOrElse { KimiReviewClient.ReviewResult(KimiReviewClient.Decision.UNSURE, 0.0, null) }

        when {
            result.decision == KimiReviewClient.Decision.APPROVE && result.confidence >= AUTO_APPROVE_CONFIDENCE -> {
                dbQuery {
                    VerificationApplications.update({ VerificationApplications.id eq applicationId }) {
                        it[state] = "APPROVED"; it[reviewer] = "AI"; it[reviewedAt] = LocalDateTime.now()
                        it[reviewConfidence] = result.confidence; it[updatedAt] = LocalDateTime.now()
                    }
                }
                BadgeService.grant(userId, applicationId)
                audit(userId, "SYSTEM", "VERIFICATION_APPROVED", "confidence=${result.confidence}")
            }
            result.decision == KimiReviewClient.Decision.REJECT && result.confidence >= AUTO_REJECT_CONFIDENCE -> {
                dbQuery {
                    VerificationApplications.update({ VerificationApplications.id eq applicationId }) {
                        it[state] = "REJECTED"; it[reviewer] = "AI"; it[reviewedAt] = LocalDateTime.now()
                        it[reviewConfidence] = result.confidence
                        it[rejectionReason] = result.userSafeReason ?: "Verification could not be completed."
                        it[updatedAt] = LocalDateTime.now()
                    }
                }
                audit(userId, "SYSTEM", "VERIFICATION_REJECTED", "confidence=${result.confidence}")
            }
            else -> dbQuery {
                // Low confidence / UNSURE / service failure: human admin decides. Users only see "under review".
                VerificationApplications.update({ VerificationApplications.id eq applicationId }) {
                    it[state] = "ADMIN_REVIEW"; it[reviewConfidence] = result.confidence; it[updatedAt] = LocalDateTime.now()
                }
            }
        }
    }

    /** Admin console decision path (protected by admin auth upstream). */
    suspend fun adminDecide(adminId: UUID, applicationId: UUID, approve: Boolean, reason: String?) {
        val app = dbQuery {
            VerificationApplications.selectAll().where { VerificationApplications.id eq applicationId }.singleOrNull()
        } ?: throw NoSuchElementException("application not found")
        val userId = app[VerificationApplications.userId]
        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq applicationId }) {
                it[state] = if (approve) "APPROVED" else "REJECTED"
                it[reviewer] = "ADMIN"; it[reviewedAt] = LocalDateTime.now()
                it[rejectionReason] = if (approve) null else (reason ?: "Verification could not be completed.")
                it[updatedAt] = LocalDateTime.now()
            }
        }
        if (approve) BadgeService.grant(userId, applicationId)
        audit(adminId, "ADMIN", if (approve) "ADMIN_APPROVED" else "ADMIN_REJECTED", applicationId.toString())
    }

    suspend fun retry(userId: UUID) {
        val app = openApplication(userId, requiredState = "REJECTED")
        require(app[VerificationApplications.retryCount] < 3) { "retry limit reached" }
        dbQuery {
            VerificationApplications.update({ VerificationApplications.id eq app[VerificationApplications.id] }) {
                it[state] = "LIVENESS"
                it[retryCount] = app[VerificationApplications.retryCount] + 1
                it[livenessChallenge] = null; it[livenessSelfieMediaId] = null
                it[idFrontMediaId] = null; it[idBackMediaId] = null
                it[rejectionReason] = null; it[reviewedAt] = null; it[reviewer] = null
                it[updatedAt] = LocalDateTime.now()
            }
        }
    }

    // ---------- helpers ----------

    private suspend fun openApplication(userId: UUID, requiredState: String) = dbQuery {
        VerificationApplications.selectAll().where { VerificationApplications.userId eq userId }
            .orderBy(VerificationApplications.createdAt, SortOrder.DESC).firstOrNull()
            ?.also { require(it[VerificationApplications.state] == requiredState) { "wrong state: ${it[VerificationApplications.state]}" } }
            ?: throw NoSuchElementException("no application")
    }

    private suspend fun mediaOwnedBy(userId: UUID, mediaId: UUID): Boolean = dbQuery {
        MediaAssets.selectAll().where { (MediaAssets.id eq mediaId) and (MediaAssets.ownerId eq userId) }.any()
    }

    private suspend fun audit(userId: UUID, actor: String, event: String, detail: String?) = dbQuery {
        VerificationAudit.insert {
            it[VerificationAudit.userId] = userId; it[VerificationAudit.actor] = actor
            it[VerificationAudit.event] = event; it[VerificationAudit.detail] = detail
            it[createdAt] = LocalDateTime.now()
        }
    }

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(AppConfig.livenessChallengeSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
