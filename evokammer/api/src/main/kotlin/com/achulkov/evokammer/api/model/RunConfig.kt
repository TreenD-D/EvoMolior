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
 * @param groupCount Number of competing groups (2–8).
 * @param groupProfiles Profile type for each group (ordered, same length as [groupCount]).
 * @param rounds Total number of evaluation rounds.
 * @param roundDurationMinutes Minutes per round before forced check-in.
 * @param maxAgentsPerGroup Maximum concurrent agents per group.
 * @param scoringDimensions Dimensions used by Grader agents.
 * @param openRouterApiKey API key for OpenRouter (used by Koog executors).
 * @param modelOverrides Optional per-role model overrides (role name → model string).
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
    val modelOverrides: Map<String, String> = emptyMap()
) {
    init {
        require(groupCount in 2..8) { "groupCount must be 2–8" }
        require(groupProfiles.size == groupCount) { "groupProfiles size must equal groupCount" }
        require(rounds > 0) { "rounds must be positive" }
        require(scoringDimensions.isNotEmpty()) { "At least one scoring dimension is required" }
    }
}
