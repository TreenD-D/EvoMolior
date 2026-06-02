package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Verifies council-schema validation for KG_FACT submissions.
 */
class KGConsistencyCheckerTest {

    @Test
    fun `validate accepts schema-compliant relation and rejects unknown relation`() {
        val checker = KGConsistencyChecker(
            schemaJson = """
                {
                  "relationTypes": ["IMPLEMENTS", "CAUSES"],
                  "entityTypes": ["Concept", "Algorithm"],
                  "hardConstraints": [],
                  "softConstraints": []
                }
            """.trimIndent()
        )

        val validSubmission = StagingSubmissionEntity(
            id = "submission-valid",
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            type = "KG_FACT",
            content = """
                SUBJECT: Algorithm A
                RELATION: IMPLEMENTS
                OBJECT: Concept B
                CONFIDENCE: 0.9
                DOMAIN: general
            """.trimIndent(),
            generation = 1,
            budgetSignature = "sig",
            wipTaskRef = "node-1",
            submittedAtMs = 1_000L
        )

        val invalidSubmission = validSubmission.copy(
            id = "submission-invalid",
            content = validSubmission.content.replace("IMPLEMENTS", "UNKNOWN_RELATION")
        )

        assertTrue(checker.validate(validSubmission) is ConsistencyResult.Valid)
        assertFalse(checker.validate(invalidSubmission) is ConsistencyResult.Valid)
    }

    @Test
    fun `validate rejects hard-constraint violations with a descriptive reason`() {
        val checker = KGConsistencyChecker(
            schemaJson = """
                {
                  "relationTypes": ["IMPLEMENTS", "CAUSES"],
                  "entityTypes": ["Concept", "Algorithm"],
                  "hardConstraints": [
                    {
                      "id": "HC-001",
                      "description": "CAUSES edges are disallowed for this run",
                      "expression": "RELATION:CAUSES"
                    }
                  ],
                  "softConstraints": []
                }
            """.trimIndent()
        )

        val violation = checker.validate(
            StagingSubmissionEntity(
                id = "submission-violating",
                runId = "run-1",
                groupId = "group-0",
                agentId = "agent-1",
                role = "Researcher",
                type = "KG_FACT",
                content = """
                    SUBJECT: Algorithm A
                    RELATION: CAUSES
                    OBJECT: Concept B
                    CONFIDENCE: 0.9
                    DOMAIN: general
                """.trimIndent(),
                generation = 1,
                budgetSignature = "sig",
                wipTaskRef = "node-1",
                submittedAtMs = 1_000L
            )
        )

        assertTrue(violation is ConsistencyResult.Invalid)
        assertTrue((violation as ConsistencyResult.Invalid).reason.contains("HC-001"))
        assertTrue(violation.reason.contains("CAUSES"))

        assertTrue(
            checker.validate(
                StagingSubmissionEntity(
                    id = "submission-blank-schema",
                    runId = "run-1",
                    groupId = "group-0",
                    agentId = "agent-2",
                    role = "Researcher",
                    type = "KG_FACT",
                    content = """
                        SUBJECT: Algorithm A
                        RELATION: IMPLEMENTS
                        OBJECT: Concept B
                    """.trimIndent(),
                    generation = 1,
                    budgetSignature = "sig",
                    wipTaskRef = "node-1",
                    submittedAtMs = 1_000L
                )
            ) is ConsistencyResult.Valid
        )
    }
}
