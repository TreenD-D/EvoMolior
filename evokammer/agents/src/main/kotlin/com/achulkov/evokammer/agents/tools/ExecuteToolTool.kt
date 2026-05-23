package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.sandbox.network.ContentSanitiser
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.db.dao.ToolRegistryDao
import kotlinx.serialization.Serializable

/**
 * Koog tool: execute a tool from the Tool Registry in the tool sub-sandbox.
 *
 * Cost: 2 on success, 4 on failure, 8 on retry (same tool + args).
 *
 * @param toolRegistryDao DAO for tool registry lookups.
 * @param toolSubSandbox The sub-sandbox for nested process execution.
 * @param budgetLedger The agent's budget ledger.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param toolsMountPath Path to the /tools mount inside the agent VM.
 */
class ExecuteToolTool(
    private val toolRegistryDao: ToolRegistryDao,
    private val toolSubSandbox: ToolSubSandbox,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val groupId: String,
    private val toolsMountPath: String = "/tools",
    /** Sanitises tool stdout/stderr before returning to agent context (prevents prompt injection). */
    private val contentSanitiser: ContentSanitiser = ContentSanitiser()
) : SimpleTool<ExecuteToolTool.Args>(
    argsType = typeToken<Args>(),
    name = "execute_tool",
    description = """
        Execute a tool from the Tool Registry in an isolated sub-sandbox.
        Cost: 2 on success, 4 on failure, 8 for retry with identical arguments.
        Output capped at 50KB. Hard timeout: 30 seconds.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val toolName: String,
        val toolVersion: Int? = null,
        val argsJson: String
    )

    override suspend fun execute(args: Args): String {
        val tool = if (args.toolVersion != null) {
            toolRegistryDao.getById("${args.toolName}-v${args.toolVersion}")
        } else {
            toolRegistryDao.getLatestByName(runId, groupId, args.toolName)
        }

        if (tool == null) {
            return "Error: Tool '${args.toolName}' not found in Tool Registry."
        }

        val argsHash = args.argsJson.hashCode().toString()
        val toolKey = "${args.toolName}:$argsHash"

        val toolExecutablePath = "$toolsMountPath/${tool.name}-v${tool.version}"

        // Wrap sandbox invocation so unexpected exceptions (e.g. process launch failure)
        // are still accounted for and returned as a structured error rather than a tool crash.
        val result = try {
            toolSubSandbox.execute(toolExecutablePath, args.argsJson)
        } catch (e: Exception) {
            // Deduct failure budget for a sandbox-level exception, then surface as structured error.
            budgetLedger.deduct(ActionType.TOOL_EXEC_FAILURE, toolKey = toolKey)
            return "Error: Tool '${tool.name}' failed to launch: ${e.message?.take(300)}"
        }

        val actionType = when {
            result.timedOut -> ActionType.TOOL_EXEC_FAILURE
            result.success -> ActionType.TOOL_EXEC_SUCCESS
            else -> ActionType.TOOL_EXEC_FAILURE
        }

        val deductResult = budgetLedger.deduct(actionType, toolKey = toolKey)

        // Sanitise stdout/stderr to prevent prompt injection from external tool output
        val sanitisedStdout = contentSanitiser.sanitise(result.stdout, sourceUrl = "tool:${tool.name}")
        val sanitisedStderr = if (result.stderr.isNotBlank()) {
            contentSanitiser.sanitise(result.stderr, sourceUrl = "tool-stderr:${tool.name}")
        } else null

        val response = buildString {
            appendLine("Tool: ${tool.name} v${tool.version}")
            appendLine("Status: ${if (result.success) "SUCCESS" else "FAILURE"}")
            if (result.timedOut) appendLine("WARNING: Timed out after 30s")
            if (result.truncated) appendLine("WARNING: Output truncated to 50KB")
            if (sanitisedStdout.injectionDetected) appendLine("WARNING: Potential prompt injection detected in tool output — content sanitised.")
            if (sanitisedStdout.truncated) appendLine("WARNING: Tool output truncated by content sanitiser.")
            appendLine("Exit code: ${result.exitCode}")
            if (sanitisedStderr != null) appendLine("Stderr: ${sanitisedStderr.content}")
            appendLine("Output:")
            appendLine(sanitisedStdout.content)
        }

        return if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            "$response\nWARNING: Budget floor reached."
        } else {
            response
        }
    }
}
