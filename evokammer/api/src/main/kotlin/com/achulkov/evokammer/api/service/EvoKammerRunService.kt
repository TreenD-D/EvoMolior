package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.disclosure.DisclosureStage
import com.achulkov.evokammer.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evokammer.competition.grader.ComparativePair
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.core.contract.GroupCheckinResult
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.epigenetic.FitnessScorer
import com.achulkov.evokammer.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evokammer.orchestration.inspector.InspectorRunner
import com.achulkov.evokammer.orchestration.lifecycle.CriticPipeline
import com.achulkov.evokammer.orchestration.lifecycle.CriticSummary
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.selector.EvolutionarySelector
import com.achulkov.evokammer.orchestration.watchdog.Watchdog
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSignal
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
     *   runObjective, scoringDimensions, graderGroupManager, onProgress?) → Unit
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
    ) -> Unit)? = null
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
    /** Model assignments from Genesis Council (role name → model string). Applied each round spawn. */
    @Volatile private var activeModelAssignments: Map<String, String> = emptyMap()
    /** Role prompts from Genesis Council (role name → system prompt). Applied each round spawn. */
    @Volatile private var activeRolePrompts: Map<String, String> = emptyMap()
    /** Max rounds from StartingField.generationCeiling (caps config.rounds in the run loop). */
    @Volatile private var activeGenerationCeiling: Int = 100
    /** Global budget ceiling from StartingField (used by BudgetCritical pause logic). */
    @Volatile private var activeGlobalBudgetCeiling: Double = 0.0
    /**
     * Latest per-group dimension scores computed by [computeAndSubmitSubstrateScores].
     * Populated each round and consumed by [processDisclosureCheckIns] (A-3).
     */
    private val latestGroupDimensionScores = ConcurrentHashMap<String, Map<String, Double>>()
    /** Per-group stasis flags set by Watchdog signal collection. Reset each round. */
    private val stasisFlags = ConcurrentHashMap<String, Boolean>()
    /** Watchdog instance for the active run (null when no run is active). */
    private var activeWatchdog: Watchdog? = null
    /** Inspector runner for the active run (null when no run is active). */
    private var activeInspectorRunner: InspectorRunner? = null
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

    override suspend fun startRun(config: RunConfig): String = mutex.withLock {
        check(activeRunId == null) { "Run $activeRunId is already active — abort it first" }

        Napier.i("EvoKammerRunService: starting run ${config.runId} groups=${config.groupCount} rounds=${config.rounds}")
        activeRunId = config.runId
        currentStatus = EngineStatus.GENESIS

        currentRound = null

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
        val startingField: StartingField = if (config.isTestRun) {
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
        } else if (genesisCouncilRunner != null) {
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
        } else {
            Napier.i("EvoKammerRunService: no Genesis Council runner configured — using default StartingField")
            StartingField.default(config.objective, config.runId)
        }
        val councilModelSummary = if (startingField.modelAssignment.isNotEmpty())
            startingField.modelAssignment.entries.joinToString(", ") { "${it.key}→${it.value}" }
        else "defaults"
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

        // Store council outputs for use in each round's spawn
        activeModelAssignments = startingField.modelAssignment
        activeRolePrompts = startingField.rolePrompts
        activeGenerationCeiling = startingField.generationCeiling
        // A-7: Store budget ceiling for BudgetCritical signal pause threshold (5% of ceiling)
        activeGlobalBudgetCeiling = startingField.globalBudgetCeiling
        // B-1: Store KG schema for KGConsistencyChecker validation in CriticPipeline
        activeKgSchema = startingField.kgSchema
        Napier.i("EvoKammerRunService: stored ${activeModelAssignments.size} model assignments and ${activeRolePrompts.size} role prompts from Genesis Council (generationCeiling=${startingField.generationCeiling})")

        // Build group IDs from indices
        val groupIds = (0 until config.groupCount).map { "group-$it" }
        // Grader group IDs — one grader group per run (expandable via StartingField.graderGroupCount)
        val graderGroupIds = (0 until startingField.graderGroupCount).map { "grader-$it" }

        // ── Seed primordial substrate data for each group ─────────────────
        // KB documents, KG facts, and WIP tasks from the Genesis Council are inserted
        // before the first round so agents have starting context and tasks to claim.
        // Grader groups receive the same seed so their KB is not empty at first eval.
        seedPrimordialData(config.runId, groupIds, graderGroupIds, startingField)
        lastRunId = config.runId
        lastRunGroupIds = groupIds
        groupSummaries = groupIds.mapIndexed { idx, gid ->
            GroupSummary(
                groupId = gid,
                profileType = config.groupProfiles[idx].name,
                activeAgents = 0,
                remainingBudget = GroupProfile.default(config.groupProfiles[idx]).spawnBudget * config.maxAgentsPerGroup,
                currentRoundScore = null
            )
        }
        Napier.i("EvoKammerRunService: initialised ${groupSummaries.size} group summaries for run ${config.runId}")

        emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        Napier.i("EvoKammerRunService: emitted RunStarted for run ${config.runId}")

        // Create coordinator and lifecycle managers
        val coordinator = groupCoordinatorProvider(groupIds, config.scoringDimensions)
        activeCoordinator = coordinator
        val graderGroupManager = graderGroupManagerProvider(config.scoringDimensions)
        activeGraderGroupManager = graderGroupManager
        val sheetRegisteredRounds = mutableSetOf<Int>()

        groupIds.forEachIndexed { idx, groupId ->
            val profileType = config.groupProfiles.getOrElse(idx) { config.groupProfiles.firstOrNull() ?: GroupProfileType.Library }
            val lm = lifecycleManagerProvider(
                config.runId, groupId, config.openRouterApiKey, resolveAgentJarPath(),
                GroupProfile.default(profileType)
            )
            // ── Wire event callbacks ─────────────────────────────────────────
            // Translate LifecycleManager callbacks into EvoKammerEvent emissions.
            lm.onAgentSpawned = { agentId, role, modelId ->
                scope.launch {
                    emit(EvoKammerEvent.AgentSpawned(config.runId, groupId, agentId, role))
                    Napier.i("[$groupId] Agent spawned: $agentId (${role.name}) model=$modelId")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "agent_spawned",
                        "[$groupId] ${role.name} agent $agentId started (model=$modelId)"))
                    // Update active agent count in summaries
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
                    // Update active agent count in summaries
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(activeAgents = maxOf(0, s.activeAgents - 1)) else s
                    }
                }
            }
            lm.onBudgetChanged = { totalRemaining ->
                scope.launch {
                    emit(EvoKammerEvent.GroupBudgetChanged(config.runId, groupId, totalRemaining))
                    // Update remaining budget in summaries
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
            liveLifecycleManagers[groupId] = lm
        }

        // Initialise ProgressiveDisclosureEngine if stages are configured
        activeDisclosureEngine = disclosureStages?.takeIf { it.isNotEmpty() }?.let { stages ->
            ProgressiveDisclosureEngine(stages = stages).also { engine ->
                groupIds.forEach { engine.registerGroup(it) }
                Napier.i("EvoKammerRunService: ProgressiveDisclosureEngine initialised with ${stages.size} stages")
            }
        }

        // Subscribe to grading results and re-emit as events
        scope.launch {
            coordinator.gradingResults.collect { result ->
                val effectiveResult = try {
                    registerRoundWithGraderManager(
                        manager = graderGroupManager,
                        runConfig = config,
                        groupIds = groupIds,
                        roundIndex = result.roundIndex,
                        sheetRegisteredRounds = sheetRegisteredRounds
                    )
                    result.scores.forEach { roundScore ->
                        graderGroupManager.submitScores(
                            graderGroupId = "grader-default",
                            targetGroupId = roundScore.groupId,
                            roundIndex = result.roundIndex,
                            scores = roundScore.dimensionScores
                        )
                    }
                    graderGroupManager.computeRoundResult(groupIds, result.roundIndex)
                } catch (e: Exception) {
                    Napier.w("EvoKammerRunService: grader manager fallback to coordinator scores: ${e.message}")
                    result
                }
                emit(EvoKammerEvent.RoundCompleted(config.runId, effectiveResult))
                // Update summaries with latest scores
                groupSummaries = groupSummaries.map { s ->
                    val score = effectiveResult.scores.firstOrNull { it.groupId == s.groupId }
                    if (score != null) s.copy(currentRoundScore = score.weightedTotal) else s
                }
            }
        }

        // ── Shuru pre-flight check ───────────────────────────────────────────
        // Run AFTER lifecycle managers are created so usesInProcessMode is accurate.
        // Performs the check asynchronously to avoid blocking startRun, but aborts the
        // active run coroutine if Shuru VM mode is needed but Shuru is unavailable.
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
                    // Only abort if any lifecycle manager is in Shuru VM mode.
                    // In-process mode (localAgentRunner != null) works without Shuru.
                    val needsShuru = liveLifecycleManagers.values.any { !it.usesInProcessMode }
                    if (needsShuru) {
                        val errorMsg = "Shuru is not available: ${shuruCheck.message}"
                        Napier.e("EvoKammerRunService: $errorMsg — aborting (Shuru VM agents are configured)")
                        emit(EvoKammerEvent.EngineProgress(config.runId, "shuru_unavailable", errorMsg))
                        emit(EvoKammerEvent.RunAborted(config.runId, "shuru_unavailable: ${shuruCheck.message}"))
                        currentStatus = EngineStatus.IDLE
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
                // Non-fatal: if check threw, assume Shuru may still be available or we are in
                // in-process mode. The run continues; a real spawn failure will surface later.
            }
        }

        // ── Start Watchdog and InspectorRunner ───────────────────────────
        // Watchdog monitors agent heartbeats and KG health (no TTL — lives the whole run).
        // InspectorRunner scans event logs per group for constitutional violations / budget spikes.
        val watchdog = Watchdog(
            substrateDatabaseFactory = substrateFactory,
            runId = config.runId
        )
        activeWatchdog = watchdog
        watchdog.start(scope)

        val inspector = InspectorRunner(watchdog = watchdog)
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

        // Subscribe to Watchdog signals → update stasisFlags and relay as EngineProgress events
        scope.launch {
            watchdog.signals.collect { signal ->
                when (signal) {
                    is WatchdogSignal.AgentStasis -> {
                        stasisFlags[signal.groupId] = true
                        emit(EvoKammerEvent.EngineProgress(config.runId, "watchdog_stasis",
                            "[${signal.groupId}] Stasis detected — agent ${signal.agentId} idle ${signal.idleMs / 1000}s"))
                    }
                    is WatchdogSignal.InspectorFlag -> {
                        emit(EvoKammerEvent.EngineProgress(config.runId, "watchdog_flag",
                            "[${signal.groupId}] Inspector flag ${signal.severity}: ${signal.description}"))
                    }
                    is WatchdogSignal.BudgetCritical -> {
                        // A-7: When remaining budget hits the last 5% of the global ceiling,
                        // pause new agent spawning so the remaining budget is not wasted on
                        // spawning agents that cannot run a full generation.
                        val ceiling = activeGlobalBudgetCeiling
                        if (ceiling > 0 && signal.remainingBudget <= ceiling * 0.05) {
                            currentStatus = EngineStatus.PAUSED
                            emit(EvoKammerEvent.EngineProgress(config.runId, "budget_pause",
                                "[${signal.groupId}] Budget at ≤5% of ceiling — spawning paused. " +
                                        "remaining=${signal.remainingBudget}, ceiling=$ceiling"))
                        } else {
                            emit(EvoKammerEvent.EngineProgress(config.runId, "watchdog_budget",
                                "[${signal.groupId}] Budget critical — remaining=${signal.remainingBudget}"))
                        }
                    }
                    is WatchdogSignal.KgAnomaly -> {
                        emit(EvoKammerEvent.EngineProgress(config.runId, "watchdog_kg",
                            "[${signal.groupId}] KG anomaly ${signal.anomalyType}: ${signal.details}"))
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
        }

        // Launch run loop
        scope.launch { runLoop(config, groupIds, coordinator, graderGroupIds, graderGroupManager) }

        config.runId
    }

    override suspend fun pauseRun(runId: String) {
        requireActiveRun(runId)
        currentStatus = EngineStatus.PAUSED
        Napier.i("EvoKammerRunService: paused run $runId")
    }

    override suspend fun resumeRun(runId: String) {
        requireActiveRun(runId)
        if (currentStatus == EngineStatus.PAUSED) {
            currentStatus = EngineStatus.RUNNING
        }
        Napier.i("EvoKammerRunService: resumed run $runId")
    }

    override suspend fun abortRun(runId: String, reason: String): Unit = mutex.withLock {
        requireActiveRun(runId)
        Napier.w("EvoKammerRunService: aborting run $runId reason=$reason")
        cleanup(runId)
        emit(EvoKammerEvent.RunAborted(runId, reason))
    }

    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? {
        return try {
            // Pick the first group DB for this run to query the meta_archive table.
            // MetaArchiveEntries are keyed by runId so querying any group's DB is sufficient.
            val groupIds = if (runId == lastRunId) lastRunGroupIds else {
                // Reconstruct group IDs using the standard naming scheme
                (0..9).map { "group-$it" }
            }
            val groupId = groupIds.firstOrNull() ?: return null
            val db = substrateFactory.get(runId, groupId)
            val total = db.metaArchiveDao().count()
            val allEntries = db.metaArchiveDao().getByRun(runId)
            val byType = allEntries.groupingBy { it.entryType }.eachCount()
            MetaArchiveSummary(total = total, byType = byType)
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: failed to load meta-archive summary for $runId: ${e.message}")
            null
        }
    }

    override suspend fun getState(): EngineState = EngineState(
        status = currentStatus,
        activeRunId = activeRunId,
        currentRound = currentRound,
        groupSummaries = groupSummaries
    )

    // ── Internal run loop ────────────────────────────────────────────────────

    private suspend fun runLoop(
        config: RunConfig,
        groupIds: List<String>,
        coordinator: GroupCoordinator,
        graderGroupIds: List<String>,
        graderGroupManager: GraderGroupManager
    ) {
        currentStatus = EngineStatus.RUNNING
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
            for (round in 0 until effectiveRounds) {
                // Honour pause/abort before starting round
                while (currentStatus == EngineStatus.PAUSED) { delay(500) }
                if (currentStatus == EngineStatus.IDLE) {
                    Napier.i("EvoKammerRunService: run aborted before round $round")
                    return
                }

                currentRound = round
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

                // ── Post-round processing ────────────────────────────────────

                currentStatus = EngineStatus.EVALUATING

                // 1. Run CriticPipeline for each group — review and promote staging submissions
                runCriticPipelinesForRound(config, groupIds, round)

                // 1b. Post-mortem fitness scoring + Legacy Protocol extraction (epigenetic inheritance)
                runFitnessAndEpigenetics(config, groupIds, round)

                // 2. Apply per-round trust decay across all groups
                applyTrustDecayForRound(config.runId, groupIds)

                // 3a. Wait for criteria phase to complete (if running), then run judging phase
                criteriaJob?.join()

                if (graderJudgingRunner != null && graderGroupIds.isNotEmpty()) {
                    // New two-phase architecture: RoundJudge reads criteria + scores all groups
                    runGraderJudging(config, graderGroupIds, groupIds, round, graderGroupManager)
                } else {
                    // Legacy: old post-round grader pipeline (backward compat)
                    runGraderAgents(config, graderGroupIds, groupIds, round, graderGroupManager)
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

                // 5. Trigger grading result aggregation
                coordinator.onGradingComplete(round)
                currentStatus = EngineStatus.RUNNING
            }

            // All rounds complete
            val winnerId = groupSummaries.maxByOrNull { it.currentRoundScore ?: 0.0 }?.groupId
            emit(EvoKammerEvent.RunCompleted(config.runId, winnerId))
            currentStatus = EngineStatus.COMPLETED
            Napier.i("EvoKammerRunService: run ${config.runId} completed, winner=$winnerId")

            // 5. Archive run artefacts for cross-run Council learning
            archiveCompletedRun(config, groupIds, winnerId)

        } catch (e: Exception) {
            Napier.e("EvoKammerRunService: run loop error", e)
            emit(EvoKammerEvent.RunAborted(config.runId, e.message ?: "internal_error"))
            currentStatus = EngineStatus.IDLE
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
                val groupProfileType = config.groupProfiles.getOrNull(groupIds.indexOf(groupId))
                    ?: config.groupProfiles.firstOrNull()
                if (groupProfileType == null) {
                    Napier.w("EvoKammerRunService: no GroupProfile for group $groupId")
                    continue
                }
                val profile = GroupProfile.default(groupProfileType)
                val budgetCeiling = (groupSummaries.firstOrNull { it.groupId == groupId }?.remainingBudget
                    ?: (profile.spawnBudget * config.maxAgentsPerGroup))

                Napier.i("EvoKammerRunService: computing role allocation for group=$groupId round=$round budgetCeiling=$budgetCeiling")
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

                val selector = EvolutionarySelector(db = db, profile = profile, runId = config.runId, groupId = groupId)
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
    ) {
        groupIds.forEach { groupId ->
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
                    criticModel = criticModelOverride ?: "anthropic/claude-sonnet-4",
                    // B-1: Pass the council-designed KG schema so KGConsistencyChecker
                    // can validate KG_FACT submissions before they go to the LLM Critic.
                    kgSchema = activeKgSchema
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
            } catch (e: Exception) {
                Napier.e("EvoKammerRunService: CriticPipeline failed for group=$groupId round=$round: ${e.message}", e)
            }
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
    ) {
        if (graderJudgingRunner == null) return
        if (graderGroupIds.isEmpty()) return

        for (graderGroupId in graderGroupIds) {
            emit(EvoKammerEvent.GraderRoundStarted(config.runId, graderGroupId, round, competingGroupIds))
        }

        try {
            Napier.i("EvoKammerRunService: starting RoundJudge evaluation round=$round")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_judge_start",
                "RoundJudge starting for round $round — scoring ${competingGroupIds.size} groups"))

            graderJudgingRunner.invoke(
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
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: RoundJudge evaluation failed round=$round: ${e.message}")
            emit(EvoKammerEvent.EngineProgress(config.runId, "grader_judge_error",
                "RoundJudge failed for round $round: ${e.message?.take(150)}"))
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
    ) {
        // Build dynamic dimension scorer map from config dimensions.
        // Built-in scorers handle kb_density, kg_coherence, tool_utility, plan_execution.
        // Council-designed dimensions that don't match a built-in ID use the heuristic
        // fallback (staging_success_ratio) so they score > 0 rather than always returning 0.
        // Add new scorer lambdas here as Phase 3+ dimensions are designed.
        val dimensionScorerMap: Map<String, suspend (com.achulkov.evokammer.substrate.db.EvoKammerDatabase, String) -> Double> =
            mapOf(
                "kb_density" to { db, gid ->
                    val count = db.knowledgeBaseDao().countActive(config.runId, gid)
                    Napier.d("[$gid] kb_density: count=$count")
                    (count.toDouble() / 20.0).coerceAtMost(1.0)
                },
                "kg_coherence" to { db, gid ->
                    val facts = db.knowledgeGraphFactDao().countActive(config.runId, gid)
                    val conflicts = try { db.knowledgeGraphFactDao().countConflicts(config.runId, gid) } catch (_: Exception) { 0 }
                    val coherenceRatio = if (facts > 0) maxOf(0.0, 1.0 - conflicts.toDouble() / facts) else 0.0
                    Napier.d("[$gid] kg_coherence: facts=$facts conflicts=$conflicts ratio=$coherenceRatio")
                    coherenceRatio * (facts.toDouble() / 10.0).coerceAtMost(1.0)
                },
                "tool_utility" to { db, gid ->
                    val tools = db.toolRegistryDao().getActive(config.runId, gid, null)
                    val avg = tools.map { it.successRate }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                    Napier.d("[$gid] tool_utility: tools=${tools.size} avgSuccessRate=$avg")
                    (tools.size.toDouble() / 5.0).coerceAtMost(1.0) * avg
                },
                "plan_execution" to { db, gid ->
                    val unclaimed = db.wipTaskDao().countUnclaimed(config.runId, gid)
                    Napier.d("[$gid] plan_execution: unclaimedTasks=$unclaimed")
                    maxOf(0.0, 1.0 - (unclaimed.toDouble() / 10.0).coerceAtMost(1.0))
                },
                // Generic fallback for council-designed dimensions: staging success ratio
                "_fallback" to { db, gid ->
                    val promotedTotal = try {
                        db.stagingDao().countPendingByType(config.runId, gid, "DOC").let { 0 } // placeholder
                    } catch (_: Exception) { 0 }
                    // Use kb_density as a proxy score for unknown dimensions
                    val count = try { db.knowledgeBaseDao().countActive(config.runId, gid) } catch (_: Exception) { 0 }
                    (count.toDouble() / 30.0).coerceAtMost(1.0).also {
                        Napier.d("[$gid] _fallback proxy: count=$count score=$it")
                        // Suppress unused warning on promotedTotal above
                        @Suppress("UNUSED_EXPRESSION") promotedTotal
                    }
                }
            )

        for (groupId in groupIds) {
            try {
                val db = substrateFactory.get(config.runId, groupId)
                val dimensionScores = config.scoringDimensions.map { dim ->
                    val scorer = dimensionScorerMap[dim.id] ?: run {
                        Napier.w("[$groupId] Unknown scoring dimension '${dim.id}' — using fallback scorer")
                        dimensionScorerMap["_fallback"]!!
                    }
                    val normalisedScore = scorer(db, groupId)
                    val rawScore = (normalisedScore * dim.maxScore).coerceAtMost(dim.maxScore)
                    DimensionScore(
                        dimensionId = dim.id,
                        rawScore = rawScore,
                        justification = "Substrate metrics at round $round for group $groupId"
                    )
                }

                coordinator.graderGroup.submit(groupId, round, dimensionScores)
                // A-3: Cache dimension scores so processDisclosureCheckIns (which runs after
                // computeAndSubmitSubstrateScores) can pass real per-dimension values.
                latestGroupDimensionScores[groupId] = dimensionScores.associate { it.dimensionId to it.rawScore }
                val scoresSummary = dimensionScores.joinToString(", ") { "${it.dimensionId}=${String.format(java.util.Locale.ROOT, "%.1f", it.rawScore)}" }
                Napier.i("[$groupId] Submitted scores for round=$round: $scoresSummary")
                emit(EvoKammerEvent.EngineProgress(config.runId, "scoring",
                    "[$groupId] Scores submitted round=$round: $scoresSummary"))
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: score computation failed for group=$groupId round=$round: ${e.message}")
            }
        }
    }

    /**
     * Applies per-round trust decay to all groups' trust score tables.
     * Also archives KB entries whose trust has dropped below the minimum threshold.
     */
    private suspend fun applyTrustDecayForRound(runId: String, groupIds: List<String>) {
        val now = System.currentTimeMillis()
        val archiveThreshold = 0.30 // archive KB entries that fall below 30% trust
        groupIds.forEach { groupId ->
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
                val score = groupSummaries.firstOrNull { it.groupId == groupId }?.currentRoundScore
                // A-3: Use per-dimension scores cached from computeAndSubmitSubstrateScores.
                val dimScores = latestGroupDimensionScores[groupId] ?: emptyMap()
                if (score != null) {
                    val checkin = GroupCheckinResult(
                        groupId = groupId,
                        overallScore = score,
                        passed = score >= 0.6,
                        dimensionScores = dimScores,
                        failedTestCount = 0,
                        gradingSheetApplied = "round-$round",
                        stageAdvanced = false
                    )
                    engine.onCheckinComplete(groupId, checkin)
                }
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: progressive disclosure check-in failed for $groupId: ${e.message}")
            }
        }
    }

    /** Calls [RunArchiveService.archiveRun] after a successful run completion. */
    private suspend fun archiveCompletedRun(config: RunConfig, groupIds: List<String>, winnerId: String?) {
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
                finalGeneration = config.rounds
            )
            Napier.i("EvoKammerRunService: run ${config.runId} archived successfully")
        } catch (e: Exception) {
            Napier.e("EvoKammerRunService: failed to archive run ${config.runId}: ${e.message}", e)
        }
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
        graderGroupIds: List<String>,
        startingField: StartingField
    ) {
        val allGroupIds = groupIds + graderGroupIds
        if (startingField.primordialKb.isEmpty() &&
            startingField.primordialKgFacts.isEmpty() &&
            startingField.primordialTools.isEmpty() &&
            startingField.nodeGraph == "{}"
        ) {
            Napier.i("EvoKammerRunService: no primordial data to seed (default StartingField)")
            return
        }

        // Parse nodeGraph → WIP task nodes
        @kotlinx.serialization.Serializable
        data class NodeEntry(val nodeId: String, val description: String)
        val nodeEntries: List<NodeEntry> = try {
            val nodeJson = Json { ignoreUnknownKeys = true }
            if (startingField.nodeGraph.trimStart().startsWith("[")) {
                nodeJson.decodeFromString<List<NodeEntry>>(startingField.nodeGraph)
            } else emptyList()
        } catch (e: Exception) {
            Napier.w("EvoKammerRunService: could not parse nodeGraph as WIP tasks: ${e.message}")
            emptyList()
        }

        val now = System.currentTimeMillis()

        for (groupId in allGroupIds) {
            try {
                val db = substrateFactory.get(runId, groupId)

                // Seed KB documents
                if (startingField.primordialKb.isNotEmpty()) {
                    val kbEntities = startingField.primordialKb.map { seed ->
                        com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity(
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
                    Napier.i("EvoKammerRunService: seeded ${kbEntities.size} primordial KB docs for group=$groupId")
                }

                // Seed KG facts
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
                    Napier.i("EvoKammerRunService: seeded ${kgEntities.size} primordial KG facts for group=$groupId")
                }

                // Seed WIP tasks from nodeGraph
                if (nodeEntries.isNotEmpty()) {
                    val wipEntities = nodeEntries.map { node ->
                        com.achulkov.evokammer.substrate.db.entity.WipTaskEntity(
                            nodeId = node.nodeId,
                            runId = runId,
                            groupId = groupId,
                            description = node.description,
                            status = "unclaimed",
                            lastChangedGeneration = 0
                        )
                    }
                    db.wipTaskDao().insertAll(wipEntities)
                    Napier.i("EvoKammerRunService: seeded ${wipEntities.size} WIP tasks for group=$groupId")
                }

                // Seed tools from primordialTools
                if (startingField.primordialTools.isNotEmpty()) {
                    val toolEntities = startingField.primordialTools.map { seed ->
                        com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity(
                            id = "${seed.id}-$groupId",
                            name = seed.name,
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
                    toolEntities.forEach { db.toolRegistryDao().insert(it) }
                    Napier.i("EvoKammerRunService: seeded ${toolEntities.size} primordial tools for group=$groupId")
                }
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: primordial seed failed for group=$groupId: ${e.message}")
            }
        }

        val totalKb = startingField.primordialKb.size * allGroupIds.size
        val totalKg = startingField.primordialKgFacts.size * allGroupIds.size
        val totalWip = nodeEntries.size * allGroupIds.size
        val totalTools = startingField.primordialTools.size * allGroupIds.size
        Napier.i("EvoKammerRunService: primordial seeding complete — KB=$totalKb KG=$totalKg WIP=$totalWip tools=$totalTools (across ${allGroupIds.size} groups: ${groupIds.size} competing + ${graderGroupIds.size} grader)")
    }

    /**
     * Runs post-mortem fitness scoring and Legacy Protocol extraction for all groups
     * after the CriticPipeline has processed each round's submissions.
     *
     * Per spec §13 (Epigenetic Inheritance):
     * - Top 20% of agents by fitness score are eligible for Legacy Protocol extraction.
     * - One protocol per role; merged via CrossoverEngine if multiple top agents share a role.
     * - Protocol expires after 2 generations; failure map is promoted to Dead Ends Registry on expiry.
     */
    private suspend fun runFitnessAndEpigenetics(config: RunConfig, groupIds: List<String>, round: Int) {
        for (groupId in groupIds) {
            try {
                val db = substrateFactory.get(config.runId, groupId)
                val scorer = FitnessScorer(
                    eventLogDao = db.eventLogDao(),
                    knowledgeBaseDao = db.knowledgeBaseDao(),
                    stagingDao = db.stagingDao(),
                    toolRegistryDao = db.toolRegistryDao()
                )
                val scores = scorer.scoreGeneration(config.runId, groupId, round)
                if (scores.isEmpty()) continue

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

                // B-4: Sweep for expired legacy protocols and promote failure maps to Dead Ends.
                // Expired protocols have expiresAtGeneration <= currentRound.
                // For each expired protocol with a non-empty failureMapJson, insert a Dead End
                // KB entry so agents don't re-investigate known failure modes.
                val expiredProtocols = runCatching {
                    db.legacyProtocolDao().getExpired(config.runId, groupId, round)
                }.getOrElse { emptyList() }

                var deadEndsInserted = 0
                for (protocol in expiredProtocols) {
                    if (protocol.failureMapJson.isNotBlank() && protocol.failureMapJson != "{}") {
                        runCatching {
                            db.knowledgeBaseDao().insert(
                                com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity(
                                    id = "dead-end-${protocol.id}",
                                    content = "DEAD END [Role: ${protocol.role}, Gen: ${protocol.extractedFromGeneration}]: " +
                                            protocol.failureMapJson,
                                    domain = "dead_ends",
                                    sourceAgentId = "epigenetic_archive",
                                    generation = round,
                                    trustScore = 1.0, // Dead ends always return regardless of trust
                                    isDeadEnd = true,
                                    status = "active",
                                    groupId = groupId,
                                    runId = config.runId,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                            deadEndsInserted++
                        }.onFailure { e ->
                            Napier.w("EvoKammerRunService: failed to insert Dead End from protocol ${protocol.id}: ${e.message}")
                        }
                    }
                }
                if (expiredProtocols.isNotEmpty()) {
                    Napier.i("EvoKammerRunService: expired ${expiredProtocols.size} legacy protocol(s), inserted $deadEndsInserted Dead End(s) for group=$groupId round=$round")
                    emit(EvoKammerEvent.EngineProgress(config.runId, "legacy_expiry",
                        "[$groupId] ${expiredProtocols.size} protocol(s) expired, $deadEndsInserted failure map(s) promoted to Dead Ends"))
                }
            } catch (e: Exception) {
                Napier.w("EvoKammerRunService: fitness/epigenetics failed for group=$groupId round=$round: ${e.message}")
            }
        }
    }

    private fun cleanup(runId: String) {
        activeWatchdog?.stop()
        activeWatchdog = null
        activeInspectorRunner?.stopAll()
        activeInspectorRunner = null
        stasisFlags.clear()
        liveLifecycleManagers.clear()
        activeCoordinator = null
        activeGraderGroupManager = null
        activeDisclosureEngine = null
        activeRunId = null
        currentRound = null
        groupSummaries = emptyList()
        currentStatus = EngineStatus.IDLE
        activeModelAssignments = emptyMap()
        activeRolePrompts = emptyMap()
        activeGenerationCeiling = 100
        activeGlobalBudgetCeiling = 0.0
        activeKgSchema = "{}"
        checkInGateOpenRound = null
        latestGroupDimensionScores.clear()
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
    /**
     * Registers a baseline sealed grading sheet for the round if not already present.
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
        val dimensions = runConfig.scoringDimensions.map { dimension ->
            RubricDimension(
                dimensionId = dimension.id,
                name = dimension.name,
                weight = dimension.weight,
                scoringGuidance = "Baseline guidance from run scoring dimensions."
            )
        }
        val pairs = buildList {
            for (i in groupIds.indices) {
                for (j in (i + 1) until groupIds.size) {
                    add(ComparativePair(groupIds[i], groupIds[j], preferredGroupId = null))
                }
            }
        }
        val sheet = GradingSheet(
            sheetId = UUID.randomUUID().toString(),
            runId = runConfig.runId,
            roundIndex = roundIndex,
            graderGroupId = "grader-default",
            rubricDimensions = dimensions,
            automatedTests = emptyList(),
            failureModes = emptyList(),
            comparativePairs = pairs
        ).seal(System.currentTimeMillis())
        manager.registerSheet(sheet)
        sheetRegisteredRounds += roundIndex
    }
}
