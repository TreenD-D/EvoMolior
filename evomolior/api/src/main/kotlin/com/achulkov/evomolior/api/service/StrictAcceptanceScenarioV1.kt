package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.repair.ToolRepairCode
import kotlinx.serialization.Serializable

/** Terminal authority expected from one deterministic strict-acceptance scenario. */
@Serializable
enum class ScenarioTerminalPolicy {
    ACCEPTED,
    PROVISIONAL_NO_CANDIDATE,
}

/**
 * Immutable configuration for the deterministic full-production-path acceptance simulator.
 *
 * A harness consuming this contract must use scripted model actors while still executing the real
 * orchestration, repositories, lifecycle cells, validators, grader authority, and publication
 * services. This value object defines the required injections; it does not execute them itself.
 *
 * @param scenarioId Stable scenario identity.
 * @param rounds Exact round horizon.
 * @param workerGroups Number of competing worker groups.
 * @param graderGroups Number of independent grader groups.
 * @param injectLifecycleRace Whether the scenario races a TTL kill with a budget update.
 * @param firstToolFailure Safe typed failure injected into the first tool candidate.
 * @param requireLaterAgentUse Whether another mortal agent must execute the promoted tool.
 * @param requireSimulationInfluence Whether tool execution must affect later evidence authority.
 * @param terminalPolicy Expected accepted or no-candidate provisional terminal class.
 */
@Serializable
data class StrictAcceptanceScenarioV1(
    val scenarioId: String,
    val rounds: Int = 6,
    val workerGroups: Int = 2,
    val graderGroups: Int = 3,
    val injectLifecycleRace: Boolean,
    val firstToolFailure: ToolRepairCode,
    val requireLaterAgentUse: Boolean,
    val requireSimulationInfluence: Boolean,
    val terminalPolicy: ScenarioTerminalPolicy,
) {
    init {
        require(scenarioId.isNotBlank()) { "Strict acceptance scenario ID must not be blank" }
        require(rounds == 6) {
            "Strict acceptance scenario requires the exact six-round horizon"
        }
        require(workerGroups == 2) {
            "Strict acceptance scenario requires exactly two worker groups"
        }
        require(graderGroups == 3) {
            "Strict acceptance scenario requires exactly three grader groups"
        }
    }
}
