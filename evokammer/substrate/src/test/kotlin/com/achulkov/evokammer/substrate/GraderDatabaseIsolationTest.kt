package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that grader substrates remain isolated from competing-group substrates.
 */
class GraderDatabaseIsolationTest {

    @Test
    fun `grader and competing substrate roots do not share staging rows`() = runTest {
        val dataRoot = createTempDirectory("grader-db-isolation-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)

        val competingDb = factory.get("run-1", "group-0")
        val graderDb = factory.getGrader("run-1", "grader-0")

        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "competing-plan-1",
                agentId = "agent-competing",
                role = "Researcher",
                type = "DOC",
                content = "Competing group document",
                budgetSignature = "budget:competing",
                status = "promoted",
                generation = 1,
                groupId = "group-0",
                runId = "run-1",
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_000L
            )
        )

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-plan-1",
                agentId = "system",
                role = "ScoringEngine",
                type = "FAILURE_TELEMETRY",
                content = "Grader-only telemetry",
                budgetSignature = "system:failure-telemetry",
                status = "promoted",
                generation = 1,
                groupId = "grader-0",
                runId = "run-1",
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_000L
            )
        )

        val competingRows = competingDb.stagingDao().getAll("run-1", "group-0")
        val graderRows = graderDb.stagingDao().getAll("run-1", "grader-0")

        assertEquals(1, competingRows.size)
        assertEquals("DOC", competingRows.single().type)

        assertEquals(1, graderRows.size)
        assertEquals("FAILURE_TELEMETRY", graderRows.single().type)
        assertTrue(
            factory.dbFilePath("run-1", "group-0").absolutePath !=
                factory.graderDbFilePath("run-1", "grader-0").absolutePath,
            "Competing and grader DB paths must be distinct"
        )
    }
}
