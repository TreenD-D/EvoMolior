package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Koog tool: propose a new KG soft constraint to the staging area.
 *
 * Soft constraints are domain-level rules that reduce trust scores on violations
 * (as opposed to hard constraints which outright reject conflicting facts).
 *
 * Only Planners and KG-focused Researchers/Builders should emit constraints.
 * Requires Critic approval before entering the canonical KG schema.
 *
 * Cost: 2 budget units (2× the cost of a fact — schema changes are expensive).
 *
 * @param stagingDao    DAO for the staging area.
 * @param budgetLedger  The agent's budget ledger.
 * @param runId         The current run ID.
 * @param groupId       The current group ID.
 * @param agentId       The submitting agent's ID.
 * @param role          The agent's role name.
 * @param generation    Current generation number.
 */
class EmitConstraintTool(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int
) : SimpleTool<EmitConstraintTool.Args>(
    argsType = typeToken<Args>(),
    name = "emit_constraint",
    description = """
        Propose a new KG soft constraint.
        Soft constraints reduce trust scores when violated (they do not block insertion).
        Example: "a solution claiming carbon neutrality must cite at least one validated tool".
        Requires Critic approval. Cost: 2 budget units.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Short identifier for this constraint (snake_case). */
        val constraintId: String,
        /** Human-readable description of what the constraint enforces. */
        val description: String,
        /** The KG entity type or relation type this constraint applies to. */
        val appliesToType: String,
        /** Natural-language expression of the constraint condition. */
        val condition: String,
        /** Trust score penalty (0.0–1.0) applied on violation. */
        val violationPenalty: Double = 0.2,
        /** Rationale for why this constraint should exist. */
        val rationale: String = ""
    )

    override suspend fun execute(args: Args): String {
        if (args.constraintId.isBlank() || args.description.isBlank() || args.condition.isBlank()) {
            return "Error: constraintId, description, and condition must all be non-empty."
        }
        if (args.violationPenalty !in 0.0..1.0) {
            return "Error: violationPenalty must be between 0.0 and 1.0."
        }

        // Deduct budget (2× EMIT_FACT cost)
        val deductResult1 = budgetLedger.deduct(ActionType.EMIT_CONSTRAINT)
        if (deductResult1 == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot emit constraint."
        }

        val content = buildString {
            appendLine("CONSTRAINT_ID: ${args.constraintId}")
            appendLine("TYPE: SOFT_CONSTRAINT")
            appendLine("APPLIES_TO: ${args.appliesToType}")
            appendLine("DESCRIPTION: ${args.description}")
            appendLine("CONDITION: ${args.condition}")
            appendLine("VIOLATION_PENALTY: ${args.violationPenalty}")
            if (args.rationale.isNotBlank()) appendLine("RATIONALE: ${args.rationale}")
        }

        val budgetSig = budgetLedger.generateStagingToken("KG_CONSTRAINT")

        val entity = StagingSubmissionEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            role = role,
            type = "KG_CONSTRAINT",
            content = content,
            wipTaskRef = null,
            speculative = false,
            outlier = role == "Outlier",
            budgetSignature = budgetSig,
            status = "pending",
            generation = generation,
            submittedAtMs = System.currentTimeMillis()
        )
        stagingDao.insert(entity)

        val floorWarning = if (deductResult1 == BudgetDeductResult.FLOOR_REACHED) {
            " WARNING: Budget floor reached."
        } else ""

        return "KG soft constraint '${args.constraintId}' submitted for review. ID=${entity.id}.$floorWarning"
    }
}
