@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.cost

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmount
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class CostLedgerRepositoryTest {
    @Test
    fun `cumulative cap reservation is atomic and independent from action credits`() = runTest {
        val runId = "run-cost-cap"
        val database = SubstrateDatabaseFactory(
            createTempDirectory("cost-ledger-cap-").toFile()
        ).get(runId, "group-1")
        try {
            val repository = CostLedgerRepository(
                database,
                CostLedgerPolicy(cumulativeCapUsd = 1.0)
            )
            val secondBoundaryRepository = CostLedgerRepository(
                database,
                CostLedgerPolicy(cumulativeCapUsd = 1.0)
            )
            val results = listOf(
                "a" to repository,
                "b" to secondBoundaryRepository
            ).map { (id, boundaryRepository) ->
                async {
                    runCatching {
                        boundaryRepository.reserve(reservation(runId, id, 0.6))
                    }.isSuccess
                }
            }.awaitAll()

            assertEquals(1, results.count { it })
            assertEquals(0.6, repository.summary(runId).activeReservedMaximumUsd)
            assertFailsWith<IllegalStateException> {
                repository.reserve(reservation(runId, "unbounded", null))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `finalization preserves unknown pricing and cap fails closed`() = runTest {
        val runId = "run-cost-finalize"
        val database = SubstrateDatabaseFactory(
            createTempDirectory("cost-ledger-finalize-").toFile()
        ).get(runId, "group-1")
        try {
            val capped = CostLedgerRepository(
                database,
                CostLedgerPolicy(cumulativeCapUsd = 1.0)
            )
            capped.reserve(reservation(runId, "known", 0.8))
            capped.finalize(finalization(runId, "known", 0.5))
            assertEquals(0.5, capped.summary(runId).knownProviderCostUsd)
            assertEquals(0.0, capped.summary(runId).activeReservedMaximumUsd)

            capped.reserve(reservation(runId, "unknown", 0.4))
            assertFailsWith<IllegalStateException> {
                capped.finalize(finalization(runId, "unknown", null))
            }
            capped.release(release(runId, "unknown"))

            val uncappedRunId = "run-cost-unknown-uncapped"
            val uncappedDatabase = SubstrateDatabaseFactory(
                createTempDirectory("cost-ledger-uncapped-").toFile()
            ).get(uncappedRunId, "group-1")
            try {
                val uncapped = CostLedgerRepository(uncappedDatabase)
                uncapped.reserve(reservation(uncappedRunId, "unknown", 0.4))
                uncapped.finalize(finalization(uncappedRunId, "unknown", null))
                val summary = uncapped.summary(uncappedRunId)
                assertEquals(1, summary.unknownMonetaryEntryCount)
                assertEquals(0.0, summary.knownProviderCostUsd)
            } finally {
                uncappedDatabase.close()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `publication closure atomically requires settlement and blocks future reservations`() =
        runTest {
            val runId = "run-cost-publication-closure"
            val database = SubstrateDatabaseFactory(
                createTempDirectory("cost-ledger-publication-closure-").toFile()
            ).get(runId, "group-1")
            try {
                val repository = CostLedgerRepository(database)
                repository.reserve(reservation(runId, "active", 0.4))

                val activeFailure = assertFailsWith<IllegalStateException> {
                    repository.closeForPublication(runId)
                }
                assertTrue(activeFailure.message.orEmpty().contains("1 active cost reservation"))

                repository.release(release(runId, "active"))
                val closure = repository.closeForPublication(runId)
                assertEquals(CostLedgerEntryKind.PUBLICATION_CLOSED, closure.kind)
                assertEquals(CostSubjectType.PUBLICATION_BARRIER, closure.subjectType)
                assertEquals(closure, repository.closeForPublication(runId))
                assertFailsWith<IllegalStateException> {
                    repository.reserve(reservation(runId, "late", 0.1))
                }
                assertEquals(0L, repository.summary(runId).activeReservationCount)
            } finally {
                database.close()
            }
        }

    private fun reservation(runId: String, id: String, maximum: Double?) = CostLedgerEntry(
        entryId = "cost-reserved-$id",
        reservationId = "reservation-$id",
        runId = runId,
        groupId = AtlasGroupId("group-1"),
        agentId = "agent-$id",
        subjectType = CostSubjectType.LLM_PROVIDER_CALL,
        subjectId = "call-$id",
        kind = CostLedgerEntryKind.RESERVED,
        modelProviderId = "provider",
        modelId = "model",
        reservedMaximumUsd = maximum,
        occurredAt = Instant.fromEpochMilliseconds(1)
    ).withComputedHash()

    private fun finalization(runId: String, id: String, cost: Double?) = CostLedgerEntry(
        entryId = "cost-finalized-$id",
        reservationId = "reservation-$id",
        runId = runId,
        groupId = AtlasGroupId("group-1"),
        agentId = "agent-$id",
        subjectType = CostSubjectType.LLM_PROVIDER_CALL,
        subjectId = "call-$id",
        kind = CostLedgerEntryKind.FINALIZED,
        modelProviderId = "provider",
        modelId = "model",
        inputTokens = 10,
        outputTokens = 5,
        providerCost = cost?.let {
            MonetaryAmount(MonetaryAmountStatus.KNOWN, it)
        } ?: MonetaryAmount(MonetaryAmountStatus.UNKNOWN),
        wallTimeMillis = 100,
        occurredAt = Instant.fromEpochMilliseconds(2)
    ).withComputedHash()

    private fun release(runId: String, id: String) = CostLedgerEntry(
        entryId = "cost-released-$id",
        reservationId = "reservation-$id",
        runId = runId,
        groupId = AtlasGroupId("group-1"),
        agentId = "agent-$id",
        subjectType = CostSubjectType.LLM_PROVIDER_CALL,
        subjectId = "call-$id",
        kind = CostLedgerEntryKind.RELEASED,
        occurredAt = Instant.fromEpochMilliseconds(3)
    ).withComputedHash()
}
