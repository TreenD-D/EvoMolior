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

/**
 * Verifies that write tools carry the claimed WiP task into staging when the model omits it.
 */
class WipTaskReferencePropagationTest {

    private fun ledger(groupId: String = "group-0") = BudgetLedger(
        agentId = "agent-1",
        initialBudget = 100.0,
        floor = 1.0,
        config = BudgetConfig(
            groupId = groupId,
            profile = GroupProfile.default(GroupProfileType.Library)
        )
    )

    @Test
    fun `write staging tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("write-staging-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-42",
            isOutlier = false
        )

        val result = tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = "task-linked doc",
                speculative = false
            )
        )

        assertTrue(result.contains("Submission accepted"))
        val stored = db.stagingDao().getByAgent("run-1", "group-0", "agent-1").single()
        assertEquals("node-42", stored.wipTaskRef)
    }

    @Test
    fun `explicit write staging wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("write-staging-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1b", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-1b",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-42",
            isOutlier = false
        )

        tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = "explicit task doc",
                wipTaskRef = "node-explicit",
                speculative = false
            )
        )

        val stored = db.stagingDao().getByAgent("run-1b", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }

    @Test
    fun `tool preflight rejection is persisted and a repaired candidate may retry`() = runTest {
        val dataRoot = createTempDirectory("write-staging-tool-preflight-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-tool-preflight", "group-0")
        var attempts = 0
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-tool-preflight",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Builder",
            generation = 0,
            defaultWipTaskRef = "node-tool",
            toolSubmissionPreflight = {
                attempts += 1
                if (attempts == 1) {
                    "tool_validation_unexpected_output: expected={\"value\":1}; actual={\"value\":2}"
                } else {
                    null
                }
            }
        )

        val rejected = tool.execute(
            WriteStagingTool.Args(type = "TOOL", content = "{\"candidate\":1}")
        )
        val accepted = tool.execute(
            WriteStagingTool.Args(type = "TOOL", content = "{\"candidate\":2}")
        )

        assertTrue(rejected.startsWith("Error: TOOL preflight rejected candidate"))
        assertTrue(rejected.contains("expected={\"value\":1}"))
        assertTrue(accepted.contains("Submission accepted"))
        val submissions = db.stagingDao()
            .getByAgent("run-tool-preflight", "group-0", "agent-1")
            .associateBy { it.content }
        assertEquals("rejected", submissions.getValue("{\"candidate\":1}").status)
        assertEquals(
            "system-tool-preflight",
            submissions.getValue("{\"candidate\":1}").criticAgentId
        )
        assertEquals("pending", submissions.getValue("{\"candidate\":2}").status)
    }

    @Test
    fun `ordinary worker tool staging rejects missing assigned opportunity before budget or persistence`() =
        runTest {
            val dataRoot = createTempDirectory("write-staging-tool-authority-missing-").toFile()
            val db = SubstrateDatabaseFactory(dataRoot).get("run-tool-authority", "group-0")
            val budget = ledger()
            val before = budget.snapshot().currentBalance
            val tool = WriteStagingTool(
                stagingDao = db.stagingDao(),
                budgetLedger = budget,
                runId = "run-tool-authority",
                groupId = "group-0",
                agentId = "agent-1",
                role = "Builder",
                generation = 0,
                defaultWipTaskRef = "node-tool",
                requireAssignedToolAuthority = true
            )

            val result = tool.execute(
                WriteStagingTool.Args(
                    type = "TOOL",
                    content = """{"opportunityId":"opportunity-tool"}"""
                )
            )

            assertEquals(
                "Error: TOOL submission requires an assigned sealed tool opportunity.",
                result
            )
            assertEquals(before, budget.snapshot().currentBalance)
            assertTrue(
                db.stagingDao().getByAgent(
                    "run-tool-authority",
                    "group-0",
                    "agent-1"
                ).isEmpty()
            )
        }

    @Test
    fun `ordinary worker tool staging rejects mismatched node and opportunity without mutation`() =
        runTest {
            val dataRoot = createTempDirectory("write-staging-tool-authority-mismatch-").toFile()
            val db = SubstrateDatabaseFactory(dataRoot).get("run-tool-authority", "group-0")
            val budget = ledger()
            val before = budget.snapshot().currentBalance
            val tool = WriteStagingTool(
                stagingDao = db.stagingDao(),
                budgetLedger = budget,
                runId = "run-tool-authority",
                groupId = "group-0",
                agentId = "agent-1",
                role = "Builder",
                generation = 0,
                defaultWipTaskRef = "node-tool",
                requireAssignedToolAuthority = true,
                assignedToolAuthority = AssignedToolSubmissionAuthority(
                    nodeId = "node-tool",
                    opportunityId = "opportunity-tool",
                    opportunityCommitmentHash = "a".repeat(64)
                ),
                toolCandidateWireCompiler = { draft ->
                    val wrongNode = draft.sourceArtifactPath == "src/wrong-node.py"
                    val opportunityId = if (wrongNode) {
                        "opportunity-tool"
                    } else {
                        "opportunity-other"
                    }
                    ToolCandidateWireCompilation.Compiled(
                        CompiledToolCandidateWire(
                            content = """{"opportunityId":"$opportunityId"}""",
                            wipTaskRef = if (wrongNode) "node-other" else "node-tool",
                            opportunityCommitmentHash = "a".repeat(64),
                            sourceHash = "b".repeat(64),
                            supplementalTestsHash = null,
                            candidateHash = "c".repeat(64)
                        )
                    )
                }
            )

            val wrongNode = tool.execute(
                WriteStagingTool.Args(
                    type = "TOOL",
                    toolCandidate = ToolCandidateWireDraft(
                        opportunityHandle = "T1",
                        sourceArtifactPath = "src/wrong-node.py",
                        entryPoint = "run_tool"
                    )
                )
            )
            val wrongOpportunity = tool.execute(
                WriteStagingTool.Args(
                    type = "TOOL",
                    toolCandidate = ToolCandidateWireDraft(
                        opportunityHandle = "T1",
                        sourceArtifactPath = "src/wrong-opportunity.py",
                        entryPoint = "run_tool"
                    )
                )
            )

            assertTrue(wrongNode.contains("exact assigned Atlas node"))
            assertTrue(wrongOpportunity.contains("does not match assigned authority"))
            assertEquals(before, budget.snapshot().currentBalance)
            assertTrue(
                db.stagingDao().getByAgent(
                    "run-tool-authority",
                    "group-0",
                    "agent-1"
                ).isEmpty()
            )
        }

    @Test
    fun `write staging tool accepts article sized doc payloads below raised cap`() = runTest {
        val dataRoot = createTempDirectory("write-staging-large-doc-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-large-doc", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-large-doc",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Builder",
            generation = 1,
            defaultWipTaskRef = null,
            isOutlier = false
        )
        val articleSizedContent = "x".repeat(53_000)

        val result = tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = articleSizedContent,
                speculative = false
            )
        )

        assertTrue(result.contains("Submission accepted"))
        val stored = db.stagingDao().getByAgent("run-large-doc", "group-0", "agent-1").single()
        assertEquals(articleSizedContent.length, stored.content.length)
    }

    @Test
    fun `write staging tool rejects runaway doc payloads above raised cap`() = runTest {
        val dataRoot = createTempDirectory("write-staging-too-large-doc-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-too-large-doc", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-too-large-doc",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Builder",
            generation = 1,
            defaultWipTaskRef = null,
            isOutlier = false
        )

        val result = tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = "x".repeat(256_001),
                speculative = false
            )
        )

        assertTrue(result.contains("Content exceeds 256KB limit"))
    }

    @Test
    fun `emit fact tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-fact-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-2", "group-0")
        val tool = EmitFactTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-2",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-99"
        )

        val result = tool.execute(
            EmitFactTool.Args(
                subject = "A",
                relation = "RELATES_TO",
                obj = "B",
                confidence = 0.8,
                domain = "general"
            )
        )

        assertTrue(result.contains("KG fact submitted"))
        val stored = db.stagingDao().getByAgent("run-2", "group-0", "agent-1").single()
        assertEquals("node-99", stored.wipTaskRef)
    }

    @Test
    fun `explicit emit fact wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-fact-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-3", "group-0")
        val tool = EmitFactTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-3",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-99"
        )

        tool.execute(
            EmitFactTool.Args(
                subject = "A",
                relation = "RELATES_TO",
                obj = "B",
                confidence = 0.8,
                domain = "general",
                wipTaskRef = "node-explicit"
            )
        )

        val stored = db.stagingDao().getByAgent("run-3", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }

    @Test
    fun `emit constraint tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-constraint-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-4", "group-0")
        val tool = EmitConstraintTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-4",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-77"
        )

        val result = tool.execute(
            EmitConstraintTool.Args(
                constraintId = "constraint-1",
                description = "must cite one validated tool",
                appliesToType = "KG_FACT",
                condition = "facts require a validated tool citation",
                violationPenalty = 0.3
            )
        )

        assertTrue(result.contains("KG soft constraint"))
        val stored = db.stagingDao().getByAgent("run-4", "group-0", "agent-1").single()
        assertEquals("node-77", stored.wipTaskRef)
    }

    @Test
    fun `explicit emit constraint wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-constraint-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-5", "group-0")
        val tool = EmitConstraintTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-5",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-77"
        )

        tool.execute(
            EmitConstraintTool.Args(
                constraintId = "constraint-2",
                description = "must cite one validated tool",
                appliesToType = "KG_FACT",
                condition = "facts require a validated tool citation",
                violationPenalty = 0.3,
                wipTaskRef = "node-explicit"
            )
        )

        val stored = db.stagingDao().getByAgent("run-5", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }
}
