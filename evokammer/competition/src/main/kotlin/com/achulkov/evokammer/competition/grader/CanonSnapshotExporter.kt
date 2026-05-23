package com.achulkov.evokammer.competition.grader

import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a competing group's substrate as a compact JSON snapshot for Grader agents.
 *
 * The snapshot is a structured text bundle containing the group's canonical knowledge:
 * - Top-N KB documents (by trust score)
 * - All active KG facts (subject, relation, object, confidence)
 * - Promoted staging entries (type, content, generation) from the current round
 *
 * The export is intentionally capped at MAX_KB_DOCS/MAX_KG_FACTS/MAX_STAGING_ENTRIES to
 * prevent overwhelming the grader agent's context window.
 *
 * The output format is designed to be human-readable and LLM-friendly rather than
 * machine-parseable — grader agents read it in natural language and reason about it.
 */
object CanonSnapshotExporter {

    private const val MAX_KB_DOCS = 40
    private const val MAX_KG_FACTS = 80
    private const val MAX_STAGING_ENTRIES = 20
    private const val MAX_CONTENT_CHARS = 1_200

    /**
     * Produces a structured text snapshot of the given group's substrate for grader consumption.
     *
     * @param db The substrate database for the competing group.
     * @param runId Run identifier.
     * @param groupId Competing group identifier.
     * @param roundIndex The round index being evaluated.
     * @return A multi-section text bundle, or a brief "no data" message if the DB is empty.
     */
    suspend fun export(
        db: EvoKammerDatabase,
        runId: String,
        groupId: String,
        roundIndex: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val kbDocs = db.knowledgeBaseDao().search(
                runId = runId,
                groupId = groupId,
                query = "",   // empty query returns all (LIKE '%' matches everything)
                limit = MAX_KB_DOCS
            ).take(MAX_KB_DOCS)

            val kgFacts = db.knowledgeGraphFactDao().queryByTriplePattern(
                runId = runId,
                groupId = groupId,
                subjectPattern = "%",
                relationPattern = "%",
                objectPattern = "%",
                limit = MAX_KG_FACTS
            )

            val stagingEntries = db.stagingDao().getAll(
                runId = runId,
                groupId = groupId,
                limit = MAX_STAGING_ENTRIES
            ).filter { it.status == "promoted" }
                .take(MAX_STAGING_ENTRIES)

            val kbCount = db.knowledgeBaseDao().countActive(runId, groupId)
            val kgCount = db.knowledgeGraphFactDao().countActive(runId, groupId)

            Napier.d(
                "CanonSnapshotExporter [$groupId] round=$roundIndex: " +
                "kbTotal=$kbCount kgTotal=$kgCount " +
                "exporting kb=${kbDocs.size} kg=${kgFacts.size} staging=${stagingEntries.size}"
            )

            buildString {
                appendLine("=== CANON SNAPSHOT: Group '$groupId' (Round $roundIndex) ===")
                appendLine("Knowledge Base: $kbCount active documents | Knowledge Graph: $kgCount active facts")
                appendLine()

                // ── Knowledge Base ─────────────────────────────────────────────
                appendLine("── KNOWLEDGE BASE (top ${kbDocs.size} by trust score) ──")
                if (kbDocs.isEmpty()) {
                    appendLine("[empty — no KB documents yet]")
                } else {
                    kbDocs.forEachIndexed { i, doc ->
                        appendLine()
                        appendLine("${i + 1}. [domain=${doc.domain}] trust=${
                            String.format(java.util.Locale.ROOT, "%.2f", doc.trustScore)
                        } gen=${doc.generation}")
                        val content = if (doc.content.length > MAX_CONTENT_CHARS) {
                            doc.content.take(MAX_CONTENT_CHARS) + "...[truncated]"
                        } else {
                            doc.content
                        }
                        appendLine(content)
                    }
                }
                appendLine()

                // ── Knowledge Graph ────────────────────────────────────────────
                appendLine("── KNOWLEDGE GRAPH FACTS (${kgFacts.size} active) ──")
                if (kgFacts.isEmpty()) {
                    appendLine("[empty — no KG facts yet]")
                } else {
                    kgFacts.forEach { fact ->
                        appendLine(
                            "(${fact.subject}) --[${fact.relation}]--> (${fact.objectNode}) " +
                            "conf=${String.format(java.util.Locale.ROOT, "%.2f", fact.confidence)} " +
                            "trust=${String.format(java.util.Locale.ROOT, "%.2f", fact.trustScore)}" +
                            if (fact.speculative) " [speculative]" else ""
                        )
                    }
                }
                appendLine()

                // ── Promoted Staging ───────────────────────────────────────────
                appendLine("── RECENT PROMOTED STAGING ENTRIES (${stagingEntries.size}) ──")
                if (stagingEntries.isEmpty()) {
                    appendLine("[none — no promoted staging entries]")
                } else {
                    stagingEntries.forEach { s ->
                        appendLine()
                        appendLine("[type=${s.type} gen=${s.generation} agent=${s.agentId.take(8)}]")
                        val content = if (s.content.length > MAX_CONTENT_CHARS) {
                            s.content.take(MAX_CONTENT_CHARS) + "...[truncated]"
                        } else {
                            s.content
                        }
                        appendLine(content)
                    }
                }
                appendLine()
                appendLine("=== END SNAPSHOT ===")
            }
        } catch (e: Exception) {
            Napier.w("CanonSnapshotExporter: failed to export snapshot for $groupId: ${e.message}")
            "=== CANON SNAPSHOT: Group '$groupId' (Round $roundIndex) ===\n" +
            "[Export failed: ${e.message?.take(200) ?: "unknown error"}]\n"
        }
    }
}
