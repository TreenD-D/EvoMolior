package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.serialization.Serializable
import java.util.UUID

private const val MAX_STAGING_CONTENT_CHARS = 256_000

/**
 * Koog tool: submit content to the Staging Area.
 *
 * Cost: 1 budget unit (WRITE_STAGING). Requires a valid budget deduction before writing.
 *
 * @param stagingDao DAO for the current group's staging area.
 * @param budgetLedger The agent's budget ledger for deduction.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param agentId The current agent ID.
 * @param role The agent's role name.
 * @param generation The current generation number.
 * @param isOutlier Whether the agent is an Outlier.
 * @param toolSubmissionPreflight Optional deterministic TOOL validation before Critic review.
 */
class WriteStagingTool(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int,
    private val defaultWipTaskRef: String? = null,
    private val isOutlier: Boolean = false,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
    private val toolSubmissionPreflight:
        suspend (StagingSubmissionEntity) -> String? = { null }
) : SimpleTool<WriteStagingTool.Args>(
    argsType = typeToken<Args>(),
    name = "write_staging",
    description = """
        Submit content to the Staging Area for Critic review.
        Submission types: DOC, TOOL, FACT, PLAN_UPDATE, DISPATCH.
        TOOL content must be one JSON object containing opportunityId when assigned, name, domain,
        language, inputSchema, outputSchema, non-empty code, and a non-empty tests array. Every
        test contains testId when committed by the opportunity, input, and optional expectedOutput.
        Executed output must obey additionalProperties=false and contain no undeclared top-level
        fields. TOOL prose or specifications without executable code and tests are rejected.
        Cost: 1 budget unit.
        Returns: submission ID on success.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val type: String,
        val content: String,
        val wipTaskRef: String? = null,
        val speculative: Boolean = false
    )

    override suspend fun execute(args: Args): String {
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = agentId,
                detail = args.type,
                sizeBytes = args.content.toByteArray(Charsets.UTF_8).size
            )
        )
        val submissionType = try {
            SubmissionType.valueOf(args.type)
        } catch (e: IllegalArgumentException) {
            val validTypes = SubmissionType.entries
                .filter { it != SubmissionType.DEATH_NOTE && it != SubmissionType.FAILURE_TELEMETRY }
                .joinToString(", ")
            return "Error: Invalid submission type '${args.type}'. Valid: $validTypes"
        }

        if (args.content.length > MAX_STAGING_CONTENT_CHARS) {
            return "Error: Content exceeds 256KB limit (${args.content.length} chars)."
        }

        val deductResult = budgetLedger.deduct(ActionType.WRITE_STAGING)
        if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot submit to staging."
        }

        val budgetSignature = budgetLedger.generateStagingToken(submissionType.name)
        val resolvedWipTaskRef = args.wipTaskRef?.takeIf { it.isNotBlank() } ?: defaultWipTaskRef
        val submissionId = UUID.randomUUID().toString()

        val entity = StagingSubmissionEntity(
            id = submissionId,
            agentId = agentId,
            role = role,
            type = submissionType.name,
            content = args.content,
            wipTaskRef = resolvedWipTaskRef,
            speculative = args.speculative,
            outlier = isOutlier,
            budgetSignature = budgetSignature,
            status = "pending",
            generation = generation,
            groupId = groupId,
            runId = runId,
            submittedAtMs = System.currentTimeMillis()
        )
        val preflightFailure = if (submissionType == SubmissionType.TOOL) {
            runCatching { toolSubmissionPreflight(entity) }
                .getOrElse { error ->
                    "tool_preflight_execution_error: ${error.message.orEmpty().take(300)}"
                }
                ?.take(2_000)
        } else {
            null
        }
        if (preflightFailure != null) {
            val reviewedAtMs = System.currentTimeMillis()
            stagingDao.insert(
                entity.copy(
                    status = "rejected",
                    rejectionReason = preflightFailure,
                    criticAgentId = "system-tool-preflight",
                    reviewedAtMs = reviewedAtMs
                )
            )
            return "Error: TOOL preflight rejected candidate (ID: $submissionId): " +
                "$preflightFailure Fix the exact defect and submit a new TOOL candidate."
        }

        stagingDao.insert(entity)

        return if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            "Submission accepted (ID: $submissionId) — WARNING: budget floor reached."
        } else {
            "Submission accepted (ID: $submissionId, type: ${submissionType.name}). Awaiting Critic review."
        }
    }
}
