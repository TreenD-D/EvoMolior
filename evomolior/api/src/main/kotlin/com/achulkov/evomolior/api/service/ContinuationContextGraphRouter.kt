package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.embedding.EmbeddingEngine
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Routes continuation context from an archived source run into a compact set of summaries.
 *
 * The router walks the source run's ancestry graph, scores archived artifacts against the new
 * objective, and returns the most relevant summaries for prompt injection.
 */
class ContinuationContextGraphRouter(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val runConfigJson: Json,
    private val resolveRunRoot: (String) -> File
) {
    /**
     * Selects a compact set of routed summaries from the source run graph.
     *
     * @param sourceRunId The run being continued from.
     * @param sourceStartingField The sealed starting field of the source run.
     * @param objective The new objective, used to score candidate context.
     * @param loadSealedStartingField Resolver for sealed starting fields of ancestor runs.
     * @param maxSummaries Maximum number of summaries to return.
     */
    suspend fun routeContinuationContextSummaries(
        sourceRunId: String,
        sourceStartingField: StartingField,
        objective: String,
        loadSealedStartingField: suspend (String) -> StartingField?,
        maxSummaries: Int = 3
    ): List<String> {
        val objectiveEmbedding = runCatching { EmbeddingEngine.embed(objective) }.getOrNull()
        val candidates = mutableListOf<RoutedContextCandidate>()
        graphTraversalOrder(sourceRunId, sourceStartingField, loadSealedStartingField).forEach { (runId, graphDistance) ->
            val graphMessageSummaries = readRunGraphMessage(runId)
                ?.routedContextSummaries
                .orEmpty()
                .filter { it.isNotBlank() }
            if (graphMessageSummaries.isNotEmpty()) {
                graphMessageSummaries.forEach { summary ->
                    candidates += RoutedContextCandidate(runId, "run-graph-message", summary, graphDistance)
                }
                return@forEach
            }

            val archiveDb = runCatching { substrateFactory.get(runId, META_ARCHIVE_GROUP_ID) }
                .getOrElse { return@forEach }
            val entries = runCatching { archiveDb.metaArchiveDao().getByRun(runId) }
                .getOrElse { emptyList() }
            val completionRecord = entries.firstOrNull { it.entryType == RunArchiveService.ENTRY_RUN_COMPLETED }
                ?.let { entry -> runCatching { runConfigJson.decodeFromString(RunCompletionRecord.serializer(), entry.contentJson) }.getOrNull() }
                ?: return@forEach

            val completionGraphMessageSummaries = completionRecord.graphMessage?.routedContextSummaries
                .orEmpty()
                .filter { it.isNotBlank() }
            if (completionGraphMessageSummaries.isNotEmpty()) {
                completionGraphMessageSummaries.forEach { summary ->
                    candidates += RoutedContextCandidate(runId, "run-graph-message", summary, graphDistance)
                }
                return@forEach
            }

            val runRoot = resolveRunRoot(runId)
            File(runRoot, "results/high-level-run-log.txt").takeIf { it.exists() }?.let { file ->
                candidates += RoutedContextCandidate(runId, "high-level-run-log", file.readText(), graphDistance)
            }
            completionRecord.finalAnswerSummary.takeIf { it.isNotBlank() }?.let { summary ->
                candidates += RoutedContextCandidate(runId, "final-answer-summary", summary, graphDistance)
            }
            completionRecord.finalDeliverableHtml?.takeIf { it.isNotBlank() }?.let { html ->
                candidates += RoutedContextCandidate(runId, "final-deliverable-html", html, graphDistance)
            }
            completionRecord.submissionSummariesByGroup.entries.forEach { (groupId, summary) ->
                summary?.takeIf { it.isNotBlank() }?.let { candidates += RoutedContextCandidate(runId, "group-summary:$groupId", it, graphDistance) }
            }
            completionRecord.judgeOutputsByGrader.entries.forEach { (graderId, output) ->
                output.takeIf { it.isNotBlank() }?.let { candidates += RoutedContextCandidate(runId, "judge-output:$graderId", it, graphDistance) }
            }
        }

        val objectiveTokens = tokenizeForRouting(objective)
        return candidates
            .map { candidate -> candidate to routingScore(candidate, objectiveTokens, objectiveEmbedding) }
            .sortedWith(
                compareByDescending<Pair<RoutedContextCandidate, Double>> { it.second }
                    .thenBy { it.first.graphDistance }
                    .thenBy { it.first.kind }
                    .thenBy { it.first.runId }
                    .thenBy { it.first.content }
            )
            .take(maxSummaries)
            .map { (candidate, _) ->
                val headline = candidate.content.lineSequence().firstOrNull().orEmpty().trim().take(240)
                "${candidate.kind}: ${headline.ifBlank { candidate.content.take(240) }}"
            }
    }

    private data class RoutedContextCandidate(
        val runId: String,
        val kind: String,
        val content: String,
        val graphDistance: Int
    )

    private suspend fun graphTraversalOrder(
        sourceRunId: String,
        sourceStartingField: StartingField,
        loadSealedStartingField: suspend (String) -> StartingField?
    ): List<Pair<String, Int>> {
        val ordered = mutableListOf<Pair<String, Int>>()
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val knownFields = mutableMapOf(sourceRunId to sourceStartingField)

        queue.addLast(sourceRunId to 0)

        while (queue.isNotEmpty()) {
            val (runId, distance) = queue.removeFirst()
            if (!visited.add(runId)) continue
            ordered += runId to distance

            val startingField = knownFields[runId] ?: loadSealedStartingField(runId)?.also { knownFields[runId] = it }
            startingField?.runGraphEdges
                ?.sortedByDescending { it.weight }
                ?.forEach { edge ->
                    if (edge.sourceRunId !in visited) {
                        queue.addLast(edge.sourceRunId to distance + 1)
                    }
                }
        }

        return ordered
    }

    private fun tokenizeForRouting(text: String): Set<String> = text
        .lowercase(java.util.Locale.ROOT)
        .split(Regex("[^A-Za-z0-9]+"))
        .filter { it.length >= 4 }
        .toSet()

    private fun routingScore(
        candidate: RoutedContextCandidate,
        objectiveTokens: Set<String>,
        objectiveEmbedding: ByteArray?
    ): Double {
        val content = candidate.content
        if (objectiveTokens.isEmpty() && objectiveEmbedding == null) return 0.0
        val contentTokens = tokenizeForRouting(content)
        val overlap = objectiveTokens.intersect(contentTokens).size
        val priority = when {
            candidate.kind == "run-graph-message" -> 5
            candidate.kind == "high-level-run-log" -> 4
            candidate.kind == "final-answer-summary" -> 3
            candidate.kind == "final-deliverable-html" -> 2
            candidate.kind.startsWith("judge-output:") -> 1
            candidate.kind.startsWith("group-summary:") -> 0
            else -> 0
        }
        val bonus = when {
            content.contains("FINAL DELIVERABLE", ignoreCase = true) -> 3
            content.contains("ROUND SUBMISSION SUMMARY", ignoreCase = true) -> 2
            content.contains("winner", ignoreCase = true) -> 1
            else -> 0
        }
        val proximityBonus = (40 - candidate.graphDistance * 5).coerceAtLeast(0)
        val semanticBonus = objectiveEmbedding?.let { queryEmbedding ->
            runCatching { EmbeddingEngine.cosineSimilarity(queryEmbedding, EmbeddingEngine.embed(content)) }
                .getOrNull()
                ?.coerceAtLeast(0.0)
                ?.times(100.0)
                ?: 0.0
        } ?: 0.0
        return priority * 1000 + overlap * 10 + bonus + proximityBonus + contentTokens.size.coerceAtMost(20) + semanticBonus
    }

    private suspend fun readRunGraphMessage(runId: String): RunGraphMessage? {
        val archiveDb = runCatching { substrateFactory.get(runId, META_ARCHIVE_GROUP_ID) }
            .getOrNull()
            ?: return null
        val graphMessageEntry = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE)
        }.getOrElse { emptyList() }.firstOrNull { it.runId == runId }
            ?: return null
        return runCatching {
            runConfigJson.decodeFromString(RunGraphMessage.serializer(), graphMessageEntry.contentJson)
        }.getOrNull()
    }

    private companion object {
        private const val META_ARCHIVE_GROUP_ID = "__meta_archive__"
    }
}
