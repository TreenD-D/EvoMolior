package com.achulkov.evokammer.core.contract

import kotlinx.serialization.Serializable

/**
 * A signal emitted by the Global Watchdog to components of the EvoKammer system.
 *
 * Watchdog signals are the primary mechanism for the deterministic rule engine to trigger
 * component behaviour changes. They are never initiated by agent LLMs.
 *
 * @param signalId Unique signal identifier (UUID).
 * @param type The type of signal (determines which component responds).
 * @param targetGroupIds List of group IDs this signal targets; empty means all groups.
 * @param metadata Additional type-specific parameters serialised as JSON.
 * @param timestampMs Unix epoch milliseconds when the signal was emitted.
 * @param runId Run this signal belongs to.
 */
@Serializable
data class WatchdogSignal(
    val signalId: String,
    val type: WatchdogSignalType,
    val targetGroupIds: List<String> = emptyList(),
    val metadata: String = "{}",
    val timestampMs: Long,
    val runId: String
)

/**
 * All signal types the Watchdog can emit.
 */
@Serializable
enum class WatchdogSignalType(val description: String) {

    /** Stasis detected in one or more groups; spawn a Meta-Planner. */
    SPAWN_META_PLANNER("Stasis detected — spawn Meta-Planner"),

    /** High-severity anomaly detected; spawn an Auditor. */
    SPAWN_AUDITOR("High-severity anomaly — spawn Auditor"),

    /** Stasis detected; increase Leak Oracle delivery frequency for target groups. */
    INCREASE_LEAK_RATE("Increase intelligence leak frequency"),

    /** Deliver a specific intelligence leak to target groups immediately. */
    DELIVER_LEAK("Deliver intelligence leak"),

    /** Open the check-in submission gate for the current milestone. */
    OPEN_CHECKIN_GATE("Open check-in submission gate"),

    /** Close the check-in gate and trigger scoring. */
    CLOSE_CHECKIN_GATE("Close check-in gate — trigger scoring"),

    /**
     * Pause new agent spawning across target groups.
     * Active agents continue to their natural end.
     */
    PAUSE_SPAWNING("Pause new agent spawning"),

    /** Resume new agent spawning after a pause. */
    RESUME_SPAWNING("Resume agent spawning"),

    /** Final submission gate is now open; all groups must submit their final deliverable. */
    TRIGGER_FINAL_SUBMISSION("Trigger final submission"),

    /** Deliver a dispatch from the relay to a target group's inbox. */
    DELIVER_DISPATCH("Deliver formal dispatch"),

    /** Notify target group that a node was claimed or recaptured. */
    NODE_CLAIM_UPDATE("Resource node claim/recapture update"),

    /** Notify the ProgressiveDisclosureEngine to advance a group's stage. */
    ADVANCE_STAGE("Advance group disclosure stage");
}
