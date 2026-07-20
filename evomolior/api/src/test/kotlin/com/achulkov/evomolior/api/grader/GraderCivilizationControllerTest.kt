package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraderCivilizationControllerTest {
    private val dimensions = listOf(
        ScoringDimension(
            id = "accuracy",
            name = "Accuracy",
            description = "Evidence-grounded correctness.",
            weight = 1.0
        )
    )

    @Test
    fun `selection creates claimable grader wip and responds to prior failure telemetry`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-selection-").toFile())
        val db = factory.getGrader("run-1", "grader-0")
        val controller = GraderCivilizationController(db, "run-1", "grader-0")

        val first = controller.planGeneration(1, dimensions)
        assertEquals(
            listOf(Role.GraderResearcher, Role.GraderBuilder),
            first.assignments.map { it.role }
        )
        first.assignments.forEachIndexed { index, assignment ->
            controller.claim(assignment, "grader-agent-$index", 1)
            val task = db.wipTaskDao().getByNodeId(assignment.taskId, "run-1", "grader-0")
            assertEquals("claimed", task?.status)
            assertEquals("grader-agent-$index", task?.claimedByAgentId)
        }
        db.stagingDao().insert(
            staging(
                id = "failure-1",
                agentId = "system",
                role = "ScoringEngine",
                type = "FAILURE_TELEMETRY",
                status = "promoted",
                generation = 1
            )
        )

        val second = controller.planGeneration(2, dimensions)

        assertEquals(3, second.assignments.size)
        assertEquals(2, second.assignments.count { it.role == Role.GraderBuilder })
    }

    @Test
    fun `reviewed grader work produces legacy context and expiry dead end`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-legacy-").toFile())
        val db = factory.getGrader("run-1", "grader-0")
        val controller = GraderCivilizationController(db, "run-1", "grader-0")
        db.stagingDao().insert(
            staging(
                id = "criteria-1",
                agentId = "grader-researcher-1",
                role = Role.GraderResearcher.name,
                type = "DOC",
                status = "promoted",
                generation = 1,
                content = "Promoted adversarial rubric methodology."
            )
        )
        db.stagingDao().insert(
            staging(
                id = "death-1",
                agentId = "grader-researcher-1",
                role = Role.GraderResearcher.name,
                type = "DEATH_NOTE",
                status = "promoted",
                generation = 1,
                content = "Test evidence thresholds before trusting presentation.",
                budgetSignature = "DEATH_NOTE:grader-researcher-1:1"
            )
        )
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = "failed-tool-1",
                timestampMs = 1L,
                eventType = EventTypes.TOOL_EXEC,
                agentId = "grader-researcher-1",
                groupId = "grader-0",
                runId = "run-1",
                generation = 1,
                payloadJson = """{"toolName":"rubric_probe","success":false}"""
            )
        )

        val firstRollover = controller.completeGeneration(1)
        val legacy = controller.legacyContext(Role.GraderResearcher, 2)

        assertEquals(1, firstRollover.scoredAgents)
        assertEquals(1, firstRollover.extractedProtocols)
        assertNotNull(legacy)
        assertTrue(legacy.contains("Test evidence thresholds"))

        val expiry = controller.completeGeneration(3)
        assertEquals(1, expiry.expiredProtocols)
        assertEquals(1, expiry.deadEndsInserted)
        assertTrue(
            db.knowledgeBaseDao().search(
                runId = "run-1",
                groupId = "grader-0",
                query = "GRADER DEAD END",
                limit = 10
            ).isNotEmpty()
        )
    }

    private fun staging(
        id: String,
        agentId: String,
        role: String,
        type: String,
        status: String,
        generation: Int,
        content: String = "grader evidence",
        budgetSignature: String = "grader:test"
    ) = StagingSubmissionEntity(
        id = id,
        agentId = agentId,
        role = role,
        type = type,
        content = content,
        budgetSignature = budgetSignature,
        status = status,
        generation = generation,
        groupId = "grader-0",
        runId = "run-1",
        submittedAtMs = generation.toLong(),
        reviewedAtMs = generation.toLong(),
        initialTrustScore = 0.8
    )
}
