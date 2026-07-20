package com.achulkov.evomolior.core.model

import kotlinx.serialization.Serializable

/**
 * The type of content being submitted to the Staging Area.
 *
 * Each type has different Critic evaluation criteria and different promotion targets
 * in the canonical substrate.
 */
@Serializable
enum class SubmissionType(val description: String) {

    /** A text document for the Knowledge Base. */
    DOC("Knowledge Base document"),

    /** A tool implementation for the Tool Registry. */
    TOOL("Tool Registry entry"),

    /** A typed Knowledge Graph fact (subject, relation, object). */
    FACT("KG fact"),

    /** A typed KG soft constraint proposal. */
    KG_CONSTRAINT("KG soft constraint"),

    /**
     * The Death Note written by an agent on termination.
     * Always promoted without Critic review — goes to Dead Ends / Legacy section.
     */
    DEATH_NOTE("Agent Death Note"),

    /** An update to the group's Plan Registry from the Planner or Meta-Planner. */
    PLAN_UPDATE("Plan Registry update"),

    /**
     * A cross-group dispatch sent via the Dispatch Relay.
     * Reviewed by Critic before entering the relay queue.
     */
    DISPATCH("Cross-group formal dispatch"),

    /**
     * A failure telemetry entry from the Scoring Engine, written to Grader Group KBs.
     * Not submitted by agents — written by the ScoringEngine directly.
     */
    FAILURE_TELEMETRY("Scoring failure telemetry");
}
