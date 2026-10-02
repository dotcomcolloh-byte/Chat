package com.telefam.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.ui.theme.TelefamColors

/** The one primary CTA style in the whole app: filled red, 16dp corners, 52dp tall, bold 16sp label. */
@Composable
fun TelefamPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TelefamColors.PrimaryRed,
            disabledContainerColor = TelefamColors.PrimaryRed.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.height(52.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = TelefamColors.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            leadingIcon?.let { it(); Spacer(Modifier.width(10.dp)) }
            Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TelefamColors.White)
            trailingIcon?.let { Spacer(Modifier.width(10.dp)); it() }
        }
    }
}

/** The one secondary/outline style: Decline, Unblock, Unarchive — 12dp corners, theme-aware outline, no fill. */
@Composable
fun TelefamOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(52.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
