package com.achulkov.evomolior.core.source

import kotlinx.serialization.Serializable

/**
 * Immutable source text available to an offline report audit.
 *
 * @param sourceName Original uploaded filename.
 * @param relativePath Immutable run-input path.
 * @param text Verified UTF-8 contents, or null when not safely readable.
 * @param unavailableReason Why exact source checking is unavailable.
 */
data class SourceReportInput(
    val sourceName: String,
    val relativePath: String,
    val text: String?,
    val unavailableReason: String? = null,
)

/**
 * A non-authoritative source observation, never an acceptance decision.
 *
 * @param code Stable diagnostic code.
 * @param reportLine One-based report line, or null for audit-wide limitations.
 * @param detail Human-readable evidence or limitation.
 */
@Serializable
data class SourceReportIssue(val code: String, val reportLine: Int?, val detail: String)

/**
 * Offline source-check results. No semantic truth, external-source completeness, or acceptance claim.
 *
 * @param issues Remaining findings, including unavailable verification.
 * @param checkedCitations Number of source spans resolved and range-checked.
 * @param checkedQuotations Number of explicitly delimited quotations compared to a cited span.
 */
@Serializable
data class SourceReportAudit(
    val issues: List<SourceReportIssue>,
    val checkedCitations: Int,
    val checkedQuotations: Int,
) {
    /** Renders a separate non-blocking report; findings do not rewrite the original artifact. */
    fun renderMarkdown(): String = buildString {
        appendLine("# Source-check side-report")
        appendLine()
        appendLine("Advisory only — this is not acceptance or proof that every claim is grounded.")
        appendLine("Checked citations: $checkedCitations; checked explicit quotations: $checkedQuotations.")
        appendLine()
        if (issues.isEmpty()) appendLine("No issues detected by the supported offline checks.")
        issues.forEach { issue ->
            appendLine("- **${issue.code}**${issue.reportLine?.let { " (report L$it)" }.orEmpty()}: ${issue.detail}")
        }
        appendLine()
        appendLine("Scope: immutable supplied text, filename:Lnumber[-Lnumber] citations and adjacent " +
            "explicit quotations. Paraphrase accuracy, unmarked quotations, external websites, and " +
            "unexecuted behavioral claims require independent review. No external sources were fetched.")
    }

    /** Bounded feedback for one correction pass in the existing writer lifecycle and allowance. */
    fun correctionPrompt(): String? = issues.filter { issue ->
        issue.code in setOf(
            "QUOTATION_MISMATCH", "SOURCE_RANGE_INVALID", "UNKNOWN_SOURCE", "AMBIGUOUS_SOURCE", "MALFORMED_URL"
        ) ||
            (issue.code == "NO_VERIFIABLE_CITATIONS" &&
                issues.none { it.code == "EXTERNAL_SOURCES_NOT_CHECKED" })
    }.takeIf { it.isNotEmpty() }?.let { actionable ->
        buildString {
            appendLine("NON-BLOCKING SOURCE CHECK: the DOC was stored successfully.")
            appendLine("Make one correction pass within your existing time and credit allowance. " +
                "Read the cited immutable sources with includeLineNumbers=true; preserve exact quote bytes " +
                "including dollar signs and shell quoting. Submit one corrected DOC if possible, then finish. " +
                "Do not invent sources, rewrite facts to silence a diagnostic, or claim checks you did not run. " +
                "If unresolved, retain the useful report; remaining issues go in a separate side-report.")
            actionable.take(12).forEach { issue ->
                appendLine("- ${issue.code}${issue.reportLine?.let { " at report L$it" }.orEmpty()}: ${issue.detail}")
            }
            if (actionable.size > 12) appendLine("- Additional findings omitted from this bounded feedback.")
        }
    }
}

/**
 * Automatic, non-blocking checks for any report format. Ambiguous citations are never guessed.
 * Input loading, hash verification, correction scheduling, and publication belong to callers.
 */
object SourceReportChecker {
    private val citation = Regex("""(?<![\w/])([A-Za-z0-9_.][A-Za-z0-9_./ -]*?):L(\d+)(?:-L?(\d+))?""")
    private val inlineQuote = Regex("""`([^`\r\n]+)`|'([^'\r\n]{3,})'|"([^"\r\n]{3,})" """.trimEnd())
    private val url = Regex("""https?://[^\s<>"')\]]+""")

    /**
     * Checks source spans and clearly delimited adjacent quotes without granting publication authority.
     *
     * @param content Report bytes decoded as text.
     * @param sources Immutable, verified uploaded sources.
     */
    fun check(content: String, sources: List<SourceReportInput>): SourceReportAudit {
        val issues = mutableListOf<SourceReportIssue>()
        val aliases = sources.flatMap { source ->
            listOf(source.sourceName, source.relativePath, source.relativePath.substringAfterLast('/'))
                .distinct().map { it to source }
        }.groupBy({ it.first }, { it.second })
        val linesBySource = sources.associateWith { it.text?.lineSequence()?.toList() }
        var citations = 0
        var quotations = 0
        if (sources.isEmpty()) {
            issues += SourceReportIssue("NO_SOURCE_AUTHORITY", null,
                "No immutable supplied sources are available; report claims cannot be source-checked offline.")
        }
        sources.filter { it.text == null }.forEach {
            issues += SourceReportIssue("SOURCE_UNAVAILABLE", null,
                "${it.sourceName}: ${it.unavailableReason ?: "verified source text unavailable"}")
        }
        content.lineSequence().forEachIndexed { index, line ->
            val matches = citation.findAll(line).toList()
            matches.forEach { match ->
                // A greedy filename candidate may include preceding prose. Only an exact known
                // suffix is resolvable, and ambiguous aliases remain explicitly unavailable.
                val raw = match.groupValues[1]
                val alias = aliases.keys.filter { raw == it || raw.endsWith(" $it") }
                    .maxByOrNull(String::length)
                val candidates = aliases[alias].orEmpty().distinct()
                val label = alias ?: raw.substringAfterLast(' ')
                val source = candidates.singleOrNull()
                if (source == null) {
                    issues += SourceReportIssue(
                        if (candidates.isEmpty()) "UNKNOWN_SOURCE" else "AMBIGUOUS_SOURCE",
                        index + 1, "Citation $label does not resolve to exactly one supplied source.")
                    return@forEach
                }
                val sourceLines = linesBySource[source] ?: return@forEach
                val start = match.groupValues[2].toIntOrNull()
                val end = if (match.groupValues[3].isEmpty()) start else match.groupValues[3].toIntOrNull()
                if (start == null || end == null || start < 1 || end < start || end > sourceLines.size) {
                    issues += SourceReportIssue("SOURCE_RANGE_INVALID", index + 1,
                        "Citation ${match.value.trim()} is outside ${source.sourceName} (${sourceLines.size} lines).")
                    return@forEach
                }
                citations++
                val span = sourceLines.subList(start - 1, end).joinToString("\n")
                val previousEnd = matches.takeWhile { it != match }.lastOrNull()?.range?.last?.plus(1) ?: 0
                val before = line.substring(previousEnd, match.range.first)
                // Only the last explicitly delimited quote immediately before this citation is
                // associated. Descriptive prose is not treated as an exact quotation.
                val quote = inlineQuote.findAll(before).lastOrNull()
                if (quote != null && before.substring(quote.range.last + 1).trim().all {
                        it in charArrayOf('(', ')', ':', ',', ';', '.', ' ')
                    }) {
                    val quoted = quote.groupValues.drop(1).first { it.isNotEmpty() }
                    quotations++
                    if (!span.contains(quoted)) {
                        issues += SourceReportIssue("QUOTATION_MISMATCH", index + 1,
                            "Explicit quotation before $label:L$start${if (end != start) "-L$end" else ""} " +
                                "does not occur byte-for-byte in the cited source span.")
                    }
                }
            }
        }
        if (sources.any { it.text != null } && citations == 0) {
            issues += SourceReportIssue("NO_VERIFIABLE_CITATIONS", null,
                "No resolvable filename:Lnumber source spans were found; prose references are not exact-source verification.")
        }
        PublicUrlHygiene.scan(content).filter { it.defect != null }.distinctBy { it.line to it.text }.forEach { bad ->
            issues += SourceReportIssue(
                "MALFORMED_URL",
                bad.line,
                "URL is unusable (${bad.defect}): ${PublicUrlHygiene.escapeForDiagnostics(bad.text).take(200)}. " +
                    "Retype it from the cited source without invisible characters, or remove it."
            )
        }
        if (url.containsMatchIn(content)) {
            issues += SourceReportIssue("EXTERNAL_SOURCES_NOT_CHECKED", null,
                "Report contains web references; this offline audit does not verify their contents or availability.")
        }
        return SourceReportAudit(issues.distinct(), citations, quotations)
    }
}
