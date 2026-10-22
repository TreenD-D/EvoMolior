@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.roundledger

import com.achulkov.evomolior.core.contract.RoundPhaseLedgerEventV1
import com.achulkov.evomolior.core.contract.RoundPhaseLedgerPolicy
import com.achulkov.evomolior.core.contract.RoundPhaseV1
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.RoundPhaseEventEntity
import kotlinx.serialization.json.Json

/** Append-only persistence authority for one run round's bounded phase ledger. */
class RoundPhaseLedgerRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.completionPersistenceDao()

    /**
     * Appends [event] only when it is the exact next phase in its run round.
     *
     * Replaying an exact event is idempotent. Reusing any ledger authority with changed content,
     * skipping a phase, or attempting a reordered phase fails before persistence.
     */
    suspend fun append(event: RoundPhaseLedgerEventV1): RoundPhaseLedgerEventV1 {
        require(event.hasValidHash()) {
            "Round phase event hash does not match its exact content"
        }
        eventByHash(event.eventHash)?.let { existing ->
            require(existing == event) { "Round phase event hash is immutable" }
            return existing
        }

        val history = history(event.runId, event.round)
        requireValidPrefix(history)
        require(RoundPhaseLedgerPolicy.allows(history.lastOrNull(), event)) {
            "Round phase event is not the exact next phase"
        }

        return dao.appendRoundPhaseEvent(
            event.toEntity(),
            history.lastOrNull()?.eventHash
        ).toContract().also { persisted ->
            require(persisted == event) { "Round phase event authority is immutable" }
        }
    }

    /**
     * Archives [event]'s round only with the exact phases 1 through 17 present.
     *
     * @param event Terminal round archival event.
     * @return The exact persisted terminal event.
     */
    suspend fun archive(event: RoundPhaseLedgerEventV1): RoundPhaseLedgerEventV1 {
        require(event.phase == RoundPhaseV1.ROUND_LEDGER_ARCHIVED) {
            "Only the terminal round phase can archive a ledger"
        }
        val persisted = append(event)
        require(RoundPhaseLedgerPolicy.isArchived(history(event.runId, event.round))) {
            "A round ledger may archive only after the exact phase 1 through 17 prefix"
        }
        return persisted
    }

    /** Returns a complete ordered immutable history for [runId] and [round]. */
    suspend fun history(runId: String, round: Int): List<RoundPhaseLedgerEventV1> =
        dao.roundPhaseHistory(runId, round).map { it.toContract() }

    /** Returns one exact immutable event by [eventHash]. */
    suspend fun eventByHash(eventHash: String): RoundPhaseLedgerEventV1? =
        dao.roundPhaseEvent(eventHash)?.toContract()

    private fun requireValidPrefix(history: List<RoundPhaseLedgerEventV1>) {
        require(history.size <= RoundPhaseV1.entries.size) {
            "Round phase ledger exceeds its closed phase count"
        }
        require(history.all { it.hasValidHash() }) {
            "Round phase ledger contains an invalid event hash"
        }
        require(
            history.isEmpty() ||
                (RoundPhaseLedgerPolicy.allows(null, history.first()) &&
                    history.zipWithNext().all { (previous, next) ->
                        RoundPhaseLedgerPolicy.allows(previous, next)
                    })
        ) {
            "Round phase ledger history is not an exact ordered prefix"
        }
    }

    private fun RoundPhaseLedgerEventV1.toEntity(): RoundPhaseEventEntity =
        RoundPhaseEventEntity(
            eventHash = eventHash,
            runId = runId,
            round = round,
            phase = phase.name,
            sequence = sequence,
            previousEventHash = previousEventHash,
            occurredAtMs = occurredAt.toEpochMilliseconds(),
            contentJson = json.encodeToString(RoundPhaseLedgerEventV1.serializer(), this)
        )

    private fun RoundPhaseEventEntity.toContract(): RoundPhaseLedgerEventV1 =
        json.decodeFromString(RoundPhaseLedgerEventV1.serializer(), contentJson).also { event ->
            require(
                event.hasValidHash() &&
                    event.eventHash == eventHash &&
                    event.runId == runId &&
                    event.round == round &&
                    event.phase.name == phase &&
                    event.sequence == sequence &&
                    event.previousEventHash == previousEventHash &&
                    event.occurredAt.toEpochMilliseconds() == occurredAtMs
            ) {
                "Persisted round phase metadata does not match its exact content"
            }
        }
}
