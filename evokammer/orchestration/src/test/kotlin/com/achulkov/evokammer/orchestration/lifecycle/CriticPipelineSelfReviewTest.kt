package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the Critic pipeline rejects submissions from an explicitly configured critic identity.
 */
class CriticPipelineSelfReviewTest {

    @Test
    fun `submission from explicit critic identity is rejected before review`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-self-review-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-self-review"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "self-review-doc",
                agentId = "critic-agent-1",
                role = "Researcher",
                type = "DOC",
                content = "This submission should be rejected because it came from the critic identity.",
                budgetSignature = "sig",
                wipTaskRef = "node-1",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticAgentId = "critic-agent-1",
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(0, summary.arbitrated)

        val stored = db.stagingDao().getById("self-review-doc")
        assertNotNull(stored)
        assertEquals("rejected", stored.status)
        assertTrue(stored.rejectionReason?.contains("self_submission_violation") == true)
    }
}
