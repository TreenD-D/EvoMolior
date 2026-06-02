package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.SharedFlow

/**
 * The main service interface exposed by the EvoKammer engine to the Desktop UI.
 *
 * Implementations run in the engine process. The Desktop UI interacts with this interface
 * either directly (in-process) or via the Ktor WebSocket IPC bridge.
 *
 * All methods are suspend functions — the engine is fully coroutine-based.
 */
interface EvoKammerEngineService {

    /** Observable stream of all engine events (real-time). */
    val events: SharedFlow<EvoKammerEvent>

    /**
     * Starts a new civilisation run with the provided [config].
     *
     * If a run is already active, throws [IllegalStateException].
     *
     * @return The run ID (same as [RunConfig.runId]).
     */
    suspend fun startRun(config: RunConfig): String

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
     * @return ZIP bytes, or null if the bundle is not yet available.
     */
    suspend fun getResultsBundle(runId: String): ByteArray?
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
 * Snapshot of the current EvoKammer engine state.
 */
@Serializable
data class EngineState(
    val status: EngineStatus,
    val activeRunId: String?,
    val currentRound: Int?,
    val groupSummaries: List<GroupSummary>
)

@Serializable
enum class EngineStatus { IDLE, GENESIS, RUNNING, PAUSED, EVALUATING, COMPLETED }

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
