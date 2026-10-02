package com.telefam.db

import com.telefam.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import javax.sql.DataSource

object DatabaseFactory {
    private lateinit var dataSource: DataSource

    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = AppConfig.databaseUrl
            username = AppConfig.databaseUser
            password = AppConfig.databasePassword
            maximumPoolSize = AppConfig.databasePoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users, LoginAttempts, OtpCodes, RefreshTokens, MediaAssets,
                PrivacySettings, MessageRequests, BlockedUsers, ArchivedChats,
                DeviceIdentities, SignedPreKeys, OneTimePreKeys, MessageEnvelopes,
                ChatPreferences, Reports,
                com.telefam.posts.UploadSessions, com.telefam.posts.Posts,
                com.telefam.posts.PostMedia, com.telefam.posts.MediaFingerprints,
                com.telefam.posts.Follows, com.telefam.posts.PostLikes,
                com.telefam.posts.PostSaves, com.telefam.posts.PostViews,
                com.telefam.posts.PostReshares, com.telefam.posts.NotInterested,
                com.telefam.posts.PostReports,
                com.telefam.connect.ContactHashes, com.telefam.connect.FollowRateLimits,
                com.telefam.connect.UserRiskScores,
                Subscriptions, AppSettings, ProblemReports, PendingEmailChanges,
                com.telefam.payments.VerificationPayments, com.telefam.payments.VerificationApplications,
                com.telefam.payments.VerificationBadges, com.telefam.payments.VerificationAudit,
                com.telefam.creator.MonetizationApplications, com.telefam.creator.StarTransactions,
                com.telefam.creator.StarGoals,
                com.telefam.subscriptions.SubscriptionPlans, com.telefam.subscriptions.PaidSubscriptions,
                com.telefam.subscriptions.SubscriptionPayments, com.telefam.subscriptions.SubscriptionRefunds,
                com.telefam.wallet.CreatorEarnings, com.telefam.wallet.WalletLedgerEntries,
                com.telefam.wallet.PayoutRequests, com.telefam.wallet.PayoutMethods,
                com.telefam.wallet.PayoutMethodChanges, com.telefam.wallet.WalletIdempotency,
                com.telefam.wallet.WalletEvents,
                com.telefam.campaigns.StarWallets, com.telefam.campaigns.StarLedgerEntries,
                com.telefam.campaigns.StarPurchases, com.telefam.campaigns.Campaigns,
                com.telefam.campaigns.VideoSeries, com.telefam.campaigns.SeriesItems,
                com.telefam.campaigns.SponsoredEvents, com.telefam.campaigns.SponsoredDeliveries,
                com.telefam.comments.PostComments, com.telefam.comments.CommentLikes,
                com.telefam.comments.CommentReports, com.telefam.comments.CommentStarGifts,
                com.telefam.calls.DeviceTokens,
                com.telefam.devices.DeviceLinkChallenges,
                com.telefam.risk.RiskEvents,
                com.telefam.notifications.Notifications,
                com.telefam.notifications.SystemMessages
            )
            // Existing deployments need these indexes too; createMissingTablesAndColumns
            // does not reliably retrofit every newly-declared index.
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_posts_status_published ON posts (status, published_at DESC)"
            )
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_posts_owner_published ON posts (owner_id, published_at DESC)"
            )
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_post_media_post_kind ON post_media (post_id, kind)"
            )
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_subrefund_status_updated ON subscription_refunds (status, updated_at)"
            )
            TransactionManager.current().exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_refunds_payment ON subscription_refunds (payment_id)"
            )
            TransactionManager.current().exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_refunds_request ON subscription_refunds (request_id)"
            )
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_comments_post_created ON post_comments (post_id, parent_id, created_at)"
            )
            TransactionManager.current().exec(
                "CREATE INDEX IF NOT EXISTS idx_payout_public_ref ON payout_requests (public_ref)"
            )
            applyRowLevelSecurityPolicies()
        }
    }

    /** Executes rls_policies.sql on boot so RLS is always in effect, even after a fresh migration. */
    private fun applyRowLevelSecurityPolicies() {
        val raw = object {}.javaClass.getResourceAsStream("/db/rls_policies.sql")
            ?.bufferedReader()?.readText() ?: return
        val withoutComments = raw.lineSequence()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
        withoutComments.split(";").map { it.trim() }.filter { it.isNotBlank() }
            .forEach { statement ->
                org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(statement)
            }
    }

    /** All DB access goes through Exposed transactions on the IO dispatcher — never blocks Netty's event loop. */
    suspend fun <T> dbQuery(block: () -> T): T =
        withContext(Dispatchers.IO) { transaction { block() } }
}
