package com.achulkov.evomolior.evokammer.viewmodel

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.GroupSummary
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
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

    init {
        scope.launch {
            engineService.events.collect { event ->
                handleEvent(event)
            }
        }
        refreshState()
    }

    /** Starts a new run with the given [config]. */
    fun startRun(config: RunConfig) {
        scope.launch {
            try {
                engineService.startRun(config)
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: failed to start run", e)
                _error.emit("Failed to start run: ${e.message}")
            }
        }
    }

    /** Pauses the active run. */
    fun pauseRun() {
        val runId = _engineState.value.activeRunId ?: return
        scope.launch {
            try {
                engineService.pauseRun(runId)
            } catch (e: Exception) {
                _error.emit("Failed to pause run: ${e.message}")
            }
        }
    }

    /** Resumes the active run. */
    fun resumeRun() {
        val runId = _engineState.value.activeRunId ?: return
        scope.launch {
            try {
                engineService.resumeRun(runId)
            } catch (e: Exception) {
                _error.emit("Failed to resume run: ${e.message}")
            }
        }
    }

    /** Aborts the active run. */
    fun abortRun() {
        val runId = _engineState.value.activeRunId ?: return
        scope.launch {
            try {
                engineService.abortRun(runId, "user_requested")
            } catch (e: Exception) {
                _error.emit("Failed to abort run: ${e.message}")
            }
        }
    }

    private fun refreshState() {
        scope.launch {
            try {
                _engineState.value = engineService.getState()
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
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    activeRunId = event.runId
                )
            }
            is EvoKammerEvent.RoundStarted -> {
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    currentRound = event.roundIndex
                )
            }
            is EvoKammerEvent.RoundCompleted -> {
                val history = _gradingHistory.value.toMutableList()
                history.add(event.result)
                _gradingHistory.value = history
            }
            is EvoKammerEvent.RunCompleted -> {
                _engineState.value = _engineState.value.copy(status = EngineStatus.COMPLETED)
            }
            is EvoKammerEvent.RunAborted -> {
                _engineState.value = _engineState.value.copy(status = EngineStatus.IDLE)
            }
            is EvoKammerEvent.GroupBudgetChanged -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(remainingBudget = event.remainingBudget) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            else -> { /* other events handled by the events list */ }
        }
    }
}

/** Stub engine service used when no real engine is connected (shows idle state). */
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
}

/** Builds a default [RunConfig] for the setup form. */
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
