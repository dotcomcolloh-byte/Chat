@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.telefam.chat.InAppWebView
import com.telefam.chat.WebViewControls
import com.telefam.data.api.DirectoryUserDto
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/**
 * Telefam's in-app browser: tapped links open here instead of leaving the app.
 * Back / forward / refresh / close; only http(s) pages ever load.
 */
@Composable
fun InAppBrowserDialog(
    url: String,
    accent: Color,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf<WebViewControls?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Toolbar
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.browser_close), tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        if (title.isBlank()) stringResource(Res.string.browser_loading) else title,
                        fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        url, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { controls?.goBack() }, enabled = canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.browser_back),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (canGoBack) 1f else 0.3f)
                    )
                }
                IconButton(onClick = { controls?.goForward() }, enabled = canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(Res.string.browser_forward),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (canGoForward) 1f else 0.3f)
                    )
                }
                IconButton(onClick = { controls?.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.browser_refresh), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            InAppWebView(
                url = url,
                modifier = Modifier.fillMaxSize(),
                onClose = onClose,
                onTitleChange = { title = it },
                onNavState = { b, f -> canGoBack = b; canGoForward = f },
                controls = { controls = it }
            )
        }
    }
}

/**
 * Action sheet for a tapped phone number: Call / Copy / Save to contacts.
 */
@Composable
fun PhoneActionsSheet(
    phone: String,
    accent: Color,
    onCall: () -> Unit,
    onCopy: () -> Unit,
    onSaveContact: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                phone, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            EntityActionRow(stringResource(Res.string.action_call), accent, onClick = { onDismiss(); onCall() })
            EntityActionRow(stringResource(Res.string.action_copy), accent, onClick = { onDismiss(); onCopy() })
            EntityActionRow(stringResource(Res.string.action_save_contact), accent, onClick = { onDismiss(); onSaveContact() })
        }
    }
}

/**
 * Action sheet for a tapped email: compose / copy / share.
 */
@Composable
fun EmailActionsSheet(
    email: String,
    accent: Color,
    onCompose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                email, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            EntityActionRow(stringResource(Res.string.action_send_email), accent, onClick = { onDismiss(); onCompose() })
            EntityActionRow(stringResource(Res.string.action_copy), accent, onClick = { onDismiss(); onCopy() })
            EntityActionRow(stringResource(Res.string.action_share), accent, onClick = { onDismiss(); onShare() })
        }
    }
}

private sealed interface MentionState {
    data object Loading : MentionState
    data class Found(val user: DirectoryUserDto) : MentionState
    data object NotFound : MentionState
    data object Failed : MentionState
}

/**
 * Sheet for a tapped @username: looks the user up in the Telefam directory and offers to message them.
 * Network errors are reported as such (not as "no such user").
 */
@Composable
fun MentionProfileSheet(
    username: String,
    accent: Color,
    resolve: suspend (String) -> DirectoryUserDto?,
    onMessage: (DirectoryUserDto) -> Unit,
    onDismiss: () -> Unit
) {
    var state by remember(username) { mutableStateOf<MentionState>(MentionState.Loading) }
    LaunchedEffect(username) {
        state = try {
            resolve(username)?.let { MentionState.Found(it) } ?: MentionState.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MentionState.Failed
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            when (val st = state) {
                MentionState.Loading -> MentionMessage(stringResource(Res.string.mention_loading))
                MentionState.NotFound -> MentionMessage(stringResource(Res.string.mention_not_found, username))
                MentionState.Failed -> MentionMessage(stringResource(Res.string.mention_load_failed))
                is MentionState.Found -> {
                    val name = st.user.fullName ?: st.user.username ?: username
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(name.take(1).uppercase(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = accent)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                name, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text("@${st.user.username ?: username}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    EntityActionRow(stringResource(Res.string.mention_send_message), accent, onClick = { onMessage(st.user) })
                }
            }
        }
    }
}

@Composable
private fun MentionMessage(text: String) {
    Text(
        text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)
    )
}

@Composable
private fun EntityActionRow(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
