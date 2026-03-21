package com.achulkov.evomolior.evokammer.viewmodel

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the EvoKammer Desktop control panel.
 *
 * Bridges [EvoKammerEngineService] to Compose state. Exposes:
 * - [engineState] — current engine status + group summaries
 * - [recentEvents] — last N engine events for the live log
 * - [gradingHistory] — round-by-round grading results
 *
 * @param engineService The engine service implementation (in-process or IPC-bridged).
 */
class EvoKammerViewModel(
    private val engineService: EvoKammerEngineService
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engineState = MutableStateFlow(
        EngineState(
            status = EngineStatus.IDLE,
            activeRunId = null,
            currentRound = null,
            groupSummaries = emptyList()
        )
    )
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<EvoKammerEvent>>(emptyList())
    val recentEvents: StateFlow<List<EvoKammerEvent>> = _recentEvents.asStateFlow()

    private val _gradingHistory = MutableStateFlow<List<GradingResult>>(emptyList())
    val gradingHistory: StateFlow<List<GradingResult>> = _gradingHistory.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _metaArchiveSummary = MutableStateFlow<MetaArchiveSummary?>(null)
    val metaArchiveSummary: StateFlow<MetaArchiveSummary?> = _metaArchiveSummary.asStateFlow()

    init {
        scope.launch {
            engineService.events.collect { event ->
                Napier.d("EvoKammerViewModel: received event ${event::class.simpleName}")
                handleEvent(event)
            }
        }
        refreshState()
    }

    /**
     * Starts a new run with the given [config].
     *
     * Emits an error via [error] flow if the engine rejects the start.
     *
     * @param config The run configuration.
     */
    fun startRun(config: RunConfig) {
        Napier.i("EvoKammerViewModel: startRun called runId=${config.runId} objective='${config.objective}'")
        scope.launch {
            try {
                val runId = engineService.startRun(config)
                Napier.i("EvoKammerViewModel: startRun accepted by engine, runId=$runId")
                // Eagerly refresh state to pick up group summaries populated during startRun
                refreshState()
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to start run ${config.runId}", e)
                _error.emit("Failed to start run: ${e.message}")
            }
        }
    }

    /** Pauses the active run. */
    fun pauseRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoKammerViewModel: pauseRun runId=$runId")
        scope.launch {
            try {
                engineService.pauseRun(runId)
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to pause run $runId", e)
                _error.emit("Failed to pause run: ${e.message}")
            }
        }
    }

    /** Resumes the active run. */
    fun resumeRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoKammerViewModel: resumeRun runId=$runId")
        scope.launch {
            try {
                engineService.resumeRun(runId)
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to resume run $runId", e)
                _error.emit("Failed to resume run: ${e.message}")
            }
        }
    }

    /** Aborts the active run. */
    fun abortRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoKammerViewModel: abortRun runId=$runId")
        scope.launch {
            try {
                engineService.abortRun(runId, "user_requested")
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to abort run $runId", e)
                _error.emit("Failed to abort run: ${e.message}")
            }
        }
    }

    private fun refreshState() {
        scope.launch {
            try {
                val state = engineService.getState()
                Napier.d("EvoKammerViewModel: refreshState status=${state.status} runId=${state.activeRunId} groups=${state.groupSummaries.size}")
                _engineState.value = state
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to refresh state", e)
            }
        }
    }

    private fun handleEvent(event: EvoKammerEvent) {
        // Append to recent events (cap at 500)
        val current = _recentEvents.value.toMutableList()
        current.add(0, event)
        if (current.size > 500) current.removeAt(current.size - 1)
        _recentEvents.value = current

        when (event) {
            is EvoKammerEvent.RunStarted -> {
                Napier.i("EvoKammerViewModel: run started runId=${event.runId}")
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    activeRunId = event.runId
                )
                // Refresh to pick up group summaries populated by EvoKammerRunService.startRun
                refreshState()
            }
            is EvoKammerEvent.RoundStarted -> {
                Napier.i("EvoKammerViewModel: round started round=${event.roundIndex} runId=${event.runId}")
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    currentRound = event.roundIndex
                )
                refreshState()
            }
            is EvoKammerEvent.RoundCompleted -> {
                Napier.i("EvoKammerViewModel: round completed round=${event.result.roundIndex}")
                val history = _gradingHistory.value.toMutableList()
                history.add(event.result)
                _gradingHistory.value = history
                refreshState()
            }
            is EvoKammerEvent.RunCompleted -> {
                Napier.i("EvoKammerViewModel: run completed runId=${event.runId} winner=${event.winnerId}")
                _engineState.value = _engineState.value.copy(status = EngineStatus.COMPLETED)
                // Load meta-archive summary for the PostRunAnalysis screen
                scope.launch {
                    try {
                        val summary = engineService.getMetaArchiveSummary(event.runId)
                        _metaArchiveSummary.value = summary
                        Napier.i("EvoKammerViewModel: meta-archive summary loaded total=${summary?.total}")
                    } catch (e: Exception) {
                        Napier.w("EvoKammerViewModel: failed to load meta-archive summary: ${e.message}")
                    }
                }
            }
            is EvoKammerEvent.RunAborted -> {
                Napier.w("EvoKammerViewModel: run aborted runId=${event.runId} reason=${event.reason}")
                _engineState.value = _engineState.value.copy(status = EngineStatus.IDLE)
                scope.launch { _error.emit("Run aborted: ${event.reason}") }
            }
            is EvoKammerEvent.GroupBudgetChanged -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(remainingBudget = event.remainingBudget) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            else -> {
                Napier.d("EvoKammerViewModel: unhandled event type ${event::class.simpleName}")
            }
        }
    }
}

/**
 * Stub engine service used when no real engine is connected (shows idle state).
 *
 * Only intended for Compose Previews and tests. The real app wires `EvoKammerRunService`.
 */
class StubEvoKammerEngineService : EvoKammerEngineService {
    private val _events = MutableSharedFlow<EvoKammerEvent>()
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    override suspend fun startRun(config: RunConfig): String = config.runId
    override suspend fun pauseRun(runId: String) {}
    override suspend fun resumeRun(runId: String) {}
    override suspend fun abortRun(runId: String, reason: String) {}
    override suspend fun getState(): EngineState = EngineState(
        status = EngineStatus.IDLE,
        activeRunId = null,
        currentRound = null,
        groupSummaries = emptyList()
    )
    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = null
}

/** Builds a default [RunConfig] for the setup form. */
@Suppress("unused") // imported by EvoKammerRunSetupScreen
fun defaultRunConfig(apiKey: String = ""): RunConfig = RunConfig(
    runId = java.util.UUID.randomUUID().toString(),
    objective = "Design a distributed, fault-tolerant knowledge synthesis system",
    groupCount = 3,
    groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory, GroupProfileType.Commune),
    rounds = 5,
    roundDurationMinutes = 30,
    maxAgentsPerGroup = 6,
    scoringDimensions = listOf(
        ScoringDimension("kb_density", "KB Density", "Volume and quality of KB entries", 0.3),
        ScoringDimension("kg_coherence", "KG Coherence", "Knowledge graph consistency score", 0.25),
        ScoringDimension("tool_utility", "Tool Utility", "Quality of custom tools built", 0.25),
        ScoringDimension("plan_execution", "Plan Execution", "% of WiP tasks completed", 0.2)
    ),
    openRouterApiKey = apiKey
)
