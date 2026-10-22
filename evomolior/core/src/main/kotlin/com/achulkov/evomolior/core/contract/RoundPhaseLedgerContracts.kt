@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** The closed, ordered phases that make a completed round auditable. */
@Serializable
enum class RoundPhaseV1 {
    ATLAS_MATCHED_FROZEN,
    DUE_YIELDS_AND_DIAGNOSTICS_APPLIED,
    PORTFOLIOS_AND_RUNTIME_CELLS_COMPILED,
    GRADER_FRONTIER_FROZEN,
    WORKERS_SPAWNED,
    CRITERIA_SPAWNED,
    PUBLIC_PREFLIGHT_COMPLETED,
    LINEARIZABLE_EXECUTION_SETTLED,
    CRITIC_AND_SEMANTIC_REVIEW_COMPLETED,
    EVIDENCE_AND_TOOL_RECORDS_SEALED,
    CRITERIA_FROZEN,
    EVIDENCE_REVEALED,
    GRADER_OUTCOMES_PERSISTED,
    MUTATION_CAPTURE_YIELD_DISCLOSURE_AND_OUTLIER_RESOLVED,
    FUTURE_DIAGNOSTICS_PUBLISHED,
    INTERIM_SNAPSHOTS_COMPILED,
    ROUND_LEDGER_ARCHIVED;

    /** Returns the sole legal successor, or null after archival. */
    fun next(): RoundPhaseV1? = entries.getOrNull(ordinal + 1)
}

/**
 * One immutable, hash-linked event in a run round's bounded phase ledger.
 *
 * @param runId Stable run authority.
 * @param round Non-negative round number.
 * @param phase Closed phase entered by this event.
 * @param sequence One-based phase sequence within [round].
 * @param previousEventHash Exact hash of the preceding phase event, when present.
 * @param authorityReferenceIds Sorted, unique, non-blank authority references for [phase].
 * @param occurredAt Instant at which the phase authority was sealed.
 * @param eventHash Deterministic self-hash that excludes only itself.
 */
@Serializable
data class RoundPhaseLedgerEventV1(
    val runId: String,
    val round: Int,
    val phase: RoundPhaseV1,
    val sequence: Int,
    val previousEventHash: String?,
    val authorityReferenceIds: List<String>,
    val occurredAt: Instant,
    val eventHash: String = ""
) {
    init {
        require(runId.isNotBlank() && round >= 0) {
            "Round ledger run identity is invalid"
        }
        require(sequence == phase.ordinal + 1) {
            "Round ledger sequence must match its closed phase ordinal"
        }
        require(
            authorityReferenceIds.isNotEmpty() &&
                authorityReferenceIds.all { it.isNotBlank() } &&
                authorityReferenceIds == authorityReferenceIds.sorted() &&
                authorityReferenceIds.distinct().size == authorityReferenceIds.size
        ) {
            "Round ledger authority references must be sorted, unique, and non-blank"
        }
        require(
            (sequence == 1 && phase == RoundPhaseV1.ATLAS_MATCHED_FROZEN &&
                previousEventHash == null) ||
                (sequence > 1 && previousEventHash?.isNotBlank() == true)
        ) {
            "Round ledger sequence and previous hash are inconsistent"
        }
    }

    /** Returns this event with its deterministic self-hash attached. */
    fun withComputedHash(): RoundPhaseLedgerEventV1 =
        copy(eventHash = RoundPhaseLedgerHashing.hash(this))

    /** Returns whether [eventHash] commits this event's exact content. */
    fun hasValidHash(): Boolean =
        MessageDigest.isEqual(
            eventHash.encodeToByteArray(),
            RoundPhaseLedgerHashing.hash(this).encodeToByteArray()
        )
}

/** Strict adjacency and completeness rules for [RoundPhaseLedgerEventV1] histories. */
object RoundPhaseLedgerPolicy {
    /** Returns whether [next] is the first event or exact successor of [previous]. */
    fun allows(
        previous: RoundPhaseLedgerEventV1?,
        next: RoundPhaseLedgerEventV1
    ): Boolean {
        if (previous == null) {
            return next.phase == RoundPhaseV1.ATLAS_MATCHED_FROZEN &&
                next.sequence == 1 &&
                next.previousEventHash == null
        }
        return previous.runId == next.runId &&
            previous.round == next.round &&
            previous.phase.next() == next.phase &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash
    }

    /** Returns whether [history] is the exact complete phase prefix ending in archival. */
    fun isArchived(history: List<RoundPhaseLedgerEventV1>): Boolean {
        val first = history.firstOrNull() ?: return false
        return history.size == RoundPhaseV1.entries.size &&
            history.all { it.hasValidHash() } &&
            history.zipWithNext().all { (previous, next) -> allows(previous, next) } &&
            allows(null, first) &&
            history.last().phase == RoundPhaseV1.ROUND_LEDGER_ARCHIVED
    }
}

/** Deterministic SHA-256 hashing for round phase ledger events. */
object RoundPhaseLedgerHashing {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    /** Hashes [event] excluding its self-referential event hash. */
    fun hash(event: RoundPhaseLedgerEventV1): String =
        sha256(
            json.encodeToString(
                CanonicalRoundPhaseLedgerEventV1(
                    runId = event.runId,
                    round = event.round,
                    phase = event.phase,
                    sequence = event.sequence,
                    previousEventHash = event.previousEventHash,
                    authorityReferenceIds = event.authorityReferenceIds,
                    occurredAt = event.occurredAt
                )
            )
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

@Serializable
private data class CanonicalRoundPhaseLedgerEventV1(
    val runId: String,
    val round: Int,
    val phase: RoundPhaseV1,
    val sequence: Int,
    val previousEventHash: String?,
    val authorityReferenceIds: List<String>,
    val occurredAt: Instant
)
