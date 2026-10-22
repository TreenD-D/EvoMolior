@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.roundledger

import com.achulkov.evomolior.core.contract.RoundPhaseLedgerEventV1
import com.achulkov.evomolior.core.contract.RoundPhaseV1
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class RoundPhaseLedgerRepositoryTest {
    @Test
    fun `round phase ledger appends only the exact next phase and replays exactly`() = runTest {
        val database = database()
        try {
            val repository = RoundPhaseLedgerRepository(database)
            val first = event(RoundPhaseV1.ATLAS_MATCHED_FROZEN, null)
            val second = event(RoundPhaseV1.DUE_YIELDS_AND_DIAGNOSTICS_APPLIED, first.eventHash)

            assertEquals(first, repository.append(first))
            assertEquals(first, repository.append(first))
            assertFailsWith<IllegalArgumentException> {
                repository.append(event(RoundPhaseV1.GRADER_FRONTIER_FROZEN, first.eventHash))
            }
            assertFailsWith<IllegalArgumentException> {
                repository.append(
                    first.copy(authorityReferenceIds = listOf("changed"), eventHash = "")
                        .withComputedHash()
                )
            }
            assertEquals(second, repository.append(second))
            assertEquals(listOf(first, second), repository.history("run-round-ledger", 4))
        } finally {
            database.close()
        }
    }

    @Test
    fun `terminal round archive requires exact complete phase prefix`() = runTest {
        val database = database()
        try {
            val repository = RoundPhaseLedgerRepository(database)
            val events = RoundPhaseV1.entries.fold(emptyList<RoundPhaseLedgerEventV1>()) { history, phase ->
                history + event(phase, history.lastOrNull()?.eventHash)
            }

            assertFailsWith<IllegalArgumentException> {
                repository.archive(events.last())
            }
            events.dropLast(1).forEach { repository.append(it) }
            assertEquals(events.last(), repository.archive(events.last()))
            assertEquals(events, repository.history("run-round-ledger", 4))
            assertEquals(events.last(), repository.archive(events.last()))
        } finally {
            database.close()
        }
    }

    private fun database() = SubstrateDatabaseFactory(
        createTempDirectory("round-phase-ledger-").toFile()
    ).get("run-round-ledger", "group")

    private fun event(
        phase: RoundPhaseV1,
        previous: String?
    ): RoundPhaseLedgerEventV1 = RoundPhaseLedgerEventV1(
        runId = "run-round-ledger",
        round = 4,
        phase = phase,
        sequence = phase.ordinal + 1,
        previousEventHash = previous,
        authorityReferenceIds = listOf("authority-${phase.ordinal}"),
        occurredAt = Instant.parse("2026-07-24T12:00:00Z")
    ).withComputedHash()
}
