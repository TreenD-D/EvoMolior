package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.agents.delegation.SubAgentCoordinator
import com.achulkov.evomolior.agents.delegation.SubAgentRequest
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable

/**
 * Koog tool: delegate a sub-task to a new sub-agent.
 *
 * Only available to roles that have [Role.canDelegateToSubAgents] = true.
 * Sub-agents run in the same JVM context (no new Shuru VM).
 * Budget is carved from the calling agent's remaining balance.
 *
 * Cost: The budget_allocation amount is deducted from the parent agent's balance.
 *
 * @param coordinator The [SubAgentCoordinator] managing delegation for this agent.
 * @param bootContext The parent agent's boot context (to check delegation permissions).
 */
class DelegateSubTaskTool(
    private val coordinator: SubAgentCoordinator,
    private val bootContext: AgentBootContext
) : SimpleTool<DelegateSubTaskTool.Args>(
    argsType = typeToken<Args>(),
    name = "delegate_sub_task",
    description = """
        Delegate a focused sub-task to a sub-agent.
        The sub-agent runs within your budget slice and shares your substrate DB.
        Only roles that support delegation can use this tool.
        
        Args:
          task_description: Clear, focused description of the sub-task to complete.
          role: The role for the sub-agent (must be in your delegatable roles).
          budget_allocation: Budget units to allocate to the sub-agent (from your balance).
          context_override: Optional extra context to inject into the sub-agent's prompt.
          model_override: Optional LLM model string override (e.g. "google/gemini-2.5-flash").
        
        Returns the sub-agent's output and a summary of its contributions.
        Cost: budget_allocation units are deducted from your balance.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val task_description: String,
        val role: String,
        val budget_allocation: Double,
        val context_override: String? = null,
        val model_override: String? = null
    )

    override suspend fun execute(args: Args): String {
        // Parse role
        val role = try {
            Role.valueOf(args.role)
        } catch (e: IllegalArgumentException) {
            return "Error: Unknown role '${args.role}'. Valid delegatable roles for ${bootContext.role.name}: " +
                    bootContext.role.canDelegate.joinToString(", ") { it.name }
        }

        if (role !in bootContext.role.canDelegate) {
            return "Error: ${bootContext.role.name} cannot delegate to $role. " +
                    "Allowed roles: ${bootContext.role.canDelegate.joinToString(", ") { it.name }}"
        }

        if (args.budget_allocation <= 0) {
            return "Error: budget_allocation must be > 0."
        }

        val request = SubAgentRequest(
            taskDescription = args.task_description,
            role = role,
            budgetAllocation = args.budget_allocation,
            contextOverride = args.context_override,
            modelOverride = args.model_override
        )

        val result = coordinator.delegate(request)

        return buildString {
            appendLine("Sub-agent ${result.subAgentId} (${result.role.name}) completed.")
            appendLine("Staging contributions: ${result.stagingContributions}")
            appendLine("Budget consumed: ${String.format("%.2f", result.budgetConsumed)} units")
            appendLine("Completed normally: ${result.completed}")
            appendLine()
            appendLine("Output:")
            appendLine(result.output.take(2000))
        }
    }
}
