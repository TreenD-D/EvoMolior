@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.publication

import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.PublicationStatePolicy
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.RecoveryPublicationStatePolicy
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticStatePolicy
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.PublicationOutcomeEventEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Append-only persistence authority for accepted, recovery, and startup publication outcomes. */
class PublicationOutcomeRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.completionPersistenceDao()

    /** Appends one exact accepted-publication [event]. */
    suspend fun appendAccepted(event: PublicationStateEvent): PublicationStateEvent {
        require(event.hasValidHash()) {
            "Accepted publication event hash does not match its exact content"
        }
        acceptedEvent(event.eventId)?.let { existing ->
            require(existing == event) { "Publication outcome event identity is immutable" }
            return existing
        }
        val previous = acceptedHistory(event.runId).lastOrNull()
        require(PublicationStatePolicy.allows(previous, event)) {
            "Illegal accepted publication outcome transition"
        }
        val entity = event.toEntity(
            outcomeClass = RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
            state = event.state.name,
            serializer = PublicationStateEvent.serializer(),
            occurredAtMs = event.occurredAt.toEpochMilliseconds()
        )
        return dao.appendPublicationOutcome(entity, previous?.eventHash)
            .toContract(PublicationStateEvent.serializer())
            .also { require(it == event) { "Publication outcome event identity is immutable" } }
    }

    /** Appends one exact provisional-recovery publication [event]. */
    suspend fun appendRecovery(
        event: RecoveryPublicationStateEvent
    ): RecoveryPublicationStateEvent {
        require(event.hasValidHash()) {
            "Recovery publication event hash does not match its exact content"
        }
        recoveryEvent(event.eventId)?.let { existing ->
            require(existing == event) { "Publication outcome event identity is immutable" }
            return existing
        }
        val previous = recoveryHistory(event.runId).lastOrNull()
        require(RecoveryPublicationStatePolicy.allows(previous, event)) {
            "Illegal recovery publication outcome transition"
        }
        val entity = event.toEntity(
            outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
            state = event.state.name,
            serializer = RecoveryPublicationStateEvent.serializer(),
            occurredAtMs = event.occurredAt.toEpochMilliseconds()
        )
        return dao.appendPublicationOutcome(entity, previous?.eventHash)
            .toContract(RecoveryPublicationStateEvent.serializer())
            .also { require(it == event) { "Publication outcome event identity is immutable" } }
    }

    /** Appends one exact startup-diagnostic publication [event]. */
    suspend fun appendStartup(
        event: StartupDiagnosticStateEvent
    ): StartupDiagnosticStateEvent {
        require(event.hasValidHash()) {
            "Startup publication event hash does not match its exact content"
        }
        startupEvent(event.eventId)?.let { existing ->
            require(existing == event) { "Publication outcome event identity is immutable" }
            return existing
        }
        val previous = startupHistory(event.runId).lastOrNull()
        require(StartupDiagnosticStatePolicy.allows(previous, event)) {
            "Illegal startup publication outcome transition"
        }
        val entity = event.toEntity(
            outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            state = event.state.name,
            serializer = StartupDiagnosticStateEvent.serializer(),
            occurredAtMs = event.occurredAt.toEpochMilliseconds()
        )
        return dao.appendPublicationOutcome(entity, previous?.eventHash)
            .toContract(StartupDiagnosticStateEvent.serializer())
            .also { require(it == event) { "Publication outcome event identity is immutable" } }
    }

    /** Returns one exact accepted-publication event by [eventId]. */
    suspend fun acceptedEvent(eventId: String): PublicationStateEvent? =
        event(eventId, RunOutcomeClass.ACCEPTED_DECISION_PACKAGE, PublicationStateEvent.serializer())

    /** Returns one exact recovery-publication event by [eventId]. */
    suspend fun recoveryEvent(eventId: String): RecoveryPublicationStateEvent? =
        event(
            eventId,
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
            RecoveryPublicationStateEvent.serializer()
        )

    /** Returns one exact startup-publication event by [eventId]. */
    suspend fun startupEvent(eventId: String): StartupDiagnosticStateEvent? =
        event(
            eventId,
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            StartupDiagnosticStateEvent.serializer()
        )

    /** Returns exact accepted-publication history for [runId]. */
    suspend fun acceptedHistory(runId: String): List<PublicationStateEvent> =
        history(
            runId,
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
            PublicationStateEvent.serializer()
        )

    /** Returns exact provisional-recovery publication history for [runId]. */
    suspend fun recoveryHistory(runId: String): List<RecoveryPublicationStateEvent> =
        history(
            runId,
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
            RecoveryPublicationStateEvent.serializer()
        )

    /** Returns exact startup-diagnostic publication history for [runId]. */
    suspend fun startupHistory(runId: String): List<StartupDiagnosticStateEvent> =
        history(
            runId,
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            StartupDiagnosticStateEvent.serializer()
        )

    private suspend fun <T> event(
        eventId: String,
        outcomeClass: RunOutcomeClass,
        serializer: KSerializer<T>
    ): T? = dao.publicationOutcome(eventId)?.let { entity ->
        require(entity.outcomeClass == outcomeClass.name) {
            "Publication event identity belongs to a different outcome class"
        }
        entity.toContract(serializer)
    }

    private suspend fun <T> history(
        runId: String,
        outcomeClass: RunOutcomeClass,
        serializer: KSerializer<T>
    ): List<T> = dao.publicationOutcomes(runId, outcomeClass.name)
        .map { it.toContract(serializer) }

    private fun <T> T.toEntity(
        outcomeClass: RunOutcomeClass,
        state: String,
        serializer: KSerializer<T>,
        occurredAtMs: Long
    ): PublicationOutcomeEventEntity {
        val identity = when (this) {
            is PublicationStateEvent -> PublicationIdentity(
                eventId, runId, sequence, previousEventHash, eventHash
            )
            is RecoveryPublicationStateEvent -> PublicationIdentity(
                eventId, runId, sequence, previousEventHash, eventHash
            )
            is StartupDiagnosticStateEvent -> PublicationIdentity(
                eventId, runId, sequence, previousEventHash, eventHash
            )
            else -> error("Unsupported publication outcome contract")
        }
        return PublicationOutcomeEventEntity(
            eventId = identity.eventId,
            runId = identity.runId,
            outcomeClass = outcomeClass.name,
            state = state,
            sequence = identity.sequence,
            previousEventHash = identity.previousEventHash,
            eventHash = identity.eventHash,
            occurredAtMs = occurredAtMs,
            contentJson = json.encodeToString(serializer, this)
        )
    }

    private fun <T> PublicationOutcomeEventEntity.toContract(serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, contentJson).also { value ->
            val persisted = when (value) {
                is PublicationStateEvent -> PersistedPublicationMetadata(
                    identity = PublicationIdentity(
                        value.eventId,
                        value.runId,
                        value.sequence,
                        value.previousEventHash,
                        value.eventHash
                    ),
                    outcomeClass = RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
                    state = value.state.name,
                    occurredAtMs = value.occurredAt.toEpochMilliseconds(),
                    validHash = value.hasValidHash()
                )
                is RecoveryPublicationStateEvent -> PersistedPublicationMetadata(
                    identity = PublicationIdentity(
                        value.eventId,
                        value.runId,
                        value.sequence,
                        value.previousEventHash,
                        value.eventHash
                    ),
                    outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                    state = value.state.name,
                    occurredAtMs = value.occurredAt.toEpochMilliseconds(),
                    validHash = value.hasValidHash()
                )
                is StartupDiagnosticStateEvent -> PersistedPublicationMetadata(
                    identity = PublicationIdentity(
                        value.eventId,
                        value.runId,
                        value.sequence,
                        value.previousEventHash,
                        value.eventHash
                    ),
                    outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
                    state = value.state.name,
                    occurredAtMs = value.occurredAt.toEpochMilliseconds(),
                    validHash = value.hasValidHash()
                )
                else -> error("Unsupported persisted publication outcome contract")
            }
            require(
                persisted.validHash &&
                    persisted.identity.eventId == eventId &&
                    persisted.identity.runId == runId &&
                    persisted.identity.sequence == sequence &&
                    persisted.identity.previousEventHash == previousEventHash &&
                    persisted.identity.eventHash == eventHash &&
                    persisted.outcomeClass.name == outcomeClass &&
                    persisted.state == state &&
                    persisted.occurredAtMs == occurredAtMs
            ) { "Persisted publication outcome metadata does not match its exact content" }
        }

    private data class PublicationIdentity(
        val eventId: String,
        val runId: String,
        val sequence: Int,
        val previousEventHash: String?,
        val eventHash: String
    )

    private data class PersistedPublicationMetadata(
        val identity: PublicationIdentity,
        val outcomeClass: RunOutcomeClass,
        val state: String,
        val occurredAtMs: Long,
        val validHash: Boolean
    )
}
