package com.achulkov.evokammer.core.budget

import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.GroupProfile
import kotlinx.serialization.Serializable

/**
 * Resolved cost configuration for a specific group's agent population.
 *
 * Applies per-group multipliers from [GroupProfile] on top of [ActionType.defaultCost].
 *
 * @param groupId The group this config applies to.
 * @param profile The group profile defining cost multipliers.
 * @param customCosts Optional per-action overrides from Genesis Council (StartingField.actionCosts).
 * @param budgetFloorRatio The ratio of initial budget below which a kill signal is emitted.
 *   Default: 0.05 (5% of initial budget).
 * @param maxIterations Maximum Koog agent loop iterations before forced exit.
 * @param retryHashWindowSize Number of recent (toolName, argsHash) pairs to track for retry detection.
 */
@Serializable
data class BudgetConfig(
    val groupId: String,
    val profile: GroupProfile,
    val customCosts: Map<String, Double> = emptyMap(),
    val budgetFloorRatio: Double = 0.05,
    val maxIterations: Int = 40,
    val retryHashWindowSize: Int = 20
) {
    /**
     * Computes the actual cost for an [ActionType] in this group's economy.
     *
     * Priority: customCosts override > group profile multiplier > defaultCost.
     * Always-free actions return 0.0 regardless of multipliers.
     *
     * @param actionType The action to price.
     * @param tokenCount For [ActionType.LLM_CALL], the number of output tokens.
     * @return The cost in abstract budget units.
     */
    fun costFor(actionType: ActionType, tokenCount: Int = 0): Double {
        if (actionType.isAlwaysFree) return 0.0

        // Check for custom cost override from Genesis Council
        val customCost = customCosts[actionType.name]
        if (customCost != null) {
            return if (actionType == ActionType.LLM_CALL) customCost * tokenCount else customCost
        }

        val baseCost = actionType.defaultCost
        val multiplier = when (actionType) {
            ActionType.WEB_FETCH -> profile.webFetchMultiplier
            ActionType.SEARCH_KB -> profile.webFetchMultiplier * 0.5 // Cheaper than web fetch
            ActionType.TOOL_EXEC_SUCCESS -> profile.toolExecMultiplier
            ActionType.TOOL_EXEC_FAILURE -> profile.toolExecMultiplier
            ActionType.TOOL_EXEC_RETRY -> profile.toolExecMultiplier
            ActionType.INTER_AGENT_MESSAGE -> profile.interAgentMessageMultiplier
            ActionType.WRITE_STAGING -> profile.writeStagingMultiplier
            ActionType.EMIT_FACT -> profile.writeStagingMultiplier
            ActionType.EMIT_CONSTRAINT -> profile.writeStagingMultiplier * 2.0
            ActionType.LLM_CALL -> profile.llmCallMultiplier
            else -> 1.0
        }

        return if (actionType == ActionType.LLM_CALL) {
            baseCost * multiplier * tokenCount
        } else {
            baseCost * multiplier
        }
    }

    /** Computes the floor threshold for an agent with the given initial budget. */
    fun floorFor(initialBudget: Double): Double = initialBudget * budgetFloorRatio
}
