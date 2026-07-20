@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.competition.coordinator

import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.dispatch.DispatchRelay
import com.achulkov.evomolior.competition.dispatch.DispatchTopologyAdaptationConfig
import com.achulkov.evomolior.competition.dispatch.DispatchTrustScoring
import com.achulkov.evomolior.competition.dispatch.FormalDispatchRelay
import com.achulkov.evomolior.competition.grader.GraderGroup
import com.achulkov.evomolior.competition.leaks.DifferentialPrivacyTransform
import com.achulkov.evomolior.competition.leaks.LeakOracle
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.agents.factory.ProviderCallCostRecorder
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.competition.resourcemap.LegacyOwnedNodeYieldApplier
import com.achulkov.evomolior.competition.resourcemap.ResourceMap
import com.achulkov.evomolior.competition.resourcemap.ResourceNode
import com.achulkov.evomolior.core.contract.LeakPacket
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central coordinator for all inter-group competition mechanics.
 *
 * Owns and orchestrates:
 * - [LeakOracle] — controls privacy-transformed inter-group knowledge leakage
 * - [DifferentialPrivacyTransform] — LLM-based anonymisation for leak content (Phase 5)
 * - [DispatchRelay] / [FormalDispatchRelay] — routes topology-aware messages between groups and tracks dispatch trust
 * - [GraderGroup] — evaluates all groups and produces [GradingResult]s
 * - [ResourceMap] — tracks which group claims which shared resource node
 *
 * The [GroupCoordinator] is created once per run and lives for the full run lifecycle.
 * It is driven by the [com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager].
 *
 * @param groupIds Ordered list of competing group IDs (defines ring topology).
 * @param dimensions Scoring dimensions defined by the Council.
 * @param leakConfig Configuration for the [LeakOracle].
 * @param resourceNodes Initial resource nodes in the [ResourceMap].
 * @param openRouterApiKey Optional API key for [llmBackend] — when provided, enables
 *   [DifferentialPrivacyTransform]. If null, leak delivery is disabled so raw content fails closed.
 * @param llmBackend Backend used by [DifferentialPrivacyTransform].
 * @param privacyTransformerOverride Optional explicit transformer for deterministic embedding or
 *   tests. Production composition normally derives the transformer from [openRouterApiKey].
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
    llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    privacyTransformModelId: String? = null,
    privacyTransformerOverride: (suspend (LeakPacket) -> LeakPacket)? = null,
    topologyKind: GroupTopologyKind = GroupTopologyKind.RING,
    dispatchLagGenerations: Int = 0,
    initialDispatchTopologyAdaptationConfig: DispatchTopologyAdaptationConfig? = null
) {

    val leakOracle = LeakOracle(leakConfig)
    /** Phase 5 privacy transform. A null value disables leak delivery rather than relaying raw content. */
    val privacyTransform: DifferentialPrivacyTransform? =
        openRouterApiKey?.let {
            DifferentialPrivacyTransform(
                openRouterApiKey = it,
                llmBackend = llmBackend,
                modelId = privacyTransformModelId ?: ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL
            )
        }

    private val leakTransformer: (suspend (LeakPacket) -> LeakPacket)? =
        privacyTransformerOverride ?: privacyTransform?.let { transform ->
        { packet: LeakPacket ->
            packet.copy(content = transform.transform(packet.content, packet.domain))
        }
    }

    private val dispatchTrustScoring = DispatchTrustScoring()
    private var dispatchTrustDaosByGroup: Map<String, com.achulkov.evomolior.substrate.db.dao.TrustScoreDao> = emptyMap()
    private var deliveryConsumer: (suspend (
        sourceGroupId: String,
        targetGroupId: String,
        message: DispatchMessage,
        trustScore: Double
    ) -> Unit)? = null
    private var dispatchTopologyAdaptationConfig = initialDispatchTopologyAdaptationConfig
    private var dispatchLagGenerations = dispatchLagGenerations
    private var currentRunId: String? = null
    private var dispatchRelay = newDispatchRelay(topologyKind)
    private var formalDispatchRelay = FormalDispatchRelay(dispatchRelay, dispatchLagGenerations)
    private var currentRoundIndex: Int = 0
    val graderGroup = GraderGroup(dimensions)
    val resourceMap = ResourceMap(resourceNodes)
    private val legacyNodeYieldApplier = LegacyOwnedNodeYieldApplier(resourceMap)

    private val _gradingResults = MutableSharedFlow<GradingResult>(extraBufferCapacity = 64)

    /** Observable stream of [GradingResult]s emitted after each evaluation round. */
    val gradingResults: SharedFlow<GradingResult> = _gradingResults.asSharedFlow()

    /** Configures per-group trust persistence for dispatch deliveries and feedback. */
    fun configureDispatchTrustPersistence(
        runId: String,
        trustScoreDaosByGroup: Map<String, com.achulkov.evomolior.substrate.db.dao.TrustScoreDao>
    ) {
        currentRunId = runId
        dispatchTrustDaosByGroup = trustScoreDaosByGroup
    }

    /** Connects leak anonymisation calls to the run-global cost ledger. */
    fun configureProviderCostLedger(
        runId: String,
        repository: CostLedgerRepository
    ) {
        privacyTransform?.configureProviderCostRecorder(
            ProviderCallCostRecorder(
                repository = repository,
                runId = runId,
                groupId = null,
                agentId = "differential-privacy-transform"
            )
        )
    }

    /** Configures the production consumer that persists delivered dispatches and leak packets. */
    fun configureDeliveryConsumer(
        consumer: suspend (
            sourceGroupId: String,
            targetGroupId: String,
            message: DispatchMessage,
            trustScore: Double
        ) -> Unit
    ) {
        deliveryConsumer = consumer
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
    val isPrivacyTransformEnabled: Boolean get() = leakTransformer != null

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
        adaptationConfig: DispatchTopologyAdaptationConfig? = dispatchTopologyAdaptationConfig,
        dispatchLagGenerations: Int = this.dispatchLagGenerations
    ) {
        require(dispatchLagGenerations >= 0) { "dispatchLagGenerations must be >= 0" }
        dispatchTopologyAdaptationConfig = adaptationConfig
        this.dispatchLagGenerations = dispatchLagGenerations
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
        onDelivered = { sourceGroupId, targetGroupId, message, trustScore ->
            if (message is DispatchMessage.Text) {
                persistDispatchTrustScore(targetGroupId, sourceGroupId, trustScore)
            }
            deliveryConsumer?.invoke(sourceGroupId, targetGroupId, message, trustScore)
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
                com.achulkov.evomolior.substrate.db.entity.TrustScoreEntity(
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
     * Registers the run's resource nodes on the coordinator-owned map.
     *
     * Ownership mutation and budget-yield calculation must share this exact map instance;
     * otherwise claims can be visible to the archive while remaining economically inert.
     *
     * @param resourceNodes Resource nodes derived from the sealed StartingField.
     */
    fun registerResourceNodes(resourceNodes: Collection<ResourceNode>) {
        resourceMap.register(resourceNodes)
    }

    /**
     * Returns the multiplicative budget bonus currently earned by [groupId].
     *
     * This folds all held resource nodes into a single spawn-budget multiplier.
     *
     * @param groupId Group identifier.
     * @return Budget multiplier, with `1.0` meaning no bonus.
     */
    fun legacyOwnedNodeBudgetMultiplierFor(groupId: String): Double =
        legacyNodeYieldApplier.budgetMultiplierFor(groupId)

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

    /** Delivers one Watchdog-selected leak over one topology edge without formal-dispatch lag. */
    suspend fun deliverLeak(
        sourceGroupId: String,
        targetGroupId: String,
        packet: LeakPacket
    ): Boolean = dispatchRelay.dispatchTo(
        sourceGroupId = sourceGroupId,
        targetGroupId = targetGroupId,
        message = DispatchMessage.Leak(packet)
    )
}
