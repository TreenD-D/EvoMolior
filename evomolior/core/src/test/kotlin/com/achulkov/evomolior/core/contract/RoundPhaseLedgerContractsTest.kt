@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class RoundPhaseLedgerContractsTest {
    @Test
    fun `phase ledger enforces its closed one based hash linked sequence`() {
        val first = event(RoundPhaseV1.ATLAS_MATCHED_FROZEN, previous = null)
        val second = event(RoundPhaseV1.DUE_YIELDS_AND_DIAGNOSTICS_APPLIED, first.eventHash)

        assertTrue(first.hasValidHash())
        assertTrue(second.hasValidHash())
        assertTrue(RoundPhaseLedgerPolicy.allows(null, first))
        assertTrue(RoundPhaseLedgerPolicy.allows(first, second))
        assertFalse(RoundPhaseLedgerPolicy.allows(first, first))
        assertFalse(second.copy(previousEventHash = "wrong").withComputedHash().hasValidHash() &&
            RoundPhaseLedgerPolicy.allows(first, second.copy(previousEventHash = "wrong").withComputedHash()))
    }

    @Test
    fun `phase ledger requires canonical authority references and phase sequence`() {
        assertFailsWith<IllegalArgumentException> {
            RoundPhaseLedgerEventV1(
                runId = "run",
                round = 1,
                phase = RoundPhaseV1.ATLAS_MATCHED_FROZEN,
                sequence = 1,
                previousEventHash = null,
                authorityReferenceIds = listOf("z", "a"),
                occurredAt = Instant.parse("2026-07-24T12:00:00Z")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RoundPhaseLedgerEventV1(
                runId = "run",
                round = 1,
                phase = RoundPhaseV1.EVIDENCE_REVEALED,
                sequence = 1,
                previousEventHash = null,
                authorityReferenceIds = listOf("authority"),
                occurredAt = Instant.parse("2026-07-24T12:00:00Z")
            )
        }
    }

    @Test
    fun `archive requires exactly all seventeen valid phases`() {
        val history = RoundPhaseV1.entries.fold(emptyList<RoundPhaseLedgerEventV1>()) { events, phase ->
            events + event(phase, events.lastOrNull()?.eventHash)
        }

        assertTrue(RoundPhaseLedgerPolicy.isArchived(history))
        assertFalse(RoundPhaseLedgerPolicy.isArchived(history.dropLast(1)))
        assertFalse(RoundPhaseLedgerPolicy.isArchived(history.drop(1)))
    }

    private fun event(
        phase: RoundPhaseV1,
        previous: String?
    ): RoundPhaseLedgerEventV1 = RoundPhaseLedgerEventV1(
        runId = "run",
        round = 3,
        phase = phase,
        sequence = phase.ordinal + 1,
        previousEventHash = previous,
        authorityReferenceIds = listOf("authority-${phase.ordinal}"),
        occurredAt = Instant.parse("2026-07-24T12:00:00Z")
    ).withComputedHash()
}
