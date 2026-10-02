package com.telefam.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File

actual class EntityActions(private val context: Context) {
    actual fun composeEmail(email: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    actual fun callNumber(phone: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    actual fun saveContact(phone: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_INSERT).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    actual fun openFile(path: String, mimeType: String?, displayName: String?): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val base = runCatching { File(AppFiles.baseDir).canonicalFile }.getOrNull() ?: return false
        // Never hand out anything outside the app's own media directory (path traversal / stray paths).
        if (!file.isFile || !file.path.startsWith(base.path + File.separator)) return false

        val extension = (displayName ?: file.name).substringAfterLast('.', "").lowercase()
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        // A received file must never be able to launch the package installer.
        if (mime == "application/vnd.android.package-archive" || extension == "apk") return false

        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull() ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    actual fun shareText(text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    null
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
actual fun rememberEntityActions(): EntityActions {
    val context = LocalContext.current.applicationContext
    return remember { EntityActions(context) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun InAppWebView(
    url: String,
    modifier: Modifier,
    onClose: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNavState: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    controls: (WebViewControls) -> Unit
) {
    // http(s) only — anything else is rejected silently and closes the sheet.
    val safeUrl = remember(url) { sanitizeForWeb(url) }
    if (safeUrl == null) {
        DisposableEffect(Unit) { onClose(); onDispose { } }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Block dangerous schemes from ever loading.
                        val u = request.url?.toString() ?: return true
                        return if (sanitizeForWeb(u) == null) true else false
                    }

                    override fun onPageFinished(view: WebView, u: String?) {
                        onTitleChange(view.title ?: u ?: "")
                        onNavState(view.canGoBack(), view.canGoForward())
                    }
                }
                controls(
                    WebViewControls(
                        goBack = { if (canGoBack()) goBack() },
                        goForward = { if (canGoForward()) goForward() },
                        reload = { reload() }
                    )
                )
                loadUrl(safeUrl)
            }
        }
    )
}

private fun sanitizeForWeb(url: String): String? =
    MessageParser.sanitizeUrl(url)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
