package com.achulkov.evokammer.agents.tools

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.tools.ToolMaterializer
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end regression for tool reuse across registry, materialisation, and execution.
 */
class ExecuteToolToolIntegrationTest {

    @Test
    fun `latest active materialized tool is executed end to end`() = runTest {
        val dataRoot = createTempDirectory("execute-tool-integration-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-tool-reuse"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val toolsRoot = factory.dbFilePath(runId, groupId).parentFile!!.resolve("tools")

        val toolV1 = ToolRegistryEntryEntity(
            id = "demo-tool-v1",
            name = "demo-tool",
            version = 1,
            authorAgentId = "agent-1",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "general",
            code = "echo version-1",
            codeLanguage = "shell",
            generation = 1,
            groupId = groupId,
            runId = runId,
            createdAtMs = 1_000L
        )
        val toolV2 = toolV1.copy(
            id = "demo-tool-v2",
            version = 2,
            code = "echo version-2",
            createdAtMs = 2_000L,
            previousVersionId = toolV1.id
        )

        val materializer = ToolMaterializer(toolsRoot)
        materializer.materialize(toolV1)
        materializer.materialize(toolV2)
        db.toolRegistryDao().insert(toolV1)
        db.toolRegistryDao().insert(toolV2)

        val resolved = db.toolRegistryDao().getLatestByName(runId, groupId, "demo-tool")
        assertNotNull(resolved)
        assertEquals(2, resolved.version)
        assertTrue(
            toolsRoot.resolve("demo-tool-v2").canExecute(),
            "Materialized tool executable should exist and be executable"
        )

        val ledger = BudgetLedger(
            agentId = "agent-test",
            initialBudget = 500.0,
            floor = 1.0,
            config = BudgetConfig(
                groupId = groupId,
                profile = GroupProfile.default(GroupProfileType.Factory)
            )
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath
        )

        val result = tool.execute(ExecuteToolTool.Args(toolName = "demo-tool", argsJson = "{}"))

        assertContains(result, "version-2", ignoreCase = true)
        assertContains(result, "SUCCESS")
        assertTrue(
            ledger.balance < 500.0,
            "Successful execution should deduct budget from the ledger"
        )
        assertTrue(
            ledger.balance <= 500.0 - BudgetConfig(groupId = groupId, profile = GroupProfile.default(GroupProfileType.Factory))
                .costFor(ActionType.TOOL_EXEC_SUCCESS) + 0.0001,
            "Success cost should be applied for end-to-end tool execution"
        )
    }
}
