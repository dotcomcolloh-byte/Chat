package com.telefam.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.menu_archived
import com.telefam.shared.generated.resources.menu_blocked
import com.telefam.shared.generated.resources.menu_linked_devices
import com.telefam.shared.generated.resources.menu_message_requests
import com.telefam.shared.generated.resources.menu_privacy
import com.telefam.shared.generated.resources.menu_settings
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPrivacyClick: () -> Unit,
    onMessageRequestsClick: () -> Unit,
    onArchivedClick: () -> Unit,
    onBlockedClick: () -> Unit,
    onLinkedDevicesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_privacy)) }, onClick = { onDismiss(); onPrivacyClick() })
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_linked_devices)) }, onClick = { onDismiss(); onLinkedDevicesClick() })
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_message_requests)) }, onClick = { onDismiss(); onMessageRequestsClick() })
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_archived)) }, onClick = { onDismiss(); onArchivedClick() })
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_blocked)) }, onClick = { onDismiss(); onBlockedClick() })
        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_settings)) }, onClick = { onDismiss(); onSettingsClick() })
    }
}
