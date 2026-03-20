package com.achulkov.evokammer.api.events

import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * All observable events emitted by the EvoKammer engine to the Desktop UI.
 *
 * Events are serialised over the Ktor WebSocket IPC channel.
 * The Desktop UI subscribes to these events to update the control panel in real-time.
 */
@Serializable
sealed class EvoKammerEvent {

    // ── Run lifecycle ──────────────────────────────────────────────────────────

    /** The run has been initialised and is waiting for Genesis Council to complete. */
    @Serializable
    data class RunStarted(val runId: String, val objective: String) : EvoKammerEvent()

    /** A round has started. */
    @Serializable
    data class RoundStarted(val runId: String, val roundIndex: Int) : EvoKammerEvent()

    /** Grading is complete; contains [GradingResult]. */
    @Serializable
    data class RoundCompleted(val runId: String, val result: GradingResult) : EvoKammerEvent()

    /** The run has ended (all rounds complete or early termination). */
    @Serializable
    data class RunCompleted(val runId: String, val winnerId: String?) : EvoKammerEvent()

    /** The run was aborted due to an error or user request. */
    @Serializable
    data class RunAborted(val runId: String, val reason: String) : EvoKammerEvent()

    // ── Agent events ───────────────────────────────────────────────────────────

    /** A new agent has been spawned. */
    @Serializable
    data class AgentSpawned(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val role: Role
    ) : EvoKammerEvent()

    /** An agent has died (budget exhausted or Death Note issued). */
    @Serializable
    data class AgentDied(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val reason: String
    ) : EvoKammerEvent()

    /** An agent emitted a tool call or message (for real-time log streaming). */
    @Serializable
    data class AgentMessage(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val role: Role,
        val content: String,
        val isToolCall: Boolean = false
    ) : EvoKammerEvent()

    // ── Budget / resource events ───────────────────────────────────────────────

    /** A group's total remaining budget changed. */
    @Serializable
    data class GroupBudgetChanged(
        val runId: String,
        val groupId: String,
        val remainingBudget: Double
    ) : EvoKammerEvent()

    /** A resource node was claimed by a group. */
    @Serializable
    data class ResourceNodeClaimed(
        val runId: String,
        val nodeId: String,
        val groupId: String
    ) : EvoKammerEvent()

    // ── Leak / disclosure events ───────────────────────────────────────────────

    /** A leak packet was sent between groups. */
    @Serializable
    data class LeakSent(
        val runId: String,
        val fromGroupId: String,
        val toGroupId: String,
        val documentKey: String,
        val wasCorrupted: Boolean
    ) : EvoKammerEvent()

    // ── Constitutional events ──────────────────────────────────────────────────

    /** An agent attempted a constitutionally-prohibited action. */
    @Serializable
    data class ConstitutionalViolation(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val lawId: String,
        val description: String
    ) : EvoKammerEvent()

    // ── Substrate events ───────────────────────────────────────────────────────

    /** A staged submission was promoted to the canonical substrate. */
    @Serializable
    data class StagingPromoted(
        val runId: String,
        val groupId: String,
        val submissionId: String,
        val submissionType: String,
        val agentId: String
    ) : EvoKammerEvent()

    /** A KG fact was emitted and promoted to the canonical KG. */
    @Serializable
    data class KGFactEmitted(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val subject: String,
        val relation: String,
        val `object`: String,
        val confidence: Double
    ) : EvoKammerEvent()

    /** A new tool was registered in the Tool Registry. */
    @Serializable
    data class ToolRegistered(
        val runId: String,
        val groupId: String,
        val toolName: String,
        val version: String,
        val authorAgentId: String
    ) : EvoKammerEvent()

    /** A dead end was recorded in the Dead Ends Registry. */
    @Serializable
    data class DeadEndRecorded(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val approach: String,
        val reason: String
    ) : EvoKammerEvent()

    /** A Legacy Protocol was extracted from a high-fitness agent. */
    @Serializable
    data class LegacyProtocolExtracted(
        val runId: String,
        val groupId: String,
        val role: Role,
        val fromGeneration: Int,
        val expiresAtGeneration: Int
    ) : EvoKammerEvent()

    /** A group's disclosure stage advanced after a successful check-in. */
    @Serializable
    data class StageAdvanced(
        val runId: String,
        val groupId: String,
        val fromStage: Int,
        val toStage: Int,
        val checkinScore: Double
    ) : EvoKammerEvent()

    // ── Inspector / audit events ───────────────────────────────────────────────

    /** Inspector detected an anomaly and flagged it. */
    @Serializable
    data class InspectorFlag(
        val runId: String,
        val groupId: String,
        val severity: String,
        val description: String
    ) : EvoKammerEvent()
}
