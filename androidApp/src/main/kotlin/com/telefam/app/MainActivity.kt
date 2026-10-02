package com.telefam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.telefam.auth.GoogleAuthLauncher
import com.telefam.data.AuthSession
import com.telefam.data.TokenStorage
import com.telefam.data.api.ApiConfig
import com.telefam.data.api.AuthApi
import com.telefam.data.api.AuthTokens
import com.telefam.data.api.PrivacyApi
import com.telefam.data.api.PrivacySettingsDto
import com.telefam.data.api.ChatPreferencesApi
import com.telefam.data.api.ReportApi
import com.telefam.data.api.PostsApi
import com.telefam.data.api.SettingsApi
import com.telefam.posts.CreatePostViewModel
import com.telefam.ui.screens.CreatePostScreen
import com.telefam.ui.screens.TrimVideoScreen
import com.telefam.data.api.ChatPreferenceDto
import com.telefam.data.api.createTelefamHttpClient
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.telefam.chat.AppFiles
import com.telefam.chat.AudioPlayer
import com.telefam.chat.ChatLocalRepository
import com.telefam.chat.ChatService
import com.telefam.chat.ChatSettingsLocalRepository
import com.telefam.chat.ContactData
import com.telefam.chat.EventData
import com.telefam.chat.FileMeta
import com.telefam.chat.LocationData
import com.telefam.chat.PickKind
import com.telefam.chat.PlaceSearchApi
import com.telefam.chat.ThemeController
import com.telefam.chat.VoiceRecorder
import com.telefam.chat.callContact
import com.telefam.chat.getCurrentLocation
import com.telefam.chat.openContact
import com.telefam.chat.rememberCameraCapture
import com.telefam.chat.rememberContactPicker
import com.telefam.chat.rememberMediaPicker
import com.telefam.chat.saveContactToDevice
import com.telefam.chat.writeLocalFile
import com.telefam.data.api.DirectoryUserDto
import com.telefam.data.api.GiphyApi
import com.telefam.data.api.GiphyMode
import com.telefam.data.api.MediaBlobApi
import com.telefam.data.api.UserDirectoryApi
import com.telefam.db.local.LocalDatabase
import com.telefam.e2ee.AndroidSignalEngine
import com.telefam.data.api.E2EEApi
import com.telefam.e2ee.SignalIdentityKeyStorage
import com.telefam.e2ee.MessageRepository
import com.telefam.realtime.RealtimeClient
import com.telefam.realtime.RealtimeEvent
import com.telefam.ui.components.AttachmentType
import com.telefam.data.model.AccessLevel
import com.telefam.data.model.BubbleColour
import com.telefam.data.model.ChatsTheme
import com.telefam.data.offline.OfflineActionRepository
import com.telefam.data.offline.OfflineDependencies
import com.telefam.data.offline.OutboxSyncWorker
import com.telefam.db.local.DatabaseDriverFactory
import com.telefam.ui.screens.*
import com.telefam.ui.components.ConfirmDialog
import com.telefam.ui.theme.TelefamTheme
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import java.util.UUID

private sealed class Screen {    object SignUp : Screen()
    object Login : Screen()
    data class Otp(val email: String, val purpose: String) : Screen()
    object ProfileSetup : Screen()
    object Home : Screen()
    object Privacy : Screen()
    data class ChatOptions(val peerId: String, val peerName: String) : Screen()
    data class MuteOptions(val peerId: String) : Screen()
    data class DisappearingMessages(val peerId: String) : Screen()
    data class Report(val peerId: String) : Screen()
    data class MediaLinksDocs(val peerId: String) : Screen()
    data class PrivacyOption(val field: String) : Screen()
    object ChatsThemeScreen : Screen()
    object BubbleColourScreen : Screen()
    object MessageRequests : Screen()
    object Archived : Screen()
    object Blocked : Screen()
    data class Chat(val peerId: String, val peerName: String) : Screen()
    /** Notification centre (the "Notifications" system conversation). */
    object Notifications : Screen()
    /** The official Telefam conversation — read-only system messages. */
    object OfficialChat : Screen()
    object NewChat : Screen()
    data class PollComposer(val peerId: String, val peerName: String) : Screen()
    data class LocationPicker(val peerId: String, val peerName: String) : Screen()
    data class GifStickerPicker(val peerId: String, val peerName: String, val mode: GiphyMode) : Screen()
    data class ForwardPicker(val peerId: String, val peerName: String, val messageId: String) : Screen()
    object CreatePost : Screen()
    object TrimPostVideo : Screen()
    object Feeds : Screen()
    data class FeedSearch(val initialQuery: String) : Screen()
    data class EditPost(val postId: String) : Screen()
    object Contacts : Screen()
    data class Profile(val userId: String, val returnTo: Screen? = null) : Screen()
    data class FollowList(val userId: String, val kind: com.telefam.connect.ConnectionListKind) : Screen()
    data class ShareProfile(val userId: String) : Screen()
    data class EditProfile(val userId: String) : Screen()
    data class CreatorMenu(val userId: String, val returnTo: Screen? = null) : Screen()
    object Verification : Screen()
    // ---- Creator program ----
    data class Dashboard(val userId: String) : Screen()
    data class CreatorAnalytics(val periodDays: Int) : Screen()
    object ContentPerformance : Screen()
    data class Monetization(val userId: String) : Screen()
    object MonetizationPolicies : Screen()
    data class Stars(val userId: String) : Screen()
    object StarTransactions : Screen()
    object StarSupporters : Screen()
    data class StarsInsights(val periodDays: Int) : Screen()
    data class Terms(val returnTo: Screen) : Screen()
    data class LegalDoc(val key: String, val title: String, val returnTo: Screen) : Screen()
    // ---- Paid subscriptions ----
    data class Subscriptions(val userId: String) : Screen()
    data class SubscriptionInsights(val periodDays: Int) : Screen()
    object ManagePlans : Screen()
    object SubscribersList : Screen()
    object SubscriptionPlanEdit : Screen()
    data class SubscribeToCreator(val creatorId: String, val returnTo: Screen? = null) : Screen()
    data class MySubscriptions(val returnTo: Screen) : Screen()
    // ---- Wallet ----
    object Wallet : Screen()
    object WalletPending : Screen()
    data class WalletSourceEarnings(val source: String) : Screen()
    object WalletTransactions : Screen()
    data class WalletTransactionDetails(val transactionId: String) : Screen()
    object WalletWithdraw : Screen()
    object WalletPayouts : Screen()
    data class WalletPayoutDetails(val payoutId: String) : Screen()
    object WalletAddMethod : Screen()
    data class WalletMethodDetails(val methodId: String) : Screen()
    // ---- Stars store & paid campaigns ----
    data class CreateCampaign(val userId: String) : Screen()
    data class MyCampaigns(val userId: String) : Screen()
    data class SeriesVideos(val userId: String) : Screen()
    data class BuyStars(val returnTo: Screen) : Screen()
    data class StarCheckout(val returnTo: Screen) : Screen()
    // ---- Linked devices & settings ----
    object LinkedDevices : Screen()
    object Settings : Screen()
    object SettingsAccount : Screen()
    object SettingsSecurity : Screen()
    object ChangeEmail : Screen()
    object ChangePhone : Screen()
    object ChangePassword : Screen()
    object ContentPreferences : Screen()
    object TimeManagement : Screen()
    object AccessibilitySettings : Screen()
    object ReportProblem : Screen()
    object SafetyCenter : Screen()
    object HelpSupport : Screen()
    object About : Screen()
}

class MainActivity : ComponentActivity() {
    private val httpClient = com.telefam.data.api.HttpClientHolder.client
    private val authApi = AuthApi(httpClient)
    private val privacyApi = PrivacyApi(httpClient)
    private val chatPreferencesApi = ChatPreferencesApi(httpClient)
    private val reportApi = ReportApi(httpClient)
    private val verificationApi by lazy { com.telefam.data.api.VerificationApi(httpClient) }

    private val chatLocalRepository by lazy { ChatLocalRepository(DatabaseDriverFactory(applicationContext)) }
    private val chatSettingsLocalRepository by lazy { ChatSettingsLocalRepository(DatabaseDriverFactory(applicationContext)) }

    private val offlineRepository by lazy {
        OfflineActionRepository(httpClient, DatabaseDriverFactory(applicationContext))
    }

    private val userDirectoryApi by lazy { UserDirectoryApi(httpClient) }
    private val placeSearchApi by lazy { PlaceSearchApi(httpClient) }
    private val e2eeApi by lazy { E2EEApi(httpClient) }
    private val mediaBlobApi by lazy { MediaBlobApi(httpClient) }
    private val giphyApi by lazy { GiphyApi(httpClient) }
    private val realtimeClient by lazy { RealtimeClient(httpClient) }

    // --- Calls (WebRTC P2P; signaling only touches the server) ---
    private val callSignalingClient by lazy { com.telefam.calls.CallSignalingClient(httpClient) }
    private val callController by lazy {
        com.telefam.calls.CallController(
            signaling = callSignalingClient,
            httpClient = httpClient,
            currentUserId = { currentUserIdField },
            resolvePeer = { userId ->
                val user = runCatching { userDirectoryApi.lookup(userId) }.getOrNull()
                val name = user?.fullName ?: user?.username ?: "Telefam user"
                name to null // DirectoryUserDto carries no avatar; profile fetch would add it.
            }
        )
    }
    private val postsApi by lazy { PostsApi(httpClient) }
    private val settingsApi by lazy { SettingsApi(httpClient) }
    private val feedApi by lazy { com.telefam.data.api.FeedApi(httpClient) }
    private val connectApi by lazy { com.telefam.data.api.ConnectApi(httpClient) }
    private val notificationsApi by lazy { com.telefam.data.api.NotificationsApi(httpClient) }
    private val deviceContacts by lazy { com.telefam.connect.DeviceContacts(this) }
    private val contactsViewModel by lazy {
        com.telefam.connect.ContactsViewModel(
            api = connectApi,
            offlineRepository = offlineRepository,
            deviceContacts = deviceContacts,
            driverFactory = DatabaseDriverFactory(applicationContext),
            onQueuedForRetry = { OutboxSyncWorker.scheduleOneTime(applicationContext) }
        )
    }
    private fun newProfileViewModel() = com.telefam.connect.ProfileViewModel(
        api = connectApi,
        offlineRepository = offlineRepository,
        driverFactory = DatabaseDriverFactory(applicationContext),
        onQueuedForRetry = { OutboxSyncWorker.scheduleOneTime(applicationContext) }
    )
    private val feedViewModelFactory: () -> com.telefam.posts.FeedViewModel = {
        com.telefam.posts.FeedViewModel(
            feedApi = feedApi,
            offlineRepository = offlineRepository,
            driverFactory = DatabaseDriverFactory(applicationContext),
            onQueuedForRetry = { OutboxSyncWorker.scheduleOneTime(applicationContext) }
        )
    }
    private val createPostViewModel by lazy {
        CreatePostViewModel(postsApi, userDirectoryApi, platformContext = applicationContext)
    }
    private val creatorApi by lazy { com.telefam.data.api.CreatorApi(httpClient) }
    private val subscriptionApi by lazy { com.telefam.data.api.SubscriptionApi(httpClient) }
    private val walletApi by lazy { com.telefam.data.api.WalletApi(httpClient) }
    private val walletViewModel by lazy { com.telefam.wallet.WalletViewModel(walletApi) }
    private fun newSubscriptionViewModel() = com.telefam.subscriptions.SubscriptionViewModel(
        api = subscriptionApi,
        driverFactory = DatabaseDriverFactory(applicationContext)
    )
    /** Shared across the creator subscription subscreens so plans/currency stay warm. */
    private val subscriptionViewModel by lazy { newSubscriptionViewModel() }
    private val creatorViewModel by lazy {
        com.telefam.creator.CreatorViewModel(
            api = creatorApi,
            offlineRepository = offlineRepository,
            driverFactory = DatabaseDriverFactory(applicationContext),
            onQueuedForRetry = { OutboxSyncWorker.scheduleOneTime(applicationContext) }
        )
    }
    private val campaignApi by lazy { com.telefam.data.api.CampaignApi(httpClient) }
    private val commentsApi by lazy { com.telefam.data.api.CommentsApi(httpClient) }
    private val deviceLinkApi by lazy { com.telefam.data.api.DeviceLinkApi(httpClient) }

    /** Comment sheet overlay used by feeds, feed search and profile watch. */
    private fun commentsSheetFor(
        returnTo: Screen,
        onNavigate: (Screen) -> Unit,
        onOpenUrl: (String) -> Unit
    ): @Composable (com.telefam.posts.FeedPostDto, () -> Unit) -> Unit = { post, onClose ->
        val commentsViewModel = remember(post.postId) {
            com.telefam.posts.CommentsViewModel(api = commentsApi)
        }
        com.telefam.ui.components.comments.CommentsScreen(
            post = post,
            viewModel = commentsViewModel,
            commentsApi = commentsApi,
            giphyApi = giphyApi,
            directoryApi = userDirectoryApi,
            campaignApi = campaignApi,
            onClose = onClose,
            onOpenProfile = { userId -> onNavigate(Screen.Profile(userId, returnTo)) },
            onFollow = { userId -> lifecycleScope.launch { runCatching { feedApi.follow(userId) } } },
            onBuyStars = { onNavigate(Screen.BuyStars(returnTo)) },
            onHashtagClick = { tag -> onNavigate(Screen.FeedSearch("#$tag")) },
            onUrlClick = onOpenUrl
        )
    }
    private val campaignViewModel by lazy {
        com.telefam.campaigns.CampaignViewModel(
            api = campaignApi,
            feedApi = feedApi,
            driverFactory = DatabaseDriverFactory(applicationContext)
        )
    }
    private val signalIdentityStorage by lazy { SignalIdentityKeyStorage(applicationContext) }
    private val messageRepository by lazy {
        val engine = AndroidSignalEngine(LocalDatabase.getInstance(DatabaseDriverFactory(applicationContext)), signalIdentityStorage)
        MessageRepository(engine, e2eeApi, localDeviceId = 1)
    }

    // Plain fields (not Compose state) so ChatService's closures always read the latest value
    // without needing to be reconstructed on every recomposition.
    @Volatile private var currentUserIdField: String? = null
    @Volatile private var cachedPoliciesField: List<String> = listOf("ANYONE", "ANYONE", "ANYONE", "ANYONE")
    @Volatile private var blockedIdsField: Set<String> = emptySet()

    private val chatService by lazy {
        ChatService(
            messageRepository, chatLocalRepository, chatSettingsLocalRepository, userDirectoryApi,
            blobApi = mediaBlobApi,
            myUserId = { currentUserIdField },
            myPolicies = { cachedPoliciesField },
            isBlocked = { blockedIdsField.contains(it) }
        )
    }

    private val audioPlayer by lazy { AudioPlayer() }
    private fun newVoiceRecorder() = VoiceRecorder(applicationContext)

    /** Reads the "sub" claim straight out of the JWT payload - no verification needed client-side, the backend already validates every call independently. */
    private fun userIdFromAccessToken(token: String): String? = try {
        val payload = token.split(".")[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
        Regex(""""sub"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
    } catch (e: Exception) { null }

    private var micPermissionCallback: ((Boolean) -> Unit)? = null
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionCallback?.invoke(granted); micPermissionCallback = null
    }
    private fun requestMicPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            micPermissionCallback = onResult
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- Call permission & screen-share plumbing ---

    /** Runs [block] once RECORD_AUDIO (+ CAMERA for video calls) are granted. */
    private fun withCallPermissions(video: Boolean, block: () -> Unit) {
        requestMicPermission { micGranted ->
            if (!micGranted) return@requestMicPermission
            if (!video) { block(); return@requestMicPermission }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                block()
            } else {
                pendingCallAfterCamera = block
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCallAfterCamera?.invoke()
        pendingCallAfterCamera = null
    }
    @Volatile private var pendingCallAfterCamera: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Conversation to open once the Compose tree is up (set by notification body taps). */
    @Volatile private var pendingChatOpen: Pair<String, String>? = null

    /** Notification-centre deep link from a push tap: (targetType, targetId). */
    @Volatile private var pendingNotificationNav: Pair<String?, String?>? = null

    /** Full-screen intent (locked phone) or autoAccept action landed on this Activity. */
    private fun handleCallIntent(intent: android.content.Intent?) {
        // Activity-notification push tap: open the notification centre / origin.
        if (intent?.action == "com.telefam.app.notifications.OPEN") {
            pendingNotificationNav =
                intent.getStringExtra("targetType") to intent.getStringExtra("targetId")
            return
        }
        // Message-notification body tap: hand the target conversation to the Compose layer.
        if (intent?.action == "com.telefam.app.chat.OPEN") {
            val peerId = intent.getStringExtra(com.telefam.app.calls.ChatNotificationHelper.EXTRA_PEER_ID)
            val peerName = intent.getStringExtra(com.telefam.app.calls.ChatNotificationHelper.EXTRA_PEER_NAME) ?: "Telefam user"
            if (peerId != null) {
                com.telefam.app.calls.ChatNotificationHelper.cancel(this, peerId)
                com.telefam.app.calls.ChatActionBus.emit(com.telefam.app.calls.ChatActionEvent.Open(peerId, peerName))
                pendingChatOpen = peerId to peerName
            }
            return
        }
        if (intent?.action != "com.telefam.app.calls.INCOMING") return
        val callId = intent.getStringExtra(com.telefam.app.calls.IncomingCallNotification.EXTRA_CALL_ID) ?: return
        val callerId = intent.getStringExtra(com.telefam.app.calls.IncomingCallNotification.EXTRA_CALLER_ID) ?: return
        val callerName = intent.getStringExtra(com.telefam.app.calls.IncomingCallNotification.EXTRA_CALLER_NAME) ?: "Telefam user"
        val callType = intent.getStringExtra(com.telefam.app.calls.IncomingCallNotification.EXTRA_CALL_TYPE) ?: "audio"
        com.telefam.app.calls.IncomingCallNotification.cancel(this)
        callController.attachIncomingFromPush(callId, callerId, callerName, com.telefam.calls.CallType.fromWire(callType))
        if (intent.getBooleanExtra("autoAccept", false)) callController.acceptIncoming()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleCallIntent(intent)
    }

    /** MediaProjection consent result, routed into the active WebRTC engine. */
    private val screenShareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.telefam.calls.WebRtcEngine.activeEngine?.onScreenSharePermissionResult(result.resultCode, result.data)
    }

    private fun requestScreenShareConsent() {
        val projectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private var locationPermissionCallback: ((Boolean) -> Unit)? = null
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationPermissionCallback?.invoke(granted); locationPermissionCallback = null
    }
    private fun requestLocationPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            locationPermissionCallback = onResult
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** FLAG_SECURE blocks screenshots and screen recording at the OS level - the real enforcement mechanism, not just a UI hint. */
    private fun setSecureScreen(secure: Boolean) {
        if (secure) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        realtimeClient.setActive(true)
    }

    override fun onStop() {
        // A backgrounded app is not "online" for the people watching our status.
        realtimeClient.setActive(false)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.telefam.connect.DeviceContacts.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OfflineDependencies.repository = offlineRepository
        OutboxSyncWorker.scheduleOneTime(applicationContext) // flush anything queued from a previous session, silently
        AuthSession.init(TokenStorage(applicationContext)) // restore a session that survived an app restart, from the Keystore-backed store
        ThemeController.init(DatabaseDriverFactory(applicationContext)) // restores last-known theme/bubble colour even fully offline
        com.telefam.usage.UsageTracker.init(DatabaseDriverFactory(applicationContext)) // screen-time tracking for Settings → Time management
        AppFiles.baseDir = applicationContext.filesDir.absolutePath
        com.telefam.posts.FeedMediaCache.init(applicationContext) // enables offline feed playback + preload

        // --- Calls setup ---
        com.telefam.calls.AndroidCallContext.init(applicationContext)
        com.telefam.app.calls.IncomingCallNotification.ensureChannel(this)
        com.telefam.calls.CallServiceBridge.starter = { _, peerName, video ->
            com.telefam.app.calls.CallForegroundService.start(this, peerName, video)
        }
        com.telefam.calls.CallServiceBridge.stopper = {
            com.telefam.app.calls.CallForegroundService.stop(this)
        }
        com.telefam.calls.WebRtcEngine.screenSharePermissionRequester = { requestScreenShareConsent() }
        com.telefam.calls.WebRtcEngine.startScreenShareService = {
            com.telefam.app.calls.CallForegroundService.startScreenShare(
                this, callController.state.value.peerName.ifBlank { "Telefam call" }
            )
        }
        callController.start()
        // Accept/Decline pressed on the ringing notification (app may have been closed).
        lifecycleScope.launch {
            com.telefam.app.calls.CallIntentBus.events.collect { event ->
                when (event) {
                    is com.telefam.app.calls.CallIntentEvent.Accept -> {
                        callController.attachIncomingFromPush(
                            event.callId, event.callerId, event.callerName,
                            com.telefam.calls.CallType.fromWire(event.callType)
                        )
                        callController.acceptIncoming()
                    }
                    is com.telefam.app.calls.CallIntentEvent.Decline -> {
                        callController.attachIncomingFromPush(
                            event.callId, event.callerId, event.callerName,
                            com.telefam.calls.CallType.fromWire(event.callType)
                        )
                        callController.rejectIncoming()
                    }
                    is com.telefam.app.calls.CallIntentEvent.Open -> {
                        callController.attachIncomingFromPush(
                            event.callId, event.callerId, event.callerName,
                            com.telefam.calls.CallType.fromWire(event.callType)
                        )
                    }
                }
            }
        }
        // Register this device's push token so calls ring when the app is closed.
        if (AuthSession.hasActiveSession()) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    lifecycleScope.launch { runCatching { PushTokenUploader.upload(applicationContext, token) } }
                }
        }
        // Reply / Mark-as-read pressed on a message notification (app may have been closed).
        lifecycleScope.launch {
            com.telefam.app.calls.ChatActionBus.events.collect { event ->
                when (event) {
                    is com.telefam.app.calls.ChatActionEvent.Reply -> {
                        // Optimistic encrypted send through the shared ChatService; the
                        // message lands in the local DB first and syncs when online.
                        runCatching {
                            chatLocalRepository.upsertPeer(event.peerId, event.peerName, null)
                            chatService.send(event.peerId, "TEXT", text = event.text)
                            chatService.retryPending()
                        }
                    }
                    is com.telefam.app.calls.ChatActionEvent.MarkRead ->
                        runCatching { chatService.markChatRead(event.peerId) }
                    is com.telefam.app.calls.ChatActionEvent.Open -> { /* handled via launch intent */ }
                }
            }
        }
        // Android 13+: the ringing notification needs a runtime grant.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleCallIntent(intent)
        OfflineDependencies.chatRetry = { chatService.retryPending() }
        val googleAuthLauncher = GoogleAuthLauncher(this)

        setContent {
            TelefamTheme {
                Surface {
                    var screen by remember { mutableStateOf<Screen>(if (AuthSession.hasActiveSession()) Screen.Home else Screen.SignUp) }
                    // Deep-link from a message notification: jump straight into the chat.
                    LaunchedEffect(Unit) {
                        pendingChatOpen?.let { (peerId, name) ->
                            pendingChatOpen = null
                            if (AuthSession.hasActiveSession()) {
                                chatLocalRepository.upsertPeer(peerId, name, null)
                                screen = Screen.Chat(peerId, name)
                            }
                        }
                    }
                    // Push tap on an activity notification: redirect to its origin.
                    LaunchedEffect(Unit) {
                        pendingNotificationNav?.let { (targetType, targetId) ->
                            pendingNotificationNav = null
                            if (AuthSession.hasActiveSession()) {
                                screen = when (targetType) {
                                    "PROFILE" -> targetId?.let { Screen.Profile(it, Screen.Notifications) } ?: Screen.Notifications
                                    "WALLET" -> Screen.Wallet
                                    "SUBSCRIPTIONS" -> Screen.MySubscriptions(Screen.Notifications)
                                    "POST", "COMMENT" -> Screen.Feeds
                                    else -> Screen.Notifications
                                }
                            }
                        }
                    }
                    /** Pinned system conversations (Telefam Official + Notifications) for the inbox. */
                    var systemInbox by remember { mutableStateOf<com.telefam.data.api.SystemInboxDto?>(null) }
                    fun refreshSystemInbox() {
                        lifecycleScope.launch {
                            runCatching { notificationsApi.systemInbox() }.onSuccess { systemInbox = it }
                        }
                    }
                    // Notification-centre + official-inbox view models (shared Compose state).
                    val notificationsVm = remember { com.telefam.notifications.NotificationsViewModel(notificationsApi) }
                    val officialInboxVm = remember { com.telefam.notifications.OfficialInboxViewModel(notificationsApi) }

                    var isLoading by remember { mutableStateOf(false) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var resendSeconds by remember { mutableStateOf(60L) }
                    var privacy by remember { mutableStateOf(PrivacySettingsDto()) }
                    var requests by remember { mutableStateOf(listOf<MessageRequestItem>()) }
                    var archived by remember { mutableStateOf(listOf<ArchivedChatItem>()) }
                    var blocked by remember { mutableStateOf(listOf<BlockedUserItem>()) }
                    var conversations by remember { mutableStateOf(chatLocalRepository.conversations()) }
                    var chatMessages by remember { mutableStateOf(listOf<com.telefam.chat.CachedMessageItem>()) }
                    var chatHasMoreOlder by remember { mutableStateOf(false) }
                    var messagesVersion by remember { mutableIntStateOf(0) }
                    /** Plan being edited in the subscription plan editor (null = create mode). */
                    var editingPlan by remember { mutableStateOf<com.telefam.data.api.SubscriptionPlanDto?>(null) }
                    /** Sponsored campaign link opened in the in-app browser (Visit / Buy / Download / ...). */
                    var sponsoredLinkUrl by remember { mutableStateOf<String?>(null) }
                    /** Tracks whether a session exists right now so effects (realtime socket) can react to QR linking. */
                    var sessionActive by remember { mutableStateOf(AuthSession.hasActiveSession()) }
                    /** Cached app settings (content preferences, accessibility, time management). */
                    var appSettings by remember { mutableStateOf<com.telefam.data.api.AppSettingsDto?>(null) }

                    /** Full logout: revoke this device's token family server-side, then wipe the local session. */
                    fun performLogout() {
                        lifecycleScope.launch {
                            runCatching {
                                httpClient.post("${ApiConfig.baseUrl}/api/auth/logout") {
                                    AuthSession.refreshToken?.let { headers.append("X-Refresh-Token", it) }
                                }
                            } // offline: local session still clears; the token simply dies by expiry
                            AuthSession.clear()
                            currentUserIdField = null
                            sessionActive = false
                            screen = Screen.SignUp
                        }
                    }

                    // Keep Android system back/navigation gestures inside the app's screen
                    // state machine. Without this, Contacts/Feeds/NewChat/etc. can close
                    // the Activity instead of returning to the previous app screen.
                    BackHandler(
                        enabled = screen !is Screen.SignUp &&
                            screen !is Screen.Login &&
                            screen !is Screen.Otp &&
                            screen !is Screen.ProfileSetup
                    ) {
                        screen = when (val current = screen) {
                            Screen.Home -> Screen.Home
                            is Screen.Chat -> Screen.Home
                            is Screen.ChatOptions -> Screen.Chat(current.peerId, current.peerName)
                            is Screen.MuteOptions,
                            is Screen.DisappearingMessages,
                            is Screen.Report,
                            is Screen.MediaLinksDocs,
                            is Screen.PrivacyOption,
                            Screen.ChatsThemeScreen,
                            Screen.BubbleColourScreen -> Screen.Privacy
                            Screen.Privacy,
                            Screen.MessageRequests,
                            Screen.Archived,
                            Screen.Blocked,
                            Screen.Contacts,
                            Screen.Feeds,
                            Screen.CreatePost -> Screen.Home
                            Screen.NewChat -> Screen.Home
                            Screen.Notifications,
                            Screen.OfficialChat -> Screen.Home
                            is Screen.PollComposer,
                            is Screen.LocationPicker,
                            is Screen.GifStickerPicker,
                            is Screen.ForwardPicker -> Screen.Chat(
                                when (current) {
                                    is Screen.PollComposer -> current.peerId
                                    is Screen.LocationPicker -> current.peerId
                                    is Screen.GifStickerPicker -> current.peerId
                                    is Screen.ForwardPicker -> current.peerId
                                    else -> error("unreachable")
                                },
                                when (current) {
                                    is Screen.PollComposer -> current.peerName
                                    is Screen.LocationPicker -> current.peerName
                                    is Screen.GifStickerPicker -> current.peerName
                                    is Screen.ForwardPicker -> current.peerName
                                    else -> error("unreachable")
                                }
                            )
                            Screen.TrimPostVideo -> Screen.CreatePost
                            is Screen.FeedSearch -> Screen.Feeds
                            is Screen.EditPost -> Screen.Feeds
                            is Screen.Profile -> current.returnTo ?: Screen.Home
                            is Screen.FollowList -> Screen.Profile(current.userId, Screen.Home)
                            is Screen.ShareProfile -> Screen.Profile(current.userId, Screen.Home)
                            is Screen.EditProfile -> Screen.Profile(current.userId, Screen.Home)
                            is Screen.CreatorMenu -> current.returnTo ?: Screen.Home
                            Screen.Verification -> Screen.CreatorMenu(com.telefam.data.AuthSession.currentUserId ?: "", Screen.Home)
                            is Screen.Dashboard,
                            is Screen.Monetization,
                            is Screen.Subscriptions,
                            is Screen.Stars -> Screen.CreatorMenu(com.telefam.data.AuthSession.currentUserId ?: "", Screen.Home)
                            is Screen.CreatorAnalytics -> Screen.Dashboard(com.telefam.data.AuthSession.currentUserId ?: "")
                            Screen.ContentPerformance -> Screen.Dashboard(com.telefam.data.AuthSession.currentUserId ?: "")
                            Screen.MonetizationPolicies -> Screen.Monetization(com.telefam.data.AuthSession.currentUserId ?: "")
                            Screen.StarTransactions,
                            Screen.StarSupporters -> Screen.Stars(com.telefam.data.AuthSession.currentUserId ?: "")
                            is Screen.StarsInsights -> Screen.Stars(com.telefam.data.AuthSession.currentUserId ?: "")
                            is Screen.SubscriptionInsights -> Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                            Screen.ManagePlans,
                            Screen.SubscribersList,
                            Screen.SubscriptionPlanEdit -> Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                            is Screen.SubscribeToCreator -> current.returnTo ?: Screen.Profile(current.creatorId, Screen.Home)
                            is Screen.MySubscriptions -> current.returnTo
                            is Screen.Terms -> current.returnTo
                            is Screen.LegalDoc -> current.returnTo
                            Screen.Wallet -> Screen.CreatorMenu(com.telefam.data.AuthSession.currentUserId ?: "", Screen.Home)
                            Screen.WalletPending,
                            is Screen.WalletSourceEarnings,
                            Screen.WalletTransactions,
                            Screen.WalletWithdraw,
                            Screen.WalletPayouts,
                            Screen.WalletAddMethod -> Screen.Wallet
                            is Screen.WalletTransactionDetails -> Screen.WalletTransactions
                            is Screen.WalletPayoutDetails -> Screen.WalletPayouts
                            is Screen.WalletMethodDetails -> Screen.Wallet
                            is Screen.CreateCampaign -> Screen.CreatorMenu(current.userId, Screen.Home)
                            is Screen.MyCampaigns -> Screen.CreatorMenu(current.userId, Screen.Home)
                            is Screen.SeriesVideos -> Screen.CreateCampaign(current.userId)
                            is Screen.BuyStars -> current.returnTo
                            is Screen.StarCheckout -> Screen.BuyStars(current.returnTo)
                            Screen.LinkedDevices -> if (AuthSession.hasActiveSession()) Screen.Home else Screen.Login
                            Screen.Settings -> Screen.Home
                            Screen.SettingsAccount,
                            Screen.SettingsSecurity,
                            Screen.ContentPreferences,
                            Screen.TimeManagement,
                            Screen.AccessibilitySettings,
                            Screen.ReportProblem,
                            Screen.SafetyCenter,
                            Screen.HelpSupport,
                            Screen.About -> Screen.Settings
                            Screen.ChangeEmail,
                            Screen.ChangePhone -> Screen.SettingsAccount
                            Screen.ChangePassword -> Screen.SettingsSecurity
                            is Screen.SignUp,
                            is Screen.Login,
                            is Screen.Otp,
                            Screen.ProfileSetup -> current
                        }
                    }

                    // Realtime presence/typing: peerId -> online flag / typing-expiry timestamp
                    var presenceMap by remember { mutableStateOf(mapOf<String, Pair<Boolean, Long?>>()) }
                    var typingUntilMap by remember { mutableStateOf(mapOf<String, Long>()) }

                    var verifiedSyncedPeers by remember { mutableStateOf(setOf<String>()) }
                    fun refreshConversations() {
                        conversations = chatLocalRepository.conversations()
                        // Refresh each visible peer's badge snapshot from the server (authoritative source).
                        val pending = conversations.map { it.peerId }.filter { it !in verifiedSyncedPeers }
                        if (pending.isNotEmpty()) {
                            lifecycleScope.launch {
                                pending.forEach { pid ->
                                    runCatching { connectApi.profile(pid) }.getOrNull()?.let {
                                        chatLocalRepository.setPeerVerified(pid, it.isVerified)
                                    }
                                }
                                verifiedSyncedPeers = verifiedSyncedPeers + pending
                                conversations = chatLocalRepository.conversations()
                            }
                        }
                    }

                    /** Follow-back button on a NEW_FOLLOWER notification. */
                    fun followBack(userId: String) {
                        lifecycleScope.launch {
                            runCatching { connectApi.follow(userId, UUID.randomUUID().toString()) }
                            refreshConversations()
                        }
                    }

                    // Poll the system inbox so unread badges stay live while the app is open.
                    LaunchedEffect(sessionActive, screen) {
                        if (sessionActive && (screen is Screen.Home || screen is Screen.Notifications || screen is Screen.OfficialChat)) {
                            refreshSystemInbox()
                            while (true) {
                                kotlinx.coroutines.delay(20_000)
                                refreshSystemInbox()
                                if (screen is Screen.Home) refreshConversations()
                            }
                        }
                    }

                    fun loadChat(peerId: String, pageSize: Long = 30L) {
                        val sameChat = chatMessages.firstOrNull()?.peerId == peerId
                        val size = if (sameChat) maxOf(pageSize, chatMessages.size.toLong()) else pageSize
                        chatMessages = chatLocalRepository.loadPage(peerId, Long.MAX_VALUE, size)
                        chatHasMoreOlder = chatMessages.size.toLong() >= size
                    }

                    /** Central handler for "mailbox changed": refresh UI, and if the user is already
                     * inside the affected chat, immediately mark it READ so the sender's ticks flip
                     * without requiring the chat to be reopened (the read-receipt gap fix). */
                    fun onInboxChanged(changedPeers: Set<String>) {
                        if (changedPeers.isEmpty()) return
                        refreshConversations()
                        messagesVersion++
                        val openChat = (screen as? Screen.Chat)
                        if (openChat != null && openChat.peerId in changedPeers) {
                            lifecycleScope.launch {
                                chatService.markChatRead(openChat.peerId)
                                loadChat(openChat.peerId)
                            }
                        }
                    }

                    fun refreshPrivacy() {
                        lifecycleScope.launch {
                            try {
                                privacy = privacyApi.getPrivacySettings().body()
                                ThemeController.update(ChatsTheme.valueOf(privacy.chatsTheme))
                                ThemeController.update(BubbleColour.valueOf(privacy.messageBubbleColour))
                                cachedPoliciesField = listOf(privacy.whoCanScreenshotChats, privacy.whoCanShareChats, privacy.whoCanCopyMessages, privacy.whoCanDownloadMedia)
                            } catch (e: Exception) { /* keep last known values, no banner */ }
                        }
                    }

                    fun refreshBlockedIds() {
                        lifecycleScope.launch {
                            try {
                                val list: List<com.telefam.data.api.BlockedUserDto> = privacyApi.listBlocked().body()
                                blockedIdsField = list.map { it.userId }.toSet()
                            } catch (e: Exception) { }
                        }
                    }

                    // --- Background: poll the E2EE mailbox and retry sends; foreground: also sweep expired disappearing messages. ---
                    // Polling is now the FALLBACK layer: the realtime socket delivers mailbox hints instantly,
                    // and this loop both covers socket downtime and flushes queued outgoing messages.
                    LaunchedEffect(Unit) {
                        while (true) {
                            if (AuthSession.hasActiveSession()) {
                                val changedPeers = runCatching { chatService.pollInbox() }.getOrDefault(emptySet())
                                chatService.sweepExpired()
                                onInboxChanged(changedPeers)
                                // Expire stale typing indicators even without new events
                                val nowMs = System.currentTimeMillis()
                                val stale = typingUntilMap.filterValues { it < nowMs }.keys
                                if (stale.isNotEmpty()) typingUntilMap = typingUntilMap - stale
                            }
                            kotlinx.coroutines.delay(4000)
                        }
                    }
                    // Screen-time accumulation for Settings → Time management (local, offline by nature).
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(60_000)
                            if (AuthSession.hasActiveSession()) com.telefam.usage.UsageTracker.tickMinute()
                        }
                    }
                    // --- Realtime socket: presence, typing, instant mailbox hints ---
                    // Keyed on sessionActive so a device that links mid-session (QR pairing
                    // from the login screen) connects without needing an app restart.
                    LaunchedEffect(sessionActive) {
                        if (!AuthSession.hasActiveSession()) return@LaunchedEffect
                        realtimeClient.connect()
                        realtimeClient.events.collect { event ->
                            when (event) {
                                is RealtimeEvent.Presence ->
                                    presenceMap =
                                        if (event.hidden) presenceMap - event.userId // privacy/block: show nothing, not stale data
                                        else presenceMap + (event.userId to (event.online to event.lastSeenEpochMillis))
                                is RealtimeEvent.Typing ->
                                    typingUntilMap = typingUntilMap + (event.fromUserId to System.currentTimeMillis() + 5000)
                                RealtimeEvent.MailboxHint -> {
                                    val changed = runCatching { chatService.pollInbox() }.getOrDefault(emptySet())
                                    onInboxChanged(changed)
                                }
                            }
                        }
                    }
                    val watchedPeerIds = conversations.map { it.peerId }.toSet() + listOfNotNull((screen as? Screen.Chat)?.peerId)
                    LaunchedEffect(watchedPeerIds) { realtimeClient.watch(watchedPeerIds) }
                    LaunchedEffect((screen as? Screen.Chat)?.peerId, messagesVersion) {
                        (screen as? Screen.Chat)?.let { loadChat(it.peerId) }
                    }

                    /** Every privacy/social mutation: save locally + attempt now, else queue silently (see OfflineActionRepository). */
                    fun saveField(field: String, value: String) {
                        lifecycleScope.launch {
                            val body = """{"field":"$field","value":"$value"}"""
                            offlineRepository.performOrQueue("${ApiConfig.baseUrl}/api/privacy", HttpMethod.Put, body)
                            OutboxSyncWorker.scheduleOneTime(applicationContext)
                            screen = Screen.Privacy
                        }
                    }

                    Box {
                        when (val s = screen) {
                            is Screen.SignUp -> SignUpScreen(
                                isLoading = isLoading,
                                errorMessage = error,
                                onSignUpClick = { email, password ->
                                    error = null; isLoading = true
                                    lifecycleScope.launch {
                                        val response = authApi.signUp(email, password)
                                        isLoading = false
                                        if (response.status.value in 200..299) screen = Screen.Otp(email, "SIGNUP_VERIFY")
                                        else error = "Unable to create account"
                                    }
                                },
                                onGoogleClick = {
                                    lifecycleScope.launch {
                                        try {
                                            val idToken = googleAuthLauncher.signIn()
                                            val response = authApi.googleSignIn(idToken)
                                            if (response.status.value in 200..299) {
                                                val tokens: AuthTokens = response.body()
                                                AuthSession.accessToken = tokens.accessToken
                                                AuthSession.refreshToken = tokens.refreshToken
                                                currentUserIdField = userIdFromAccessToken(tokens.accessToken)
                                                lifecycleScope.launch { runCatching { messageRepository.registerThisDevice() } }
                                                refreshBlockedIds()
                                                screen = Screen.ProfileSetup
                                            }
                                        } catch (e: Exception) { error = "Google sign-in failed" }
                                    }
                                },
                                onAppleClick = { },
                                onLoginClick = { screen = Screen.Login },
                                onForgotPasswordClick = { email ->
                                    if (email.isNotBlank()) {
                                        error = null
                                        lifecycleScope.launch {
                                            runCatching { authApi.sendOtp(email, "PASSWORD_RESET") }
                                            screen = Screen.Otp(email, "PASSWORD_RESET")
                                        }
                                    } else error = "Enter your email address first"
                                },
                                onLinkDeviceClick = { screen = Screen.LinkedDevices }
                            )

                            is Screen.Login -> LoginScreen(
                                isLoading = isLoading,
                                errorMessage = error,
                                onLoginClick = { email, password ->
                                    error = null; isLoading = true
                                    lifecycleScope.launch {
                                        val response = authApi.login(email, password)
                                        isLoading = false
                                        if (response.status.value in 200..299) {
                                            val tokens: AuthTokens = response.body()
                                            AuthSession.accessToken = tokens.accessToken
                                            AuthSession.refreshToken = tokens.refreshToken
                                            currentUserIdField = userIdFromAccessToken(tokens.accessToken)
                                            lifecycleScope.launch { runCatching { messageRepository.registerThisDevice() } }
                                            refreshBlockedIds()
                                            screen = Screen.Home
                                        } else error = "Invalid credentials"
                                    }
                                },
                                onGoogleClick = {
                                    lifecycleScope.launch {
                                        try {
                                            val idToken = googleAuthLauncher.signIn()
                                            val response = authApi.googleSignIn(idToken)
                                            val tokens: AuthTokens = response.body()
                                            AuthSession.accessToken = tokens.accessToken
                                            AuthSession.refreshToken = tokens.refreshToken
                                            currentUserIdField = userIdFromAccessToken(tokens.accessToken)
                                            lifecycleScope.launch { runCatching { messageRepository.registerThisDevice() } }
                                            refreshBlockedIds()
                                            screen = Screen.Home
                                        } catch (e: Exception) { error = "Google sign-in failed" }
                                    }
                                },
                                onAppleClick = {},
                                onSignUpClick = { screen = Screen.SignUp },
                                onForgotPasswordClick = { email ->
                                    if (email.isNotBlank()) {
                                        error = null
                                        lifecycleScope.launch {
                                            runCatching { authApi.sendOtp(email, "PASSWORD_RESET") }
                                            screen = Screen.Otp(email, "PASSWORD_RESET")
                                        }
                                    } else error = "Enter your email address first"
                                },
                                onLinkDeviceClick = { screen = Screen.LinkedDevices }
                            )

                            is Screen.Otp -> OtpScreen(
                                email = s.email,
                                secondsUntilResend = resendSeconds,
                                isLoading = isLoading,
                                errorMessage = error,
                                onVerifyClick = { code ->
                                    error = null; isLoading = true
                                    lifecycleScope.launch {
                                        val response = authApi.verifyOtp(s.email, s.purpose, code)
                                        isLoading = false
                                        if (response.status.value in 200..299) {
                                            val tokens: AuthTokens = response.body()
                                            AuthSession.accessToken = tokens.accessToken
                                            AuthSession.refreshToken = tokens.refreshToken
                                            currentUserIdField = userIdFromAccessToken(tokens.accessToken)
                                            lifecycleScope.launch { runCatching { messageRepository.registerThisDevice() } }
                                            refreshBlockedIds()
                                            // Password-reset / 2FA codes sign into an existing account; signup codes need profile setup.
                                            screen = if (s.purpose == "SIGNUP_VERIFY") Screen.ProfileSetup else Screen.Home
                                        } else error = "Invalid code"
                                    }
                                },
                                onResendClick = { lifecycleScope.launch { authApi.sendOtp(s.email, s.purpose) } },
                                onBackClick = { screen = Screen.SignUp }
                            )

                            is Screen.ProfileSetup -> {
                                var profileState by remember { mutableStateOf(ProfileSetupState()) }
                                ProfileSetupScreen(
                                    state = profileState,
                                    onStateChange = { profileState = it },
                                    profileImage = null,
                                    onPickPhotoClick = { /* wire rememberImagePickerCropCompress here */ },
                                    onBackClick = { screen = Screen.SignUp },
                                    onOpenDatePicker = { /* wire native DatePickerDialog, set profileState.dateOfBirth */ },
                                    onContinueClick = {
                                        screen = Screen.Home
                                        refreshPrivacy()
                                    },
                                    isLoading = isLoading
                                )
                            }

                            is Screen.Home -> HomeScreen(
                                profileAvatarUrl = null,
                                conversations = conversations,
                                systemInbox = systemInbox,
                                onOpenOfficialChat = { screen = Screen.OfficialChat },
                                onOpenNotifications = { screen = Screen.Notifications },
                                onOpenChat = { peerId, name -> loadChat(peerId); screen = Screen.Chat(peerId, name) },
                                onNewChat = { screen = Screen.NewChat },
                                onOpenPost = { screen = Screen.CreatePost },
                                onOpenFeeds = { screen = Screen.Feeds },
                                onOpenPrivacy = { refreshPrivacy(); screen = Screen.Privacy },
                                onOpenMessageRequests = {
                                    lifecycleScope.launch {
                                        try {
                                            val list: List<com.telefam.data.api.MessageRequestDto> = privacyApi.listMessageRequests().body()
                                            requests = list.map { MessageRequestItem(it.id, it.fromName ?: "Unknown", it.previewText) }
                                        } catch (e: Exception) { }
                                    }
                                    screen = Screen.MessageRequests
                                },
                                onOpenArchived = {
                                    lifecycleScope.launch {
                                        try {
                                            val list: List<com.telefam.data.api.ArchivedChatDto> = privacyApi.listArchived().body()
                                            archived = list.map { ArchivedChatItem(it.chatId, "Chat", null) }
                                        } catch (e: Exception) { }
                                    }
                                    screen = Screen.Archived
                                },
                                onOpenBlocked = {
                                    lifecycleScope.launch {
                                        try {
                                            val list: List<com.telefam.data.api.BlockedUserDto> = privacyApi.listBlocked().body()
                                            blocked = list.map { BlockedUserItem(it.userId, it.name ?: "Unknown") }
                                        } catch (e: Exception) { }
                                    }
                                    screen = Screen.Blocked
                                },
                                onOpenContacts = { screen = Screen.Contacts },
                                onOpenLinkedDevices = { screen = Screen.LinkedDevices },
                                onOpenSettings = { screen = Screen.Settings },
                                onOpenProfile = { peerId -> screen = Screen.Profile(peerId, Screen.Home) },
                                // Conversation-list selection-mode actions (offline-safe: archive goes
                                // through the outbox, delete/read are local DB operations).
                                onArchiveChats = { peerIds ->
                                    lifecycleScope.launch {
                                        peerIds.forEach { pid ->
                                            runCatching { UUID.fromString(pid) }?.let { uuid ->
                                                offlineRepository.performOrQueue(
                                                    "${ApiConfig.baseUrl}/api/social/archived",
                                                    io.ktor.http.HttpMethod.Post,
                                                    """{"chatId":"$uuid"}"""
                                                )
                                            }
                                        }
                                        OutboxSyncWorker.scheduleOneTime(applicationContext)
                                    }
                                },
                                onDeleteChats = { peerIds ->
                                    peerIds.forEach { chatLocalRepository.deleteConversation(it) }
                                    refreshConversations()
                                },
                                onMarkChatsRead = { peerIds ->
                                    lifecycleScope.launch {
                                        peerIds.forEach { runCatching { chatService.markChatRead(it) } }
                                        refreshConversations()
                                    }
                                }
                            )

                            is Screen.Contacts -> ContactsScreen(
                                viewModel = contactsViewModel,
                                onOpenProfile = { userId -> screen = Screen.Profile(userId, Screen.Contacts) }
                            )

                            Screen.Notifications -> com.telefam.ui.screens.NotificationsScreen(
                                viewModel = notificationsVm,
                                onBack = { screen = Screen.Home },
                                onOpenPost = { screen = Screen.Feeds },
                                onOpenProfile = { userId -> screen = Screen.Profile(userId, Screen.Notifications) },
                                onOpenWallet = { screen = Screen.Wallet },
                                onOpenSubscriptions = { screen = Screen.MySubscriptions(Screen.Notifications) },
                                onFollowBack = { userId -> followBack(userId) }
                            )

                            Screen.OfficialChat -> com.telefam.ui.screens.OfficialChatScreen(
                                viewModel = officialInboxVm,
                                officialName = systemInbox?.officialName ?: "Telefam Official",
                                onBack = { screen = Screen.Home },
                                onOpenProfile = {
                                    systemInbox?.officialUserId?.let { screen = Screen.Profile(it, Screen.Home) }
                                }
                            )

                            is Screen.Profile -> {
                                val profileVm = remember(s.userId) { newProfileViewModel() }
                                ProfileScreen(
                                    userId = s.userId,
                                    viewModel = profileVm,
                                    feedViewModelFactory = feedViewModelFactory,
                                    shareTargets = conversations.map {
                                        com.telefam.ui.components.feed.ShareTarget(it.peerId, it.displayName)
                                    },
                                    onSendToChat = { post, target ->
                                        lifecycleScope.launch {
                                            chatService.send(target.peerId, "TEXT", text = "${post.displayHandle}: ${post.shareUrl}")
                                        }
                                    },
                                    onEditPost = { post -> screen = Screen.EditPost(post.postId) },
                                    onOpenSearch = { q -> screen = Screen.FeedSearch(q) },
                                    onOpenList = { uid, kind -> screen = Screen.FollowList(uid, kind) },
                                    onMessage = { uid, name ->
                                        chatLocalRepository.upsertPeer(uid, name, null)
                                        refreshConversations()
                                        loadChat(uid)
                                        screen = Screen.Chat(uid, name)
                                    },
                                    onBack = { screen = s.returnTo ?: Screen.Home },
                                    onEditProfile = { screen = Screen.EditProfile(s.userId) },
                                    onOpenSettings = { refreshPrivacy(); screen = Screen.Privacy },
                                    onShareProfile = { screen = Screen.ShareProfile(s.userId) },
                                    onOpenCreatorMenu = { screen = Screen.CreatorMenu(s.userId, screen) },
                                    onSubscribe = { screen = Screen.SubscribeToCreator(s.userId, screen) },
                                    commentsSheet = commentsSheetFor(
                                        Screen.Profile(s.userId, s.returnTo), { screen = it }, { url -> sponsoredLinkUrl = url })
                                )
                            }

                            is Screen.ShareProfile -> {
                                val shareVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { shareVm.load(s.userId) }
                                val shareState by shareVm.state.collectAsState()
                                ShareProfileScreen(
                                    profile = shareState.profile,
                                    onBack = { screen = Screen.Profile(s.userId, Screen.Home) },
                                    onSaveQr = { /* QR export to gallery lands with the media-saver feature */ }
                                )
                            }

                            is Screen.CreatorMenu -> {
                                val menuVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { menuVm.load(s.userId) }
                                val menuState by menuVm.state.collectAsState()
                                com.telefam.ui.screens.CreatorMenuScreen(
                                    profile = menuState.profile,
                                    onBack = { screen = s.returnTo ?: Screen.Home },
                                    onOpenProfile = { screen = Screen.Profile(s.userId, Screen.CreatorMenu(s.userId, s.returnTo)) },
                                    onGetVerified = { screen = Screen.Verification },
                                    onOpenItem = { item ->
                                        screen = when (item) {
                                            com.telefam.ui.screens.CreatorMenuItem.DASHBOARD -> Screen.Dashboard(s.userId)
                                            com.telefam.ui.screens.CreatorMenuItem.STARS -> Screen.Stars(s.userId)
                                            com.telefam.ui.screens.CreatorMenuItem.SUBSCRIPTION -> Screen.Subscriptions(s.userId)
                                            com.telefam.ui.screens.CreatorMenuItem.MONETIZATION -> Screen.Monetization(s.userId)
                                            com.telefam.ui.screens.CreatorMenuItem.WALLET -> Screen.Wallet
                                            com.telefam.ui.screens.CreatorMenuItem.CREATE_CAMPAIGN -> Screen.CreateCampaign(s.userId)
                                            com.telefam.ui.screens.CreatorMenuItem.MY_CAMPAIGNS -> Screen.MyCampaigns(s.userId)
                                            else -> screen
                                        }
                                    }
                                )
                            }

                            Screen.Verification -> {
                                val verifyVm = remember { com.telefam.verification.VerificationViewModel(verificationApi) }
                                val myId = com.telefam.data.AuthSession.currentUserId
                                val verifyProfileVm = remember(myId) { if (myId != null) newProfileViewModel() else null }
                                LaunchedEffect(myId) { if (myId != null) verifyProfileVm?.load(myId) }
                                val verifyProfileState = verifyProfileVm?.state?.collectAsState()
                                com.telefam.ui.screens.VerificationHostScreen(
                                    viewModel = verifyVm,
                                    profile = verifyProfileState?.value?.profile,
                                    onBack = { screen = Screen.CreatorMenu(myId ?: "", Screen.Home) }
                                )
                            }

                            // ---- Creator program screens ----
                            is Screen.Dashboard -> {
                                val dashVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { dashVm.load(s.userId) }
                                val dashState by dashVm.state.collectAsState()
                                DashboardScreen(
                                    profile = dashState.profile,
                                    viewModel = creatorViewModel,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onCreatePost = { screen = Screen.CreatePost },
                                    onViewAnalytics = { days -> screen = Screen.CreatorAnalytics(days) },
                                    onViewAllContent = { screen = Screen.ContentPerformance }
                                )
                            }

                            is Screen.CreatorAnalytics -> {
                                CreatorAnalyticsScreen(
                                    viewModel = creatorViewModel,
                                    initialPeriodDays = s.periodDays,
                                    onBack = {
                                        screen = Screen.Dashboard(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    }
                                )
                            }

                            Screen.ContentPerformance -> {
                                ContentPerformanceScreen(
                                    viewModel = creatorViewModel,
                                    onBack = {
                                        screen = Screen.Dashboard(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    },
                                    onCreatePost = { screen = Screen.CreatePost }
                                )
                            }

                            is Screen.Monetization -> {
                                val monVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { monVm.load(s.userId) }
                                val monState by monVm.state.collectAsState()
                                MonetizationScreen(
                                    profile = monState.profile,
                                    viewModel = creatorViewModel,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onGoToDashboard = { screen = Screen.Dashboard(s.userId) },
                                    onViewTerms = { screen = Screen.Terms(screen) },
                                    onLearnMore = { screen = Screen.MonetizationPolicies }
                                )
                            }

                            Screen.MonetizationPolicies -> {
                                MonetizationPoliciesScreen(
                                    onBack = {
                                        screen = Screen.Monetization(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    }
                                )
                            }

                            is Screen.Stars -> {
                                val starsVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { starsVm.load(s.userId) }
                                val starsState by starsVm.state.collectAsState()
                                StarsScreen(
                                    profile = starsState.profile,
                                    viewModel = creatorViewModel,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onViewInsights = { days -> screen = Screen.StarsInsights(days) },
                                    onSeeAllTransactions = { screen = Screen.StarTransactions },
                                    onOpenSupporters = { screen = Screen.StarSupporters }
                                )
                            }

                            Screen.StarTransactions -> {
                                StarTransactionsScreen(
                                    viewModel = creatorViewModel,
                                    onBack = {
                                        screen = Screen.Stars(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    }
                                )
                            }

                            Screen.StarSupporters -> {
                                StarSupportersScreen(
                                    viewModel = creatorViewModel,
                                    onBack = {
                                        screen = Screen.Stars(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    }
                                )
                            }

                            is Screen.StarsInsights -> {
                                StarsInsightsScreen(
                                    viewModel = creatorViewModel,
                                    initialPeriodDays = s.periodDays,
                                    onBack = {
                                        screen = Screen.Stars(
                                            com.telefam.data.AuthSession.currentUserId ?: ""
                                        )
                                    }
                                )
                            }

                            // ---- Paid subscriptions ----
                            is Screen.Subscriptions -> {
                                val subVm = subscriptionViewModel
                                val subProfileVm = remember(s.userId) { newProfileViewModel() }
                                LaunchedEffect(s.userId) { subProfileVm.load(s.userId) }
                                val subProfileState by subProfileVm.state.collectAsState()
                                com.telefam.ui.screens.subscription.CreatorSubscriptionsScreen(
                                    profile = subProfileState.profile,
                                    viewModel = subVm,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onOpenInsights = { days -> screen = Screen.SubscriptionInsights(days) },
                                    onCreatePlan = { editingPlan = null; screen = Screen.SubscriptionPlanEdit },
                                    onManagePlans = { screen = Screen.ManagePlans },
                                    onEditPlan = { plan -> editingPlan = plan; screen = Screen.SubscriptionPlanEdit },
                                    onSeeAllSubscribers = { screen = Screen.SubscribersList },
                                    onOpenSubscriberProfile = { uid -> screen = Screen.Profile(uid, screen) }
                                )
                            }

                            is Screen.SubscriptionInsights -> {
                                val subVm = remember { newSubscriptionViewModel() }
                                com.telefam.ui.screens.subscription.SubscriptionInsightsScreen(
                                    viewModel = subVm,
                                    initialPeriodDays = s.periodDays,
                                    onBack = {
                                        screen = Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                                    }
                                )
                            }

                            Screen.ManagePlans -> {
                                val subVm = subscriptionViewModel
                                com.telefam.ui.screens.subscription.ManagePlansScreen(
                                    viewModel = subVm,
                                    onBack = {
                                        screen = Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                                    },
                                    onCreatePlan = { editingPlan = null; screen = Screen.SubscriptionPlanEdit },
                                    onEditPlan = { plan -> editingPlan = plan; screen = Screen.SubscriptionPlanEdit }
                                )
                            }

                            Screen.SubscribersList -> {
                                val subVm = subscriptionViewModel
                                com.telefam.ui.screens.subscription.SubscribersListScreen(
                                    viewModel = subVm,
                                    onBack = {
                                        screen = Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                                    },
                                    onOpenProfile = { uid -> screen = Screen.Profile(uid, Screen.SubscribersList) }
                                )
                            }

                            Screen.SubscriptionPlanEdit -> {
                                val subVm = subscriptionViewModel
                                val subCreatorState by subVm.creator.collectAsState()
                                LaunchedEffect(Unit) { subVm.loadCreator() }
                                val planToEdit = editingPlan
                                com.telefam.ui.screens.subscription.PlanEditorScreen(
                                    viewModel = subVm,
                                    existing = planToEdit,
                                    currency = planToEdit?.currency
                                        ?: subCreatorState.overview?.currency ?: "USD",
                                    onBack = {
                                        editingPlan = null
                                        screen = Screen.Subscriptions(com.telefam.data.AuthSession.currentUserId ?: "")
                                    }
                                )
                            }

                            is Screen.SubscribeToCreator -> {
                                val subVm = remember(s.creatorId) { newSubscriptionViewModel() }
                                com.telefam.ui.screens.subscription.SubscribeToCreatorScreen(
                                    creatorId = s.creatorId,
                                    viewModel = subVm,
                                    onBack = {
                                        screen = s.returnTo ?: Screen.Profile(s.creatorId, Screen.Home)
                                    },
                                    onMySubscriptions = { screen = Screen.MySubscriptions(screen) }
                                )
                            }

                            is Screen.MySubscriptions -> {
                                com.telefam.ui.screens.subscription.MySubscriptionsScreen(
                                    api = subscriptionApi,
                                    onBack = { screen = s.returnTo }
                                )
                            }

                            // ---- Wallet ----
                            Screen.Wallet -> {
                                val walletProfileVm = remember { newProfileViewModel() }
                                val walletUserId = com.telefam.data.AuthSession.currentUserId
                                LaunchedEffect(walletUserId) { if (walletUserId != null) walletProfileVm.load(walletUserId) }
                                val walletProfileState by walletProfileVm.state.collectAsState()
                                com.telefam.ui.screens.wallet.WalletScreen(
                                    profile = walletProfileState.profile,
                                    viewModel = walletViewModel,
                                    onBack = { screen = Screen.CreatorMenu(walletUserId ?: "", Screen.Home) },
                                    onOpenPending = { screen = Screen.WalletPending },
                                    onOpenSourceEarnings = { src -> screen = Screen.WalletSourceEarnings(src) },
                                    onOpenTransaction = { id -> screen = Screen.WalletTransactionDetails(id) },
                                    onSeeAllTransactions = { screen = Screen.WalletTransactions },
                                    onSeeAllPayouts = { screen = Screen.WalletPayouts },
                                    onOpenPayout = { id -> screen = Screen.WalletPayoutDetails(id) },
                                    onWithdraw = { screen = Screen.WalletWithdraw },
                                    onAddPaymentMethod = { screen = Screen.WalletAddMethod },
                                    onOpenPaymentMethod = { id -> screen = Screen.WalletMethodDetails(id) }
                                )
                            }

                            Screen.WalletPending -> com.telefam.ui.screens.wallet.PendingEarningsScreen(
                                api = walletApi,
                                onBack = { screen = Screen.Wallet }
                            )

                            is Screen.WalletSourceEarnings -> com.telefam.ui.screens.wallet.SourceEarningsScreen(
                                api = walletApi,
                                source = s.source,
                                onBack = { screen = Screen.Wallet },
                                onOpenTransaction = { id -> screen = Screen.WalletTransactionDetails(id) }
                            )

                            Screen.WalletTransactions -> com.telefam.ui.screens.wallet.TransactionsScreen(
                                api = walletApi,
                                onBack = { screen = Screen.Wallet },
                                onOpenTransaction = { id -> screen = Screen.WalletTransactionDetails(id) }
                            )

                            is Screen.WalletTransactionDetails -> com.telefam.ui.screens.wallet.TransactionDetailsScreen(
                                api = walletApi,
                                transactionId = s.transactionId,
                                onBack = { screen = Screen.WalletTransactions }
                            )

                            Screen.WalletWithdraw -> com.telefam.ui.screens.wallet.WithdrawScreen(
                                api = walletApi,
                                onBack = { screen = Screen.Wallet },
                                onPayoutCreated = { payout -> screen = Screen.WalletPayoutDetails(payout.id) },
                                onAddMethod = { screen = Screen.WalletAddMethod }
                            )

                            Screen.WalletPayouts -> com.telefam.ui.screens.wallet.PayoutsScreen(
                                api = walletApi,
                                onBack = { screen = Screen.Wallet },
                                onOpenPayout = { id -> screen = Screen.WalletPayoutDetails(id) }
                            )

                            is Screen.WalletPayoutDetails -> com.telefam.ui.screens.wallet.PayoutDetailsScreen(
                                api = walletApi,
                                payoutId = s.payoutId,
                                onBack = { screen = Screen.WalletPayouts },
                                onContactSupport = { screen = Screen.Terms(screen) }
                            )

                            Screen.WalletAddMethod -> com.telefam.ui.screens.wallet.AddPaymentMethodScreen(
                                api = walletApi,
                                onBack = { screen = Screen.Wallet },
                                onAdded = { walletViewModel.load(); screen = Screen.Wallet }
                            )

                            is Screen.WalletMethodDetails -> com.telefam.ui.screens.wallet.PaymentMethodDetailScreen(
                                api = walletApi,
                                methodId = s.methodId,
                                onBack = { screen = Screen.Wallet },
                                onRemoved = { walletViewModel.load(); screen = Screen.Wallet }
                            )

                            // ---- Stars store & paid campaigns ----
                            is Screen.CreateCampaign -> {
                                com.telefam.ui.screens.campaign.CreateCampaignScreen(
                                    viewModel = campaignViewModel,
                                    userId = s.userId,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onBuyStars = { screen = Screen.BuyStars(Screen.CreateCampaign(s.userId)) },
                                    onOpenSeries = { screen = Screen.SeriesVideos(s.userId) },
                                    onBoosted = { screen = Screen.MyCampaigns(s.userId) }
                                )
                            }

                            is Screen.MyCampaigns -> {
                                com.telefam.ui.screens.campaign.CampaignDashboardScreen(
                                    viewModel = campaignViewModel,
                                    onBack = { screen = Screen.CreatorMenu(s.userId, Screen.Home) },
                                    onBuyStars = { screen = Screen.BuyStars(Screen.MyCampaigns(s.userId)) },
                                    onCreateCampaign = { screen = Screen.CreateCampaign(s.userId) }
                                )
                            }

                            is Screen.SeriesVideos -> {
                                com.telefam.ui.screens.campaign.SeriesVideosScreen(
                                    viewModel = campaignViewModel,
                                    userId = s.userId,
                                    onBack = { screen = Screen.CreateCampaign(s.userId) },
                                    onBuyStars = { screen = Screen.BuyStars(Screen.SeriesVideos(s.userId)) },
                                    onCreatePost = { screen = Screen.CreatePost }
                                )
                            }

                            is Screen.BuyStars -> {
                                com.telefam.ui.screens.campaign.BuyStarsScreen(
                                    viewModel = campaignViewModel,
                                    onBack = { screen = s.returnTo },
                                    onCheckout = { screen = Screen.StarCheckout(s.returnTo) }
                                )
                            }

                            is Screen.StarCheckout -> {
                                com.telefam.ui.screens.campaign.StarCheckoutScreen(
                                    viewModel = campaignViewModel,
                                    onBack = { screen = Screen.BuyStars(s.returnTo) },
                                    onPurchased = { screen = s.returnTo }
                                )
                            }

                            is Screen.Terms -> {
                                com.telefam.ui.screens.TermsPoliciesScreen(
                                    onBack = { screen = s.returnTo },
                                    onOpenDoc = { doc -> screen = Screen.LegalDoc(doc.key, doc.title, screen) }
                                )
                            }

                            is Screen.LegalDoc -> {
                                var docContent by remember(s.key) { mutableStateOf<String?>(null) }
                                var docLoading by remember(s.key) { mutableStateOf(true) }
                                var docError by remember(s.key) { mutableStateOf<String?>(null) }
                                fun loadDoc() {
                                    docLoading = true; docError = null
                                    lifecycleScope.launch {
                                        runCatching { settingsApi.legalDocument(s.key) }
                                            .onSuccess { docContent = it }
                                            .onFailure { docError = it.message ?: "Couldn't load this document" }
                                        docLoading = false
                                    }
                                }
                                LaunchedEffect(s.key) { loadDoc() }
                                com.telefam.ui.screens.LegalDocScreen(
                                    doc = com.telefam.ui.screens.LegalDoc(s.key, s.title, ""),
                                    content = docContent,
                                    loading = docLoading,
                                    error = docError,
                                    onBack = { screen = s.returnTo },
                                    onRetry = { loadDoc() }
                                )
                            }

                            is Screen.EditProfile -> {
                                var account by remember(s.userId) { mutableStateOf<com.telefam.data.api.AccountDetailsDto?>(null) }
                                var saving by remember(s.userId) { mutableStateOf(false) }
                                var editError by remember(s.userId) { mutableStateOf<String?>(null) }
                                LaunchedEffect(s.userId) {
                                    account = runCatching { settingsApi.accountDetails() }.getOrNull()
                                }
                                val acc = account
                                if (acc == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = com.telefam.ui.theme.TelefamColors.PrimaryRed)
                                    }
                                } else {
                                    EditProfileScreen(
                                        avatarUrl = acc.avatarUrl,
                                        initialName = acc.fullName ?: "",
                                        initialUsername = acc.username ?: "",
                                        initialBio = acc.bio ?: "",
                                        initialWebsite = acc.website ?: "",
                                        initialLocation = acc.locationName ?: "",
                                        usernameCooldownDays = acc.usernameChangeCooldownDays,
                                        saving = saving,
                                        error = editError,
                                        onBack = { screen = Screen.Profile(s.userId, Screen.Home) },
                                        onSave = { name, uname, bio, website, location, photo, removeAvatar ->
                                            saving = true; editError = null
                                            lifecycleScope.launch {
                                                try {
                                                    val mediaId = photo?.let {
                                                        settingsApi.uploadProfileImage(it.bytes, it.mimeType)
                                                    }
                                                    settingsApi.editProfile(
                                                        com.telefam.data.api.EditProfileRequest(
                                                            fullName = name,
                                                            username = uname,
                                                            bio = bio,
                                                            profileImageMediaId = mediaId,
                                                            website = website,
                                                            locationName = location,
                                                            removeAvatar = removeAvatar && mediaId == null
                                                        )
                                                    )
                                                    screen = Screen.Profile(s.userId, Screen.Home)
                                                } catch (e: com.telefam.data.api.UsernameCooldownException) {
                                                    editError = "You can change your username again in ${e.daysRemaining} day(s)"
                                                } catch (e: Exception) {
                                                    editError = "Couldn't save your profile. Check the details and try again."
                                                } finally {
                                                    saving = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            is Screen.FollowList -> {
                                val listVm = remember(s.userId) { newProfileViewModel() }
                                FollowListScreen(
                                    userId = s.userId,
                                    initialKind = s.kind,
                                    viewModel = listVm,
                                    onOpenProfile = { uid -> screen = Screen.Profile(uid, Screen.FollowList(s.userId, s.kind)) },
                                    onBack = { screen = Screen.Profile(s.userId, Screen.Home) }
                                )
                            }

                            is Screen.CreatePost -> CreatePostScreen(
                                viewModel = createPostViewModel,
                                onClose = { screen = Screen.Home },
                                onOpenTrim = { screen = Screen.TrimPostVideo },
                                onPosted = { screen = Screen.Home }
                            )

                            is Screen.Feeds -> FeedScreen(
                                viewModelFactory = feedViewModelFactory,
                                shareTargets = conversations.map {
                                    com.telefam.ui.components.feed.ShareTarget(it.peerId, it.displayName)
                                },
                                onSendToChat = { post, target ->
                                    lifecycleScope.launch {
                                        chatService.send(
                                            target.peerId, "TEXT",
                                            text = "${post.displayHandle}: ${post.shareUrl}"
                                        )
                                    }
                                },
                                onEditPost = { post -> screen = Screen.EditPost(post.postId) },
                                onOpenSearch = { q -> screen = Screen.FeedSearch(q) },
                                onBack = { screen = Screen.Home },
                                commentsSheet = commentsSheetFor(Screen.Feeds, { screen = it }, { url -> sponsoredLinkUrl = url }),
                                onOpenProfile = { userId -> screen = Screen.Profile(userId, Screen.Feeds) },
                                onSponsoredImpression = { post ->
                                    post.sponsoredCampaignId?.let { id ->
                                        lifecycleScope.launch { runCatching { campaignApi.recordSponsoredEvent(id, "IMPRESSION") } }
                                    }
                                },
                                onSponsoredAction = { post ->
                                    post.sponsoredCampaignId?.let { id ->
                                        lifecycleScope.launch { runCatching { campaignApi.recordSponsoredEvent(id, "CLICK") } }
                                    }
                                    when (post.sponsoredAction) {
                                        "OPEN_LINK" -> {
                                            // Funnel: the advertiser sees Impressions → Clicks → Destination actions.
                                            post.sponsoredCampaignId?.let { id ->
                                                lifecycleScope.launch {
                                                    runCatching { campaignApi.recordSponsoredEvent(id, "DESTINATION_CLICK") }
                                                }
                                            }
                                            post.sponsoredLink?.let { sponsoredLinkUrl = it }
                                        }
                                        "FOLLOW" -> {
                                            // Funnel: Impressions → Profile visits → Follows.
                                            post.sponsoredCampaignId?.let { id ->
                                                lifecycleScope.launch {
                                                    runCatching { campaignApi.recordSponsoredEvent(id, "PROFILE_VISIT") }
                                                    runCatching { campaignApi.recordSponsoredEvent(id, "FOLLOW") }
                                                }
                                            }
                                            lifecycleScope.launch { runCatching { feedApi.follow(post.ownerId) } }
                                        }
                                        else -> Unit // WATCH: the sponsored video is already playing
                                    }
                                }
                            )

                            is Screen.FeedSearch -> FeedSearchScreen(
                                viewModelFactory = feedViewModelFactory,
                                initialQuery = s.initialQuery,
                                shareTargets = conversations.map {
                                    com.telefam.ui.components.feed.ShareTarget(it.peerId, it.displayName)
                                },
                                onSendToChat = { post, target ->
                                    lifecycleScope.launch {
                                        chatService.send(target.peerId, "TEXT", text = "${post.displayHandle}: ${post.shareUrl}")
                                    }
                                },
                                onEditPost = { post -> screen = Screen.EditPost(post.postId) },
                                onBack = { screen = Screen.Feeds },
                                onOpenProfile = { userId -> screen = Screen.Profile(userId, Screen.Feeds) },
                                commentsSheet = commentsSheetFor(Screen.FeedSearch(s.initialQuery), { screen = it }, { url -> sponsoredLinkUrl = url })
                            )

                            is Screen.EditPost -> EditPostScreen(
                                postId = s.postId,
                                feedApi = feedApi,
                                viewModel = null, // edits go straight to the API here; feeds reload on return
                                onClose = { screen = Screen.Feeds },
                                onSaved = { screen = Screen.Feeds }
                            )

                            is Screen.TrimPostVideo -> {
                                val postState = createPostViewModel.state.collectAsState().value
                                TrimVideoScreen(
                                    durationMs = postState.durationMs,
                                    frames = postState.frameStrip,
                                    initialStartMs = postState.trimStartMs,
                                    initialEndMs = postState.trimEndMs,
                                    onCancel = { screen = Screen.CreatePost },
                                    onApply = { start, end ->
                                        createPostViewModel.setTrim(start, end)
                                        screen = Screen.CreatePost
                                    }
                                )
                            }

                            is Screen.ChatOptions -> {
                                var isBlocked by remember(s.peerId) { mutableStateOf(false) }
                                var muteLabel by remember(s.peerId) { mutableStateOf("Off") }
                                var disappearingLabel by remember(s.peerId) { mutableStateOf("Off") }
                                var showClearConfirm by remember { mutableStateOf(false) }
                                var showBlockConfirm by remember { mutableStateOf(false) }

                                LaunchedEffect(s.peerId) {
                                    try {
                                        val blockedList: List<com.telefam.data.api.BlockedUserDto> = privacyApi.listBlocked().body()
                                        isBlocked = blockedList.any { it.userId == s.peerId }
                                    } catch (e: Exception) { }
                                    val local = chatSettingsLocalRepository.get(s.peerId)
                                    muteLabel = when {
                                        local?.mutedUntil == null -> "Off"
                                        local.mutedUntil == Long.MAX_VALUE -> "Until I turn it back on"
                                        else -> "Muted"
                                    }
                                    disappearingLabel = when (local?.disappearingMessagesSeconds ?: 0L) {
                                        0L -> "Off"; 86400L -> "24 hours"; 259200L -> "3 days"
                                        604800L -> "1 week"; 2592000L -> "1 month"; else -> "Off"
                                    }
                                }

                                ChatOptionsScreen(
                                    summary = ChatOptionsSummary(muteLabel, disappearingLabel, isBlocked),
                                    onClose = { screen = Screen.Chat(s.peerId, s.peerName) },
                                    onVideoCallClick = {
                                        withCallPermissions(video = true) {
                                            callController.startOutgoingCall(s.peerId, s.peerName, null, com.telefam.calls.CallType.VIDEO)
                                        }
                                    },
                                    onAudioCallClick = {
                                        withCallPermissions(video = false) {
                                            callController.startOutgoingCall(s.peerId, s.peerName, null, com.telefam.calls.CallType.AUDIO)
                                        }
                                    },
                                    onViewProfileClick = { screen = Screen.Profile(s.peerId, Screen.ChatOptions(s.peerId, s.peerName)) },
                                    onMediaLinksDocsClick = { screen = Screen.MediaLinksDocs(s.peerId) },
                                    onMuteClick = { screen = Screen.MuteOptions(s.peerId) },
                                    onBlockClick = { showBlockConfirm = true },
                                    onReportClick = { screen = Screen.Report(s.peerId) },
                                    onDisappearingMessagesClick = { screen = Screen.DisappearingMessages(s.peerId) },
                                    onClearChatClick = { showClearConfirm = true }
                                )

                                if (showBlockConfirm) {
                                    ConfirmDialog(
                                        title = if (isBlocked) "Unblock ${s.peerName}?" else "Block ${s.peerName}?",
                                        message = if (isBlocked) "They will be able to message and call you again." else "They won't be able to message or call you.",
                                        confirmLabel = if (isBlocked) "Unblock" else "Block",
                                        onConfirm = {
                                            val targetId = s.peerId
                                            val wasBlocked = isBlocked
                                            lifecycleScope.launch {
                                                if (wasBlocked) {
                                                    privacyApi.unblock(targetId)
                                                    blockedIdsField = blockedIdsField - targetId
                                                } else {
                                                    httpClient.post("${ApiConfig.baseUrl}/api/social/blocked") {
                                                        contentType(io.ktor.http.ContentType.Application.Json)
                                                        setBody(mapOf("userId" to targetId))
                                                    }
                                                    blockedIdsField = blockedIdsField + targetId
                                                }
                                            }
                                            isBlocked = !wasBlocked
                                            showBlockConfirm = false
                                        },
                                        onDismiss = { showBlockConfirm = false }
                                    )
                                }
                                if (showClearConfirm) {
                                    ConfirmDialog(
                                        title = "Clear Chat",
                                        message = "This removes all messages in this chat from your device. This can't be undone.",
                                        confirmLabel = "Clear",
                                        onConfirm = {
                                            chatLocalRepository.clearChat(s.peerId)
                                            refreshConversations()
                                            showClearConfirm = false
                                            screen = Screen.Home
                                        },
                                        onDismiss = { showClearConfirm = false }
                                    )
                                }
                            }

                            is Screen.MuteOptions -> {
                                val current = when (chatSettingsLocalRepository.get(s.peerId)?.mutedUntil) {
                                    null -> MuteOption.OFF
                                    Long.MAX_VALUE -> MuteOption.ALWAYS
                                    else -> MuteOption.EIGHT_HOURS
                                }
                                MuteOptionsScreen(
                                    current = current,
                                    onBackClick = { screen = Screen.ChatOptions(s.peerId, "") },
                                    onSelect = { option ->
                                        val now = System.currentTimeMillis() / 1000
                                        val mutedUntil = option.toMutedUntilEpochSeconds(now)
                                        val existing = chatSettingsLocalRepository.get(s.peerId)
                                        chatSettingsLocalRepository.upsert(
                                            s.peerId, mutedUntil, existing?.disappearingMessagesSeconds ?: 0L,
                                            existing?.cachedTheme, existing?.cachedBubbleColour,
                                            existing?.cachedPeerScreenshotPolicy ?: "ANYONE", existing?.cachedPeerSharePolicy ?: "ANYONE",
                                            existing?.cachedPeerCopyPolicy ?: "ANYONE", existing?.cachedPeerDownloadPolicy ?: "ANYONE",
                                            System.currentTimeMillis()
                                        )
                                        lifecycleScope.launch {
                                            offlineRepository.performOrQueue(
                                                "${ApiConfig.baseUrl}/api/chat-prefs/mute", HttpMethod.Post,
                                                """{"peerId":"${s.peerId}","mutedUntilEpochSeconds":${mutedUntil ?: "null"}}"""
                                            )
                                            OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        }
                                    }
                                )
                            }

                            is Screen.DisappearingMessages -> {
                                val currentSeconds = chatSettingsLocalRepository.get(s.peerId)?.disappearingMessagesSeconds ?: 0L
                                val current = DisappearingOption.entries.firstOrNull { it.seconds == currentSeconds } ?: DisappearingOption.OFF
                                DisappearingMessagesScreen(
                                    current = current,
                                    onBackClick = { screen = Screen.ChatOptions(s.peerId, "") },
                                    onSelect = { option ->
                                        lifecycleScope.launch { chatService.setDisappearing(s.peerId, option.seconds) }
                                    }
                                )
                            }

                            is Screen.Report -> {
                                var isSubmitting by remember { mutableStateOf(false) }
                                var submitted by remember { mutableStateOf(false) }
                                ReportScreen(
                                    onBackClick = { screen = Screen.ChatOptions(s.peerId, "") },
                                    isSubmitting = isSubmitting,
                                    submitted = submitted,
                                    onSubmit = { reason ->
                                        isSubmitting = true
                                        lifecycleScope.launch {
                                            try { reportApi.submit(s.peerId, reason); submitted = true }
                                            finally { isSubmitting = false }
                                        }
                                    }
                                )
                            }

                            is Screen.MediaLinksDocs -> {
                                val media = chatLocalRepository.mediaItems(s.peerId).map {
                                    MediaLinkDocItem(it.id, it.outgoing, it.textOrCaption ?: it.contentCategory, it.createdAt.toString())
                                }
                                val docs = chatLocalRepository.docItems(s.peerId).map {
                                    MediaLinkDocItem(it.id, it.outgoing, it.textOrCaption ?: "File", it.createdAt.toString())
                                }
                                val links = chatLocalRepository.linkItems(s.peerId).map { (msg, url) ->
                                    MediaLinkDocItem(msg.id, msg.outgoing, url, msg.createdAt.toString())
                                }
                                MediaLinksDocsScreen(media, links, docs, onBackClick = { screen = Screen.ChatOptions(s.peerId, "") })
                            }

                            is Screen.Privacy -> PrivacyMainScreen(
                                state = PrivacyMainState(
                                    messageRequests = AccessLevel.valueOf(privacy.messageRequests),
                                    whoCanCallMe = AccessLevel.valueOf(privacy.whoCanCallMe),
                                    whoCanScreenshotChats = AccessLevel.valueOf(privacy.whoCanScreenshotChats),
                                    whoCanShareChats = AccessLevel.valueOf(privacy.whoCanShareChats),
                                    whoCanCopyMessages = AccessLevel.valueOf(privacy.whoCanCopyMessages),
                                    whoCanDownloadMedia = AccessLevel.valueOf(privacy.whoCanDownloadMedia),
                                    whoCanSeeLastSeen = AccessLevel.valueOf(privacy.whoCanSeeLastSeen),
                                    chatsTheme = ChatsTheme.valueOf(privacy.chatsTheme),
                                    messageBubbleColour = BubbleColour.valueOf(privacy.messageBubbleColour)
                                ),
                                onBackClick = { screen = Screen.Home },
                                onOpenMessageRequests = { screen = Screen.PrivacyOption("messageRequests") },
                                onOpenCallMe = { screen = Screen.PrivacyOption("whoCanCallMe") },
                                onOpenScreenshot = { screen = Screen.PrivacyOption("whoCanScreenshotChats") },
                                onOpenShare = { screen = Screen.PrivacyOption("whoCanShareChats") },
                                onOpenCopy = { screen = Screen.PrivacyOption("whoCanCopyMessages") },
                                onOpenDownload = { screen = Screen.PrivacyOption("whoCanDownloadMedia") },
                                onOpenLastSeen = { screen = Screen.PrivacyOption("whoCanSeeLastSeen") },
                                onOpenTheme = { screen = Screen.ChatsThemeScreen },
                                onOpenBubbleColour = { screen = Screen.BubbleColourScreen }
                            )

                            is Screen.PrivacyOption -> PrivacyOptionScreen(
                                field = s.field,
                                icon = com.telefam.ui.components.iconForPrivacyField(s.field),
                                title = com.telefam.ui.components.localizedTitle(s.field),
                                description = com.telefam.ui.components.localizedDescription(s.field),
                                current = AccessLevel.valueOf(
                                    when (s.field) {
                                        "messageRequests" -> privacy.messageRequests
                                        "whoCanCallMe" -> privacy.whoCanCallMe
                                        "whoCanScreenshotChats" -> privacy.whoCanScreenshotChats
                                        "whoCanShareChats" -> privacy.whoCanShareChats
                                        "whoCanCopyMessages" -> privacy.whoCanCopyMessages
                                        "whoCanSeeLastSeen" -> privacy.whoCanSeeLastSeen
                                        else -> privacy.whoCanDownloadMedia
                                    }
                                ),
                                onBackClick = { screen = Screen.Privacy },
                                onSave = { level -> saveField(s.field, level.name) }
                            )

                            is Screen.ChatsThemeScreen -> ChatsThemeScreen(
                                current = ChatsTheme.valueOf(privacy.chatsTheme),
                                onBackClick = { screen = Screen.Privacy },
                                onSave = { theme -> ThemeController.update(theme); saveField("chatsTheme", theme.name) }
                            )

                            is Screen.BubbleColourScreen -> MessageBubbleColourScreen(
                                current = BubbleColour.valueOf(privacy.messageBubbleColour),
                                onBackClick = { screen = Screen.Privacy },
                                onSave = { colour -> ThemeController.update(colour); saveField("messageBubbleColour", colour.name) }
                            )

                            is Screen.MessageRequests -> MessageRequestsScreen(
                                requests = requests,
                                onBackClick = { screen = Screen.Home },
                                onAccept = { id ->
                                    lifecycleScope.launch {
                                        offlineRepository.performOrQueue("${ApiConfig.baseUrl}/api/social/requests/$id/respond", HttpMethod.Post, """{"accept":true}""")
                                        OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        requests = requests.filterNot { it.id == id }
                                    }
                                },
                                onDecline = { id ->
                                    lifecycleScope.launch {
                                        offlineRepository.performOrQueue("${ApiConfig.baseUrl}/api/social/requests/$id/respond", HttpMethod.Post, """{"accept":false}""")
                                        OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        requests = requests.filterNot { it.id == id }
                                    }
                                }
                            )

                            is Screen.Archived -> ArchivedChatsScreen(
                                chats = archived,
                                onBackClick = { screen = Screen.Home },
                                onUnarchive = { chatId ->
                                    lifecycleScope.launch {
                                        offlineRepository.performOrQueue("${ApiConfig.baseUrl}/api/social/archived/$chatId", HttpMethod.Delete, null)
                                        OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        archived = archived.filterNot { it.chatId == chatId }
                                    }
                                }
                            )

                            is Screen.Blocked -> BlockedUsersScreen(
                                users = blocked,
                                onBackClick = { screen = Screen.Home },
                                onUnblock = { userId ->
                                    lifecycleScope.launch {
                                        offlineRepository.performOrQueue("${ApiConfig.baseUrl}/api/social/blocked/$userId", HttpMethod.Delete, null)
                                        OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        blocked = blocked.filterNot { it.userId == userId }
                                    }
                                }
                            )

                            is Screen.NewChat -> {
                                var query by remember { mutableStateOf("") }
                                var results by remember { mutableStateOf(listOf<DirectoryUserDto>()) }
                                LaunchedEffect(query) {
                                    kotlinx.coroutines.delay(300) // debounce
                                    results = if (query.trim().length >= 3) runCatching { userDirectoryApi.search(query.trim()) }.getOrDefault(emptyList()) else emptyList()
                                }
                                NewChatScreen(
                                    query = query, onQueryChange = { query = it }, results = results,
                                    onBackClick = { screen = Screen.Home },
                                    onPick = { user ->
                                        val name = user.fullName ?: user.username ?: "Unknown"
                                        chatLocalRepository.upsertPeer(user.userId, name, user.username)
                                        refreshConversations()
                                        loadChat(user.userId)
                                        screen = Screen.Chat(user.userId, name)
                                    }
                                )
                            }

                            is Screen.Chat -> {
                                val settingsRow = chatSettingsLocalRepository.get(s.peerId)
                                val policies = ChatPolicies(
                                    allowScreenshot = (settingsRow?.cachedPeerScreenshotPolicy ?: "ANYONE") != "NOBODY",
                                    allowCopy = (settingsRow?.cachedPeerCopyPolicy ?: "ANYONE") != "NOBODY",
                                    allowForward = (settingsRow?.cachedPeerSharePolicy ?: "ANYONE") != "NOBODY",
                                    allowDownload = (settingsRow?.cachedPeerDownloadPolicy ?: "ANYONE") != "NOBODY"
                                )
                                LaunchedEffect(s.peerId) { lifecycleScope.launch { chatService.markChatRead(s.peerId) } }
                                val launchContactPicker = rememberContactPicker { picked ->
                                    if (picked != null) lifecycleScope.launch {
                                        chatService.send(s.peerId, "CONTACT", text = picked.encode())
                                        loadChat(s.peerId); refreshConversations()
                                    }
                                }

                                // --- Attachment pickers (all functional, all routed through the encrypted pipeline) ---
                                fun sendPicked(picked: com.telefam.chat.PickedFile?, category: String) {
                                    if (picked == null) return
                                    lifecycleScope.launch {
                                        when (category) {
                                            "FILE" -> chatService.send(
                                                s.peerId, "FILE",
                                                text = FileMeta(picked.displayName ?: "file", picked.mimeType, picked.sizeBytes).encode(),
                                                mediaPath = picked.path
                                            )
                                            "AUDIO" -> chatService.send(s.peerId, "AUDIO", mediaPath = picked.path, durationSeconds = picked.durationSeconds)
                                            "VIDEO" -> chatService.send(s.peerId, "VIDEO", mediaPath = picked.path, durationSeconds = picked.durationSeconds)
                                            else -> chatService.send(s.peerId, category, mediaPath = picked.path)
                                        }
                                        loadChat(s.peerId); refreshConversations()
                                    }
                                }
                                val launchGalleryPicker = rememberMediaPicker(PickKind.IMAGE) { sendPicked(it, "IMAGE") }
                                val launchClipPicker = rememberMediaPicker(PickKind.VIDEO) { sendPicked(it, "VIDEO") }
                                val launchAudioPicker = rememberMediaPicker(PickKind.AUDIO) { sendPicked(it, "AUDIO") }
                                val launchDocumentPicker = rememberMediaPicker(PickKind.DOCUMENT) { sendPicked(it, "FILE") }
                                val launchCamera = rememberCameraCapture { sendPicked(it, "IMAGE") }

                                val presence = presenceMap[s.peerId]
                                val typingActive = (typingUntilMap[s.peerId] ?: 0L) > System.currentTimeMillis()
                                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); nowMs = System.currentTimeMillis() } }
                                // null = nothing to show (typing is rendered by ChatScreen itself; hidden/unknown presence shows no status)
                                val statusText = if (typingActive) null
                                    else presence?.let { com.telefam.ui.components.presenceLabel(it.first, it.second, nowMs) }
                                val pinnedList = remember(s.peerId, messagesVersion) { chatLocalRepository.pinnedMessages(s.peerId) }

                                // Two-sided block state: cached instantly (offline), confirmed by the server.
                                var blockStatus by remember(s.peerId) {
                                    mutableStateOf(
                                        com.telefam.data.api.PrivacyApi.BlockStatusDto(
                                            iBlocked = blockedIdsField.contains(s.peerId),
                                            blockedMe = false
                                        )
                                    )
                                }
                                LaunchedEffect(s.peerId, blockedIdsField) {
                                    runCatching {
                                        val dto: com.telefam.data.api.PrivacyApi.BlockStatusDto =
                                            privacyApi.blockStatus(s.peerId).body()
                                        blockStatus = dto
                                    }
                                }

                                ChatScreen(
                                    // The official account: read-only conversation, no composer, no calls.
                                    isOfficialAccount = systemInbox?.officialUserId == s.peerId,
                                    peerBlockedByMe = blockStatus.iBlocked,
                                    peerBlockedMe = blockStatus.blockedMe,
                                    onUnblockPeer = {
                                        lifecycleScope.launch {
                                            blockedIdsField = blockedIdsField - s.peerId
                                            blockStatus = blockStatus.copy(iBlocked = false)
                                            offlineRepository.performOrQueue(
                                                "${ApiConfig.baseUrl}/api/social/blocked/${s.peerId}",
                                                io.ktor.http.HttpMethod.Delete, ""
                                            )
                                            OutboxSyncWorker.scheduleOneTime(applicationContext)
                                        }
                                    },
                                    peerName = s.peerName, peerAvatarUrl = null,
                                    peerOnline = presence?.first == true, peerStatus = statusText,
                                    peerTyping = typingActive,
                                    currentUserId = currentUserIdField ?: "",
                                    messages = chatMessages, hasMoreOlder = chatHasMoreOlder, policies = policies,
                                    pinnedMessages = pinnedList,
                                    voiceRecorder = remember(s.peerId) { newVoiceRecorder() }, audioPlayer = audioPlayer,
                                    ensureMicPermission = { cb -> requestMicPermission(cb) },
                                    setSecureScreen = ::setSecureScreen,
                                    onBack = { chatMessages = emptyList(); screen = Screen.Home }, // drop any window expanded by search
                                    onOpenOptions = { screen = Screen.ChatOptions(s.peerId, s.peerName) },
                                    onOpenProfile = { screen = Screen.Profile(s.peerId, Screen.Chat(s.peerId, s.peerName)) },
                                    onLoadOlder = {
                                        val older = chatLocalRepository.loadPage(s.peerId, chatMessages.lastOrNull()?.createdAt ?: Long.MAX_VALUE, 30)
                                        if (older.isNotEmpty()) { chatMessages = chatMessages + older; chatHasMoreOlder = older.size >= 30 } else chatHasMoreOlder = false
                                    },
                                    onSendText = { txt, replyTo ->
                                        lifecycleScope.launch {
                                            chatService.send(s.peerId, "TEXT", text = txt, replyTo = replyTo)
                                            loadChat(s.peerId); refreshConversations()
                                        }
                                    },
                                    onSendVoice = { audio, viewOnce ->
                                        lifecycleScope.launch {
                                            chatService.send(s.peerId, "VOICE", mediaPath = audio.filePath, durationSeconds = audio.durationSeconds, viewOnce = viewOnce)
                                            loadChat(s.peerId); refreshConversations()
                                        }
                                    },
                                    onSendEvent = { ev ->
                                        lifecycleScope.launch {
                                            chatService.send(s.peerId, "EVENT", text = ev.encode())
                                            loadChat(s.peerId); refreshConversations()
                                        }
                                    },
                                    onAttachment = { type ->
                                        when (type) {
                                            AttachmentType.POLL -> screen = Screen.PollComposer(s.peerId, s.peerName)
                                            AttachmentType.LOCATION -> screen = Screen.LocationPicker(s.peerId, s.peerName)
                                            AttachmentType.CONTACT -> launchContactPicker()
                                            AttachmentType.GALLERY -> launchGalleryPicker()
                                            AttachmentType.CAMERA -> launchCamera()
                                            AttachmentType.DOCUMENT -> launchDocumentPicker()
                                            AttachmentType.AUDIO -> launchAudioPicker()
                                            AttachmentType.CLIP -> launchClipPicker()
                                            AttachmentType.GIF -> screen = Screen.GifStickerPicker(s.peerId, s.peerName, GiphyMode.GIF)
                                            AttachmentType.STICKER -> screen = Screen.GifStickerPicker(s.peerId, s.peerName, GiphyMode.STICKER)
                                            else -> {}
                                        }
                                    },
                                    onVotePoll = { msg, poll, selected ->
                                        lifecycleScope.launch { chatService.sendPollVote(s.peerId, msg, poll, selected); loadChat(s.peerId) }
                                    },
                                    onViewOnceConsumed = { id ->
                                        chatLocalRepository.consumeViewOnce(id); loadChat(s.peerId)
                                    },
                                    onDeleteForMe = { msg ->
                                        chatService.deleteForMe(msg); loadChat(s.peerId); refreshConversations()
                                    },
                                    onDeleteForEveryone = { msg ->
                                        lifecycleScope.launch { chatService.deleteForEveryone(msg); loadChat(s.peerId); refreshConversations() }
                                    },
                                    onEditMessage = { msg, newText ->
                                        lifecycleScope.launch { chatService.editMessage(msg, newText); loadChat(s.peerId) }
                                    },
                                    onReact = { msg, emoji ->
                                        lifecycleScope.launch { chatService.toggleReaction(msg, emoji); loadChat(s.peerId) }
                                    },
                                    onTogglePin = { msg ->
                                        lifecycleScope.launch { chatService.setPinned(msg, !msg.pinned); loadChat(s.peerId) }
                                    },
                                    onToggleStar = { msg ->
                                        chatService.toggleStarred(msg); loadChat(s.peerId)
                                    },
                                    onForward = { msg -> screen = Screen.ForwardPicker(s.peerId, s.peerName, msg.id) },
                                    onRetryMedia = { msg ->
                                        lifecycleScope.launch { chatService.fetchRemoteMedia(msg.id); loadChat(s.peerId) }
                                    },
                                    onTyping = { realtimeClient.sendTyping(s.peerId) },
                                    onStartVideoCall = {
                                        withCallPermissions(video = true) {
                                            callController.startOutgoingCall(s.peerId, s.peerName, null, com.telefam.calls.CallType.VIDEO)
                                        }
                                    },
                                    onStartAudioCall = {
                                        withCallPermissions(video = false) {
                                            callController.startOutgoingCall(s.peerId, s.peerName, null, com.telefam.calls.CallType.AUDIO)
                                        }
                                    },
                                    // Whole local history, off the main thread (the chat window only holds the newest pages).
                                    onSearchMessages = { q -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { chatLocalRepository.searchMessages(s.peerId, q) } },
                                    onStarredMessages = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { chatLocalRepository.starredMessages(s.peerId) } },
                                    onEnsureMessageLoaded = { id ->
                                        if (chatMessages.none { it.id == id }) {
                                            chatLocalRepository.getById(id)?.takeIf { it.peerId == s.peerId }?.let { target ->
                                                chatMessages = chatLocalRepository.messagesSince(s.peerId, target.createdAt)
                                                chatHasMoreOlder = chatLocalRepository.hasOlderThan(s.peerId, target.createdAt)
                                            }
                                        }
                                    },
                                    onResolveMention = { username -> userDirectoryApi.lookupByUsername(username) },
                                    onMessageUser = { user ->
                                        val name = user.fullName ?: user.username ?: "Unknown"
                                        chatLocalRepository.upsertPeer(user.userId, name, user.username)
                                        refreshConversations()
                                        loadChat(user.userId)
                                        screen = Screen.Chat(user.userId, name)
                                    },
                                    onRestrictedAttempt = { action ->
                                        lifecycleScope.launch { chatService.flagAttempt(s.peerId, action.name) }
                                    },
                                    onContactSave = { c -> saveContactToDevice(applicationContext, c) },
                                    onContactOpen = { c -> openContact(applicationContext, c) },
                                    onContactCall = { c -> callContact(applicationContext, c) },
                                    initialDraft = remember(s.peerId) { chatLocalRepository.getDraft(s.peerId) },
                                    onDraftChange = { draft -> chatLocalRepository.saveDraft(s.peerId, draft); refreshConversations() }
                                )
                            }

                            is Screen.GifStickerPicker -> GifStickerPickerScreen(
                                api = giphyApi,
                                initialMode = s.mode,
                                onBack = { screen = Screen.Chat(s.peerId, s.peerName) },
                                onSend = { item, mode ->
                                    lifecycleScope.launch {
                                        // Download the bytes, persist locally, then send through the
                                        // encrypted pipeline exactly like any local media file.
                                        val bytes = giphyApi.downloadBytes(item)
                                        if (bytes != null) {
                                            val ext = if (item.isVideo) "mp4" else "gif"
                                            val path = "${applicationContext.filesDir.absolutePath}/giphy_${System.currentTimeMillis()}.$ext"
                                            if (writeLocalFile(path, bytes)) {
                                                val category = when (mode) {
                                                    GiphyMode.GIF -> "GIF"
                                                    GiphyMode.STICKER -> "STICKER"
                                                    GiphyMode.CLIP -> "VIDEO"
                                                }
                                                chatService.send(s.peerId, category, mediaPath = path)
                                            }
                                        }
                                        loadChat(s.peerId); refreshConversations()
                                        screen = Screen.Chat(s.peerId, s.peerName)
                                    }
                                }
                            )

                            is Screen.ForwardPicker -> {
                                val forwardMessage = remember(s.messageId) { chatLocalRepository.getById(s.messageId) }
                                var forwardSending by remember { mutableStateOf(false) }
                                ForwardPickerScreen(
                                    conversations = conversations,
                                    excludePeerId = s.peerId,
                                    sending = forwardSending,
                                    onBack = { screen = Screen.Chat(s.peerId, s.peerName) },
                                    onSend = { targets ->
                                        val msg = forwardMessage ?: return@ForwardPickerScreen
                                        forwardSending = true
                                        lifecycleScope.launch {
                                            chatService.forward(msg, targets)
                                            refreshConversations()
                                            forwardSending = false
                                            screen = Screen.Chat(s.peerId, s.peerName)
                                        }
                                    }
                                )
                            }

                            is Screen.PollComposer -> PollComposerScreen(
                                onBackClick = { screen = Screen.Chat(s.peerId, s.peerName) },
                                onSend = { poll ->
                                    lifecycleScope.launch {
                                        chatService.send(s.peerId, "POLL", text = poll.encode())
                                        loadChat(s.peerId); refreshConversations()
                                    }
                                    screen = Screen.Chat(s.peerId, s.peerName)
                                }
                            )

                            is Screen.LocationPicker -> {
                                var searchResults by remember { mutableStateOf(listOf<LocationData>()) }
                                var pending by remember { mutableStateOf<LocationData?>(null) }
                                LocationPickerScreen(
                                    initialLocation = pending, searchResults = searchResults,
                                    onSearchQueryChange = { q ->
                                        lifecycleScope.launch { searchResults = runCatching { placeSearchApi.search(q) }.getOrDefault(emptyList()) }
                                    },
                                    onUseCurrentLocation = {
                                        requestLocationPermission { granted ->
                                            if (granted) lifecycleScope.launch { pending = getCurrentLocation(applicationContext) }
                                        }
                                    },
                                    onBackClick = { screen = Screen.Chat(s.peerId, s.peerName) },
                                    onSend = { loc, viewOnce, disappearAfter ->
                                        lifecycleScope.launch {
                                            chatService.send(s.peerId, "LOCATION", text = loc.encode(), viewOnce = viewOnce, ttlOverrideSeconds = disappearAfter)
                                            loadChat(s.peerId); refreshConversations()
                                        }
                                        screen = Screen.Chat(s.peerId, s.peerName)
                                    }
                                )
                            }

                            // ---- Linked devices (QR pairing, accept/decline, session removal) ----
                            Screen.LinkedDevices -> {
                                val linkedVm = remember {
                                    com.telefam.devices.LinkedDevicesViewModel(
                                        api = deviceLinkApi,
                                        settingsApi = settingsApi,
                                        onSessionLinked = { access, refresh ->
                                            // Scanner side: adopt the freshly-minted session immediately so the
                                            // redirect progress lands directly on a signed-in Home.
                                            AuthSession.accessToken = access
                                            AuthSession.refreshToken = refresh
                                            AuthSession.currentUserId = userIdFromAccessToken(access)
                                            currentUserIdField = AuthSession.currentUserId
                                            sessionActive = true
                                            lifecycleScope.launch { runCatching { messageRepository.registerThisDevice() } }
                                            // Register this newly-linked device for incoming-call push.
                                            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                                                .addOnSuccessListener { token ->
                                                    lifecycleScope.launch { runCatching { PushTokenUploader.upload(applicationContext, token) } }
                                                }
                                            refreshBlockedIds()
                                            refreshPrivacy()
                                        }
                                    )
                                }
                                LinkedDevicesScreen(
                                    viewModel = linkedVm,
                                    onBack = {
                                        screen = if (AuthSession.hasActiveSession()) Screen.Home else Screen.Login
                                    },
                                    onRedirectComplete = {
                                        refreshConversations()
                                        screen = Screen.Home
                                    },
                                    signedIn = AuthSession.hasActiveSession()
                                )
                            }

                            // ---- Settings & privacy hub ----
                            Screen.Settings -> SettingsMainScreen(
                                onBack = { screen = Screen.Home },
                                onOpenAccount = { screen = Screen.SettingsAccount },
                                onOpenAccountPrivacy = { refreshPrivacy(); screen = Screen.Privacy },
                                onOpenSecurity = { screen = Screen.SettingsSecurity },
                                onOpenShareProfile = {
                                    val myId = AuthSession.currentUserId
                                    if (myId != null) screen = Screen.ShareProfile(myId)
                                },
                                onOpenContentPreferences = { screen = Screen.ContentPreferences },
                                onOpenTimeManagement = { screen = Screen.TimeManagement },
                                onOpenAccessibility = { screen = Screen.AccessibilitySettings },
                                onOpenPrivacy = { refreshPrivacy(); screen = Screen.Privacy },
                                onOpenBlocked = {
                                    lifecycleScope.launch {
                                        try {
                                            val list: List<com.telefam.data.api.BlockedUserDto> = privacyApi.listBlocked().body()
                                            blocked = list.map { BlockedUserItem(it.userId, it.name ?: "Unknown") }
                                        } catch (e: Exception) { }
                                    }
                                    screen = Screen.Blocked
                                },
                                onOpenReportProblem = { screen = Screen.ReportProblem },
                                onOpenSafetyCenter = { screen = Screen.SafetyCenter },
                                onOpenHelp = { screen = Screen.HelpSupport },
                                onOpenTerms = { screen = Screen.Terms(screen) },
                                onOpenAbout = { screen = Screen.About },
                                onLogout = { performLogout() },
                                onOpenLinkedDevices = { screen = Screen.LinkedDevices }
                            )

                            Screen.SettingsAccount -> {
                                var account by remember { mutableStateOf<com.telefam.data.api.AccountDetailsDto?>(null) }
                                var accountLoading by remember { mutableStateOf(true) }
                                LaunchedEffect(Unit) {
                                    accountLoading = true
                                    account = runCatching { settingsApi.accountDetails() }.getOrNull()
                                    accountLoading = false
                                }
                                AccountScreen(
                                    account = account,
                                    loading = accountLoading,
                                    onBack = { screen = Screen.Settings },
                                    onEditProfile = {
                                        val myId = AuthSession.currentUserId
                                        if (myId != null) screen = Screen.EditProfile(myId)
                                    },
                                    onChangeEmail = { screen = Screen.ChangeEmail },
                                    onChangePhone = { screen = Screen.ChangePhone },
                                    onChangePassword = { screen = Screen.ChangePassword },
                                    onAccountRecovery = { screen = Screen.ChangePassword },
                                    onDeactivate = {
                                        lifecycleScope.launch { runCatching { settingsApi.deactivate() } }
                                        performLogout()
                                    },
                                    onDelete = {
                                        lifecycleScope.launch { runCatching { settingsApi.deleteAccount() } }
                                        performLogout()
                                    }
                                )
                            }

                            Screen.SettingsSecurity -> {
                                var twoFa by remember { mutableStateOf(false) }
                                var loginAlerts by remember { mutableStateOf(true) }
                                var sessions by remember { mutableStateOf(listOf<com.telefam.data.api.SessionDto>()) }
                                var sessionsLoading by remember { mutableStateOf(true) }
                                fun reloadSessions() {
                                    lifecycleScope.launch {
                                        sessions = runCatching { settingsApi.listSessions(AuthSession.refreshToken) }.getOrDefault(sessions)
                                        sessionsLoading = false
                                    }
                                }
                                LaunchedEffect(Unit) {
                                    runCatching { settingsApi.securitySettings() }.onSuccess {
                                        twoFa = it.twoFactorEnabled; loginAlerts = it.loginAlertsEnabled
                                    }
                                    reloadSessions()
                                }
                                SecurityPermissionsScreen(
                                    twoFactorEnabled = twoFa,
                                    loginAlertsEnabled = loginAlerts,
                                    sessions = sessions,
                                    loading = sessionsLoading,
                                    onBack = { screen = Screen.Settings },
                                    onChangePassword = { screen = Screen.ChangePassword },
                                    onToggleTwoFactor = { enabled ->
                                        twoFa = enabled
                                        lifecycleScope.launch {
                                            runCatching {
                                                settingsApi.updateSecuritySettings(com.telefam.data.api.SecuritySettingsUpdate(twoFactorEnabled = enabled))
                                            }.onFailure { twoFa = !enabled } // revert on failure
                                        }
                                    },
                                    onToggleLoginAlerts = { enabled ->
                                        loginAlerts = enabled
                                        lifecycleScope.launch {
                                            runCatching {
                                                settingsApi.updateSecuritySettings(com.telefam.data.api.SecuritySettingsUpdate(loginAlertsEnabled = enabled))
                                            }.onFailure { loginAlerts = !enabled }
                                        }
                                    },
                                    onRevokeSession = { session ->
                                        lifecycleScope.launch {
                                            runCatching { settingsApi.revokeSession(session.sessionId) }
                                            reloadSessions()
                                        }
                                    },
                                    onRevokeOtherSessions = {
                                        lifecycleScope.launch {
                                            runCatching { settingsApi.revokeOtherSessions(AuthSession.refreshToken) }
                                            reloadSessions()
                                        }
                                    }
                                )
                            }

                            Screen.ChangeEmail -> {
                                var currentEmail by remember { mutableStateOf("") }
                                var saving by remember { mutableStateOf(false) }
                                var changeError by remember { mutableStateOf<String?>(null) }
                                var changeInfo by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(Unit) {
                                    currentEmail = runCatching { settingsApi.accountDetails() }.getOrNull()?.email ?: ""
                                }
                                ChangeEmailScreen(
                                    currentEmail = currentEmail,
                                    saving = saving,
                                    error = changeError,
                                    info = changeInfo,
                                    onBack = { screen = Screen.SettingsAccount },
                                    onRequestCode = { newEmail ->
                                        saving = true; changeError = null; changeInfo = null
                                        lifecycleScope.launch {
                                            try {
                                                val res = settingsApi.requestEmailChange(newEmail)
                                                if (res.status.value in 200..299) changeInfo = "Code sent to $newEmail"
                                                else changeError = "Couldn't send the code. The email may already be in use."
                                            } catch (e: Exception) {
                                                changeError = "Couldn't send the code. Check your connection and try again."
                                            } finally { saving = false }
                                        }
                                    },
                                    onConfirm = { newEmail, code ->
                                        saving = true; changeError = null
                                        lifecycleScope.launch {
                                            try {
                                                val res = settingsApi.confirmEmailChange(newEmail, code)
                                                if (res.status.value in 200..299) screen = Screen.SettingsAccount
                                                else changeError = "Invalid or expired code"
                                            } catch (e: Exception) {
                                                changeError = "Couldn't verify the code. Check your connection and try again."
                                            } finally { saving = false }
                                        }
                                    }
                                )
                            }

                            Screen.ChangePhone -> {
                                var phoneAccount by remember { mutableStateOf<com.telefam.data.api.AccountDetailsDto?>(null) }
                                var saving by remember { mutableStateOf(false) }
                                var phoneError by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(Unit) {
                                    phoneAccount = runCatching { settingsApi.accountDetails() }.getOrNull()
                                }
                                ChangePhoneScreen(
                                    currentCountryCode = phoneAccount?.phoneCountryCode,
                                    currentNumber = phoneAccount?.phoneNumber,
                                    saving = saving,
                                    error = phoneError,
                                    onBack = { screen = Screen.SettingsAccount },
                                    onSave = { code, number ->
                                        saving = true; phoneError = null
                                        lifecycleScope.launch {
                                            try {
                                                val res = settingsApi.updatePhone(code, number)
                                                if (res.status.value in 200..299) screen = Screen.SettingsAccount
                                                else phoneError = "Couldn't update the phone number"
                                            } catch (e: Exception) {
                                                phoneError = "Couldn't update the phone number. Check your connection and try again."
                                            } finally { saving = false }
                                        }
                                    }
                                )
                            }

                            Screen.ChangePassword -> {
                                var saving by remember { mutableStateOf(false) }
                                var passwordError by remember { mutableStateOf<String?>(null) }
                                ChangePasswordScreen(
                                    saving = saving,
                                    error = passwordError,
                                    onBack = { screen = Screen.SettingsSecurity },
                                    onSubmit = { current, new ->
                                        saving = true; passwordError = null
                                        lifecycleScope.launch {
                                            try {
                                                val res = settingsApi.changePassword(current, new)
                                                when (res.status.value) {
                                                    in 200..299 -> screen = Screen.SettingsSecurity
                                                    401 -> passwordError = "Current password is incorrect"
                                                    else -> passwordError = "Couldn't update the password"
                                                }
                                            } catch (e: Exception) {
                                                passwordError = "Couldn't update the password. Check your connection and try again."
                                            } finally { saving = false }
                                        }
                                    }
                                )
                            }

                            Screen.ContentPreferences -> {
                                LaunchedEffect(Unit) {
                                    appSettings = runCatching { settingsApi.appSettings() }.getOrNull() ?: appSettings
                                }
                                ContentPreferencesScreen(
                                    settings = appSettings,
                                    onBack = { screen = Screen.Settings },
                                    onUpdate = { updated ->
                                        appSettings = updated
                                        lifecycleScope.launch { runCatching { settingsApi.updateAppSettings(updated) } }
                                    }
                                )
                            }

                            Screen.TimeManagement -> {
                                LaunchedEffect(Unit) {
                                    appSettings = runCatching { settingsApi.appSettings() }.getOrNull()?.also {
                                        com.telefam.usage.UsageTracker.applySettings(it.dailyLimitMinutes, it.breakReminderMinutes, it.quietModeEnabled)
                                    } ?: appSettings
                                }
                                val todayMinutes by com.telefam.usage.UsageTracker.todayMinutes.collectAsState()
                                TimeManagementScreen(
                                    settings = appSettings,
                                    todayMinutes = todayMinutes,
                                    weeklyMinutes = com.telefam.usage.UsageTracker.weeklyMinutes().map { it.second },
                                    onBack = { screen = Screen.Settings },
                                    onUpdate = { updated ->
                                        appSettings = updated
                                        com.telefam.usage.UsageTracker.applySettings(updated.dailyLimitMinutes, updated.breakReminderMinutes, updated.quietModeEnabled)
                                        lifecycleScope.launch { runCatching { settingsApi.updateAppSettings(updated) } }
                                    }
                                )
                            }

                            Screen.AccessibilitySettings -> {
                                LaunchedEffect(Unit) {
                                    appSettings = runCatching { settingsApi.appSettings() }.getOrNull() ?: appSettings
                                }
                                AccessibilityScreen(
                                    settings = appSettings,
                                    onBack = { screen = Screen.Settings },
                                    onUpdate = { updated ->
                                        appSettings = updated
                                        lifecycleScope.launch { runCatching { settingsApi.updateAppSettings(updated) } }
                                    }
                                )
                            }

                            Screen.ReportProblem -> {
                                var reportError by remember { mutableStateOf<String?>(null) }
                                var reportSubmitting by remember { mutableStateOf(false) }
                                var submittedReport by remember { mutableStateOf<com.telefam.data.api.ProblemReportDto?>(null) }
                                ReportProblemScreen(
                                    submitting = reportSubmitting,
                                    submitted = submittedReport,
                                    error = reportError,
                                    onBack = { screen = Screen.Settings },
                                    onSubmit = { category, description ->
                                        reportError = null; reportSubmitting = true
                                        lifecycleScope.launch {
                                            try {
                                                val res = settingsApi.submitProblemReport(
                                                    com.telefam.data.api.ProblemReportRequest(category, description)
                                                )
                                                if (res.status.value in 200..299) {
                                                    submittedReport = res.body()
                                                } else reportError = "Couldn't send the report. Try again."
                                            } catch (e: Exception) {
                                                reportError = "Couldn't send the report. Check your connection and try again."
                                            } finally { reportSubmitting = false }
                                        }
                                    }
                                )
                            }

                            Screen.SafetyCenter -> SafetyCenterScreen(
                                onBack = { screen = Screen.Settings },
                                onOpenPrivacy = { refreshPrivacy(); screen = Screen.Privacy },
                                onOpenSecurity = { screen = Screen.SettingsSecurity },
                                onOpenBlocked = {
                                    lifecycleScope.launch {
                                        try {
                                            val list: List<com.telefam.data.api.BlockedUserDto> = privacyApi.listBlocked().body()
                                            blocked = list.map { BlockedUserItem(it.userId, it.name ?: "Unknown") }
                                        } catch (e: Exception) { }
                                    }
                                    screen = Screen.Blocked
                                },
                                onOpenTimeManagement = { screen = Screen.TimeManagement },
                                onOpenReportProblem = { screen = Screen.ReportProblem }
                            )

                            Screen.HelpSupport -> {
                                var myReports by remember { mutableStateOf(listOf<com.telefam.data.api.ProblemReportDto>()) }
                                LaunchedEffect(Unit) {
                                    myReports = runCatching { settingsApi.myProblemReports() }.getOrDefault(emptyList())
                                }
                                HelpSupportScreen(
                                    myReports = myReports,
                                    onBack = { screen = Screen.Settings },
                                    onContactSupport = { screen = Screen.ReportProblem }
                                )
                            }

                            Screen.About -> AboutScreen(
                                onBack = { screen = Screen.Settings },
                                onOpenTerms = { screen = Screen.Terms(screen) },
                                onOpenLicenses = { screen = Screen.Terms(screen) }
                            )
                        }

                        // Sponsored campaign link (Get Sales) opens in the in-app browser,
                        // above whatever screen is showing.
                        sponsoredLinkUrl?.let { url ->
                            Box(Modifier.fillMaxSize()) {
                                com.telefam.chat.InAppWebView(
                                    url = url,
                                    modifier = Modifier.fillMaxSize(),
                                    onClose = { sponsoredLinkUrl = null }
                                )
                            }
                        }

                        // --- Call overlay: renders above every screen (chat included) ---
                        val callState by callController.state.collectAsState()
                        when (callState.phase) {
                            com.telefam.calls.CallPhase.INCOMING_RINGING -> {
                                IncomingCallScreen(
                                    state = callState,
                                    onAccept = {
                                        withCallPermissions(video = callState.callType == com.telefam.calls.CallType.VIDEO) {
                                            callController.acceptIncoming()
                                        }
                                    },
                                    onDecline = { callController.rejectIncoming() }
                                )
                            }
                            com.telefam.calls.CallPhase.OUTGOING_RINGING,
                            com.telefam.calls.CallPhase.CONNECTING,
                            com.telefam.calls.CallPhase.ACTIVE -> {
                                CallScreen(
                                    state = callState,
                                    engine = callController.currentEngine(),
                                    onHangUp = { callController.hangUp() },
                                    onToggleMic = { callController.toggleMic() },
                                    onToggleCamera = { callController.toggleCamera() },
                                    onFlipCamera = { callController.flipCamera() },
                                    onToggleScreenShare = { callController.toggleScreenShare() },
                                    onToggleSpeaker = { callController.toggleSpeaker() }
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
