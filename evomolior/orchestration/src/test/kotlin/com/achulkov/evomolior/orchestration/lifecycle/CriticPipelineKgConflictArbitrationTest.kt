package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that KG conflicts enter dispute arbitration and can be resolved deterministically
 * without hitting the OpenRouter critic path.
 */
class CriticPipelineKgConflictArbitrationTest {

    @Test
    fun `conflicting KG fact is disputed then resolved by the injected critic runner`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-conflict-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-conflict"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val reviewerPrompts = mutableListOf<String>()
        val reviewerContexts = mutableListOf<String?>()

        db.knowledgeGraphFactDao().insert(
            KnowledgeGraphFactEntity(
                id = "kg-fact-existing",
                subject = "Algorithm A",
                relation = "IMPLEMENTS",
                objectNode = "Concept B",
                confidence = 0.95,
                sourceAgentId = "agent-existing",
                generation = 1,
                trustScore = 0.9,
                provenanceJson = "[]",
                status = "active",
                speculative = false,
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-conflicting",
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: IMPLEMENTS
                    OBJECT: Concept C
                    CONFIDENCE: 0.8
                    DOMAIN: general
                """.trimIndent(),
                wipTaskRef = "node-1",
                budgetSignature = "sig",
                generation = 2,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 2_000L
            )
        )

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 2,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            kgSchema = """
                {
                  "relationTypes": ["IMPLEMENTS", "CAUSES"],
                  "entityTypes": ["Concept", "Algorithm"],
                  "hardConstraints": [],
                  "softConstraints": []
                }
            """.trimIndent(),
            criticReviewRunner = { submission, prompt, disputeContext, criticId ->
                reviewerPrompts += prompt
                reviewerContexts += disputeContext
                assertTrue(disputeContext?.contains("factId=kg-fact-existing") == true)
                assertTrue(disputeContext.contains("subject=Algorithm A"))
                assertTrue(disputeContext.contains("relation=IMPLEMENTS"))
                assertTrue(disputeContext.contains("object=Concept B"))
                assertTrue(disputeContext.contains("trust=0.9"))
                assertTrue(disputeContext.contains("generation=1"))
                assertTrue(disputeContext.contains("status=active"))
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.84
                )
            },
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(1, reviewerPrompts.size)
        assertEquals(1, reviewerContexts.size)
        assertTrue(reviewerPrompts.single().contains("SECOND REVIEW"))
        assertTrue(reviewerPrompts.single().contains("KG-Critic arbitration"))
        assertTrue(reviewerPrompts.single().contains("Conflicting active fact(s):"))

        val staged = db.stagingDao().getById("kg-fact-conflicting")
        assertNotNull(staged)
        assertEquals("promoted", staged.status)
        assertEquals(0.5, staged.initialTrustScore)

        val promotedFact = db.knowledgeGraphFactDao()
            .queryByTriplePattern(runId, groupId, "Algorithm A", "IMPLEMENTS", "%", 10)
            .firstOrNull { it.objectNode == "Concept C" }
        assertNotNull(promotedFact)
        assertEquals("active", promotedFact.status)

        val previousFact = db.knowledgeGraphFactDao().getById("kg-fact-existing")
        assertNotNull(previousFact)
        assertEquals("disputed", previousFact.status)
        assertTrue(previousFact.trustScore > promotedFact.trustScore)
    }

    @Test
    fun `conflicting KG fact is rejected when the second review also disputes`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-conflict-dispute-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-conflict-dispute"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val reviewerPrompts = mutableListOf<String>()

        db.knowledgeGraphFactDao().insert(
            KnowledgeGraphFactEntity(
                id = "kg-fact-existing-dispute",
                subject = "Algorithm A",
                relation = "CAUSES",
                objectNode = "Concept B",
                confidence = 0.95,
                sourceAgentId = "agent-existing",
                generation = 1,
                trustScore = 0.9,
                provenanceJson = "[]",
                status = "active",
                speculative = false,
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-conflicting-dispute",
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: CAUSES
                    OBJECT: Concept C
                    CONFIDENCE: 0.8
                    DOMAIN: general
                """.trimIndent(),
                wipTaskRef = "node-1",
                budgetSignature = "sig",
                generation = 2,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 2_000L
            )
        )

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 2,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            kgSchema = """
                {
                  "relationTypes": ["IMPLEMENTS", "CAUSES"],
                  "entityTypes": ["Concept", "Algorithm"],
                  "hardConstraints": [],
                  "softConstraints": []
                }
            """.trimIndent(),
            criticReviewRunner = { submission, prompt, disputeContext, criticId ->
                reviewerPrompts += prompt
                assertTrue(disputeContext?.contains("factId=kg-fact-existing-dispute") == true)
                assertTrue(disputeContext.contains("subject=Algorithm A"))
                assertTrue(disputeContext.contains("relation=CAUSES"))
                assertTrue(disputeContext.contains("object=Concept B"))
                assertTrue(disputeContext.contains("trust=0.9"))
                assertTrue(disputeContext.contains("generation=1"))
                assertTrue(disputeContext.contains("status=active"))
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.DISPUTE,
                    criticAgentId = criticId,
                    disputeReason = "second review still uncertain"
                )
            },
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(1, reviewerPrompts.size)
        assertTrue(reviewerPrompts.single().contains("SECOND REVIEW"))
        assertTrue(reviewerPrompts.single().contains("KG-Critic arbitration"))
        assertTrue(reviewerPrompts.single().contains("Conflicting active fact(s):"))

        val staged = db.stagingDao().getById("kg-fact-conflicting-dispute")
        assertNotNull(staged)
        assertEquals("rejected", staged.status)
        assertTrue(staged.rejectionReason?.contains("Arbitration remained unresolved after the second review") == true)
    }
}
