@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.publication

import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class PublicationStateRepositoryTest {
    @Test
    fun `final candidates are hash verified and immutable`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("publication-candidates-").toFile()
        ).getAtlas("run-1")
        try {
            val repository = PublicationStateRepository(database)
            val candidate = com.achulkov.evomolior.core.contract.FinalCandidateRecord(
                candidateId = "candidate-1",
                runId = "run-1",
                groupId = "group-1",
                round = 1,
                outputContractHash = "output-contract-hash",
                sourceAuthorities = listOf(
                    com.achulkov.evomolior.core.contract.ContributionAuthorityRef(
                        com.achulkov.evomolior.core.contract.ContributionAuthorityKind
                            .NODE_CAPTURE_DECISION,
                        "capture-1",
                        "capture-hash"
                    )
                ),
                sealedAt = Instant.fromEpochMilliseconds(500)
            ).withComputedHash()

            assertEquals(listOf(candidate), repository.sealFinalCandidates(listOf(candidate)))
            assertEquals(listOf(candidate), repository.finalCandidates(candidate.runId))
            assertFailsWith<IllegalArgumentException> {
                repository.sealFinalCandidates(
                    listOf(candidate.copy(outputContractHash = "different").withComputedHash())
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `repository rejects skipped and stale publication transitions`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("publication-state-").toFile()
        ).getAtlas("run-publication")
        try {
            val repository = PublicationStateRepository(database)
            val first = event(
                PublicationState.FINAL_CANDIDATES_SEALED,
                sequence = 0,
                previousHash = null
            )
            val second = event(
                PublicationState.WINNER_OR_FINAL_POLICY_RESOLVED,
                sequence = 1,
                previousHash = first.eventHash
            )

            repository.append(first)
            repository.append(second)

            assertEquals(second, repository.latest("run-publication"))
            assertEquals(listOf(first, second), repository.history("run-publication"))
            assertFailsWith<IllegalArgumentException> {
                repository.append(
                    event(
                        PublicationState.OUTPUT_STAGED,
                        sequence = 2,
                        previousHash = second.eventHash
                    )
                )
            }
        } finally {
            database.close()
        }
    }

    private fun event(
        state: PublicationState,
        sequence: Int,
        previousHash: String?
    ) = PublicationStateEvent(
        eventId = "publication-${state.name}",
        runId = "run-publication",
        state = state,
        sequence = sequence,
        previousEventHash = previousHash,
        authorityReferenceIds = setOf("authority-${state.name}"),
        occurredAt = Instant.parse("2026-07-12T10:00:00Z")
    ).withComputedHash()
}
