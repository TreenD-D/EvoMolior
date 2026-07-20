@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.agents.tools.KnowledgeBaseContextFormatter.formatIndex
import com.achulkov.evomolior.core.embedding.EmbeddingEngine
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.DownstreamUseKind
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock

/**
 * Koog tool: semantic (vector) search over the Knowledge Base.
 *
 * Computes a TF-IDF embedding of the query, then ranks all active KB entries
 * by cosine similarity to find semantically related documents.  Entries without
 * a stored embedding fall back to keyword containment and are ranked below
 * embedding-matched entries.
 *
 * ### Cost
 * 0.1 budget units per call (more expensive than [ReadKBTool] keyword search).
 *
 * @param knowledgeBaseDao DAO for the current group's KB.
 * @param runId            The current run ID.
 * @param groupId          The current group ID.
 * @param maxCandidateLoad Maximum number of entries to load for ranking (default: 200).
 *                         Keep this bounded to avoid large in-memory loads.
 */
class SearchKBTool(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val runId: String,
    private val groupId: String,
    private val maxCandidateLoad: Int = 200,
    private val agentId: String = "",
    private val round: Int = 0,
    private val nodeId: String? = null,
    private val contributionGraphRepository: ContributionGraphRepository? = null,
    private val useIdFactory: () -> String = { UUID.randomUUID().toString() }
) : SimpleTool<SearchKBTool.Args>(
    argsType = typeToken<Args>(),
    name = "search_kb",
    description = """
        Semantic (vector) search over the Knowledge Base.
        Unlike read_kb (keyword match), this finds conceptually related documents
        even when the exact query words are absent.
        Cost: 0.1 budget units.
    """.trimIndent()
) {
    private val json = Json { encodeDefaults = true }

    @Serializable
    data class Args(
        /** Natural-language search query. Blank lists organized active entries. */
        val query: String = "",
        /** Optional domain filter. If null, searches all domains. */
        val domain: String? = null,
        /** Maximum results to return (default: 5). */
        val maxResults: Int = 5,
        /** Compatibility escape hatch for full text from semantic results. Prefer read_kb(entryIds). */
        val includeContent: Boolean = false
    )

    override suspend fun execute(args: Args): String {
        val normalizedQuery = args.query.trim()
        val boundedMaxResults = args.maxResults.coerceIn(1, 25)
        if (normalizedQuery.isBlank()) {
            return listRecentEntries(args.domain, boundedMaxResults)
        }

        // 1. Compute query embedding
        val queryEmbedding = EmbeddingEngine.embed(normalizedQuery)

        // 2. Load candidate entries from DB
        val candidates = if (args.domain != null) {
            knowledgeBaseDao.queryByDomain(runId, groupId, args.domain, maxCandidateLoad)
        } else {
            // loadAllActive is bounded by maxCandidateLoad; use the full-scan query
            knowledgeBaseDao.search(runId, groupId, "", maxCandidateLoad)
        }

        if (candidates.isEmpty()) {
            return "Knowledge Base is empty — no entries found" +
                    (args.domain?.let { " in domain '$it'" } ?: "") + "."
        }

        // 3. Split into entries with and without embeddings
        val (withEmbedding, withoutEmbedding) = candidates.partition { it.embedding != null }

        // 4. Rank embedding-equipped entries by cosine similarity
        val ranked = EmbeddingEngine.rank(
            query = queryEmbedding,
            candidates = withEmbedding,
            embeddingOf = { it.embedding }
        )

        // 5. For entries without embeddings, fall back to keyword containment score
        val queryTokens = normalizedQuery.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val keywordFallback = withoutEmbedding
            .map { entry ->
                val contentLower = entry.content.lowercase()
                val matchCount = queryTokens.count { it in contentLower }
                val score = if (queryTokens.isEmpty()) 0.0 else matchCount.toDouble() / queryTokens.size
                entry to (score * 0.5) // discount fallback entries
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        // 6. Merge: embedding results first, then keyword fallback
        val merged = (ranked + keywordFallback)
            .sortedByDescending { it.second }
            .take(boundedMaxResults)

        if (merged.isEmpty()) {
            return "No semantically related KB entries found for: \"$normalizedQuery\""
        }

        val entries = merged.map { it.first }
        if (args.includeContent) {
            recordReads(entries.map { "kb:${it.id}" }, args)
            merged.forEach { (entry, _) -> knowledgeBaseDao.incrementCitationCount(entry.id) }
            return buildString {
                appendLine("Loaded ${merged.size} semantic KB matches with full content (query: \"$normalizedQuery\"):")
                merged.forEachIndexed { i, (entry, score) ->
                    appendLine()
                    appendLine("--- Entry ${i + 1} ---")
                    appendLine("ID: ${entry.id}")
                    appendLine("Similarity: ${String.format(Locale.US, "%.3f", score)}")
                    append(KnowledgeBaseContextFormatter.formatFullEntries(listOf(entry)))
                }
            }
        }

        return formatIndex(
            entries = entries,
            heading = "Found ${merged.size} semantically related KB headers (query: \"$normalizedQuery\"):"
        )
    }

    private suspend fun listRecentEntries(domain: String?, maxResults: Int): String {
        val results = if (domain != null) {
            knowledgeBaseDao.queryByDomain(runId, groupId, domain, maxResults)
        } else {
            knowledgeBaseDao.listActiveOrganized(runId, groupId, maxResults)
        }
        if (results.isEmpty()) {
            return "Knowledge Base is empty" + (domain?.let { " in domain '$it'" } ?: "") + "."
        }
        return formatIndex(
            entries = results,
            heading = "No semantic query supplied; found ${results.size} organized active KB headers:"
        )
    }

    private suspend fun recordReads(authorityIds: List<String>, args: Args) {
        val repository = contributionGraphRepository ?: return
        require(agentId.isNotBlank()) {
            "Downstream KB search reads require exact reader identity"
        }
        val contextHash = AtlasHashing.hashText(
            json.encodeToString(Args.serializer(), args)
        )
        authorityIds.forEach { authorityId ->
            val authority = requireNotNull(
                repository.canonicalAuthority(runId, groupId, authorityId)
            ) {
                "KB search read lacks immutable canonical authority for $authorityId"
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
