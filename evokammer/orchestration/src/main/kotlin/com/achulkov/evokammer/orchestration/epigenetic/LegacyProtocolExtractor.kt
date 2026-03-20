package com.achulkov.evokammer.orchestration.epigenetic

import com.achulkov.evokammer.substrate.db.dao.EventLogDao
import com.achulkov.evokammer.substrate.db.dao.LegacyProtocolDao
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Extracts and persists [LegacyProtocolEntity] records from top-performing agents
 * at the end of each generation.
 *
 * The Legacy Protocol captures:
 * - **Reasoning posture** — summary of effective reasoning approaches (derived from Death Notes)
 * - **Tool heuristics** — tools the agent used successfully (from event log TOOL_EXEC)
 * - **Failure map** — tool/action combinations that consistently failed
 * - **KG habits** — KG relations the agent focused on (from promoted KG_FACT submissions)
 *
 * Per spec:
 * - Extracted from the top 20% of agents by fitness score.
 * - Injected as a privileged context prefix at spawn time (via [AgentBootContext.legacyProtocol]).
 * - Expires after 2 generations.
 * - Outliers never receive Legacy Protocols.
 * - Failure maps are promoted to Dead Ends Registry when the protocol expires.
 *
 * @param legacyProtocolDao DAO for persisting extracted protocols.
 * @param stagingDao        DAO for reading Death Notes and promoted submissions.
 * @param eventLogDao       DAO for reading agent action history.
 */
class LegacyProtocolExtractor(
    private val legacyProtocolDao: LegacyProtocolDao,
    private val stagingDao: StagingDao,
    private val eventLogDao: EventLogDao
) {

    /**
     * Extracts Legacy Protocols for all top-performing agents in [fitnessScores].
     *
     * One protocol is created per unique role. If multiple top agents share a role,
     * their protocols are merged via [CrossoverEngine] before persistence.
     *
     * @param fitnessScores Sorted list (highest first) from [FitnessScorer].
     * @param topAgentIds   Agent IDs selected for extraction (typically top 20%).
     * @param currentGeneration Generation in which these agents ran.
     * @param runId         Current run ID.
     * @param groupId       Current group ID.
     * @return List of extracted [LegacyProtocolEntity] records (one per role).
     */
    suspend fun extract(
        fitnessScores: List<FitnessScore>,
        topAgentIds: Set<String>,
        currentGeneration: Int,
        runId: String,
        groupId: String
    ): List<LegacyProtocolEntity> {
        val extracted = mutableListOf<LegacyProtocolEntity>()
        val expiresAt = currentGeneration + 2

        // Group top agents by role (from staging submissions)
        val agentRoles: Map<String, String> = stagingDao
            .getDistinctAgentIds(runId, groupId, currentGeneration)
            .filter { it in topAgentIds }
            .associateWith { agentId ->
                // Infer role from the agent's most recent staging submission
                stagingDao.getByAgent(runId, groupId, agentId)
                    .firstOrNull()?.role ?: "Unknown"
            }

        val roleGroups: Map<String, List<String>> = agentRoles
            .entries
            .groupBy({ it.value }, { it.key })

        for ((role, agentIds) in roleGroups) {
            val protocol = buildProtocolForRole(
                role = role,
                agentIds = agentIds,
                currentGeneration = currentGeneration,
                expiresAt = expiresAt,
                runId = runId,
                groupId = groupId
            )
            legacyProtocolDao.insert(protocol)
            extracted.add(protocol)

            // Emit event
            val event = EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                groupId = groupId,
                agentId = null,
                eventType = EventTypes.LEGACY_PROTOCOL_EXTRACTED,
                payloadJson = """{"protocolId":"${protocol.id}","role":"$role","generation":$currentGeneration}""",
                generation = currentGeneration,
                timestampMs = System.currentTimeMillis()
            )
            eventLogDao.insert(event)

            Napier.i("LegacyProtocolExtractor: extracted protocol for role=$role from ${agentIds.size} agents (gen=$currentGeneration expires=$expiresAt)")
        }

        return extracted
    }

    private suspend fun buildProtocolForRole(
        role: String,
        agentIds: List<String>,
        currentGeneration: Int,
        expiresAt: Int,
        runId: String,
        groupId: String
    ): LegacyProtocolEntity {
        // Collect Death Notes for reasoning posture
        val deathNotes = agentIds.flatMap { agentId ->
            stagingDao.getByAgent(runId, groupId, agentId)
                .filter { it.type == "DEATH_NOTE" }
                .map { it.content }
        }
        val reasoningPosture = summariseDeathNotes(deathNotes, role)

        // Collect successful tool executions for tool heuristics
        val toolExecutions = agentIds.flatMap { agentId ->
            eventLogDao.getLatestByType(runId, groupId, "TOOL_EXEC", 50)
                .filter { it.agentId == agentId && it.payloadJson.contains("\"success\":true") }
                .map { extractToolName(it.payloadJson) }
        }.groupBy { it }.mapValues { it.value.size }
        val toolHeuristics = buildToolHeuristics(toolExecutions)

        // Collect failure patterns
        val failedToolExecutions = agentIds.flatMap { agentId ->
            eventLogDao.getLatestByType(runId, groupId, "TOOL_EXEC", 50)
                .filter { it.agentId == agentId && it.payloadJson.contains("\"success\":false") }
                .map { extractToolName(it.payloadJson) }
        }.groupBy { it }.mapValues { it.value.size }
        val failureMap = buildFailureMap(failedToolExecutions)

        // Collect KG habits from promoted KG_FACT submissions
        val kgFacts = agentIds.flatMap { agentId ->
            stagingDao.getByAgent(runId, groupId, agentId)
                .filter { it.type == "KG_FACT" && it.status == "promoted" }
                .map { it.content }
        }
        val kgHabits = buildKgHabits(kgFacts, role)

        return LegacyProtocolEntity(
            id = UUID.randomUUID().toString(),
            role = role,
            extractedFromGeneration = currentGeneration,
            expiresAtGeneration = expiresAt,
            reasoningPosture = reasoningPosture,
            toolHeuristicsJson = toolHeuristics,
            failureMapJson = failureMap,
            kgHabitsJson = kgHabits,
            sourceAgentIds = agentIds.joinToString(","),
            groupId = groupId,
            runId = runId,
            createdAtMs = System.currentTimeMillis()
        )
    }

    private fun summariseDeathNotes(deathNotes: List<String>, role: String): String {
        if (deathNotes.isEmpty()) {
            return "No Death Notes available. Standard $role reasoning posture applies."
        }
        // Combine Death Note content into a compact reasoning posture summary
        val summary = deathNotes
            .take(3) // At most 3 death notes to keep the protocol compact
            .joinToString("\n\n---\n\n") { it.take(500) }
        return "Reasoning posture from generation $role agents:\n$summary"
    }

    private fun buildToolHeuristics(toolCounts: Map<String, Int>): String {
        if (toolCounts.isEmpty()) return "{}"
        val sorted = toolCounts.entries.sortedByDescending { it.value }
        return buildString {
            append("{")
            sorted.forEachIndexed { i, (tool, count) ->
                if (i > 0) append(",")
                append(""""$tool":$count""")
            }
            append("}")
        }
    }

    private fun buildFailureMap(failedCounts: Map<String, Int>): String {
        if (failedCounts.isEmpty()) return "{}"
        val sorted = failedCounts.entries.sortedByDescending { it.value }
        return buildString {
            append("{")
            sorted.forEachIndexed { i, (tool, count) ->
                if (i > 0) append(",")
                append(""""$tool":{"failures":$count,"note":"Avoid repeated calls with same args"}""")
            }
            append("}")
        }
    }

    private fun buildKgHabits(kgFacts: List<String>, role: String): String {
        if (kgFacts.isEmpty()) return "{\"note\":\"No KG contributions yet\"}"
        // Extract relation types from the fact content
        val relations = kgFacts
            .mapNotNull { content ->
                content.lines()
                    .firstOrNull { it.startsWith("RELATION:") }
                    ?.removePrefix("RELATION:")?.trim()
            }
            .groupBy { it }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
        return buildString {
            append("""{"role":"$role","dominantRelations":[""")
            relations.take(5).forEachIndexed { i, (rel, count) ->
                if (i > 0) append(",")
                append("""{"relation":"$rel","count":$count}""")
            }
            append("]}")
        }
    }

    private fun extractToolName(payloadJson: String): String {
        // Extract tool name from payload JSON like {"toolName":"read_kb","success":true}
        val match = Regex(""""toolName"\s*:\s*"([^"]+)"""").find(payloadJson)
        return match?.groupValues?.get(1) ?: "unknown_tool"
    }
}
