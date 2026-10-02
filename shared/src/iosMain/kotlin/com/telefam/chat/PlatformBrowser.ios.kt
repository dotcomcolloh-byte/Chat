package com.telefam.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ContactsUI.CNContactViewController
import platform.Contacts.CNMutableContact
import platform.Contacts.CNPhoneNumber
import platform.Contacts.CNLabeledValue
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.MessageUI.MFMailComposeViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private fun topViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    val window = scenes.mapNotNull { it as? platform.UIKit.UIWindowScene }
        .firstOrNull()?.windows?.mapNotNull { it as? platform.UIKit.UIWindow }
        ?.firstOrNull { it.isKeyWindow() }
    var vc = window?.rootViewController
    while (vc?.presentedViewController != null) vc = vc?.presentedViewController
    return vc
}

actual class EntityActions {
    actual fun composeEmail(email: String) {
        // Prefer the mail composer; fall back to mailto: which opens the default mail app.
        val vc = topViewController()
        if (vc != null && MFMailComposeViewController.canSendMail()) {
            val mail = MFMailComposeViewController()
            mail.setToRecipients(listOf(email))
            vc.presentViewController(mail, animated = true, completion = null)
        } else {
            NSURL.URLWithString("mailto:$email")?.let { UIApplication.sharedApplication.openURL(it) }
        }
    }

    actual fun callNumber(phone: String) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        NSURL.URLWithString("tel:$digits")?.let { UIApplication.sharedApplication.openURL(it) }
    }

    actual fun saveContact(phone: String) {
        val contact = CNMutableContact()
        contact.phoneNumbers = listOf(
            CNLabeledValue.labeledValueWithLabel(
                label = null,
                value = CNPhoneNumber.phoneNumberWithStringValue(phone.filter { it.isDigit() || it == '+' })
            )
        )
        val vc = topViewController() ?: return
        vc.presentViewController(CNContactViewController.viewControllerForNewContact(contact), animated = true, completion = null)
    }

    actual fun shareText(text: String) {
        val vc = topViewController() ?: return
        vc.presentViewController(UIActivityViewController(listOf(text), null), animated = true, completion = null)
    }

    // Kept as fields: UIKit only holds weak references to a document controller and its delegate.
    private var docController: UIDocumentInteractionController? = null
    private var docDelegate: NSObject? = null

    actual fun openFile(path: String, mimeType: String?, displayName: String?): Boolean {
        // Only files inside Telefam's own media directory, and never installable/executable bundles.
        if (AppFiles.baseDir.isEmpty() || !path.startsWith(AppFiles.baseDir + "/")) return false
        if (path.contains("/../")) return false
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return false
        val vc = topViewController() ?: return false
        val url = NSURL.fileURLWithPath(path)

        val delegate = object : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
            override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController = vc
        }
        val controller = UIDocumentInteractionController.interactionControllerWithURL(url)
        controller.delegate = delegate
        controller.name = displayName
        docDelegate = delegate
        docController = controller
        if (!controller.presentPreviewAnimated(true)) {
            // No in-app preview for this type: fall back to the system sheet (Open in..., Save to Files).
            vc.presentViewController(UIActivityViewController(listOf(url), null), animated = true, completion = null)
        }
        return true
    }
}

@Composable
actual fun rememberEntityActions(): EntityActions = remember { EntityActions() }

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun InAppWebView(
    url: String,
    modifier: Modifier,
    onClose: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNavState: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    controls: (WebViewControls) -> Unit
) {
    val safeUrl = MessageParser.sanitizeUrl(url)
    if (safeUrl == null) {
        onClose()
        return
    }
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                onTitleChange(webView.title ?: "")
                onNavState(webView.canGoBack, webView.canGoForward)
            }

            override fun webView(
                webView: WKWebView,
                decidePolicyForNavigationAction: WKNavigationAction,
                decisionHandler: (WKNavigationActionPolicy) -> Unit
            ) {
                val u = decidePolicyForNavigationAction.request.URL?.absoluteString
                // Only http(s) navigations are allowed.
                decisionHandler(
                    if (u != null && MessageParser.sanitizeUrl(u) != null) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
                    else WKNavigationActionPolicy.WKNavigationActionPolicyCancel
                )
            }
        }
    }
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply {
                navigationDelegate = delegate
                controls(
                    WebViewControls(
                        goBack = { if (canGoBack) goBack() },
                        goForward = { if (canGoForward) goForward() },
                        reload = { reload() }
                    )
                )
                NSURL.URLWithString(safeUrl)?.let { loadRequest(platform.Foundation.NSURLRequest(uRL = it)) }
            }
        }
    )
}
