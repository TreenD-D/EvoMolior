package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.agents.tools.KnowledgeBaseContextFormatter.formatIndex
import com.achulkov.evomolior.substrate.db.dao.KnowledgeBaseDao
import kotlinx.serialization.Serializable

/**
 * Lists compact Knowledge Base titles/headers before agents load full entries.
 *
 * @param knowledgeBaseDao DAO for the current group's KB.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 */
class ListKBTool(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val runId: String,
    private val groupId: String
) : SimpleTool<ListKBTool.Args>(
    argsType = typeToken<Args>(),
    name = "list_kb",
    description = """
        List compact Knowledge Base titles/headers and IDs without loading full document bodies.
        Use this first, then call read_kb(entryIds=[...]) only for selected entries.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Optional keyword or phrase to filter headers by body search. Blank lists recent active entries. */
        val query: String = "",
        /** Optional domain filter. If null, searches all domains. */
        val domain: String? = null,
        /** Maximum headers to return. Default: 10. */
        val maxResults: Int = 10
    )

    override suspend fun execute(args: Args): String {
        val normalizedQuery = args.query.trim()
        val boundedMaxResults = args.maxResults.coerceIn(1, 50)
        val results = when {
            args.domain != null -> knowledgeBaseDao.queryByDomain(runId, groupId, args.domain, boundedMaxResults)
            normalizedQuery.isBlank() -> knowledgeBaseDao.listActiveOrganized(runId, groupId, boundedMaxResults)
            else -> knowledgeBaseDao.search(runId, groupId, normalizedQuery, boundedMaxResults)
        }

        if (results.isEmpty()) {
            return if (normalizedQuery.isBlank()) {
                "Knowledge Base has no active headers${args.domain?.let { " in domain: $it" } ?: ""}."
            } else {
                "No KB headers found for query: \"$normalizedQuery\"${args.domain?.let { " in domain: $it" } ?: ""}."
            }
        }

        return formatIndex(
            entries = results,
            heading = if (normalizedQuery.isBlank()) {
                "Found ${results.size} organized active KB headers:"
            } else {
                "Found ${results.size} KB headers for query: \"$normalizedQuery\""
            }
        )
    }
}
