package com.telefam

import com.telefam.auth.*
import com.telefam.config.AppConfig
import com.telefam.db.DatabaseFactory
import com.telefam.email.ResendClient
import com.telefam.media.MediaProcessor
import com.telefam.plugins.configureRateLimiting
import com.telefam.plugins.configureSecurity
import com.telefam.privacy.PresenceService
import com.telefam.privacy.PrivacyService
import com.telefam.privacy.SocialService
import com.telefam.privacy.ChatPreferencesService
import com.telefam.privacy.ReportService
import com.telefam.e2ee.E2EEService
import com.telefam.realtime.RealtimeService
import com.telefam.routes.authRoutes
import com.telefam.routes.notificationRoutes
import com.telefam.routes.accountRoutes
import com.telefam.routes.legalRoutes
import com.telefam.routes.chatPreferencesRoutes
import com.telefam.routes.realtimeRoutes
import com.telefam.routes.callRoutes
import com.telefam.routes.reportRoutes
import com.telefam.routes.userDirectoryRoutes
import com.telefam.routes.e2eeRoutes
import com.telefam.routes.mediaRoutes
import com.telefam.routes.privacyRoutes
import com.telefam.routes.postRoutes
import com.telefam.routes.feedRoutes
import com.telefam.routes.profileRoutes
import com.telefam.routes.socialRoutes
import com.telefam.routes.connectRoutes
import com.telefam.connect.ConnectService
import com.telefam.payments.PaymentService
import com.telefam.verification.VerificationService
import com.telefam.routes.verificationRoutes
import com.telefam.routes.creatorRoutes
import com.telefam.routes.subscriptionRoutes
import com.telefam.routes.walletRoutes
import com.telefam.routes.campaignRoutes
import com.telefam.routes.commentRoutes
import com.telefam.routes.deviceLinkRoutes
import com.telefam.subscriptions.SubscriptionService
import com.telefam.posts.ChunkedUploadService
import com.telefam.posts.FeedService
import com.telefam.posts.SignedUrlService
import com.telefam.posts.VideoJobQueue
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

fun main() {
    DatabaseFactory.init()
    embeddedServer(Netty, port = AppConfig.port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = java.time.Duration.ofSeconds(20)
        timeout = java.time.Duration.ofSeconds(40)
        maxFrameSize = 16 * 1024 // metadata-only frames; anything bigger is abuse
    }
    install(CallLogging)
    install(CORS) {
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // Restrict to real app origins in production via env-driven config if serving web clients.
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "Something went wrong"))
        }
    }

    val resendClient = ResendClient()
    val jwtService = JwtService()
    val otpService = OtpService(resendClient)
    val loginAttemptService = LoginAttemptService()
    val authService = AuthService(otpService, jwtService, loginAttemptService)
    val googleVerifier = GoogleAuthVerifier()
    val appleVerifier = AppleAuthVerifier()
    val mediaProcessor = MediaProcessor()
    val presenceService = PresenceService()
    val realtimeService = RealtimeService(presenceService)
    val pushNotifier = com.telefam.calls.pushNotifierFromConfig()
    val callSignalingService = com.telefam.calls.CallSignalingService(pushNotifier)
    val socialService = SocialService(onBlockChanged = { blocker, blocked ->
        realtimeService.onPrivacyChanged(blocker)
        realtimeService.onPrivacyChanged(blocked)
    })
    // Changing who may see "last seen" must take effect for people already watching, not just on their next reconnect.
    val privacyService = PrivacyService(onFieldChanged = { userId, field ->
        if (field == "whoCanSeeLastSeen") realtimeService.onPrivacyChanged(userId)
    })
    // The official Telefam account exists before any route can touch it.
    kotlinx.coroutines.runBlocking {
        runCatching { com.telefam.official.OfficialAccountService.ensureExists() }
            .onFailure { log.error("Official account seeding failed", it) }
    }
    // Every in-app notification is mirrored to devices as a push with its deep-link payload.
    com.telefam.notifications.NotificationService.pushHook = { recipientId, title, body, targetType, targetId, notificationId ->
        pushNotifier.notifyNotification(recipientId, title, body, targetType, targetId, notificationId)
    }

    val e2eeService = E2EEService(
        onEnvelopeStored = realtimeService::notifyMailbox,
        onMessageStored = { recipientId, senderId ->
            // Content-free wake-up push so backgrounded/killed apps pull the mailbox,
            // decrypt locally, and show the real Reply / Mark-as-read notification.
            val senderName = runCatching {
                com.telefam.db.DatabaseFactory.dbQuery {
                    com.telefam.db.Users.selectAll().where { com.telefam.db.Users.id eq senderId }.singleOrNull()
                        ?.let { it[com.telefam.db.Users.fullName] ?: it[com.telefam.db.Users.username] }
                }
            }.getOrNull() ?: "Telefam user"
            pushNotifier.notifyNewMessage(recipientId, senderId, senderName)
        }
    )
    val chatPreferencesService = ChatPreferencesService()
    val reportService = ReportService()
    val accountService = com.telefam.settings.AccountService(jwtService)

    // Post pipeline: bounded worker pool + chunked intake + signed delivery.
    val videoJobQueue = VideoJobQueue(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
    videoJobQueue.start()
    val chunkedUploadService = ChunkedUploadService(videoJobQueue)
    val signedUrlService = SignedUrlService()

    configureSecurity(jwtService)
    configureRateLimiting()
    val walletService = com.telefam.wallet.WalletService(otpService)
    val subscriptionService = SubscriptionService(wallet = walletService)
    val campaignService = com.telefam.campaigns.CampaignService(signedUrls = signedUrlService)

    // Campaign expiry sweep: campaigns end when their paid window passes or their
    // reach cap is met (reach is flipped inline at impression time); series expire
    // at the end of their paid days. DB-backed so multiple instances are safe.
    launch {
        while (isActive) {
            runCatching { campaignService.sweepExpired() }
                .onFailure { log.error("Campaign expiry sweep failed", it) }
            delay(60_000)
        }
    }

    // The database uniqueness/idempotency keys make this safe if more than one
    // backend instance runs the maintenance loop at the same time.
    launch {
        while (isActive) {
            runCatching { subscriptionService.billingMaintenance() }
                .onFailure { log.error("Subscription billing maintenance failed", it) }
            delay(60_000)
        }
    }

    // Settlement worker: periodically moves due PENDING earnings through the
    // eligibility check into AVAILABLE, writing SETTLEMENT ledger entries.
    // The DB uniqueness/idempotency guards make concurrent instances safe.
    launch {
        while (isActive) {
            runCatching { walletService.settleDueEarnings() }
                .onFailure { log.error("Wallet settlement sweep failed", it) }
            delay(com.telefam.config.AppConfig.settlementSweepSeconds * 1000)
        }
    }

    routing {
        authRoutes(authService, otpService, jwtService, loginAttemptService, googleVerifier, appleVerifier)
        profileRoutes(authService)
        accountRoutes(accountService, authService, otpService)
        legalRoutes()
        mediaRoutes(mediaProcessor)
        privacyRoutes(privacyService)
        socialRoutes(socialService)
        e2eeRoutes(e2eeService)
        realtimeRoutes(realtimeService)
        callRoutes(callSignalingService)
        chatPreferencesRoutes(chatPreferencesService)
        reportRoutes(reportService)
        userDirectoryRoutes()
        postRoutes(chunkedUploadService, signedUrlService)
        feedRoutes(FeedService(signedUrlService, campaignService))
        commentRoutes(com.telefam.comments.CommentService(walletService))
        connectRoutes(ConnectService())
        verificationRoutes(PaymentService(), VerificationService())
        creatorRoutes(com.telefam.creator.CreatorService())
        subscriptionRoutes(subscriptionService)
        walletRoutes(walletService)
        campaignRoutes(campaignService)
        deviceLinkRoutes(com.telefam.devices.DeviceLinkService(jwtService))
        notificationRoutes()

        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
    }
}
