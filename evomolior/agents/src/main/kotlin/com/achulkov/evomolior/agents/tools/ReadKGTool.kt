@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.kg.KgRelationResolver
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.DownstreamUseKind
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.substrate.db.dao.KGSchemaDao
import com.achulkov.evomolior.substrate.db.dao.KnowledgeGraphFactDao
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock

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
    private val kgSchemaDao: KGSchemaDao,
    private val runId: String,
    private val groupId: String,
    private val agentId: String = "",
    private val round: Int = 0,
    private val nodeId: String? = null,
    private val contributionGraphRepository: ContributionGraphRepository? = null,
    private val useIdFactory: () -> String = { UUID.randomUUID().toString() }
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
    private val json = Json { encodeDefaults = true }

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
        val relationPattern = canonicalRelationPattern(args.relation)
            ?: return "Error: relation '${args.relation}' is not authorized by the active KG schema."
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

        recordReads(facts.map { "kg:${it.id}" }, args)

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

    private suspend fun canonicalRelationPattern(relation: String): String? {
        val pattern = relation.toSqlPattern()
        if (pattern.contains('%')) return pattern
        val allowedRelations = kgSchemaDao.get(runId, groupId)
            ?.relationTypesJson
            ?.let { raw -> runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw) }.getOrNull() }
            .orEmpty()
        return if (allowedRelations.isEmpty()) pattern else KgRelationResolver.resolve(pattern, allowedRelations)
    }

    private suspend fun recordReads(authorityIds: List<String>, args: Args) {
        val repository = contributionGraphRepository ?: return
        require(agentId.isNotBlank()) {
            "Downstream KG reads require exact reader identity"
        }
        val contextHash = AtlasHashing.hashText(
            json.encodeToString(Args.serializer(), args)
        )
        authorityIds.forEach { authorityId ->
            val authority = requireNotNull(
                repository.canonicalAuthority(runId, groupId, authorityId)
            ) {
                "KG read lacks immutable canonical authority for $authorityId"
            }
            repository.recordDownstreamUse(
                DownstreamUseRecord(
                    useId = "downstream-read:${useIdFactory()}",
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = round,
                    agentId = agentId,
                    nodeId = nodeId,
                    kind = DownstreamUseKind.READ,
                    sourceAuthority = authority,
                    contextHash = contextHash,
                    occurredAt = Clock.System.now()
                ).withComputedHash()
            )
        }
    }
}
