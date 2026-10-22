package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.source.SourceReportChecker
import com.achulkov.evomolior.core.source.SourceReportInput
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic advisory attachments, separate from strict publication authority. */
object ReportSourceSidecars {
    /**
     * Safe identifier bound to run and archive path, including when multiple stages are published.
     * @param runId Archive run identity.
     * @param reportPath Archive entry path.
     */
    fun pathFor(runId: String, reportPath: String): String =
        "source-checks/${sha256Hex("$runId\u0000$reportPath".encodeToByteArray())}.md"

    /**
     * Selects textual outputs without recursively auditing attachments.
     * @param path Archive entry path.
     * @param mediaType Published media type; callers separately restrict eligible artifact categories.
     */
    fun isReport(path: String, mediaType: String?): Boolean =
        !path.startsWith("source-checks/") &&
            (path.endsWith(".md", true) || path.endsWith(".html", true) ||
                path.endsWith(".txt", true) || path.endsWith(".csv", true) ||
                path.endsWith(".ipynb", true) ||
                path.endsWith(".json", true) || mediaType?.startsWith("text/") == true ||
                mediaType?.substringBefore(';') == "application/json")

    /**
     * Audits exact published bytes without modifying them or granting acceptance.
     * @param runId Archive run identity.
     * @param reportPath Exact committed archive entry path.
     * @param bytes Exact report bytes committed by the manifest.
     * @param sources Verified immutable input texts.
     */
    fun render(
        runId: String,
        reportPath: String,
        bytes: ByteArray,
        sources: List<SourceReportInput>,
    ): ByteArray = buildString {
        appendLine("# Automatic source check")
        appendLine()
        appendLine("Run identity (JSON string): ${JsonPrimitive(runId)}")
        appendLine("Report path (JSON string): ${JsonPrimitive(reportPath)}")
        appendLine("Report SHA-256: ${sha256Hex(bytes)}")
        appendLine("Report size: ${bytes.size} bytes")
        appendLine()
        appendLine("Nonblocking advisory; existing strict source gates remain independent. Not semantic proof.")
        if (sources.isEmpty()) {
            appendLine("Limitation: no verified source inputs were available to this archive publisher.")
        }
        appendLine()
        append(SourceReportChecker.check(bytes.decodeToString(), sources).renderMarkdown())
    }.encodeToByteArray()
}
