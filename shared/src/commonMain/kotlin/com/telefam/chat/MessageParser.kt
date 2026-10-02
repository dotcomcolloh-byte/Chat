package com.telefam.chat

/**
 * Reusable smart message parser. Pure, platform-neutral and allocation-light.
 * Parsing happens entirely on-device — detected entities (OTPs included) are
 * never sent anywhere for parsing.
 *
 * Detection order matters: later detectors never claim ranges already taken,
 * so clickable regions never overlap.
 */

enum class EntityType { URL, EMAIL, PHONE, OTP, MENTION, HASHTAG }

data class MessageEntity(
    val type: EntityType,
    val start: Int,
    val end: Int,
    /** Exact substring from the original message — content is never altered. */
    val raw: String,
    /** Normalised value used for actions, e.g. full https URL or E.164-ish phone. */
    val normalized: String
)

data class ParsedMessage(
    val text: String,
    val entities: List<MessageEntity>,
    /** Markdown-style pipe tables extracted from the text. */
    val tables: List<MessageTable>,
    /** Title/body split when the message starts with a bold heading line ("**Title**" or "Title:"). */
    val title: String?,
    val bodyStart: Int
)

data class MessageTable(
    /** Line range in the source text [startLine, endLine) — these lines are rendered as a table, not text. */
    val startLine: Int,
    val endLine: Int,
    val header: List<String>,
    val rows: List<List<String>>
)

object MessageParser {

    // --- Regexes ---------------------------------------------------------------
    // URLs are detected structurally (no hand-maintained TLD list):
    //  * EXPLICIT: anything with an http(s):// scheme or a "www." prefix. Any syntactically valid host qualifies,
    //    so new/rare TLDs (.photography, .xn--..., .technology ...) link as soon as the user writes them with a scheme/www.
    //  * BARE: "host.tld[:port][/path]" without scheme. Because plain prose such as "ok.thanks" must not become a
    //    link, a bare host must end in a two-letter label (every ccTLD), an IDN/punycode TLD, or one of the
    //    widely used generic TLDs in COMMON_BARE_TLDS. See [validateBare].
    private const val URL_STOP = "\\s<>\"'“”‘’«»"
    private val EXPLICIT_URL_RE = Regex(
        "(?i)(?<![\\p{L}\\p{N}_@/])(?:https?://|www\\.)[^$URL_STOP]+"
    )
    private val BARE_DOMAIN_RE = Regex(
        "(?i)(?<![\\p{L}\\p{N}_@./-])" +
            "(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+" +
            "(?:xn--[a-z0-9-]{1,59}|\\p{L}{2,63})" +
            "(?::\\d{2,5})?(?:[/?#][^$URL_STOP]*)?" +
            "(?![\\p{L}\\p{N}_@-])"
    )
    private val EMAIL_RE = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
    // International or local phone: optional +country code, 7-15 significant digits, common separators.
    private val PHONE_RE = Regex("""(?x)(?<![\w@])(\+?[0-9][0-9\s().-]{6,18}[0-9])(?![\w@])""")
    // OTP: 4-8 digits, only when the message context mentions a code/verification.
    private val OTP_CONTEXT_RE = Regex(
        """(?i)\b(otp|code|verification|verify|passcode|pin|token|one[- ]?time|2fa|authenticat)"""
    )
    private val OTP_RE = Regex("""(?<![\w@.])(\d{4,8})(?![\w@.])""")
    private val MENTION_RE = Regex("""(?<![\w.])@([A-Za-z0-9_]{2,32})\b""")
    private val HASHTAG_RE = Regex("""(?<![\w&])#([\p{L}\p{N}_]{2,64})\b""")

    /** Characters that are never part of a URL when they end it ("see https://x.com/a." / "(https://x.com)"). */
    private const val TRAILING_PUNCT = ".,;:!?'\"]}…"

    /** Generic TLDs accepted for scheme-less hosts ("example.com"). Explicit URLs (scheme or www.) accept any valid TLD. */
    private val COMMON_BARE_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name", "pro", "mobi", "asia", "tel",
        "travel", "jobs", "museum", "coop", "aero", "cat", "app", "dev", "page", "blog", "shop", "store", "online",
        "site", "tech", "xyz", "top", "club", "cloud", "news", "live", "life", "world", "today", "space", "website",
        "link", "email", "agency", "digital", "network", "media", "studio", "design", "art", "social", "chat",
        "game", "games", "fun", "one", "vip", "icu", "wiki", "love", "team", "tips", "zone", "work", "works",
        "click", "download", "services", "solutions", "systems", "group", "company", "center", "community",
        "support", "help", "guru", "academy", "school", "education", "university", "health", "care", "finance",
        "money", "bank", "capital", "fund", "global", "international", "city", "press", "video", "photo",
        "photos", "music", "radio", "events", "guide", "hosting", "domains", "ltd", "inc", "llc"
    )

    /** Two-letter endings that are overwhelmingly file names / code in chat rather than country domains. */
    private val NON_DOMAIN_TWO_LETTER = setOf(
        "js", "ts", "kt", "py", "rb", "sh", "md", "rs", "pl", "cs", "vb", "gz", "gd", "tx", "ex", "eg", "ie"
    )

    /** Conservative URL validation: only http(s) is ever openable. */
    fun sanitizeUrl(raw: String): String? {
        val withScheme = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("www.", true) -> "https://$raw"
            else -> "https://$raw"
        }
        return try {
            val lower = withScheme.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            if (host.isBlank() || !host.contains('.') || host.any { it.isWhitespace() }) null
            else withScheme
        } catch (e: Throwable) { null }
    }

    fun isPlausiblePhone(raw: String): Boolean {
        val digits = raw.count { it.isDigit() }
        if (digits < 7 || digits > 15) return false
        // Reject obvious non-phones: dates (2024-01-31), decimals/prices (12.99), bare short codes.
        val compact = raw.trim()
        if (compact.matches(Regex("""\d{1,4}[-./]\d{1,2}([-./]\d{1,4})?"""))) return false
        if (compact.matches(Regex("""\d+\.\d+"""))) return false
        if (isIPv4(compact)) return false
        if (!raw.startsWith("+") && digits < 9) return false // local numbers need at least 9 digits
        return true
    }

    // --- URL validation -------------------------------------------------------------------------

    private class UrlHit(val end: Int, val normalized: String)

    private fun isIPv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
    }

    private fun isHostLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 63) return false
        if (label.first() == '-' || label.last() == '-') return false
        return label.all { it.isLetterOrDigit() || it == '-' }
    }

    private fun isTld(tld: String): Boolean {
        if (tld.length > 63) return false
        if (tld.startsWith("xn--", ignoreCase = true)) return tld.length > 4 && tld.drop(4).all { it.isLetterOrDigit() || it == '-' }
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    /** RFC-style host check: dot-separated valid labels ending in a valid TLD (or an IPv4 / localhost when allowed). */
    private fun isValidHost(host: String, allowIpAndLocalhost: Boolean): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (allowIpAndLocalhost && (host.equals("localhost", ignoreCase = true) || isIPv4(host))) return true
        val labels = host.split('.')
        if (labels.size < 2 || !labels.all(::isHostLabel)) return false
        return isTld(labels.last())
    }

    /** Drops trailing punctuation, and a closing bracket only when it has no opener inside the URL (keeps "…/Foo_(bar)"). */
    private fun trimUrlEnd(text: String, start: Int, endExclusive: Int): Int {
        var e = endExclusive
        while (e > start) {
            val c = text[e - 1]
            val drop = when {
                c in TRAILING_PUNCT -> true
                c == ')' -> countIn(text, start, e, '(') < countIn(text, start, e, ')')
                else -> false
            }
            if (!drop) break
            e--
        }
        return e
    }

    private fun countIn(text: String, start: Int, end: Int, ch: Char): Int {
        var n = 0
        for (i in start until end) if (text[i] == ch) n++
        return n
    }

    /** Splits "host[:port]" out of the authority part and validates both; null when invalid. */
    private fun validAuthority(authority: String, hasScheme: Boolean): Boolean {
        if (authority.isEmpty() || '@' in authority || authority.startsWith("[")) return false // no userinfo/IPv6 (spoofing risk)
        val host: String
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            val port = authority.substring(colon + 1)
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() !in 1..65535) return false
            host = authority.substring(0, colon)
        } else host = authority
        return isValidHost(host, allowIpAndLocalhost = hasScheme || colon >= 0)
    }

    private fun authorityOf(urlBody: String): String {
        var end = urlBody.length
        for (ch in charArrayOf('/', '?', '#')) {
            val i = urlBody.indexOf(ch)
            if (i in 0 until end) end = i
        }
        return urlBody.substring(0, end)
    }

    /** http(s):// or www. candidate -> normalized https/http URL, or null when the host is not valid. */
    private fun validateExplicit(text: String, start: Int, endExclusive: Int): UrlHit? {
        val end = trimUrlEnd(text, start, endExclusive)
        if (end <= start) return null
        val raw = text.substring(start, end)
        val hasScheme = raw.startsWith("http://", true) || raw.startsWith("https://", true)
        val body = if (hasScheme) raw.substringAfter("://") else raw
        if (!validAuthority(authorityOf(body), hasScheme)) return null
        if (!hasScheme && authorityOf(body).count { it == '.' } < 2) return null // "www.x" alone is not a host
        val normalized = sanitizeUrl(raw) ?: return null
        return UrlHit(end, normalized)
    }

    /** Scheme-less "host.tld[:port][/path]" candidate; stricter than explicit URLs to keep prose like "ok.thanks" plain. */
    private fun validateBare(text: String, start: Int, endExclusive: Int): UrlHit? {
        val end = trimUrlEnd(text, start, endExclusive)
        if (end <= start) return null
        val raw = text.substring(start, end)
        val authority = authorityOf(raw)
        if (!validAuthority(authority, hasScheme = false)) return null
        val host = if (':' in authority) authority.substringBeforeLast(':') else authority
        val tld = host.substringAfterLast('.')
        if (isIPv4(host)) return null // bare "192.168.1.1" is ambiguous with versions/phones; needs http(s)://
        // Mixed-case endings ("Thanks.Bye", "Hello.World") are sentence run-ons, not domains.
        if (tld != tld.lowercase() && tld != tld.uppercase()) return null
        val t = tld.lowercase()
        val accepted = t.startsWith("xn--") ||
            (t.length == 2 && t !in NON_DOMAIN_TWO_LETTER) ||
            t in COMMON_BARE_TLDS
        if (!accepted) return null
        val normalized = sanitizeUrl(raw) ?: return null
        return UrlHit(end, normalized)
    }

    /** Split "Title\nBody" — a title exists when the first line is short, non-empty, and followed by a blank line or bold-marked. */
    private fun splitTitle(lines: List<String>, tableLines: IntRange?): Pair<String?, Int> {
        if (lines.isEmpty()) return null to 0
        val first = lines[0]
        if (tableLines?.first == 0) return null to 0
        val bold = first.trim().let { it.length > 3 && it.startsWith("**") && it.endsWith("**") }
        val heading = first.trim().length in 2..70 && !first.any { it == '|' } &&
            (bold || (lines.size > 2 && lines[1].isBlank()) || (first.trim().endsWith(":") && first.trim().length > 3))
        return if (heading) {
            val title = first.trim().removePrefix("**").removeSuffix("**").removeSuffix(":").trim()
            var bodyStart = 1
            while (bodyStart < lines.size && lines[bodyStart].isBlank()) bodyStart++
            title to bodyStart
        } else null to 0
    }

    /** Markdown-style pipe tables: a header row with | separators, followed by >=1 data rows (an optional --- separator row is skipped). */
    private fun extractTables(lines: List<String>): List<MessageTable> {
        val tables = mutableListOf<MessageTable>()
        fun cells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        fun isSeparatorRow(line: String) =
            cells(line).isNotEmpty() && cells(line).all { it.matches(Regex(""":?-{2,}:?""")) }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.count { it == '|' } >= 1 && !line.trimStart().startsWith("#")) {
                val header = cells(line)
                if (header.size >= 2) {
                    var j = i + 1
                    if (j < lines.size && isSeparatorRow(lines[j])) j++
                    val rows = mutableListOf<List<String>>()
                    while (j < lines.size && lines[j].count { it == '|' } >= 1 && lines[j].isNotBlank()) {
                        val r = cells(lines[j])
                        if (r.size >= 2) rows.add(r)
                        j++
                    }
                    if (rows.isNotEmpty()) {
                        tables.add(MessageTable(i, j, header, rows))
                        i = j
                        continue
                    }
                }
            }
            i++
        }
        return tables
    }

    fun parse(text: String): ParsedMessage {
        if (text.isEmpty()) return ParsedMessage(text, emptyList(), emptyList(), null, 0)
        val lines = text.split('\n')
        val tables = extractTables(lines)
        val tableRanges = tables.map { it.startLine until it.endLine }

        // Compute char offsets per line so entities inside table rows can be excluded.
        val lineStarts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { lineStarts[i] = acc; acc += lines[i].length + 1 }
        fun inTable(start: Int, end: Int): Boolean {
            val lineIdx = lineStarts.indexOfLast { it <= start }.coerceAtLeast(0)
            return tableRanges.any { lineIdx in it }
        }

        val claimed = mutableListOf<Pair<Int, Int>>()
        val entities = mutableListOf<MessageEntity>()
        fun free(start: Int, end: Int): Boolean =
            !inTable(start, end) && claimed.none { it.first < end && start < it.second }
        fun claim(start: Int, end: Int, type: EntityType, raw: String, normalized: String) {
            entities += MessageEntity(type, start, end, raw, normalized)
            claimed += start to end
        }

        // 1) Explicit URLs (http(s):// or www.) — unambiguous, so they claim first. Anything inside them
        //    (an "@user" path segment, "?e=a@b.co") is therefore never split off as a mention/e-mail.
        for (m in EXPLICIT_URL_RE.findAll(text)) {
            val s = m.range.first
            if (!free(s, m.range.last + 1)) continue
            val hit = validateExplicit(text, s, m.range.last + 1) ?: continue
            if (!free(s, hit.end)) continue
            claim(s, hit.end, EntityType.URL, text.substring(s, hit.end), hit.normalized)
        }
        // 2) Emails — before bare domains, so "jane.co@x.com" is one e-mail and not "jane.co" + junk.
        for (m in EMAIL_RE.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (!free(s, e)) continue
            claim(s, e, EntityType.EMAIL, text.substring(s, e), text.substring(s, e))
        }
        // 2b) Scheme-less domains ("example.com/path", "shop.co.ke", "site.xn--p1ai")
        for (m in BARE_DOMAIN_RE.findAll(text)) {
            val s = m.range.first
            if (!free(s, m.range.last + 1)) continue
            val hit = validateBare(text, s, m.range.last + 1) ?: continue
            if (!free(s, hit.end)) continue
            claim(s, hit.end, EntityType.URL, text.substring(s, hit.end), hit.normalized)
        }
        // 3) Phone numbers
        for (m in PHONE_RE.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (!free(s, e)) continue
            val raw = text.substring(s, e).trim()
            if (!isPlausiblePhone(raw)) continue
            val normalized = "+" + raw.filter { it.isDigit() }.removePrefix("00")
            claim(s, e, EntityType.PHONE, text.substring(s, e), if (raw.startsWith("+")) normalized else raw.filter { it.isDigit() || it == '+' })
        }
        // 4) OTP — only when context mentions a code and the number is not already claimed.
        if (OTP_CONTEXT_RE.containsMatchIn(text)) {
            for (m in OTP_RE.findAll(text)) {
                val s = m.range.first; val e = m.range.last + 1
                if (!free(s, e)) continue
                // Never treat something that looks like a date/year/price fragment as an OTP.
                val before = text.getOrNull(s - 1); val after = text.getOrNull(e)
                if ((before != null && before in charArrayOf('.', ',', '/', '-', ':')) ||
                    (after != null && after in charArrayOf('.', ',', '/', '-', ':'))
                ) continue
                claim(s, e, EntityType.OTP, text.substring(s, e), text.substring(s, e))
            }
        }
        // 5) Mentions & hashtags
        for (m in MENTION_RE.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (free(s, e)) claim(s, e, EntityType.MENTION, text.substring(s, e), m.groupValues[1])
        }
        for (m in HASHTAG_RE.findAll(text)) {
            val s = m.range.first; val e = m.range.last + 1
            if (free(s, e)) claim(s, e, EntityType.HASHTAG, text.substring(s, e), m.groupValues[1])
        }

        entities.sortBy { it.start }
        val tableRange = tables.firstOrNull()?.let { it.startLine until it.endLine }
        val (title, bodyStart) = splitTitle(lines, tableRange)
        return ParsedMessage(text, entities, tables, title, bodyStart)
    }

    /** Collapses the gaps left behind after a span was cut out of a line ("code is  ." -> "code is."). */
    private fun tidyAfterRemoval(line: String): String =
        line.replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\\s+([.,;:!?])"), "\$1")
            .trim()

    /**
     * Body text with table lines removed (tables render as real tables).
     *
     * When [otpShownAsCard] is given, exactly that OTP span is cut out of the body because the dedicated OTP
     * card already renders the code above it (so the digits never appear twice). Any further numbers that
     * look like codes stay in the text, and lines without the removed span are returned byte-for-byte.
     */
    fun bodyOutsideTables(parsed: ParsedMessage, otpShownAsCard: MessageEntity? = null): String {
        val lines = parsed.text.split('\n')
        val skip = parsed.tables.flatMap { it.startLine until it.endLine }.toSet()

        var sourceOffset = 0
        val bodyLines = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val lineStart = sourceOffset
            val lineEnd = lineStart + line.length
            sourceOffset = lineEnd + 1
            if (i < parsed.bodyStart || i in skip) continue

            if (otpShownAsCard == null || otpShownAsCard.start < lineStart || otpShownAsCard.end > lineEnd) {
                bodyLines += line
                continue
            }
            val cut = line.removeRange(otpShownAsCard.start - lineStart, otpShownAsCard.end - lineStart)
            val tidy = tidyAfterRemoval(cut)
            // A line that only held the code (or the code plus punctuation) disappears entirely.
            if (tidy.none { it.isLetterOrDigit() }) continue
            bodyLines += tidy
        }
        return bodyLines.joinToString("\n").trim()
    }

    /** Title to display; drops the OTP when the code sits in the title line and is already shown on the card. */
    fun titleOutsideOtp(parsed: ParsedMessage, otpShownAsCard: MessageEntity?): String? {
        val title = parsed.title ?: return null
        if (otpShownAsCard == null) return title
        val firstLineEnd = parsed.text.indexOf('\n').let { if (it < 0) parsed.text.length else it }
        if (otpShownAsCard.start >= firstLineEnd) return title
        val cut = tidyAfterRemoval(title.replace(otpShownAsCard.raw, ""))
        return cut.takeIf { c -> c.any { it.isLetterOrDigit() } }
    }

    // --- Small LRU cache so recomposition of long chats never re-parses --------
    // (Plain LinkedHashMap: access-order constructors / removeEldestEntry are JVM-only and this file is common code.)
    private const val CACHE_MAX = 512
    private val cache = LinkedHashMap<String, ParsedMessage>()

    @Synchronized
    fun parseCached(text: String): ParsedMessage {
        cache.remove(text)?.let { cache[text] = it; return it }
        val parsed = parse(text)
        cache[text] = parsed
        if (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
        return parsed
    }
}
