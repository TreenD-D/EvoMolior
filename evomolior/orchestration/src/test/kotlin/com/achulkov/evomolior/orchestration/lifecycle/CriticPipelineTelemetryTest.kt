package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression coverage for critic counts lost when a later review interrupts the pass. */
class CriticPipelineTelemetryTest {
    @Test
    fun `TTL preserves earlier outcomes in lifecycle summary and death note`() = runTest {
        assertLifecycleTelemetry("ttl_expired")
    }

    @Test
    fun `fatal review failure preserves earlier outcomes without counting unfinished work`() = runTest {
        assertLifecycleTelemetry("failed")
    }

    @Test
    fun `completed pass counts arbitration once and resets snapshot on next pass`() = runTest {
        assertLifecycleTelemetry("completed")
    }

    @Test
    fun `review exception after earlier promotions leaves unfinished work uncounted`() = runTest {
        assertLifecycleTelemetry("review_exception")
    }

    @Test
    fun `timeout after durable promotion preserves that promotion without false rejection`() = runTest {
        assertLifecycleTelemetry("promotion_timeout")
    }

    @Test
    fun `unfinished second review is neither rejected nor counted as resolved arbitration`() = runTest {
        assertLifecycleTelemetry("second_review_exception")
    }

    @Test
    fun `timeout before promotion authority excludes preliminary promoted status`() = runTest {
        assertLifecycleTelemetry("pre_authority_timeout")
    }

    private suspend fun assertLifecycleTelemetry(scenario: String) {
        val expectedOutcome = when (scenario) {
            "review_exception", "second_review_exception" -> "completed"
            "promotion_timeout", "pre_authority_timeout" -> "ttl_expired"
            else -> scenario
        }
        val lastPromoted = scenario in setOf("completed", "promotion_timeout")
        val root = createTempDirectory("critic-telemetry").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val runId = "run-telemetry"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        try {
            val ids = listOf("promote", "reject", "arbitrate", "last")
            for ((index, id) in ids.withIndex()) {
                db.stagingDao().insert(
                    StagingSubmissionEntity(
                        id = id,
                        agentId = "author",
                        role = "Researcher",
                        type = "PLAN_UPDATE",
                        content = "Criteria plan $id",
                        budgetSignature = "sig",
                        generation = 1,
                        groupId = groupId,
                        runId = runId,
                        submittedAtMs = index.toLong()
                    )
                )
            }
            val config = BudgetConfig(groupId, GroupProfile.Library)
            val ledger = BudgetLedger("critic", 10.0, config.floorFor(10.0), config)
            lateinit var pipeline: CriticPipeline
            var reachedLastReview = false
            var reachedPromotionBoundary = false
            val lifecycle = LifecycleBoundCriticPipeline(
                db = db,
                runId = runId,
                groupId = groupId,
                generation = 1,
                criticAgentId = "critic",
                ttlMs = if (expectedOutcome == "ttl_expired") 5_000L else 60_000L,
                budgetLedger = ledger,
                pipelineFactory = { criticId, blocklist ->
                    CriticPipeline(
                        db = db,
                        runId = runId,
                        groupId = groupId,
                        generation = 1,
                        openRouterApiKey = "unused",
                        criticAgentId = criticId,
                        criticAuthorBlocklist = blocklist,
                        toolsMountHostPath = root.absolutePath,
                        wipCompletionAuthorizer = { submission, _ ->
                            if (submission.id == "last" && scenario == "pre_authority_timeout") {
                                reachedPromotionBoundary = true
                                awaitCancellation()
                            }
                            WipCompletionAuthorityDecision.AUTHORIZED
                        },
                        onSubmissionPromoted = { submission, _ ->
                            if (submission.id == "last" && scenario == "promotion_timeout") {
                                reachedPromotionBoundary = true
                                awaitCancellation()
                            }
                        },
                        criticReviewRunner = { submission, _, disputeContext, reviewer ->
                            if (submission.id == "last") {
                                reachedLastReview = true
                                when (scenario) {
                                    "ttl_expired" -> awaitCancellation()
                                    "failed" -> throw LinkageError("review linkage failed")
                                    "review_exception" -> error("review failed before persistence")
                                    "second_review_exception" -> if (disputeContext != null) {
                                        error("second review failed before persistence")
                                    }
                                }
                            }
                            CriticReview(
                                submissionId = submission.id,
                                outcome = when {
                                    submission.id == "reject" -> CriticOutcome.REJECT
                                    submission.id == "last" && scenario == "second_review_exception" ->
                                        CriticOutcome.DISPUTE
                                    submission.id == "arbitrate" && disputeContext == null ->
                                        CriticOutcome.DISPUTE
                                    else -> CriticOutcome.PROMOTE
                                },
                                criticAgentId = reviewer,
                                initialTrustScore = 0.8,
                                rejectionReason = "Rejected criteria",
                                disputeReason = "Independent review required"
                            )
                        }
                    ).also { pipeline = it }
                }
            )

            val result = lifecycle.run()
            val expected = CriticSummary(
                promoted = if (lastPromoted) 3 else 2,
                rejected = 1,
                arbitrated = 1
            )
            assertTrue(reachedLastReview, "TTL must interrupt the final review, not fixture setup")
            if (scenario in setOf("promotion_timeout", "pre_authority_timeout")) {
                assertTrue(reachedPromotionBoundary)
            }
            assertEquals(expectedOutcome, result.lifecycleOutcome)
            assertEquals(expected, result.summary)
            assertEquals(expected, pipeline.summarySnapshot())
            assertEquals(expected.promoted + expected.rejected, result.summary.total)
            assertTrue(result.deathNoteWritten)
            assertTrue(result.budgetSnapshot.currentBalance < result.budgetSnapshot.initialBudget)

            val staged = db.stagingDao().getAll(runId, groupId)
            val reviewed = staged.filter { it.id in ids }
            assertEquals(
                expected.promoted,
                reviewed.count { it.status == "promoted" && it.promotedArtifactRef != null }
            )
            assertEquals(expected.rejected, reviewed.count { it.status == "rejected" })
            assertEquals(
                if (lastPromoted || scenario == "pre_authority_timeout") 0 else 1,
                reviewed.count { it.status == "pending" }
            )
            if (scenario == "pre_authority_timeout") {
                val unfinished = reviewed.single { it.id == "last" }
                assertEquals("promoted", unfinished.status)
                assertNull(unfinished.promotedArtifactRef)
                assertNull(
                    db.contributionGraphDao().criticDecisionForSubmission(runId, groupId, "last")
                )
            }
            val note = staged.single { it.type == "DEATH_NOTE" }
            assertEquals("promoted", note.status)
            assertTrue(note.content.contains("outcome: $expectedOutcome"))
            assertTrue(note.content.contains("promoted: ${expected.promoted}"))
            assertTrue(note.content.contains("rejected: ${expected.rejected}"))
            assertTrue(note.content.contains("arbitrated: ${expected.arbitrated}"))
            assertEquals(note.content, db.knowledgeBaseDao().getDeadEnds(runId, groupId).single().content)

            if (scenario == "completed") {
                // Already-promoted records (including the lifecycle note) are not new work.
                val empty = pipeline.processPendingSubmissions()
                assertEquals(CriticSummary(0, 0, 0), empty)
                assertEquals(empty, pipeline.summarySnapshot())
            }
        } finally {
            factory.close(runId, groupId)
            root.deleteRecursively()
        }
    }
}
