package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a structured event log entry.
 *
 * The event log is append-only — entries are never deleted, only queried.
 * It is the primary data source for the Inspector (anomaly detection) and for
 * post-mortem fitness scoring by the Epigenetic Inheritance Engine.
 */
@Entity(
    tableName = "event_log_entries",
    indices = [
        Index("runId"),
        Index("groupId"),
        Index("agentId"),
        Index("eventType"),
        Index("generation"),
        Index("timestampMs"),
        Index(value = ["runId", "groupId", "generation"])
    ]
)
data class EventLogEntryEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    /** EventType enum name string. */
    val eventType: String,
    val agentId: String? = null,
    val groupId: String? = null,
    val runId: String,
    val generation: Int,
    /** Event-specific JSON payload. Structure depends on eventType. */
    val payloadJson: String = "{}"
)

/**
 * All event types that can be logged. String names are stored in the DB.
 * Add new event types here; they are referenced by string in the DB for forward compatibility.
 */
object EventTypes {
    const val AGENT_SPAWN = "AGENT_SPAWN"
    const val AGENT_DEATH = "AGENT_DEATH"
    const val AGENT_KILLED = "AGENT_KILLED"
    const val AGENT_CONSTITUTIONAL_KILL = "AGENT_CONSTITUTIONAL_KILL"
    const val STAGING_SUBMIT = "STAGING_SUBMIT"
    const val STAGING_PROMOTE = "STAGING_PROMOTE"
    const val STAGING_REJECT = "STAGING_REJECT"
    const val STAGING_DISPUTE = "STAGING_DISPUTE"
    const val TOOL_EXEC = "TOOL_EXEC"
    const val TOOL_INFRASTRUCTURE_METRICS = "TOOL_INFRASTRUCTURE_METRICS"
    const val LLM_CALL = "LLM_CALL"
    const val BUDGET_DEDUCT = "BUDGET_DEDUCT"
    const val BUDGET_FLOOR_REACHED = "BUDGET_FLOOR_REACHED"
    const val WIP_CLAIM = "WIP_CLAIM"
    const val WIP_RELEASE = "WIP_RELEASE"
    const val WIP_COMPLETE = "WIP_COMPLETE"
    const val DISPATCH_SENT = "DISPATCH_SENT"
    const val DISPATCH_DELIVERED = "DISPATCH_DELIVERED"
    const val LEAK_DELIVERED = "LEAK_DELIVERED"
    const val CHECKIN_GATE_OPEN = "CHECKIN_GATE_OPEN"
    const val CHECKIN_GATE_CLOSE = "CHECKIN_GATE_CLOSE"
    const val SCORE_REVEAL = "SCORE_REVEAL"
    const val WATCHDOG_SIGNAL = "WATCHDOG_SIGNAL"
    const val STASIS_DETECTED = "STASIS_DETECTED"
    const val GENERATION_BOUNDARY = "GENERATION_BOUNDARY"
    const val LEGACY_PROTOCOL_EXTRACTED = "LEGACY_PROTOCOL_EXTRACTED"
    const val NODE_CLAIMED = "NODE_CLAIMED"
    const val NODE_RECAPTURED = "NODE_RECAPTURED"
    const val TRUST_DECAY = "TRUST_DECAY"
    const val STAGE_ADVANCED = "STAGE_ADVANCED"
    const val CONSTITUTIONAL_VIOLATION = "CONSTITUTIONAL_VIOLATION"
    const val INSPECTOR_FLAG = "INSPECTOR_FLAG"
    const val META_PLANNER_SPAWN = "META_PLANNER_SPAWN"
    const val FINAL_SUBMISSION = "FINAL_SUBMISSION"
    /** Emitted by EvolutionarySelector after each generation allocation decision. */
    const val SELECTOR_DECISION = "SELECTOR_DECISION"
    /** Emitted by KGConsistencyChecker when a KG_FACT submission violates the schema. */
    const val KG_SCHEMA_VIOLATION = "KG_SCHEMA_VIOLATION"
    /** Emitted when a legacy protocol expires and failure maps are promoted to Dead Ends. */
    const val LEGACY_PROTOCOL_EXPIRED = "LEGACY_PROTOCOL_EXPIRED"
    /** Emitted when generation-boundary KB maintenance archives stale working-set entries. */
    const val KB_ORGANIZED = "KB_ORGANIZED"
    /** Emitted when generation-boundary KB maintenance fails for a group. */
    const val KB_ORGANIZATION_FAILED = "KB_ORGANIZATION_FAILED"
    /** Emitted when a KG_CONSTRAINT submission is promoted and applied to the schema table. */
    const val KG_CONSTRAINT_APPLIED = "KG_CONSTRAINT_APPLIED"
    /** Emitted when a nested inner group has been rolled up into the outer substrate. */
    const val NESTED_GROUP_ROLLUP = "NESTED_GROUP_ROLLUP"
    /** Emitted when an agent sends an intra-group message. */
    const val INTER_AGENT_MESSAGE = "INTER_AGENT_MESSAGE"
}
