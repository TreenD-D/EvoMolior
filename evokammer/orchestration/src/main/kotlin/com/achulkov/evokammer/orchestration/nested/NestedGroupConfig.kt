package com.achulkov.evokammer.orchestration.nested

import com.achulkov.evokammer.core.model.GroupProfileType
import kotlinx.serialization.Serializable

/**
 * Configuration for launching a nested (inner) EvoKammer group instance.
 *
 * A nested group is a full EvoKammer civilisation running inside an outer group's
 * budget envelope. Its outputs (winning KB + KG facts) roll up into the outer group's
 * substrate when it completes.
 *
 * Nested groups:
 * - Share the outer group's run ID but have a unique nested group ID
 * - Run their own generations with their own Evolutionary Selector
 * - Have an isolated Room DB (sibling directory under the outer group)
 * - Cannot run deeper nested groups (one nesting level only)
 *
 * @param nestedGroupId Unique identifier for this nested group.
 * @param outerGroupId The outer group that spawned this instance.
 * @param outerRunId The parent run's ID.
 * @param objective The decomposed sub-objective for this nested group.
 * @param budgetAllocation Budget carved from the outer group for this inner instance.
 * @param maxGenerations Maximum generations to run.
 * @param profileType Group profile to use.
 * @param openRouterApiKey API key for LLM calls.
 * @param agentJarPath Path to the AgentRunner JAR.
 * @param safetyMarginFraction Fraction of allocated budget held in reserve (not handed to inner group).
 * @param modelAssignment Per-role model overrides (role name → OpenRouter model string).
 *   If empty, [LifecycleManager] falls back to the default model map.
 *   Set from [com.achulkov.evokammer.core.contract.StartingField.modelAssignment] when available.
 */
@Serializable
data class NestedGroupConfig(
    val nestedGroupId: String,
    val outerGroupId: String,
    val outerRunId: String,
    val objective: String,
    val budgetAllocation: Double,
    val maxGenerations: Int = 5,
    val profileType: GroupProfileType = GroupProfileType.Library,
    val openRouterApiKey: String,
    val agentJarPath: String,
    val safetyMarginFraction: Double = 0.1,
    val modelAssignment: Map<String, String> = emptyMap()
) {
    /** Budget actually given to the inner instance (after safety margin deduction). */
    val effectiveBudget: Double get() = budgetAllocation * (1.0 - safetyMarginFraction)
}
