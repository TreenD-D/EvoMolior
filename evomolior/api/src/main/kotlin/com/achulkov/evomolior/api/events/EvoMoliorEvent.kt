package com.achulkov.evomolior.api.events

import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable

/**
 * All observable events emitted by the EvoMolior engine to the Desktop UI.
 *
 * Events are serialised over the Ktor WebSocket IPC channel.
 * The Desktop UI subscribes to these events to update the control panel in real-time.
 */
@Serializable
sealed class EvoMoliorEvent {

    // ── Run lifecycle ──────────────────────────────────────────────────────────

    /** The run has been initialised and is waiting for Genesis Council to complete. */
    @Serializable
    data class RunStarted(
        val runId: String,
        val objective: String,
        val scoringDimensionIds: List<String> = emptyList()
    ) : EvoMoliorEvent()

    /** A round has started. */
    @Serializable
    data class RoundStarted(val runId: String, val roundIndex: Int) : EvoMoliorEvent()

    /** Grading is complete; contains [GradingResult]. */
    @Serializable
    data class RoundCompleted(val runId: String, val result: GradingResult) : EvoMoliorEvent()

    /** The run has ended (all rounds complete or early termination). */
    @Serializable
    data class RunCompleted(val runId: String, val winnerId: String?) : EvoMoliorEvent()

    /** The run was aborted due to an error or user request. */
    @Serializable
    data class RunAborted(val runId: String, val reason: String) : EvoMoliorEvent()

    // ── Agent events ───────────────────────────────────────────────────────────

    /** A new agent has been spawned. */
    @Serializable
    data class AgentSpawned(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val role: Role
    ) : EvoMoliorEvent()

    /** An agent has died (budget exhausted or Death Note issued). */
    @Serializable
    data class AgentDied(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val reason: String
    ) : EvoMoliorEvent()

    /** Updated useful ordinary-exit accounting after a mortal worker terminates. */
    @Serializable
    data class UsefulExitRateUpdated(
        val runId: String,
        val groupId: String,
        val usefulExitCount: Int,
        val ordinaryExitCount: Int,
        val usefulExitRate: Double
    ) : EvoMoliorEvent()

    /** An agent emitted a tool call or message (for real-time log streaming). */
    @Serializable
    data class AgentMessage(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val role: Role,
        val content: String,
        val isToolCall: Boolean = false
    ) : EvoMoliorEvent()

    // ── Budget / resource events ───────────────────────────────────────────────

    /** A group's total remaining budget changed. */
    @Serializable
    data class GroupBudgetChanged(
        val runId: String,
        val groupId: String,
        val remainingBudget: Double
    ) : EvoMoliorEvent()

    /** A resource node was claimed by a group. */
    @Serializable
    data class ResourceNodeClaimed(
        val runId: String,
        val nodeId: String,
        val groupId: String,
        val round: Int? = null,
        val previousOwnerGroupId: String? = null,
        val evidencePacketId: String? = null,
        val evidencePacketHash: String? = null,
        val scoreBundleId: String? = null,
        val scoreBundleHash: String? = null,
        val captureDecisionId: String? = null,
        val captureDecisionHash: String? = null,
        val captureDecisionType: String? = null,
        val gradeVerdict: String? = null,
        val atlasVersion: Int? = null,
        val atlasHash: String? = null,
        val nodePayloadHash: String? = null,
        val winningScore: Double? = null,
        val priorWinningScore: Double? = null,
        val yieldScheduleHash: String? = null
    ) : EvoMoliorEvent()

    // ── Leak / disclosure events ───────────────────────────────────────────────

    /** A leak packet was sent between groups. */
    @Serializable
    data class LeakSent(
        val runId: String,
        val fromGroupId: String,
        val toGroupId: String,
        val documentKey: String,
        val wasCorrupted: Boolean
    ) : EvoMoliorEvent()

    // ── Constitutional events ──────────────────────────────────────────────────

    /** An agent attempted a constitutionally-prohibited action. */
    @Serializable
    data class ConstitutionalViolation(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val lawId: String,
        val description: String
    ) : EvoMoliorEvent()

    // ── Substrate events ───────────────────────────────────────────────────────

    /** A staged submission was promoted to the canonical substrate. */
    @Serializable
    data class StagingPromoted(
        val runId: String,
        val groupId: String,
        val submissionId: String,
        val submissionType: String,
        val agentId: String
    ) : EvoMoliorEvent()

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
    ) : EvoMoliorEvent()

    /** A new tool was registered in the Tool Registry. */
    @Serializable
    data class ToolRegistered(
        val runId: String,
        val groupId: String,
        val toolName: String,
        val version: String,
        val authorAgentId: String
    ) : EvoMoliorEvent()

    /** A dead end was recorded in the Dead Ends Registry. */
    @Serializable
    data class DeadEndRecorded(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val approach: String,
        val reason: String
    ) : EvoMoliorEvent()

    /** A Legacy Protocol was extracted from a high-fitness agent. */
    @Serializable
    data class LegacyProtocolExtracted(
        val runId: String,
        val groupId: String,
        val role: Role,
        val fromGeneration: Int,
        val expiresAtGeneration: Int
    ) : EvoMoliorEvent()

    /** A group's disclosure stage advanced after a successful check-in. */
    @Serializable
    data class StageAdvanced(
        val runId: String,
        val groupId: String,
        val fromStage: Int,
        val toStage: Int,
        val checkinScore: Double
    ) : EvoMoliorEvent()

    // ── Inspector / audit events ───────────────────────────────────────────────

    /** Inspector detected an anomaly and flagged it. */
    @Serializable
    data class InspectorFlag(
        val runId: String,
        val groupId: String,
        val severity: String,
        val description: String
    ) : EvoMoliorEvent()

    // ── Genesis Council events ─────────────────────────────────────────────────

    /**
     * Genesis Council completed successfully and produced a StartingField configuration.
     *
     * @param runId Active run identifier.
     * @param groupCount Number of groups designed by the council.
     * @param modelAssignment Role → model ID assignments (e.g. "Researcher" → "google/gemini-2.5-flash").
     * @param rolePromptSummary Short summary of role prompts (first 120 chars each, truncated).
     * @param stageCount Number of disclosure stages designed.
     * @param scoringDimensionIds IDs of the scoring dimensions chosen.
     * @param kgSchemaNodeCount Number of node types in the designed KG schema.
     * @param primordialKbCount Number of seed KB documents seeded by the council.
     * @param primordialFactCount Number of seed KG facts seeded by the council.
     * @param usedDefault True if the default StartingField was used (council timed out or failed).
     * @param consistencyReport Human-readable consistency report from CouncilSynthesis (B-3).
     *   Empty string if the council was not invoked or did not produce one.
     */
    @Serializable
    data class GenesisCouncilCompleted(
        val runId: String,
        val groupCount: Int,
        val modelAssignment: Map<String, String>,
        val rolePromptSummary: Map<String, String>,
        val stageCount: Int,
        val scoringDimensionIds: List<String>,
        val kgSchemaNodeCount: Int,
        val primordialKbCount: Int,
        val primordialFactCount: Int,
        val usedDefault: Boolean,
        val consistencyReport: String = ""
    ) : EvoMoliorEvent()

    /** A deterministic objective-based expert bundle was selected for the run. */
    @Serializable
    data class ObjectiveExpertBundleSelected(
        val runId: String,
        val bundleName: String,
        val objective: String
    ) : EvoMoliorEvent()

    /** A run-node ancestry graph was prepared for a continuation launch. */
    @Serializable
    data class RunGraphPrepared(
        val runId: String,
        val sourceRunId: String,
        val edgeCount: Int,
        val ancestryDepth: Int,
        val routedContextCount: Int
    ) : EvoMoliorEvent()

    /** A continuation launch carried a non-empty graph ancestry payload. */
    @Serializable
    data class RunGraphExpanded(
        val runId: String,
        val sourceRunId: String,
        val ancestryDepth: Int,
        val edgeCount: Int
    ) : EvoMoliorEvent()

    // ── Grader Group events ────────────────────────────────────────────────────

    /**
     * A Grader Group started evaluating all competing groups for a round.
     *
     * @param runId Active run identifier.
     * @param graderGroupId The grader group performing the evaluation.
     * @param roundIndex The round being evaluated.
     * @param competingGroupIds IDs of all competing groups being evaluated.
     */
    @Serializable
    data class GraderRoundStarted(
        val runId: String,
        val graderGroupId: String,
        val roundIndex: Int,
        val competingGroupIds: List<String>
    ) : EvoMoliorEvent()

    /**
     * A Grader Group completed evaluating all competing groups for a round.
     *
     * @param runId Active run identifier.
     * @param graderGroupId The grader group that completed evaluation.
     * @param roundIndex The round that was evaluated.
     * @param scoresPerGroup Map of groupId → list of dimension score summaries (dimensionId=score).
     * @param totalScoresCollected Total number of DimensionScore entries collected.
     */
    @Serializable
    data class GraderRoundCompleted(
        val runId: String,
        val graderGroupId: String,
        val roundIndex: Int,
        val scoresPerGroup: Map<String, String>,
        val totalScoresCollected: Int
    ) : EvoMoliorEvent()

    // ── Engine progress / debug events ────────────────────────────────────────

    /**
     * Generic engine progress heartbeat — emitted periodically during long-running
     * operations so the UI never looks frozen.
     *
     * @param runId Active run identifier.
     * @param phase Short label for the current phase (e.g., "round_wait", "spawning", "critic").
     * @param message Human-readable progress description.
     * @param progressFraction Optional 0.0–1.0 fraction if deterministic progress is known.
     */
    @Serializable
    data class EngineProgress(
        val runId: String,
        val phase: String,
        val message: String,
        val progressFraction: Double? = null
    ) : EvoMoliorEvent()

    /**
     * An attempt to spawn an agent VM failed.
     *
     * @param runId Active run identifier.
     * @param groupId Group this agent belongs to.
     * @param agentId Attempted agent ID.
     * @param role Intended role of the agent.
     * @param error Error description.
     */
    @Serializable
    data class AgentSpawnFailed(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val role: Role,
        val error: String
    ) : EvoMoliorEvent()

    /**
     * CriticPipeline finished reviewing all pending submissions for a group.
     *
     * @param runId Active run identifier.
     * @param groupId Group whose submissions were reviewed.
     * @param roundIndex Round in which the pipeline ran.
     * @param promoted Number of submissions promoted to the canonical substrate.
     * @param rejected Number of submissions rejected.
     */
    @Serializable
    data class CriticPipelineCompleted(
        val runId: String,
        val groupId: String,
        val roundIndex: Int,
        val promoted: Int,
        val rejected: Int
    ) : EvoMoliorEvent()

    /**
     * A Watchdog KG anomaly signal was raised.
     *
     * @param runId Active run identifier.
     * @param groupId Affected group.
     * @param signalType Type of anomaly (e.g., "kg_density_low", "kg_inconsistency_high", "budget_critical").
     * @param details Human-readable details.
     */
    @Serializable
    data class WatchdogAlert(
        val runId: String,
        val groupId: String,
        val signalType: String,
        val details: String
    ) : EvoMoliorEvent()

    /**
     * Result of the Shuru availability check at engine startup.
     *
     * @param available Whether Shuru is installed and functional.
     * @param version Shuru version string, or null if unavailable.
     * @param message Human-readable status message.
     */
    @Serializable
    data class ShuruStatus(
        val available: Boolean,
        val version: String?,
        val message: String
    ) : EvoMoliorEvent()
}
