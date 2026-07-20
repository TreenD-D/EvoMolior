@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CostLedgerContractsTest {
    @Test
    fun `unknown provider pricing remains distinct from zero`() {
        val entry = finalization(
            providerCost = MonetaryAmount(MonetaryAmountStatus.UNKNOWN)
        ).withComputedHash()

        assertNull(entry.knownTotalUsd())
        assertTrue(entry.hasValidHash())
        assertEquals(
            0.0,
            finalization(
                providerCost = MonetaryAmount(MonetaryAmountStatus.KNOWN, 0.0)
            ).knownTotalUsd()
        )
    }

    @Test
    fun `reservation finalization and release enforce separate shapes`() {
        assertFailsWith<IllegalArgumentException> {
            finalization(
                providerCost = MonetaryAmount(MonetaryAmountStatus.KNOWN, 0.1)
            ).copy(reservedMaximumUsd = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CostLedgerEntry(
                entryId = "release-1",
                reservationId = "reservation-1",
                runId = "run-1",
                groupId = null,
                agentId = null,
                subjectType = CostSubjectType.LLM_PROVIDER_CALL,
                subjectId = "call-1",
                kind = CostLedgerEntryKind.RELEASED,
                wallTimeMillis = 1,
                occurredAt = Instant.fromEpochMilliseconds(1)
            )
        }
    }

    private fun finalization(providerCost: MonetaryAmount) = CostLedgerEntry(
        entryId = "finalization-1",
        reservationId = "reservation-1",
        runId = "run-1",
        groupId = null,
        agentId = "agent-1",
        subjectType = CostSubjectType.LLM_PROVIDER_CALL,
        subjectId = "call-1",
        kind = CostLedgerEntryKind.FINALIZED,
        modelProviderId = "provider-1",
        modelId = "model-1",
        inputTokens = 10,
        outputTokens = 5,
        providerCost = providerCost,
        wallTimeMillis = 100,
        occurredAt = Instant.fromEpochMilliseconds(2)
    )
}
