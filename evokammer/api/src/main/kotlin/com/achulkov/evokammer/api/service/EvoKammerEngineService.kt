package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

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
}

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
