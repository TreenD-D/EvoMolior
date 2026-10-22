package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
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
 * The export is intentionally capped at the configured limits to prevent overwhelming the
 * grader agent's context window.
 *
 * The output format is designed to be human-readable and LLM-friendly rather than
 * machine-parseable — grader agents read it in natural language and reason about it.
 */
object CanonSnapshotExporter {

    private const val MAX_KB_DOCS = 40
    private const val MAX_KG_FACTS = 80
    private const val MAX_STAGING_ENTRIES = 20
    private const val MAX_CONTENT_CHARS = 1_200
    private const val JUDGE_MAX_KB_DOCS = 16
    private const val JUDGE_MAX_KG_FACTS = 40
    private const val JUDGE_MAX_STAGING_ENTRIES = 8
    private const val JUDGE_MAX_CONTENT_CHARS = 600

    /**
     * Shared KB content budget for judge snapshots. Every document keeps at least
     * [JUDGE_MAX_CONTENT_CHARS]; unused share flows to longer documents, so a single substantial
     * report is visible to RoundJudge instead of its first 600 characters (October 2026 runs
     * scored every dimension "insufficient evidence" for a 39,670-character report).
     */
    const val JUDGE_KB_CONTENT_BUDGET_CHARS = 24_000

    /**
     * Produces the exact compact snapshot used for criteria freezes and RoundJudge evaluation.
     *
     * @param db Target group substrate.
     * @param runId Run identifier.
     * @param groupId Target group.
     * @param roundIndex Evaluation round.
     * @return Deterministic compact snapshot.
     */
    suspend fun exportForJudge(
        db: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        roundIndex: Int
    ): String = export(
        db = db,
        runId = runId,
        groupId = groupId,
        roundIndex = roundIndex,
        maxKbDocs = JUDGE_MAX_KB_DOCS,
        maxKgFacts = JUDGE_MAX_KG_FACTS,
        maxStagingEntries = JUDGE_MAX_STAGING_ENTRIES,
        maxContentChars = JUDGE_MAX_CONTENT_CHARS,
        kbContentBudgetChars = JUDGE_KB_CONTENT_BUDGET_CHARS
    )

    /**
     * Allocates per-document character limits from [budget] by water-filling: short documents
     * keep their full length, each document gets at least `min(length, floor)`, and leftover
     * budget is shared equally among documents that are still truncated.
     *
     * @param lengths Document lengths in snapshot order.
     * @param floor Minimum characters retained per document.
     * @param budget Total characters across all documents; never below the per-document floors.
     * @return Character limit for each document, in input order.
     */
    fun allocateContentLimits(lengths: List<Int>, floor: Int, budget: Int): List<Int> {
        val limits = lengths.map { minOf(it, floor) }.toMutableList()
        var remaining = budget - limits.sum()
        while (remaining > 0) {
            val growable = lengths.indices.filter { limits[it] < lengths[it] }
            if (growable.isEmpty()) break
            val share = maxOf(1, remaining / growable.size)
            for (index in growable) {
                if (remaining <= 0) break
                val grant = minOf(share, lengths[index] - limits[index], remaining)
                limits[index] += grant
                remaining -= grant
            }
        }
        return limits
    }

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
        db: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        roundIndex: Int,
        maxKbDocs: Int = MAX_KB_DOCS,
        maxKgFacts: Int = MAX_KG_FACTS,
        maxStagingEntries: Int = MAX_STAGING_ENTRIES,
        maxContentChars: Int = MAX_CONTENT_CHARS,
        kbContentBudgetChars: Int? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val kbDocs = db.knowledgeBaseDao().search(
                runId = runId,
                groupId = groupId,
                query = "",   // empty query returns all (LIKE '%' matches everything)
                limit = maxKbDocs
            ).take(maxKbDocs)

            val kgFacts = db.knowledgeGraphFactDao().queryByTriplePattern(
                runId = runId,
                groupId = groupId,
                subjectPattern = "%",
                relationPattern = "%",
                objectPattern = "%",
                limit = maxKgFacts
            )

            val stagingEntries = db.stagingDao().getAll(
                runId = runId,
                groupId = groupId,
                limit = maxStagingEntries
            ).filter { it.status == "promoted" }
                .take(maxStagingEntries)

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
                    val kbLimits = kbContentBudgetChars
                        ?.let { allocateContentLimits(kbDocs.map { it.content.length }, maxContentChars, it) }
                        ?: kbDocs.map { maxContentChars }
                    kbDocs.forEachIndexed { i, doc ->
                        appendLine()
                        appendLine("${i + 1}. [domain=${doc.domain}] trust=${
                            String.format(java.util.Locale.ROOT, "%.2f", doc.trustScore)
                        } gen=${doc.generation}")
                        val limit = kbLimits[i]
                        val content = if (doc.content.length > limit) {
                            doc.content.take(limit) +
                                "...[truncated: ${doc.content.length - limit} of ${doc.content.length} chars omitted]"
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
                        val content = if (s.content.length > maxContentChars) {
                            s.content.take(maxContentChars) + "...[truncated]"
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
