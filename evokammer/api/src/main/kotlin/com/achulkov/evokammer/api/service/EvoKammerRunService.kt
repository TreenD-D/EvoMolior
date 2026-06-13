package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.FinalOutputKind
import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.api.service.NESTED_GROUP_OPEN_TASK_LIMIT
import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerKernelAuditActionType
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.core.contract.OverseerKernelArchive
import com.achulkov.evokammer.agents.factory.RolePromptBuilder
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.CanonSnapshotExporter
import com.achulkov.evokammer.competition.disclosure.DisclosureStage
import com.achulkov.evokammer.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evokammer.competition.grader.ComparativePair
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.MetaJudge
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.competition.resourcemap.NodeClaimEvent
import com.achulkov.evokammer.competition.resourcemap.ResourceMapEngine
import com.achulkov.evokammer.competition.resourcemap.ResourceNode
import com.achulkov.evokammer.competition.resourcemap.ResourceMap
import com.achulkov.evokammer.competition.resourcemap.ResourceType
import com.achulkov.evokammer.competition.scoring.FailureTelemetry
import com.achulkov.evokammer.competition.scoring.GroupRoundResult
import com.achulkov.evokammer.competition.scoring.ScoringEngine
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.config.RunObjectiveHeuristics
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evokammer.core.contract.GroupCheckinResult
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.epigenetic.FitnessScorer
import com.achulkov.evokammer.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evokammer.orchestration.inspector.InspectorRunner
import com.achulkov.evokammer.orchestration.nested.NestedGroupConfig
import com.achulkov.evokammer.orchestration.nested.NestedGroupResult
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.orchestration.lifecycle.CriticPipeline
import com.achulkov.evokammer.orchestration.lifecycle.CriticSummary
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.selector.EvolutionarySelector
import com.achulkov.evokammer.orchestration.watchdog.Watchdog
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSignal
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evokammer.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeRefiner
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evokammer.substrate.tools.ToolMaterializer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val META_ARCHIVE_GROUP_ID = "__meta_archive__"
private const val MAX_RUN_ARTIFACT_PREVIEW_CHARS = 512
private const val ROUND_SUMMARY_MAX_ATTEMPTS = 2
private const val FINAL_DELIVERABLE_MAX_ATTEMPTS = 3
private const val DEFAULT_MULTI_GRADER_GROUP_COUNT = 3
private data class MaterializedRunInputArtifacts(
    val artifacts: List<RunInputArtifact>,
    val allowedHosts: Set<String>
)

@Serializable
private data class RoundSummaryRecord(
    val runId: String,
    val roundIndex: Int,
    val roundWinnerId: String?,
    val judgeOutputsByGrader: Map<String, String>,
    val groupSummaries: List<RoundGroupSummaryRecord>,
    val generatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Durable completion payload stored in the run meta-archive.
 *
 * The completion marker is intentionally the single source of truth for the final answer card,
 * judge outputs, and submission summaries after a run finishes.
 */
@Serializable
data class RunCompletionRecord(
    val runId: String,
    val winnerId: String?,
    val finalAnswerSummary: String = "",
    val finalDeliverableHtml: String? = null,
    val generatedOutputFiles: List<GeneratedOutputFile> = emptyList(),
    val requestedOutputSpec: FinalOutputSpec? = null,
    val judgeOutputsByGrader: Map<String, String> = emptyMap(),
    val submissionSummariesByGroup: Map<String, String?> = emptyMap(),
    val completedAtMs: Long = System.currentTimeMillis()
)

/**
 * Canonical generated file captured from the final output writer.
 *
 * Stored in the run completion record and copied into downloadable result bundles so follow-up
 * pipeline stages and operators can retrieve a stable artifact path without scraping summaries.
 *
 * @param relativePath Relative path under the run's `outputs/` directory.
 * @param mediaType MIME type of the generated file.
 * @param content Authored file content extracted from the final output writer.
 */
@Serializable
data class GeneratedOutputFile(
    val relativePath: String,
    val mediaType: String,
    val content: String
)

@Serializable
private data class RoundGroupSummaryRecord(
    val runId: String,
    val roundIndex: Int,
    val groupId: String,
    val criticPromoted: Int,
    val criticRejected: Int,
    val criticArbitrated: Int,
    val fitnessScoredAgents: Int,
    val fitnessTopEligibleAgents: Int,
    val legacyProtocolsExtracted: Int,
    val legacyProtocolsExpired: Int,
    val deadEndsInserted: Int,
    val submissionSummary: String? = null,
    val deterministicScore: DeterministicAssessmentRecord,
    val disclosureCheckin: DisclosureCheckinRecord,
    val blindScore: BlindScoreRecord
)

@Serializable
private data class DeterministicAssessmentRecord(
    val overallScore: Double,
    val automatedTestsPassed: Int,
    val automatedTestsFailed: Int,
    val mandatoryFailure: Boolean,
    val gradingSheetApplied: String,
    val failedTestIds: List<String>,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class DisclosureCheckinRecord(
    val overallScore: Double,
    val passed: Boolean,
    val testPassRate: Double,
    val failedTestCount: Int,
    val gradingSheetApplied: String,
    val stageAdvanced: Boolean,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class BlindScoreRecord(
    val weightedTotal: Double,
    val rank: Int,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class SubstrateRoundAssessment(
    val dimensionScores: List<DimensionScore>,
    val overallScore: Double,
    val kbCount: Int,
    val kgCount: Int,
    val toolCount: Int,
    val toolSuccessRate: Double,
    val completedWipTasks: Int,
    val totalWipTasks: Int
)

@Serializable
private data class EpigeneticRoundSummary(
    val fitnessScoredAgents: Int,
    val fitnessTopEligibleAgents: Int,
    val legacyProtocolsExtracted: Int,
    val legacyProtocolsExpired: Int,
    val deadEndsInserted: Int
)

/**
 * Real in-process implementation of [EvoKammerEngineService].
 *
 * Manages the full run lifecycle:
 * 1. Group setup and LifecycleManager creation per group
 * 2. GroupCoordinator for inter-group mechanics (leak oracle, dispatch relay, scoring)
 * 3. Round loop: start → CriticPipeline → trust decay → check-in → evaluation → score emission
 * 4. ProgressiveDisclosureEngine stage gating per group
 * 5. RunArchiveService archival on completion
 * 6. Event emission to subscribed UI clients
 *
 * @param lifecycleManagerProvider Factory lambda for creating [LifecycleManager] instances.
 * @param groupCoordinatorProvider Factory lambda for creating [GroupCoordinator] instances.
 * @param graderGroupManagerProvider Factory lambda for creating [GraderGroupManager] instances.
 * @param substrateFactory [SubstrateDatabaseFactory] for per-group DB access.
 * @param archiveService Optional [RunArchiveService] — if provided, archives the run on completion.
 * @param criticModelOverride Optional model override for [CriticPipeline] review agents.
 * @param trustDecayFactor Per-round trust decay multiplier applied to all trust scores after each round (default: 0.97).
 * @param disclosureStages Optional list of [DisclosureStage]s for progressive task disclosure; if null, disclosure is disabled.
 */
class EvoKammerRunService(
    private val lifecycleManagerProvider: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String,
        groupProfile: GroupProfile
    ) -> LifecycleManager,
    private val groupCoordinatorProvider: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>
    ) -> GroupCoordinator,
    private val groupCoordinatorProviderWithKey: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>,
        openRouterApiKey: String
    ) -> GroupCoordinator = { groupIds, dimensions, _ ->
        groupCoordinatorProvider(groupIds, dimensions)
    },
    private val graderGroupManagerProvider: (
        dimensions: List<ScoringDimension>
    ) -> GraderGroupManager,
    private val substrateFactory: SubstrateDatabaseFactory,
    /** Shuru VM manager used for pre-flight availability check and referenced by LifecycleManagers. */
    private val shuruVmManager: ShuruVmManager = ShuruVmManager(),
    private val archiveService: RunArchiveService? = null,
    private val criticModelOverride: String? = null,
    private val trustDecayFactor: Double = 0.97,
    private val disclosureStages: List<DisclosureStage>? = null,
    /** How often to emit heartbeat progress events while agents are running (default: 30 seconds). */
    private val roundHeartbeatIntervalMs: Long = 30_000L,
    /** How often the Inspector scans event logs for anomalies (default: 60 seconds). */
    private val inspectorCheckIntervalMs: Long = 60_000L,
    /**
     * Optional Genesis Council runner.
     *
     * If provided, called at the start of each run to produce a [StartingField] that configures
     * the model assignments, role prompts, scoring dimensions, and KG schema for the run.
     * If null (or if it fails), defaults to [StartingField.default()].
     *
     * The callback signature: (runId, objective, openRouterApiKey, onProgress?) → StartingField
     * - onProgress is called with human-readable progress strings as the council runs
     *
     * Maximum time: 8 minutes; times out to defaults on expiry.
     */
    private val genesisCouncilRunner: (suspend (runId: String, objective: String, openRouterApiKey: String, onProgress: ((String) -> Unit)?) -> StartingField)? = null,
    /**
     * Optional Grader Round runner.
     *
     * If provided, called post-round (after competing agents complete, before substrate scoring)
     * to run the Grader Group AI civilisation that evaluates each competing group.
     *
     * The callback signature: (runId, graderGroupIds, competingGroupIds, roundIndex,
     *   runObjective, scoringDimensions, graderGroupManager, onProgress?) → Map<String, String>
     *
     * If null or if the grader run fails, falls back to substrate-based scoring only.
     */
    private val graderRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional Grader Criteria runner — Phase A of the new grader architecture.
     *
     * If provided, called at round START (concurrent with main group agents) to develop
     * grading criteria for the round. Criteria agents run during the round and write
     * their rubric to the grader group staging DB.
     *
     * Signature: (runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective,
     *   scoringDimensions, onProgress?) → Unit
     *
     * When both [graderCriteriaRunner] and [graderJudgingRunner] are provided, the two-phase
     * architecture is active: criteria agents run concurrently during the round, and the
     * RoundJudge scores post-round using those criteria.
     *
     * If null, the legacy [graderRunner] is used post-round (backward compat).
     */
    private val graderCriteriaRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional Grader Judging runner — Phase B of the new grader architecture.
     *
     * If provided, called post-round (after CriticPipeline) to run the RoundJudge, which
     * reads criteria from the grader staging DB and scores all competing groups.
     *
     * If null, the legacy [graderRunner] is used post-round (backward compat).
     */
    private val graderJudgingRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)?
    ) -> Map<String, String>)? = null,
    /**
     * Optional Grader Criteria reviewer — reviews criteria submissions before they are used.
     *
     * If provided, called after the criteria phase completes and before judging begins.
     * This lets tests or custom runtimes substitute a reviewer while the default path
     * still uses the built-in CriticPipeline-based review.
     */
    private val graderCriteriaReviewRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional MetaPlanner runner.
     *
     * When provided, round-level stasis signals are fed into the runner and it can spawn
     * a MetaPlanner agent through the relevant group's LifecycleManager once the threshold
     * is exceeded.
     */
    private val metaPlannerRunner: MetaPlannerRunner? = null
) : EvoKammerEngineService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** JSON instance for serialising [RunConfig] into the meta-archive. Lenient for future field additions. */
    private val runConfigJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 0, extraBufferCapacity = 512)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    @Volatile private var activeRunId: String? = null
    @Volatile private var currentStatus: EngineStatus = EngineStatus.IDLE
    @Volatile private var currentRound: Int? = null
    @Volatile private var groupSummaries: List<GroupSummary> = emptyList()
    /** Preserved across cleanup so meta-archive can be queried after run completion. */
    @Volatile private var lastRunId: String? = null
    @Volatile private var lastRunGroupIds: List<String> = emptyList()

    private val liveLifecycleManagers = mutableMapOf<String, LifecycleManager>()
    private var activeCoordinator: GroupCoordinator? = null
    private var activeGraderGroupManager: GraderGroupManager? = null
    private var activeDisclosureEngine: ProgressiveDisclosureEngine? = null
    /** Resource-map engine that turns round outcomes into node claim/recapture events. */
    private var activeResourceMapEngine: ResourceMapEngine? = null
    /** Durable deliverable tree store for per-group skeleton state. */
    private val deliverableSkeletonTreeStore = DeliverableSkeletonTreeStore(substrateFactory)
    /** Durable Overseer kernel state store for recursive self-improvement metadata. */
    private val overseerKernelService = OverseerKernelService(substrateFactory)
    /** Mirror of Overseer audit entries in the project meta-archive for evidence queries. */
    private val overseerAuditArchiveJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
    /** Dedicated scoring boundary for blind tournament gate/reveal lifecycle. */
    private var activeBlindTournamentService: BlindTournamentService? = null
    /** Deterministic evaluator that binds the sealed suite to canonical snapshots. */
    private val sealedTestSuiteEvaluator = SealedTestSuiteEvaluator()
    /** Model assignments from Genesis Council (role name → model string). Applied as fallback. */
    @Volatile private var activeModelAssignments: Map<String, String> = emptyMap()
    /** Candidate model pools from Genesis Council (role name → ordered list of model IDs). */
    @Volatile private var activeModelPools: Map<String, List<String>> = emptyMap()
    /** Active group profile order for the current run, aligned with [lastRunGroupIds]. */
    @Volatile private var activeGroupProfiles: List<GroupProfileType> = emptyList()
    /** Role prompts from Genesis Council (role name → system prompt). Applied each round spawn. */
    @Volatile private var activeRolePrompts: Map<String, String> = emptyMap()
    /** Max rounds from StartingField.generationCeiling (caps config.rounds in the run loop). */
    @Volatile private var activeGenerationCeiling: Int = 100
    /** Global budget ceiling from StartingField (used by BudgetCritical pause logic). */
    @Volatile private var activeGlobalBudgetCeiling: Double = 0.0
    /** Canonical sealed test suite JSON emitted by the Genesis Council. */
    @Volatile private var activeSealedTestSuiteJson: String = ""
    /** Canonical sealed test suite hash emitted by the Genesis Council. */
    @Volatile private var activeSealedTestSuiteHash: String = ""
    /** Dedicated grader-civilisation seed resolved for the active run. */
    @Volatile private var activeGraderStartingField: StartingField? = null
    /** Council-defined score normalization policy keyed by scoring dimension ID. */
    @Volatile private var activeScoreNormalization: Map<String, Double> = emptyMap()
    /**
     * Latest per-group dimension scores computed from live substrate signals.
     * Populated each round and consumed by [processDisclosureCheckIns] (A-3).
     */
    private val latestGroupDimensionScores = ConcurrentHashMap<String, Map<String, Double>>()
    /** Latest live substrate assessment per group. */
    private val latestGroupSubstrateAssessments = ConcurrentHashMap<String, SubstrateRoundAssessment>()
    /** Latest deterministic sealed-suite assessment per group. */
    private val latestGroupRoundAssessments = ConcurrentHashMap<String, DeterministicRoundAssessment>()
    /** Per-group stasis flags set by Watchdog signal collection. Reset each round. */
    private val stasisFlags = ConcurrentHashMap<String, Boolean>()
    /** WiP completions observed during the current round, grouped by owning group. */
    private val pendingWipCompletions = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    /** Groups that have already spawned an Auditor during this run. */
    private val auditorSpawnedGroups = ConcurrentHashMap.newKeySet<String>()
    /** Groups that already consumed the narrow nested-hierarchy trigger during this run. */
    private val nestedHierarchyTriggeredGroups = ConcurrentHashMap.newKeySet<String>()
    /** Testable planner for deriving the first production nested-hierarchy trigger. */
    private val nestedHierarchySpawnPlanner = NestedHierarchySpawnPlanner()
    /** Watchdog instance for the active run (null when no run is active). */
    private var activeWatchdog: Watchdog? = null
    /** Inspector runner for the active run (null when no run is active). */
    private var activeInspectorRunner: InspectorRunner? = null
    /**
     * Canonical operator-facing output spec for runs started by this service.
     *
     * Kept after completion so `getResultsBundle()` can still recover the declared output
     * contract even if the persisted state file has been removed or is stale.
     */
    private val runFinalOutputSpecs = ConcurrentHashMap<String, FinalOutputSpec>()
    /**
     * KG schema JSON from the Genesis Council's [StartingField].
     * Threaded into [CriticPipeline] each round so KG_FACT submissions can be
     * validated against the council-designed schema (B-1).
     */
    @Volatile private var activeKgSchema: String = "{}"
    /**
     * B-5: Round index for which the check-in gate is currently open, or null if closed.
     * Set by [WatchdogSignal.CheckInGateOpen] and cleared by [WatchdogSignal.CheckInGateClosed].
     * [processDisclosureCheckIns] only runs when this matches the current round.
     */
    @Volatile private var checkInGateOpenRound: Int? = null

    private data class ActivatedRunInfrastructure(
        val coordinator: GroupCoordinator,
        val graderGroupManager: GraderGroupManager,
        val sheetRegisteredRounds: MutableSet<Int>
    )

    private data class ResumeBootstrapPlan(
        val persistedState: PersistedRunState?,
        val startRoundIndex: Int,
        val replayRoundIndex: Int?
    )

    private fun persistedRunStateFile(runId: String): File =
        File(substrateFactory.runControlDir(runId), PersistedRunStateStore.STATE_FILE_NAME)

    private fun readPersistedRunState(runId: String): PersistedRunState? =
        PersistedRunStateStore.read(persistedRunStateFile(runId))

    private fun writePersistedRunState(state: PersistedRunState) {
        PersistedRunStateStore.write(persistedRunStateFile(state.runId), state)
    }

    private fun resolveGroupIds(startingField: StartingField, fallbackGroupCount: Int): List<String> =
        startingField.ringTopology.takeIf { it.size == fallbackGroupCount }
            ?: (0 until fallbackGroupCount).map { "group-$it" }

    private fun resolveRecoveryGroupProfiles(
        config: RunConfig,
        startingField: StartingField,
        groupIds: List<String>
    ): List<GroupProfileType> = groupIds.mapIndexed { index, groupId ->
        startingField.groupProfiles[groupId]
            ?: config.groupProfiles.getOrElse(index) {
                config.groupProfiles.firstOrNull() ?: GroupProfileType.Library
            }
    }

    private suspend fun loadSealedStartingFieldSnapshot(runId: String): StartingField? = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val stored = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
        }.getOrElse { emptyList() }
            .firstOrNull { it.entryType == RunArchiveService.ENTRY_STARTING_FIELD }
            ?: return@withContext null
        runCatching {
            runConfigJson.decodeFromString(StartingField.serializer(), stored.contentJson)
        }.getOrNull()
    }

    private suspend fun activateRunInfrastructure(
        config: RunConfig,
        startingField: StartingField,
        groupIds: List<String>,
        groupProfiles: List<GroupProfileType>,
        graderGroupIds: List<String>,
        allowedHosts: Set<String>,
        emitRunStartedEvent: Boolean
    ): ActivatedRunInfrastructure {
        activeModelAssignments = startingField.modelAssignment
        activeModelPools = startingField.modelPools
        activeRolePrompts = startingField.rolePrompts
        activeGenerationCeiling = startingField.generationCeiling
        activeGlobalBudgetCeiling = startingField.globalBudgetCeiling
        activeKgSchema = startingField.kgSchema
        activeScoreNormalization = startingField.scoreNormalization
        activeSealedTestSuiteJson = startingField.sealedTestSuiteJson
        activeSealedTestSuiteHash = startingField.sealedTestSuiteHash
        activeGroupProfiles = groupProfiles
        lastRunId = config.runId
        lastRunGroupIds = groupIds
        groupSummaries = groupIds.mapIndexed { idx, gid ->
            GroupSummary(
                groupId = gid,
                profileType = activeGroupProfiles.getOrElse(idx) { GroupProfileType.Library }.name,
                activeAgents = 0,
                remainingBudget = GroupProfile.default(activeGroupProfiles.getOrElse(idx) { GroupProfileType.Library }).spawnBudget *
                    config.maxAgentsPerGroup,
                currentRoundScore = null
            )
        }
        Napier.i("EvoKammerRunService: initialised ${groupSummaries.size} group summaries for run ${config.runId}")

        if (emitRunStartedEvent) {
            emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
            Napier.i("EvoKammerRunService: emitted RunStarted for run ${config.runId}")
        }

        val coordinator = groupCoordinatorProviderWithKey(groupIds, config.scoringDimensions, config.openRouterApiKey)
        activeCoordinator = coordinator
        val graderGroupManager = graderGroupManagerProvider(config.scoringDimensions)
        activeGraderGroupManager = graderGroupManager
        activeResourceMapEngine = ResourceMapEngine(
            resourceMap = ResourceMap(deriveResourceNodes(startingField.nodeGraph))
        )
        val blindTournamentService = BlindTournamentService(
            onFailureTelemetry = { telemetry ->
                routeFailureTelemetry(config.runId, graderGroupIds, telemetry)
            },
            onDeliverableNodeUpdates = { updates ->
                for (update in updates) {
                    val targetRunId = config.runId
                    val targetGroupId = update.groupId ?: continue
                    runCatching {
                        deliverableSkeletonTreeStore.applyUpdate(targetRunId, targetGroupId, update)
                    }.onFailure { e ->
                        Napier.w(e) { "EvoKammerRunService: failed to apply deliverable skeleton update for node ${update.dstNodeRef} group $targetGroupId" }
                    }
                }
            }
        ).also { it.start(groupIds) }
        activeBlindTournamentService = blindTournamentService
        val sheetRegisteredRounds = mutableSetOf<Int>()

        groupIds.forEachIndexed { idx, groupId ->
            val profileType = activeGroupProfiles.getOrElse(idx) { activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library }
            val lm = lifecycleManagerProvider(
                config.runId, groupId, config.openRouterApiKey, resolveAgentJarPath(),
                GroupProfile.default(profileType)
            )
            lm.configureRunArtifacts(
                inputArtifacts = startingField.inputArtifacts,
                artifactsMountPath = if (startingField.inputArtifacts.isNotEmpty()) resolveRunArtifactsRoot(config.runId).absolutePath else null,
                initialContextAllowedHosts = allowedHosts
            )
            lm.onAgentSpawned = { agentId, role, modelId ->
                scope.launch {
                    emit(EvoKammerEvent.AgentSpawned(config.runId, groupId, agentId, role))
                    Napier.i("[$groupId] Agent spawned: $agentId (${role.name}) model=$modelId")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "agent_spawned",
                        "[$groupId] ${role.name} agent $agentId started (model=$modelId)"))
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(activeAgents = s.activeAgents + 1) else s
                    }
                }
            }
            lm.onAgentDied = { agentId, wasKilled ->
                scope.launch {
                    val reason = if (wasKilled) "killed" else "completed"
                    emit(EvoKammerEvent.AgentDied(config.runId, groupId, agentId, reason))
                    Napier.i("[$groupId] Agent died: $agentId ($reason)")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "agent_died",
                        "[$groupId] Agent $agentId $reason"))
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(activeAgents = maxOf(0, s.activeAgents - 1)) else s
                    }
                }
            }
            lm.onBudgetChanged = { totalRemaining ->
                scope.launch {
                    emit(EvoKammerEvent.GroupBudgetChanged(config.runId, groupId, totalRemaining))
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(remainingBudget = totalRemaining) else s
                    }
                }
            }
            lm.onSpawnFailed = { agentId, role, error ->
                scope.launch {
                    val msg = error.message ?: "unknown error"
                    emit(EvoKammerEvent.AgentSpawnFailed(config.runId, groupId, agentId, role, msg))
                    Napier.e("[$groupId] Agent spawn failed: $agentId (${role.name}) — $msg")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "spawn_error",
                        "[$groupId] Failed to spawn ${role.name} agent: $msg"))
                }
            }
            lm.onWipTaskAbandoned = { agentId, nodeId, generation ->
                scope.launch {
                    emit(EvoKammerEvent.EngineProgress(
                        config.runId,
                        "wip_abandoned",
                        "[$groupId] WiP task $nodeId abandoned by $agentId at generation $generation"
                    ))
                }
            }
            liveLifecycleManagers[groupId] = lm
        }

        val resolvedDisclosureStages = buildDisclosureStages(startingField)
        activeDisclosureEngine = resolvedDisclosureStages.takeIf { it.isNotEmpty() }?.let { stages ->
            ProgressiveDisclosureEngine(stages = stages).also { engine ->
                groupIds.forEach { engine.registerGroup(it) }
                Napier.i("EvoKammerRunService: ProgressiveDisclosureEngine initialised with ${stages.size} stages")
            }
        }

        scope.launch {
            try {
                val shuruCheck = shuruVmManager.ensureInstalled()
                Napier.i("EvoKammerRunService: Shuru check — available=${shuruCheck.available} version=${shuruCheck.version} msg=${shuruCheck.message}")
                emit(EvoKammerEvent.ShuruStatus(
                    available = shuruCheck.available,
                    version = shuruCheck.version,
                    message = shuruCheck.message
                ))
                if (!shuruCheck.available) {
                    val needsShuru = liveLifecycleManagers.values.any { !it.usesInProcessMode }
                    if (needsShuru) {
                        val errorMsg = "Shuru is not available: ${shuruCheck.message}"
                        Napier.e("EvoKammerRunService: $errorMsg — aborting (Shuru VM agents are configured)")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "shuru_unavailable", errorMsg))
                        emit(EvoKammerEvent.RunAborted(config.runId, "shuru_unavailable: ${shuruCheck.message}"))
                        currentStatus = EngineStatus.IDLE
                        updatePersistedRunStatus(config.runId, PersistedRunStatus.ABORTED)
                        cleanup(config.runId)
                    } else {
                        Napier.i("EvoKammerRunService: Shuru unavailable but all lifecycle managers use in-process mode — continuing without Shuru")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "shuru_skipped", "Shuru unavailable — using in-process agent mode"))
                    }
                }
            } catch (e: Exception) {
                Napier.e("EvoKammerRunService: Shuru pre-flight check threw an exception", e)
                emit(EvoKammerEvent.ShuruStatus(
                    available = false,
                    version = null,
                    message = "Shuru check exception: ${e.message}"
                ))
            }
        }

        val watchdog = Watchdog(
            substrateDatabaseFactory = substrateFactory,
            runId = config.runId
        )
        activeWatchdog = watchdog
        watchdog.start(scope)

        val inspector = InspectorRunner(watchdog = watchdog, checkIntervalMs = inspectorCheckIntervalMs)
        activeInspectorRunner = inspector
        stasisFlags.clear()

        groupIds.forEach { gid ->
            inspector.startFor(gid, scope) {
                try {
                    substrateFactory.get(config.runId, gid)
                        .eventLogDao()
                        .queryFiltered(config.runId, gid, null, null, null, null, 200, 0)
                        .map { it.eventType + ":" + it.payloadJson }
                } catch (_: Exception) { emptyList() }
            }
        }

        scope.launch {
            watchdog.signals.collect { signal ->
                handleWatchdogSignal(config, signal, groupIds)
            }
        }

        return ActivatedRunInfrastructure(
            coordinator = coordinator,
            graderGroupManager = graderGroupManager,
            sheetRegisteredRounds = sheetRegisteredRounds
        )
    }

    private fun updatePersistedRunState(runId: String, transform: (PersistedRunState) -> PersistedRunState) {
        val current = readPersistedRunState(runId) ?: return
        writePersistedRunState(
            transform(current).copy(lastUpdatedAtMs = System.currentTimeMillis())
        )
    }

    private fun updatePersistedRunStatus(runId: String, status: PersistedRunStatus) {
        updatePersistedRunState(runId) { current ->
            current.copy(status = status)
        }
    }

    private suspend fun buildResumeBootstrapPlan(
        config: RunConfig,
        groupIds: List<String>
    ): ResumeBootstrapPlan {
        val persistedState = readPersistedRunState(config.runId)
        if (persistedState != null) {
            val replayRoundIndex = persistedState.activeRoundIndex
            val startRoundIndex = replayRoundIndex ?: persistedState.nextRoundIndex
            return ResumeBootstrapPlan(
                persistedState = persistedState,
                startRoundIndex = startRoundIndex,
                replayRoundIndex = replayRoundIndex
            )
        }

        val archiveDb = substrateFactory.get(config.runId, META_ARCHIVE_GROUP_ID)
        val lastCompletedRound = runCatching {
            archiveDb.metaArchiveDao().getByRun(config.runId)
                .asSequence()
                .filter { it.entryType == RunArchiveService.ENTRY_ROUND_SUMMARY }
                .maxOfOrNull { it.generation }
        }.getOrNull()

        val maxObservedGeneration = groupIds.maxOfOrNull { groupId ->
            val db = substrateFactory.get(config.runId, groupId)
            listOfNotNull(
                runCatching { db.eventLogDao().getMaxGeneration(config.runId, groupId) }.getOrNull(),
                runCatching { db.stagingDao().getMaxGeneration(config.runId, groupId) }.getOrNull()
            ).maxOrNull() ?: -1
        } ?: -1

        val replayRoundIndex = when {
            maxObservedGeneration > (lastCompletedRound ?: -1) -> maxObservedGeneration
            else -> null
        }
        val startRoundIndex = replayRoundIndex ?: ((lastCompletedRound ?: -1) + 1)
        return ResumeBootstrapPlan(
            persistedState = null,
            startRoundIndex = startRoundIndex,
            replayRoundIndex = replayRoundIndex
        )
    }

    private suspend fun sanitizeReplayRound(
        runId: String,
        groupIds: List<String>,
        graderGroupIds: List<String>,
        replayRoundIndex: Int
    ) {
        suspend fun sanitizeGroup(groupId: String) {
            val db = substrateFactory.get(runId, groupId)
            db.stagingDao().getPendingByGeneration(runId, groupId, replayRoundIndex).forEach { pending ->
                db.stagingDao().updateReview(
                    id = pending.id,
                    status = "rejected",
                    criticId = "resume-recovery",
                    trustScore = pending.initialTrustScore,
                    reason = "recovery_replay_round_$replayRoundIndex",
                    reviewedAtMs = System.currentTimeMillis()
                )
            }
            db.wipTaskDao().releaseClaimsFromGeneration(runId, groupId, replayRoundIndex)
        }

        groupIds.forEach { sanitizeGroup(it) }
        graderGroupIds.forEach { sanitizeGroup(it) }
    }

    override suspend fun startRun(config: RunConfig): String = mutex.withLock {
        check(activeRunId == null) { "Run $activeRunId is already active — abort it first" }

        Napier.i("EvoKammerRunService: starting run ${config.runId} groups=${config.groupCount} rounds=${config.rounds}")
        activeRunId = config.runId
        config.finalOutputSpec?.let { runFinalOutputSpecs[config.runId] = it } ?: runFinalOutputSpecs.remove(config.runId)
        currentStatus = EngineStatus.GENESIS

        currentRound = null

        val continuationSourceRunId = config.continuationFromRunId?.trim()?.takeIf { it.isNotBlank() }
        val continuationSourceStartingField = continuationSourceRunId?.let { sourceRunId ->
            loadSealedStartingField(sourceRunId)
                ?: failStartup(config.runId, "continuation source run=$sourceRunId does not have a sealed StartingField")
        }
        if (continuationSourceStartingField != null) {
            if (config.isTestRun && continuationSourceStartingField.groupCount != 1) {
                failStartup(
                    config.runId,
                    "test-run continuation requires a single-group source run, but ${continuationSourceStartingField.runId} has ${continuationSourceStartingField.groupCount} groups"
                )
            }
            emit(
                EvoKammerEvent.EngineProgress(
                    config.runId,
                    "continuation_start",
                    "Continuing from completed run ${continuationSourceStartingField.runId}"
                )
            )
            Napier.i(
                "EvoKammerRunService: continuing run ${config.runId} from source run ${continuationSourceStartingField.runId}"
            )
        }

        // ── Genesis Council ───────────────────────────────────────────────────
        // Run the Genesis Council before setting up groups. The council designs the run:
        // model assignments, role prompts, scoring dimensions, KG schema, etc.
        // Falls back to StartingField.default() if no runner is wired or if it fails/times out.
        //
        // TEST RUN SHORT-CIRCUIT: When config.isTestRun is true, skip the Genesis Council
        // entirely and use a minimal StartingField with a fixed budget. This lets developers
        // verify the agent pipeline (tasks, KB, death notes, substrate) without spending real
        // LLM budget on council orchestration.
        // B-7: Track whether the council actually ran (not just whether a runner is wired).
        // `usedDefault = true` only when the council runner was absent, timed out, or threw.
        var councilProducedField = false
        val sourceGroupCount = continuationSourceStartingField?.groupCount ?: config.groupCount
        var startingField: StartingField = when {
            continuationSourceStartingField != null -> buildContinuationStartingField(
                source = continuationSourceStartingField,
                config = config
            )
            config.isTestRun -> {
                Napier.i("EvoKammerRunService: TEST RUN — skipping Genesis Council, using minimal StartingField (budget=${config.budgetOverride ?: 200.0})")
                emit(EvoKammerEvent.EngineProgress(config.runId, "genesis_skipped",
                    "Test run — Genesis Council bypassed. Using fixed defaults (budget=${config.budgetOverride ?: 200.0})."))
                val testGroupProfiles = config.groupProfiles.mapIndexed { idx, pt ->
                    "group-$idx" to pt
                }.toMap()
                StartingField.default(
                    objective = config.objective,
                    runId = config.runId,
                    groupCount = config.groupCount,
                    groupProfilesMap = testGroupProfiles,
                    budgetCeiling = config.budgetOverride ?: 200.0,
                    generationCeiling = config.rounds
                )
            }
            genesisCouncilRunner != null -> {
                Napier.i("EvoKammerRunService: starting Genesis Council for run ${config.runId}")
                emit(EvoKammerEvent.EngineProgress(config.runId, "genesis", "Genesis Council starting — designing this run…"))
                try {
                    withTimeoutOrNull(8 * 60_000L) {
                        genesisCouncilRunner.invoke(config.runId, config.objective, config.openRouterApiKey) { progressMsg ->
                            scope.launch {
                                emit(EvoKammerEvent.EngineProgress(config.runId, "genesis_agent", progressMsg))
                                Napier.i("GenesisCouncil: $progressMsg")
                            }
                        }
                    }?.also { councilProducedField = true } ?: run {
                        Napier.w("EvoKammerRunService: Genesis Council timed out after 8 minutes, using default StartingField")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "genesis_timeout", "Genesis Council timed out — using default run configuration"))
                        StartingField.default(config.objective, config.runId)
                    }
                } catch (e: Exception) {
                    Napier.w("EvoKammerRunService: Genesis Council error: ${e.message}, using default StartingField")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "genesis_error", "Genesis Council error: ${e.message} — using defaults"))
                    StartingField.default(config.objective, config.runId)
                }
            }
            else -> {
                Napier.i("EvoKammerRunService: no Genesis Council runner configured — using default StartingField")
                StartingField.default(config.objective, config.runId)
            }
        }
        startingField = applyModelOverrides(canonicalizeStartingField(startingField), config.modelOverrides)
        if (continuationSourceRunId != null) {
            copyContinuationArtifacts(
                sourceRunId = continuationSourceRunId,
                targetRunId = config.runId,
                sourceArtifacts = continuationSourceStartingField?.inputArtifacts.orEmpty()
            )
        }
        val uploadedArtifacts = materializeRunInputArtifacts(config.runId, config.contextArtifacts)
        val mergedInputArtifacts = mergeInputArtifacts(
            startingField.inputArtifacts,
            uploadedArtifacts.artifacts
        )
        if (mergedInputArtifacts.isNotEmpty()) {
            startingField = startingField.copy(inputArtifacts = mergedInputArtifacts)
        }
        val graderStartingField = resolveGraderStartingField(
            config = config,
            workerStartingField = startingField
        )
        activeGraderStartingField = graderStartingField
        startingField = startingField.copy(graderGroupCount = graderStartingField?.groupCount ?: 0)
        activeSealedTestSuiteJson = startingField.sealedTestSuiteJson
        activeSealedTestSuiteHash = startingField.sealedTestSuiteHash
        val councilModelSummary = when {
            startingField.modelAssignment.isNotEmpty() ->
                startingField.modelAssignment.entries.joinToString(", ") { "${it.key}→${it.value}" }
            startingField.modelPools.isNotEmpty() ->
                startingField.modelPools.entries.joinToString(", ") { (role, pool) ->
                    "$role→[${pool.joinToString(", ")}]"
                }
            else -> "defaults"
        }
        Napier.i("EvoKammerRunService: Genesis Council complete — models=[$councilModelSummary]")
        emit(EvoKammerEvent.EngineProgress(config.runId, "genesis_complete",
            "Genesis Council complete — ${startingField.groupCount} groups, models: $councilModelSummary"))
        // Emit rich Genesis Council summary event for the Log Viewer / Dashboard.
        emit(EvoKammerEvent.GenesisCouncilCompleted(
            runId = config.runId,
            groupCount = startingField.groupCount,
            modelAssignment = startingField.modelAssignment,
            rolePromptSummary = startingField.rolePrompts.mapValues { (_, v) ->
                v.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: "(empty)"
            },
            stageCount = startingField.stageContents.size,
            scoringDimensionIds = config.scoringDimensions.map { it.id },
            kgSchemaNodeCount = if (startingField.kgSchema == "{}") 0
                else startingField.kgSchema.split("\"type\"").size - 1,
            primordialKbCount = startingField.primordialKb.size,
            primordialFactCount = startingField.primordialKgFacts.size,
            // B-7: `usedDefault` tracks whether the council runner produced the field.
            // A council that deliberately designs empty model assignments would previously
            // set usedDefault=true incorrectly; now it is false iff the council actually ran.
            usedDefault = !councilProducedField,
            // B-3: Surface the CouncilSynthesis consistency report in the log viewer / dashboard.
            consistencyReport = startingField.consistencyReport
        ))

        persistSealedStartingField(config.runId, startingField)
        Napier.i(
            "EvoKammerRunService: stored ${startingField.modelAssignment.size} model assignments, " +
                "${startingField.modelPools.size} model pools, and ${startingField.rolePrompts.size} role prompts " +
                "from Genesis Council (generationCeiling=${startingField.generationCeiling})"
        )

        // Build group IDs from the council's ring order when available so topology stays canonical.
        val groupIds = resolveGroupIds(startingField, sourceGroupCount)
        val groupProfiles = if (continuationSourceStartingField != null) {
            resolveRecoveryGroupProfiles(config, continuationSourceStartingField, groupIds)
        } else {
            config.groupProfiles
        }
        // Grader groups come from the dedicated grader-civilisation seed.
        val graderGroupIds = (0 until (graderStartingField?.groupCount ?: startingField.graderGroupCount)).map { "grader-$it" }

        if (startingField.sealedTestSuiteJson.isNotBlank()) {
            persistSealedTestSuite(config.runId, groupIds.firstOrNull(), startingField)
            verifySealedTestSuite(
                runId = config.runId,
                firstGroupId = groupIds.firstOrNull(),
                expectedHash = startingField.sealedTestSuiteHash
            )
        }

        // ── Seed primordial substrate data for each group ─────────────────
        // KB documents, KG facts, and WIP tasks from the Genesis Council are inserted
        // before the first round so agents have starting context and tasks to claim.
        // Grader groups receive the same seed so their KB is not empty at first eval.
        seedPrimordialData(
            runId = config.runId,
            groupIds = groupIds,
            workerStartingField = startingField,
            graderGroupIds = graderGroupIds,
            graderStartingField = graderStartingField
        )
        val activatedRun = activateRunInfrastructure(
            config = config,
            startingField = startingField,
            groupIds = groupIds,
            groupProfiles = groupProfiles,
            graderGroupIds = graderGroupIds,
            allowedHosts = uploadedArtifacts.allowedHosts,
            emitRunStartedEvent = true
        )
        writePersistedRunState(
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                allowedHosts = uploadedArtifacts.allowedHosts,
                nextRoundIndex = 0,
                activeRoundIndex = null,
                status = PersistedRunStatus.BOOTSTRAPPED
            )
        )

        // Launch run loop
        scope.launch {
            runLoop(
                config = config,
                groupIds = groupIds,
                coordinator = activatedRun.coordinator,
                graderGroupIds = graderGroupIds,
                graderGroupManager = activatedRun.graderGroupManager,
                sheetRegisteredRounds = activatedRun.sheetRegisteredRounds,
                initialRoundIndex = 0
            )
        }

        config.runId
    }

    override suspend fun resumePersistedRun(config: RunConfig): String = mutex.withLock {
        check(activeRunId == null) { "Run $activeRunId is already active — abort it first" }

        val startingField = loadSealedStartingFieldSnapshot(config.runId)
            ?: failStartup(config.runId, "persisted run ${config.runId} does not have a sealed StartingField snapshot")

        val archiveDb = substrateFactory.get(config.runId, META_ARCHIVE_GROUP_ID)
        val completed = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }.any { it.runId == config.runId }
        if (completed) {
            failStartup(config.runId, "persisted run ${config.runId} is already completed")
        }

        Napier.i("EvoKammerRunService: resuming persisted run ${config.runId}")
        activeRunId = config.runId
        config.finalOutputSpec?.let { runFinalOutputSpecs[config.runId] = it } ?: runFinalOutputSpecs.remove(config.runId)
        currentStatus = EngineStatus.GENESIS
        currentRound = null

        val groupIds = readPersistedRunState(config.runId)?.groupIds
            ?.takeIf { it.isNotEmpty() }
            ?: resolveGroupIds(startingField, config.groupCount)
        val groupProfiles = readPersistedRunState(config.runId)?.groupProfiles
            ?.takeIf { it.size == groupIds.size }
            ?: resolveRecoveryGroupProfiles(config, startingField, groupIds)
        val graderGroupIds = readPersistedRunState(config.runId)?.graderGroupIds
            ?.takeIf { it.isNotEmpty() }
            ?: (0 until startingField.graderGroupCount).map { "grader-$it" }
        val resumePlan = buildResumeBootstrapPlan(config, groupIds)
        if (resumePlan.replayRoundIndex != null) {
            sanitizeReplayRound(
                runId = config.runId,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                replayRoundIndex = resumePlan.replayRoundIndex
            )
        }

        val allowedHosts = resumePlan.persistedState?.allowedHosts?.toSet() ?: emptySet()
        val activatedRun = activateRunInfrastructure(
            config = config,
            startingField = startingField,
            groupIds = groupIds,
            groupProfiles = groupProfiles,
            graderGroupIds = graderGroupIds,
            allowedHosts = allowedHosts,
            emitRunStartedEvent = false
        )

        writePersistedRunState(
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                allowedHosts = allowedHosts,
                nextRoundIndex = resumePlan.startRoundIndex,
                activeRoundIndex = resumePlan.replayRoundIndex,
                status = PersistedRunStatus.RUNNING,
                bootstrappedAtMs = resumePlan.persistedState?.bootstrappedAtMs ?: System.currentTimeMillis()
            )
        )

        emit(
            EvoKammerEvent.EngineProgress(
                config.runId,
                "recovery_start",
                "Recovering persisted run ${config.runId} from round ${resumePlan.startRoundIndex}"
            )
        )

        scope.launch {
            runLoop(
                config = config,
                groupIds = groupIds,
                coordinator = activatedRun.coordinator,
                graderGroupIds = graderGroupIds,
                graderGroupManager = activatedRun.graderGroupManager,
                sheetRegisteredRounds = activatedRun.sheetRegisteredRounds,
                initialRoundIndex = resumePlan.startRoundIndex
            )
        }

        config.runId
    }

    override suspend fun pauseRun(runId: String) {
        requireActiveRun(runId)
        currentStatus = EngineStatus.PAUSED
        updatePersistedRunStatus(runId, PersistedRunStatus.PAUSED)
        Napier.i("EvoKammerRunService: paused run $runId")
    }

    override suspend fun resumeRun(runId: String) {
        requireActiveRun(runId)
        if (currentStatus == EngineStatus.PAUSED) {
            currentStatus = EngineStatus.RUNNING
            updatePersistedRunStatus(runId, PersistedRunStatus.RUNNING)
        }
        Napier.i("EvoKammerRunService: resumed run $runId")
    }

    override suspend fun abortRun(runId: String, reason: String): Unit = mutex.withLock {
        requireActiveRun(runId)
        Napier.w("EvoKammerRunService: aborting run $runId reason=$reason")
        updatePersistedRunStatus(runId, PersistedRunStatus.ABORTED)
        cleanup(runId)
        emit(EvoKammerEvent.RunAborted(runId, reason))
    }

    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? {
        return try {
            // Meta-archive records are written to the dedicated meta-archive substrate DB.
            val db = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
            val total = db.metaArchiveDao().count()
            val allEntries = db.metaArchiveDao().getByRun(runId)
            val byType = allEntries.groupingBy { it.entryType }.eachCount()
            MetaArchiveSummary(total = total, byType = byType)
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: failed to load meta-archive summary for $runId: ${e.message}")
            null
        }
    }

    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> {
        return try {
            val db = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
            val entries = db.metaArchiveDao().getByRun(runId)
            entries.asSequence()
                .filter { entryType == null || it.entryType == entryType }
                .map { entry ->
                    MetaArchiveEntryRecord(
                        id = entry.id,
                        runId = entry.runId,
                        entryType = entry.entryType,
                        groupId = entry.groupId,
                        label = entry.label,
                        contentJson = entry.contentJson,
                        runObjective = entry.runObjective,
                        finalScore = entry.finalScore,
                        isFromWinner = entry.isFromWinner,
                        generation = entry.generation,
                        chunkIndex = entry.chunkIndex,
                        totalChunks = entry.totalChunks,
                        archivedAtMs = entry.archivedAtMs
                    )
                }
                .toList()
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: failed to load meta-archive entries for $runId: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getResultsBundle(runId: String): ByteArray? {
        return try {
            val archive = archiveService ?: RunArchiveService(substrateFactory)
            val requestedOutputSpec = runFinalOutputSpecs[runId] ?: readPersistedRunState(runId)?.finalOutputSpec
            archive.buildResultsBundle(runId, requestedOutputSpec = requestedOutputSpec)
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: failed to build results bundle for $runId: ${e.message}")
            null
        }
    }

    override suspend fun getOverseerKernelState(): OverseerKernelState = overseerKernelService.currentState()

    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        overseerKernelService.preflightHypothesis(hypothesis)

    override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        overseerKernelService.preflightExperimentPlan(experimentPlan)

    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState {
        val result = overseerKernelService.formalizeHypothesisWithAudit(hypothesis)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState {
        val result = overseerKernelService.registerExperimentPlanWithAudit(experimentPlan)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState {
        val result = overseerKernelService.scheduleEligibleExperimentPlansWithAudit()
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun launchOverseerExperimentRun(
        experimentPlanId: String,
        launchRequest: OverseerExperimentLaunchRequest
    ): OverseerExperimentLaunchResponse {
        val currentState = overseerKernelService.currentState()
        val schedule = currentState.experimentSchedules[experimentPlanId]
            ?: error("No scheduled experiment plan found for '$experimentPlanId'.")
        require(launchRequest.scheduledRunId in schedule.scheduledRunIds) {
            "Scheduled run '${launchRequest.scheduledRunId}' is not registered for experiment plan '$experimentPlanId'."
        }
        require(launchRequest.runConfig.runId == launchRequest.scheduledRunId) {
            "Launch runId '${launchRequest.runConfig.runId}' must match scheduled run '${launchRequest.scheduledRunId}'."
        }

        val launchedRunId = startRun(launchRequest.runConfig)
        val launchRecord = overseerKernelService.launchExperimentRunWithAudit(experimentPlanId, launchRequest.scheduledRunId)
        mirrorOverseerAuditEntry(launchRecord.auditEntry)
        return OverseerExperimentLaunchResponse(
            experimentPlanId = experimentPlanId,
            scheduledRunId = launchRequest.scheduledRunId,
            runId = launchedRunId
        )
    }

    override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState {
        val result = overseerKernelService.recordExperimentResultWithAudit(experimentResult)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState {
        val result = overseerKernelService.recordDeadEndWithAudit(deadEnd)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> =
        overseerKernelService.getAuditEntries()

    override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> =
        overseerKernelService.getExperimentPlanHistory(experimentPlanId)

    override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> =
        overseerKernelService.getDeadEndHistory(hypothesisId)

    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(OverseerKernelArchive.RUN_ID, META_ARCHIVE_GROUP_ID)
        archiveDb.metaArchiveDao().getByType(OverseerKernelArchive.ENTRY_TYPE).map { entry ->
            MetaArchiveEntryRecord(
                id = entry.id,
                runId = entry.runId,
                entryType = entry.entryType,
                groupId = entry.groupId,
                label = entry.label,
                contentJson = entry.contentJson,
                runObjective = entry.runObjective,
                finalScore = entry.finalScore,
                isFromWinner = entry.isFromWinner,
                generation = entry.generation,
                chunkIndex = entry.chunkIndex,
                totalChunks = entry.totalChunks,
                archivedAtMs = entry.archivedAtMs
            )
        }
    }

    override suspend fun getState(): EngineState = EngineState(
        status = currentStatus,
        activeRunId = activeRunId,
        currentRound = currentRound,
        groupSummaries = groupSummaries
    )

    private suspend fun mirrorOverseerAuditEntry(
        auditEntry: OverseerKernelAuditEntry
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val archiveDb = substrateFactory.get(OverseerKernelArchive.RUN_ID, META_ARCHIVE_GROUP_ID)
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = auditEntry.entryId,
                    runId = OverseerKernelArchive.RUN_ID,
                    entryType = OverseerKernelArchive.ENTRY_TYPE,
                    groupId = null,
                    label = "Overseer audit — ${auditEntry.actionType.name}",
                    contentJson = overseerAuditArchiveJson.encodeToString(
                        OverseerKernelAuditEntry.serializer(),
                        auditEntry
                    ),
                    runObjective = "Recursive self-improvement control-plane evidence",
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = auditEntry.timestampMs
                )
            )
        }.onFailure { error ->
            Napier.w("EvoKammerRunService: failed to mirror Overseer audit entry to meta-archive", error)
        }
    }

    // ── Internal run loop ────────────────────────────────────────────────────

    private suspend fun runLoop(
        config: RunConfig,
        groupIds: List<String>,
        coordinator: GroupCoordinator,
        graderGroupIds: List<String>,
        graderGroupManager: GraderGroupManager,
        sheetRegisteredRounds: MutableSet<Int>,
        initialRoundIndex: Int
    ) {
        currentStatus = EngineStatus.RUNNING
        updatePersistedRunStatus(config.runId, PersistedRunStatus.RUNNING)
        val effectiveRounds = minOf(config.rounds, activeGenerationCeiling)
        if (effectiveRounds < config.rounds) {
            Napier.w("EvoKammerRunService: config.rounds=${config.rounds} exceeds generationCeiling=$activeGenerationCeiling — capping at $effectiveRounds")
            scope.launch {
                emit(EvoKammerEvent.EngineProgress(config.runId, "gen_ceiling_cap",
                    "Run capped at $effectiveRounds rounds by Genesis Council generationCeiling"))
            }
        }
        Napier.i("EvoKammerRunService: run loop started effectiveRounds=$effectiveRounds")

        try {
            var finalRoundJudgeOutputs: Map<String, String> = emptyMap()
            var finalRoundSubmissionSummaries: Map<String, String?> = emptyMap()
            var finalRoundWinnerId: String? = null
            var finalRoundIndex: Int = -1
            for (round in initialRoundIndex until effectiveRounds) {
                // Honour pause/abort before starting round
                while (currentStatus == EngineStatus.PAUSED) { delay(500) }
                if (currentStatus == EngineStatus.IDLE) {
                    Napier.i("EvoKammerRunService: run aborted before round $round")
                    return
                }

                currentRound = round
                updatePersistedRunState(config.runId) { current ->
                    current.copy(
                        status = PersistedRunStatus.RUNNING,
                        nextRoundIndex = round,
                        activeRoundIndex = round
                    )
                }
                coordinator.onRoundStart(round)
                emit(EvoKammerEvent.RoundStarted(config.runId, round))
                Napier.i("EvoKammerRunService: round $round started (duration=${config.roundDurationMinutes}m)")
                emit(EvoKammerEvent.EngineProgress(config.runId, "round_start", "Round $round started — spawning agents for ${groupIds.size} group(s)"))

                // ── Spawn agents for this round ──────────────────────────────
                spawnAgentsForRound(config, groupIds, round)

                // ── Launch grader criteria phase concurrently (Phase A) ───────
                // Criteria agents run IN PARALLEL with the main group agents.
                // They study the run objective + dimensions and develop the scoring rubric.
                // The job is collected so we can wait for it before the judging phase.
                val criteriaJob = if (graderCriteriaRunner != null && graderGroupIds.isNotEmpty()) {
                    scope.launch {
                        try {
                            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_criteria_start",
                                "Grader criteria agents starting (round $round) — developing scoring rubric…"))
                            graderCriteriaRunner.invoke(
                                config.runId,
                                config.openRouterApiKey,
                                graderGroupIds,
                                round,
                                config.objective,
                                config.scoringDimensions
                            ) { progressMsg ->
                                scope.launch {
                                    emit(EvoKammerEvent.EngineProgress(config.runId, "grader_criteria", progressMsg))
                                }
                            }
                            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_criteria_done",
                                "Grader criteria phase complete (round $round)"))
                        } catch (e: Exception) {
                            Napier.w("EvoKammerRunService: criteria phase failed for round=$round: ${e.message}")
                        }
                    }
                } else null

                // ── Wait for round duration with heartbeat events ─────────────
                // Instead of a raw delay, poll periodically so the UI sees
                // "still alive" messages and we can honour pause/abort without
                // waiting the full round duration.
                val roundEndMs = System.currentTimeMillis() + config.roundDurationMinutes * 60_000L
                var heartbeatCounter = 0
                while (currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    val remainingMs = roundEndMs - now
                    if (remainingMs <= 0) break

                    // Honour pause
                    while (currentStatus == EngineStatus.PAUSED) { delay(500) }
                    // Honour abort
                    if (currentStatus == EngineStatus.IDLE) {
                        Napier.i("EvoKammerRunService: run aborted mid-round at round $round")
                        return
                    }

                    delay(minOf(roundHeartbeatIntervalMs, remainingMs))
                    heartbeatCounter++

                    val elapsedMin = (config.roundDurationMinutes * 60_000L - remainingMs + roundHeartbeatIntervalMs) / 60_000.0
                    val totalMin = config.roundDurationMinutes.toDouble()
                    val fraction = (elapsedMin / totalMin).coerceIn(0.0, 1.0)

                    val liveAgentCounts = groupIds.sumOf { gid ->
                        liveLifecycleManagers[gid]?.getLiveAgentCount() ?: 0
                    }
                    val msg = "Round $round — ${elapsedMin.toInt()}/${totalMin.toInt()} min elapsed, $liveAgentCounts agents active"
                    Napier.i("EvoKammerRunService: $msg")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "round_wait", msg, fraction))

                    // Early exit: if all agents have completed after at least one heartbeat,
                    // there is no point waiting out the full round duration.
                    if (liveAgentCounts == 0 && heartbeatCounter > 0) {
                        Napier.i("EvoKammerRunService: all agents completed for round $round — ending round early")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "round_early_end",
                            "Round $round — all agents completed, advancing to post-round evaluation"))
                        break
                    }
                }

                // ── End of round: terminate all remaining agents ──────────────
                Napier.i("EvoKammerRunService: round $round duration elapsed — terminating remaining agents")
                emit(EvoKammerEvent.EngineProgress(config.runId, "round_end", "Round $round time up — terminating agents and running post-round evaluation"))
                groupIds.forEach { gid ->
                    try {
                        liveLifecycleManagers[gid]?.terminateAll()
                    } catch (e: Exception) {
                        Napier.w("EvoKammerRunService: terminateAll failed for group $gid: ${e.message}")
                    }
                }

                // ── Round submission summaries ───────────────────────────────
                val roundSubmissionSummaries = spawnRoundSubmissionSummaries(
                    config = config,
                    groupIds = groupIds,
                    round = round
                )
                if (roundSubmissionSummaries.isNotEmpty()) {
                    val summaryLine = roundSubmissionSummaries.entries.joinToString(" | ") { (groupId, summary) ->
                        "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ").trim().take(180)}"
                    }
                    emit(
                        EvoKammerEvent.EngineProgress(
                            config.runId,
                            "round_summary_result",
                            "Round $round submission summaries: $summaryLine"
                        )
                    )
                }

                // ── Post-round processing ────────────────────────────────────

                currentStatus = EngineStatus.EVALUATING

                // 1. Run CriticPipeline for each group — review and promote staging submissions
                val criticSummaries = runCriticPipelinesForRound(config, groupIds, round)

                // 1b. Post-mortem fitness scoring + Legacy Protocol extraction (epigenetic inheritance)
                val epigeneticSummaries = runFitnessAndEpigenetics(config, groupIds, round)

                // 2. Apply per-round trust decay across all groups
                applyTrustDecayForRound(config.runId, groupIds)

                // 3a. Wait for criteria phase to complete (if running), then run judging phase
                criteriaJob?.join()

                val judgeOutputs = if (graderJudgingRunner != null && graderGroupIds.isNotEmpty()) {
                    // New two-phase architecture: RoundJudge reads criteria + scores all groups
                    runGraderJudging(config, graderGroupIds, groupIds, round, graderGroupManager)
                } else {
                    emptyMap()
                }

                if (judgeOutputs.isNotEmpty()) {
                    val judgeSummary = judgeOutputs.entries.joinToString(" | ") { (graderGroupId, output) ->
                        "[$graderGroupId] ${output.replace(Regex("\\s+"), " ").trim().take(220)}"
                    }
                    emit(
                        EvoKammerEvent.EngineProgress(
                            config.runId,
                            "grader_judge_result",
                            "Round $round RoundJudge output: $judgeSummary"
                        )
                    )
                }

                if (graderJudgingRunner == null || graderGroupIds.isEmpty()) {
                    // Legacy: old post-round grader pipeline (backward compat)
                    runGraderAgents(config, graderGroupIds, groupIds, round, graderGroupManager)
                }

                if (graderGroupIds.isNotEmpty()) {
                    registerRoundWithGraderManager(
                        manager = graderGroupManager,
                        runConfig = config,
                        groupIds = groupIds,
                        roundIndex = round,
                        sheetRegisteredRounds = sheetRegisteredRounds
                    )
                    try {
                        val graderRoundResult = graderGroupManager.computeRoundResult(groupIds, round)
                        emit(
                            EvoKammerEvent.EngineProgress(
                                config.runId,
                                "sealed_suite_verdict",
                                "Supplemental sealed-suite verdict completed for round $round — winner ${graderRoundResult.winnerId}"
                            )
                        )
                        Napier.i(
                            "EvoKammerRunService: sealed-suite supplemental verdict round=$round " +
                                "winner=${graderRoundResult.winnerId} scores=${graderRoundResult.scores.size}"
                        )
                    } catch (e: Exception) {
                        Napier.w("EvoKammerRunService: sealed-suite supplemental verdict failed for round=$round: ${e.message}")
                    }
                }

                // 3b. Compute real substrate-based scores and submit to GraderGroup (fallback/supplement).
                // Must run BEFORE processDisclosureCheckIns so latestGroupDimensionScores is populated.
                computeAndSubmitSubstrateScores(config, groupIds, round, coordinator)

                // 4. Process progressive disclosure check-ins per group.
                // B-5: Gate is explicitly opened and closed so late-round submissions cannot
                // contaminate check-ins of a future round. Only runs when gate is open.
                // Runs after computeAndSubmitSubstrateScores so per-dimension scores are available (A-3).
                val watchdogForCheckIn = activeWatchdog
                if (watchdogForCheckIn != null) {
                    watchdogForCheckIn.openCheckInGate(round)
                    // Signal is asynchronous — wait a tick for the collector to update the flag
                    kotlinx.coroutines.yield()
                }
                if (checkInGateOpenRound == round || activeDisclosureEngine != null) {
                    processDisclosureCheckIns(config.runId, groupIds, round)
                }
                watchdogForCheckIn?.closeCheckInGate(round)

                // 5. Seal and reveal the round scores through the blind scoring gate.
                val blindResult = publishBlindRoundResult(config, groupIds, round)

                // Phase 7.5: Apply accepted DST structural refinements and rubric policies.
                applyDstRefinementsFromRound(config.runId, groupIds, blindResult, round)

                val completedWipTasksByGroup = groupIds.associateWith { drainWipCompletions(it) }
                    .filterValues { it.isNotEmpty() }

                // 5b. Publish resource-node ownership changes from the explicit WiP completion stream.
                publishResourceNodeClaims(config.runId, completedWipTasksByGroup, round)

                // 5c. Clear the legacy coordinator state after the blind publish completes.
                coordinator.onGradingComplete(round)

                // 5d. Feed the round's explicit WiP completion stream into the MetaPlanner runner.
                // A round without completions increments stasis; completions reset the counter.
                processMetaPlannerSignals(config, groupIds, round, completedWipTasksByGroup)

                archiveRoundSummaries(
                    runId = config.runId,
                    runObjective = config.objective,
                    groupIds = groupIds,
                    round = round,
                    criticSummaries = criticSummaries,
                    epigeneticSummaries = epigeneticSummaries,
                    blindResult = blindResult,
                    judgeOutputsByGrader = judgeOutputs,
                    roundSubmissionSummaries = roundSubmissionSummaries
                )
                finalRoundJudgeOutputs = judgeOutputs
                finalRoundSubmissionSummaries = roundSubmissionSummaries
                finalRoundWinnerId = blindResult.winnerId
                finalRoundIndex = round
                currentStatus = EngineStatus.RUNNING
                updatePersistedRunState(config.runId) { current ->
                    current.copy(
                        status = PersistedRunStatus.RUNNING,
                        nextRoundIndex = round + 1,
                        activeRoundIndex = null
                    )
                }
            }

            if (finalRoundIndex >= 0) {
                val finalAnswerSummary = buildString {
                    append("Round ${finalRoundIndex + 1} final answer: winner=")
                    append(finalRoundWinnerId ?: "none")
                    if (finalRoundJudgeOutputs.isNotEmpty()) {
                        append(" | judge=")
                        append(
                            finalRoundJudgeOutputs.entries.joinToString(" | ") { (graderId, output) ->
                                "$graderId=${output.replace(Regex("\\s+"), " ").trim().take(180)}"
                            }
                        )
                    }
                    if (finalRoundSubmissionSummaries.isNotEmpty()) {
                        append(" | summaries=")
                        append(
                            finalRoundSubmissionSummaries.entries.joinToString(" | ") { (groupId, summary) ->
                                "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)}"
                            }
                        )
                    }
                }
                emit(
                    EvoKammerEvent.EngineProgress(
                        config.runId,
                        "final_answer",
                        finalAnswerSummary
                    )
                )
            }

            // All rounds complete
            val winnerId = groupSummaries.maxByOrNull { it.currentRoundScore ?: 0.0 }?.groupId
            val finalAnswerSummary = if (finalRoundIndex >= 0) {
                buildString {
                    append("Round ${finalRoundIndex + 1} final answer: winner=")
                    append(finalRoundWinnerId ?: "none")
                    if (finalRoundJudgeOutputs.isNotEmpty()) {
                        append(" | judge=")
                        append(
                            finalRoundJudgeOutputs.entries.joinToString(" | ") { (graderId, output) ->
                                "$graderId=${output.replace(Regex("\\s+"), " ").trim().take(180)}"
                            }
                        )
                    }
                    if (finalRoundSubmissionSummaries.isNotEmpty()) {
                        append(" | summaries=")
                        append(
                            finalRoundSubmissionSummaries.entries.joinToString(" | ") { (groupId, summary) ->
                                "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)}"
                            }
                        )
                    }
                }
            } else {
                ""
            }
            val generatedOutputFile = winnerId
                ?.let { winningGroupId ->
                    resolveFinalOutputSpec(config)
                        ?.let { outputSpec ->
                            spawnFinalDeliverableFile(
                                config = config,
                                groupId = winningGroupId,
                                finalRoundIndex = finalRoundIndex.takeIf { it >= 0 } ?: (effectiveRounds - 1),
                                outputSpec = outputSpec
                            )
                        }
                }

            if (generatedOutputFile != null) {
                emit(
                    EvoKammerEvent.EngineProgress(
                        config.runId,
                        "final_deliverable_result",
                        "Final deliverable writer published ${generatedOutputFile.relativePath} for winner=$winnerId"
                    )
                )
            }

            val completionRecord = RunCompletionRecord(
                runId = config.runId,
                winnerId = winnerId,
                finalAnswerSummary = finalAnswerSummary,
                finalDeliverableHtml = generatedOutputFile
                    ?.takeIf { it.mediaType.startsWith("text/html", ignoreCase = true) }
                    ?.content,
                generatedOutputFiles = listOfNotNull(generatedOutputFile),
                requestedOutputSpec = config.finalOutputSpec,
                judgeOutputsByGrader = finalRoundJudgeOutputs,
                submissionSummariesByGroup = finalRoundSubmissionSummaries
            )
            persistRunCompletionMarker(completionRecord)

            // 4b. Phase 7.5: Synthesise final deliverables from the DST for each group.
            runFinalSynthesisers(config, groupIds)

            // 5. Archive run artefacts for cross-run Council learning
            archiveCompletedRun(config, groupIds, winnerId, completionRecord)

            emit(EvoKammerEvent.RunCompleted(config.runId, winnerId))
            currentStatus = EngineStatus.COMPLETED
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.COMPLETED,
                    nextRoundIndex = effectiveRounds,
                    activeRoundIndex = null
                )
            }
            Napier.i("EvoKammerRunService: run ${config.runId} completed, winner=$winnerId")

        } catch (e: Exception) {
            Napier.e("EvoKammerRunService: run loop error", e)
            emit(EvoKammerEvent.RunAborted(config.runId, e.message ?: "internal_error"))
            currentStatus = EngineStatus.IDLE
            updatePersistedRunState(config.runId) { current ->
                current.copy(status = PersistedRunStatus.FAILED)
            }
        } finally {
            cleanup(config.runId)
        }
    }

    /**
     * Spawns agents for all groups at the start of a round.
     *
     * Uses [EvolutionarySelector] to compute a role allocation for each group based on
     * substrate signals (KB density, staging backlog, tool failure rate, etc.), then calls
     * [LifecycleManager.spawnGeneration] to actually spin up the VMs.
     *
     * Failures are caught per-group so one broken group does not abort the whole round.
     */
    private suspend fun spawnAgentsForRound(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ) {
        for (groupId in groupIds) {
            try {
                val lm = liveLifecycleManagers[groupId]
                if (lm == null) {
                    Napier.w("EvoKammerRunService: no LifecycleManager for group $groupId — skipping spawn")
                    continue
                }
                val db = substrateFactory.get(config.runId, groupId)
                val groupProfileType = activeGroupProfiles.getOrNull(groupIds.indexOf(groupId))
                    ?: activeGroupProfiles.firstOrNull()
                if (groupProfileType == null) {
                    Napier.w("EvoKammerRunService: no GroupProfile for group $groupId")
                    continue
                }
                val profile = GroupProfile.default(groupProfileType)
                val nodeBudgetMultiplier = activeCoordinator?.budgetMultiplierFor(groupId) ?: 1.0
                val budgetCeiling = ((groupSummaries.firstOrNull { it.groupId == groupId }?.remainingBudget
                    ?: (profile.spawnBudget * config.maxAgentsPerGroup)) * nodeBudgetMultiplier)

                Napier.i("EvoKammerRunService: computing role allocation for group=$groupId round=$round budgetCeiling=$budgetCeiling nodeBudgetMultiplier=$nodeBudgetMultiplier")
                emit(EvoKammerEvent.EngineProgress(config.runId, "spawning", "[$groupId] Computing role allocation for round $round"))

                // B-6: Enforce 15% inter-group migration ceiling based on current KB size.
                // Must be called before agents spawn so the LeakOracle has the ceiling for this round.
                val coordinator = activeCoordinator
                if (coordinator != null) {
                    try {
                        val kbCount = db.knowledgeBaseDao().countActive(config.runId, groupId)
                        coordinator.updateMigrationCeiling(groupId, kbCount)
                    } catch (e: Exception) {
                        Napier.w("EvoKammerRunService: migration ceiling update failed for $groupId: ${e.message}")
                    }
                }

                val deliverableTreeSummary = deliverableSkeletonTreeStore.readSummary(config.runId, groupId)
                val selector = EvolutionarySelector(
                    db = db,
                    profile = profile,
                    runId = config.runId,
                    groupId = groupId,
                    deliverableTreeSummary = deliverableTreeSummary
                )
                val stasis = stasisFlags[groupId] ?: false
                val allocation = selector.computeAllocation(
                    generation = round,
                    globalBudgetCeiling = budgetCeiling,
                    stasisDetected = stasis
                )
                // Reset stasis flag after it has been consumed for this round's allocation
                stasisFlags.remove(groupId)

                Napier.i("EvoKammerRunService: allocation for group=$groupId round=$round: ${allocation.totalAgentCount} agents — ${allocation.rationale}")
                emit(EvoKammerEvent.EngineProgress(
                    config.runId, "spawning",
                    "[$groupId] Spawning ${allocation.totalAgentCount} agents: ${allocation.entries.joinToString { "${it.count}×${it.role.name}" }}"
                ))

                val stageDisclosure = try {
                    activeDisclosureEngine?.currentStage(groupId)?.constraintFramedContent ?: ""
                } catch (_: Exception) { "" }

                lm.spawnGeneration(
                    allocation = allocation,
                    stageDisclosure = stageDisclosure,
                    modelAssignments = activeModelAssignments, // Genesis Council assignments (empty = use LifecycleManager defaults)
                    modelPools = activeModelPools,
                    rolePrompts = activeRolePrompts,           // Genesis Council role prompts (empty = use defaults)
                    scope = scope
                )

                Napier.i("EvoKammerRunService: spawn complete for group=$groupId round=$round")
                emit(EvoKammerEvent.EngineProgress(
                    config.runId, "spawning",
                    "[$groupId] All agents spawned for round $round"
                ))
            } catch (e: Exception) {
                Napier.e("EvoKammerRunService: spawn failed for group $groupId round $round: ${e.message}", e)
                emit(EvoKammerEvent.EngineProgress(
                    config.runId, "spawn_error",
                    "[$groupId] Spawn error round $round: ${e.message}"
                ))
            }
        }
    }

    /**
     * Runs [CriticPipeline.processPendingSubmissions] for every active group.
     * Called at the end of each round to review and promote staged agent submissions.
     */
    private suspend fun runCriticPipelinesForRound(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, CriticSummary> = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val pendingCount = db.stagingDao().countPending(config.runId, groupId)
                    Napier.i("EvoKammerRunService: CriticPipeline starting for group=$groupId round=$round pending=$pendingCount")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "critic",
                        "[$groupId] CriticPipeline reviewing $pendingCount pending submissions"))
                    val pipeline = CriticPipeline(
                        db = db,
                        runId = config.runId,
                        groupId = groupId,
                        generation = round,
                        openRouterApiKey = config.openRouterApiKey,
                        criticModel = criticModelOverride ?: ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
                        criticAgentId = criticAgentIdFor(groupId, round),
                        toolsMountHostPath = substrateFactory.dbFilePath(config.runId, groupId).parentFile
                            ?.resolve("tools")
                            ?.absolutePath
                            ?: error("Missing tools mount host path for group=$groupId"),
                        // B-1: Pass the council-designed KG schema so KGConsistencyChecker
                        // can validate KG_FACT submissions before they go to the LLM Critic.
                        kgSchema = activeKgSchema,
                        onWipTaskCompleted = { completedGroupId, nodeId, agentId, generation ->
                            recordWipCompletion(completedGroupId, nodeId)
                            emit(EvoKammerEvent.EngineProgress(
                                config.runId,
                                "wip_completed",
                                "[$completedGroupId] WiP task $nodeId completed by $agentId at generation $generation"
                            ))
                        },
                        deliverableTreeStore = deliverableSkeletonTreeStore
                    )
                    val summary: CriticSummary = pipeline.processPendingSubmissions()
                    Napier.i("EvoKammerRunService: CriticPipeline processed ${summary.total} for group=$groupId round=$round: promoted=${summary.promoted} rejected=${summary.rejected} arbitrated=${summary.arbitrated}")
                    emit(EvoKammerEvent.CriticPipelineCompleted(
                        runId = config.runId,
                        groupId = groupId,
                        roundIndex = round,
                        promoted = summary.promoted,
                        rejected = summary.rejected
                    ))
                    groupId to summary
                } catch (e: Exception) {
                    Napier.e("EvoKammerRunService: CriticPipeline failed for group=$groupId round=$round: ${e.message}", e)
                    groupId to CriticSummary(promoted = 0, rejected = 0, arbitrated = 0)
                }
            }
        }.awaitAll().toMap()
    }

    internal suspend fun reviewGraderCriteriaForRound(
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        onProgress: ((String) -> Unit)? = null
    ) {
        if (graderGroupIds.isEmpty()) return

        graderCriteriaReviewRunner?.let { reviewer ->
            reviewer.invoke(runId, openRouterApiKey, graderGroupIds, roundIndex, onProgress)
            return
        }

        supervisorScope {
            graderGroupIds.map { graderGroupId ->
                async {
                    try {
                        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
                        val pendingCount = graderDb.stagingDao().countPending(runId, graderGroupId)
                        Napier.i("EvoKammerRunService: Grader criteria CriticPipeline starting for grader=$graderGroupId round=$roundIndex pending=$pendingCount")
                        onProgress?.invoke("[$graderGroupId] CriticPipeline reviewing $pendingCount grader criteria submissions")
                        val pipeline = CriticPipeline(
                            db = graderDb,
                            runId = runId,
                            groupId = graderGroupId,
                            generation = roundIndex,
                            openRouterApiKey = openRouterApiKey,
                            criticModel = criticModelOverride ?: ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
                            criticAgentId = criticAgentIdFor(graderGroupId, roundIndex),
                            toolsMountHostPath = substrateFactory.graderDbFilePath(runId, graderGroupId)
                                .parentFile
                                ?.resolve("tools")
                                ?.absolutePath
                                ?: error("Missing tools mount host path for grader=$graderGroupId"),
                            kgSchema = activeKgSchema,
                            onWipTaskCompleted = null,
                            deliverableTreeStore = deliverableSkeletonTreeStore
                        )
                        val summary: CriticSummary = pipeline.processPendingSubmissions()
                        Napier.i("EvoKammerRunService: Grader criteria CriticPipeline processed ${summary.total} for grader=$graderGroupId round=$roundIndex: promoted=${summary.promoted} rejected=${summary.rejected} arbitrated=${summary.arbitrated}")
                        onProgress?.invoke("[$graderGroupId] grader criteria review complete: promoted=${summary.promoted} rejected=${summary.rejected}")
                    } catch (e: Exception) {
                        Napier.e("EvoKammerRunService: Grader criteria CriticPipeline failed for grader=$graderGroupId round=$roundIndex: ${e.message}", e)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Runs the Grader Group AI civilisation for the current round.
     *
     * Calls [graderRunner] if configured. On failure, logs a warning and falls back gracefully
     * (substrate-based scoring still runs after this).
     *
     * Emits [EvoKammerEvent.GraderRoundStarted] and [EvoKammerEvent.GraderRoundCompleted] events.
     */
    private suspend fun runGraderAgents(
        config: RunConfig,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        round: Int,
        graderGroupManager: GraderGroupManager
    ) {
        if (graderRunner == null) {
            Napier.d("EvoKammerRunService: no graderRunner configured — skipping Phase 6 grader evaluation for round=$round")
            return
        }
        if (graderGroupIds.isEmpty()) {
            Napier.d("EvoKammerRunService: graderGroupCount=0 — skipping grader evaluation for round=$round")
            return
        }

        for (graderGroupId in graderGroupIds) {
            emit(EvoKammerEvent.GraderRoundStarted(config.runId, graderGroupId, round, competingGroupIds))
        }

        try {
            Napier.i("EvoKammerRunService: starting grader evaluation round=$round graders=${graderGroupIds.size}")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_start",
                "Grader Group evaluation starting for round $round"))

            graderRunner.invoke(
                config.runId,
                config.openRouterApiKey,
                graderGroupIds,
                competingGroupIds,
                round,
                config.objective,
                config.scoringDimensions,
                graderGroupManager
            ) { progressMsg ->
                scope.launch {
                    emit(EvoKammerEvent.EngineProgress(config.runId, "grader_agent", progressMsg))
                }
            }

            // Build score summary per group for the completion event
            val scoresPerGroup = competingGroupIds.associateWith {
                "[graded by ${graderGroupIds.size} grader(s)]"
            }
            val totalScores = scoresPerGroup.size * graderGroupIds.size * config.scoringDimensions.size

            for (graderGroupId in graderGroupIds) {
                emit(EvoKammerEvent.GraderRoundCompleted(
                    runId = config.runId,
                    graderGroupId = graderGroupId,
                    roundIndex = round,
                    scoresPerGroup = scoresPerGroup,
                    totalScoresCollected = totalScores
                ))
            }

            Napier.i("EvoKammerRunService: grader evaluation complete for round=$round")
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: grader evaluation failed for round=$round: ${e.message}")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_error",
                "Grader evaluation failed for round $round: ${e.message?.take(150)} — falling back to substrate scoring"))
        }
    }

    /**
     * Runs the Phase B RoundJudge for the new two-phase grader architecture.
     *
     * Called post-round only when [graderJudgingRunner] is configured. The judging runner
     * should call `GraderRunner.runJudgingPhase` which spawns the RoundJudge agent.
     */
    private suspend fun runGraderJudging(
        config: RunConfig,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        round: Int,
        graderGroupManager: GraderGroupManager
    ): Map<String, String> {
        if (graderJudgingRunner == null || graderGroupIds.isEmpty()) return emptyMap()

        for (graderGroupId in graderGroupIds) {
            emit(EvoKammerEvent.GraderRoundStarted(config.runId, graderGroupId, round, competingGroupIds))
        }

        try {
            Napier.i("EvoKammerRunService: starting RoundJudge evaluation round=$round")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_judge_start",
                "RoundJudge starting for round $round — scoring ${competingGroupIds.size} groups"))

            val judgeOutputs = graderJudgingRunner.invoke(
                config.runId,
                config.openRouterApiKey,
                graderGroupIds,
                competingGroupIds,
                round,
                config.objective,
                config.scoringDimensions,
                graderGroupManager
            ) { progressMsg ->
                scope.launch {
                    emit(EvoKammerEvent.EngineProgress(config.runId, "grader_judge", progressMsg))
                }
            }

            val scoresPerGroup = competingGroupIds.associateWith { "[judged by RoundJudge]" }
            val totalScores = scoresPerGroup.size * config.scoringDimensions.size
            for (graderGroupId in graderGroupIds) {
                emit(EvoKammerEvent.GraderRoundCompleted(
                    runId = config.runId,
                    graderGroupId = graderGroupId,
                    roundIndex = round,
                    scoresPerGroup = scoresPerGroup,
                    totalScoresCollected = totalScores
                ))
            }
            Napier.i("EvoKammerRunService: RoundJudge evaluation complete round=$round")
            return judgeOutputs
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: RoundJudge evaluation failed round=$round: ${e.message}")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_judge_error",
                "RoundJudge failed for round $round: ${e.message?.take(150)}"))
            return emptyMap()
        }
    }

    /**
     * Computes substrate-based scores for each group from their Room DB and submits them
     * to GroupCoordinator so GraderGroup.computeGradingResult has real data
     * instead of zero-defaulting every group.
     *
     * Score mapping (approximate):
     * - `kb_density`      → count of active KB entries (normalised)
     * - `kg_coherence`    → count of active KG facts (normalised)
     * - `tool_utility`    → count of active tools * avg success rate (normalised)
     * - `plan_execution`  → WIP task completion rate (normalised)
     * - (default)         → 0.0 with explanation logged
     */
    private suspend fun computeAndSubmitSubstrateScores(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        coordinator: GroupCoordinator
    ) = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val snapshot = CanonSnapshotExporter.export(db, config.runId, groupId, round)
                    val sealedSuiteHash = activeSealedTestSuiteHash.ifBlank { canonicalTestSuiteHash(activeSealedTestSuiteJson) }
                    val sealedAssessment = sealedTestSuiteEvaluator.assessGroup(
                        runId = config.runId,
                        groupId = groupId,
                        roundIndex = round,
                        suiteJson = activeSealedTestSuiteJson,
                        suiteHash = sealedSuiteHash,
                        canonicalSnapshot = snapshot,
                        scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
                    )
                    val substrateAssessment = assessSubstrateGroup(
                        db = db,
                        runId = config.runId,
                        groupId = groupId,
                        round = round,
                        scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
                    )
                    coordinator.graderGroup.submit(groupId, round, substrateAssessment.dimensionScores)
                    latestGroupDimensionScores[groupId] = substrateAssessment.dimensionScores.associate { it.dimensionId to it.rawScore }
                    latestGroupSubstrateAssessments[groupId] = substrateAssessment
                    latestGroupRoundAssessments[groupId] = sealedAssessment

                    val substrateScoresSummary = substrateAssessment.dimensionScores.joinToString(", ") {
                        "${it.dimensionId}=${String.format(java.util.Locale.ROOT, "%.1f", it.rawScore)}"
                    }
                    val sealedScoresSummary = sealedAssessment.dimensionScores.joinToString(", ") {
                        "${it.dimensionId}=${String.format(java.util.Locale.ROOT, "%.1f", it.rawScore)}"
                    }
                    Napier.i(
                        "[$groupId] Substrate scores round=$round: $substrateScoresSummary " +
                            "kb=${substrateAssessment.kbCount} kg=${substrateAssessment.kgCount} " +
                            "tools=${substrateAssessment.toolCount} wip=${substrateAssessment.completedWipTasks}/${substrateAssessment.totalWipTasks}"
                    )
                    Napier.i(
                        "[$groupId] Supplemental sealed-suite scores round=$round: $sealedScoresSummary " +
                            "tests=${sealedAssessment.automatedTestsPassed}/${sealedAssessment.automatedTestsPassed + sealedAssessment.automatedTestsFailed} " +
                            "mandatoryFailure=${sealedAssessment.mandatoryFailure}"
                    )
                    emit(EvoKammerEvent.EngineProgress(config.runId, "scoring",
                        "[$groupId] Scores submitted round=$round: $substrateScoresSummary"))
                } catch (e: Exception) {
                    Napier.w("EvoKammerRunService: score computation failed for group=$groupId round=$round: ${e.message}")
                }
            }
        }.awaitAll()
    }

    private suspend fun assessSubstrateGroup(
        db: EvoKammerDatabase,
        runId: String,
        groupId: String,
        round: Int,
        scoringDimensions: List<ScoringDimension>
    ): SubstrateRoundAssessment = coroutineScope {
        val kbCountDeferred = async { db.knowledgeBaseDao().countActive(runId, groupId) }
        val kgCountDeferred = async { db.knowledgeGraphFactDao().countActive(runId, groupId) }
        val kgConflictDeferred = async { db.knowledgeGraphFactDao().countConflicts(runId, groupId) }
        val toolCountDeferred = async { db.toolRegistryDao().getActive(runId, groupId, null).size }
        val toolFailureRateDeferred = async { db.toolRegistryDao().getAverageFailureRate(runId, groupId) ?: 0.0 }
        val wipCompletedDeferred = async { db.wipTaskDao().countCompleted(runId, groupId) }
        val wipTotalDeferred = async { db.wipTaskDao().countAll(runId, groupId) }

        val kbCount = kbCountDeferred.await()
        val kgCount = kgCountDeferred.await()
        val kgConflicts = kgConflictDeferred.await()
        val toolCount = toolCountDeferred.await()
        val toolFailureRate = toolFailureRateDeferred.await().coerceIn(0.0, 1.0)
        val completedWipTasks = wipCompletedDeferred.await()
        val totalWipTasks = wipTotalDeferred.await()

        val toolSuccessRate = (1.0 - toolFailureRate).coerceIn(0.0, 1.0)
        val dimensionScores = scoringDimensions.map { dimension ->
            val rawScore = substrateRawScore(
                dimensionId = dimension.id,
                maxScore = dimension.maxScore,
                kbCount = kbCount,
                kgCount = kgCount,
                kgConflicts = kgConflicts,
                toolCount = toolCount,
                toolSuccessRate = toolSuccessRate,
                completedWipTasks = completedWipTasks,
                totalWipTasks = totalWipTasks
            )
            DimensionScore(
                dimensionId = dimension.id,
                rawScore = rawScore,
                justification = buildSubstrateJustification(
                    groupId = groupId,
                    round = round,
                    dimensionId = dimension.id,
                    kbCount = kbCount,
                    kgCount = kgCount,
                    kgConflicts = kgConflicts,
                    toolCount = toolCount,
                    toolSuccessRate = toolSuccessRate,
                    completedWipTasks = completedWipTasks,
                    totalWipTasks = totalWipTasks
                )
            )
        }

        val totalWeight = scoringDimensions.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        val overallScore = scoringDimensions.sumOf { dimension ->
            val raw = dimensionScores.firstOrNull { it.dimensionId == dimension.id }?.rawScore ?: 0.0
            (raw / dimension.maxScore) * (dimension.weight / totalWeight)
        }.coerceIn(0.0, 1.0)

        SubstrateRoundAssessment(
            dimensionScores = dimensionScores,
            overallScore = overallScore,
            kbCount = kbCount,
            kgCount = kgCount,
            toolCount = toolCount,
            toolSuccessRate = toolSuccessRate,
            completedWipTasks = completedWipTasks,
            totalWipTasks = totalWipTasks
        )
    }

    private fun substrateRawScore(
        dimensionId: String,
        maxScore: Double,
        kbCount: Int,
        kgCount: Int,
        kgConflicts: Int,
        toolCount: Int,
        toolSuccessRate: Double,
        completedWipTasks: Int,
        totalWipTasks: Int
    ): Double {
        val raw = when (dimensionId) {
            "kb_density" -> kbCount * 10.0
            "kg_coherence" -> {
                val coherenceFactor = if (kgCount <= 0) 0.0
                else (1.0 - (kgConflicts.toDouble() / kgCount.toDouble())).coerceIn(0.0, 1.0)
                kgCount * 10.0 * coherenceFactor
            }
            "tool_utility" -> toolCount * toolSuccessRate * 20.0
            "plan_execution" -> if (totalWipTasks > 0) {
                (completedWipTasks.toDouble() / totalWipTasks.toDouble()) * maxScore
            } else 0.0
            else -> 0.0
        }
        return raw.coerceIn(0.0, maxScore)
    }

    private fun buildSubstrateJustification(
        groupId: String,
        round: Int,
        dimensionId: String,
        kbCount: Int,
        kgCount: Int,
        kgConflicts: Int,
        toolCount: Int,
        toolSuccessRate: Double,
        completedWipTasks: Int,
        totalWipTasks: Int
    ): String = when (dimensionId) {
        "kb_density" ->
            "[$groupId] round $round: $kbCount active KB entries in substrate."
        "kg_coherence" ->
            "[$groupId] round $round: $kgCount active KG facts with $kgConflicts conflicts."
        "tool_utility" ->
            "[$groupId] round $round: $toolCount active tools with ${(toolSuccessRate * 100).toInt()}% average success."
        "plan_execution" ->
            "[$groupId] round $round: $completedWipTasks/$totalWipTasks WIP tasks completed."
        else ->
            "[$groupId] round $round: no substrate scoring heuristic configured for '$dimensionId'."
    }

    /**
     * Ticks the MetaPlanner runner once per round for each group.
     *
     * WiP completions reset the group counter; a round with no completions increments
     * the stasis count and may trigger a MetaPlanner spawn once the threshold is hit.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs for the run.
     * @param round Current round index.
     * @param completedWipTasksByGroup WiP task IDs completed during this round.
     */
    private suspend fun processMetaPlannerSignals(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        completedWipTasksByGroup: Map<String, List<String>>
    ) {
        val runner = metaPlannerRunner ?: return
        for (groupId in groupIds) {
            if (completedWipTasksByGroup[groupId].isNullOrEmpty()) {
                runner.onGenerationTick(groupId) { targetGroupId, role ->
                    spawnMetaPlannerForGroup(config, groupIds, targetGroupId, role, round)
                }
            } else {
                runner.onWipCompleted(groupId)
            }
        }
    }

    /**
     * Spawns a MetaPlanner as a special-purpose agent with no WIP claim and no legacy protocol.
     *
     * @param config Current run configuration.
     * @param groupIds All competing group IDs in this run.
     * @param targetGroupId The group that should receive the MetaPlanner.
     * @param role The role requested by the runner.
     * @param round Current round index.
     */
    private suspend fun spawnMetaPlannerForGroup(
        config: RunConfig,
        groupIds: List<String>,
        targetGroupId: String,
        role: com.achulkov.evokammer.core.model.Role,
        round: Int
    ) {
        val lm = liveLifecycleManagers[targetGroupId] ?: return
        val profileType = activeGroupProfiles.getOrNull(groupIds.indexOf(targetGroupId)) ?: return
        val profile = GroupProfile.default(profileType)
        val budgetPerAgent = profile.spawnBudget * 2.0

        emit(EvoKammerEvent.EngineProgress(
            config.runId,
            "meta_planner_spawn",
            "[$targetGroupId] Spawning MetaPlanner after sustained stasis at round $round"
        ))

        maybeSpawnNestedHierarchyForGroup(
            config = config,
            targetGroupId = targetGroupId,
            round = round,
            profile = profile
        )

        lm.spawnSpecialAgent(
            role = role,
            generation = round + 1,
            budgetPerAgent = budgetPerAgent,
            stageDisclosure = "",
            modelAssignments = activeModelAssignments,
            modelPools = activeModelPools,
            rolePrompt = activeRolePrompts[role.name] ?: "You are a MetaPlanner. Rebuild the plan registry and unstitch stasis.",
            scope = scope
        )
    }

    /**
     * Narrow Phase 9 production trigger: when a group hits sustained planning stasis and still
     * has multiple unresolved WiP tasks, spin up one nested inner instance for that group.
     *
     * This intentionally fires at most once per group per run until a richer planner-owned
     * decomposition policy exists.
     */
    private suspend fun maybeSpawnNestedHierarchyForGroup(
        config: RunConfig,
        targetGroupId: String,
        round: Int,
        profile: GroupProfile
    ) {
        val lm = liveLifecycleManagers[targetGroupId] ?: return
        if (lm.getLiveNestedGroupCount() > 0) return

        val db = runCatching { substrateFactory.get(config.runId, targetGroupId) }
            .getOrElse { return }
        val openTasks = runCatching {
            db.wipTaskDao().getOpenTasks(config.runId, targetGroupId, NESTED_GROUP_OPEN_TASK_LIMIT)
        }.getOrElse { return }
        if (!nestedHierarchyTriggeredGroups.add(targetGroupId)) return

        val remainingBudget = groupSummaries.firstOrNull { it.groupId == targetGroupId }?.remainingBudget
            ?: (profile.spawnBudget * config.maxAgentsPerGroup)
        val plan = nestedHierarchySpawnPlanner.plan(
            NestedHierarchySpawnRequest(
                runId = config.runId,
                outerGroupId = targetGroupId,
                round = round,
                runObjective = config.objective,
                profileType = profile.profileType,
                profile = profile,
                remainingBudget = remainingBudget,
                openTaskDescriptions = openTasks.map { it.description },
                openRouterApiKey = config.openRouterApiKey,
                agentJarPath = resolveAgentJarPath(),
                modelAssignment = activeModelAssignments,
                modelPools = activeModelPools
            )
        )
        if (plan == null) {
            nestedHierarchyTriggeredGroups.remove(targetGroupId)
            return
        }

        emit(EvoKammerEvent.EngineProgress(
            config.runId,
            "nested_group_spawn",
            plan.progressMessage
        ))

        runCatching {
            lm.spawnNestedGroup(
                config = plan.config,
                scope = scope,
                onCompleted = { result ->
                    emitNestedGroupCompletion(config.runId, result)
                }
            )
        }.onFailure { error ->
            nestedHierarchyTriggeredGroups.remove(targetGroupId)
            Napier.w("EvoKammerRunService: nested group spawn failed for group=$targetGroupId round=$round: ${error.message}")
        }
    }

    private suspend fun emitNestedGroupCompletion(
        runId: String,
        result: NestedGroupResult
    ) {
        emit(EvoKammerEvent.EngineProgress(
            runId,
            "nested_group_result",
            "[${result.outerGroupId}] Nested group ${result.nestedGroupId} completed; imported " +
                "${result.importedKbDocumentIds.size} KB, ${result.importedKgFactIds.size} KG, " +
                "${result.importedToolIds.size} tools"
        ))
    }

    /**
     * Spawns a short-lived round-end summary agent for each competing group and waits for the
     * summary text to appear in staging.
     *
     * The spawned agent writes a `PLAN_UPDATE` entry that begins with the exact marker
     * `ROUND SUBMISSION SUMMARY`, which is then harvested for archival.
     */
    private suspend fun spawnRoundSubmissionSummaries(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, String?> = supervisorScope {
        groupIds.mapIndexed { index, groupId ->
            async {
                val lm = liveLifecycleManagers[groupId]
                    ?: return@async groupId to null
                val profileType = activeGroupProfiles.getOrNull(index)
                    ?: GroupProfileType.Library
                val profile = GroupProfile.default(profileType)
                val budgetPerAgent = maxOf(5.0, profile.spawnBudget * 0.15)

                waitForLiveAgentsToDrain(groupId, lm, "post-round termination")
                var summary: String? = null
                for (attempt in 1..ROUND_SUMMARY_MAX_ATTEMPTS) {
                    val prompt = RolePromptBuilder.buildRoundSubmissionSummary(
                        runObjective = config.objective,
                        roundIndex = round,
                        groupId = groupId,
                        attempt = attempt
                    )
                    val summaryStartedAtMs = System.currentTimeMillis()

                    emit(EvoKammerEvent.EngineProgress(
                        config.runId,
                        "round_summary_spawn",
                        "[$groupId] Spawning round submission summariser for round ${round + 1} (attempt $attempt/$ROUND_SUMMARY_MAX_ATTEMPTS)"
                    ))

                    try {
                        lm.spawnSpecialAgent(
                            role = Role.Archivist,
                            generation = round + 1,
                            budgetPerAgent = budgetPerAgent,
                            stageDisclosure = buildString {
                                append("Round ${round + 1} submission summary generation for group $groupId.")
                                if (attempt > 1) {
                                    append(" Retry attempt $attempt after no summary was recorded from the previous attempt.")
                                }
                            },
                            modelAssignment = null,
                            modelAssignments = activeModelAssignments,
                            modelPools = activeModelPools,
                            rolePrompt = prompt,
                            scope = scope
                        )
                    } catch (e: Exception) {
                        Napier.w("EvoKammerRunService: round submission summariser spawn failed for group=$groupId attempt=$attempt: ${e.message}")
                        break
                    }

                    summary = awaitRoundSubmissionSummary(
                        runId = config.runId,
                        groupId = groupId,
                        lifecycleManager = lm,
                        startedAtMs = summaryStartedAtMs
                    )
                    if (!summary.isNullOrBlank()) {
                        break
                    }

                    if (attempt < ROUND_SUMMARY_MAX_ATTEMPTS) {
                        Napier.w(
                            "EvoKammerRunService: no round submission summary recorded for group=$groupId round=$round on attempt=$attempt; retrying"
                        )
                    }
                }

                groupId to summary
            }
        }.awaitAll().toMap()
    }

    /**
     * Waits for the group's live agents to drain without blocking indefinitely.
     */
    private suspend fun waitForLiveAgentsToDrain(
        groupId: String,
        lm: LifecycleManager,
        phase: String
    ) {
        withTimeoutOrNull(90_000L) {
            while (lm.getLiveAgentCount() > 0) {
                delay(250L)
            }
        } ?: Napier.w(
            "EvoKammerRunService: $phase drain timed out for group=$groupId; continuing without it"
        )
    }

    /**
     * Reads the newest round submission summary that was written after the summary agents
     * were spawned.
     */
    private suspend fun readRoundSubmissionSummary(
        runId: String,
        groupId: String,
        startedAtMs: Long
    ): String? {
        val db = runCatching { substrateFactory.get(runId, groupId) }
            .getOrElse { return null }

        val marker = "ROUND SUBMISSION SUMMARY"
        return runCatching {
            db.stagingDao().getAll(runId, groupId, limit = 100)
                .asSequence()
                .filter { it.submittedAtMs >= startedAtMs }
                .filter { it.type == com.achulkov.evokammer.core.model.SubmissionType.PLAN_UPDATE.name }
                .sortedByDescending { it.submittedAtMs }
                .firstOrNull { it.content.trimStart().startsWith(marker) }
                ?.content
        }.getOrNull()
    }

    /**
     * Waits for the required round-summary staging marker instead of the agent's full teardown.
     *
     * Once the PLAN_UPDATE exists, post-round evaluation can proceed deterministically even if the
     * summariser is still wrapping up its death note.
     */
    private suspend fun awaitRoundSubmissionSummary(
        runId: String,
        groupId: String,
        lifecycleManager: LifecycleManager,
        startedAtMs: Long,
        timeoutMs: Long = 120_000L
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val summary = readRoundSubmissionSummary(runId, groupId, startedAtMs)
            if (!summary.isNullOrBlank()) {
                return summary
            }
            if (lifecycleManager.getLiveAgentCount() == 0) {
                return null
            }
            delay(250L)
        }

        return readRoundSubmissionSummary(runId, groupId, startedAtMs).also { summary ->
            if (summary.isNullOrBlank()) {
                Napier.w(
                    "EvoKammerRunService: timed out waiting for round submission summary marker for group=$groupId; continuing without it"
                )
            }
        }
    }

    private fun resolveFinalOutputSpec(config: RunConfig): FinalOutputSpec? {
        config.finalOutputSpec?.let { return it }
        return if (RunObjectiveHeuristics.requestsBrowserDeliverable(config.objective)) {
            FinalOutputSpec(
                relativePath = "final-article.html",
                mediaType = "text/html",
                description = "Final browser-renderable deliverable",
                kind = FinalOutputKind.HTML_ARTICLE
            )
        } else {
            null
        }
    }

    /**
     * Spawns a winner-side Builder agent that writes the final generated deliverable file.
     */
    private suspend fun spawnFinalDeliverableFile(
        config: RunConfig,
        groupId: String,
        finalRoundIndex: Int,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val lm = liveLifecycleManagers[groupId] ?: return null
        val groupIndex = groupSummaries.indexOfFirst { it.groupId == groupId }
        val profileType = activeGroupProfiles.getOrNull(groupIndex) ?: GroupProfileType.Library
        val profile = GroupProfile.default(profileType)
        val candidateModels = listOfNotNull(
            activeModelAssignments[Role.Builder.name],
            activeModelAssignments[Role.Researcher.name],
            activeModelAssignments[Role.Archivist.name]
        ).distinct()
        var finalFile: GeneratedOutputFile? = null
        val maxAttempts = maxOf(FINAL_DELIVERABLE_MAX_ATTEMPTS, candidateModels.size.coerceAtLeast(1))
        for (attempt in 1..maxAttempts) {
            val selectedModel = candidateModels.getOrElse(attempt - 1) {
                candidateModels.lastOrNull()
            }
            val startedAtMs = System.currentTimeMillis()
            emit(
                EvoKammerEvent.EngineProgress(
                    config.runId,
                    "final_deliverable_spawn",
                    "[$groupId] Spawning final deliverable writer (attempt $attempt/$maxAttempts, model=${selectedModel ?: "default"})"
                )
            )

            lm.spawnSpecialAgent(
                role = Role.Builder,
                generation = maxOf(finalRoundIndex + 1, 0),
                budgetPerAgent = maxOf(8.0, profile.spawnBudget * 0.25),
                stageDisclosure = buildString {
                    append("Generate the final standalone deliverable for the completed run.")
                    if (attempt > 1) {
                        append(
                            " Retry attempt $attempt after no persisted final output file was recorded from the previous attempt."
                        )
                    }
                    if (selectedModel != null) {
                        append(" Use model assignment $selectedModel for this attempt.")
                    }
                },
                modelAssignment = selectedModel,
                modelAssignments = activeModelAssignments,
                modelPools = activeModelPools,
                rolePrompt = if (outputSpec.kind == FinalOutputKind.PATCH_BUNDLE) {
                    RolePromptBuilder.buildPatchBundleFile(
                        runObjective = config.objective,
                        roundCount = finalRoundIndex + 1,
                        groupId = groupId,
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        description = outputSpec.description,
                        attempt = attempt
                    )
                } else {
                    RolePromptBuilder.buildFinalDeliverableFile(
                        runObjective = config.objective,
                        roundCount = finalRoundIndex + 1,
                        groupId = groupId,
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        description = outputSpec.description,
                        attempt = attempt
                    )
                },
                scope = scope
            )

            finalFile = awaitGeneratedOutputFile(
                runId = config.runId,
                groupId = groupId,
                lifecycleManager = lm,
                startedAtMs = startedAtMs,
                outputSpec = outputSpec
            )
            if (finalFile != null) {
                break
            }
            if (attempt < maxAttempts) {
                Napier.w(
                    "EvoKammerRunService: no final deliverable file recorded for group=$groupId " +
                        "round=$finalRoundIndex on attempt=$attempt; retrying"
                )
            }
        }
        if (finalFile == null) {
            throw IllegalStateException(
                "Final output ${outputSpec.relativePath} (${outputSpec.mediaType}) was not published for group=$groupId after $maxAttempts attempt(s)"
            )
        }
        return finalFile
    }

    /**
     * Polls for a generated final output file and treats that persisted DOC artifact as success
     * even if the writer is still finishing or its trailing death note fails.
     */
    private suspend fun awaitGeneratedOutputFile(
        runId: String,
        groupId: String,
        lifecycleManager: LifecycleManager,
        startedAtMs: Long,
        outputSpec: FinalOutputSpec,
        timeoutMs: Long = 300_000L
    ): GeneratedOutputFile? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readFinalDeliverableFile(
                runId = runId,
                groupId = groupId,
                startedAtMs = startedAtMs,
                outputSpec = outputSpec
            )?.let { return it }
            if (lifecycleManager.getLiveAgentCount() == 0) {
                return null
            }
            delay(250L)
        }

        return readFinalDeliverableFile(
            runId = runId,
            groupId = groupId,
            startedAtMs = startedAtMs,
            outputSpec = outputSpec
        ).also { finalFile ->
            if (finalFile == null) {
                Napier.w(
                    "EvoKammerRunService: timed out waiting for generated final output for group=$groupId; continuing without it"
                )
            }
        }
    }

    /**
     * Reads the newest authored final deliverable file written after the final deliverable
     * writer was spawned.
     */
    private suspend fun readFinalDeliverableFile(
        runId: String,
        groupId: String,
        startedAtMs: Long,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val db = runCatching { substrateFactory.get(runId, groupId) }
            .getOrElse { return null }

        return runCatching {
            // Large, long-running stages can emit many staging records after the final writer
            // starts. Use a wider window so the canonical final HTML file is not missed simply
            // because it is older than an arbitrary shallow limit.
            db.stagingDao().getAll(runId, groupId, limit = 1000)
                .asSequence()
                .filter { it.submittedAtMs >= startedAtMs }
                .filter { it.type == com.achulkov.evokammer.core.model.SubmissionType.DOC.name }
                .sortedByDescending { it.submittedAtMs }
                .map { it.content.trim() }
                .mapNotNull { candidate -> extractGeneratedOutputFile(candidate, outputSpec) }
                .firstOrNull()
        }.getOrNull()
    }

    private fun extractGeneratedOutputFile(
        candidate: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        if (outputSpec.mediaType.startsWith("text/html", ignoreCase = true)) {
            if (candidate.startsWith("<!doctype html>", ignoreCase = true) ||
                candidate.startsWith("<html", ignoreCase = true)
            ) {
                return validateGeneratedOutputFile(
                    GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = candidate
                    )
                )
            }
        }

        directBodyGeneratedOutputFile(candidate, outputSpec)?.let { return validateGeneratedOutputFile(it) }

        if (!candidate.startsWith("FINAL DELIVERABLE FILE")) {
            return null
        }
        val lines = candidate.lines()
        val pathLine = lines.firstOrNull { it.startsWith("Path:", ignoreCase = true) } ?: return null
        val mediaLine = lines.firstOrNull { it.startsWith("Media-Type:", ignoreCase = true) } ?: return null
        val contentMarkerIndex = lines.indexOfFirst { it.trim().equals("CONTENT", ignoreCase = true) }
        if (contentMarkerIndex < 0 || contentMarkerIndex + 1 >= lines.size) {
            return null
        }
        val path = pathLine.substringAfter(':').trim()
        val mediaType = mediaLine.substringAfter(':').trim()
        if (path != outputSpec.relativePath || !mediaType.equals(outputSpec.mediaType, ignoreCase = true)) {
            return null
        }
        val content = lines.drop(contentMarkerIndex + 1).joinToString("\n").trim()
        if (content.isBlank()) return null
        return validateGeneratedOutputFile(
            GeneratedOutputFile(
                relativePath = path,
                mediaType = mediaType,
                content = content
            )
        )
    }

    private fun directBodyGeneratedOutputFile(
        candidate: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val body = candidate.trim()
        if (body.isBlank()) return null

        val mediaType = outputSpec.mediaType
        val acceptsBody = when {
            mediaType.startsWith("text/html", ignoreCase = true) -> false
            mediaType.equals("application/json", ignoreCase = true) ||
                mediaType.endsWith("+json", ignoreCase = true) ->
                runCatching { Json.parseToJsonElement(body) }.isSuccess
            mediaType.startsWith("text/", ignoreCase = true) -> true
            else -> false
        }

        if (!acceptsBody) return null

        return GeneratedOutputFile(
            relativePath = outputSpec.relativePath,
            mediaType = outputSpec.mediaType,
            content = body
        )
    }

    /**
     * Applies per-round trust decay to all groups' trust score tables.
     * Also archives KB entries whose trust has dropped below the minimum threshold.
     */
    private suspend fun applyTrustDecayForRound(runId: String, groupIds: List<String>) = supervisorScope {
        val now = System.currentTimeMillis()
        val archiveThreshold = 0.30 // archive KB entries that fall below 30% trust
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(runId, groupId)
                    db.trustScoreDao().applyDecay(runId, groupId, trustDecayFactor, now)
                    val archived = db.knowledgeBaseDao().archiveBelowTrust(runId, groupId, archiveThreshold)
                    if (archived > 0) {
                        Napier.i("EvoKammerRunService: trust decay — archived $archived low-trust KB entries in group=$groupId")
                    }
                } catch (e: Exception) {
                    Napier.w("EvoKammerRunService: trust decay failed for group=$groupId: ${e.message}")
                }
            }
        }.awaitAll()
    }

    /**
     * Notifies [ProgressiveDisclosureEngine] about group check-in scores after each round.
     *
     * Derives a [GroupCheckinResult] from the current group summaries and the per-dimension
     * scores cached by [computeAndSubmitSubstrateScores] in [latestGroupDimensionScores] (A-3).
     * This must be called AFTER [computeAndSubmitSubstrateScores] to get valid dimension data.
     *
     * @param runId The active run identifier.
     * @param groupIds The competing group IDs.
     * @param round The round index for this check-in.
     */
    private suspend fun processDisclosureCheckIns(@Suppress("UNUSED_PARAMETER") runId: String, groupIds: List<String>, round: Int) {
        val engine = activeDisclosureEngine ?: return
        groupIds.forEach { groupId ->
            try {
                val checkin = buildDisclosureCheckinResult(groupId, round)
                engine.onCheckinComplete(groupId, checkin)
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: progressive disclosure check-in failed for $groupId: ${e.message}")
            }
        }

        applyLeakPressureForNextRound(groupIds)
    }

    /** Builds the disclosure check-in payload from the sealed-suite assessment telemetry. */
    internal fun buildDisclosureCheckinResult(groupId: String, round: Int): GroupCheckinResult {
        val assessment = latestGroupSubstrateAssessments[groupId]
            ?: latestGroupRoundAssessments[groupId]?.let { deterministic ->
                SubstrateRoundAssessment(
                    dimensionScores = deterministic.dimensionScores,
                    overallScore = deterministic.overallScore,
                    kbCount = 0,
                    kgCount = 0,
                    toolCount = 0,
                    toolSuccessRate = 0.0,
                    completedWipTasks = deterministic.automatedTestsPassed,
                    totalWipTasks = deterministic.automatedTestsPassed + deterministic.automatedTestsFailed
                )
            }
        val score = assessment?.overallScore ?: 0.0
        val dimScores = assessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
            ?: emptyMap()
        val totalTasks = assessment?.totalWipTasks ?: 0
        val testPassRate = if (totalTasks > 0) {
            assessment?.completedWipTasks?.toDouble()?.div(totalTasks) ?: 0.0
        } else 0.0

        return GroupCheckinResult(
            groupId = groupId,
            overallScore = score,
            passed = score >= 0.6,
            dimensionScores = dimScores,
            testPassRate = testPassRate,
            failedTestCount = if (latestGroupSubstrateAssessments.containsKey(groupId)) {
                (totalTasks - (assessment?.completedWipTasks ?: 0)).coerceAtLeast(0)
            } else {
                latestGroupRoundAssessments[groupId]?.automatedTestsFailed ?: 0
            },
            gradingSheetApplied = if (latestGroupSubstrateAssessments.containsKey(groupId)) {
                "substrate-round-$round"
            } else {
                latestGroupRoundAssessments[groupId]?.gradingSheetApplied ?: "round-$round"
            },
            stageAdvanced = false
        )
    }

    /**
     * Raises outbound leak ceilings for groups adjacent to below-threshold groups.
     *
     * The disclosure engine tracks how badly a group missed check-in. We project that signal
     * onto the adjacent source groups so the next round increases leak traffic toward the
     * underperforming group.
     */
    private fun applyLeakPressureForNextRound(groupIds: List<String>) {
        val engine = activeDisclosureEngine ?: return
        val coordinator = activeCoordinator ?: return
        if (groupIds.size < 2) return

        groupIds.forEachIndexed { index, sourceGroupId ->
            val leftGroupId = groupIds[(index - 1 + groupIds.size) % groupIds.size]
            val rightGroupId = groupIds[(index + 1) % groupIds.size]
            val pressure = maxOf(
                engine.leakMultiplierFor(leftGroupId),
                engine.leakMultiplierFor(rightGroupId)
            )
            if (pressure <= 1.0) return@forEachIndexed

            val currentCeiling = coordinator.leakOracle.ceilingFor(sourceGroupId)
            val elevatedCeiling = maxOf(currentCeiling, kotlin.math.ceil(currentCeiling * pressure).toInt())
            coordinator.leakOracle.patchGroupCeiling(sourceGroupId, elevatedCeiling)
        }
    }

    /** Calls [RunArchiveService.archiveRun] after a successful run completion. */
    private suspend fun archiveCompletedRun(
        config: RunConfig,
        groupIds: List<String>,
        winnerId: String?,
        completionRecord: RunCompletionRecord? = null
    ) {
        val service = archiveService ?: return
        try {
            // Serialise the full RunConfig as the StartingField snapshot, masking the API key
            // so credentials are never stored in the substrate DB.
            val startingFieldJson = runConfigJson.encodeToString(
                RunConfig.serializer(),
                config.copy(openRouterApiKey = "<redacted>")
            )
            service.archiveRun(
                runId = config.runId,
                groupIds = groupIds,
                winnerId = winnerId,
                objective = config.objective,
                startingFieldJson = startingFieldJson,
                finalGeneration = config.rounds,
                requestedOutputSpec = config.finalOutputSpec,
                completionRecord = completionRecord
            )
            runFinalOutputSpecs.remove(config.runId)
            Napier.i("EvoKammerRunService: run ${config.runId} archived successfully")
        } catch (e: Exception) {
            Napier.e("EvoKammerRunService: failed to archive run ${config.runId}: ${e.message}", e)
        }
    }

    /**
     * Writes a durable completion marker into the run meta-archive.
     *
     * Continuation gating and the run picker both need a completion signal that is available
     * even when the downloadable results bundle is not being written.
     *
     * @param runId The completed run identifier.
     * @param winnerId The winning group ID, if any.
     */
    private suspend fun persistRunCompletionMarker(completionRecord: RunCompletionRecord) {
        val archiveDb = substrateFactory.get(completionRecord.runId, META_ARCHIVE_GROUP_ID)
        val markerExists = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }.any { it.runId == completionRecord.runId }
        if (markerExists) return

        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "run-completed-${completionRecord.runId}",
                    runId = completionRecord.runId,
                    entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                    groupId = null,
                    label = "Run Completed — ${completionRecord.runId}",
                    contentJson = runConfigJson.encodeToString(
                        RunCompletionRecord.serializer(),
                        completionRecord
                    ),
                    runObjective = runCatching {
                        archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                    }.getOrElse { emptyList() }
                        .firstOrNull { it.runId == completionRecord.runId }
                        ?.runObjective.orEmpty(),
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
        }.onFailure { e ->
            Napier.w("EvoKammerRunService: failed to persist completion marker for run=${completionRecord.runId}: ${e.message}")
        }
    }

    /**
     * Persists one round summary per competing group so the end-of-round output is queryable
     * outside the transient log stream.
     */
    private suspend fun archiveRoundSummaries(
        runId: String,
        runObjective: String,
        groupIds: List<String>,
        round: Int,
        criticSummaries: Map<String, CriticSummary>,
        epigeneticSummaries: Map<String, EpigeneticRoundSummary>,
        blindResult: com.achulkov.evokammer.core.model.GradingResult,
        judgeOutputsByGrader: Map<String, String>,
        roundSubmissionSummaries: Map<String, String?>
    ) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val now = System.currentTimeMillis()
        val roundSummary = RoundSummaryRecord(
            runId = runId,
            roundIndex = round,
            roundWinnerId = blindResult.winnerId,
            judgeOutputsByGrader = judgeOutputsByGrader,
            groupSummaries = groupIds.map { groupId ->
                val critic = criticSummaries[groupId] ?: CriticSummary(0, 0, 0)
                val epigenetic = epigeneticSummaries[groupId] ?: EpigeneticRoundSummary(0, 0, 0, 0, 0)
                val deterministicAssessment = latestGroupRoundAssessments[groupId]
                val blindScore = blindResult.scores.firstOrNull { it.groupId == groupId }
                RoundGroupSummaryRecord(
                    runId = runId,
                    roundIndex = round,
                    groupId = groupId,
                    criticPromoted = critic.promoted,
                    criticRejected = critic.rejected,
                    criticArbitrated = critic.arbitrated,
                    fitnessScoredAgents = epigenetic.fitnessScoredAgents,
                    fitnessTopEligibleAgents = epigenetic.fitnessTopEligibleAgents,
                    legacyProtocolsExtracted = epigenetic.legacyProtocolsExtracted,
                    legacyProtocolsExpired = epigenetic.legacyProtocolsExpired,
                    deadEndsInserted = epigenetic.deadEndsInserted,
                    deterministicScore = DeterministicAssessmentRecord(
                        overallScore = deterministicAssessment?.overallScore ?: 0.0,
                        automatedTestsPassed = deterministicAssessment?.automatedTestsPassed ?: 0,
                        automatedTestsFailed = deterministicAssessment?.automatedTestsFailed ?: 0,
                        mandatoryFailure = deterministicAssessment?.mandatoryFailure ?: false,
                        gradingSheetApplied = deterministicAssessment?.gradingSheetApplied ?: "sealed-suite-$round",
                        failedTestIds = deterministicAssessment?.failedTestIds.orEmpty(),
                        dimensionScores = deterministicAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }.orEmpty()
                    ),
                    disclosureCheckin = runCatching { buildDisclosureCheckinResult(groupId, round) }
                        .getOrElse {
                            GroupCheckinResult(
                                groupId = groupId,
                                overallScore = 0.0,
                                passed = false,
                                dimensionScores = emptyMap(),
                                testPassRate = 0.0,
                                failedTestCount = 0,
                                gradingSheetApplied = "sealed-suite-$round",
                                stageAdvanced = false
                            )
                        }.let { checkin ->
                            DisclosureCheckinRecord(
                                overallScore = checkin.overallScore,
                                passed = checkin.passed,
                                testPassRate = checkin.testPassRate,
                                failedTestCount = checkin.failedTestCount,
                                gradingSheetApplied = checkin.gradingSheetApplied,
                                stageAdvanced = checkin.stageAdvanced,
                                dimensionScores = checkin.dimensionScores
                            )
                        },
                    blindScore = BlindScoreRecord(
                        weightedTotal = blindScore?.weightedTotal ?: 0.0,
                        rank = blindScore?.rank ?: groupIds.size,
                        dimensionScores = blindScore?.dimensionScores?.associate { it.dimensionId to it.rawScore }.orEmpty()
                    ),
                    submissionSummary = roundSubmissionSummaries[groupId]
                )
            }
        )

        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "round-summary-$runId-$round",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                    groupId = null,
                    label = "Round summary — round ${round + 1}",
                    contentJson = runConfigJson.encodeToString(RoundSummaryRecord.serializer(), roundSummary),
                    runObjective = runObjective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = round,
                    archivedAtMs = now
                )
            )
        }.onFailure { e ->
            Napier.w("EvoKammerRunService: failed to archive round summary for round=$round: ${e.message}")
        }

        groupIds.forEach { groupId ->
            val groupSummary = roundSummary.groupSummaries.firstOrNull { it.groupId == groupId } ?: return@forEach
            runCatching {
                archiveDb.metaArchiveDao().insert(
                    MetaArchiveEntryEntity(
                        id = "round-summary-$runId-$round-$groupId",
                        runId = runId,
                        entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                        groupId = groupId,
                        label = "Round summary — $groupId — round ${round + 1}",
                        contentJson = runConfigJson.encodeToString(RoundGroupSummaryRecord.serializer(), groupSummary),
                        runObjective = runObjective,
                        finalScore = groupSummary.blindScore.weightedTotal,
                        isFromWinner = groupSummary.groupId == blindResult.winnerId,
                        generation = round,
                        archivedAtMs = now
                    )
                )
            }.onFailure { e ->
                Napier.w("EvoKammerRunService: failed to archive round summary for $groupId round=$round: ${e.message}")
            }
        }

        emit(EvoKammerEvent.EngineProgress(
            runId,
            "round_summary",
            "Round ${round + 1} summaries archived for ${groupIds.size} groups"
        ))
    }

    /**
     * Canonicalises the Genesis Council output by filling in the sealed test suite hash
     * when the suite JSON is present.
     *
     * @param startingField Raw council output.
     * @return The same field with a canonical suite hash if a suite was provided.
     */
    private fun canonicalizeStartingField(startingField: StartingField): StartingField {
        val canonicalSuiteField = if (startingField.sealedTestSuiteJson.isBlank()) {
            startingField
        } else {
            val suiteHash = canonicalTestSuiteHash(startingField.sealedTestSuiteJson)
            if (startingField.sealedTestSuiteHash == suiteHash) startingField
            else startingField.copy(sealedTestSuiteHash = suiteHash)
        }
        return canonicalSuiteField.copy(
            graderGroupCount = normalizeGraderGroupCount(
                workerGroupCount = canonicalSuiteField.groupCount,
                requestedGraderGroupCount = canonicalSuiteField.graderGroupCount
            )
        )
    }

    private fun normalizeGraderGroupCount(
        workerGroupCount: Int,
        requestedGraderGroupCount: Int
    ): Int {
        if (workerGroupCount <= 1) return 0
        return requestedGraderGroupCount.coerceAtLeast(DEFAULT_MULTI_GRADER_GROUP_COUNT)
    }

    private suspend fun resolveGraderStartingField(
        config: RunConfig,
        workerStartingField: StartingField
    ): StartingField? {
        if (workerStartingField.groupCount <= 1) {
            return null
        }

        val desiredCount = normalizeGraderGroupCount(
            workerGroupCount = workerStartingField.groupCount,
            requestedGraderGroupCount = workerStartingField.graderGroupCount
        )
        val fallbackField = buildFallbackGraderStartingField(
            runId = config.runId,
            workerStartingField = workerStartingField,
            graderGroupCount = desiredCount
        )

        if (config.isTestRun || genesisCouncilRunner == null) {
            return fallbackField
        }

        emit(
            EvoKammerEvent.EngineProgress(
                config.runId,
                "grader_genesis_start",
                "Grader Genesis Council starting — designing evaluator civilisations…"
            )
        )

        val graderObjective = buildGraderCouncilObjective(workerStartingField)
        val resolved = try {
            withTimeoutOrNull(5 * 60_000L) {
                genesisCouncilRunner.invoke(
                    "${config.runId}-grader-council",
                    graderObjective,
                    config.openRouterApiKey
                ) { progressMsg ->
                    scope.launch {
                        emit(
                            EvoKammerEvent.EngineProgress(
                                config.runId,
                                "grader_genesis_agent",
                                progressMsg
                            )
                        )
                    }
                }
            } ?: fallbackField
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: grader genesis council failed: ${e.message}")
            fallbackField
        }

        val normalized = normalizeGraderStartingField(
            runId = config.runId,
            candidate = resolved,
            desiredCount = desiredCount,
            workerStartingField = workerStartingField
        )
        emit(
            EvoKammerEvent.EngineProgress(
                config.runId,
                "grader_genesis_complete",
                "Grader Genesis Council complete — ${normalized.groupCount} grader groups"
            )
        )
        return normalized
    }

    private fun buildGraderCouncilObjective(workerStartingField: StartingField): String = buildString {
        append("Design a grader civilisation for the worker objective: ")
        append(workerStartingField.objective)
        append(". Produce several competing grader groups with distinct evaluation philosophies. ")
        append("Their task is to create deep, diverse, adversarial criteria for judging worker-group results. ")
        append("They must expose shallow or gamed outputs, reward reproducibility and evidence, ")
        append("and prefer hard-to-fake competence over surface polish.")
    }

    private fun normalizeGraderStartingField(
        runId: String,
        candidate: StartingField,
        desiredCount: Int,
        workerStartingField: StartingField
    ): StartingField {
        val canonical = canonicalizeStartingField(candidate)
        val resolvedProfiles = (0 until desiredCount).associate { index ->
            "grader-$index" to (
                canonical.groupProfiles.values.elementAtOrNull(index)
                    ?: GroupProfileType.entries[index % GroupProfileType.entries.size]
                )
        }
        val fallbackField = buildFallbackGraderStartingField(
            runId = runId,
            workerStartingField = workerStartingField,
            graderGroupCount = desiredCount
        )
        return canonical.copy(
            runId = runId,
            objective = canonical.objective.ifBlank { fallbackField.objective },
            groupCount = desiredCount,
            groupProfiles = resolvedProfiles,
            ringTopology = resolvedProfiles.keys.toList(),
            graderGroupCount = 0,
            primordialKb = canonical.primordialKb.ifEmpty { fallbackField.primordialKb },
            norms = canonical.norms.ifBlank { fallbackField.norms }
        )
    }

    private fun buildFallbackGraderStartingField(
        runId: String,
        workerStartingField: StartingField,
        graderGroupCount: Int
    ): StartingField {
        val profiles = (0 until graderGroupCount).associate { index ->
            "grader-$index" to GroupProfileType.entries[index % GroupProfileType.entries.size]
        }
        return StartingField.default(
            objective = buildGraderCouncilObjective(workerStartingField),
            runId = runId,
            groupCount = graderGroupCount,
            groupProfilesMap = profiles,
            budgetCeiling = workerStartingField.globalBudgetCeiling,
            generationCeiling = workerStartingField.generationCeiling
        ).copy(
            primordialKb = listOf(
                com.achulkov.evokammer.core.contract.SeedKBDocument(
                    id = "grader-seed-mission",
                    domain = "grader_seed",
                    content = """
                        Mission: evaluate worker civilisations with deep, adversarial rigor.
                        Reward evidence, reproducibility, comparative strength, and resilient execution.
                        Penalise unsupported claims, superficial polish, and brittle output.
                    """.trimIndent()
                ),
                com.achulkov.evokammer.core.contract.SeedKBDocument(
                    id = "grader-seed-objective",
                    domain = "grader_seed",
                    content = "Worker objective under evaluation: ${workerStartingField.objective}"
                )
            ),
            norms = "Be evidence-based. Prefer hard-to-fake signals. Design tie-breakers and failure-mode checks."
        )
    }

    /**
     * Applies explicit run-level model overrides so they win over council-generated pools.
     *
     * Pools are rewritten to singleton lists for overridden roles because the lifecycle
     * model resolver prefers pools before assignments.
     */
    private fun applyModelOverrides(
        startingField: StartingField,
        modelOverrides: Map<String, String>
    ): StartingField {
        if (modelOverrides.isEmpty()) return startingField

        val mergedAssignments = startingField.modelAssignment + modelOverrides
        val mergedPools = startingField.modelPools.toMutableMap()
        modelOverrides.forEach { (role, modelId) ->
            mergedPools[role] = listOf(modelId)
        }

        return startingField.copy(
            modelAssignment = mergedAssignments,
            modelPools = mergedPools
        )
    }

    /**
     * Persists the sealed test suite once, in the first competing group's substrate DB.
     *
     * The suite is stored write-once and locked. If the first group is unavailable or the
     * insert fails, startup aborts because there is no safe alternate canonical location.
     *
     * @param runId The active run identifier.
     * @param firstGroupId The first competing group ID.
     * @param startingField The council output containing the sealed suite.
     */
    private suspend fun persistSealedTestSuite(
        runId: String,
        firstGroupId: String?,
        startingField: StartingField
    ) {
        val groupId = firstGroupId
            ?: failStartup(runId, "sealed test suite could not be stored because no competing group was available")

        runCatching {
            val db = substrateFactory.get(runId, groupId)
            val suiteHash = canonicalTestSuiteHash(startingField.sealedTestSuiteJson)
            db.sealedTestSuiteDao().insert(
                com.achulkov.evokammer.substrate.db.entity.SealedTestSuiteEntity(
                    id = "sealed-suite-$runId",
                    runId = runId,
                    groupId = groupId,
                    suiteJson = startingField.sealedTestSuiteJson,
                    suiteHash = suiteHash,
                    isLocked = true,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            Napier.i("EvoKammerRunService: sealed test suite stored for run=$runId group=$groupId hash=$suiteHash")
        }.getOrElse { e ->
            failStartup(runId, "failed to store sealed test suite for run=$runId group=$groupId: ${e.message}", e)
        }
    }

    /**
     * Persists the full Genesis Council output as a write-once sealed archive snapshot.
     *
     * This is the canonical record for council-authored disclosure stage content and all
     * other StartingField fields used to boot the run.
     *
     * @param runId The active run identifier.
     * @param startingField The council output to seal.
     */
    private suspend fun persistSealedStartingField(
        runId: String,
        startingField: StartingField
    ) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val existing = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
        }.getOrElse { emptyList() }.firstOrNull { it.runId == runId }
        if (existing != null) {
            failStartup(runId, "sealed StartingField already exists for run=$runId")
        }

        val startingFieldJson = runConfigJson.encodeToString(
            StartingField.serializer(),
            startingField
        )

        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "starting-field-$runId",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                    groupId = null,
                    label = "StartingField — $runId",
                    contentJson = startingFieldJson,
                    runObjective = startingField.objective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
        }.getOrElse { e ->
            failStartup(runId, "failed to seal StartingField for run=$runId: ${e.message}", e)
        }

        val stored = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
        }.getOrElse { emptyList() }.firstOrNull { it.id == "starting-field-$runId" }
            ?: failStartup(runId, "sealed StartingField not retrievable for run=$runId")

        val decoded = runCatching {
            runConfigJson.decodeFromString(StartingField.serializer(), stored.contentJson)
        }.getOrElse { e ->
            failStartup(runId, "sealed StartingField readback could not be decoded for run=$runId: ${e.message}", e)
        }

        if (decoded.stageContents != startingField.stageContents ||
            decoded.sealedTestSuiteJson != startingField.sealedTestSuiteJson ||
            decoded.sealedTestSuiteHash != startingField.sealedTestSuiteHash
        ) {
            failStartup(runId, "sealed StartingField readback mismatch for run=$runId")
        }

        Napier.i("EvoKammerRunService: sealed StartingField stored for run=$runId stageCount=${startingField.stageContents.size}")
    }

    /**
     * Loads the sealed [StartingField] snapshot for a previously completed run.
     *
     * Continuation relies on the archived snapshot rather than any live runtime state so the
     * source run stays immutable and reproducible.
     *
     * @param runId The source run identifier.
     * @return The sealed [StartingField], or null if the run has no archived snapshot yet.
     */
    private suspend fun loadSealedStartingField(runId: String): StartingField? = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val completed = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }.any { it.runId == runId }
        if (!completed) {
            failStartup(runId, "continuation source run=$runId is not completed yet")
        }
        val stored = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
        }.getOrElse { emptyList() }
            .firstOrNull { it.entryType == RunArchiveService.ENTRY_STARTING_FIELD }
            ?: return@withContext null

        runCatching {
            runConfigJson.decodeFromString(StartingField.serializer(), stored.contentJson)
        }.getOrElse { e ->
            failStartup(runId, "sealed StartingField readback could not be decoded for run=$runId: ${e.message}", e)
        }
    }

    /**
     * Builds a continuation seed from a sealed source run.
     *
     * The source run provides the immutable baseline; the new launch config only overrides the
     * objective and generation ceiling so the continuation keeps the original group layout and
     * council-authored substrate seed intact.
     *
     * @param source The source run's sealed [StartingField].
     * @param config The new run configuration.
     * @return A derived [StartingField] for the fresh run ID.
     */
    private fun buildContinuationStartingField(
        source: StartingField,
        config: RunConfig
    ): StartingField = source.copy(
        runId = config.runId,
        objective = config.objective,
        generationCeiling = config.rounds,
        continuedFromRunId = source.runId
    )

    /**
     * Copies the source run's artifact directory into the new run's artifact root.
     *
     * The agent tools read from the mounted filesystem, so the continuation run must receive the
     * actual files, not only their metadata snapshots.
     *
     * @param sourceRunId The completed run being continued.
     * @param targetRunId The fresh run that will consume the copied artifacts.
     */
    private suspend fun copyContinuationArtifacts(
        sourceRunId: String,
        targetRunId: String,
        sourceArtifacts: List<RunInputArtifact>
    ) {
        val sourceRoot = resolveRunArtifactsRoot(sourceRunId)
        if (!sourceRoot.exists()) {
            Napier.w("EvoKammerRunService: source artifacts root missing for continuation source=$sourceRunId")
            return
        }

        val targetRoot = resolveRunArtifactsRoot(targetRunId)
        targetRoot.mkdirs()

        for (artifact in sourceArtifacts) {
            val sourceFile = File(sourceRoot, artifact.relativePath)
            if (!sourceFile.exists()) {
                failStartup(sourceRunId, "continuation source artifact missing: ${artifact.relativePath}")
            }

            val targetFile = File(targetRoot, artifact.relativePath)
            targetFile.parentFile?.mkdirs()
            Files.copy(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
        }
    }

    /**
     * Merges source-run and launch-time input artifacts into a single canonical list.
     *
     * New uploads take precedence on path collisions so users can replace a continued artifact
     * without mutating the source run.
     *
     * @param sourceArtifacts Artifacts inherited from the sealed continuation seed.
     * @param uploadedArtifacts Fresh uploads materialised for the new run.
     * @return A de-duplicated list keyed by relative path.
     */
    private fun mergeInputArtifacts(
        sourceArtifacts: List<RunInputArtifact>,
        uploadedArtifacts: List<RunInputArtifact>
    ): List<RunInputArtifact> {
        if (sourceArtifacts.isEmpty() && uploadedArtifacts.isEmpty()) return emptyList()

        val mergedByPath = LinkedHashMap<String, RunInputArtifact>(sourceArtifacts.size + uploadedArtifacts.size)
        sourceArtifacts.forEach { artifact ->
            mergedByPath[artifact.relativePath] = artifact
        }
        uploadedArtifacts.forEach { artifact ->
            mergedByPath[artifact.relativePath] = artifact
        }
        return mergedByPath.values.toList()
    }

    /**
     * Verifies that the sealed test suite written at genesis is immediately readable.
     *
     * This provides a bootstrap-time consumer path for the suite so the next stage can rely
     * on a persisted canonical copy instead of only trusting the in-memory council output.
     *
     * @param runId The active run identifier.
     * @param firstGroupId The first competing group ID.
     * @param expectedHash The canonical suite hash we expect to read back.
     */
    private suspend fun verifySealedTestSuite(
        runId: String,
        firstGroupId: String?,
        expectedHash: String
    ) {
        val groupId = firstGroupId
            ?: failStartup(runId, "sealed test suite could not be verified because no competing group was available")

        val stored = runCatching {
            substrateFactory.get(runId, groupId).sealedTestSuiteDao().get(runId, groupId)
        }.getOrElse { e ->
            failStartup(runId, "failed to read sealed test suite for run=$runId group=$groupId: ${e.message}", e)
        } ?: failStartup(runId, "sealed test suite not retrievable for run=$runId group=$groupId")

        val storedHash = canonicalTestSuiteHash(stored.suiteJson)
        if (stored.suiteHash != expectedHash || storedHash != expectedHash) {
            failStartup(
                runId,
                "sealed test suite hash mismatch for run=$runId group=$groupId expected=$expectedHash actual=${stored.suiteHash} canonical=$storedHash"
            )
        }

        if (!stored.isLocked) {
            failStartup(runId, "sealed test suite for run=$runId group=$groupId was not locked after persistence")
        }

        Napier.i("EvoKammerRunService: sealed test suite verified for run=$runId group=$groupId")
    }

    /**
     * Computes the canonical SHA-256 hash for the sealed test suite JSON.
     *
     * @param suiteJson Raw JSON emitted by the Genesis Council.
     * @return Lowercase hex SHA-256 digest.
     */
    private fun canonicalTestSuiteHash(suiteJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(suiteJson.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Materialises uploaded run-start artifacts to the run artifact directory and returns
     * their canonical metadata records.
     *
     * @param runId The current run identifier.
     * @param uploads Uploaded artifacts supplied at run start.
     * @return Canonical, file-backed artifact metadata.
     */
    private fun materializeRunInputArtifacts(
        runId: String,
        uploads: List<RunInputArtifactUpload>
    ): MaterializedRunInputArtifacts {
        if (uploads.isEmpty()) return MaterializedRunInputArtifacts(emptyList(), emptySet())

        val artifactsRoot = resolveRunArtifactsRoot(runId)
        artifactsRoot.mkdirs()

        val allowedHosts = mutableSetOf<String>()
        val artifacts = uploads.map { upload ->
            val bytes = runCatching { Base64.getDecoder().decode(upload.contentBase64) }
                .getOrElse { e -> throw IllegalArgumentException("Invalid base64 content for ${upload.relativePath}: ${e.message}", e) }

            require(upload.relativePath.isNotBlank()) {
                "Artifact path must not be blank"
            }

            val normalizedPath = java.nio.file.Path.of(upload.relativePath).normalize()
            require(!normalizedPath.isAbsolute) {
                "Artifact path must be relative to the run artifact root: ${upload.relativePath}"
            }
            val normalisedRelativePath = normalizedPath.toString()
            require(!normalisedRelativePath.startsWith("..")) {
                "Artifact path must stay within the run artifact root: ${upload.relativePath}"
            }

            val targetFile = File(artifactsRoot, normalisedRelativePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(bytes)

            if (upload.kind == RunInputArtifactKind.TEXT) {
                allowedHosts += ContextUrlAllowlistExtractor.extractHosts(bytes.toString(Charsets.UTF_8))
            }

            RunInputArtifact(
                relativePath = normalisedRelativePath,
                sourceName = upload.sourceName,
                mimeType = upload.mimeType,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
                kind = upload.kind,
                textPreview = upload.textPreview?.takeIf { it.isNotBlank() }
                    ?.let { compactArtifactPreview(it) }
                    ?: extractTextPreview(bytes, upload.kind)
            )
        }

        return MaterializedRunInputArtifacts(
            artifacts = artifacts,
            allowedHosts = allowedHosts
        )
    }

    /** Returns the shared run-artifacts root directory for this run. */
    private fun resolveRunArtifactsRoot(runId: String): File = File(
        resolveRunRoot(runId),
        "artifacts"
    )

    private fun resolveRunRoot(runId: String): File = substrateFactory.dbFilePath(runId, "group-0")
        .parentFile?.parentFile?.parentFile
        ?: error("Unable to resolve run root for $runId")

    private fun extractTextPreview(bytes: ByteArray, kind: RunInputArtifactKind): String? = when (kind) {
        RunInputArtifactKind.TEXT -> buildTextArtifactPreview(bytes)
        RunInputArtifactKind.IMAGE -> "Image artifact (${bytes.size} bytes). Use read_run_artifact or the archive download for the raw file."
        RunInputArtifactKind.BINARY -> "Binary artifact (${bytes.size} bytes). Use read_run_artifact or the archive download for the raw file."
    }

    private fun buildTextArtifactPreview(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val snippet = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString(" ")
            .ifBlank {
                text.replace(Regex("\\s+"), " ").trim()
            }
            .take(MAX_RUN_ARTIFACT_PREVIEW_CHARS)

        return snippet.ifBlank {
            "Text artifact (${bytes.size} bytes). Use read_run_artifact for the full file."
        }
    }

    private fun compactArtifactPreview(preview: String): String =
        preview.replace(Regex("\\s+"), " ").trim().take(MAX_RUN_ARTIFACT_PREVIEW_CHARS)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Aborts startup after a sealed-suite invariant failure and clears any partial state.
     *
     * @param runId The active run identifier.
     * @param reason Human-readable failure reason.
     * @param cause Optional underlying exception.
     * @throws IllegalStateException always.
     */
    private suspend fun failStartup(
        runId: String,
        reason: String,
        cause: Throwable? = null
    ): Nothing {
        val message = "EvoKammerRunService: startup aborted for run=$runId - $reason"
        if (cause == null) Napier.e(message) else Napier.e(message, cause)
        updatePersistedRunStatus(runId, PersistedRunStatus.FAILED)
        emit(EvoKammerEvent.RunAborted(runId, reason))
        cleanup(runId)
        throw IllegalStateException(reason, cause)
    }

    /**
     * Seeds each group's substrate with primordial data from the [StartingField].
     *
     * Inserts:
     * - KB documents from [StartingField.primordialKb]
     * - KG facts from [StartingField.primordialKgFacts]
     * - WIP tasks parsed from [StartingField.nodeGraph] (JSON array of
     *   `{"nodeId", "description"}` objects; silently skips malformed nodes)
     *
     * Called once per run after the Genesis Council completes, before the first round spawn.
     */
    private suspend fun seedPrimordialData(
        runId: String,
        groupIds: List<String>,
        workerStartingField: StartingField,
        graderGroupIds: List<String>,
        graderStartingField: StartingField?
    ) {
        val workerCounts = seedPrimordialDataForGroups(
            runId = runId,
            groupIds = groupIds,
            startingField = workerStartingField,
            seedOverlayBuilder = { _, _ -> emptyList() }
        )
        val graderCounts = graderStartingField?.let { seed ->
            seedPrimordialDataForGroups(
                runId = runId,
                groupIds = graderGroupIds,
                startingField = seed,
                seedOverlayBuilder = { groupId, index ->
                    buildGraderOverlaySeedDocuments(
                        runId = runId,
                        groupId = groupId,
                        workerStartingField = workerStartingField,
                        graderStartingField = seed,
                        profileType = seed.groupProfiles[groupId]
                            ?: GroupProfileType.entries[index % GroupProfileType.entries.size]
                    )
                }
            )
        } ?: SeedInsertCounts()

        Napier.i(
            "EvoKammerRunService: primordial seeding complete — " +
                "KB=${workerCounts.kb + graderCounts.kb} KG=${workerCounts.kg + graderCounts.kg} " +
                "WIP=${workerCounts.wip + graderCounts.wip} tools=${workerCounts.tools + graderCounts.tools} " +
                "(across ${groupIds.size} competing + ${graderGroupIds.size} grader groups)"
        )
    }

    private suspend fun seedPrimordialDataForGroups(
        runId: String,
        groupIds: List<String>,
        startingField: StartingField,
        seedOverlayBuilder: (groupId: String, index: Int) -> List<KnowledgeBaseEntryEntity>
    ): SeedInsertCounts {
        if (groupIds.isEmpty()) return SeedInsertCounts()
        if (startingField.primordialKb.isEmpty() &&
            startingField.primordialKgFacts.isEmpty() &&
            startingField.primordialTools.isEmpty() &&
            startingField.nodeGraph == "{}" &&
            startingField.deliverableSkeletonTreeJson == "{}" &&
            startingField.sealedTestSuiteJson.isBlank() &&
            startingField.inputArtifacts.isEmpty()
        ) {
            return SeedInsertCounts()
        }

        val now = System.currentTimeMillis()
        var kbCount = 0
        var kgCount = 0
        var wipCount = 0
        var toolCount = 0

        for ((index, groupId) in groupIds.withIndex()) {
            try {
                val db = substrateFactory.get(runId, groupId)
                val deliverableTree = deliverableSkeletonTreeStore.initialise(runId, groupId, startingField)

                if (startingField.primordialKb.isNotEmpty()) {
                    val kbEntities = startingField.primordialKb.map { seed ->
                        KnowledgeBaseEntryEntity(
                            id = "${seed.id}-$groupId",
                            content = seed.content,
                            domain = seed.domain,
                            sourceAgentId = "genesis-council",
                            generation = 0,
                            trustScore = 0.9,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeBaseDao().insertAll(kbEntities)
                    kbCount += kbEntities.size
                }

                val overlayEntities = seedOverlayBuilder(groupId, index)
                if (overlayEntities.isNotEmpty()) {
                    db.knowledgeBaseDao().insertAll(overlayEntities)
                    kbCount += overlayEntities.size
                }

                val uploadedDocs = startingField.inputArtifacts.filter { it.kind == RunInputArtifactKind.TEXT }
                if (uploadedDocs.isNotEmpty()) {
                    val uploadedKbEntities = uploadedDocs.map { artifact ->
                        KnowledgeBaseEntryEntity(
                            id = "uploaded-${artifact.sha256.take(12)}-$groupId",
                            content = buildString {
                                appendLine("Uploaded context artifact: ${artifact.sourceName}")
                                appendLine("Path: ${artifact.relativePath}")
                                appendLine("MIME: ${artifact.mimeType}")
                                appendLine("SHA-256: ${artifact.sha256}")
                                appendLine()
                                appendLine(artifact.textPreview ?: "(no text preview)")
                            },
                            domain = "uploaded_context",
                            sourceAgentId = "user-upload",
                            generation = 0,
                            trustScore = 1.0,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeBaseDao().insertAll(uploadedKbEntities)
                    kbCount += uploadedKbEntities.size
                }

                if (startingField.primordialKgFacts.isNotEmpty()) {
                    val kgEntities = startingField.primordialKgFacts.map { seed ->
                        com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity(
                            id = "${seed.id}-$groupId",
                            subject = seed.subject,
                            relation = seed.relation,
                            objectNode = seed.`object`,
                            confidence = seed.confidence,
                            sourceAgentId = "genesis-council",
                            generation = 0,
                            trustScore = 0.9,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeGraphFactDao().insertAll(kgEntities)
                    kgCount += kgEntities.size
                }

                val wipNodes = deliverableTree.nodes.filterNot { it.nodeId == deliverableTree.rootNodeId }
                if (wipNodes.isNotEmpty()) {
                    val wipEntities = wipNodes.map { node ->
                        val description = listOfNotNull(node.title.takeIf { it.isNotBlank() }, node.pathHint)
                            .joinToString(" - ")
                            .ifBlank { node.nodeId }
                        com.achulkov.evokammer.substrate.db.entity.WipTaskEntity(
                            nodeId = node.nodeId,
                            runId = runId,
                            groupId = groupId,
                            description = description,
                            status = "unclaimed",
                            lastChangedGeneration = 0,
                            linkedNodeId = node.nodeId,
                            dstNodeRef = node.nodeId
                        )
                    }
                    db.wipTaskDao().insertAll(wipEntities)
                    wipCount += wipEntities.size
                }

                if (startingField.primordialTools.isNotEmpty()) {
                    val toolMaterializer = ToolMaterializer(
                        substrateFactory.dbFilePath(runId, groupId).parentFile
                            ?.resolve("tools")
                            ?: error("Missing tools mount host path for group=$groupId")
                    )
                    val toolEntities = startingField.primordialTools.map { seed ->
                        com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity(
                            id = "${seed.id}-$groupId",
                            name = seed.name,
                            version = 1,
                            authorAgentId = "genesis-council",
                            successRate = 1.0,
                            inputSchemaJson = seed.inputSchemaJson,
                            outputSchemaJson = seed.outputSchemaJson,
                            domain = seed.domain,
                            code = seed.code,
                            codeLanguage = "shell",
                            generation = 0,
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    for (seed in toolEntities) {
                        toolMaterializer.materialize(seed)
                        db.toolRegistryDao().insert(seed)
                    }
                    toolCount += toolEntities.size
                }
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: primordial seed failed for group=$groupId: ${e.message}")
            }
        }

        return SeedInsertCounts(kb = kbCount, kg = kgCount, wip = wipCount, tools = toolCount)
    }

    private fun buildGraderOverlaySeedDocuments(
        runId: String,
        groupId: String,
        workerStartingField: StartingField,
        graderStartingField: StartingField,
        profileType: GroupProfileType
    ): List<KnowledgeBaseEntryEntity> {
        val now = System.currentTimeMillis()
        val profileLens = when (profileType) {
            GroupProfileType.Library -> "Emphasise evidence density, source traceability, and coverage breadth."
            GroupProfileType.Factory -> "Emphasise reproducibility, operational robustness, and concrete deliverables."
            GroupProfileType.Commune -> "Emphasise clarity, accessibility, and coherence across artefacts."
            GroupProfileType.Monastery -> "Emphasise rigor, internal consistency, and disciplined scoring."
            GroupProfileType.Mercenary -> "Emphasise competitive differentiation, decisive tradeoffs, and exposed weaknesses."
        }
        return listOf(
            KnowledgeBaseEntryEntity(
                id = "grader-overlay-mission-$groupId",
                content = """
                    You are $groupId, a grader civilisation created by a separate Genesis Council run.
                    Worker objective: ${workerStartingField.objective}
                    Your task: generate deep, diverse, adversarial criteria that expose shallow work.
                    ${graderStartingField.norms}
                """.trimIndent(),
                domain = "grader_seed",
                sourceAgentId = "grader-genesis-council",
                generation = 0,
                trustScore = 1.0,
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = now
            ),
            KnowledgeBaseEntryEntity(
                id = "grader-overlay-lens-$groupId",
                content = "Evaluation lens for $groupId (${profileType.name}): $profileLens",
                domain = "grader_seed",
                sourceAgentId = "grader-genesis-council",
                generation = 0,
                trustScore = 1.0,
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = now
            )
        )
    }

    private data class SeedInsertCounts(
        val kb: Int = 0,
        val kg: Int = 0,
        val wip: Int = 0,
        val tools: Int = 0
    )

    /**
     * Runs post-mortem fitness scoring and Legacy Protocol extraction for all groups
     * after the CriticPipeline has processed each round's submissions.
     *
     * Per spec §13 (Epigenetic Inheritance):
     * - Top 20% of agents by fitness score are eligible for Legacy Protocol extraction.
     * - One protocol per role; merged via CrossoverEngine if multiple top agents share a role.
     * - Protocol expires after 2 generations; failure map is promoted to Dead Ends Registry on expiry.
     */
    private suspend fun runFitnessAndEpigenetics(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, EpigeneticRoundSummary> = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val scorer = FitnessScorer(
                        eventLogDao = db.eventLogDao(),
                        knowledgeBaseDao = db.knowledgeBaseDao(),
                        stagingDao = db.stagingDao(),
                        toolRegistryDao = db.toolRegistryDao()
                    )
                    val scores = scorer.scoreGeneration(config.runId, groupId, round)
                    if (scores.isEmpty()) {
                        return@async groupId to EpigeneticRoundSummary(0, 0, 0, 0, 0)
                    }

                    val topScores = scorer.topPercentile(scores, 0.20)
                    val topAgentIds = topScores.map { it.agentId }.toSet()

                    Napier.i("EvoKammerRunService: fitness scored ${scores.size} agents for group=$groupId round=$round; top ${topAgentIds.size} eligible for legacy protocol")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "fitness",
                        "[$groupId] Fitness scored ${scores.size} agents (round $round) — top ${topAgentIds.size} extracting legacy protocol"))

                    val extractor = LegacyProtocolExtractor(
                        legacyProtocolDao = db.legacyProtocolDao(),
                        stagingDao = db.stagingDao(),
                        eventLogDao = db.eventLogDao()
                    )
                    val protocols = extractor.extract(
                        fitnessScores = topScores,
                        topAgentIds = topAgentIds,
                        currentGeneration = round,
                        runId = config.runId,
                        groupId = groupId
                    )
                    if (protocols.isNotEmpty()) {
                        Napier.i("EvoKammerRunService: extracted ${protocols.size} legacy protocol(s) for group=$groupId round=$round")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "legacy_protocol",
                            "[$groupId] ${protocols.size} legacy protocol(s) extracted for round $round"))
                    }

                    val expiryResult = sweepExpiredLegacyProtocols(db, config.runId, groupId, round)
                    if (expiryResult.expiredProtocols > 0) {
                        Napier.i("EvoKammerRunService: expired ${expiryResult.expiredProtocols} legacy protocol(s), inserted ${expiryResult.deadEndsInserted} Dead End(s) for group=$groupId round=$round")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "legacy_expiry",
                            "[$groupId] ${expiryResult.expiredProtocols} protocol(s) expired, ${expiryResult.deadEndsInserted} failure map(s) promoted to Dead Ends"))
                    }

                    groupId to EpigeneticRoundSummary(
                        fitnessScoredAgents = scores.size,
                        fitnessTopEligibleAgents = topAgentIds.size,
                        legacyProtocolsExtracted = protocols.size,
                        legacyProtocolsExpired = expiryResult.expiredProtocols,
                        deadEndsInserted = expiryResult.deadEndsInserted
                    )
                } catch (e: Exception) {
                    Napier.w("EvoKammerRunService: fitness/epigenetics failed for group=$groupId round=$round: ${e.message}")
                    groupId to EpigeneticRoundSummary(0, 0, 0, 0, 0)
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Promotes expired Legacy Protocol failure maps into the Dead Ends KB channel.
     *
     * Kept as a dedicated lifecycle step so protocol expiry can be verified independently
     * from the fitness scoring and extraction pass.
     */
    internal suspend fun sweepExpiredLegacyProtocols(
        db: EvoKammerDatabase,
        runId: String,
        groupId: String,
        round: Int
    ): LegacyExpirySweepResult {
        val expiredProtocols = runCatching {
            db.legacyProtocolDao().getExpired(runId, groupId, round)
        }.getOrElse { emptyList() }

        var deadEndsInserted = 0
        for (protocol in expiredProtocols) {
            if (protocol.failureMapJson.isBlank() || protocol.failureMapJson == "{}") continue

            runCatching {
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "dead-end-${protocol.id}",
                        content = "DEAD END [Role: ${protocol.role}, Gen: ${protocol.extractedFromGeneration}]: " +
                            protocol.failureMapJson,
                        domain = "dead_ends",
                        sourceAgentId = "epigenetic_archive",
                        generation = round,
                        trustScore = 1.0,
                        isDeadEnd = true,
                        status = "active",
                        groupId = groupId,
                        runId = runId,
                        createdAtMs = System.currentTimeMillis()
                    )
                )
                deadEndsInserted++
            }.onFailure { e ->
                Napier.w("EvoKammerRunService: failed to insert Dead End from protocol ${protocol.id}: ${e.message}")
            }
        }

        return LegacyExpirySweepResult(
            expiredProtocols = expiredProtocols.size,
            deadEndsInserted = deadEndsInserted
        )
    }

    /**
     * Phase 7.5: Invokes the Final Synthesiser for each group to produce a coherent
     * deliverable from the deliverable skeleton tree.
     *
     * The output is written to each group's control directory under `deliverable/`.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs.
     */
    private suspend fun runFinalSynthesisers(config: RunConfig, groupIds: List<String>) {
        for (groupId in groupIds) {
            try {
                val db = substrateFactory.get(config.runId, groupId)
                val synthesiser = FinalSynthesiser(
                    treeStore = deliverableSkeletonTreeStore,
                    db = db,
                    runId = config.runId,
                    groupId = groupId
                )
                val outputDir = File(substrateFactory.groupControlDir(config.runId, groupId), "deliverable")
                val outputFile = synthesiser.synthesise(
                    format = FinalSynthesiser.OutputFormat.MARKDOWN,
                    outputDir = outputDir,
                    objective = config.objective
                )
                Napier.i("EvoKammerRunService: final deliverable synthesised for group=$groupId → ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Napier.w(e) { "EvoKammerRunService: final synthesis failed for group=$groupId" }
            }
        }
    }

    private fun cleanup(runId: String) {
        activeWatchdog?.stop()
        activeWatchdog = null
        activeInspectorRunner?.stopAll()
        activeInspectorRunner = null
        activeBlindTournamentService?.stop()
        activeBlindTournamentService = null
        stasisFlags.clear()
        liveLifecycleManagers.clear()
        activeCoordinator = null
        activeGraderGroupManager = null
        activeDisclosureEngine = null
        activeResourceMapEngine = null
        activeRunId = null
        currentRound = null
        groupSummaries = emptyList()
        currentStatus = EngineStatus.IDLE
        activeModelAssignments = emptyMap()
        activeModelPools = emptyMap()
        activeGroupProfiles = emptyList()
        activeRolePrompts = emptyMap()
        activeGenerationCeiling = 100
        activeGlobalBudgetCeiling = 0.0
        activeKgSchema = "{}"
        activeScoreNormalization = emptyMap()
        activeSealedTestSuiteJson = ""
        activeSealedTestSuiteHash = ""
        activeGraderStartingField = null
        checkInGateOpenRound = null
        latestGroupDimensionScores.clear()
        latestGroupRoundAssessments.clear()
        pendingWipCompletions.clear()
        auditorSpawnedGroups.clear()
        nestedHierarchyTriggeredGroups.clear()
        runFinalOutputSpecs.remove(runId)
        Napier.i("EvoKammerRunService: cleanup complete for run $runId")
    }

    private fun requireActiveRun(runId: String) {
        check(activeRunId == runId) { "Run $runId is not the active run (active=$activeRunId)" }
    }

    private suspend fun emit(event: EvoKammerEvent) {
        _events.emit(event)
    }

    /**
     * Resolves the path to the agent runner JAR.
     *
     * In development, check the system property. In distribution, the JAR
     * is bundled alongside the app package.
     */
    private fun resolveAgentJarPath(): String {
        return System.getProperty(
            "evokammer.agent.jar.path",
            "agent-runner.jar"
        )
    }

    private fun recordWipCompletion(groupId: String, nodeId: String) {
        pendingWipCompletions.computeIfAbsent(groupId) { ConcurrentLinkedQueue() }.add(nodeId)
    }

    /**
     * Writes anonymised scoring failures into every grader group's staging DB.
     *
     * Failure telemetry is system-generated, so it is inserted directly as promoted
     * staging content rather than waiting for Critic review.
     */
    internal suspend fun routeFailureTelemetry(
        runId: String,
        graderGroupIds: List<String>,
        telemetry: FailureTelemetry
    ) {
        val now = System.currentTimeMillis()
        val content = buildString {
            appendLine("Failure telemetry")
            appendLine("Round: ${telemetry.roundIndex}")
            appendLine("Failure modes: ${telemetry.failureModeIds.joinToString(",").ifBlank { "none" }}")
            appendLine("Automated tests failed: ${telemetry.automatedTestsFailed}")
            appendLine("Raw score: ${String.format(java.util.Locale.ROOT, "%.3f", telemetry.rawScore)}")
            appendLine("Weighted score: ${String.format(java.util.Locale.ROOT, "%.3f", telemetry.weightedScore)}")
            appendLine("Grading sheets: ${telemetry.gradingSheetIds.joinToString(",")}")
        }

        graderGroupIds.forEach { graderGroupId ->
            try {
                val db = substrateFactory.getGrader(runId, graderGroupId)
                db.stagingDao().insert(
                    com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity(
                        id = "failure-telemetry-${telemetry.groupId}-${telemetry.roundIndex}-$graderGroupId",
                        agentId = "system",
                        role = "ScoringEngine",
                        type = com.achulkov.evokammer.core.model.SubmissionType.FAILURE_TELEMETRY.name,
                        content = content,
                        wipTaskRef = null,
                        speculative = false,
                        outlier = false,
                        budgetSignature = "system:failure-telemetry",
                        status = "promoted",
                        rejectionReason = null,
                        criticAgentId = null,
                        initialTrustScore = 1.0,
                        generation = telemetry.roundIndex,
                        groupId = graderGroupId,
                        runId = runId,
                        submittedAtMs = now,
                        reviewedAtMs = now
                    )
                )
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: failed to route failure telemetry to grader=$graderGroupId: ${e.message}")
            }
        }
    }

    /**
     * Builds disclosure stages from the council output when present.
     *
     * The council-authored `stageContents` is the canonical source. The injected
     * `disclosureStages` list is retained as a fallback for older callers.
     */
    internal fun buildDisclosureStages(startingField: StartingField): List<DisclosureStage> {
        if (startingField.stageContents.isNotEmpty()) {
            return startingField.stageContents.mapIndexed { index, content ->
                DisclosureStage(
                    stageIndex = index,
                    title = "Council Stage ${index + 1}",
                    constraintFramedContent = content,
                    // Ordinary agents only see the constraint frame; MetaPlanner gets the
                    // run objective as the hidden goal frame for progress assessment.
                    goalFramedContent = startingField.objective,
                    unlockCondition = "Pass check-in ${index + 1}"
                )
            }
        }
        return disclosureStages.orEmpty()
    }

    /**
     * Spawns an Auditor once per group when a high-severity Inspector flag arrives.
     *
     * Auditor spawning is rate-limited per group so repeated alerts do not flood the run
     * with redundant policy agents.
     */
    internal suspend fun handleInspectorFlag(
        config: RunConfig,
        signal: WatchdogSignal.InspectorFlag,
        groupIds: List<String>
    ) {
        if (signal.severity != WatchdogSeverity.HIGH && signal.severity != WatchdogSeverity.CRITICAL) return
        if (signal.suggestedRole != null && signal.suggestedRole != Role.Auditor) return
        if (!auditorSpawnedGroups.add(signal.groupId)) return

        val targetGroupId = signal.groupId
        val lm = liveLifecycleManagers[targetGroupId]
        if (lm == null) {
            Napier.w("EvoKammerRunService: cannot spawn Auditor for $targetGroupId — no LifecycleManager")
            auditorSpawnedGroups.remove(signal.groupId)
            return
        }

        val profileType = activeGroupProfiles.getOrElse(groupIds.indexOf(targetGroupId)) {
            activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library
        }
        val profile = GroupProfile.default(profileType)
        val budgetPerAgent = (profile.spawnBudget * 0.5).coerceAtLeast(1.0)
        val rolePrompt = activeRolePrompts[Role.Auditor.name]
            ?: "You are an Auditor. Investigate high-severity constitutional anomalies, quarantine unsafe outputs, and write system flags."
        val stageDisclosure = buildString {
            appendLine("High-severity Inspector flag")
            appendLine("Group: $targetGroupId")
            appendLine("Severity: ${signal.severity}")
            appendLine("Description: ${signal.description}")
            appendLine("Action: inspect recent activity and quarantine unsafe submissions.")
        }

        emit(EvoKammerEvent.EngineProgress(
            config.runId,
            "auditor_spawn",
            "[$targetGroupId] Spawning Auditor for Inspector flag ${signal.severity}: ${signal.description}"
        ))

        try {
            lm.spawnSpecialAgent(
                role = Role.Auditor,
                generation = currentRound ?: 0,
                budgetPerAgent = budgetPerAgent,
                stageDisclosure = stageDisclosure,
                modelAssignments = activeModelAssignments,
                modelPools = activeModelPools,
                rolePrompt = rolePrompt,
                scope = scope
            )
        } catch (e: Exception) {
            auditorSpawnedGroups.remove(signal.groupId)
            Napier.w("EvoKammerRunService: Auditor spawn failed for $targetGroupId: ${e.message}")
            emit(EvoKammerEvent.EngineProgress(
                config.runId,
                "auditor_spawn_failed",
                "[$targetGroupId] Auditor spawn failed: ${e.message ?: "unknown error"}"
            ))
        }
    }

    /**
     * Handles a single watchdog signal in the same way the live collector does.
     *
     * Exposed as an internal boundary so the budget-critical pause behavior and other
     * watchdog transitions can be regression-tested without standing up a full run loop.
     *
     * @param config Current run configuration.
     * @param signal The watchdog signal to handle.
     * @param groupIds Competing group IDs in the run.
     */
    internal suspend fun handleWatchdogSignal(
        config: RunConfig,
        signal: WatchdogSignal,
        groupIds: List<String>
    ) {
        when (signal) {
            is WatchdogSignal.AgentStasis -> {
                stasisFlags[signal.groupId] = true
                emit(EvoKammerEvent.EngineProgress(
                    config.runId,
                    "watchdog_stasis",
                    "[${signal.groupId}] Stasis detected — agent ${signal.agentId} idle ${signal.idleMs / 1000}s"
                ))
            }
            is WatchdogSignal.InspectorFlag -> {
                emit(EvoKammerEvent.EngineProgress(
                    config.runId,
                    "watchdog_flag",
                    "[${signal.groupId}] Inspector flag ${signal.severity}: ${signal.description}"
                ))
                handleInspectorFlag(config, signal, groupIds)
            }
            is WatchdogSignal.BudgetCritical -> {
                // A-7: When remaining budget hits the last 5% of the global ceiling,
                // pause new agent spawning so the remaining budget is not wasted on
                // spawning agents that cannot run a full generation.
                val ceiling = activeGlobalBudgetCeiling
                if (ceiling > 0 && signal.remainingBudget <= ceiling * 0.05) {
                    currentStatus = EngineStatus.PAUSED
                    emit(EvoKammerEvent.EngineProgress(
                        config.runId,
                        "budget_pause",
                        "[${signal.groupId}] Budget at ≤5% of ceiling — spawning paused. " +
                            "remaining=${signal.remainingBudget}, ceiling=$ceiling"
                    ))
                } else {
                    emit(EvoKammerEvent.EngineProgress(
                        config.runId,
                        "watchdog_budget",
                        "[${signal.groupId}] Budget critical — remaining=${signal.remainingBudget}"
                    ))
                }
            }
            is WatchdogSignal.KgAnomaly -> {
                emit(EvoKammerEvent.EngineProgress(
                    config.runId,
                    "watchdog_kg",
                    "[${signal.groupId}] KG anomaly ${signal.anomalyType}: ${signal.details}"
                ))
            }
            is WatchdogSignal.CheckInGateOpen -> {
                // B-5: Open the check-in gate — processDisclosureCheckIns will now run.
                checkInGateOpenRound = signal.roundIndex
                Napier.d("EvoKammerRunService: check-in gate opened for round ${signal.roundIndex}")
            }
            is WatchdogSignal.CheckInGateClosed -> {
                // B-5: Close the check-in gate — no more check-ins for this round.
                if (checkInGateOpenRound == signal.roundIndex) {
                    checkInGateOpenRound = null
                }
                Napier.d("EvoKammerRunService: check-in gate closed for round ${signal.roundIndex}")
            }
        }
    }

    private fun drainWipCompletions(groupId: String): List<String> {
        val queue = pendingWipCompletions[groupId] ?: return emptyList()
        val completions = buildList {
            while (true) {
                val nodeId = queue.poll() ?: break
                add(nodeId)
            }
        }
        if (queue.isEmpty()) {
            pendingWipCompletions.remove(groupId, queue)
        }
        return completions.distinct()
    }
    /**
     * Registers the sealed grading sheet for the round if it has not been attached yet.
     *
     * The live round loop uses this once per round so the grader manager can evaluate a
     * sealed suite-backed baseline rubric alongside the RoundJudge path.
     *
     * @param manager Active grader-group manager for this run.
     * @param runConfig Current run configuration.
     * @param groupIds Competing group IDs.
     * @param roundIndex Current round index.
     * @param sheetRegisteredRounds Mutable set tracking already-registered rounds.
     */
    private fun registerRoundWithGraderManager(
        manager: GraderGroupManager,
        runConfig: RunConfig,
        groupIds: List<String>,
        roundIndex: Int,
        sheetRegisteredRounds: MutableSet<Int>
    ) {
        if (roundIndex in sheetRegisteredRounds) return
        val sealedSuiteHash = activeSealedTestSuiteHash.ifBlank { canonicalTestSuiteHash(activeSealedTestSuiteJson) }
        val sheet = sealedTestSuiteEvaluator.buildGradingSheet(
            runId = runConfig.runId,
            roundIndex = roundIndex,
            suiteJson = activeSealedTestSuiteJson,
            suiteHash = sealedSuiteHash,
            scoringDimensions = runConfig.scoringDimensions
        ).copy(
            sheetId = UUID.randomUUID().toString(),
            graderGroupId = "grader-default",
            comparativePairs = buildList {
                for (i in groupIds.indices) {
                    for (j in (i + 1) until groupIds.size) {
                        add(ComparativePair(groupIds[i], groupIds[j], preferredGroupId = null))
                    }
                }
            }
        )
        manager.registerSheet(sheet)
        sheetRegisteredRounds += roundIndex
    }

    /**
     * Registers a sealed baseline rubric with the blind scoring engine.
     *
     * The engine needs at least one sealed sheet per round to derive its verdict.
     *
     * @param engine Blind scoring engine for the active run.
     * @param runConfig Current run configuration.
     * @param roundIndex Current round index.
     */
    private fun registerRoundWithBlindScoringEngine(
        engine: ScoringEngine,
        runConfig: RunConfig,
        roundIndex: Int
    ) {
        val sheet = sealedTestSuiteEvaluator.buildGradingSheet(
            runId = runConfig.runId,
            roundIndex = roundIndex,
            suiteJson = activeSealedTestSuiteJson,
            suiteHash = activeSealedTestSuiteHash.ifBlank { canonicalTestSuiteHash(activeSealedTestSuiteJson) },
            scoringDimensions = normaliseScoringDimensions(runConfig.scoringDimensions)
        )
        engine.registerGradingSheet(sheet)
    }

    /**
     * Applies council-defined normalization overrides to the live scoring dimensions.
     *
     * If the council did not provide an override for a dimension, the dimension's own
     * `maxScore` remains the fallback.
     */
    private fun normaliseScoringDimensions(scoringDimensions: List<ScoringDimension>): List<ScoringDimension> {
        return scoringDimensions.map { dimension ->
            val normalizedMaxScore = activeScoreNormalization[dimension.id]
                ?.takeIf { it > 0.0 }
                ?: dimension.maxScore
            if (normalizedMaxScore == dimension.maxScore) dimension
            else dimension.copy(maxScore = normalizedMaxScore)
        }
    }

    /**
     * Derives deterministic resource nodes from the council's node graph seed.
     *
     * The plan does not yet persist a dedicated resource-node seed, so the node graph
     * is reused as the resource-map seed.
     *
     * @param nodeGraph Raw council node graph JSON.
     * @return Resource nodes used by the round-level resource map.
     */
    internal fun deriveResourceNodes(nodeGraph: String): List<ResourceNode> {
        @kotlinx.serialization.Serializable
        data class NodeEntry(val nodeId: String, val description: String)

        val nodes: List<NodeEntry> = try {
            val nodeJson = Json { ignoreUnknownKeys = true }
            if (nodeGraph.trimStart().startsWith("[")) {
                nodeJson.decodeFromString<List<NodeEntry>>(nodeGraph)
            } else emptyList()
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: could not parse nodeGraph as resource nodes: ${e.message}")
            emptyList()
        }

        return nodes.mapIndexed { index, node ->
            val resourceType = ResourceType.entries[index % ResourceType.entries.size]
            val yieldMultiplier = 1.05 + ((index % 5) * 0.05)
            ResourceNode(
                id = node.nodeId,
                name = node.description,
                resourceType = resourceType,
                yieldMultiplier = yieldMultiplier
            )
        }
    }

    /**
     * Publishes resource-node ownership changes derived from the explicit WiP completion stream.
     *
     * Temporary contract: the council node graph is reused as the resource-node seed until
     * the council emits a dedicated resource-map field.
     *
     * @param runId Active run identifier.
     * @param completedWipTasksByGroup WiP task IDs completed during this round.
     * @param round Current round index.
     */
    internal suspend fun publishResourceNodeClaims(
        runId: String,
        completedWipTasksByGroup: Map<String, List<String>>,
        round: Int
    ) {
        val engine = activeResourceMapEngine ?: return
        val claimEvents: List<NodeClaimEvent> = engine.applyWipCompletions(completedWipTasksByGroup)

        claimEvents.forEach { event ->
            emit(EvoKammerEvent.ResourceNodeClaimed(runId, event.nodeId, event.newOwnerId))
            Napier.i("EvoKammerRunService: resource node ${event.nodeId} claimed by ${event.newOwnerId} round=$round")
        }
    }

    /**
     * Builds a deterministic reviewer identity for a critic pipeline instance.
     *
     * A stable identity keeps the self-review guard active in production runs instead of only
     * in tests that inject an explicit critic identity.
     */
    private fun criticAgentIdFor(groupId: String, round: Int): String = "critic-$groupId-round-$round"

    /**
     * Publishes the round's sealed substrate scores through the blind gate.
     *
     * The gate is opened only after all sealed submissions are ready, and the final
     * [GradingResult] is emitted only after the blind reveal completes.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs.
     * @param round Current round index.
     * @return Final blind-scored result for the round.
     */
    /**
     * Phase 7.5: Applies DST structural refinements and rubric policies accepted by the
     * Meta-Judge, across all competing groups.
     *
     * @param runId Active run identifier.
     * @param groupIds Competing group IDs.
     * @param blindResult The blind round result, carrying accepted refinements and policies.
     * @param round Current round index.
     */
    private suspend fun applyDstRefinementsFromRound(
        runId: String,
        groupIds: List<String>,
        blindResult: com.achulkov.evokammer.core.model.GradingResult,
        round: Int
    ) {
        val refinements = blindResult.acceptedRefinements
        val policies = blindResult.newRubricPolicies

        if (refinements.isEmpty() && policies.isEmpty()) return

        for (groupId in groupIds) {
            try {
                // Apply rubric policies first (may affect complexity levels)
                if (policies.isNotEmpty()) {
                    deliverableSkeletonTreeStore.applyPolicies(runId, groupId, policies)
                }

                // Apply structural refinements
                if (refinements.isNotEmpty()) {
                    val refiner = DeliverableSkeletonTreeRefiner(
                        store = deliverableSkeletonTreeStore,
                        runId = runId,
                        groupId = groupId
                    )
                    val result = refiner.applyRefinements(refinements, round)
                    if (result.structuralChangesCount > 0) {
                        Napier.i("EvoKammerRunService: applied ${result.structuralChangesCount} DST refinements for group=$groupId round=$round")
                    }
                }
            } catch (e: Exception) {
                Napier.w(e) { "EvoKammerRunService: failed to apply DST refinements for group=$groupId round=$round" }
            }
        }
    }

    private suspend fun publishBlindRoundResult(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): com.achulkov.evokammer.core.model.GradingResult {
        val tournament = activeBlindTournamentService
            ?: error("BlindTournamentService is not initialised for run ${config.runId}")

        return tournament.publishRoundResult(
            config = config,
            groupIds = groupIds,
            round = round,
            registerRound = ::registerRoundWithBlindScoringEngine,
            buildGroupRoundResult = { groupId ->
                val substrateAssessment = latestGroupSubstrateAssessments[groupId]
                val sealedAssessment = latestGroupRoundAssessments[groupId]
                GroupRoundResult(
                    groupId = groupId,
                    roundIndex = round,
                    dimensionScores = substrateAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
                        ?: sealedAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
                        ?: latestGroupDimensionScores[groupId].orEmpty(),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = sealedAssessment?.automatedTestsPassed ?: 0,
                    automatedTestsFailed = sealedAssessment?.automatedTestsFailed ?: 0
                )
            }
        ).also { result ->
            emit(EvoKammerEvent.RoundCompleted(config.runId, result))
            groupSummaries = groupSummaries.map { summary ->
                val score = result.scores.firstOrNull { it.groupId == summary.groupId }
                if (score != null) summary.copy(currentRoundScore = score.weightedTotal) else summary
            }
        }
    }
}

/** Summary of a legacy-protocol expiry sweep. */
internal data class LegacyExpirySweepResult(
    val expiredProtocols: Int,
    val deadEndsInserted: Int
)
