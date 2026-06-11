package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import io.github.aakira.napier.Napier
import java.io.File

/**
 * Assembles the final deliverable from a group's deliverable skeleton tree.
 *
 * The synthesiser walks the DST and produces a coherent output that reflects all
 * generations of work, grading history, and provenance metadata. It handles
 * document corpora, code repositories, and multi-artifact bundles.
 *
 * @param treeStore Store for reading per-group deliverable trees.
 * @param db The group's substrate database for artifact resolution.
 * @param runId Active run identifier.
 * @param groupId Group to synthesize for.
 */
class FinalSynthesiser(
    private val treeStore: DeliverableSkeletonTreeStore,
    private val db: EvoKammerDatabase,
    private val runId: String,
    private val groupId: String
) {

    /**
     * Possible output formats for the final deliverable.
     */
    enum class OutputFormat {
        /** Markdown document with embedded grading metadata as HTML comments. */
        MARKDOWN,
        /** Plain text with YAML frontmatter for provenance. */
        PLAINTEXT,
        /** Structured JSON with full provenance and grading history. */
        JSON
    }

    /**
     * Synthesises the final deliverable.
     *
     * @param format Output format.
     * @param outputDir Directory where the deliverable file(s) will be written.
     * @param objective The original run objective (used for the output preamble).
     * @return Path to the main output file.
     */
    suspend fun synthesise(
        format: OutputFormat = OutputFormat.MARKDOWN,
        outputDir: File,
        objective: String
    ): File {
        val tree = treeStore.read(runId, groupId)
        if (tree == null) {
            Napier.w("FinalSynthesiser: no deliverable tree for $runId/$groupId — producing fallback")
            return writeFallback(outputDir, format, objective)
        }

        val summary = tree.summarize()
        val root = tree.nodes.firstOrNull { it.nodeId == tree.rootNodeId }
        val children = root?.children?.mapNotNull { ref -> tree.nodes.firstOrNull { it.nodeId == ref } }
            ?: emptyList()

        return when (format) {
            OutputFormat.MARKDOWN -> synthesiseMarkdown(tree, summary, children, objective, outputDir)
            OutputFormat.PLAINTEXT -> synthesisePlaintext(tree, summary, children, objective, outputDir)
            OutputFormat.JSON -> synthesiseJson(tree, summary, children, objective, outputDir)
        }
    }

    // ── Format-specific synthesisers ────────────────────────────────────────────

    private suspend fun synthesiseMarkdown(
        tree: DeliverableSkeletonTree,
        summary: DeliverableSkeletonTreeSummary,
        children: List<DeliverableSkeletonNode>,
        objective: String,
        outputDir: File
    ): File {
        val output = File(outputDir, "deliverable.md")
        output.parentFile?.mkdirs()

        val content = buildString {
            appendLine("# $objective")
            appendLine()
            appendLine(buildProvenanceBlock(tree, summary))
            appendLine()

            // Walk the tree depth-first and render each node
            children.forEach { child ->
                renderMarkdownNode(tree, child, 2, this)
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Grading Summary")
            appendLine()
            appendLine("- **Total nodes**: ${summary.totalNodes}")
            appendLine("- **Average trust**: ${"%.2f".format(summary.averageTrustScore)}")
            appendLine("- **Open gaps**: ${summary.openGapCount}")
            appendLine("- **Contested nodes**: ${summary.contestedNodeCount}")
            appendLine("- **Dead ends**: ${summary.deadEndCount}")
            appendLine("- **Max complexity**: ${summary.maxComplexityLevel}")
        }

        output.writeText(content)
        Napier.i("FinalSynthesiser: wrote markdown deliverable to ${output.absolutePath}")
        return output
    }

    private suspend fun synthesisePlaintext(
        tree: DeliverableSkeletonTree,
        summary: DeliverableSkeletonTreeSummary,
        children: List<DeliverableSkeletonNode>,
        objective: String,
        outputDir: File
    ): File {
        val output = File(outputDir, "deliverable.txt")
        output.parentFile?.mkdirs()

        val content = buildString {
            appendLine(objective)
            appendLine("=" .repeat(objective.length))
            appendLine()
            appendLine("Provenance: run=$runId group=$groupId version=${tree.version} nodes=${summary.totalNodes}")
            appendLine()

            children.forEach { child ->
                renderPlaintextNode(tree, child, 1, this)
            }
        }

        output.writeText(content)
        return output
    }

    private suspend fun synthesiseJson(
        tree: DeliverableSkeletonTree,
        summary: DeliverableSkeletonTreeSummary,
        @Suppress("UNUSED_PARAMETER") children: List<DeliverableSkeletonNode>,
        objective: String,
        outputDir: File
    ): File {
        val output = File(outputDir, "deliverable.json")
        output.parentFile?.mkdirs()

        // Resolve artifact content for each non-root node
        val nodeDetails = tree.nodes
            .filter { it.nodeId != tree.rootNodeId }
            .map { node ->
                buildJsonNode(node)
            }

        val json = buildString {
            appendLine("{")
            appendLine("  \"objective\": ${escapeJson(objective)},")
            appendLine("  \"runId\": ${escapeJson(runId)},")
            appendLine("  \"groupId\": ${escapeJson(groupId)},")
            appendLine("  \"treeVersion\": ${tree.version},")
            appendLine("  \"summary\": {")
            appendLine("    \"totalNodes\": ${summary.totalNodes},")
            appendLine("    \"averageTrust\": ${summary.averageTrustScore},")
            appendLine("    \"openGapCount\": ${summary.openGapCount},")
            appendLine("    \"contestedNodes\": ${summary.contestedNodeCount},")
            appendLine("    \"deadEnds\": ${summary.deadEndCount},")
            appendLine("    \"maxComplexity\": ${summary.maxComplexityLevel}")
            appendLine("  },")
            appendLine("  \"nodes\": [")
            appendLine(nodeDetails.joinToString(",\n") { "    $it" })
            appendLine("  ]")
            appendLine("}")
        }

        output.writeText(json)
        return output
    }

    // ── Node renderers ──────────────────────────────────────────────────────────

    private suspend fun renderMarkdownNode(
        tree: DeliverableSkeletonTree,
        node: DeliverableSkeletonNode,
        depth: Int,
        builder: StringBuilder
    ) {
        val heading = "#".repeat(depth.coerceIn(2, 6))
        val statusIcon = statusIcon(node.status)

        builder.appendLine("$heading $node.title $statusIcon")
        builder.appendLine()

        // Artefact reference
        if (node.currentArtifactRef != null) {
            builder.appendLine("> **Artefact**: `${node.currentArtifactRef}` (v${node.artifactVersion}, trust=${"%.2f".format(node.trustScore)})")
            builder.appendLine()
            // Try to resolve artifact content from KB
            resolveArtifactContent(node)?.let { content ->
                builder.appendLine(content.take(2000))
                builder.appendLine()
            }
        }

        // Open gaps
        if (node.openGaps.isNotEmpty()) {
            builder.appendLine("**Open gaps:**")
            node.openGaps.forEach { gap ->
                builder.appendLine("- $gap")
            }
            builder.appendLine()
        }

        // Grading history (compact)
        val recentHistory = node.gradingHistory.takeLast(2)
        if (recentHistory.isNotEmpty()) {
            builder.appendLine("<!-- grading: ${
                recentHistory.joinToString("; ") { h ->
                    "ci=${h.checkinId} rubric=${h.rubricVersion} passed=${h.passedThreshold}"
                }
            } -->")
            builder.appendLine()
        }

        // Dead end refs
        if (node.deadEndRefs.isNotEmpty()) {
            builder.appendLine("> ⚠️ Dead ends: ${node.deadEndRefs.joinToString(", ")}")
            builder.appendLine()
        }

        // Agent chain
        if (node.citationAgentChain.isNotEmpty()) {
            builder.appendLine("<!-- agents: ${node.citationAgentChain.joinToString(" → ")} -->")
            builder.appendLine()
        }

        // Recursively render children
        node.children.forEach { childRef ->
            tree.nodes.firstOrNull { it.nodeId == childRef }?.let { child ->
                renderMarkdownNode(tree, child, depth + 1, builder)
            }
        }
    }

    private fun renderPlaintextNode(
        tree: DeliverableSkeletonTree,
        node: DeliverableSkeletonNode,
        depth: Int,
        builder: StringBuilder
    ) {
        val indent = "  ".repeat(depth)
        builder.appendLine("$indent[${statusIcon(node.status)}] ${node.title} (${node.nodeType.name})")

        if (node.currentArtifactRef != null) {
            builder.appendLine("$indent  artefact: ${node.currentArtifactRef} trust=${"%.2f".format(node.trustScore)}")
        }

        node.children.forEach { childRef ->
            tree.nodes.firstOrNull { it.nodeId == childRef }?.let { child ->
                renderPlaintextNode(tree, child, depth + 1, builder)
            }
        }
    }

    private fun buildJsonNode(node: DeliverableSkeletonNode): String = buildString {
        append("{")
        append("\"nodeId\": ${escapeJson(node.nodeId)},")
        append("\"title\": ${escapeJson(node.title)},")
        append("\"nodeType\": ${escapeJson(node.nodeType.name)},")
        append("\"status\": ${escapeJson(node.status.name)},")
        append("\"trustScore\": ${node.trustScore},")
        append("\"artifactVersion\": ${node.artifactVersion},")
        append("\"artifactRef\": ${escapeJson(node.currentArtifactRef ?: "")},")
        append("\"openGaps\": [${node.openGaps.joinToString(",") { escapeJson(it) }}],")
        append("\"graderComplexityLevel\": ${node.graderComplexityLevel},")
        append("\"gradingHistoryCount\": ${node.gradingHistory.size},")
        append("\"deadEndRefs\": [${node.deadEndRefs.joinToString(",") { escapeJson(it) }}],")
        append("\"owningGeneration\": ${node.owningGeneration},")
        append("\"ago\": \"${if (node.citationAgentChain.isEmpty()) "none" else node.citationAgentChain.joinToString(" → ")}\"")
        append("}")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun buildProvenanceBlock(
        tree: DeliverableSkeletonTree,
        summary: DeliverableSkeletonTreeSummary
    ): String = buildString {
        appendLine("<!-- EVOKAMMER DELIVERABLE PROVENANCE -->")
        appendLine("<!-- runId: $runId -->")
        appendLine("<!-- groupId: $groupId -->")
        appendLine("<!-- treeVersion: ${tree.version} -->")
        appendLine("<!-- nodes: ${summary.totalNodes}  trust: ${"%.2f".format(summary.averageTrustScore)} -->")
        appendLine("<!-- gaps: ${summary.openGapCount}  contested: ${summary.contestedNodeCount}  deadEnds: ${summary.deadEndCount} -->")
        appendLine("<!-- maxComplexity: ${summary.maxComplexityLevel} -->")
        appendLine("<!-- generated at: ${System.currentTimeMillis()} -->")
        appendLine()
        appendLine("> Generated by EvoKammer — a civilisational multi-agent framework.")
        appendLine("> ${summary.totalNodes} deliverable nodes across ${tree.version} tree versions.")
        appendLine()
    }

    private fun statusIcon(status: DeliverableSkeletonNodeStatus): String = when (status) {
        DeliverableSkeletonNodeStatus.COMPLETE -> "✅"
        DeliverableSkeletonNodeStatus.IN_PROGRESS -> "🔧"
        DeliverableSkeletonNodeStatus.STUB -> "📋"
        DeliverableSkeletonNodeStatus.CONTESTED -> "⚠️"
        DeliverableSkeletonNodeStatus.DEAD_END -> "💀"
    }

    private suspend fun resolveArtifactContent(node: DeliverableSkeletonNode): String? {
        val ref = node.currentArtifactRef ?: return null
        return when {
            ref.startsWith("kb:") -> {
                val kbId = ref.removePrefix("kb:")
                runCatching {
                    db.knowledgeBaseDao().getById(kbId)
                }.getOrNull()?.content?.take(2000)
            }
            ref.startsWith("tool:") -> {
                val toolId = ref.removePrefix("tool:")
                runCatching {
                    db.toolRegistryDao().getById(toolId)
                }.getOrNull()?.let { tool ->
                    "Tool: ${tool.name} v${tool.version}\n${tool.code.take(2000)}"
                }
            }
            else -> "[unresolved: $ref]"
        }
    }

    private fun writeFallback(outputDir: File, format: OutputFormat, objective: String): File {
        val output = File(outputDir, "deliverable.md")
        output.parentFile?.mkdirs()
        output.writeText("# $objective\n\n> No deliverable skeleton tree was generated for this run.\n")
        return output
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
