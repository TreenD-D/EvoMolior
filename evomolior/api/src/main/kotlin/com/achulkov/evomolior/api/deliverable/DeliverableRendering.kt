package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.DeliverableChecklistItem
import com.achulkov.evomolior.core.deliverable.DeliverableComponent
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceDisclosure
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import java.util.Base64

/**
 * One deterministic in-memory deliverable file.
 *
 * @param path Package-relative path.
 * @param mediaType Canonical media type.
 * @param content Exact UTF-8 content.
 * @param contentHash SHA-256 commitment of [content].
 */
data class RenderedDeliverableArtifact(
    val path: String,
    val mediaType: String,
    val content: String,
    val contentHash: String = DeliverableHashing.hashText(content),
) {
    init {
        require(path.isSafeRelativePath()) { "Rendered artifact path is unsafe: $path" }
        require(mediaType.isNotBlank()) { "Rendered artifact media type must not be blank" }
        require(
            DeliverableHashing.constantTimeEquals(
                contentHash,
                DeliverableHashing.hashText(content)
            )
        ) { "Rendered artifact content hash is invalid" }
    }
}

/**
 * Complete output from one deterministic renderer.
 *
 * @param format Renderer format.
 * @param semanticPackageHash Exact semantic package commitment.
 * @param artifacts Ordered output artifacts.
 */
data class RenderedDeliverable(
    val format: DeliverableFormat,
    val semanticPackageHash: String,
    val artifacts: List<RenderedDeliverableArtifact>,
) {
    init {
        require(artifacts.isNotEmpty()) { "A renderer must emit at least one artifact" }
        require(artifacts.map { it.path }.distinct().size == artifacts.size) {
            "A renderer cannot emit duplicate artifact paths"
        }
    }
}

/** Adapter from one semantic receiver package to one deterministic format. */
fun interface DeliverableRenderer {
    /**
     * Renders [decisionPackage] without changing its semantic authority.
     *
     * @param decisionPackage Hashed semantic package.
     * @return Deterministic rendered output.
     */
    fun render(decisionPackage: ReceiverDecisionPackage): RenderedDeliverable
}

/** Deterministic Markdown renderer with an embedded canonical semantic package. */
object MarkdownDeliverableRenderer : DeliverableRenderer {
    override fun render(decisionPackage: ReceiverDecisionPackage): RenderedDeliverable {
        require(decisionPackage.hasValidHashes()) { "Cannot render an invalid semantic package" }
        val content = MarkdownSemanticWriter.render(decisionPackage) +
            "\n\n<!-- $MARKDOWN_SEMANTIC_MARKER" +
            SemanticPackageEmbedding.encode(decisionPackage) +
            " -->\n"
        return RenderedDeliverable(
            format = DeliverableFormat.MARKDOWN,
            semanticPackageHash = decisionPackage.packageHash,
            artifacts = listOf(
                RenderedDeliverableArtifact(
                    path = decisionPackage.mainFileName("md"),
                    mediaType = "text/markdown; charset=utf-8",
                    content = content,
                )
            ),
        )
    }
}

/** Deterministic HTML5 renderer with escaped content and embedded canonical semantics. */
object Html5DeliverableRenderer : DeliverableRenderer {
    override fun render(decisionPackage: ReceiverDecisionPackage): RenderedDeliverable {
        require(decisionPackage.hasValidHashes()) { "Cannot render an invalid semantic package" }
        val content = buildString {
            append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>Decision package</title>")
            append("<style>")
            append("body{font-family:system-ui,sans-serif;max-width:72rem;margin:auto;padding:2rem;")
            append("line-height:1.5}table{border-collapse:collapse;width:100%}")
            append("th,td{border:1px solid #bbb;padding:.4rem;text-align:left}")
            append(".authority{font-weight:700}.withheld{background:#fff3cd;padding:.6rem}")
            append("code{overflow-wrap:anywhere}</style></head><body>")
            append("<header><h1>Receiver decision package</h1>")
            append("<p class=\"authority\">")
            append(decisionPackage.authorityBanner().escapeHtml())
            append("</p><dl><dt>Audience</dt><dd>")
            append(decisionPackage.audience.escapeHtml())
            append("</dd><dt>Decision or use</dt><dd>")
            append(decisionPackage.decisionOrUse.escapeHtml())
            append("</dd><dt>Package hash</dt><dd><code>")
            append(decisionPackage.packageHash)
            append("</code></dd></dl></header>")
            decisionPackage.components.forEach { component ->
                append("<section id=\"")
                append(component.componentId.escapeHtmlAttribute())
                append("\"><h2>")
                append(component.title.escapeHtml())
                append("</h2><p><strong>Authority:</strong> ")
                append(component.authorityClass.name)
                append("</p>")
                if (component.blocks.isEmpty()) {
                    append("<p class=\"withheld\">Content withheld because supplied semantic ")
                    append("authority is unavailable.</p>")
                } else {
                    component.blocks.forEach { block -> append(block.toHtml()) }
                }
                component.uncertainty?.let {
                    append("<p><strong>Uncertainty:</strong> ")
                    append(it.escapeHtml())
                    append("</p>")
                }
                append(component.provenance.toHtml())
                append("</section>")
            }
            append("<script id=\"evomolior-semantic-package\" type=\"application/json\" ")
            append("data-encoding=\"base64\">")
            append(SemanticPackageEmbedding.encode(decisionPackage))
            append("</script></body></html>\n")
        }
        return RenderedDeliverable(
            format = DeliverableFormat.HTML5,
            semanticPackageHash = decisionPackage.packageHash,
            artifacts = listOf(
                RenderedDeliverableArtifact(
                    path = decisionPackage.mainFileName("html"),
                    mediaType = "text/html; charset=utf-8",
                    content = content,
                )
            ),
        )
    }
}

/** Canonical JSON renderer for the complete semantic package. */
object JsonDeliverableRenderer : DeliverableRenderer {
    override fun render(decisionPackage: ReceiverDecisionPackage): RenderedDeliverable {
        require(decisionPackage.hasValidHashes()) { "Cannot render an invalid semantic package" }
        return RenderedDeliverable(
            format = DeliverableFormat.JSON,
            semanticPackageHash = decisionPackage.packageHash,
            artifacts = listOf(
                RenderedDeliverableArtifact(
                    path = decisionPackage.mainFileName("json"),
                    mediaType = "application/json",
                    content = DeliverableHashing.encodePackage(decisionPackage),
                )
            ),
        )
    }
}

/**
 * Deterministic CSV bundle renderer.
 *
 * CSV carries flattened indexes and exact supplied table blocks. A canonical JSON member preserves
 * semantic types that CSV cannot represent without loss.
 */
object CsvBundleDeliverableRenderer : DeliverableRenderer {
    override fun render(decisionPackage: ReceiverDecisionPackage): RenderedDeliverable {
        require(decisionPackage.hasValidHashes()) { "Cannot render an invalid semantic package" }
        val artifacts = mutableListOf(
            RenderedDeliverableArtifact(
                path = "semantic-package.json",
                mediaType = "application/json",
                content = DeliverableHashing.encodePackage(decisionPackage),
            ),
            RenderedDeliverableArtifact(
                path = "component-index.csv",
                mediaType = "text/csv; charset=utf-8",
                content = CsvWriter.rows(
                    listOf("component_id", "kind", "title", "authority", "content_hash"),
                    decisionPackage.components.map {
                        listOf(
                            it.componentId,
                            it.kind.name,
                            it.title,
                            it.authorityClass.name,
                            it.contentHash,
                        )
                    },
                ),
            ),
            RenderedDeliverableArtifact(
                path = "evidence-ledger.csv",
                mediaType = "text/csv; charset=utf-8",
                content = CsvWriter.rows(
                    listOf(
                        "evidence_id",
                        "authority",
                        "disclosure",
                        "source_node_id",
                        "evidence_packet_id",
                        "evidence_hash",
                        "public_summary",
                    ),
                    decisionPackage.evidenceLedger.map {
                        listOf(
                            it.evidenceId,
                            it.authorityClass.name,
                            it.disclosure.name,
                            it.sourceNodeId.orEmpty(),
                            it.evidencePacketId.orEmpty(),
                            it.evidenceHash,
                            if (it.disclosure == DeliverableEvidenceDisclosure.PUBLIC_COMMITTED) {
                                it.publicSummary.orEmpty()
                            } else {
                                ""
                            },
                        )
                    },
                ),
            ),
        )
        decisionPackage.components.forEachIndexed { componentIndex, component ->
            component.blocks.forEachIndexed { blockIndex, block ->
                if (block is DeliverableSemanticBlock.Table) {
                    artifacts += RenderedDeliverableArtifact(
                        path = "tables/${componentIndex.toString().padStart(3, '0')}-" +
                            "${blockIndex.toString().padStart(3, '0')}.csv",
                        mediaType = "text/csv; charset=utf-8",
                        content = CsvWriter.rows(block.columns, block.rows),
                    )
                }
            }
        }
        return RenderedDeliverable(
            format = DeliverableFormat.CSV_BUNDLE,
            semanticPackageHash = decisionPackage.packageHash,
            artifacts = artifacts,
        )
    }
}

/** Registry of the bounded deterministic renderer set. */
object DeterministicDeliverableRenderers {
    /** Returns the renderer for [format]. */
    fun renderer(format: DeliverableFormat): DeliverableRenderer = when (format) {
        DeliverableFormat.MARKDOWN -> MarkdownDeliverableRenderer
        DeliverableFormat.HTML5 -> Html5DeliverableRenderer
        DeliverableFormat.JSON -> JsonDeliverableRenderer
        DeliverableFormat.CSV_BUNDLE -> CsvBundleDeliverableRenderer
    }

    /** Renders [decisionPackage] in deterministic enum order for all [formats]. */
    fun renderAll(
        decisionPackage: ReceiverDecisionPackage,
        formats: Set<DeliverableFormat>,
    ): List<RenderedDeliverable> =
        formats.sortedBy { it.ordinal }.map { renderer(it).render(decisionPackage) }
}

internal object MarkdownSemanticWriter {
    fun render(
        decisionPackage: ReceiverDecisionPackage,
        components: List<DeliverableComponent> = decisionPackage.components,
        heading: String = "Receiver decision package",
        includePackageHeader: Boolean = true,
    ): String = buildString {
        append("# ").appendLine(heading)
        if (includePackageHeader) {
            appendLine()
            append("**").append(decisionPackage.authorityBanner()).appendLine("**")
            appendLine()
            appendLine("- Audience: ${decisionPackage.audience}")
            appendLine("- Decision or use: ${decisionPackage.decisionOrUse}")
            appendLine("- Package hash: `${decisionPackage.packageHash}`")
        }
        components.forEach { component ->
            appendLine()
            append("## ").appendLine(component.title)
            appendLine()
            appendLine("**Authority: ${component.authorityClass.name}**")
            if (component.blocks.isEmpty()) {
                appendLine()
                appendLine(
                    "Content withheld because supplied semantic authority is unavailable."
                )
            } else {
                component.blocks.forEach { block ->
                    appendLine()
                    append(block.toMarkdown())
                    if (!endsWith("\n")) appendLine()
                }
            }
            component.uncertainty?.let {
                appendLine()
                appendLine("**Uncertainty:** $it")
            }
            appendLine()
            appendLine("### Provenance")
            appendLine()
            appendLine(
                "- Evidence: ${component.provenance.evidenceIds.sorted().displayOrNone()}"
            )
            appendLine(
                "- Source nodes: ${component.provenance.sourceNodeIds.sorted().displayOrNone()}"
            )
            appendLine(
                "- Evidence packets: " +
                    component.provenance.evidencePacketIds.sorted().displayOrNone()
            )
            appendLine(
                "- Tool executions: " +
                    component.provenance.toolExecutionIds.sorted().displayOrNone()
            )
            appendLine(
                "- Simulation executions: " +
                    component.provenance.simulationExecutionIds.sorted().displayOrNone()
            )
            component.provenance.grades.forEach {
                appendLine(
                    "- Grade `${it.gradeId}`: score=${it.score?.toString() ?: "none"}, " +
                        "hash=`${it.gradeHash}`"
                )
            }
            component.provenance.tests.forEach {
                appendLine(
                    "- Test `${it.testId}`: status=${it.status.name}, " +
                        "hash=`${it.resultHash}`"
                )
            }
        }
    }.trimEnd()
}

private object CsvWriter {
    fun rows(header: List<String>, rows: List<List<String>>): String =
        (listOf(header) + rows).joinToString(separator = "\n", postfix = "\n") { row ->
            row.joinToString(",") { it.escapeCsv() }
        }
}

private object SemanticPackageEmbedding {
    fun encode(decisionPackage: ReceiverDecisionPackage): String =
        Base64.getEncoder().encodeToString(
            DeliverableHashing.encodePackage(decisionPackage).encodeToByteArray()
        )

    fun decode(value: String): ReceiverDecisionPackage =
        DeliverableHashing.decodePackage(
            Base64.getDecoder().decode(value).decodeToString()
        )
}

internal fun extractEmbeddedPackage(rendered: RenderedDeliverable): ReceiverDecisionPackage =
    when (rendered.format) {
        DeliverableFormat.MARKDOWN -> {
            val content = rendered.artifacts.single().content
            val encoded = content.substringAfter(MARKDOWN_SEMANTIC_MARKER, "")
                .substringBefore(" -->", "")
            require(encoded.isNotBlank()) { "Markdown semantic package marker is missing" }
            SemanticPackageEmbedding.decode(encoded)
        }
        DeliverableFormat.HTML5 -> {
            val content = rendered.artifacts.single().content
            val encoded = content.substringAfter(HTML_SEMANTIC_MARKER, "")
                .substringBefore("</script>", "")
            require(encoded.isNotBlank()) { "HTML semantic package marker is missing" }
            SemanticPackageEmbedding.decode(encoded)
        }
        DeliverableFormat.JSON -> DeliverableHashing.decodePackage(
            rendered.artifacts.single().content
        )
        DeliverableFormat.CSV_BUNDLE -> DeliverableHashing.decodePackage(
            rendered.artifacts.single { it.path == "semantic-package.json" }.content
        )
    }

private fun ReceiverDecisionPackage.mainFileName(extension: String): String =
    when (outcomeClass) {
        RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "decision-package.$extension"
        RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE ->
            "provisional-decision-package.$extension"
        RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> "startup-diagnostic-package.$extension"
    }

internal fun ReceiverDecisionPackage.authorityBanner(): String = when (outcomeClass) {
    RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "ACCEPTED DECISION PACKAGE"
    RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE ->
        "PROVISIONAL NON-ACCEPTED — DO NOT TREAT AS AN ACCEPTED RECOMMENDATION"
    RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
        "STARTUP DIAGNOSTIC — CONTAINS NO RECEIVER DECISION CLAIMS"
}

private fun DeliverableSemanticBlock.toMarkdown(): String = when (this) {
    is DeliverableSemanticBlock.Narrative -> text
    is DeliverableSemanticBlock.Table -> buildString {
        append("| ").append(columns.joinToString(" | ") { it.escapeMarkdownCell() })
            .appendLine(" |")
        append("| ").append(columns.joinToString(" | ") { "---" }).appendLine(" |")
        rows.forEach { row ->
            append("| ").append(row.joinToString(" | ") { it.escapeMarkdownCell() })
                .appendLine(" |")
        }
    }.trimEnd()
    is DeliverableSemanticBlock.KeyValue -> buildString {
        appendLine("| Field | Value |")
        appendLine("| --- | --- |")
        entries.forEach {
            append("| ").append(it.key.escapeMarkdownCell()).append(" | ")
                .append(it.value.escapeMarkdownCell()).appendLine(" |")
        }
    }.trimEnd()
    is DeliverableSemanticBlock.Checklist -> items.joinToString("\n") {
        "- [${if (it.completed) "x" else " "}] ${it.text}"
    }
}

private fun DeliverableSemanticBlock.toHtml(): String = when (this) {
    is DeliverableSemanticBlock.Narrative ->
        "<p>${text.escapeHtml().replace("\n", "<br>")}</p>"
    is DeliverableSemanticBlock.Table -> buildString {
        append("<table><thead><tr>")
        columns.forEach { append("<th>").append(it.escapeHtml()).append("</th>") }
        append("</tr></thead><tbody>")
        rows.forEach { row ->
            append("<tr>")
            row.forEach { append("<td>").append(it.escapeHtml()).append("</td>") }
            append("</tr>")
        }
        append("</tbody></table>")
    }
    is DeliverableSemanticBlock.KeyValue -> buildString {
        append("<dl>")
        entries.forEach {
            append("<dt>").append(it.key.escapeHtml()).append("</dt><dd>")
                .append(it.value.escapeHtml()).append("</dd>")
        }
        append("</dl>")
    }
    is DeliverableSemanticBlock.Checklist ->
        "<ul>${items.joinToString("") { it.toHtml() }}</ul>"
}

private fun DeliverableChecklistItem.toHtml(): String =
    "<li>${if (completed) "☑" else "☐"} ${text.escapeHtml()}</li>"

private fun com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance.toHtml(): String =
    buildString {
        append("<h3>Provenance</h3><ul>")
        append("<li>Evidence: ").append(evidenceIds.sorted().displayOrNone().escapeHtml())
            .append("</li>")
        append("<li>Source nodes: ")
            .append(sourceNodeIds.sorted().displayOrNone().escapeHtml()).append("</li>")
        append("<li>Evidence packets: ")
            .append(evidencePacketIds.sorted().displayOrNone().escapeHtml()).append("</li>")
        append("<li>Tool executions: ")
            .append(toolExecutionIds.sorted().displayOrNone().escapeHtml()).append("</li>")
        append("<li>Simulation executions: ")
            .append(simulationExecutionIds.sorted().displayOrNone().escapeHtml()).append("</li>")
        grades.forEach {
            append("<li>Grade <code>").append(it.gradeId.escapeHtml())
                .append("</code>: score=")
                .append((it.score?.toString() ?: "none").escapeHtml())
                .append(", hash=<code>").append(it.gradeHash).append("</code></li>")
        }
        tests.forEach {
            append("<li>Test <code>").append(it.testId.escapeHtml())
                .append("</code>: status=").append(it.status.name)
                .append(", hash=<code>").append(it.resultHash).append("</code></li>")
        }
        append("</ul>")
    }

private fun List<String>.displayOrNone(): String = if (isEmpty()) "none" else joinToString(", ")

private fun String.escapeCsv(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }

private fun String.escapeMarkdownCell(): String =
    replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>")

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun String.escapeHtmlAttribute(): String = escapeHtml()

private fun String.isSafeRelativePath(): Boolean {
    if (isBlank() || startsWith("/") || startsWith("\\") || contains('\u0000')) return false
    return split('/', '\\').none { it.isBlank() || it == "." || it == ".." }
}

private const val MARKDOWN_SEMANTIC_MARKER = "EVOMOLIOR_SEMANTIC_PACKAGE_V1:"
private const val HTML_SEMANTIC_MARKER =
    "<script id=\"evomolior-semantic-package\" type=\"application/json\" " +
        "data-encoding=\"base64\">"
