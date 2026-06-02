package com.achulkov.evokammer.agents.delegation

import com.achulkov.evokammer.agents.factory.EvoKammerAgentFactory
import com.achulkov.evokammer.agents.factory.requireProductiveAgentOutcome
import com.achulkov.evokammer.agents.factory.runWithOpenRouterProviderRetry
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Manages sub-agent delegation from a parent agent.
 *
 * A parent agent can delegate a sub-task to a short-lived sub-agent running
 * within the same JVM context (no new Shuru VM). The sub-agent:
 * - Shares the parent's substrate DB
 * - Has budget carved from the parent's remaining balance
 * - Runs the same EvoKammer tool set
 * - Has its staging outputs automatically attributed to it (with parent reference)
 *
 * Only one level of delegation is permitted — sub-agents cannot delegate further.
 *
 * @param parentContext The parent agent's boot context.
 * @param parentBudgetLedger The parent's [BudgetLedger]; carve-outs reduce the parent's balance.
 * @param db The shared substrate database.
 * @param openRouterApiKey API key for LLM calls.
 * @param budgetConfig Budget configuration for this group.
 */
class SubAgentCoordinator(
    private val parentContext: AgentBootContext,
    private val parentBudgetLedger: BudgetLedger,
    private val db: EvoKammerDatabase,
    private val openRouterApiKey: String,
    private val budgetConfig: BudgetConfig
) {
    private val activeSubAgents = mutableListOf<String>()

    /**
     * Delegates a sub-task to a new sub-agent.
     *
     * The sub-agent receives a carved budget slice from the parent. If the parent's
     * remaining balance is insufficient, delegation is refused.
     *
     * @param request The sub-agent request specifying role, task, and budget.
     * @return The result of the sub-agent's run.
     */
    suspend fun delegate(request: SubAgentRequest): SubAgentResult {
        validateRequest(request)

        val subAgentId = "sub-${request.role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        Napier.i("SubAgentCoordinator: parent=${parentContext.agentId} spawning sub-agent $subAgentId (${request.role.name})")

        // Carve budget from parent
        val slicedBudget = request.budgetAllocation.coerceAtMost(parentBudgetLedger.balance * MAX_DELEGATION_FRACTION)
        if (slicedBudget <= 0) {
            Napier.w("SubAgentCoordinator: insufficient parent budget for delegation (balance=${parentBudgetLedger.balance})")
            return SubAgentResult(
                subAgentId = subAgentId,
                role = request.role,
                output = "Sub-agent delegation refused: insufficient parent budget.",
                stagingContributions = 0,
                budgetConsumed = 0.0,
                completed = false
            )
        }
        parentBudgetLedger.deductRaw(slicedBudget, "sub_agent_allocation:$subAgentId")
        activeSubAgents.add(subAgentId)

        // Build sub-agent boot context (inherits parent's context, overrides role + task)
        val subBootContext = buildSubBootContext(subAgentId, request, slicedBudget)

        // Build sub-agent budget ledger (standalone, carved amount)
        val subBudgetLedger = BudgetLedger(
            agentId = subAgentId,
            initialBudget = slicedBudget,
            floor = budgetConfig.floorFor(slicedBudget),
            config = budgetConfig
        )

        // Run sub-agent via factory
        val factory = EvoKammerAgentFactory(
            openRouterApiKey = openRouterApiKey,
            db = db,
            toolSubSandbox = com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox(),
            budgetConfig = budgetConfig,
            toolsMountPath = "/tools"
        )

        val agentContext = factory.create(subBootContext, existingBudgetLedger = subBudgetLedger)
        var output = ""
        var stagingCount = 0
        var completed = true

        try {
            output = runWithOpenRouterProviderRetry("SubAgent[$subAgentId]") {
                agentContext.agent.run(buildSubPrompt(request, subBootContext))
            }
            requireProductiveAgentOutcome("SubAgent[$subAgentId]", output, subBudgetLedger)
        } catch (e: Exception) {
            Napier.e("SubAgentCoordinator: sub-agent $subAgentId error: ${e.message}", e)
            output = "Sub-agent error: ${e.message}"
            completed = false
        } finally {
            activeSubAgents.remove(subAgentId)
            // Count staging submissions attributed to this sub-agent
            stagingCount = try {
                db.stagingDao().getTotalCountForAgent(parentContext.runId, parentContext.groupId, subAgentId)
            } catch (e: Exception) { 0 }
        }

        val consumed = slicedBudget - subBudgetLedger.balance
        Napier.i("SubAgentCoordinator: sub-agent $subAgentId completed. consumed=$consumed stagingCount=$stagingCount")

        return SubAgentResult(
            subAgentId = subAgentId,
            role = request.role,
            output = output,
            stagingContributions = stagingCount,
            budgetConsumed = consumed,
            completed = completed
        )
    }

    /** Number of sub-agents currently running (for lifecycle monitoring). */
    val activeCount: Int get() = activeSubAgents.size

    // ── Private ──────────────────────────────────────────────────────────────

    private fun validateRequest(request: SubAgentRequest) {
        check(!parentContext.isSubAgent) {
            "Sub-agents cannot spawn their own sub-agents (one-level delegation only)"
        }
        check(request.role in parentContext.role.canDelegate) {
            "Role ${parentContext.role} cannot delegate to ${request.role}. " +
                    "Allowed: ${parentContext.role.canDelegate}"
        }
        check(request.budgetAllocation > 0) { "Sub-agent budget allocation must be > 0" }
    }

    private fun buildSubBootContext(
        subAgentId: String,
        request: SubAgentRequest,
        slicedBudget: Double
    ): AgentBootContext = AgentBootContext(
        agentId = subAgentId,
        runId = parentContext.runId,
        groupId = parentContext.groupId,
        role = request.role,
        rolePrompt = buildSubRolePrompt(request),
        budgetToken = "sub-token:$subAgentId:${System.currentTimeMillis()}",
        substrateDbPath = parentContext.substrateDbPath,
        stagingMountPath = parentContext.stagingMountPath,
        artifactsMountPath = parentContext.artifactsMountPath,
        inputArtifacts = parentContext.inputArtifacts,
        wipTaskClaim = null,        // sub-agents don't claim WiP tasks directly
        legacyProtocol = null,      // sub-agents don't receive Legacy Protocols
        stageDisclosure = parentContext.stageDisclosure,
        modelAssignment = request.modelOverride ?: parentContext.modelAssignment,
        ipcBaseUrl = parentContext.ipcBaseUrl,
        generation = parentContext.generation,
        gracePeriodSeconds = parentContext.gracePeriodSeconds / 2,  // shorter grace
        parentAgentId = parentContext.agentId,
        isSubAgent = true
    )

    private fun buildSubRolePrompt(request: SubAgentRequest): String = buildString {
        append("You are a ${request.role.name} sub-agent, delegated by ${parentContext.role.name} agent ${parentContext.agentId}.\n\n")
        append("Your specific task:\n${request.taskDescription}\n\n")
        if (request.contextOverride != null) {
            append("Additional context:\n${request.contextOverride}\n\n")
        }
        append("You have a limited budget — be focused and concise.")
    }

    private fun buildSubPrompt(request: SubAgentRequest, ctx: AgentBootContext): String =
        "Complete your delegated task: ${request.taskDescription}"

    companion object {
        /** Maximum fraction of parent budget that can be delegated to sub-agents in total. */
        const val MAX_DELEGATION_FRACTION = 0.5
    }
}
