package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.ComparativePair
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Real in-process implementation of [EvoKammerEngineService].
 *
 * Manages the full run lifecycle:
 * 1. Group setup and LifecycleManager creation per group
 * 2. GroupCoordinator for inter-group mechanics (leak oracle, dispatch relay, scoring)
 * 3. Round loop: start → agent work → evaluation → score emission
 * 4. Event emission to subscribed UI clients
 *
 * @param lifecycleManagerProvider Factory lambda for creating [LifecycleManager] instances.
 * @param groupCoordinatorProvider Factory lambda for creating [GroupCoordinator] instances.
 * @param graderGroupManagerProvider Factory lambda for creating [GraderGroupManager] instances.
 * @param substrateFactory [SubstrateDatabaseFactory] for per-group DB access.
 */
class EvoKammerRunService(
    private val lifecycleManagerProvider: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String
    ) -> LifecycleManager,
    private val groupCoordinatorProvider: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>
    ) -> GroupCoordinator,
    private val graderGroupManagerProvider: (
        dimensions: List<ScoringDimension>
    ) -> GraderGroupManager,
    private val substrateFactory: SubstrateDatabaseFactory
) : EvoKammerEngineService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 0, extraBufferCapacity = 512)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    @Volatile private var activeRunId: String? = null
    @Volatile private var currentStatus: EngineStatus = EngineStatus.IDLE
    @Volatile private var currentRound: Int? = null
    @Volatile private var groupSummaries: List<GroupSummary> = emptyList()

    private val liveLifecycleManagers = mutableMapOf<String, LifecycleManager>()
    private var activeCoordinator: GroupCoordinator? = null
    private var activeGraderGroupManager: GraderGroupManager? = null

    override suspend fun startRun(config: RunConfig): String = mutex.withLock {
        check(activeRunId == null) { "Run ${activeRunId} is already active — abort it first" }

        Napier.i("EvoKammerRunService: starting run ${config.runId}")
        activeRunId = config.runId
        currentStatus = EngineStatus.GENESIS
        currentRound = null

        // Build group IDs from indices
        val groupIds = (0 until config.groupCount).map { "group-$it" }
        groupSummaries = groupIds.mapIndexed { idx, gid ->
            GroupSummary(
                groupId = gid,
                profileType = config.groupProfiles[idx].name,
                activeAgents = 0,
                remainingBudget = GroupProfile.default(config.groupProfiles[idx]).spawnBudget * config.maxAgentsPerGroup,
                currentRoundScore = null
            )
        }

        emit(EvoKammerEvent.RunStarted(config.runId, config.objective))

        // Create coordinator and lifecycle managers
        val coordinator = groupCoordinatorProvider(groupIds, config.scoringDimensions)
        activeCoordinator = coordinator
        val graderGroupManager = graderGroupManagerProvider(config.scoringDimensions)
        activeGraderGroupManager = graderGroupManager
        val sheetRegisteredRounds = mutableSetOf<Int>()

        groupIds.forEach { groupId ->
            liveLifecycleManagers[groupId] = lifecycleManagerProvider(
                config.runId, groupId, config.openRouterApiKey, resolveAgentJarPath()
            )
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

        // Launch run loop
        scope.launch { runLoop(config, groupIds, coordinator) }

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
        coordinator: GroupCoordinator
    ) {
        currentStatus = EngineStatus.RUNNING
        Napier.i("EvoKammerRunService: run loop started rounds=${config.rounds}")

        try {
            for (round in 0 until config.rounds) {
                // Honour pause/abort
                while (currentStatus == EngineStatus.PAUSED) { delay(500) }
                if (currentStatus == EngineStatus.IDLE) {
                    Napier.i("EvoKammerRunService: run aborted at round $round")
                    return
                }

                currentRound = round
                coordinator.onRoundStart(round)
                emit(EvoKammerEvent.RoundStarted(config.runId, round))
                Napier.i("EvoKammerRunService: round $round started (duration=${config.roundDurationMinutes}m)")

                // Wait for round duration (agents are working concurrently)
                delay(config.roundDurationMinutes * 60_000L)

                // Trigger grading
                currentStatus = EngineStatus.EVALUATING
                coordinator.onGradingComplete(round)
                currentStatus = EngineStatus.RUNNING
            }

            // All rounds complete
            val winnerId = groupSummaries.maxByOrNull { it.currentRoundScore ?: 0.0 }?.groupId
            emit(EvoKammerEvent.RunCompleted(config.runId, winnerId))
            currentStatus = EngineStatus.COMPLETED
            Napier.i("EvoKammerRunService: run ${config.runId} completed, winner=$winnerId")

        } catch (e: Exception) {
            Napier.e("EvoKammerRunService: run loop error", e)
            emit(EvoKammerEvent.RunAborted(config.runId, e.message ?: "internal_error"))
            currentStatus = EngineStatus.IDLE
        } finally {
            cleanup(config.runId)
        }
    }

    private fun cleanup(runId: String) {
        liveLifecycleManagers.clear()
        activeCoordinator = null
        activeGraderGroupManager = null
        activeRunId = null
        currentRound = null
        groupSummaries = emptyList()
        currentStatus = EngineStatus.IDLE
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
