package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.kg.KgFactAttemptFailureCode
import com.achulkov.evomolior.core.kg.KgFactAttemptStatus
import com.achulkov.evomolior.substrate.kg.KgFactAttemptRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Verifies that the Critic pipeline fails closed on schema-invalid KG facts before LLM review.
 */
class CriticPipelineKgConsistencyTest {

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
    fun `alias is persisted and queried with the Council canonical relation`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-alias-test").toFile()
        val runId = "run-kg-alias"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-alias",
                runId = runId,
                groupId = groupId,
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: rollout
                    RELATION: requires_rollout_gate
                    OBJECT: approval
                    CONFIDENCE: 0.9
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "node-1",
                status = "pending",
                submittedAtMs = 1_000L
            )
        )
        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            kgSchema = """{"relationTypes":["REQUIRES"],"hardConstraints":[],"softConstraints":[]}""",
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, summary.promoted)
        val fact = db.knowledgeGraphFactDao()
            .queryByTriplePattern(runId, groupId, "rollout", "REQUIRES", "approval", 10)
            .single()
        assertEquals("REQUIRES", fact.relation)
        val attempt = KgFactAttemptRepository(db)
            .bySubmission(runId, groupId, "kg-fact-alias")
        assertNotNull(attempt)
        assertEquals(KgFactAttemptStatus.SCHEMA_RESOLVED, attempt.status)
        assertEquals("requires_rollout_gate", attempt.requestedRelation)
        assertEquals("REQUIRES", attempt.resolvedRelation)
        assertEquals(null, attempt.failureCode)
    }

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
        val attempt = KgFactAttemptRepository(db)
            .bySubmission(runId, groupId, "kg-fact-invalid")
        assertNotNull(attempt)
        assertEquals(KgFactAttemptStatus.SYNTAX_REJECTED, attempt.status)
        assertEquals(KgFactAttemptFailureCode.UNKNOWN_RELATION, attempt.failureCode)
        assertEquals(null, attempt.resolvedRelation)
    }

    @Test
    fun `malformed KG fact attempt is recorded with a typed missing-field failure`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-malformed-attempt-test").toFile()
        val runId = "run-kg-malformed-attempt"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-missing-subject",
                runId = runId,
                groupId = groupId,
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = "RELATION: IMPLEMENTS\nOBJECT: Concept B",
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = null,
                status = "pending",
                submittedAtMs = 1_000L
            )
        )

        CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            kgSchema = """{"relationTypes":["IMPLEMENTS"]}""",
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = dataRoot.absolutePath
        ).processPendingSubmissions()

        val attempt = KgFactAttemptRepository(db)
            .bySubmission(runId, groupId, "kg-fact-missing-subject")
        assertNotNull(attempt)
        assertEquals(KgFactAttemptStatus.SYNTAX_REJECTED, attempt.status)
        assertEquals(KgFactAttemptFailureCode.MISSING_SUBJECT, attempt.failureCode)
        assertEquals(1, KgFactAttemptRepository(db).forGroup(runId, groupId).size)
    }

    @Test
    fun `syntactically valid KG fact is recorded before missing WIP rejection`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-wip-attempt-test").toFile()
        val runId = "run-kg-wip-attempt"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        var reviewInvoked = false
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-no-wip",
                runId = runId,
                groupId = groupId,
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = "SUBJECT: A\nRELATION: concerns\nOBJECT: B",
                generation = 2,
                budgetSignature = "sig",
                wipTaskRef = null,
                status = "pending",
                submittedAtMs = 2_000L
            )
        )

        val summary = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 2,
            openRouterApiKey = "sk-test",
            kgSchema = """{"relationTypes":["CONCERNS"]}""",
            criticReviewRunner = { submission, _, _, criticId ->
                reviewInvoked = true
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            },
            toolsMountHostPath = dataRoot.absolutePath
        ).processPendingSubmissions()

        assertEquals(1, summary.rejected)
        assertTrue(!reviewInvoked)
        val staged = db.stagingDao().getById("kg-fact-no-wip")
        assertNotNull(staged)
        assertTrue(staged.rejectionReason?.contains("missing_wip_task_reference") == true)
        val attempts = KgFactAttemptRepository(db).forGroup(runId, groupId)
        assertEquals(1, attempts.size)
        assertEquals(KgFactAttemptStatus.SCHEMA_RESOLVED, attempts.single().status)
        assertEquals("CONCERNS", attempts.single().resolvedRelation)
        assertEquals(null, attempts.single().failureCode)
    }

    @Test
    fun `run-root KG facts are eligible for promotion into canonical KG`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-root-kg-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-root-kg"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-root",
                runId = runId,
                groupId = groupId,
                agentId = "agent-fact",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: audit-run
                    RELATION: SUPPORTS
                    OBJECT: durable-substrate-evidence
                    CONFIDENCE: 0.85
                    DOMAIN: audit
                    PROVENANCE: archive high-level log
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "dst:root",
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
            kgSchema = "{}",
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = dataRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        val staged = db.stagingDao().getById("kg-fact-root")
        assertNotNull(staged)
        assertEquals("promoted", staged.status)
        val facts = db.knowledgeGraphFactDao().queryByTriplePattern(runId, groupId, "%", "%", "%", 10)
        assertEquals(1, facts.size)
        assertEquals("SUPPORTS", facts.single().relation)
    }

    @Test
    fun `run-root tool submissions are eligible for promotion and materialisation`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-root-tool-test").toFile()
        val toolsRoot = File(dataRoot, "tools")
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-root-tool"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-root",
                runId = runId,
                groupId = groupId,
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name": "health-summary",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {"type":"object"},
                      "code": "cat >/dev/null\necho '{\"ok\":true}'",
                      "tests": [
                        {
                          "input": {},
                          "expectedOutput": {"ok": true}
                        }
                      ]
                    }
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "dst:root",
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
            kgSchema = "{}",
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = toolsRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        val staged = db.stagingDao().getById("tool-root")
        assertNotNull(staged)
        assertEquals("promoted", staged.status)
        val tools = db.toolRegistryDao().getActive(runId, groupId, domain = null)
        assertEquals(1, tools.size)
        assertEquals("health-summary", tools.single().name)
        assertTrue(File(toolsRoot, "health-summary-v1").canExecute())
    }

    @Test
    fun `tool submission without executable tests is rejected before critic LLM is invoked`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-validation-test").toFile()
        val toolsRoot = File(dataRoot, "tools")
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-tool-validation"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        var reviewInvoked = false

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-no-tests",
                runId = runId,
                groupId = groupId,
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name": "untested-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {"type":"object"},
                      "code": "cat >/dev/null\necho '{\"ok\":true}'"
                    }
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "dst:root",
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
            kgSchema = "{}",
            criticReviewRunner = { submission, _, _, criticId ->
                reviewInvoked = true
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            },
            toolsMountHostPath = toolsRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertTrue(!reviewInvoked)
        val staged = db.stagingDao().getById("tool-no-tests")
        assertNotNull(staged)
        assertEquals("rejected", staged.status)
        assertTrue(staged.rejectionReason?.contains("tool_validation_missing_tests") == true)
        assertEquals(0, db.toolRegistryDao().getActive(runId, groupId, domain = null).size)
    }

    @Test
    fun `tool submission with stdout schema mismatch is rejected before critic LLM is invoked`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-schema-mismatch-test").toFile()
        val toolsRoot = File(dataRoot, "tools")
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-tool-schema-mismatch"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        var reviewInvoked = false

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-schema-mismatch",
                runId = runId,
                groupId = groupId,
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name": "schema-mismatch-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {
                        "type":"object",
                        "properties": {"ok": {"type": "boolean"}},
                        "required": ["ok"]
                      },
                      "code": "cat >/dev/null\necho '{\"ok\":\"true\"}'",
                      "tests": [
                        {
                          "input": {}
                        }
                      ]
                    }
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "dst:root",
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
            kgSchema = "{}",
            criticReviewRunner = { submission, _, _, criticId ->
                reviewInvoked = true
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            },
            toolsMountHostPath = toolsRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertTrue(!reviewInvoked)
        val staged = db.stagingDao().getById("tool-schema-mismatch")
        assertNotNull(staged)
        assertEquals("rejected", staged.status)
        assertTrue(staged.rejectionReason?.contains("tool_validation_schema_mismatch") == true)
        assertEquals(0, db.toolRegistryDao().getActive(runId, groupId, domain = null).size)
    }

    @Test
    fun `promoted KG constraint updates live schema validation through the persisted constraint table`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-constraint-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-constraint"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

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
            criticReviewRunner = promoteReviewRunner,
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

    @Test
    fun `promoted soft KG constraint reduces trust on later matching fact`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-kg-soft-constraint-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-kg-soft-constraint"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-soft-constraint-1",
                runId = runId,
                groupId = groupId,
                agentId = "agent-constraint",
                role = "Builder",
                type = "KG_CONSTRAINT",
                content = """
                    {
                      "type": "soft",
                      "id": "SC-DEPRECATED-OBJECT",
                      "description": "Deprecated concepts are lower-trust until independently verified.",
                      "expression": "OBJECT:Deprecated",
                      "penaltyFactor": 0.25
                    }
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
            criticReviewRunner = promoteReviewRunner,
            toolsMountHostPath = dataRoot.absolutePath
        )

        val firstSummary = pipeline.processPendingSubmissions()
        assertEquals(1, firstSummary.promoted)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-fact-soft-penalty",
                runId = runId,
                groupId = groupId,
                agentId = "agent-fact",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: IMPLEMENTS
                    OBJECT: Deprecated Concept
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

        val factSubmission = db.stagingDao().getById("kg-fact-soft-penalty")
        assertNotNull(factSubmission)
        assertEquals("promoted", factSubmission.status)
        assertEquals(0.55, factSubmission.initialTrustScore ?: 0.0, 0.0001)

        val promotedFacts = db.knowledgeGraphFactDao()
            .queryByTriplePattern(runId, groupId, "%", "%", "%", 10)
        assertEquals(1, promotedFacts.size)
        assertEquals(0.55, promotedFacts.single().trustScore, 0.0001)
    }
}
