package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.embedding.EmbeddingEngine
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import kotlinx.serialization.Serializable
import java.util.Locale

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
    private val maxCandidateLoad: Int = 200
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
    @Serializable
    data class Args(
        /** Natural-language search query. */
        val query: String,
        /** Optional domain filter. If null, searches all domains. */
        val domain: String? = null,
        /** Maximum results to return (default: 5). */
        val maxResults: Int = 5
    )

    override suspend fun execute(args: Args): String {
        // 1. Compute query embedding
        val queryEmbedding = EmbeddingEngine.embed(args.query)

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
        val queryTokens = args.query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
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
            .take(args.maxResults)

        if (merged.isEmpty()) {
            return "No semantically related KB entries found for: \"${args.query}\""
        }

        // 7. Increment citation counts
        merged.forEach { (entry, _) -> knowledgeBaseDao.incrementCitationCount(entry.id) }

        // 8. Format response
        return buildString {
            appendLine("Found ${merged.size} semantically related KB entries (query: \"${args.query}\"):")
            merged.forEachIndexed { i, (entry, score) ->
                appendLine("\n--- Entry ${i + 1} ---")
                appendLine("ID: ${entry.id}")
                appendLine("Domain: ${entry.domain}")
                appendLine("Similarity: ${String.format(Locale.US, "%.3f", score)}")
                appendLine("Trust Score: ${String.format(Locale.US, "%.2f", entry.trustScore)}")
                appendLine("Generation: ${entry.generation}")
                appendLine("Citations: ${entry.citationCount}")
                if (entry.isDeadEnd) appendLine("DEAD END: This approach has been tried and failed.")
                if (entry.embedding == null) appendLine("[Note: embedding not yet computed — keyword fallback match]")
                appendLine("Content:")
                appendLine(
                    entry.content.take(2000).let {
                        if (entry.content.length > 2000) "$it\n[...truncated]" else it
                    }
                )
            }
        }
    }
}
