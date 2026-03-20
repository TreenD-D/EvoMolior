package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import kotlinx.serialization.Serializable

/**
 * Koog tool: search the Knowledge Base.
 *
 * Cost: 0 (reads are always free) — no budget deduction.
 *
 * @param knowledgeBaseDao DAO for the current group's KB.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 */
class ReadKBTool(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<ReadKBTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_kb",
    description = """
        Search the Knowledge Base for documents relevant to your query.
        Returns matching documents with trust scores and generation numbers.
        Dead Ends (documented failure approaches) are always included in results.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Search query — keyword or phrase. */
        val query: String,
        /** Optional domain filter. If null, searches all domains. */
        val domain: String? = null,
        /** Maximum results to return. Default: 5. */
        val maxResults: Int = 5
    )

    override suspend fun execute(args: Args): String {
        val results = if (args.domain != null) {
            knowledgeBaseDao.queryByDomain(runId, groupId, args.domain, args.maxResults)
        } else {
            knowledgeBaseDao.search(runId, groupId, args.query, args.maxResults)
        }

        if (results.isEmpty()) {
            return "No results found for query: \"${args.query}\"${args.domain?.let { " in domain: $it" } ?: ""}"
        }

        // Increment citation counts for returned results
        results.forEach { entry -> knowledgeBaseDao.incrementCitationCount(entry.id) }

        return buildString {
            appendLine("Found ${results.size} KB entries:")
            results.forEachIndexed { i, entry ->
                appendLine("\n--- Entry ${i + 1} ---")
                appendLine("ID: ${entry.id}")
                appendLine("Domain: ${entry.domain}")
                appendLine("Generation: ${entry.generation}")
                appendLine("Trust Score: ${String.format("%.2f", entry.trustScore)}")
                appendLine("Citations: ${entry.citationCount}")
                if (entry.isDeadEnd) appendLine("DEAD END: This approach has been tried and failed.")
                appendLine("Content:")
                appendLine(
                    entry.content.take(2000).let {
                        if (entry.content.length > 2000) it + "\n[...truncated]" else it
                    }
                )
            }
        }
    }
}
