package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.substrate.db.dao.ToolRegistryDao
import kotlinx.serialization.Serializable

/**
 * Koog tool: list active tools registered in the group's Tool Registry.
 *
 * @param toolRegistryDao DAO for the current group's tool registry.
 * @param runId Current run identifier.
 * @param groupId Current group identifier.
 */
class ListToolsTool(
    private val toolRegistryDao: ToolRegistryDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<ListToolsTool.Args>(
    argsType = typeToken<Args>(),
    name = "list_tools",
    description = """
        List active reusable tools available to this group.
        Use exact toolName and toolVersion before execute_tool when a worker tool card requires
        one immutable promoted version.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val domain: String? = null,
        val maxResults: Int = 10,
        val toolName: String? = null,
        val toolVersion: Int? = null
    )

    override suspend fun execute(args: Args): String {
        if (args.toolVersion != null && args.toolName.isNullOrBlank()) {
            return "Error: toolVersion requires an exact toolName."
        }
        val exactLookup = !args.toolName.isNullOrBlank()
        val tools = if (exactLookup) {
            val tool = if (args.toolVersion == null) {
                toolRegistryDao.getLatestByName(runId, groupId, requireNotNull(args.toolName))
            } else {
                toolRegistryDao.getById("${args.toolName}-v${args.toolVersion}")
            }
            listOfNotNull(tool).filter {
                it.runId == runId &&
                    it.groupId == groupId &&
                    it.status == "active" &&
                    (args.domain == null || it.domain == args.domain)
            }
        } else {
            toolRegistryDao.getActive(runId, groupId, args.domain)
                .take(args.maxResults.coerceIn(1, 50))
        }

        if (tools.isEmpty()) {
            val exactSuffix = args.toolName?.let { name ->
                " for $name${args.toolVersion?.let { " v$it" }.orEmpty()}"
            }.orEmpty()
            return "No active tools found$exactSuffix" +
                "${args.domain?.let { " in domain: $it" }.orEmpty()}. " +
                "If this task has a repeatable parsing, validation, extraction, or scanning step, " +
                "propose a reusable tool with write_staging(type=\"TOOL\", content=<tool JSON>)."
        }

        return buildString {
            appendLine("Found ${tools.size} active tool(s):")
            tools.forEachIndexed { index, tool ->
                appendLine()
                appendLine("--- Tool ${index + 1} ---")
                appendLine("Name: ${tool.name}")
                appendLine("Version: ${tool.version}")
                appendLine("Domain: ${tool.domain}")
                appendLine("Success Rate: ${String.format(java.util.Locale.ROOT, "%.2f", tool.successRate)}")
                appendLine(
                    "Input Schema: " +
                        if (exactLookup) tool.inputSchemaJson else tool.inputSchemaJson.take(800)
                )
                appendLine(
                    "Output Schema: " +
                        if (exactLookup) tool.outputSchemaJson else tool.outputSchemaJson.take(800)
                )
            }
        }
    }
}
