package com.achulkov.evokammer.agents.delegation

import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * A request from a parent agent to spawn a sub-agent.
 *
 * Sub-agents run within the parent VM (not a new Shuru VM) and share
 * the same substrate DB. Their budget is carved from the parent's remaining balance.
 *
 * Constraints:
 * - One level deep only (sub-agents cannot spawn their own sub-agents)
 * - Sub-agent role must be a subset of [Role.canDelegate] set for the parent role
 * - Total budget sliced to sub-agents must not exceed [maxDelegationFraction] of parent budget
 * - Sub-agent lifetime is bounded by the parent's remaining grace period
 *
 * @param taskDescription Natural-language description of the sub-task.
 * @param role Role to assign to the sub-agent.
 * @param budgetAllocation Budget units to carve for this sub-agent.
 * @param contextOverride Optional additional context for the sub-agent's system prompt.
 * @param modelOverride If set, use a different LLM model for this sub-agent.
 */
@Serializable
data class SubAgentRequest(
    val taskDescription: String,
    val role: Role,
    val budgetAllocation: Double,
    val contextOverride: String? = null,
    val modelOverride: String? = null
)

/**
 * The result of a completed sub-agent run.
 *
 * @param subAgentId The generated ID of the sub-agent.
 * @param role The role the sub-agent was assigned.
 * @param output The final output text from the sub-agent.
 * @param stagingContributions Number of staging submissions made.
 * @param budgetConsumed Budget units actually consumed.
 * @param completed Whether the sub-agent completed normally or was budget-killed.
 */
@Serializable
data class SubAgentResult(
    val subAgentId: String,
    val role: Role,
    val output: String,
    val stagingContributions: Int,
    val budgetConsumed: Double,
    val completed: Boolean
)
