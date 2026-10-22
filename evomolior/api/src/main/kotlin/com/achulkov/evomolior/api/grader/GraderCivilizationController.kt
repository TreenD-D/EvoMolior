package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.epigenetic.FitnessScorer
import com.achulkov.evomolior.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import java.util.UUID

/**
 * Owns grader-local generation selection, WiP claims, Legacy injection, and epigenetic rollover.
 *
 * The controller uses the grader group's existing isolated substrate tables. It does not inspect
 * competing-group databases; canon snapshots remain the only judging input.
 *
 * @param db Isolated grader-group substrate.
 * @param runId Run identifier.
 * @param graderGroupId Grader civilisation identifier.
 */
class GraderCivilizationController(
    private val db: EvoMoliorDatabase,
    private val runId: String,
    private val graderGroupId: String
) {
    /**
     * Selects criteria agents and creates their generation-scoped WiP tasks.
     *
     * Without an accepted baseline, a researcher and builder are retained. Rejection pressure
     * selects an additional researcher, while failure telemetry selects an additional builder.
     * When [refineFromBaseline] is set and neither pressure is present, the previous round's
     * accepted criteria still hold, so a single builder revises them instead of re-researching
     * every dimension (the October 2026 logs spent ~7.5 minutes per researcher per round).
     *
     * @param generation One-based grader generation.
     * @param scoringDimensions Active scoring dimensions.
     * @param refineFromBaseline Whether an accepted previous-round methodology can be revised.
     */
    suspend fun planGeneration(
        generation: Int,
        scoringDimensions: List<ScoringDimension>,
        refineFromBaseline: Boolean = false
    ): GraderGenerationPlan {
        db.wipTaskDao().releaseClaimsFromGeneration(runId, graderGroupId, generation)
        val priorEntries = db.stagingDao().getAll(runId, graderGroupId)
            .filter { it.generation == generation - 1 }
        val rejectionPressure = priorEntries.count { it.status == "rejected" } >
            priorEntries.count { it.status == "promoted" }
        val failurePressure = priorEntries.any { it.type == "FAILURE_TELEMETRY" }
        val stableRefinement = refineFromBaseline && !rejectionPressure && !failurePressure
        val roles = buildList {
            if (!stableRefinement) add(Role.GraderResearcher)
            add(Role.GraderBuilder)
            if (rejectionPressure) add(Role.GraderResearcher)
            if (failurePressure) add(Role.GraderBuilder)
        }
        val dimensionSummary = scoringDimensions.joinToString(", ") { it.name }
        val assignments = roles.mapIndexedNotNull { index, role ->
            val task = GraderAgentAssignment(
                role = role,
                taskId = "grader-wip-g${generation}-${role.name.lowercase()}-$index",
                taskDescription = when {
                    refineFromBaseline && role == Role.GraderResearcher ->
                        "Revise the accepted generation-${generation - 1} criteria for generation $generation " +
                            "where rejections or failure telemetry show gaps, across: $dimensionSummary."
                    refineFromBaseline && role == Role.GraderBuilder ->
                        "Improve the accepted generation-${generation - 1} rubric tests and typed methodology " +
                            "for generation $generation across: $dimensionSummary."
                    role == Role.GraderResearcher ->
                        "Research adversarial evidence thresholds for generation $generation across: $dimensionSummary."
                    role == Role.GraderBuilder ->
                        "Build executable or mechanically checkable rubric tests for generation $generation across: $dimensionSummary."
                    else -> error("Unsupported grader selection role: $role")
                }
            )
            db.wipTaskDao().insertIfAbsent(
                WipTaskEntity(
                    nodeId = task.taskId,
                    runId = runId,
                    groupId = graderGroupId,
                    description = task.taskDescription,
                    lastChangedGeneration = generation
                )
            )
            val persistedTask = db.wipTaskDao().getByNodeId(task.taskId, runId, graderGroupId)
            task.takeUnless { persistedTask?.status == "completed" }
        }
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                eventType = EventTypes.SELECTOR_DECISION,
                groupId = graderGroupId,
                runId = runId,
                generation = generation,
                payloadJson = """{"civilization":"grader","roles":[${
                    roles.joinToString(",") { "\"${it.name}\"" }
                }],"rejectionPressure":$rejectionPressure,"failurePressure":$failurePressure,""" +
                    """"refineFromBaseline":$refineFromBaseline}"""
            )
        )
        return GraderGenerationPlan(generation, assignments)
    }

    /**
     * Atomically claims an assignment for its newly created agent.
     *
     * @param assignment Selected role and task.
     * @param agentId Agent identity generated by the grader factory.
     * @param generation One-based grader generation.
     */
    suspend fun claim(
        assignment: GraderAgentAssignment,
        agentId: String,
        generation: Int
    ) {
        check(
            db.wipTaskDao().claimTask(
                assignment.taskId,
                runId,
                graderGroupId,
                agentId,
                generation
            ) == 1
        ) {
            "Grader WiP task ${assignment.taskId} could not be claimed by $agentId"
        }
    }

    /**
     * Returns the active Legacy Protocol context for a selected grader role.
     *
     * @param role Selected grader role.
     * @param generation One-based grader generation.
     */
    suspend fun legacyContext(role: Role, generation: Int): String? =
        db.legacyProtocolDao()
            .getLatestForRole(runId, graderGroupId, role.name, generation)
            ?.let { protocol ->
                buildString {
                    appendLine(protocol.reasoningPosture)
                    appendLine("Tool heuristics: ${protocol.toolHeuristicsJson}")
                    appendLine("Failure map: ${protocol.failureMapJson}")
                    appendLine("KG habits: ${protocol.kgHabitsJson}")
                    append("Expires after generation ${protocol.expiresAtGeneration}.")
                }
            }

    /**
     * Scores reviewed grader work, extracts role Legacy Protocols, and archives expired failures.
     *
     * @param generation One-based grader generation just reviewed by the Critic.
     * @param allowLegacyExtraction Whether generic agent-level Legacy is authoritative.
     * @return Generation rollover summary.
     */
    suspend fun completeGeneration(
        generation: Int,
        allowLegacyExtraction: Boolean = true
    ): GraderGenerationRollover {
        val scorer = FitnessScorer(
            db.eventLogDao(),
            db.knowledgeBaseDao(),
            db.stagingDao(),
            db.toolRegistryDao(),
            com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository(db)
        )
        val scores = scorer.scoreGeneration(runId, graderGroupId, generation)
        val top = if (scores.isEmpty()) emptyList() else scorer.topPercentile(scores)
        val generationAlreadyExtracted = db.eventLogDao()
            .getByGeneration(runId, graderGroupId, generation)
            .any { it.eventType == EventTypes.LEGACY_PROTOCOL_EXTRACTED }
        val protocols = if (!allowLegacyExtraction || top.isEmpty() || generationAlreadyExtracted) {
            emptyList()
        } else {
            LegacyProtocolExtractor(
                db.legacyProtocolDao(),
                db.stagingDao(),
                db.eventLogDao()
            ).extract(
                fitnessScores = top,
                topAgentIds = top.mapTo(linkedSetOf()) { it.agentId },
                currentGeneration = generation,
                runId = runId,
                groupId = graderGroupId
            )
        }
        val expired = db.legacyProtocolDao().getExpired(runId, graderGroupId, generation)
        var deadEnds = 0
        expired.filter { it.failureMapJson.isNotBlank() && it.failureMapJson != "{}" }
            .forEach { protocol ->
                val deadEndId = "grader-dead-end-${protocol.id}"
                if (db.knowledgeBaseDao().getById(deadEndId) != null) return@forEach
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = deadEndId,
                        content = "GRADER DEAD END [${protocol.role}]: ${protocol.failureMapJson}",
                        sourceAgentId = "grader-epigenetic-archive",
                        generation = generation,
                        trustScore = 1.0,
                        domain = "grader_dead_ends",
                        status = "active",
                        groupId = graderGroupId,
                        runId = runId,
                        createdAtMs = System.currentTimeMillis(),
                        isDeadEnd = true
                    )
                )
                deadEnds++
            }
        return GraderGenerationRollover(
            scoredAgents = scores.size,
            extractedProtocols = protocols.size,
            expiredProtocols = expired.size,
            deadEndsInserted = deadEnds
        )
    }
}

/** Selected grader generation. */
data class GraderGenerationPlan(
    val generation: Int,
    val assignments: List<GraderAgentAssignment>
)

/** One selected grader agent and its generation-scoped WiP task. */
data class GraderAgentAssignment(
    val role: Role,
    val taskId: String,
    val taskDescription: String
)

/** Epigenetic rollover outcome for one grader generation. */
data class GraderGenerationRollover(
    val scoredAgents: Int,
    val extractedProtocols: Int,
    val expiredProtocols: Int,
    val deadEndsInserted: Int
)
