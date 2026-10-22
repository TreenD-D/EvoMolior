package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Exact immutable persistence row for one typed tool-repair diagnostic. */
@Entity(
    tableName = "tool_repair_diagnostics",
    indices = [
        Index(value = ["runId", "groupId", "opportunityId", "visibleFromRound"]),
        Index(value = ["diagnosticHash"], unique = true)
    ]
)
data class ToolRepairDiagnosticEntity(
    @PrimaryKey
    val diagnosticId: String,
    val runId: String,
    val groupId: String,
    val nodeId: String,
    val opportunityId: String,
    val candidateHash: String,
    val source: String,
    val disclosureClass: String,
    val code: String,
    val createdRound: Int,
    val visibleFromRound: Int,
    val diagnosticHash: String,
    val contentJson: String
)

/** Exact immutable persistence row for one bounded tool-repair attempt. */
@Entity(
    tableName = "tool_repair_attempts",
    indices = [
        Index(value = ["opportunityId", "round", "attemptId"]),
        Index(value = ["candidateHash"]),
        Index(value = ["attemptHash"], unique = true)
    ]
)
data class ToolRepairAttemptEntity(
    @PrimaryKey
    val attemptId: String,
    val opportunityId: String,
    val candidateHash: String,
    val authorAgentId: String,
    val round: Int,
    val preflightResult: String,
    val criticResult: String?,
    val promotionResult: String,
    val attemptHash: String,
    val contentJson: String
)

/** Exact immutable persistence row for one receiver deliverable blueprint. */
@Entity(
    tableName = "deliverable_blueprints",
    indices = [
        Index(value = ["blueprintHash"], unique = true),
        Index(value = ["receiverContractHash"])
    ]
)
data class DeliverableBlueprintEntity(
    @PrimaryKey
    val blueprintId: String,
    val schemaVersion: String,
    val receiverContractHash: String,
    val primaryFormat: String,
    val blueprintHash: String,
    val contentJson: String
)

/** Exact immutable persistence row for one sealed round-boundary deliverable snapshot. */
@Entity(
    tableName = "interim_deliverable_snapshots",
    indices = [
        Index(value = ["runId", "groupId", "round"], unique = true),
        Index(value = ["blueprintHash"]),
        Index(value = ["snapshotHash"], unique = true)
    ]
)
data class InterimDeliverableSnapshotEntity(
    @PrimaryKey
    val snapshotHash: String,
    val schemaVersion: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val blueprintHash: String,
    val contentJson: String
)

/** Exact immutable persistence row for one receiver decision package. */
@Entity(
    tableName = "decision_packages",
    indices = [
        Index(value = ["runId", "outcomeClass", "generatedAtMs"]),
        Index(value = ["blueprintHash"]),
        Index(value = ["packageHash"], unique = true)
    ]
)
data class DecisionPackageEntity(
    @PrimaryKey
    val packageId: String,
    val schemaVersion: String,
    val runId: String,
    val outcomeClass: String,
    val receiverContractHash: String,
    val blueprintHash: String,
    val packageHash: String,
    val generatedAtMs: Long,
    val contentJson: String
)

/**
 * Exact immutable accepted, recovery, or startup publication transition.
 *
 * The `outcomeClass` discriminator keeps the three core state machines separate without
 * inventing a lossy common contract.
 */
@Entity(
    tableName = "publication_outcome_events",
    indices = [
        Index(value = ["runId", "outcomeClass", "sequence"], unique = true),
        Index(value = ["runId", "outcomeClass", "state"], unique = true),
        Index(value = ["eventHash"], unique = true)
    ]
)
data class PublicationOutcomeEventEntity(
    @PrimaryKey
    val eventId: String,
    val runId: String,
    val outcomeClass: String,
    val state: String,
    val sequence: Int,
    val previousEventHash: String?,
    val eventHash: String,
    val occurredAtMs: Long,
    val contentJson: String
)

/** Exact immutable persistence row for one hash-linked round phase ledger event. */
@Entity(
    tableName = "round_phase_events",
    indices = [
        Index(value = ["runId", "round", "sequence"], unique = true),
        Index(value = ["runId", "round", "phase"], unique = true),
        Index(value = ["eventHash"], unique = true)
    ]
)
data class RoundPhaseEventEntity(
    @PrimaryKey
    val eventHash: String,
    val runId: String,
    val round: Int,
    val phase: String,
    val sequence: Int,
    val previousEventHash: String?,
    val occurredAtMs: Long,
    val contentJson: String
)
