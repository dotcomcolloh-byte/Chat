package com.telefam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.chat.*
import com.telefam.shared.generated.resources.Res
import com.telefam.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Telefam message typography — one scale, used by every text bubble.
 * Line height 1.35x, gentle letter spacing, readable at dynamic font sizes.
 */
object MessageTypography {
    val body: TextStyle
        @Composable get() = TextStyle(
            fontSize = 15.5.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.1.sp,
            textAlign = TextAlign.Start
        )

    val title: TextStyle
        @Composable get() = TextStyle(
            fontSize = 16.5.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp
        )

    val subtitle: TextStyle
        @Composable get() = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp
        )

    val code: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 19.sp
        )

    val tableCell: TextStyle
        @Composable get() = TextStyle(
            fontSize = 13.5.sp,
            lineHeight = 18.sp
        )

    val tableHeader: TextStyle
        @Composable get() = tableCell.copy(fontWeight = FontWeight.SemiBold)
}

private const val TAG_ENTITY = "entity"

/** Builds the annotated, entity-highlighted body. Entities keep the exact original characters — only styling + click tags are added. */
fun buildMessageAnnotatedString(
    text: String,
    entities: List<MessageEntity>,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    fun appendSegment(segment: String) {
        // Inline `code` formatting for backtick spans.
        var i = 0
        while (i < segment.length) {
            val open = segment.indexOf('`', i)
            if (open < 0) { append(segment.substring(i)); break }
            val close = segment.indexOf('`', open + 1)
            if (close < 0) { append(segment.substring(i)); break }
            append(segment.substring(i, open))
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 14.sp)) {
                append(segment.substring(open + 1, close))
            }
            i = close + 1
        }
    }
    for (e in entities) {
        if (e.start < cursor) continue
        appendSegment(text.substring(cursor, e.start))
        val style = when (e.type) {
            EntityType.URL, EntityType.EMAIL -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            EntityType.PHONE -> SpanStyle(color = linkColor)
            EntityType.OTP -> SpanStyle(color = linkColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            EntityType.MENTION, EntityType.HASHTAG -> SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)
        }
        pushStringAnnotation(TAG_ENTITY, "${e.type.name}:${e.start}:${e.end}")
        withStyle(style) { append(text.substring(e.start, e.end)) }
        pop()
        cursor = e.end
    }
    if (cursor < text.length) appendSegment(text.substring(cursor))
}

/**
 * Smart text body: detects URLs/emails/phones/OTPs/mentions/hashtags and makes them interactive
 * via proper spans — no overlapping clickable UI.
 */
@Composable
fun SmartMessageText(
    text: String,
    color: Color,
    linkColor: Color,
    onEntityClick: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MessageTypography.body
) {
    val parsed = remember(text) { MessageParser.parseCached(text) }
    val codeBg = color.copy(alpha = 0.08f)
    val annotated = remember(parsed, linkColor, codeBg) {
        buildMessageAnnotatedString(text, parsed.entities, linkColor, codeBg)
    }
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier.fillMaxWidth(),
        onClick = { offset ->
            annotated.getStringAnnotations(TAG_ENTITY, offset, offset).firstOrNull()?.let { ann ->
                val parts = ann.item.split(':')
                val start = parts.getOrNull(1)?.toIntOrNull() ?: return@let
                val end = parts.getOrNull(2)?.toIntOrNull() ?: return@let
                parsed.entities.firstOrNull { it.start == start && it.end == end }?.let(onEntityClick)
            }
        }
    )
}

/** Prominent OTP chip shown above the body when a verification code is detected. One tap = copy. */
@Composable
fun OtpCard(
    code: String,
    outgoing: Boolean,
    accent: Color,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (outgoing) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val otpLabel = stringResource(Res.string.otp_detected_label)
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onCopy(code) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(otpLabel, style = MessageTypography.subtitle, color = fg.copy(alpha = 0.7f))
            Spacer(Modifier.height(2.dp))
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 3.sp,
                color = fg
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(Res.string.action_copy),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (outgoing) Color.White else accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (outgoing) Color.White.copy(alpha = 0.22f) else accent.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/**
 * Message table: readable minimum cell width; when wider than the screen it scrolls
 * horizontally instead of shrinking into unreadability or overlapping other content.
 */
@Composable
fun MessageTableView(
    table: MessageTable,
    outgoing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val borderColor = if (outgoing) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val headerBg = if (outgoing) Color.White.copy(alpha = 0.14f) else accent.copy(alpha = 0.10f)
    val fg = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState()) // wide tables scroll sideways — never clipped, never shrunk
            .padding(1.dp)
    ) {
        // Header
        Row(Modifier.background(headerBg)) {
            table.header.forEach { h ->
                TableCell(h, MessageTypography.tableHeader, fg, borderColor, width = 110.dp)
            }
        }
        table.rows.forEachIndexed { idx, row ->
            Row {
                table.header.indices.forEach { c ->
                    TableCell(row.getOrNull(c) ?: "", MessageTypography.tableCell, fg, borderColor, width = 110.dp, shaded = idx % 2 == 1 && !outgoing)
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, style: TextStyle, fg: Color, borderColor: Color, width: androidx.compose.ui.unit.Dp, shaded: Boolean = false) {
    Box(
        Modifier
            .width(width)
            .background(if (shaded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(text, style = style, color = fg, maxLines = 6)
    }
    Box(Modifier.width(width).height(0.dp)) // keeps layout pass consistent
}

/** Optional message title: strong but restrained typography, balanced spacing, theme-aware. */
@Composable
fun MessageTitle(title: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MessageTypography.title,
        color = color,
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp)
    )
}
