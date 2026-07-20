@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.agents.tools.KnowledgeBaseContextFormatter.formatFullEntries
import com.achulkov.evomolior.agents.tools.KnowledgeBaseContextFormatter.formatIndex
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.DownstreamUseKind
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Koog tool: read selected Knowledge Base entries or list matching headers.
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
    private val groupId: String,
    private val agentId: String = "",
    private val round: Int = 0,
    private val nodeId: String? = null,
    private val contributionGraphRepository: ContributionGraphRepository? = null,
    private val useIdFactory: () -> String = { UUID.randomUUID().toString() }
) : SimpleTool<ReadKBTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_kb",
    description = """
        Read selected Knowledge Base entries by ID, or list matching headers without full content.
        First use list_kb/search_kb to inspect titles/headers, then pass entryIds for full bodies.
        Dead Ends (documented failure approaches) are always included in results.
        Cost: FREE.
    """.trimIndent()
) {
    private val json = Json { encodeDefaults = true }

    @Serializable
    data class Args(
        /** Explicit KB entry IDs to load in full. Preferred for context safety. */
        val entryIds: List<String> = emptyList(),
        /** Search query — keyword or phrase. Blank lists active headers when entryIds is empty. */
        val query: String = "",
        /** Optional domain filter. If null, searches all domains. */
        val domain: String? = null,
        /** Maximum header results to return when entryIds is empty. Default: 5. */
        val maxResults: Int = 5,
        /** Compatibility escape hatch for full text from query results. Prefer entryIds. */
        val includeContent: Boolean = false
    )

    override suspend fun execute(args: Args): String {
        val requestedIds = args.entryIds.map(String::trim).filter(String::isNotBlank).distinct().take(5)
        if (requestedIds.isNotEmpty()) {
            val entries = requestedIds.mapNotNull { id ->
                knowledgeBaseDao.getById(id)?.takeIf { entry ->
                    entry.runId == runId &&
                        entry.groupId == groupId &&
                        (entry.status == "active" || entry.isDeadEnd)
                }
            }
            if (entries.isEmpty()) {
                return "No active KB entries found for requested IDs: ${requestedIds.joinToString(", ")}"
            }
            recordReads(entries.map { "kb:${it.id}" }, args)
            entries.forEach { entry -> knowledgeBaseDao.incrementCitationCount(entry.id) }
            val missingIds = requestedIds - entries.map { it.id }.toSet()
            return buildString {
                if (missingIds.isNotEmpty()) {
                    appendLine("Missing or inactive requested IDs: ${missingIds.joinToString(", ")}")
                    appendLine()
                }
                append(formatFullEntries(entries))
            }
        }

        val normalizedQuery = args.query.trim()
        val boundedMaxResults = args.maxResults.coerceIn(1, 25)
        val results = when {
            args.domain != null -> knowledgeBaseDao.queryByDomain(runId, groupId, args.domain, boundedMaxResults)
            normalizedQuery.isBlank() -> knowledgeBaseDao.listActiveOrganized(runId, groupId, boundedMaxResults)
            else -> knowledgeBaseDao.search(runId, groupId, normalizedQuery, boundedMaxResults)
        }

        if (results.isEmpty()) {
            return if (normalizedQuery.isBlank()) {
                "Knowledge Base is empty${args.domain?.let { " in domain: $it" } ?: ""}."
            } else {
                "No results found for query: \"$normalizedQuery\"${args.domain?.let { " in domain: $it" } ?: ""}"
            }
        }

        if (args.includeContent) {
            recordReads(results.map { "kb:${it.id}" }, args)
            results.forEach { entry -> knowledgeBaseDao.incrementCitationCount(entry.id) }
            return formatFullEntries(results)
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

    private suspend fun recordReads(authorityIds: List<String>, args: Args) {
        val repository = contributionGraphRepository ?: return
        require(agentId.isNotBlank()) {
            "Downstream KB reads require exact reader identity"
        }
        val contextHash = AtlasHashing.hashText(
            json.encodeToString(Args.serializer(), args)
        )
        authorityIds.forEach { authorityId ->
            val authority = requireNotNull(
                repository.canonicalAuthority(runId, groupId, authorityId)
            ) {
                "KB read lacks immutable canonical authority for $authorityId"
            }
            repository.recordDownstreamUse(
                DownstreamUseRecord(
                    useId = "downstream-read:${useIdFactory()}",
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = round,
                    agentId = agentId,
                    nodeId = nodeId,
                    kind = DownstreamUseKind.READ,
                    sourceAuthority = authority,
                    contextHash = contextHash,
                    occurredAt = Clock.System.now()
                ).withComputedHash()
            )
        }
    }
}
