@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasHashing
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

/** Strict recovery-only publication states; these never imply accepted publication. */
@Serializable
enum class RecoveryPublicationState {
    WORK_CYCLE_CLOSED_WITHOUT_ACCEPTANCE,
    RECOVERY_SOURCE_SET_SEALED,
    PROVISIONAL_PACKAGE_ASSEMBLED,
    PROVISIONAL_PACKAGE_VALIDATED,
    RECOVERY_COST_AND_EXIT_RECONCILED,
    RECOVERY_MANIFEST_HASHED,
    ARCHIVED_RECOVERED;

    /** Returns the only legal next recovery state, when one exists. */
    fun next(): RecoveryPublicationState? = entries.getOrNull(ordinal + 1)
}

/** One immutable, hash-linked transition in the recovery publication state machine. */
@Serializable
data class RecoveryPublicationStateEvent(
    val eventId: String,
    val runId: String,
    val state: RecoveryPublicationState,
    val sequence: Int,
    val previousEventHash: String?,
    val authorityReferenceIds: Set<String>,
    val occurredAt: Instant,
    val eventHash: String = "",
) {
    init {
        require(eventId.isNotBlank() && runId.isNotBlank()) {
            "Recovery publication event identity fields must not be blank"
        }
        require(sequence >= 0 && authorityReferenceIds.isNotEmpty()) {
            "Recovery publication event sequence and authority are invalid"
        }
        require(authorityReferenceIds.none(String::isBlank)) {
            "Recovery publication authority references must not be blank"
        }
        require(
            (sequence == 0 &&
                state == RecoveryPublicationState.WORK_CYCLE_CLOSED_WITHOUT_ACCEPTANCE &&
                previousEventHash == null) ||
                (sequence > 0 && previousEventHash?.isNotBlank() == true)
        ) { "Recovery publication transition sequence and previous hash are inconsistent" }
    }

    /** Returns this event with its deterministic self-hash attached. */
    fun withComputedHash(): RecoveryPublicationStateEvent =
        copy(eventHash = publicationContractHash(serializer(), this, "eventHash"))

    /** Returns whether this event commits its exact transition and authorities. */
    fun hasValidHash(): Boolean = MessageDigest.isEqual(
        eventHash.encodeToByteArray(),
        publicationContractHash(serializer(), this, "eventHash").encodeToByteArray(),
    )
}

/** Validates exact adjacency and hash linking for recovery publication. */
object RecoveryPublicationStatePolicy {
    /** Returns whether [next] is the first recovery event or exact successor of [previous]. */
    fun allows(
        previous: RecoveryPublicationStateEvent?,
        next: RecoveryPublicationStateEvent,
    ): Boolean {
        if (previous == null) {
            return next.state ==
                RecoveryPublicationState.WORK_CYCLE_CLOSED_WITHOUT_ACCEPTANCE &&
                next.sequence == 0 &&
                next.previousEventHash == null
        }
        return previous.runId == next.runId &&
            previous.state.next() == next.state &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash
    }
}

/** Strict pre-runtime diagnostic states, separate from accepted and recovery publication. */
@Serializable
enum class StartupDiagnosticState {
    ADMISSION_OR_COUNCIL_FAILURE,
    REJECTION_AUTHORITY_FROZEN,
    STARTUP_DIAGNOSTIC_ASSEMBLED,
    DIAGNOSTIC_MANIFEST_HASHED,
    ARCHIVED_REJECTED_PRE_RUNTIME;

    /** Returns the only legal next startup-diagnostic state, when one exists. */
    fun next(): StartupDiagnosticState? = entries.getOrNull(ordinal + 1)
}

/** One immutable, hash-linked transition in the startup diagnostic state machine. */
@Serializable
data class StartupDiagnosticStateEvent(
    val eventId: String,
    val runId: String,
    val state: StartupDiagnosticState,
    val sequence: Int,
    val previousEventHash: String?,
    val authorityReferenceIds: Set<String>,
    val occurredAt: Instant,
    val eventHash: String = "",
) {
    init {
        require(eventId.isNotBlank() && runId.isNotBlank()) {
            "Startup diagnostic event identity fields must not be blank"
        }
        require(sequence >= 0 && authorityReferenceIds.isNotEmpty()) {
            "Startup diagnostic event sequence and authority are invalid"
        }
        require(authorityReferenceIds.none(String::isBlank)) {
            "Startup diagnostic authority references must not be blank"
        }
        require(
            (sequence == 0 &&
                state == StartupDiagnosticState.ADMISSION_OR_COUNCIL_FAILURE &&
                previousEventHash == null) ||
                (sequence > 0 && previousEventHash?.isNotBlank() == true)
        ) { "Startup diagnostic transition sequence and previous hash are inconsistent" }
    }

    /** Returns this event with its deterministic self-hash attached. */
    fun withComputedHash(): StartupDiagnosticStateEvent =
        copy(eventHash = publicationContractHash(serializer(), this, "eventHash"))

    /** Returns whether this event commits its exact transition and authorities. */
    fun hasValidHash(): Boolean = MessageDigest.isEqual(
        eventHash.encodeToByteArray(),
        publicationContractHash(serializer(), this, "eventHash").encodeToByteArray(),
    )
}

/** Validates exact adjacency and hash linking for startup diagnostic publication. */
object StartupDiagnosticStatePolicy {
    /** Returns whether [next] is the first diagnostic event or exact successor of [previous]. */
    fun allows(
        previous: StartupDiagnosticStateEvent?,
        next: StartupDiagnosticStateEvent,
    ): Boolean {
        if (previous == null) {
            return next.state == StartupDiagnosticState.ADMISSION_OR_COUNCIL_FAILURE &&
                next.sequence == 0 &&
                next.previousEventHash == null
        }
        return previous.runId == next.runId &&
            previous.state.next() == next.state &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash
    }
}

private val PUBLICATION_CONTRACT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun <T> publicationContractHash(
    serializer: KSerializer<T>,
    value: T,
    excludedField: String,
): String {
    val element = PUBLICATION_CONTRACT_JSON.encodeToJsonElement(serializer, value)
    val canonical = canonicalPublicationJson(
        JsonObject(element.jsonObject.filterKeys { it != excludedField })
    )
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun canonicalPublicationJson(
    element: kotlinx.serialization.json.JsonElement,
): String = when (element) {
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            PUBLICATION_CONTRACT_JSON.encodeToString(
                kotlinx.serialization.json.JsonPrimitive.serializer(),
                kotlinx.serialization.json.JsonPrimitive(key),
            ) + ":" + canonicalPublicationJson(value)
        }
    is kotlinx.serialization.json.JsonArray -> element
        .map(::canonicalPublicationJson)
        .sorted()
        .joinToString(prefix = "[", postfix = "]")
    is kotlinx.serialization.json.JsonPrimitive -> element.toString()
}
