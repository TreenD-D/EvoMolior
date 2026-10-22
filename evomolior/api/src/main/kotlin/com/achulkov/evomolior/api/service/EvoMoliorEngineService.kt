package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelArchive
import com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelState
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.SharedFlow

/**
 * The main service interface exposed by the EvoMolior engine to the Desktop UI.
 *
 * Implementations run in the engine process. The Desktop UI interacts with this interface
 * either directly (in-process) or via the Ktor WebSocket IPC bridge.
 *
 * All methods are suspend functions — the engine is fully coroutine-based.
 */
interface EvoMoliorEngineService {

    /** Observable stream of all engine events (real-time). */
    val events: SharedFlow<EvoMoliorEvent>

    /**
     * Starts a new civilisation run with the provided [config].
     *
     * If a run is already active, throws [IllegalStateException].
     *
     * @return The run ID (same as [RunConfig.runId]).
     */
    suspend fun startRun(config: RunConfig): String

    /**
     * Resumes a previously persisted run after process death using the supplied [config].
     *
     * The [RunConfig.runId] must point at an unfinished persisted run. Implementations may
     * validate that the non-secret fields of [config] match the persisted recovery snapshot
     * when one exists.
     *
     * The caller must supply a fresh API key in [RunConfig.openRouterApiKey]; secrets are not
     * expected to be stored in the persisted recovery snapshot.
     *
     * @return The resumed run ID (same as [RunConfig.runId]).
     */
    suspend fun resumePersistedRun(config: RunConfig): String {
        error("Persisted run resume is not supported by this engine service")
    }

    /**
     * Pauses the active run (suspends all agent loops).
     * The run can be resumed with [resumeRun].
     *
     * @param runId Must match the active run ID.
     */
    suspend fun pauseRun(runId: String)

    /**
     * Resumes a previously paused run.
     *
     * @param runId Must match the active run ID.
     */
    suspend fun resumeRun(runId: String)

    /**
     * Aborts the active run immediately.
     * All agents are killed and resources are released.
     *
     * @param runId Must match the active run ID.
     * @param reason Human-readable reason for abort.
     */
    suspend fun abortRun(runId: String, reason: String = "user_requested")

    /**
     * Returns a snapshot of the current engine state.
     */
    suspend fun getState(): EngineState

    /**
     * Returns a summary of meta-archive entries for a completed [runId].
     *
     * Queries the meta-archive DAO for total count and per-type breakdown.
     * Returns null if no archive data is available (e.g., run not yet complete or no DB).
     *
     * @param runId The ID of the completed run.
     */
    suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary?

    /**
     * Returns archived meta-archive entries for a completed [runId].
     *
     * If [entryType] is non-null, only entries of that type are returned.
     *
     * @param runId The completed run ID.
     * @param entryType Optional entry type filter.
     */
    suspend fun getMetaArchiveEntries(runId: String, entryType: String? = null): List<MetaArchiveEntryRecord>

    /**
     * Returns the downloadable results ZIP for a completed or partial run.
     *
     * @param runId The run identifier.
     * @return ZIP bytes, or null if the bundle is not yet available. Implementations should
     *   prefer the run-service output-spec cache first, then the request's completion record
     *   `requestedOutputSpec`, and finally persisted-state inference when no other durable
     *   source is available.
     */
    suspend fun getResultsBundle(runId: String): ByteArray?

    /**
     * Returns a strict typed projection of the verified terminal outcome archive.
     *
     * @param runId Completed, recovered, or startup-rejected run identity.
     */
    suspend fun getRunOutcomePresentation(runId: String): RunOutcomePresentation? =
        getResultsBundle(runId)?.let(RunOutcomeBundleInspector::inspect)

    /**
     * Returns the current durable Overseer kernel snapshot.
     */
    suspend fun getOverseerKernelState(): OverseerKernelState

    /**
     * Returns the deterministic preflight verdict for an Overseer hypothesis.
     *
     * @param hypothesis Hypothesis to validate.
     */
    suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility

    /**
     * Returns the deterministic preflight verdict for an Overseer experiment plan.
     *
     * @param experimentPlan Experiment plan to validate.
     */
    suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility

    /**
     * Persists a formalized Overseer hypothesis and returns the updated snapshot.
     */
    suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState

    /**
     * Persists an Overseer experiment plan and returns the updated snapshot.
     */
    suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState

    /**
     * Schedules all eligible Overseer experiment plans and returns the updated snapshot.
     */
    suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState

    /**
     * Launches a scheduled Overseer experiment run using an operator-supplied run config.
     *
     * @param experimentPlanId Experiment plan whose schedule is being consumed.
     * @param launchRequest Scheduled run identifier and concrete run configuration to start.
     * @return Launch response with the started run identifier.
     */
    suspend fun launchOverseerExperimentRun(
        experimentPlanId: String,
        launchRequest: OverseerExperimentLaunchRequest
    ): OverseerExperimentLaunchResponse {
        error("Overseer experiment launch is not supported by this engine service")
    }

    /**
     * Persists an Overseer experiment result and returns the updated snapshot.
     *
     * @param experimentResult Deterministic experiment outcome to store.
     */
    suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState

    /**
     * Persists an Overseer dead-end record and returns the updated snapshot.
     */
    suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState

    /**
     * Returns the append-only audit trail for Overseer kernel mutations.
     */
    suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry>

    /**
     * Returns the lifecycle history for a specific Overseer experiment plan.
     *
     * @param experimentPlanId Experiment plan identifier.
     */
    suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry>

    /**
     * Returns the history of dead-end records for a specific hypothesis.
     *
     * @param hypothesisId Hypothesis identifier.
     */
    suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry>

    /**
     * Returns the archived mirror of the Overseer audit trail.
     */
    suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord>
}

/**
 * Summary of meta-archive entries for a completed run.
 *
 * @param total Total number of archived entries across all types.
 * @param byType Count of entries per archive type (e.g., "WINNING_KB", "DEAD_ENDS", "GRADING_SHEET").
 */
@Serializable
data class MetaArchiveSummary(
    val total: Int,
    val byType: Map<String, Int>
)

/**
 * A single meta-archive entry exposed to the UI and IPC layer.
 *
 * @param id Entry ID.
 * @param runId Run identifier.
 * @param entryType Canonical archive type.
 * @param groupId Owning group, if any.
 * @param label Human-readable label.
 * @param contentJson JSON payload stored in the archive.
 * @param runObjective Objective text of the run.
 * @param finalScore Final score, if this entry is group-scored.
 * @param isFromWinner Whether the entry came from the winner.
 * @param generation Generation captured in the archive.
 * @param chunkIndex Chunk index for large entries.
 * @param totalChunks Total chunk count for large entries.
 * @param archivedAtMs Archive timestamp.
 */
@Serializable
data class MetaArchiveEntryRecord(
    val id: String,
    val runId: String,
    val entryType: String,
    val groupId: String?,
    val label: String,
    val contentJson: String,
    val runObjective: String,
    val finalScore: Double?,
    val isFromWinner: Boolean,
    val generation: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val archivedAtMs: Long
)

/**
 * Snapshot of the current EvoMolior engine state.
 */
@Serializable
data class EngineState(
    val status: EngineStatus,
    val activeRunId: String?,
    val currentRound: Int?,
    val groupSummaries: List<GroupSummary>
)

@Serializable
enum class EngineStatus { IDLE, GENESIS, RUNNING, PAUSED, EVALUATING, COMPLETED, RECOVERED }

/**
 * High-level summary of a single group's current state.
 */
@Serializable
data class GroupSummary(
    val groupId: String,
    val profileType: String,
    val activeAgents: Int,
    val remainingBudget: Double,
    val currentRoundScore: Double?
)
