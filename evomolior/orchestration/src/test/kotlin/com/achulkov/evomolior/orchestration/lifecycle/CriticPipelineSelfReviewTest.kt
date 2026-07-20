package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.model.GroupProfile
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

    private val promoteReviewRunner: suspend (StagingSubmissionEntity, String, String?, String) -> CriticReview =
        { submission, _, _, criticId ->
            CriticReview(
                submissionId = submission.id,
                outcome = CriticOutcome.PROMOTE,
                criticAgentId = criticId,
                initialTrustScore = 0.8
            )
        }

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
        val decision = db.contributionGraphDao().criticDecisionForSubmission(
            runId,
            groupId,
            "self-review-doc"
        )
        assertNotNull(decision)
        assertEquals(CriticOutcome.REJECT.name, decision.outcome)
        assertEquals(
            listOf(ContributionRelation.REVIEWED_AS.name),
            db.contributionGraphDao().forRun(runId, groupId).map { it.relation }
        )
    }

    @Test
    fun `submission from prior critic lifecycle identity is rejected before review`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-prior-self-review-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-prior-self-review"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "prior-critic-doc",
                agentId = "critic-group-0-round-1",
                role = "Critic",
                type = "DOC",
                content = "A critic's older staged submission must not be promotable by later critic lifecycle ids.",
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
            generation = 2,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticAgentId = "critic-group-0-round-2",
            criticAuthorBlocklist = setOf("critic-group-0-round-1"),
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        val stored = db.stagingDao().getById("prior-critic-doc")
        assertNotNull(stored)
        assertEquals("rejected", stored.status)
        assertTrue(stored.rejectionReason?.contains("self_submission_violation") == true)
    }

    @Test
    fun `lifecycle-bound critic writes death note and spends bounded budget`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-lifecycle-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-lifecycle"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val budgetConfig = BudgetConfig(groupId, GroupProfile.Library)
        val ledger = BudgetLedger("critic-lifecycle-1", 10.0, budgetConfig.floorFor(10.0), budgetConfig)

        val lifecycle = LifecycleBoundCriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            criticAgentId = "critic-lifecycle-1",
            ttlMs = 60_000L,
            budgetLedger = ledger,
            pipelineFactory = { criticId, blocklist ->
                CriticPipeline(
                    db = db,
                    runId = runId,
                    groupId = groupId,
                    generation = 1,
                    openRouterApiKey = "sk-test",
                    criticModel = "anthropic/claude-haiku-4.5",
                    criticAgentId = criticId,
                    criticAuthorBlocklist = blocklist,
                    criticReviewRunner = promoteReviewRunner,
                    toolsMountHostPath = dataRoot.absolutePath
                )
            }
        )

        val result = lifecycle.run()

        assertEquals("completed", result.lifecycleOutcome)
        assertTrue(result.deathNoteWritten)
        assertTrue(result.budgetSnapshot.currentBalance < result.budgetSnapshot.initialBudget)
        val deathNotes = db.stagingDao().getAll(runId, groupId).filter { it.type == "DEATH_NOTE" }
        assertEquals(1, deathNotes.size)
        assertEquals("promoted", deathNotes.single().status)
        assertTrue(deathNotes.single().content.contains("CRITIC_PIPELINE_DEATH_NOTE"))
        val deadEnds = db.knowledgeBaseDao().getDeadEnds(runId, groupId)
        assertEquals(1, deadEnds.size)
        assertTrue(deadEnds.single().content.contains("CRITIC_PIPELINE_DEATH_NOTE"))
    }

    @Test
    fun `lifecycle-bound critic writes promoted death note when budget is already exhausted`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-lifecycle-budget-exhausted-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-lifecycle-budget-exhausted"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val budgetConfig = BudgetConfig(groupId, GroupProfile.Library)
        val ledger = BudgetLedger("critic-lifecycle-budget-exhausted", 1.0, 1.0, budgetConfig)

        val lifecycle = LifecycleBoundCriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            criticAgentId = "critic-lifecycle-budget-exhausted",
            ttlMs = 60_000L,
            budgetLedger = ledger,
            pipelineFactory = { _, _ -> error("review pipeline must not run when budget is exhausted") }
        )

        val result = lifecycle.run()

        assertEquals("budget_exhausted", result.lifecycleOutcome)
        assertTrue(result.deathNoteWritten)
        val deathNotes = db.stagingDao().getAll(runId, groupId).filter { it.type == "DEATH_NOTE" }
        assertEquals(1, deathNotes.size)
        assertEquals("promoted", deathNotes.single().status)
        assertTrue(deathNotes.single().content.contains("outcome: budget_exhausted"))
        val deadEnds = db.knowledgeBaseDao().getDeadEnds(runId, groupId)
        assertEquals(1, deadEnds.size)
        assertTrue(deadEnds.single().content.contains("outcome: budget_exhausted"))
    }

    @Test
    fun `lifecycle-bound critic fails closed on linkage errors`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-lifecycle-linkage-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-lifecycle-linkage"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val budgetConfig = BudgetConfig(groupId, GroupProfile.Library)
        val ledger = BudgetLedger("critic-lifecycle-linkage", 10.0, budgetConfig.floorFor(10.0), budgetConfig)

        val lifecycle = LifecycleBoundCriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            criticAgentId = "critic-lifecycle-linkage",
            ttlMs = 60_000L,
            budgetLedger = ledger,
            pipelineFactory = { _, _ -> throw LinkageError("missing CriticReviewAgent companion") }
        )

        val result = lifecycle.run()

        assertEquals("failed", result.lifecycleOutcome)
        assertTrue(result.deathNoteWritten)
        val deathNote = db.stagingDao().getAll(runId, groupId).single { it.type == "DEATH_NOTE" }
        assertEquals("promoted", deathNote.status)
        assertTrue(deathNote.content.contains("outcome: failed"))
        assertTrue(deathNote.content.contains("LinkageError"))
    }
}
