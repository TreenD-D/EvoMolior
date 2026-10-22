package com.achulkov.evomolior.evomolior.viewmodel

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.EngineState
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.MetaArchiveEntryRecord
import com.achulkov.evomolior.api.service.MetaArchiveSummary
import com.achulkov.evomolior.api.service.RunOutcomePresentation
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.evomolior.substrate.RunInfo
import com.achulkov.evomolior.evomolior.substrate.SubstrateRepository
import com.achulkov.evomolior.evomolior.substrate.SubstrateSnapshot
import com.achulkov.evomolior.evomolior.debug.DebugLogBridge
import com.achulkov.evomolior.evomolior.toLogLine
import com.achulkov.evomolior.evomolior.screens.buildArchivedGradingHistory
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
 * ViewModel for the EvoMolior Desktop control panel.
 *
 * Bridges [EvoMoliorEngineService] to Compose state. Exposes:
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
class EvoMoliorViewModel(
    private val engineService: EvoMoliorEngineService,
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

    private val _recentEvents = MutableStateFlow<List<EvoMoliorEvent>>(emptyList())
    val recentEvents: StateFlow<List<EvoMoliorEvent>> = _recentEvents.asStateFlow()

    private val _gradingHistory = MutableStateFlow<List<GradingResult>>(emptyList())
    val gradingHistory: StateFlow<List<GradingResult>> = _gradingHistory.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _activeGraderGroupIds = MutableStateFlow<List<String>>(emptyList())
    /**
     * Grader group IDs seen so far in the current run (populated from [EvoMoliorEvent.GraderRoundStarted]).
     * Empty until the first grader round starts (post-round evaluation).
     */
    val activeGraderGroupIds: StateFlow<List<String>> = _activeGraderGroupIds.asStateFlow()

    private val _metaArchiveSummary = MutableStateFlow<MetaArchiveSummary?>(null)
    val metaArchiveSummary: StateFlow<MetaArchiveSummary?> = _metaArchiveSummary.asStateFlow()

    private val _roundSummaryEntries = MutableStateFlow<List<MetaArchiveEntryRecord>>(emptyList())
    val roundSummaryEntries: StateFlow<List<MetaArchiveEntryRecord>> = _roundSummaryEntries.asStateFlow()

    private val _runCompletionEntries = MutableStateFlow<List<MetaArchiveEntryRecord>>(emptyList())
    val runCompletionEntries: StateFlow<List<MetaArchiveEntryRecord>> = _runCompletionEntries.asStateFlow()

    private val _runOutcomePresentation = MutableStateFlow<RunOutcomePresentation?>(null)
    /** Verified accepted, provisional, or startup-diagnostic terminal package projection. */
    val runOutcomePresentation: StateFlow<RunOutcomePresentation?> =
        _runOutcomePresentation.asStateFlow()

    private val _archivedLogText = MutableStateFlow<String?>(null)
    /** Archived log text for a historical run, if one has been opened from disk. */
    val archivedLogText: StateFlow<String?> = _archivedLogText.asStateFlow()

    init {
        scope.launch {
            engineService.events.collect { event ->
                Napier.d("EvoMoliorViewModel: received event ${event::class.simpleName}")
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
            Napier.d("EvoMoliorViewModel: discovered ${runs.size} run(s) on disk")
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
                    "EvoMoliorViewModel: substrate snapshot loaded run=$runId group=$groupId " +
                    "total=${snapshot.totalRecords} records loaded=${snapshot.loaded}"
                )
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: substrate refresh failed: ${e.message}", e)
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
        Napier.i("EvoMoliorViewModel: startRun called runId=${config.runId} objective='${config.objective}'")
        scope.launch {
            try {
                val runId = engineService.startRun(config)
                Napier.i("EvoMoliorViewModel: startRun accepted by engine, runId=$runId")
                // Eagerly refresh state to pick up group summaries populated during startRun
                refreshState()
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: failed to start run ${config.runId}", e)
                _error.emit("Failed to start run: ${e.message}")
            }
        }
    }

    /** Pauses the active run. */
    fun pauseRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoMoliorViewModel: pauseRun runId=$runId")
        scope.launch {
            try {
                engineService.pauseRun(runId)
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: failed to pause run $runId", e)
                _error.emit("Failed to pause run: ${e.message}")
            }
        }
    }

    /** Resumes the active run. */
    fun resumeRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoMoliorViewModel: resumeRun runId=$runId")
        scope.launch {
            try {
                engineService.resumeRun(runId)
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: failed to resume run $runId", e)
                _error.emit("Failed to resume run: ${e.message}")
            }
        }
    }

    /** Aborts the active run. */
    fun abortRun() {
        val runId = _engineState.value.activeRunId ?: return
        Napier.i("EvoMoliorViewModel: abortRun runId=$runId")
        scope.launch {
            try {
                engineService.abortRun(runId, "user_requested")
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: failed to abort run $runId", e)
                _error.emit("Failed to abort run: ${e.message}")
            }
        }
    }

    private fun refreshState() {
        scope.launch {
            try {
                val state = engineService.getState()
                Napier.d("EvoMoliorViewModel: refreshState status=${state.status} runId=${state.activeRunId} groups=${state.groupSummaries.size}")
                _engineState.value = state
            } catch (e: Exception) {
                Napier.e("EvoMoliorViewModel: failed to refresh state", e)
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
        Napier.i("EvoMoliorViewModel: loadArchivedRun runId=$runId")
        _engineState.value = _engineState.value.copy(
            status = EngineStatus.EVALUATING,
            activeRunId = runId,
            currentRound = null
        )
        _gradingHistory.value = emptyList()
        _runOutcomePresentation.value = null
        loadOutcomePresentation(runId)

        scope.launch {
            try {
                val summary = engineService.getMetaArchiveSummary(runId)
                _metaArchiveSummary.value = summary
                Napier.i("EvoMoliorViewModel: archived meta-archive summary loaded total=${summary?.total}")
            } catch (e: Exception) {
                Napier.w("EvoMoliorViewModel: failed to load archived meta-archive summary: ${e.message}")
            }
        }
        scope.launch {
            try {
                val roundSummaries = engineService.getMetaArchiveEntries(runId, "ROUND_SUMMARY")
                _roundSummaryEntries.value = roundSummaries
                Napier.i("EvoMoliorViewModel: archived round summaries loaded count=${roundSummaries.size}")
                _gradingHistory.value = buildArchivedGradingHistory(roundSummaries)
            } catch (e: Exception) {
                Napier.w("EvoMoliorViewModel: failed to load archived round summaries: ${e.message}")
            }
        }
        scope.launch {
            try {
                val completionEntries = engineService.getMetaArchiveEntries(runId, "RUN_COMPLETED")
                val recoveredEntries = engineService.getMetaArchiveEntries(
                    runId,
                    "RUN_RECOVERED_NON_ACCEPTED"
                )
                check(completionEntries.isEmpty() || recoveredEntries.isEmpty()) {
                    "Run $runId has conflicting completed and recovered terminal authority"
                }
                _runCompletionEntries.value = completionEntries
                if (_runOutcomePresentation.value == null) {
                    _engineState.value = _engineState.value.copy(
                        status = if (recoveredEntries.isNotEmpty()) {
                            EngineStatus.RECOVERED
                        } else {
                            EngineStatus.COMPLETED
                        }
                    )
                }
                Napier.i("EvoMoliorViewModel: archived run completion entries loaded count=${completionEntries.size}")
            } catch (e: Exception) {
                Napier.w("EvoMoliorViewModel: failed to load archived run completion entries: ${e.message}")
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
                    "EvoMoliorViewModel: archived log loaded runId=$runId chars=${_archivedLogText.value?.length ?: 0}"
                )
            } catch (e: Exception) {
                Napier.w("EvoMoliorViewModel: failed to load archived log for $runId: ${e.message}")
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
                Napier.i("EvoMoliorViewModel: persisted run debug log runId=$runId path=${logFile.absolutePath}")
            } catch (e: Exception) {
                Napier.w("EvoMoliorViewModel: failed to persist run debug log for $runId: ${e.message}")
            }
        }
    }

    private fun runDebugLogFile(runId: String): File? =
        dataRootDir?.let { File(it, "evomolior/runs/$runId/results/debug-log.txt") }

    private fun handleEvent(event: EvoMoliorEvent) {
        // Append to recent events (cap at 500)
        val current = _recentEvents.value.toMutableList()
        current.add(0, event)
        if (current.size > 500) current.removeAt(current.size - 1)
        _recentEvents.value = current

        when (event) {
            is EvoMoliorEvent.RunStarted -> {
                Napier.i("EvoMoliorViewModel: run started runId=${event.runId}")
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    activeRunId = event.runId
                )
                _activeGraderGroupIds.value = emptyList() // reset for new run
                // Refresh to pick up group summaries populated by EvoMoliorRunService.startRun
                refreshState()
                _roundSummaryEntries.value = emptyList()
                _runCompletionEntries.value = emptyList()
                _metaArchiveSummary.value = null
                _runOutcomePresentation.value = null
                // Auto-select this run in the Substrate Browser so data is visible immediately
                val startedRunId = event.runId
                if (_selectedBrowserRunId.value != startedRunId) {
                    _selectedBrowserRunId.value = startedRunId
                    _selectedBrowserGroupId.value = null
                    _substrateSnapshot.value = null
                }
                refreshAvailableRuns()
            }
            is EvoMoliorEvent.RoundStarted -> {
                Napier.i("EvoMoliorViewModel: round started round=${event.roundIndex} runId=${event.runId}")
                _engineState.value = _engineState.value.copy(
                    status = EngineStatus.RUNNING,
                    currentRound = event.roundIndex
                )
                refreshState()
            }
            is EvoMoliorEvent.RoundCompleted -> {
                Napier.i("EvoMoliorViewModel: round completed round=${event.result.roundIndex}")
                val history = _gradingHistory.value.toMutableList()
                history.add(event.result)
                _gradingHistory.value = history
                refreshState()
                // Refresh substrate browser after each round (new KB/KG facts, staging, etc.)
                refreshSubstrate()
            }
            is EvoMoliorEvent.RunCompleted -> {
                Napier.i("EvoMoliorViewModel: run completed runId=${event.runId} winner=${event.winnerId}")
                _engineState.value = _engineState.value.copy(status = EngineStatus.COMPLETED)
                // Load meta-archive summary for the PostRunAnalysis screen
                scope.launch {
                    try {
                        val summary = engineService.getMetaArchiveSummary(event.runId)
                        _metaArchiveSummary.value = summary
                        Napier.i("EvoMoliorViewModel: meta-archive summary loaded total=${summary?.total}")
                    } catch (e: Exception) {
                        Napier.w("EvoMoliorViewModel: failed to load meta-archive summary: ${e.message}")
                    }
                }
                scope.launch {
                    try {
                        val roundSummaries = engineService.getMetaArchiveEntries(event.runId, "ROUND_SUMMARY")
                        _roundSummaryEntries.value = roundSummaries
                        Napier.i("EvoMoliorViewModel: round summaries loaded count=${roundSummaries.size}")
                    } catch (e: Exception) {
                        Napier.w("EvoMoliorViewModel: failed to load round summaries: ${e.message}")
                    }
                }
                scope.launch {
                    try {
                        val completionEntries = engineService.getMetaArchiveEntries(event.runId, "RUN_COMPLETED")
                        _runCompletionEntries.value = completionEntries
                        Napier.i("EvoMoliorViewModel: run completion entries loaded count=${completionEntries.size}")
                    } catch (e: Exception) {
                        Napier.w("EvoMoliorViewModel: failed to load run completion entries: ${e.message}")
                    }
                }
                persistRunDebugLog(event.runId)
                loadOutcomePresentation(event.runId)
            }
            is EvoMoliorEvent.RunCompletedProvisional -> {
                Napier.w(
                    "EvoMoliorViewModel: run completed provisionally " +
                        "runId=${event.runId} reason=${event.reason}"
                )
                _engineState.value = _engineState.value.copy(
                    status = engineStatusForExecution(event.terminalOutcome.executionStatus)
                )
                scope.launch {
                    _metaArchiveSummary.value =
                        engineService.getMetaArchiveSummary(event.runId)
                    _roundSummaryEntries.value = engineService.getMetaArchiveEntries(
                        event.runId,
                        "ROUND_SUMMARY"
                    )
                }
                persistRunDebugLog(event.runId)
                loadOutcomePresentation(event.runId)
                refreshAvailableRuns()
                refreshSubstrate()
            }
            is EvoMoliorEvent.RunRecovered -> {
                Napier.w(
                    "EvoMoliorViewModel: run recovered without acceptance " +
                        "runId=${event.runId} reason=${event.reason}"
                )
                _engineState.value = _engineState.value.copy(
                    status = engineStatusForExecution(event.terminalOutcome?.executionStatus)
                )
                scope.launch {
                    _metaArchiveSummary.value =
                        engineService.getMetaArchiveSummary(event.runId)
                    _roundSummaryEntries.value = engineService.getMetaArchiveEntries(
                        event.runId,
                        "ROUND_SUMMARY"
                    )
                }
                persistRunDebugLog(event.runId)
                loadOutcomePresentation(event.runId)
                refreshAvailableRuns()
                refreshSubstrate()
            }
            is EvoMoliorEvent.RunAborted -> {
                Napier.w("EvoMoliorViewModel: run aborted runId=${event.runId} reason=${event.reason}")
                _engineState.value = _engineState.value.copy(status = EngineStatus.IDLE)
                scope.launch { _error.emit("Run aborted: ${event.reason}") }
                loadOutcomePresentation(event.runId)
            }
            is EvoMoliorEvent.GroupBudgetChanged -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(remainingBudget = event.remainingBudget) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            is EvoMoliorEvent.AgentSpawned -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(activeAgents = s.activeAgents + 1) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            is EvoMoliorEvent.AgentDied -> {
                val summaries = _engineState.value.groupSummaries.map { s ->
                    if (s.groupId == event.groupId) s.copy(activeAgents = maxOf(0, s.activeAgents - 1)) else s
                }
                _engineState.value = _engineState.value.copy(groupSummaries = summaries)
            }
            is EvoMoliorEvent.ShuruStatus -> {
                if (!event.available) {
                    Napier.e("EvoMoliorViewModel: Shuru unavailable — ${event.message}")
                    scope.launch { _error.emit("⚠️ Shuru: ${event.message}") }
                } else {
                    Napier.i("EvoMoliorViewModel: Shuru OK — ${event.version}")
                }
            }
            is EvoMoliorEvent.AgentSpawnFailed -> {
                Napier.w("EvoMoliorViewModel: agent spawn failed group=${event.groupId} role=${event.role} error=${event.error}")
                scope.launch { _error.emit("[${event.groupId}] Spawn failed (${event.role.name}): ${event.error}") }
            }
            is EvoMoliorEvent.StagingPromoted,
            is EvoMoliorEvent.KGFactEmitted,
            is EvoMoliorEvent.ToolRegistered,
            is EvoMoliorEvent.DeadEndRecorded,
            is EvoMoliorEvent.LegacyProtocolExtracted -> {
                // These events mutate substrate data — refresh if the affected group is selected
                val affectedGroup = when (event) {
                    is EvoMoliorEvent.StagingPromoted -> event.groupId
                    is EvoMoliorEvent.KGFactEmitted -> event.groupId
                    is EvoMoliorEvent.ToolRegistered -> event.groupId
                    is EvoMoliorEvent.DeadEndRecorded -> event.groupId
                    is EvoMoliorEvent.LegacyProtocolExtracted -> event.groupId
                    else -> null
                }
                if (affectedGroup != null && affectedGroup == _selectedBrowserGroupId.value) {
                    refreshSubstrate()
                }
            }
            is EvoMoliorEvent.GraderRoundStarted -> {
                // Track grader group IDs so the Dashboard can display them
                val current = _activeGraderGroupIds.value
                if (event.graderGroupId !in current) {
                    _activeGraderGroupIds.value = current + event.graderGroupId
                }
                Napier.i("EvoMoliorViewModel: grader round started graderGroupId=${event.graderGroupId} round=${event.roundIndex}")
            }
            is EvoMoliorEvent.EngineProgress -> {
                Napier.d("EvoMoliorViewModel: engine progress phase=${event.phase} msg=${event.message}")
            }
            else -> {
                Napier.d("EvoMoliorViewModel: unhandled event type ${event::class.simpleName}")
            }
        }
    }

    private fun loadOutcomePresentation(runId: String) {
        scope.launch(ioDispatcher) {
            runCatching {
                engineService.getRunOutcomePresentation(runId)
            }.onSuccess { outcome ->
                _runOutcomePresentation.value = outcome
                if (outcome != null) {
                    _engineState.value = _engineState.value.copy(
                        status = engineStatusForExecution(outcome.executionStatus)
                    )
                }
            }.onFailure { error ->
                Napier.w(
                    "EvoMoliorViewModel: failed to load run outcome for $runId: " +
                        error.message
                )
            }
        }
    }

    private fun engineStatusForExecution(
        executionStatus: ExecutionCompletionStatus?,
    ): EngineStatus = when (executionStatus) {
        ExecutionCompletionStatus.COMPLETED -> EngineStatus.COMPLETED
        ExecutionCompletionStatus.FAILED_PRE_ADMISSION,
        ExecutionCompletionStatus.FAILED_CORRUPT_STATE,
        ExecutionCompletionStatus.FAILED_UNWRITABLE_OUTPUT -> EngineStatus.RECOVERED
        null -> EngineStatus.RECOVERED
    }
}

/**
 * Stub engine service used when no real engine is connected (shows idle state).
 *
 * Only intended for Compose Previews and tests. The real app wires `EvoMoliorRunService`.
 */
class StubEvoMoliorEngineService : EvoMoliorEngineService {
    private val _events = MutableSharedFlow<EvoMoliorEvent>()
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

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
    override suspend fun getOverseerKernelState(): OverseerKernelState = OverseerKernelState()
    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        OverseerKernelEligibility(allowed = false, reasons = listOf("stub_engine"))
    override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        OverseerExperimentPlanEligibility(allowed = false, reasons = listOf("stub_engine"))
    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState = OverseerKernelState()
    override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState = OverseerKernelState()
    override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState = OverseerKernelState()
    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> = emptyList()
    override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> = emptyList()
    override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> = emptyList()
    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> = emptyList()
}

/** Builds a default [RunConfig] for the setup form. */
@Suppress("unused") // imported by EvoMoliorRunSetupScreen
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
