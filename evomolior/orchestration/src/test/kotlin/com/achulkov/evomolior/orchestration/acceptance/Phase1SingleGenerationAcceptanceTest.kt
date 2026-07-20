package com.achulkov.evomolior.orchestration.acceptance

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase1SingleGenerationAcceptanceTest {

    @Test
    fun `one generation promotes a validated document tool and failed approach`() = runTest {
        val dataRoot = createTempDirectory("phase-1-single-generation-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-phase-1-acceptance"
        val groupId = "group-0"
        val toolsRoot = File(dataRoot, "tools")
        val db = substrateFactory.get(runId, groupId)

        listOf(
                submission(
                    id = "generation-1-document",
                    runId = runId,
                    groupId = groupId,
                    agentId = "researcher-generation-1",
                    role = "Researcher",
                    type = "DOC",
                    content = "PHASE_ONE_DOCUMENT_MARKER: validated single-generation finding."
                ),
                submission(
                    id = "generation-1-tool",
                    runId = runId,
                    groupId = groupId,
                    agentId = "builder-generation-1",
                    role = "Builder",
                    type = "TOOL",
                    content = """
                        {
                          "name": "phase-one-validator",
                          "domain": "acceptance",
                          "language": "shell",
                          "inputSchema": {"type":"object"},
                          "outputSchema": {"type":"object"},
                          "code": "cat >/dev/null\necho '{\"validated\":true}'",
                          "tests": [
                            {
                              "input": {},
                              "expectedOutput": {"validated": true}
                            }
                          ]
                        }
                    """.trimIndent()
                ),
                submission(
                    id = "generation-1-dead-end",
                    runId = runId,
                    groupId = groupId,
                    agentId = "researcher-generation-1",
                    role = "Researcher",
                    type = "DEATH_NOTE",
                    content = """
                        {
                          "summary":"Generation one acceptance complete.",
                          "whatWorked":"Validated document and tool paths.",
                          "whatFailed":"PHASE_ONE_DEAD_END_MARKER: unbounded regex evaluation exhausted the test budget.",
                          "recommendations":"Use bounded deterministic matchers.",
                          "role":"Researcher",
                          "generation":1
                        }
                    """.trimIndent()
                )
        ).forEach { db.stagingDao().insert(it) }

        val pipeline = CriticPipeline(
            db = db,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticReviewRunner = { staged, _, _, criticId ->
                CriticReview(
                    submissionId = staged.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.9
                )
            },
            toolsMountHostPath = toolsRoot.absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(3, summary.promoted)
        assertEquals(0, summary.rejected)
        val kbEntries = db.knowledgeBaseDao().listActiveOrganized(runId, groupId, 10)
        assertTrue(kbEntries.any { !it.isDeadEnd && it.content.contains("PHASE_ONE_DOCUMENT_MARKER") })
        assertTrue(kbEntries.any { it.isDeadEnd && it.content.contains("PHASE_ONE_DEAD_END_MARKER") })
        val tool = assertNotNull(
            db.toolRegistryDao().getLatestByName(runId, groupId, "phase-one-validator")
        )
        assertEquals("active", tool.status)
        assertTrue(toolsRoot.resolve("${tool.name}-v${tool.version}").canExecute())
    }

    private fun submission(
        id: String,
        runId: String,
        groupId: String,
        agentId: String,
        role: String,
        type: String,
        content: String
    ): StagingSubmissionEntity = StagingSubmissionEntity(
        id = id,
        runId = runId,
        groupId = groupId,
        agentId = agentId,
        role = role,
        type = type,
        content = content,
        generation = 1,
        budgetSignature = "budget:$agentId",
        wipTaskRef = "dst:root",
        status = "pending",
        submittedAtMs = 1_000L,
        reviewedAtMs = null
    )
}
