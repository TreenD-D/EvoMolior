package com.achulkov.evokammer.competition.coordinator

import com.achulkov.evokammer.competition.dispatch.DispatchMessage
import com.achulkov.evokammer.competition.dispatch.DispatchRelay
import com.achulkov.evokammer.competition.dispatch.DispatchTopologyAdaptationConfig
import com.achulkov.evokammer.competition.dispatch.DispatchTrustScoring
import com.achulkov.evokammer.competition.dispatch.FormalDispatchRelay
import com.achulkov.evokammer.competition.grader.GraderGroup
import com.achulkov.evokammer.competition.leaks.DifferentialPrivacyTransform
import com.achulkov.evokammer.competition.leaks.LeakOracle
import com.achulkov.evokammer.competition.leaks.LeakOracleConfig
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.GroupTopologyKind
import com.achulkov.evokammer.competition.resourcemap.NodeYieldApplier
import com.achulkov.evokammer.competition.resourcemap.ResourceMap
import com.achulkov.evokammer.competition.resourcemap.ResourceNode
import com.achulkov.evokammer.core.contract.LeakPacket
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
 * - [DispatchRelay] / [FormalDispatchRelay] — routes topology-aware messages between groups and tracks dispatch trust
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
 * @param topologyKind Base topology kind used for dispatch routing.
 * @param dispatchLagGenerations Number of generations to hold voluntary dispatches before delivery.
 * @param initialDispatchTopologyAdaptationConfig Optional trust-aware edge pruning/rewiring
 *   settings. When provided, topology refreshes happen at round boundaries.
 */
class GroupCoordinator(
    val groupIds: List<String>,
    dimensions: List<ScoringDimension>,
    leakConfig: LeakOracleConfig = LeakOracleConfig(),
    resourceNodes: List<ResourceNode> = emptyList(),
    openRouterApiKey: String? = null,
    privacyTransformModelId: String? = null,
    topologyKind: GroupTopologyKind = GroupTopologyKind.RING,
    private val dispatchLagGenerations: Int = 0,
    initialDispatchTopologyAdaptationConfig: DispatchTopologyAdaptationConfig? = null
) {

    val leakOracle = LeakOracle(leakConfig)
    /** Phase 5: differential privacy transform for leak content. Null if no API key is provided. */
    val privacyTransform: DifferentialPrivacyTransform? =
        openRouterApiKey?.let { DifferentialPrivacyTransform(it, modelId = privacyTransformModelId ?: ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL) }

    private val leakTransformer: (suspend (LeakPacket) -> LeakPacket)? = privacyTransform?.let { transform ->
        { packet: LeakPacket ->
            packet.copy(content = transform.transform(packet.content, packet.domain))
        }
    }

    private val dispatchTrustScoring = DispatchTrustScoring()
    private var dispatchTrustDaosByGroup: Map<String, com.achulkov.evokammer.substrate.db.dao.TrustScoreDao> = emptyMap()
    private var dispatchTopologyAdaptationConfig = initialDispatchTopologyAdaptationConfig
    private var currentRunId: String? = null
    private var dispatchRelay = newDispatchRelay(topologyKind)
    private var formalDispatchRelay = FormalDispatchRelay(dispatchRelay, dispatchLagGenerations)
    private var currentRoundIndex: Int = 0
    val graderGroup = GraderGroup(dimensions)
    val resourceMap = ResourceMap(resourceNodes)
    private val nodeYieldApplier = NodeYieldApplier(resourceMap)

    private val _gradingResults = MutableSharedFlow<GradingResult>(extraBufferCapacity = 64)

    /** Observable stream of [GradingResult]s emitted after each evaluation round. */
    val gradingResults: SharedFlow<GradingResult> = _gradingResults.asSharedFlow()

    /** Configures per-group trust persistence for dispatch deliveries and feedback. */
    fun configureDispatchTrustPersistence(
        runId: String,
        trustScoreDaosByGroup: Map<String, com.achulkov.evokammer.substrate.db.dao.TrustScoreDao>
    ) {
        currentRunId = runId
        dispatchTrustDaosByGroup = trustScoreDaosByGroup
    }

    /**
     * Called at the start of each round to reset per-round state.
     */
    fun onRoundStart(roundIndex: Int) {
        currentRoundIndex = roundIndex
        Napier.i("GroupCoordinator: round $roundIndex starting — resetting leak quotas")
        leakOracle.onRoundStart()
        dispatchRelay.refreshTopology()
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

    /** Returns the currently configured neighbours for [groupId]. */
    fun neighboursOf(groupId: String): List<String> = dispatchRelay.neighboursOf(groupId)

    /** Returns the formal dispatch inbox for [groupId]. */
    fun dispatchInboxFor(groupId: String) = dispatchRelay.inboundFor(groupId)

    /** Returns the current dispatch trust score for [sourceGroupId] in the default scope. */
    fun dispatchTrustScoreFor(sourceGroupId: String): Double = dispatchRelay.trustScoreOf(sourceGroupId)

    /** Returns the current dispatch trust score for [sourceGroupId] as seen by [targetGroupId]. */
    fun dispatchTrustScoreFor(sourceGroupId: String, targetGroupId: String): Double =
        dispatchRelay.trustScoreOf(sourceGroupId, targetGroupId)

    /** Applies feedback to the dispatch trust scorer in the default scope. */
    suspend fun recordDispatchFeedback(
        sourceGroupId: String,
        accepted: Boolean,
        magnitude: Double = 1.0
    ) {
        dispatchRelay.recordFeedback(sourceGroupId, accepted, magnitude)
    }

    /** Applies feedback to the dispatch trust scorer for a specific target/source pair. */
    suspend fun recordDispatchFeedback(
        targetGroupId: String,
        sourceGroupId: String,
        accepted: Boolean,
        magnitude: Double = 1.0
    ) {
        dispatchRelay.recordFeedback(sourceGroupId, targetGroupId, accepted, magnitude)
        persistDispatchTrustScore(targetGroupId, sourceGroupId, dispatchRelay.trustScoreOf(sourceGroupId, targetGroupId))
    }

    /** Reconfigures the dispatch topology for this coordinator. */
    fun configureTopology(
        topologyKind: GroupTopologyKind,
        adaptationConfig: DispatchTopologyAdaptationConfig? = dispatchTopologyAdaptationConfig
    ) {
        dispatchTopologyAdaptationConfig = adaptationConfig
        dispatchRelay = newDispatchRelay(topologyKind)
        formalDispatchRelay = FormalDispatchRelay(dispatchRelay, dispatchLagGenerations)
    }

    /**
     * Rebuilds the dispatch relay for the current topology and trust adaptation settings.
     */
    private fun newDispatchRelay(topologyKind: GroupTopologyKind) = DispatchRelay(
        groupIds = groupIds,
        leakOracle = leakOracle,
        topologyKind = topologyKind,
        topologyAdaptationConfig = dispatchTopologyAdaptationConfig,
        leakTransformer = leakTransformer,
        trustScorer = dispatchTrustScoring,
        onDelivered = { sourceGroupId, targetGroupId, _message, trustScore ->
            persistDispatchTrustScore(targetGroupId, sourceGroupId, trustScore)
        }
    )

    /**
     * Releases queued dispatches that have reached their configured lag.
     *
     * The run service calls this at round boundaries so delayed dispatches move forward
     * through the competition layer in lockstep with the generation clock.
     */
    suspend fun flushPendingDispatches(currentRoundIndex: Int = this.currentRoundIndex): Int =
        formalDispatchRelay.flushDue(currentRoundIndex)

    private suspend fun persistDispatchTrustScore(targetGroupId: String, sourceGroupId: String, trustScore: Double) {
        val runId = currentRunId ?: return
        val dao = dispatchTrustDaosByGroup[targetGroupId] ?: return
        val nowMs = System.currentTimeMillis()
        val existing = dao.get(runId, targetGroupId, sourceGroupId, "dispatch")
        if (existing == null) {
            dao.upsert(
                com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity(
                    id = "dispatch-$runId-$targetGroupId-$sourceGroupId",
                    sourceId = sourceGroupId,
                    sourceType = "dispatch",
                    runId = runId,
                    groupId = targetGroupId,
                    score = trustScore,
                    lastUpdatedMs = nowMs
                )
            )
        } else {
            dao.upsert(existing.copy(score = trustScore, lastUpdatedMs = nowMs))
        }
    }

    /**
     * B-6: Updates the inter-group migration ceiling for [sourceGroupId] for the current round.
     *
     * The ceiling is computed as `ceil(kbEntryCount * 0.15)` by the orchestration layer
     * and passed here so the [LeakOracle] can enforce the spec §8 15% migration rate cap.
     *
     * Should be called during `spawnAgentsForRound` — after the KB entry count is read
     * from the substrate DB and before agents start leaking knowledge.
     *
     * @param sourceGroupId The group whose outbound migration is being capped.
     * @param kbEntryCount The current count of active KB entries in that group.
     */
    fun updateMigrationCeiling(sourceGroupId: String, kbEntryCount: Int) {
        val ceiling = maxOf(1, kotlin.math.ceil(kbEntryCount * 0.15).toInt())
        leakOracle.patchGroupCeiling(sourceGroupId, ceiling)
        Napier.d("GroupCoordinator: migration ceiling for $sourceGroupId = $ceiling (kbEntries=$kbEntryCount)")
    }

    /**
     * Returns the multiplicative budget bonus currently earned by [groupId].
     *
     * This folds all held resource nodes into a single spawn-budget multiplier.
     *
     * @param groupId Group identifier.
     * @return Budget multiplier, with `1.0` meaning no bonus.
     */
    fun budgetMultiplierFor(groupId: String): Double = nodeYieldApplier.budgetMultiplierFor(groupId)

    /**
     * Dispatches a [DispatchMessage] from [sourceGroupId], applying [DifferentialPrivacyTransform]
     * to [DispatchMessage.Leak] packets when Phase 5 is enabled (A-8).
     *
     * The privacy transform is applied BEFORE the [LeakOracle] processes quota and
     * corruption — so the oracle sees an already-anonymised payload.
     *
     * For non-leak messages, delegates directly to [dispatchRelay] when no lag is configured.
     * When lag is enabled, the message is queued and released via [flushPendingDispatches].
     *
     * @param sourceGroupId The originating group ID.
     * @param message The message to relay.
     */
    suspend fun dispatchLeak(sourceGroupId: String, message: DispatchMessage) {
        if (dispatchLagGenerations <= 0) {
            dispatchRelay.dispatch(sourceGroupId, message)
        } else {
            formalDispatchRelay.enqueue(sourceGroupId, message, currentRoundIndex)
        }
    }
}
