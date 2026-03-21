package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.substrate.db.dao.KnowledgeGraphFactDao
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Koog tool: pattern-match query on the typed Knowledge Graph table.
 *
 * Returns (subject, relation, object) triples matching the specified pattern.
 * Wildcards are represented by an empty string or "*" — mapped to SQL `%` for LIKE queries.
 *
 * Cost: 0.5 budget units (deducted by BudgetLedger in the tool wrapper).
 *
 * @param knowledgeGraphFactDao DAO for typed KG fact queries.
 * @param runId   The current run ID.
 * @param groupId The current group ID.
 */
class ReadKGTool(
    private val knowledgeGraphFactDao: KnowledgeGraphFactDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<ReadKGTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_kg",
    description = """
        Query the Knowledge Graph for typed (subject, relation, object) triples.
        Use "*" or "" in any field to match any value (wildcard).
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
        /** Maximum results to return. Default: 10. */
        val maxResults: Int = 10,
        /** Minimum trust score filter (0.0–1.0). Default: 0.0 (include all). */
        val minTrustScore: Double = 0.0
    )

    override suspend fun execute(args: Args): String {
        // Map empty string or "*" to SQL wildcard "%" for LIKE queries
        val subjectPattern = args.subject.toSqlPattern()
        val relationPattern = args.relation.toSqlPattern()
        val objPattern = args.obj.toSqlPattern()

        val facts = knowledgeGraphFactDao.queryByTriplePattern(
            runId = runId,
            groupId = groupId,
            subjectPattern = subjectPattern,
            relationPattern = relationPattern,
            objectPattern = objPattern,
            limit = args.maxResults
        ).filter { it.trustScore >= args.minTrustScore }

        if (facts.isEmpty()) {
            return buildString {
                append("No KG facts found for pattern: ")
                append("(${args.subject.ifBlank { "*" }}, ${args.relation.ifBlank { "*" }}, ${args.obj.ifBlank { "*" }})")
            }
        }

        return buildString {
            appendLine("Found ${facts.size} KG facts:")
            facts.forEachIndexed { i, fact ->
                appendLine("\n--- Fact ${i + 1} ---")
                appendLine("ID: ${fact.id}")
                appendLine("Subject: ${fact.subject}")
                appendLine("Relation: ${fact.relation}")
                appendLine("Object: ${fact.objectNode}")
                appendLine("Confidence: ${String.format(Locale.US, "%.2f", fact.confidence)}")
                appendLine("Trust Score: ${String.format(Locale.US, "%.2f", fact.trustScore)}")
                appendLine("Generation: ${fact.generation}")
                if (fact.speculative) appendLine("(speculative)")
            }
        }
    }

    /**
     * Converts an agent-supplied wildcard value ("", "*") to a SQL LIKE pattern ("%").
     * Non-wildcard values are returned as-is for exact matching.
     */
    private fun String.toSqlPattern(): String = if (isBlank() || this == "*") "%" else this
}
