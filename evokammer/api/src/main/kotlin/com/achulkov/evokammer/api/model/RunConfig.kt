package com.achulkov.evokammer.api.model

import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import kotlinx.serialization.Serializable

/**
 * Full configuration for a single EvoKammer civilisation run.
 *
 * Populated either manually by the user in the UI, or output by the Genesis Council agents.
 *
 * @param runId Unique run identifier (UUID).
 * @param objective Natural-language objective for the civilisation (e.g. "solve climate change").
 * @param groupCount Number of competing groups (2–8; or 1 for a test run).
 * @param groupProfiles Profile type for each group (ordered, same length as [groupCount]).
 * @param rounds Total number of evaluation rounds.
 * @param roundDurationMinutes Minutes per round before forced check-in.
 * @param maxAgentsPerGroup Maximum concurrent agents per group.
 * @param scoringDimensions Dimensions used by Grader agents.
 * @param openRouterApiKey API key for OpenRouter (used by Koog executors).
 * @param modelOverrides Optional per-role model overrides (role name → model string).
 * @param isTestRun When true, enables a minimal single-group diagnostic run:
 *   - groupCount = 1 is allowed
 *   - Genesis Council is bypassed (defaults used)
 *   - [budgetOverride] is applied to cap spend for fast verification
 * @param budgetOverride Optional global budget ceiling override (used in test runs, e.g. 200.0).
 *   Has no effect unless [isTestRun] is true or the run service explicitly respects it.
 */
@Serializable
data class RunConfig(
    val runId: String,
    val objective: String,
    val groupCount: Int,
    val groupProfiles: List<GroupProfileType>,
    val rounds: Int = 5,
    val roundDurationMinutes: Int = 30,
    val maxAgentsPerGroup: Int = 6,
    val scoringDimensions: List<ScoringDimension>,
    val openRouterApiKey: String,
    val modelOverrides: Map<String, String> = emptyMap(),
    /** True for a minimal diagnostic run: 1 group, no Genesis Council, fixed budget. */
    val isTestRun: Boolean = false,
    /**
     * Optional global budget ceiling override.
     * If non-null, overrides the default [StartingField.globalBudgetCeiling].
     * Primarily used by test runs to cap spend (e.g. 200.0 budget points).
     */
    val budgetOverride: Double? = null
) {
    init {
        // Test runs allow a single group; full runs require at least 2 for competition.
        val minGroups = if (isTestRun) 1 else 2
        require(groupCount in minGroups..8) {
            if (isTestRun) "groupCount must be 1–8" else "groupCount must be 2–8"
        }
        require(groupProfiles.size == groupCount) { "groupProfiles size must equal groupCount" }
        require(rounds > 0) { "rounds must be positive" }
        require(scoringDimensions.isNotEmpty()) { "At least one scoring dimension is required" }
    }
}
