package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import kotlinx.serialization.Serializable

/**
 * Koog tool: pattern-match query on the Knowledge Graph.
 *
 * Returns typed (subject, relation, object) triples matching the specified pattern.
 * Wildcards are represented by an empty string or "*".
 *
 * Cost: 0.5 budget units (deducted by [BudgetLedger] in the tool wrapper).
 *
 * Note: For Phase 3 full implementation, this will query the dedicated KG table.
 * In Phase 1–2 it returns KG-typed KB entries (tagged with domain="kg").
 *
 * @param knowledgeBaseDao DAO for the current group's KB (used for KG entry lookup).
 * @param runId   The current run ID.
 * @param groupId The current group ID.
 */
class ReadKGTool(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<ReadKGTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_kg",
    description = """
        Query the Knowledge Graph for typed (subject, relation, object) triples.
        Use wildcards ("*" or "") to match any value in a field.
        Returns matching facts with trust scores.
        Cost: 0.5 budget units.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Subject entity to match. Use "*" or "" for wildcard. */
        val subject: String = "",
        /** Relation type to match. Use "*" or "" for wildcard. */
        val relation: String = "",
        /** Object entity to match. Use "*" or "" for wildcard. */
        val obj: String = "",
        /** Optional domain filter (entity type category). */
        val domain: String? = null,
        /** Maximum results. Default: 10. */
        val maxResults: Int = 10,
        /** Minimum trust score filter (0.0–1.0). */
        val minTrustScore: Double = 0.0
    )

    override suspend fun execute(args: Args): String {
        // Phase 1–2: query KB entries in domain "kg" as a proxy for the KG table.
        // Phase 3 replaces this with a full KG DAO query.
        val entries = knowledgeBaseDao.queryByDomain(runId, groupId, args.domain ?: "kg", args.maxResults)
            .filter { it.trustScore >= args.minTrustScore }

        if (entries.isEmpty()) {
            return buildString {
                append("No KG facts found for pattern: ")
                append("(${args.subject.ifBlank { "*" }}, ${args.relation.ifBlank { "*" }}, ${args.obj.ifBlank { "*" }})")
                if (args.domain != null) append(" in domain: ${args.domain}")
            }
        }

        return buildString {
            appendLine("Found ${entries.size} KG facts:")
            entries.forEachIndexed { i, entry ->
                appendLine("\n--- Fact ${i + 1} ---")
                appendLine("ID: ${entry.id}")
                appendLine("Trust Score: ${String.format("%.2f", entry.trustScore)}")
                appendLine("Generation: ${entry.generation}")
                appendLine("Content:")
                appendLine(entry.content.take(1000))
            }
        }
    }
}
