package com.achulkov.evokammer.orchestration.epigenetic

import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Crossover Engine for Legacy Protocol merging.
 *
 * When multiple top-performing agents share the same role, their individual Legacy Protocols
 * are merged using the crossover rules:
 *
 * - **Reasoning posture**: concatenated with a separator, then truncated to max length.
 * - **Tool heuristics**: JSON merged; counts summed for shared tools.
 * - **Failure map**: JSON merged; failure counts summed for shared tools.
 * - **KG habits**: dominant relations merged by total count across both protocols.
 *
 * The result is a single merged protocol that supersedes individual ones.
 * If only one protocol exists for a role, no crossover is needed.
 */
class CrossoverEngine {

    /**
     * Merges two [LegacyProtocolEntity] records of the same role into one.
     *
     * The merged entity is a new record referencing both source agent ID sets.
     *
     * @param a First protocol.
     * @param b Second protocol.
     * @return A merged [LegacyProtocolEntity].
     */
    fun crossover(a: LegacyProtocolEntity, b: LegacyProtocolEntity): LegacyProtocolEntity {
        require(a.role == b.role) { "Cannot crossover protocols of different roles: ${a.role} vs ${b.role}" }
        require(a.groupId == b.groupId) { "Cannot crossover protocols from different groups" }

        Napier.d("CrossoverEngine: merging ${a.id} + ${b.id} for role=${a.role}")

        val mergedReasoningPosture = mergeReasoningPosture(a.reasoningPosture, b.reasoningPosture)
        val mergedToolHeuristics = mergeJsonCounts(a.toolHeuristicsJson, b.toolHeuristicsJson)
        val mergedFailureMap = mergeFailureMaps(a.failureMapJson, b.failureMapJson)
        val mergedKgHabits = mergeKgHabits(a.kgHabitsJson, b.kgHabitsJson)
        val mergedSourceIds = "${a.sourceAgentIds},${b.sourceAgentIds}"

        return LegacyProtocolEntity(
            id = UUID.randomUUID().toString(),
            role = a.role,
            extractedFromGeneration = maxOf(a.extractedFromGeneration, b.extractedFromGeneration),
            expiresAtGeneration = minOf(a.expiresAtGeneration, b.expiresAtGeneration), // Expire earliest
            reasoningPosture = mergedReasoningPosture,
            toolHeuristicsJson = mergedToolHeuristics,
            failureMapJson = mergedFailureMap,
            kgHabitsJson = mergedKgHabits,
            sourceAgentIds = mergedSourceIds,
            groupId = a.groupId,
            runId = a.runId,
            createdAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Merges a list of protocols for the same role into one by sequential crossover.
     * If the list is empty, returns null. If it has one item, returns it directly.
     */
    fun mergeAll(protocols: List<LegacyProtocolEntity>): LegacyProtocolEntity? {
        return when {
            protocols.isEmpty() -> null
            protocols.size == 1 -> protocols.first()
            else -> protocols.reduce { acc, p -> crossover(acc, p) }
        }
    }

    // ─────────── Private helpers ───────────

    private fun mergeReasoningPosture(a: String, b: String): String {
        val combined = "$a\n\n---\n\n$b"
        return combined.take(4000) // Keep it compact for system prompt injection
    }

    /**
     * Merges two JSON objects of the form `{"key": count}` by summing counts for shared keys.
     */
    private fun mergeJsonCounts(jsonA: String, jsonB: String): String {
        val mapA = parseJsonCounts(jsonA)
        val mapB = parseJsonCounts(jsonB)
        val merged = (mapA.keys + mapB.keys).toSet().associateWith { key ->
            (mapA[key] ?: 0) + (mapB[key] ?: 0)
        }
        return buildString {
            append("{")
            merged.entries.sortedByDescending { it.value }.forEachIndexed { i, (k, v) ->
                if (i > 0) append(",")
                append(""""$k":$v""")
            }
            append("}")
        }
    }

    /**
     * Merges failure maps, summing failure counts per tool.
     *
     * Input format: `{"toolName":{"failures":N,"note":"..."}}`
     */
    private fun mergeFailureMaps(jsonA: String, jsonB: String): String {
        val mapA = parseFailureMap(jsonA)
        val mapB = parseFailureMap(jsonB)
        val merged = (mapA.keys + mapB.keys).toSet().associateWith { key ->
            (mapA[key] ?: 0) + (mapB[key] ?: 0)
        }
        return buildString {
            append("{")
            merged.entries.sortedByDescending { it.value }.forEachIndexed { i, (tool, count) ->
                if (i > 0) append(",")
                append(""""$tool":{"failures":$count,"note":"Avoid repeated calls with same args"}""")
            }
            append("}")
        }
    }

    /**
     * Merges KG habits by combining dominant relation counts from both protocols.
     */
    private fun mergeKgHabits(jsonA: String, jsonB: String): String {
        // Extract relation entries from both
        val relA = parseKgRelations(jsonA)
        val relB = parseKgRelations(jsonB)
        val merged = (relA.keys + relB.keys).toSet().associateWith { key ->
            (relA[key] ?: 0) + (relB[key] ?: 0)
        }
        val topRelations = merged.entries.sortedByDescending { it.value }.take(8)
        return buildString {
            append("""{"dominantRelations":[""")
            topRelations.forEachIndexed { i, (rel, count) ->
                if (i > 0) append(",")
                append("""{"relation":"$rel","count":$count}""")
            }
            append("]}")
        }
    }

    /** Parses `{"key":N}` → `Map<String, Int>` */
    private fun parseJsonCounts(json: String): Map<String, Int> {
        if (json == "{}" || json.isBlank()) return emptyMap()
        return try {
            Regex(""""(\w+)"\s*:\s*(\d+)""").findAll(json)
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Parses failure map `{"tool":{"failures":N,...}}` → `Map<String, Int>` */
    private fun parseFailureMap(json: String): Map<String, Int> {
        if (json == "{}" || json.isBlank()) return emptyMap()
        return try {
            Regex(""""(\w+)"\s*:\s*\{"failures"\s*:\s*(\d+)""").findAll(json)
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Parses KG habits `{"dominantRelations":[{"relation":"X","count":N},...]}` → `Map<String, Int>` */
    private fun parseKgRelations(json: String): Map<String, Int> {
        if (json.isBlank()) return emptyMap()
        return try {
            Regex(""""relation"\s*:\s*"([^"]+)"\s*,\s*"count"\s*:\s*(\d+)""").findAll(json)
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
