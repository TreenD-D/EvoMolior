package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.core.contract.CriticReview
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the Critic pipeline fails closed on schema-invalid KG facts before LLM review.
 */
class CriticPipelineKgConsistencyTest {

    @Test
    fun `schema-invalid KG fact is rejected before the critic LLM is invoked`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-consistency-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-consistency"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-invalid",
                runId = runId,
                groupId = groupId,
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: UNKNOWN_RELATION
                    OBJECT: Concept B
                    CONFIDENCE: 0.9
                    DOMAIN: general
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "node-1",
                status = "pending",
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
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
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(0, summary.arbitrated)

        val stored = db.stagingDao().getById("kg-fact-invalid")
        assertNotNull(stored)
        assertEquals("rejected", stored.status)
        assertTrue(stored.rejectionReason?.contains("kg_schema_unknown_relation") == true)
    }

    @Test
    fun `promoted KG constraint updates live schema validation through the persisted constraint table`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-constraint-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-constraint"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        val reviewerRunner: suspend (StagingSubmissionEntity, String, String?, String) -> CriticReview =
            { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            }

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-constraint-1",
                runId = runId,
                groupId = groupId,
                agentId = "agent-constraint",
                role = "Builder",
                type = "KG_CONSTRAINT",
                content = """{"relationType":"EXTENDS"}""",
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "node-1",
                status = "pending",
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
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
            criticReviewRunner = reviewerRunner,
            toolsMountHostPath = dataRoot.absolutePath
        )

        val firstSummary = pipeline.processPendingSubmissions()
        assertEquals(1, firstSummary.promoted)
        assertEquals(0, firstSummary.rejected)

        val constraintRows = db.kgSchemaConstraintDao().getAll(runId, groupId)
        assertEquals(1, constraintRows.size)
        assertTrue(constraintRows.single().constraintJson.contains("EXTENDS"))

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-extension",
                runId = runId,
                groupId = groupId,
                agentId = "agent-fact",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: EXTENDS
                    OBJECT: Concept B
                    CONFIDENCE: 0.8
                    DOMAIN: general
                """.trimIndent(),
                generation = 2,
                budgetSignature = "sig",
                wipTaskRef = "node-2",
                status = "pending",
                submittedAtMs = 2_000L,
                reviewedAtMs = null
            )
        )

        val secondSummary = pipeline.processPendingSubmissions()
        assertEquals(1, secondSummary.promoted)
        assertEquals(0, secondSummary.rejected)

        val fact = db.stagingDao().getById("kg-fact-extension")
        assertNotNull(fact)
        assertEquals("promoted", fact.status)
        assertTrue(fact.rejectionReason == null)
        assertTrue(db.kgSchemaDao().get(runId, groupId)?.relationTypesJson?.contains("EXTENDS") == true)
    }
}
