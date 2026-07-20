@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Exact ordered states required before a run may be archived. */
@Serializable
enum class PublicationState {
    FINAL_CANDIDATES_SEALED,
    WINNER_OR_FINAL_POLICY_RESOLVED,
    FINAL_WRITER_RUNNING,
    OUTPUT_STAGED,
    OUTPUT_VALIDATED,
    COST_FINALIZED,
    DEATH_NOTE_FINALIZED,
    ALL_NON_DAEMON_AGENTS_TERMINATED,
    METRICS_EXPORTED,
    MANIFEST_HASHED,
    ARCHIVED;

    /** Returns the only legal next publication state, when one exists. */
    fun next(): PublicationState? = entries.getOrNull(ordinal + 1)
}

/** Immutable candidate assembled from exact captured contribution authorities. */
@Serializable
data class FinalCandidateRecord(
    val candidateId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val outputContractHash: String,
    val sourceAuthorities: List<ContributionAuthorityRef>,
    val sealedAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(candidateId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank()) {
            "Final-candidate identity fields must not be blank"
        }
        require(round >= 0 && outputContractHash.isNotBlank()) {
            "Final-candidate round and output contract are invalid"
        }
        require(sourceAuthorities.isNotEmpty() && sourceAuthorities.distinct() == sourceAuthorities) {
            "Final-candidate sources must be non-empty and unique"
        }
        require(sourceAuthorities.all { source ->
            ContributionRelation.REFERENCED_BY_FINAL_DELIVERABLE.accepts(
                source.kind,
                ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE
            ) && source.authorityHash != null
        }) { "Final-candidate sources must be exact admitted final-output authorities" }
    }

    /** Returns this candidate with its deterministic self-hash attached. */
    fun withComputedHash(): FinalCandidateRecord =
        copy(recordHash = AtlasHashing.hashFinalCandidate(this))

    /** Verifies that the candidate hash commits its exact sources and output contract. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashFinalCandidate(this))
}

/**
 * One append-only transition in the publication barrier.
 *
 * [authorityReferenceIds] names exact output, cost, death-note, lifecycle, or manifest authority
 * required for the entered state. The record hash excludes only itself.
 */
@Serializable
data class PublicationStateEvent(
    val eventId: String,
    val runId: String,
    val state: PublicationState,
    val sequence: Int,
    val previousEventHash: String?,
    val authorityReferenceIds: Set<String>,
    val occurredAt: Instant,
    val eventHash: String = ""
) {
    init {
        require(eventId.isNotBlank() && runId.isNotBlank()) {
            "Publication event identity fields must not be blank"
        }
        require(sequence >= 0) { "Publication sequence must not be negative" }
        require(authorityReferenceIds.isNotEmpty() && authorityReferenceIds.none(String::isBlank)) {
            "Publication transitions require exact non-blank authority references"
        }
        require(
            (sequence == 0 && state == PublicationState.FINAL_CANDIDATES_SEALED &&
                previousEventHash == null) ||
                (sequence > 0 && previousEventHash?.isNotBlank() == true)
        ) {
            "Publication transition sequence and previous hash are inconsistent"
        }
    }

    /** Returns this event with its deterministic self-hash attached. */
    fun withComputedHash(): PublicationStateEvent =
        copy(eventHash = AtlasHashing.hashPublicationStateEvent(this))

    /** Verifies that the event hash commits the exact transition. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            eventHash,
            AtlasHashing.hashPublicationStateEvent(this)
        )
}

/** Validates strict adjacency in the publication state machine. */
object PublicationStatePolicy {
    /** Returns whether [next] is the first event or exact successor of [previous]. */
    fun allows(
        previous: PublicationStateEvent?,
        next: PublicationStateEvent
    ): Boolean {
        if (previous == null) {
            return next.state == PublicationState.FINAL_CANDIDATES_SEALED &&
                next.sequence == 0 &&
                next.previousEventHash == null
        }
        return previous.runId == next.runId &&
            previous.state.next() == next.state &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash
    }
}
