package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class StagingToolRepairQueryTest {
    @Test
    fun `prior rejected tool query is bounded ordered and node scoped`() = runTest {
        val root = createTempDirectory("staging-tool-repair-query-").toFile()
        val database = SubstrateDatabaseFactory(root).get("run-repair", "group-repair")
        try {
            val rows = listOf(
                row("old", generation = 1, reviewedAtMs = 10),
                row("new-b", generation = 2, reviewedAtMs = 20, submittedAtMs = 30),
                row("new-a", generation = 2, reviewedAtMs = 20, submittedAtMs = 30),
                row("future", generation = 3, reviewedAtMs = 40),
                row("other-node", generation = 2, wipTaskRef = "node-other"),
                row("promoted", generation = 2, status = "promoted"),
                row("doc", generation = 2, type = "DOC")
            )
            rows.forEach { database.stagingDao().insert(it) }

            val result = database.stagingDao().getPriorRejectedToolsForNode(
                runId = "run-repair",
                groupId = "group-repair",
                authoringNodeId = "node-tool",
                beforeGeneration = 3,
                limit = 2,
                offset = 0
            )

            assertEquals(listOf("new-a", "new-b"), result.map { it.id })
            assertEquals(
                listOf("new-b"),
                database.stagingDao().getPriorRejectedToolsForNode(
                    runId = "run-repair",
                    groupId = "group-repair",
                    authoringNodeId = "node-tool",
                    beforeGeneration = 3,
                    limit = 1,
                    offset = 1
                ).map { it.id }
            )
        } finally {
            database.close()
        }
    }

    private fun row(
        id: String,
        generation: Int,
        reviewedAtMs: Long = generation.toLong(),
        submittedAtMs: Long = generation.toLong(),
        wipTaskRef: String = "node-tool",
        status: String = "rejected",
        type: String = "TOOL"
    ) = StagingSubmissionEntity(
        id = id,
        agentId = "agent-$id",
        role = "Builder",
        type = type,
        content = """{"opportunityId":"tool-opportunity"}""",
        wipTaskRef = wipTaskRef,
        budgetSignature = "signature",
        status = status,
        rejectionReason = "tool_promotion_test_failed:test:INVALID_OUTPUT_SCHEMA",
        criticAgentId = "critic",
        generation = generation,
        groupId = "group-repair",
        runId = "run-repair",
        submittedAtMs = submittedAtMs,
        reviewedAtMs = reviewedAtMs
    )
}
