package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunAutoCompletionAuthorityTest {
    private val policy = RunAutoCompletionPolicy(consecutiveAcceptedRounds = 3)

    @Test
    fun `three exact accepted rounds for one winner complete the run`() {
        val first = RunAutoCompletionAuthority.advance(policy, null, 0, accepted(0, "group-a"))
        val second = RunAutoCompletionAuthority.advance(
            policy,
            first.candidateWinnerId,
            first.acceptedRoundStreak,
            accepted(1, "group-a")
        )
        val third = RunAutoCompletionAuthority.advance(
            policy,
            second.candidateWinnerId,
            second.acceptedRoundStreak,
            accepted(2, "group-a")
        )

        assertEquals(1, first.acceptedRoundStreak)
        assertEquals(2, second.acceptedRoundStreak)
        assertFalse(second.complete)
        assertEquals(3, third.acceptedRoundStreak)
        assertTrue(third.complete)
    }

    @Test
    fun `winner change and any failed predicate reset stability`() {
        val changedWinner = RunAutoCompletionAuthority.advance(
            policy,
            previousWinnerId = "group-a",
            previousStreak = 2,
            evidence = accepted(2, "group-b")
        )
        val rejected = RunAutoCompletionAuthority.advance(
            policy,
            previousWinnerId = changedWinner.candidateWinnerId,
            previousStreak = changedWinner.acceptedRoundStreak,
            evidence = accepted(3, "group-b").copy(
                finalOutputValidated = false,
                rejectionReasons = setOf("final_output_contract_not_satisfied")
            )
        )

        assertEquals("group-b", changedWinner.candidateWinnerId)
        assertEquals(1, changedWinner.acceptedRoundStreak)
        assertEquals(null, rejected.candidateWinnerId)
        assertEquals(0, rejected.acceptedRoundStreak)
        assertFalse(rejected.complete)
    }

    @Test
    fun `every exact acceptance predicate fails closed and clears a prior streak`() {
        val rejectedEvidence = listOf(
            accepted(2, "group-a").copy(winnerId = null),
            accepted(2, "group-a").copy(exactGraderAuthorityValid = false),
            accepted(2, "group-a").copy(mandatoryTestsPassed = false),
            accepted(2, "group-a").copy(finalOutputValidated = false),
            accepted(2, "group-a").copy(rejectionReasons = setOf("requirement_failure"))
        )

        rejectedEvidence.forEach { evidence ->
            val progress = RunAutoCompletionAuthority.advance(
                policy = policy,
                previousWinnerId = "group-a",
                previousStreak = 2,
                evidence = evidence
            )

            assertEquals(null, progress.candidateWinnerId)
            assertEquals(0, progress.acceptedRoundStreak)
            assertFalse(progress.complete)
        }
    }

    @Test
    fun `completion metadata records actual early round count and authoritative reason`() {
        val early = resolveRunCompletionDecision(
            finalRoundIndex = 2,
            autoCompletionReached = true
        )
        val bounded = resolveRunCompletionDecision(
            finalRoundIndex = 5,
            autoCompletionReached = false
        )

        assertEquals(3, early.completedRoundCount)
        assertEquals(RunCompletionReason.GRADER_ACCEPTED_STABILITY, early.reason)
        assertEquals(6, bounded.completedRoundCount)
        assertEquals(RunCompletionReason.ROUND_LIMIT_REACHED, bounded.reason)
    }

    private fun accepted(round: Int, winnerId: String) = RunAutoCompletionRoundEvidence(
        roundIndex = round,
        winnerId = winnerId,
        exactGraderAuthorityValid = true,
        mandatoryTestsPassed = true,
        finalOutputValidated = true
    )
}
