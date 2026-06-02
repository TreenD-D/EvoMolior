package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.core.contract.CriticReview
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the KG arbitration wrapper builds a specialised conflict-focused prompt.
 */
class KgCriticArbitrationAgentTest {

    @Test
    fun `reviewConflict forwards KG arbitration prompt and conflict context`() = runTest {
        val prompts = mutableListOf<String>()
        val agent = KgCriticArbitrationAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5",
            deterministicReviewRunner = { submission, prompt, disputeContext, criticId ->
                prompts += prompt
                assertEquals("kg-submission-1", submission.id)
                assertTrue(disputeContext?.contains("higher-trust fact") == true)
                assertTrue(criticId.startsWith("critic-"))
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.81
                )
            }
        )

        val review = agent.reviewConflict(
            submission = sampleSubmission(),
            disputeContext = """
                Proposed fact contradicts a higher-trust fact with the same subject/relation.
                conflictingFactIds=[fact-123]
            """.trimIndent(),
            criticId = "critic-kg-1"
        )

        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("KG-Critic arbitration"))
        assertTrue(prompts.single().contains("Conflict context"))
        assertTrue(prompts.single().contains("SECOND REVIEW (KG arbitration)"))
        assertEquals(CriticOutcome.PROMOTE, review.outcome)
    }

    private fun sampleSubmission(): StagingSubmissionEntity = StagingSubmissionEntity(
        id = "kg-submission-1",
        agentId = "agent-kg-1",
        role = "Researcher",
        type = "KG_FACT",
        content = """
            SUBJECT: Algorithm A
            RELATION: IMPLEMENTS
            OBJECT: Concept B
            CONFIDENCE: 0.7
            DOMAIN: general
        """.trimIndent(),
        budgetSignature = "sig",
        wipTaskRef = "node-kg-1",
        speculative = false,
        outlier = false,
        generation = 1,
        groupId = "group-0",
        runId = "run-0",
        submittedAtMs = 1_000L
    )
}
