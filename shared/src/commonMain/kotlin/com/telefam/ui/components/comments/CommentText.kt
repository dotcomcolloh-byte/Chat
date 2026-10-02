package com.telefam.ui.components.comments

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** One parsed token of a comment body. */
private sealed class Token {
    data class Text(val text: String) : Token()
    data class Url(val text: String) : Token()
    data class Mention(val text: String, val username: String) : Token()
    data class Hashtag(val text: String) : Token()
}

private val URL_REGEX = Regex("""(https?://[^\s]+|www\.[^\s]+)""")
private val MENTION_REGEX = Regex("""@([A-Za-z0-9_.]{2,32})""")
private val HASHTAG_REGEX = Regex("""#([\p{L}\p{N}_]{1,64})""")

private fun tokenize(text: String): List<Token> {
    data class Span(val start: Int, val end: Int, val token: Token)
    val spans = mutableListOf<Span>()
    URL_REGEX.findAll(text).forEach { m -> spans += Span(m.range.first, m.range.last + 1, Token.Url(m.value)) }
    MENTION_REGEX.findAll(text).forEach { m ->
        if (spans.none { m.range.first < it.end && m.range.last + 1 > it.start })
            spans += Span(m.range.first, m.range.last + 1, Token.Mention(m.value, m.groupValues[1]))
    }
    HASHTAG_REGEX.findAll(text).forEach { m ->
        if (spans.none { m.range.first < it.end && m.range.last + 1 > it.start })
            spans += Span(m.range.first, m.range.last + 1, Token.Hashtag(m.value))
    }
    spans.sortBy { it.start }
    val out = mutableListOf<Token>()
    var idx = 0
    for (s in spans) {
        if (s.start > idx) out += Token.Text(text.substring(idx, s.start))
        out += s.token
        idx = s.end
    }
    if (idx < text.length) out += Token.Text(text.substring(idx))
    return out
}

/**
 * Comment body with autolinking: URLs, @mentions and #hashtags are styled in the
 * brand colour and tappable; taps are routed to the matching handler.
 */
@Composable
fun AutoLinkText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    onUrlClick: (String) -> Unit = {},
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    val annotated = buildAnnotatedString {
        for (token in tokenize(text)) {
            when (token) {
                is Token.Text -> withStyle(SpanStyle(color = color)) { append(token.text) }
                is Token.Url -> {
                    pushStringAnnotation("url", token.text)
                    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    pop()
                }
                is Token.Mention -> {
                    pushStringAnnotation("mention", token.username)
                    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    pop()
                }
                is Token.Hashtag -> {
                    pushStringAnnotation("hashtag", token.text.removePrefix("#"))
                    withStyle(SpanStyle(color = linkColor)) { append(token.text) }
                    pop()
                }
            }
        }
    }
    ClickableText(text = annotated, style = style) { offset ->
        annotated.getStringAnnotations(offset, offset).firstOrNull()?.let { ann ->
            when (ann.tag) {
                "url" -> onUrlClick(if (ann.item.startsWith("http")) ann.item else "https://${ann.item}")
                "mention" -> onMentionClick(ann.item)
                "hashtag" -> onHashtagClick(ann.item)
            }
        }
    }
}

/** Collapsible long text: renders up to [collapsedMaxChars] with "Read more" / "Read less". */
@Composable
fun ExpandableAutoLinkText(
    text: String,
    style: TextStyle,
    color: Color,
    collapsedMaxChars: Int = 180,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUrlClick: (String) -> Unit = {},
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    val isLong = text.length > collapsedMaxChars
    val shown = if (expanded || !isLong) text else text.take(collapsedMaxChars).trimEnd() + "…"
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        for (token in tokenize(shown)) {
            when (token) {
                is Token.Text -> withStyle(SpanStyle(color = color)) { append(token.text) }
                is Token.Url -> {
                    pushStringAnnotation("url", token.text)
                    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    pop()
                }
                is Token.Mention -> {
                    pushStringAnnotation("mention", token.username)
                    withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    pop()
                }
                is Token.Hashtag -> {
                    pushStringAnnotation("hashtag", token.text.removePrefix("#"))
                    withStyle(SpanStyle(color = linkColor)) { append(token.text) }
                    pop()
                }
            }
        }
        if (isLong) {
            pushStringAnnotation("toggle", "toggle")
            withStyle(SpanStyle(color = color.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)) {
                append(if (expanded) "  Read less" else "  Read more")
            }
            pop()
        }
    }
    ClickableText(text = annotated, style = style) { offset ->
        annotated.getStringAnnotations(offset, offset).firstOrNull()?.let { ann ->
            when (ann.tag) {
                "url" -> onUrlClick(if (ann.item.startsWith("http")) ann.item else "https://${ann.item}")
                "mention" -> onMentionClick(ann.item)
                "hashtag" -> onHashtagClick(ann.item)
                "toggle" -> onToggle()
            }
        }
    }
}

/** Relative timestamp like the reference ("2h", "3h"). */
fun relativeTime(iso: String): String {
    val created = runCatching { kotlinx.datetime.LocalDateTime.parse(iso.take(19)) }.getOrNull() ?: return ""
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val createdInstant = created.toInstant(zone)
    val seconds = (kotlinx.datetime.Clock.System.now() - createdInstant).inWholeSeconds
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        else -> "${minutes / (60 * 24 * 7)}w"
    }
}

fun formatCommentCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    n >= 1_000 -> "%,d".format(n)
    else -> n.toString()
}
