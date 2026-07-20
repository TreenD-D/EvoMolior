package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.kg.KgRelationResolver
import com.achulkov.evomolior.substrate.db.dao.KGSchemaDao
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.Serializable
import java.util.UUID
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate

/**
 * Koog tool: emit a typed Knowledge Graph fact to the staging area.
 *
 * Facts are represented as (subject, relation, object) triples with a confidence score.
 * The Critic pipeline runs [KGConsistencyChecker] before deciding PROMOTE/REJECT/DISPUTE.
 *
 * Cost: 1.0 budget unit (same as [WriteStagingTool] — KG emission is a write operation).
 *
 * @param stagingDao    DAO for the staging area.
 * @param budgetLedger  The agent's budget ledger.
 * @param runId         The current run ID.
 * @param groupId       The current group ID.
 * @param agentId       The submitting agent's ID.
 * @param role          The agent's role name.
 * @param generation    Current generation number.
 */
class EmitFactTool(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int,
    private val defaultWipTaskRef: String? = null,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
    private val kgSchemaDao: KGSchemaDao? = null
) : SimpleTool<EmitFactTool.Args>(
    argsType = typeToken<Args>(),
    name = "emit_fact",
    description = """
        Emit a typed (subject, relation, object) knowledge graph fact to the staging area.
        Facts must use recognised entity types and relation types from the KG schema.
        The Critic pipeline will validate consistency before promoting to the canonical KG.
        Cost: 1 budget unit.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Subject entity (e.g. "climate_model_v3"). */
        val subject: String,
        /** Relation type (e.g. "achieves", "requires", "contradicts"). */
        val relation: String,
        /** Object entity (e.g. "carbon_neutrality_2050"). */
        val obj: String,
        /** Confidence score (0.0–1.0). */
        val confidence: Double = 0.8,
        /** Domain this fact belongs to. */
        val domain: String,
        /** Brief provenance note explaining the source or reasoning. */
        val provenance: String = "",
        /** Optional WiP task reference for facts produced from a claimed task. */
        val wipTaskRef: String? = null
    )

    override suspend fun execute(args: Args): String {
        if (args.subject.isBlank() || args.relation.isBlank() || args.obj.isBlank()) {
            return "Error: subject, relation, and object must all be non-empty."
        }
        if (args.confidence !in 0.0..1.0) {
            return "Error: confidence must be between 0.0 and 1.0."
        }
        val allowedRelations = kgSchemaDao
            ?.get(runId, groupId)
            ?.relationTypesJson
            ?.let { encoded ->
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(encoded)
                }.getOrNull()
            }
            .orEmpty()
        val canonicalRelation = if (allowedRelations.isEmpty()) {
            args.relation
        } else {
            KgRelationResolver.resolve(args.relation, allowedRelations)
                ?: return "Error: relation '${args.relation}' is not authorized by the active KG schema."
        }

        val content = buildString {
            appendLine("SUBJECT: ${args.subject}")
            appendLine("RELATION: $canonicalRelation")
            appendLine("OBJECT: ${args.obj}")
            appendLine("CONFIDENCE: ${args.confidence}")
            appendLine("DOMAIN: ${args.domain}")
            if (args.provenance.isNotBlank()) appendLine("PROVENANCE: ${args.provenance}")
        }
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = agentId,
                detail = "KG_FACT",
                sizeBytes = content.toByteArray(Charsets.UTF_8).size
            )
        )

        // Deduct budget
        val deductResult = budgetLedger.deduct(ActionType.EMIT_FACT)
        if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot emit fact."
        }

        val budgetSig = budgetLedger.generateStagingToken("KG_FACT")
        val resolvedWipTaskRef = args.wipTaskRef?.takeIf { it.isNotBlank() } ?: defaultWipTaskRef

        val entity = StagingSubmissionEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            role = role,
            type = "KG_FACT",
            content = content,
            wipTaskRef = resolvedWipTaskRef,
            speculative = args.confidence < 0.6,
            outlier = role == "Outlier",
            budgetSignature = budgetSig,
            status = "pending",
            generation = generation,
            submittedAtMs = System.currentTimeMillis()
        )
        stagingDao.insert(entity)

        val floorWarning = if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            " WARNING: Budget floor reached — start writing your Death Note."
        } else ""

        return "KG fact submitted for review. ID=${entity.id}. " +
            "Triple: (${args.subject}, $canonicalRelation, ${args.obj}) confidence=${args.confidence}.$floorWarning"
    }
}
