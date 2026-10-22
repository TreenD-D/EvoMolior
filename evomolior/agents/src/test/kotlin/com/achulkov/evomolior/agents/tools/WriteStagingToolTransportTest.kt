package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.CompiledToolCandidateWire
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteStagingToolTransportTest {
    @Test
    fun `wire draft stages only runtime compiled exact tool payload`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("write-staging-wire-").toFile()
        ).get("run-wire", "group-wire")
        try {
            val commitmentHash = "a".repeat(64)
            val exactContent = """
                {
                  "opportunityId":"exact-opportunity",
                  "name":"tool-exact",
                  "domain":"calculation",
                  "language":"python",
                  "inputSchema":{"type":"object"},
                  "outputSchema":{"type":"object"},
                  "code":"print('{}')",
                  "tests":[{"testId":"public-case","input":{},"expectedOutput":{}}]
                }
            """.trimIndent()
            var preflightContent: String? = null
            val tool = WriteStagingTool(
                stagingDao = database.stagingDao(),
                budgetLedger = ledger(),
                runId = "run-wire",
                groupId = "group-wire",
                agentId = "agent-wire",
                role = "Builder",
                generation = 2,
                defaultWipTaskRef = "node-wire",
                requireAssignedToolAuthority = true,
                assignedToolAuthority = AssignedToolSubmissionAuthority(
                    nodeId = "node-wire",
                    opportunityId = "exact-opportunity",
                    opportunityCommitmentHash = commitmentHash
                ),
                toolCandidateWireCompiler = { draft ->
                    assertEquals("T1", draft.opportunityHandle)
                    ToolCandidateWireCompilation.Compiled(
                        CompiledToolCandidateWire(
                            content = exactContent,
                            wipTaskRef = "node-wire",
                            opportunityCommitmentHash = commitmentHash,
                            sourceHash = "b".repeat(64),
                            supplementalTestsHash = null,
                            candidateHash = "c".repeat(64)
                        )
                    )
                },
                toolSubmissionPreflight = { submission ->
                    preflightContent = submission.content
                    null
                }
            )

            val result = tool.execute(
                WriteStagingTool.Args(
                    type = "TOOL",
                    toolCandidate = ToolCandidateWireDraft(
                        opportunityHandle = "T1",
                        sourceArtifactPath = "src/main.py",
                        entryPoint = "run_tool"
                    )
                )
            )

            assertTrue(result.startsWith("Submission accepted"))
            val stored = database.stagingDao()
                .getByAgent("run-wire", "group-wire", "agent-wire")
                .single()
            assertEquals(exactContent, stored.content)
            assertEquals(exactContent, preflightContent)
            assertEquals("node-wire", stored.wipTaskRef)
        } finally {
            database.close()
        }
    }

    @Test
    fun `assigned workspace rejects direct exact contract transcription before budget mutation`() =
        runTest {
            val database = SubstrateDatabaseFactory(
                createTempDirectory("write-staging-wire-direct-").toFile()
            ).get("run-wire", "group-wire")
            try {
                val budget = ledger()
                val before = budget.snapshot().currentBalance
                val tool = WriteStagingTool(
                    stagingDao = database.stagingDao(),
                    budgetLedger = budget,
                    runId = "run-wire",
                    groupId = "group-wire",
                    agentId = "agent-wire",
                    role = "Builder",
                    generation = 2,
                    defaultWipTaskRef = "node-wire",
                    requireAssignedToolAuthority = true,
                    assignedToolAuthority = AssignedToolSubmissionAuthority(
                        nodeId = "node-wire",
                        opportunityId = "exact-opportunity",
                        opportunityCommitmentHash = "a".repeat(64)
                    ),
                    toolCandidateWireCompiler = {
                        ToolCandidateWireCompilation.Rejected(
                            "tool_wire_unexpected",
                            "Compiler should not be called."
                        )
                    }
                )

                val result = tool.execute(
                    WriteStagingTool.Args(
                        type = "TOOL",
                        content = """{"opportunityId":"exact-opportunity"}"""
                    )
                )

                assertEquals(
                    "Error: Assigned TOOL submissions require the generated toolCandidate wire.",
                    result
                )
                assertEquals(before, budget.snapshot().currentBalance)
                assertTrue(
                    database.stagingDao()
                        .getByAgent("run-wire", "group-wire", "agent-wire")
                        .isEmpty()
                )
            } finally {
                database.close()
            }
        }

    private fun ledger() = BudgetLedger(
        agentId = "agent-wire",
        initialBudget = 100.0,
        floor = 1.0,
        config = BudgetConfig(
            groupId = "group-wire",
            profile = GroupProfile.default(GroupProfileType.Library)
        )
    )
}
