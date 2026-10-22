package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.source.SourceReportChecker
import com.achulkov.evomolior.core.source.SourceReportInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks every staged report while requesting at most one correction in an agent lifecycle.
 *
 * This grants no credits, agent slots, source access, or acceptance authority.
 *
 * @param loadSources Loader restricted to this agent's existing source authority.
 */
class ReportSourceAdvisory(
    private val loadSources: suspend () -> List<SourceReportInput>
) {
    private val sourceLock = Mutex()
    private var sources: List<SourceReportInput>? = null
    private val correctionRequested = AtomicBoolean(false)

    /**
     * Returns non-blocking feedback for content that has already been stored.
     *
     * @param content Exact staged DOC content.
     * @return One correction request, subsequent limitations, or null when no issue was detected.
     */
    suspend fun check(content: String): String? {
        val inputs = sourceLock.withLock {
            sources ?: loadSources().toList().also { sources = it }
        }
        val audit = SourceReportChecker.check(content, inputs)
        if (audit.issues.isEmpty()) return null
        // Malformed links are correctable from the report alone, so they keep the correction pass.
        if (inputs.none { it.text != null } && audit.issues.none { it.code == "MALFORMED_URL" }) {
            return "NON-BLOCKING SOURCE CHECK: the DOC was stored successfully.\n" +
                "No verified source text is available in this agent's existing authority. " +
                "No correction is requested. Do not invent sources or request additional access; " +
                "retain the useful report. Remaining limitations belong in a separate advisory side-report.\n" +
                audit.issues.take(12).joinToString("\n") { "- ${it.code}: ${it.detail}" }
        }
        val correction = audit.correctionPrompt()
        if (correction == null) {
            return "NON-BLOCKING SOURCE CHECK: verification limitations only; no correction is requested.\n" +
                "Do not replace external evidence with citations to discovery seed files. " +
                "Retain these limitations in the separate advisory side-report.\n" +
                audit.issues.take(12).joinToString("\n") { "- ${it.code}: ${it.detail}" }
        }
        if (correctionRequested.compareAndSet(false, true)) return correction
        return buildString {
            appendLine("NON-BLOCKING SOURCE CHECK: the DOC was stored successfully.")
            appendLine("The one correction pass has already been requested. Finish within the existing allowance; " +
                "retain the useful report. Remaining findings belong in a separate advisory side-report.")
            audit.issues.take(12).forEach { issue ->
                appendLine("- ${issue.code}: ${issue.detail}")
            }
        }
    }
}
