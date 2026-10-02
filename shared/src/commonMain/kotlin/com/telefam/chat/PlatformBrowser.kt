package com.telefam.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform hooks for detected message entities. All actions use platform APIs; permissions are only requested by the OS when needed. */
expect class EntityActions {
    /** Opens the system email composer addressed to [email]. */
    fun composeEmail(email: String)
    /** Opens the system dialer with [phone] pre-filled (no CALL permission needed for ACTION_DIAL-style intents). */
    fun callNumber(phone: String)
    /** Opens the platform "add contact" UI pre-filled with [phone]. */
    fun saveContact(phone: String)
    /** Shares [text] via the platform share sheet. */
    fun shareText(text: String)
    /**
     * Opens a file stored in Telefam's private storage with an app that can handle it (viewer / "open with" menu).
     * Only files inside [AppFiles.baseDir] are ever opened, and installable packages are refused.
     * Returns false when nothing could open the file so the UI can tell the user.
     */
    fun openFile(path: String, mimeType: String?, displayName: String?): Boolean
}

@Composable
expect fun rememberEntityActions(): EntityActions

/**
 * Full-screen in-app browser page with back/forward/refresh/close.
 * Implementations must only load http(s) URLs — the caller sanitizes via MessageParser.sanitizeUrl.
 */
@Composable
expect fun InAppWebView(
    url: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onTitleChange: (String) -> Unit = {},
    /** Called when navigation state changes so the toolbar can enable/disable back/forward. */
    onNavState: (canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _ -> },
    /** Imperative controls: set by the platform implementation. */
    controls: (WebViewControls) -> Unit = {}
)

class WebViewControls(
    val goBack: () -> Unit,
    val goForward: () -> Unit,
    val reload: () -> Unit
)
