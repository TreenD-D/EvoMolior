package com.achulkov.evomolior.competition.coordinator
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.competition.resourcemap.ResourceNode
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.ScoringDimension
/**
 * Plan-aligned facade for the multi-group competition subsystem.
 *
 * Internally delegates to the existing [GroupCoordinator] while exposing explicit
 * ring-topology metadata and the naming used in the implementation plan.
 *
 * @param topology Ring topology for all competing groups.
 * @param dimensions Scoring dimensions used by grader groups.
 * @param leakConfig Leak oracle configuration.
 * @param resourceNodes Initial resource map nodes.
 * @param openRouterApiKey Optional key enabling differential privacy leak transform.
 * @param llmBackend Backend used by the optional differential privacy leak transform.
 */
class MultiGroupCoordinator(
    val topology: RingTopology,
    dimensions: List<ScoringDimension>,
    leakConfig: LeakOracleConfig = LeakOracleConfig(),
    resourceNodes: List<ResourceNode> = emptyList(),
    openRouterApiKey: String? = null,
    llmBackend: LlmBackend = LlmBackend.OPENROUTER
) {
    /** Backing coordinator that owns runtime competition components. */
    val delegate: GroupCoordinator = GroupCoordinator(
        groupIds = topology.groupIds,
        dimensions = dimensions,
        leakConfig = leakConfig,
        resourceNodes = resourceNodes,
        openRouterApiKey = openRouterApiKey,
        llmBackend = llmBackend
    )
}
