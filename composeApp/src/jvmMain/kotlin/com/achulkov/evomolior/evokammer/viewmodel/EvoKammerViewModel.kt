package com.achulkov.evomolior.evokammer.viewmodel

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.evokammer.substrate.RunInfo
import com.achulkov.evomolior.evokammer.substrate.SubstrateRepository
import com.achulkov.evomolior.evokammer.substrate.SubstrateSnapshot
import com.achulkov.evomolior.evokammer.debug.DebugLogBridge
import com.achulkov.evomolior.evokammer.toLogLine
import com.achulkov.evomolior.evokammer.screens.buildArchivedGradingHistory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for the EvoKammer Desktop control panel.
 *
 * Bridges [EvoKammerEngineService] to Compose state. Exposes:
 * - [engineState] — current engine status + group summaries
 * - [recentEvents] — last N engine events for the live log
 * - [gradingHistory] — round-by-round grading results
 * - [availableRuns] — runs discovered on disk (for the Substrate Browser)
 * - [substrateSnapshot] — current Substrate Browser data for the selected run + group
 *
 * @param engineService The engine service implementation (in-process or IPC-bridged).
 * @param substrateFactory Optional substrate DB factory used by the Substrate Browser.
 * @param dataRootDir Optional data root directory used to discover runs on disk.
 */
class EvoKammerViewModel(
    private val engineService: EvoKammerEngineService,
    substrateFactory: SubstrateDatabaseFactory? = null,
    private val dataRootDir: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null
) {
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Substrate Browser state ────────────────────────────────────────────────
    private val substrateRepository: SubstrateRepository? =
        if (substrateFactory != null && dataRootDir != null)
            SubstrateRepository(substrateFactory, dataRootDir)
        else null

    private val _availableRuns = MutableStateFlow<List<RunInfo>>(emptyList())
    /** Runs discovered on disk, sorted newest-first. Used by the Substrate Browser. */
    val availableRuns: StateFlow<List<RunInfo>> = _availableRuns.asStateFlow()

    private val _selectedBrowserRunId = MutableStateFlow<String?>(null)
    /** The run ID selected in the Substrate Browser. */
    val selectedBrowserRunId: StateFlow<String?> = _selectedBrowserRunId.asStateFlow()

    private val _selectedBrowserGroupId = MutableStateFlow<String?>(null)
    /** The group ID selected in the Substrate Browser. */
    val selectedBrowserGroupId: StateFlow<String?> = _selectedBrowserGroupId.asStateFlow()

    private val _substrateSnapshot = MutableStateFlow<SubstrateSnapshot?>(null)
    /** Substrate data snapshot for the currently selected (runId, groupId). */
    val substrateSnapshot: StateFlow<SubstrateSnapshot?> = _substrateSnapshot.asStateFlow()

    private val _substrateLoading = MutableStateFlow(false)
    /** True while a [refreshSubstrate] is in progress. */
    val substrateLoading: StateFlow<Boolean> = _substrateLoading.asStateFlow()

    // ── Engine state ───────────────────────────────────────────────────────────
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

    private val _activeGraderGroupIds = MutableStateFlow<List<String>>(emptyList())
    /**
     * Grader group IDs seen so far in the current run (populated from [EvoKammerEvent.GraderRoundStarted]).
     * Empty until the first grader round starts (post-round evaluation).
     */
    val activeGraderGroupIds: StateFlow<List<String>> = _activeGraderGroupIds.asStateFlow()

    private val _metaArchiveSummary = MutableStateFlow<MetaArchiveSummary?>(null)
    val metaArchiveSummary: StateFlow<MetaArchiveSummary?> = _metaArchiveSummary.asStateFlow()

    private val _roundSummaryEntries = MutableStateFlow<List<MetaArchiveEntryRecord>>(emptyList())
    val roundSummaryEntries: StateFlow<List<MetaArchiveEntryRecord>> = _roundSummaryEntries.asStateFlow()

    private val _runCompletionEntries = MutableStateFlow<List<MetaArchiveEntryRecord>>(emptyList())
    val runCompletionEntries: StateFlow<List<MetaArchiveEntryRecord>> = _runCompletionEntries.asStateFlow()

    private val _archivedLogText = MutableStateFlow<String?>(null)
    /** Archived log text for a historical run, if one has been opened from disk. */
    val archivedLogText: StateFlow<String?> = _archivedLogText.asStateFlow()

    init {
        scope.launch {
            engineService.events.collect { event ->
                Napier.d("EvoKammerViewModel: received event ${event::class.simpleName}")
                handleEvent(event)
            }
        }
        refreshState()
        refreshAvailableRuns()
    }

    // ── Substrate Browser commands ─────────────────────────────────────────────

    /**
     * Re-scans the filesystem for available runs and updates [availableRuns].
     * If the currently active run is not yet in the list, it is not auto-selected here —
     * use [selectBrowserRun] or the auto-select triggered by RunStarted events.
     */
    fun refreshAvailableRuns() {
        val repo = substrateRepository ?: return
        scope.launch(ioDispatcher) {
            val runs = repo.discoverRuns()
            Napier.d("EvoKammerViewModel: discovered ${runs.size} run(s) on disk")
            _availableRuns.value = runs
            // Auto-select the most recent run if nothing is selected yet
            if (_selectedBrowserRunId.value == null && runs.isNotEmpty()) {
                val first = runs.first()
                _selectedBrowserRunId.value = first.runId
                if (_selectedBrowserGroupId.value == null && first.groupIds.isNotEmpty()) {
                    _selectedBrowserGroupId.value = first.groupIds.first()
                    refreshSubstrate()
                }
            }
        }
    }

    /** Selects a run in the Substrate Browser and reloads the snapshot for its first group. */
    fun selectBrowserRun(runId: String) {
        if (_selectedBrowserRunId.value == runId) return
        _selectedBrowserRunId.value = runId
        _substrateSnapshot.value = null
        val groupIds = _availableRuns.value.find { it.runId == runId }?.groupIds ?: emptyList()
        val firstGroup = groupIds.firstOrNull()
        _selectedBrowserGroupId.value = firstGroup
        if (firstGroup != null) refreshSubstrate()
    }

    /** Selects a group within the currently selected run and reloads the snapshot. */
    fun selectBrowserGroup(groupId: String) {
        if (_selectedBrowserGroupId.value == groupId) return
        _selectedBrowserGroupId.value = groupId
        refreshSubstrate()
    }

    /**
     * (Re)loads the [SubstrateSnapshot] for the currently selected (runId, groupId).
     * No-op if no selection is active or no repository is available.
     */
    fun refreshSubstrate() {
        val repo = substrateRepository ?: return
        val runId = _selectedBrowserRunId.value ?: return
        val groupId = _selectedBrowserGroupId.value ?: return

        scope.launch(ioDispatcher) {
            _substrateLoading.value = true
            try {
                val snapshot = repo.loadSnapshot(runId, groupId)
                _substrateSnapshot.value = snapshot
                Napier.d(
                    "EvoKammerViewModel: substrate snapshot loaded run=$runId group=$groupId " +
                    "total=${snapshot.totalRecords} records loaded=${snapshot.loaded}"
                )
            } catch (e: Exception) {
                Napier.e("EvoKammerViewModel: substrate refresh failed: ${e.message}", e)
            } finally {
                _substrateLoading.value = false
            }
        }
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

    /**
     * Loads archive state for a historical run so the dashboard, analysis screen, and log viewer
     * can reopen it after app restart.
     *
     * @param runId The archived run identifier.
     */
    fun loadArchivedRun(runId: String) {
        Napier.i("EvoKammerViewModel: loadArchivedRun runId=$runId")
        _engineState.value = _engineState.value.copy(
            status = EngineStatus.COMPLETED,
            activeRunId = runId,
            currentRound = null
        )
        _gradingHistory.value = emptyList()

        scope.launch {
            try {
                val summary = engineService.getMetaArchiveSummary(runId)
                _metaArchiveSummary.value = summary
                Napier.i("EvoKammerViewModel: archived meta-archive summary loaded total=${summary?.total}")
            } catch (e: Exception) {
                Napier.w("EvoKammerViewModel: failed to load archived meta-archive summary: ${e.message}")
            }
        }
        scope.launch {
            try {
                val roundSummaries = engineService.getMetaArchiveEntries(runId, "ROUND_SUMMARY")
                _roundSummaryEntries.value = roundSummaries
                Napier.i("EvoKammerViewModel: archived round summaries loaded count=${roundSummaries.size}")
                _gradingHistory.value = buildArchivedGradingHistory(roundSummaries)
            } catch (e: Exception) {
                Napier.w("EvoKammerViewModel: failed to load archived round summaries: ${e.message}")
            }
        }
        scope.launch {
            try {
                val completionEntries = engineService.getMetaArchiveEntries(runId, "RUN_COMPLETED")
                _runCompletionEntries.value = completionEntries
                Napier.i("EvoKammerViewModel: archived run completion entries loaded count=${completionEntries.size}")
            } catch (e: Exception) {
                Napier.w("EvoKammerViewModel: failed to load archived run completion entries: ${e.message}")
            }
        }
        loadArchivedLog(runId)
    }

    /** Clears any archived log text currently displayed by the log viewer. */
    fun clearArchivedLog() {
        _archivedLogText.value = null
    }

    /**
     * Loads the persisted debug log for an archived run into [archivedLogText].
     *
     * @param runId The archived run identifier.
     */
    fun loadArchivedLog(runId: String) {
        scope.launch(ioDispatcher) {
            try {
                val logFile = runDebugLogFile(runId)
                _archivedLogText.value = if (logFile?.exists() == true) logFile.readText() else null
                Napier.i(
                    "EvoKammerViewModel: archived log loaded runId=$runId chars=${_archivedLogText.value?.length ?: 0}"
                )
            } catch (e: Exception) {
                Napier.w("EvoKammerViewModel: failed to load archived log for $runId: ${e.message}")
            }
        }
    }

    private fun persistRunDebugLog(runId: String) {
        scope.launch(ioDispatcher) {
            try {
                val logFile = runDebugLogFile(runId) ?: return@launch
                logFile.parentFile?.mkdirs()
                val lines = DebugLogBridge.logs.value
                    .asReversed()
                    .joinToString(separator = "\n") { it.toLogLine() }
                logFile.writeText(lines)
                Napier.i("EvoKammerViewModel: persisted run debug log runId=$runId path=${logFile.absolutePath}")
            } catch (e: Exception) {
                Napier.w("EvoKammerViewModel: failed to persist run debug log for $runId: ${e.message}")
            }
        }
    }

    private fun runDebugLogFile(runId: String): File? =
        dataRootDir?.let { File(it, "evokammer/runs/$runId/results/debug-log.txt") }

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
                _activeGraderGroupIds.value = emptyList() // reset for new run
                // Refresh to pick up group summaries populated by EvoKammerRunService.startRun
                refreshState()
                _roundSummaryEntries.value = emptyList()
                _runCompletionEntries.value = emptyList()
                _metaArchiveSummary.value = null
                // Auto-select this run in the Substrate Browser so data is visible immediately
                val startedRunId = event.runId
                if (_selectedBrowserRunId.value != startedRunId) {
                    _selectedBrowserRunId.value = startedRunId
                    _selectedBrowserGroupId.value = null
                    _substrateSnapshot.value = null
                }
                refreshAvailableRuns()
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
                // Refresh substrate browser after each round (new KB/KG facts, staging, etc.)
                refreshSubstrate()
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
                scope.launch {
                    try {
                        val roundSummaries = engineService.getMetaArchiveEntries(event.runId, "ROUND_SUMMARY")
                        _roundSummaryEntries.value = roundSummaries
                        Napier.i("EvoKammerViewModel: round summaries loaded count=${roundSummaries.size}")
                    } catch (e: Exception) {
                        Napier.w("EvoKammerViewModel: failed to load round summaries: ${e.message}")
                    }
                }
                scope.launch {
                    try {
                        val completionEntries = engineService.getMetaArchiveEntries(event.runId, "RUN_COMPLETED")
                        _runCompletionEntries.value = completionEntries
                        Napier.i("EvoKammerViewModel: run completion entries loaded count=${completionEntries.size}")
                    } catch (e: Exception) {
                        Napier.w("EvoKammerViewModel: failed to load run completion entries: ${e.message}")
                    }
                }
                persistRunDebugLog(event.runId)
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
            is EvoKammerEvent.AgentSpawned -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(activeAgents = s.activeAgents + 1) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            is EvoKammerEvent.AgentDied -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(activeAgents = maxOf(0, s.activeAgents - 1)) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            is EvoKammerEvent.ShuruStatus -> {
                if (!event.available) {
                    Napier.e("EvoKammerViewModel: Shuru unavailable — ${event.message}")
                    scope.launch { _error.emit("⚠️ Shuru: ${event.message}") }
                } else {
                    Napier.i("EvoKammerViewModel: Shuru OK — ${event.version}")
                }
            }
            is EvoKammerEvent.AgentSpawnFailed -> {
                Napier.w("EvoKammerViewModel: agent spawn failed group=${event.groupId} role=${event.role} error=${event.error}")
                scope.launch { _error.emit("[${event.groupId}] Spawn failed (${event.role.name}): ${event.error}") }
            }
            is EvoKammerEvent.StagingPromoted,
            is EvoKammerEvent.KGFactEmitted,
            is EvoKammerEvent.ToolRegistered,
            is EvoKammerEvent.DeadEndRecorded,
            is EvoKammerEvent.LegacyProtocolExtracted -> {
                // These events mutate substrate data — refresh if the affected group is selected
                val affectedGroup = when (event) {
                    is EvoKammerEvent.StagingPromoted -> event.groupId
                    is EvoKammerEvent.KGFactEmitted -> event.groupId
                    is EvoKammerEvent.ToolRegistered -> event.groupId
                    is EvoKammerEvent.DeadEndRecorded -> event.groupId
                    is EvoKammerEvent.LegacyProtocolExtracted -> event.groupId
                    else -> null
                }
                if (affectedGroup != null && affectedGroup == _selectedBrowserGroupId.value) {
                    refreshSubstrate()
                }
            }
            is EvoKammerEvent.GraderRoundStarted -> {
                // Track grader group IDs so the Dashboard can display them
                val current = _activeGraderGroupIds.value
                if (event.graderGroupId !in current) {
                    _activeGraderGroupIds.value = current + event.graderGroupId
                }
                Napier.i("EvoKammerViewModel: grader round started graderGroupId=${event.graderGroupId} round=${event.roundIndex}")
            }
            is EvoKammerEvent.EngineProgress -> {
                Napier.d("EvoKammerViewModel: engine progress phase=${event.phase} msg=${event.message}")
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
    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()
    override suspend fun getResultsBundle(runId: String): ByteArray? = null
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
