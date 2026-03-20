package com.achulkov.evokammer.agents.tools
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.substrate.db.dao.KnowledgeGraphFactDao
import kotlinx.serialization.Serializable
/**
 * Koog tool: typed triple-pattern query over the canonical Knowledge Graph.
 *
 * This is the plan-defined KG traversal entrypoint for Phase 3. It queries the
 * dedicated KG table (not KB proxy entries) and returns the best-matching facts
 * ordered by trust score.
 *
 * @param knowledgeGraphFactDao DAO for active KG facts.
 * @param budgetLedger Agent budget ledger (charged with [ActionType.QUERY_PATTERN_KG]).
 * @param runId Current run ID.
 * @param groupId Current group ID.
 */
class QueryPatternTool(
    private val knowledgeGraphFactDao: KnowledgeGraphFactDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String
) : SimpleTool<QueryPatternTool.Args>(
    argsType = typeToken<Args>(),
    name = "query_pattern",
    description = """
        Query the Knowledge Graph with typed triple patterns.
        Use "*" or empty values as wildcards for subject/relation/object.
        Cost: 0.5 budget units.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Subject pattern (exact, wildcard "*", or partial SQL-like text). */
        val subject: String = "*",
        /** Relation pattern (exact or wildcard "*"). */
        val relation: String = "*",
        /** Object pattern (exact or wildcard "*"). */
        val obj: String = "*",
        /** Maximum number of results. */
        val maxResults: Int = 20,
        /** Minimum trust score filter. */
        val minTrustScore: Double = 0.0
    )
    override suspend fun execute(args: Args): String {
        if (args.maxResults <= 0) {
            return "Error: maxResults must be > 0."
        }
        if (args.minTrustScore !in 0.0..1.0) {
            return "Error: minTrustScore must be between 0.0 and 1.0."
        }
        val deductResult = budgetLedger.deduct(ActionType.QUERY_PATTERN_KG)
        if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot run query_pattern."
        }
        val subjectPattern = normalisePattern(args.subject)
        val relationPattern = normalisePattern(args.relation)
        val objectPattern = normalisePattern(args.obj)
        val facts = knowledgeGraphFactDao.queryByTriplePattern(
            runId = runId,
            groupId = groupId,
            subjectPattern = subjectPattern,
            relationPattern = relationPattern,
            objectPattern = objectPattern,
            limit = args.maxResults
        ).filter { it.trustScore >= args.minTrustScore }
        val floorWarning = if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            " WARNING: budget floor reached."
        } else ""
        if (facts.isEmpty()) {
            return "No KG facts matched ($subjectPattern, $relationPattern, $objectPattern).$floorWarning"
        }
        return buildString {
            appendLine("Matched ${facts.size} KG fact(s).$floorWarning")
            facts.forEachIndexed { i, fact ->
                appendLine(
                    "${i + 1}. (${fact.subject}, ${fact.relation}, ${fact.objectNode}) " +
                        "[trust=${"%.2f".format(fact.trustScore)}, gen=${fact.generation}]"
                )
            }
        }
    }
    /**
     * Converts user-facing wildcard input into SQL LIKE patterns used by Room queries.
     *
     * @param raw Raw user value from tool args.
     * @return SQL LIKE pattern.
     */
    private fun normalisePattern(raw: String): String {
        val value = raw.trim()
        return when {
            value.isEmpty() || value == "*" -> "%"
            value.contains('%') -> value
            else -> value
        }
    }
}