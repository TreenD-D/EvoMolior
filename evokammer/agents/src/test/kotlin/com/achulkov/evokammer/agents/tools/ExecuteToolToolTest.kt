package com.achulkov.evokammer.agents.tools

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.db.dao.ToolRegistryDao
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Unit tests for [ExecuteToolTool].
 *
 * Uses fake DAO and sandbox implementations so no Room DB or real processes are required.
 *
 * Verifies:
 * - Missing tool returns structured error without budget deduction
 * - Sandbox exception deducts failure budget and returns structured error
 * - Success deducts success budget
 * - Failure deducts failure budget
 * - Repeated identical call is priced as retry (higher cost than first)
 */
class ExecuteToolToolTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun budgetConfig(groupId: String = "g0") = BudgetConfig(
        groupId = groupId,
        profile = GroupProfile.default(GroupProfileType.Factory)
    )

    private fun freshLedger(initialBudget: Double = 500.0) = BudgetLedger(
        agentId = "agent-test",
        initialBudget = initialBudget,
        floor = 1.0,
        config = budgetConfig()
    )

    /** Fake DAO that returns null (no tool) or the provided tool entry. */
    private class FakeToolRegistryDao(
        private val registeredTool: ToolRegistryEntryEntity? = null
    ) : ToolRegistryDao {
        override suspend fun insert(tool: ToolRegistryEntryEntity) {}
        override suspend fun update(tool: ToolRegistryEntryEntity) {}
        override suspend fun getById(id: String) = registeredTool?.takeIf { it.id == id }
        override suspend fun getLatestByName(runId: String, groupId: String, name: String) =
            registeredTool?.takeIf { it.name == name }
        override suspend fun getLatestVersionByName(runId: String, groupId: String, name: String) =
            registeredTool?.takeIf { it.name == name }
        override suspend fun getActive(runId: String, groupId: String, domain: String?) = emptyList<ToolRegistryEntryEntity>()
        override fun observeActive(runId: String, groupId: String): Flow<List<ToolRegistryEntryEntity>> = flowOf(emptyList())
        override suspend fun updateExecutionStats(id: String, newSuccessRate: Double, newFailureLogJson: String) {}
        override suspend fun getAverageFailureRate(runId: String, groupId: String): Double? = null
        override suspend fun getAdoptedToolNames(runId: String, groupId: String) = emptyList<String>()
        override suspend fun getAdoptedToolCountForAgent(runId: String, groupId: String, authorAgentId: String) = 0
    }

    private fun sampleTool(name: String = "my-tool") = ToolRegistryEntryEntity(
        id = "$name-v1",
        name = name,
        version = 1,
        authorAgentId = "agent-builder-001",
        inputSchemaJson = "{}",
        outputSchemaJson = "{}",
        domain = "utils",
        code = "#!/bin/sh\necho ok",
        generation = 1,
        groupId = "g0",
        runId = "run-test",
        createdAtMs = 0L
    )

    private fun toolsMountPath(scriptName: String, scriptBody: String): String {
        val toolsDir = createTempDirectory(prefix = "tools-").toFile()
        val script = File(toolsDir, scriptName)
        script.writeText(scriptBody.trimIndent())
        script.setExecutable(true, false)
        return toolsDir.absolutePath
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `missing tool returns structured error without deducting budget`() = runTest {
        val ledger = freshLedger()
        val balanceBefore = ledger.balance

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = null),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0"
        )

        val result = tool.execute(ExecuteToolTool.Args(toolName = "nonexistent", argsJson = "{}"))

        assertContains(result, "not found", ignoreCase = true,
            message = "Missing tool result must indicate not found")
        assertEquals(balanceBefore, ledger.balance,
            "No budget should be deducted when tool is not in registry")
    }

    @Test
    fun `sandbox exception deducts failure budget and returns structured error`() = runTest {
        val ledger = freshLedger()
        val balanceBefore = ledger.balance

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = sampleTool()),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0"
        )

        val result = tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = "{}"))

        assertContains(result, "Error", ignoreCase = true,
            message = "Sandbox exception must return a structured error message")
        assertTrue(ledger.balance < balanceBefore,
            "Failure budget must be deducted after sandbox exception")
    }

    @Test
    fun `successful tool execution deducts success budget`() = runTest {
        val ledger = freshLedger()
        val balanceBefore = ledger.balance
        val toolsMount = toolsMountPath(
            scriptName = "${sampleTool().name}-v${sampleTool().version}",
            scriptBody = """
                #!/bin/sh
                cat >/dev/null
                echo ok
                exit 0
            """
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = sampleTool()),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0",
            toolsMountPath = toolsMount
        )

        tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = "{}"))

        val deducted = balanceBefore - ledger.balance
        val expectedSuccessCost = budgetConfig().costFor(ActionType.TOOL_EXEC_SUCCESS)
        assertEquals(expectedSuccessCost, deducted, 0.0001,
            "Successful tool execution must deduct exactly the success cost")
    }

    @Test
    fun `latest active tool version is executed when version is omitted`() = runTest {
        val ledger = freshLedger()
        val latestTool = sampleTool().copy(
            id = "my-tool-v2",
            version = 2
        )
        val toolsMount = toolsMountPath(
            scriptName = "${latestTool.name}-v${latestTool.version}",
            scriptBody = """
                #!/bin/sh
                cat >/dev/null
                echo latest-version
                exit 0
            """
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = latestTool),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0",
            toolsMountPath = toolsMount
        )

        val result = tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = "{}"))

        assertContains(result, "latest-version", ignoreCase = true,
            message = "Omitted toolVersion should execute the latest active registry row")
    }

    @Test
    fun `failed tool execution deducts failure budget`() = runTest {
        val ledger = freshLedger()
        val balanceBefore = ledger.balance
        val toolsMount = toolsMountPath(
            scriptName = "${sampleTool().name}-v${sampleTool().version}",
            scriptBody = """
                #!/bin/sh
                cat >/dev/null
                echo error 1>&2
                exit 1
            """
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = sampleTool()),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0",
            toolsMountPath = toolsMount
        )

        tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = "{}"))

        val deducted = balanceBefore - ledger.balance
        val expectedFailureCost = budgetConfig().costFor(ActionType.TOOL_EXEC_FAILURE)
        assertEquals(expectedFailureCost, deducted, 0.0001,
            "Failed tool execution must deduct exactly the failure cost")
    }

    @Test
    fun `repeated identical args is priced as retry (higher cost than first call)`() = runTest {
        val ledger = freshLedger()
        val toolsMount = toolsMountPath(
            scriptName = "${sampleTool().name}-v${sampleTool().version}",
            scriptBody = """
                #!/bin/sh
                cat >/dev/null
                echo ok
                exit 0
            """
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = FakeToolRegistryDao(registeredTool = sampleTool()),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = "run-test",
            groupId = "g0",
            toolsMountPath = toolsMount
        )

        val balanceBefore = ledger.balance
        tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = """{"key":"val"}"""))
        val balanceAfterFirst = ledger.balance

        // Same tool + same args = retry
        tool.execute(ExecuteToolTool.Args(toolName = "my-tool", argsJson = """{"key":"val"}"""))
        val balanceAfterSecond = ledger.balance

        val firstCost = balanceBefore - balanceAfterFirst
        val secondCost = balanceAfterFirst - balanceAfterSecond
        assertTrue(secondCost > firstCost,
            "Retry call (same args) must cost more than the first call: first=$firstCost second=$secondCost")
    }
}
