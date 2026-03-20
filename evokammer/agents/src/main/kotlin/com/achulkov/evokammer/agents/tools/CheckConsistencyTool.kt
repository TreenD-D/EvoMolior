package com.achulkov.evokammer.agents.tools
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.substrate.db.dao.KGSchemaDao
import com.achulkov.evokammer.substrate.db.dao.KnowledgeGraphFactDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
/**
 * Koog tool: preflight consistency check for staged KG facts.
 *
 * This tool is intentionally lightweight and free (per plan/action model). It is meant
 * to be called before `emit_fact` so agents can cheaply catch obvious schema violations
 * and likely conflicts.
 *
 * @param kgSchemaDao DAO for current KG schema.
 * @param knowledgeGraphFactDao DAO for conflict checks against existing active facts.
 * @param runId Current run ID.
 * @param groupId Current group ID.
 */
class CheckConsistencyTool(
    private val kgSchemaDao: KGSchemaDao,
    private val knowledgeGraphFactDao: KnowledgeGraphFactDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<CheckConsistencyTool.Args>(
    argsType = typeToken<Args>(),
    name = "check_consistency",
    description = """
        Preflight-check staged KG facts against schema and existing higher-trust facts.
        Returns pass/dispute/reject style diagnostics without writing anything.
        Cost: 0 budget units.
    """.trimIndent()
) {
    @Serializable
    data class FactCandidate(
        /** Candidate subject node. */
        val subject: String,
        /** Candidate relation type. */
        val relation: String,
        /** Candidate object node. */
        val obj: String,
        /** Proposed confidence (0.0..1.0). */
        val confidence: Double = 0.7
    )
    @Serializable
    data class Args(
        /** Batch of candidate facts to validate. */
        val facts: List<FactCandidate>
    )
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun execute(args: Args): String {
        if (args.facts.isEmpty()) {
            return "Error: at least one fact candidate is required."
        }
        val schema = kgSchemaDao.get(runId, groupId)
        val allowedRelations = schema?.relationTypesJson
            ?.let { parseStringArray(it) }
            ?.toSet()
            ?: emptySet()
        val results = mutableListOf<String>()
        var rejectCount = 0
        var disputeCount = 0
        for ((index, fact) in args.facts.withIndex()) {
            val prefix = "${index + 1}. (${fact.subject}, ${fact.relation}, ${fact.obj})"
            if (fact.subject.isBlank() || fact.relation.isBlank() || fact.obj.isBlank()) {
                results += "$prefix -> REJECT (subject/relation/object must be non-empty)"
                rejectCount++
                continue
            }
            if (fact.confidence !in 0.0..1.0) {
                results += "$prefix -> REJECT (confidence must be 0.0..1.0)"
                rejectCount++
                continue
            }
            if (allowedRelations.isNotEmpty() && fact.relation !in allowedRelations) {
                results += "$prefix -> REJECT (relation not in KG schema)"
                rejectCount++
                continue
            }
            val conflicting = knowledgeGraphFactDao.findConflict(
                runId = runId,
                groupId = groupId,
                subject = fact.subject,
                relation = fact.relation
            )
            if (conflicting != null && conflicting.objectNode != fact.obj && conflicting.trustScore > fact.confidence) {
                results += "$prefix -> DISPUTE (conflicts with fact ${conflicting.id}, trust=${"%.2f".format(conflicting.trustScore)})"
                disputeCount++
                continue
            }
            results += "$prefix -> PASS"
        }
        return buildString {
            appendLine("Consistency check finished: total=${args.facts.size}, rejects=$rejectCount, disputes=$disputeCount")
            results.forEach { appendLine(it) }
        }.trim()
    }
    /**
     * Parses a JSON string array safely.
     *
     * @param rawJson JSON array as text.
     * @return Parsed list or empty list on parsing failure.
     */
    private fun parseStringArray(rawJson: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
}