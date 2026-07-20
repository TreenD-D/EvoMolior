@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class PublicationContractsTest {
    @Test
    fun `final candidate commits exact output contract and admitted sources`() {
        val candidate = FinalCandidateRecord(
            candidateId = "candidate-1",
            runId = "run-1",
            groupId = "group-1",
            round = 2,
            outputContractHash = "output-contract-hash",
            sourceAuthorities = listOf(
                ContributionAuthorityRef(
                    ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                    "capture-1",
                    "capture-hash"
                )
            ),
            sealedAt = Instant.fromEpochMilliseconds(500)
        ).withComputedHash()

        assertTrue(candidate.hasValidHash())
        assertFalse(candidate.copy(outputContractHash = "tampered").hasValidHash())
    }

    @Test
    fun `publication policy accepts only exact ordered hash linked transitions`() {
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

        assertTrue(first.hasValidHash())
        assertTrue(PublicationStatePolicy.allows(null, first))
        assertTrue(PublicationStatePolicy.allows(first, second))
        assertFalse(
            PublicationStatePolicy.allows(
                first,
                second.copy(
                    state = PublicationState.OUTPUT_STAGED,
                    eventHash = ""
                ).withComputedHash()
            )
        )
        assertFalse(
            PublicationStatePolicy.allows(
                first,
                second.copy(previousEventHash = "wrong", eventHash = "").withComputedHash()
            )
        )
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
