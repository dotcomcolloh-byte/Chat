package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.telefam.chat.EntityType
import com.telefam.chat.MessageEntity
import com.telefam.chat.MessageParser
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Telefam text bubble: smart entity detection (links, emails, phones, OTPs, mentions,
 * hashtags), optional message titles, pipe tables (horizontally scrollable when wide),
 * inline `code` formatting, and premium typography. Sent/received styling stays distinct.
 */
@Composable
fun TextMessageBubble(
    text: String,
    timestamp: String,
    outgoing: Boolean,
    bubbleColor: Color,
    deliveryState: String,
    avatarUrl: String? = null,
    editedAt: Long? = null,
    readAvatarUrl: String? = null,
    /** Tapped smart entity (link/email/phone/mention/hashtag) — handled by the screen so it can open sheets/browser. */
    onEntityClick: ((MessageEntity) -> Unit)? = null,
    /** One-tap OTP copy. */
    onOtpCopy: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val parsed = remember(text) { MessageParser.parseCached(text) }
    // The card is only shown when copying is wired up; in that case the code is cut out of the title/body so it never renders twice.
    val otp = if (onOtpCopy != null) parsed.entities.firstOrNull { it.type == EntityType.OTP } else null
    val body = remember(parsed, otp) { MessageParser.bodyOutsideTables(parsed, otpShownAsCard = otp) }
    val title = remember(parsed, otp) { MessageParser.titleOutsideOtp(parsed, otp) }

    val contentColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val linkColor = if (outgoing) Color(0xFFCFE8FF) else MaterialTheme.colorScheme.primary

    Row(modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        if (!outgoing) {
            AvatarThumb(avatarUrl)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (outgoing) bubbleColor else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            // Optional title — visually distinct but never oversized.
            title?.let { MessageTitle(it, contentColor) }

            // Prominent OTP card (copy on tap), clearly not an ordinary number.
            if (otp != null && onOtpCopy != null) {
                OtpCard(otp.raw, outgoing, bubbleColor, onCopy = onOtpCopy)
                if (body.isNotBlank()) Spacer(Modifier.height(8.dp))
            }

            if (body.isNotBlank()) {
                if (onEntityClick != null) {
                    SmartMessageText(
                        text = body,
                        color = contentColor,
                        linkColor = linkColor,
                        onEntityClick = onEntityClick,
                        style = MessageTypography.body
                    )
                } else {
                    Text(body, color = contentColor, style = MessageTypography.body)
                }
            }

            parsed.tables.forEach { table ->
                Spacer(Modifier.height(8.dp))
                // Constrain to bubble width; the table scrolls horizontally inside.
                Box(Modifier.fillMaxWidth()) {
                    MessageTableView(table, outgoing, bubbleColor)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (editedAt != null) {
                    Text(
                        stringResource(Res.string.chat_edited), fontSize = 10.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = if (outgoing) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    timestamp, style = MessageTypography.subtitle,
                    color = if (outgoing) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (outgoing) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTicks(deliveryState, Color.White.copy(alpha = 0.85f), readAvatarUrl = readAvatarUrl)
                }
            }
        }
    }
}

@Composable
internal fun AvatarThumb(url: String?, size: Dp = 30.dp) {
    androidx.compose.foundation.layout.Box(Modifier.size(size).clip(CircleShape)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}
