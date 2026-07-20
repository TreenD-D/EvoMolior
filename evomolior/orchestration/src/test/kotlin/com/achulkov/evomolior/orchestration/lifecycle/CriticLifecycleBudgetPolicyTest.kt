package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlin.test.Test
import kotlin.test.assertTrue

/** Tests critic lifecycle budget estimation from pending workload. */
class CriticLifecycleBudgetPolicyTest {

    @Test
    fun `estimate is positive for empty pending workload`() {
        val policy = CriticLifecycleBudgetPolicy()

        assertTrue(policy.estimateReviewTokenCount(emptyList()) > 0)
    }

    @Test
    fun `estimate scales with pending workload size and content length`() {
        val policy = CriticLifecycleBudgetPolicy()
        val small = listOf(submission(id = "small", content = "short"))
        val large = small + submission(id = "large", content = "x".repeat(2_000))

        assertTrue(policy.estimateReviewTokenCount(large) > policy.estimateReviewTokenCount(small))
    }

    private fun submission(id: String, content: String): StagingSubmissionEntity =
        StagingSubmissionEntity(
            id = id,
            runId = "run-budget-policy",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            type = "DOC",
            content = content,
            budgetSignature = "sig",
            wipTaskRef = "node-1",
            generation = 1,
            submittedAtMs = 1_000L
        )
}
