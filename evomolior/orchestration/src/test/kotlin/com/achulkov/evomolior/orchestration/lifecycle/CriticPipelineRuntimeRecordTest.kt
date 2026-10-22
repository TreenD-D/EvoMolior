package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runtime-generated staging records are settled without spending a model review.
 */
class CriticPipelineRuntimeRecordTest {
    @Test
    fun `source fetch and fallback records are settled without a model review`() = runTest {
        val dataRoot = createTempDirectory("critic-runtime-records").toFile()
        val runId = "run-runtime-records"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        fun staged(id: String, signature: String, trust: Double?, type: String = "PLAN_UPDATE") =
            StagingSubmissionEntity(
                id = id,
                agentId = "agent-builder-1",
                role = "Builder",
                type = type,
                content = "content for $id",
                budgetSignature = signature,
                initialTrustScore = trust,
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        db.stagingDao().insert(staged("source-fetch-1", "SOURCE_FETCH:agent-builder-1:1", 0.35))
        db.stagingDao().insert(staged("fallback-1", "SUBSTRATE_CONTRACT_FALLBACK:agent-builder-1:2", 0.1))
        db.stagingDao().insert(staged("no-action-1", "NO_ACTION_FALLBACK:agent-builder-1:3", 0.1))
        db.stagingDao().insert(staged("authored-plan", "sig-authored", null))

        val reviewed = mutableListOf<String>()
        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            toolsMountHostPath = dataRoot.absolutePath,
            criticReviewRunner = { submission, _, _, criticId ->
                reviewed += submission.id
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.7
                )
            }
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(listOf("authored-plan"), reviewed, "only authored work reaches the model reviewer")
        assertEquals(2, summary.promoted)
        assertEquals(2, summary.rejected)

        val fetch = assertNotNull(db.stagingDao().getById("source-fetch-1"))
        assertEquals("promoted", fetch.status)
        assertEquals("system", fetch.criticAgentId)
        assertEquals(0.35, fetch.initialTrustScore)
        listOf("fallback-1", "no-action-1").forEach { id ->
            val fallback = assertNotNull(db.stagingDao().getById(id))
            assertEquals("rejected", fallback.status)
            assertTrue(fallback.rejectionReason.orEmpty().startsWith("runtime_fallback_record"))
            val decision = assertNotNull(db.contributionGraphDao().criticDecisionForSubmission(runId, groupId, id))
            assertEquals(CriticOutcome.REJECT.name, decision.outcome)
        }
    }

    @Test
    fun `full local storage stops the review pass instead of failing every submission`() = runTest {
        val dataRoot = createTempDirectory("critic-storage-exhausted").toFile()
        val runId = "run-storage-exhausted"
        val groupId = "grader-group_0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        (1..5).forEach { index ->
            db.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "rubric-$index",
                    agentId = "criteria-graderresearcher-1",
                    role = "GraderResearcher",
                    type = "PLAN_UPDATE",
                    content = "rubric $index",
                    budgetSignature = "rubric-$index",
                    generation = 1,
                    groupId = groupId,
                    runId = runId,
                    submittedAtMs = index.toLong()
                )
            )
        }
        var reviewAttempts = 0
        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            toolsMountHostPath = dataRoot.absolutePath,
            criticReviewRunner = { _, _, _, _ ->
                reviewAttempts += 1
                throw IllegalStateException(
                    "Error executing node (name: nodeCallLLM)",
                    java.nio.file.FileSystemException("/tmp/evomolior-codex-1.txt: No space left on device")
                )
            }
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, reviewAttempts)
        assertEquals(0, summary.promoted + summary.rejected)
        assertEquals(5, db.stagingDao().countPending(runId, groupId))
    }
}
