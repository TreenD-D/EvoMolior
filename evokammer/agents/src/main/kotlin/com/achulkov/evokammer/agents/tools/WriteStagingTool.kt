package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.SubmissionType
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
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
    private val isOutlier: Boolean = false
) : SimpleTool<WriteStagingTool.Args>(
    argsType = typeToken<Args>(),
    name = "write_staging",
    description = """
        Submit content to the Staging Area for Critic review.
        Submission types: DOC, TOOL, FACT, PLAN_UPDATE, DISPATCH.
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
        stagingDao.insert(entity)

        return if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            "Submission accepted (ID: $submissionId) — WARNING: budget floor reached."
        } else {
            "Submission accepted (ID: $submissionId, type: ${submissionType.name}). Awaiting Critic review."
        }
    }
}
