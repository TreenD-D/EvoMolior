package com.achulkov.evokammer.competition.coordinator

import com.achulkov.evokammer.competition.dispatch.DispatchRelay
import com.achulkov.evokammer.competition.grader.GraderGroup
import com.achulkov.evokammer.competition.leaks.DifferentialPrivacyTransform
import com.achulkov.evokammer.competition.leaks.LeakOracle
import com.achulkov.evokammer.competition.leaks.LeakOracleConfig
import com.achulkov.evokammer.competition.resourcemap.ResourceMap
import com.achulkov.evokammer.competition.resourcemap.ResourceNode
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central coordinator for all inter-group competition mechanics.
 *
 * Owns and orchestrates:
 * - [LeakOracle] — controls inter-group knowledge leakage (raw + privacy-transformed)
 * - [DifferentialPrivacyTransform] — LLM-based anonymisation for leak content (Phase 5)
 * - [DispatchRelay] — routes ring-topology messages between groups
 * - [GraderGroup] — evaluates all groups and produces [GradingResult]s
 * - [ResourceMap] — tracks which group claims which shared resource node
 *
 * The [GroupCoordinator] is created once per run and lives for the full run lifecycle.
 * It is driven by the [com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager].
 *
 * @param groupIds Ordered list of competing group IDs (defines ring topology).
 * @param dimensions Scoring dimensions defined by the Council.
 * @param leakConfig Configuration for the [LeakOracle].
 * @param resourceNodes Initial resource nodes in the [ResourceMap].
 * @param openRouterApiKey Optional API key — when provided, enables [DifferentialPrivacyTransform].
 *                         If null, leaks are relayed without privacy transformation (Phase 4 mode).
 */
class GroupCoordinator(
    val groupIds: List<String>,
    dimensions: List<ScoringDimension>,
    leakConfig: LeakOracleConfig = LeakOracleConfig(),
    resourceNodes: List<ResourceNode> = emptyList(),
    openRouterApiKey: String? = null
) {

    val leakOracle = LeakOracle(leakConfig)
    val dispatchRelay = DispatchRelay(groupIds, leakOracle)
    val graderGroup = GraderGroup(dimensions)
    val resourceMap = ResourceMap(resourceNodes)

    /** Phase 5: differential privacy transform for leak content. Null if no API key is provided. */
    val privacyTransform: DifferentialPrivacyTransform? =
        openRouterApiKey?.let { DifferentialPrivacyTransform(it) }

    private val _gradingResults = MutableSharedFlow<GradingResult>(extraBufferCapacity = 64)

    /** Observable stream of [GradingResult]s emitted after each evaluation round. */
    val gradingResults: SharedFlow<GradingResult> = _gradingResults.asSharedFlow()

    /**
     * Called at the start of each round to reset per-round state.
     */
    fun onRoundStart(roundIndex: Int) {
        Napier.i("GroupCoordinator: round $roundIndex starting — resetting leak quotas")
        leakOracle.onRoundStart()
    }

    /**
     * Called when all Grader agents have submitted evaluations for [roundIndex].
     * Computes [GradingResult] and emits it on [gradingResults].
     */
    suspend fun onGradingComplete(roundIndex: Int) {
        Napier.i("GroupCoordinator: computing scores for round $roundIndex")
        val result = graderGroup.computeGradingResult(groupIds, roundIndex)
        graderGroup.clearRound(roundIndex)
        _gradingResults.emit(result)
        Napier.i("GroupCoordinator: round $roundIndex winner=${result.winnerId}")
    }

    /**
     * Returns whether Phase 5 privacy transformation is active for this run.
     */
    val isPrivacyTransformEnabled: Boolean get() = privacyTransform != null
}
