package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Koog tool: write the mandatory Death Note on agent termination.
 *
 * Cost: 0 (always free, even when budget is at floor).
 * Death Notes are automatically promoted without Critic review.
 *
 * @param stagingDao DAO for the current group's staging area.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param agentId The current agent ID.
 * @param role The agent's role name.
 * @param generation The current generation number.
 */
class WriteDeathNoteTool(
    private val stagingDao: StagingDao,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int
) : SimpleTool<WriteDeathNoteTool.Args>(
    argsType = typeToken<Args>(),
    name = "write_death_note",
    description = """
        Write your Death Note before termination. MANDATORY final action.
        Death Notes are auto-promoted without Critic review and preserved permanently.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val summary: String,
        val whatWorked: String,
        val whatFailed: String,
        val recommendations: String,
        val kgHabits: String? = null,
        val toolObservations: String? = null
    )

    override suspend fun execute(args: Args): String {
        val payload = DeathNotePayload(
            summary = args.summary,
            whatWorked = args.whatWorked,
            whatFailed = args.whatFailed,
            recommendations = args.recommendations,
            kgHabits = args.kgHabits,
            toolObservations = args.toolObservations,
            role = role,
            generation = generation
        )

        val submissionId = UUID.randomUUID().toString()
        val entity = StagingSubmissionEntity(
            id = submissionId,
            agentId = agentId,
            role = role,
            type = "DEATH_NOTE",
            content = Json.encodeToString(payload),
            wipTaskRef = null,
            speculative = false,
            outlier = false,
            budgetSignature = "DEATH_NOTE:$agentId:${System.currentTimeMillis()}",
            status = "promoted",
            initialTrustScore = 0.8,
            generation = generation,
            groupId = groupId,
            runId = runId,
            submittedAtMs = System.currentTimeMillis(),
            reviewedAtMs = System.currentTimeMillis()
        )
        stagingDao.insert(entity)

        return "Death Note written (ID: $submissionId). Your contributions are preserved."
    }

    @Serializable
    private data class DeathNotePayload(
        val summary: String,
        val whatWorked: String,
        val whatFailed: String,
        val recommendations: String,
        val kgHabits: String?,
        val toolObservations: String?,
        val role: String,
        val generation: Int
    )
}
