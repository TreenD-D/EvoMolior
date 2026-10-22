package com.achulkov.evomolior.core.source

import java.net.IDN

/**
 * Why a URL-shaped span in a report cannot be used as a link.
 */
enum class UrlDefect {
    /** Contains an invisible, format, control, or non-ASCII space character (e.g. U+200B). */
    INVISIBLE_CHARACTER,

    /** The host is empty, has invalid characters, or is not a convertible internationalized name. */
    INVALID_HOST,
}

/**
 * One URL-shaped span found in report text.
 *
 * @param text The span as written, after trailing prose punctuation is removed.
 * @param line One-based line number in the scanned text.
 * @param defect Why the span is unusable, or null when it is a well-formed public URL.
 */
data class UrlCandidate(val text: String, val line: Int, val defect: UrlDefect?)

/**
 * Shared detection of usable public URLs in model-authored reports.
 *
 * Java's `\s` only matches ASCII whitespace, so a URL regex happily absorbs zero-width spaces and
 * other invisible characters. The October 2026 synthesis report published a Sentinel-2 link whose
 * host contained two U+200B characters; it still counted toward the contract's public-URL minimum.
 * `IDN.toASCII` alone is not a safe validator here because nameprep silently deletes U+200B.
 */
object PublicUrlHygiene {
    private val candidate = Regex("""https?://[^\s"'<>),;\]]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PROSE_PUNCTUATION = ".,:;!?*_`'\""
    private val asciiHostLabel = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?""")

    /**
     * Finds every URL-shaped span in [text] and classifies it.
     *
     * @param text Report text.
     * @return Candidates in document order, duplicates included.
     */
    fun scan(text: String): List<UrlCandidate> =
        text.lineSequence().flatMapIndexed { index, line ->
            candidate.findAll(line).mapNotNull { match ->
                val trimmed = match.value.trimEnd { it in TRAILING_PROSE_PUNCTUATION }
                trimmed.takeIf { it.length > "http://".length }
                    ?.let { UrlCandidate(it, index + 1, defectOf(it)) }
            }
        }.toList()

    /**
     * Distinct well-formed public URLs in [text]; defective spans never count.
     *
     * @param text Report text.
     */
    fun wellFormedUrls(text: String): Set<String> =
        scan(text).filter { it.defect == null }.mapTo(linkedSetOf()) { it.text }

    /**
     * Renders [url] with every invisible or non-ASCII character escaped as `U+XXXX`, so diagnostics
     * show exactly what a reader cannot see.
     *
     * @param url Span to render.
     */
    fun escapeForDiagnostics(url: String): String = buildString {
        url.codePoints().forEach { codePoint ->
            if (codePoint in 0x21..0x7E) appendCodePoint(codePoint) else append("<U+%04X>".format(codePoint))
        }
    }

    private fun defectOf(url: String): UrlDefect? {
        if (url.codePoints().anyMatch(::isInvisible)) return UrlDefect.INVISIBLE_CHARACTER
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').let { hostAndPort ->
            if (hostAndPort.startsWith("[")) hostAndPort.substringBefore(']') + "]" else hostAndPort.substringBefore(':')
        }
        if (host.isEmpty()) return UrlDefect.INVALID_HOST
        if (host.startsWith("[") && host.endsWith("]")) return null
        val asciiHost = if (host.all { it.code < 0x80 }) {
            host
        } else {
            runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrNull() ?: return UrlDefect.INVALID_HOST
        }
        val labels = asciiHost.trimEnd('.').split('.')
        return if (labels.all { it.isNotEmpty() && asciiHostLabel.matches(it) }) null else UrlDefect.INVALID_HOST
    }

    private fun isInvisible(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.FORMAT.toInt(),
        Character.CONTROL.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.PRIVATE_USE.toInt() -> true
        else -> false
    }
}
