package com.achulkov.evomolior.orchestration.epigenetic

import com.achulkov.evomolior.substrate.db.dao.EventLogDao
import com.achulkov.evomolior.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.dao.ToolRegistryDao
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable

/**
 * Post-mortem fitness scorer for completed agents.
 *
 * Fitness is computed as a weighted sum of five signals:
 * 1. **Citation score** — number of times this agent's promoted KB entries were cited by later agents.
 * 2. **KG contribution quality** — weighted contribution of promoted KG facts.
 * 3. **Tool fitness score** — delayed causal fitness from evidence-bound exact-version reuse.
 * 4. **Staging success ratio** — promoted / (promoted + rejected) staging submissions.
 * 5. **Task completion** — whether the WiP task was completed, abandoned, or left unclaimed.
 *
 * The top 20% of agents by fitness score are eligible for Legacy Protocol extraction.
 *
 * @param eventLogDao      For counting event types.
 * @param knowledgeBaseDao For counting citation signals.
 * @param stagingDao       For counting staging outcomes.
 * @param toolRegistryDao  For resolving immutable versions authored by the agent.
 * @param toolInfrastructureRepository Exact delayed tool-fitness authority.
 */
class FitnessScorer(
    private val eventLogDao: EventLogDao,
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val stagingDao: StagingDao,
    private val toolRegistryDao: ToolRegistryDao,
    private val toolInfrastructureRepository: ToolInfrastructureRepository? = null
) {

    /**
     * Computes the fitness score for [agentId] at the end of generation [generation].
     *
     * @return A [FitnessScore] with the total and per-dimension breakdown.
     */
    suspend fun score(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int
    ): FitnessScore {
        // 1. Citation score — canonical reuse, including citations retained on archived versions.
        val citationCount = knowledgeBaseDao.getCitationCountForAgent(runId, groupId, agentId)
        val citationScore = (citationCount * 2.0).coerceAtMost(20.0)

        // 2. KG contribution quality — count promoted KG_FACT submissions
        val promotedKgCount = stagingDao.getPromotedKgFactCountForAgent(runId, groupId, agentId)
        val kgScore = (promotedKgCount * 3.0).coerceAtMost(15.0)

        // 3. Tool fitness — only exact evidence-bound reuse can contribute.
        val toolScore = toolInfrastructureRepository?.let { repository ->
            toolRegistryDao.getByAuthor(runId, groupId, agentId).sumOf { tool ->
                repository.fitnessForVersion(ToolVersionId(tool.id))
                    .filter { it.throughRound <= generation }
                    .maxByOrNull { it.throughRound }
                    ?.fitnessValue
                    ?: 0.0
            }.coerceIn(-20.0, 20.0)
        } ?: 0.0

        // 4. Staging success ratio
        val stagingRatio = stagingDao
            .getAcceptanceRateForAgent(runId, groupId, agentId)
            ?: 0.0
        val stagingScore = (stagingRatio * 25.0).coerceAtMost(25.0)

        // 5. Task completion — check event log for TASK_COMPLETE event from this agent
        val taskCompletionCount = eventLogDao.countByAgentAndType(runId, groupId, agentId, "WIP_COMPLETE")
        val taskScore = (taskCompletionCount * 20.0).coerceAtMost(20.0)

        val total = citationScore + kgScore + toolScore + stagingScore + taskScore

        Napier.d(
            "FitnessScorer: $agentId gen=$generation " +
                "citation=$citationScore kg=$kgScore tool=$toolScore " +
                "staging=$stagingScore task=$taskScore total=$total"
        )

        return FitnessScore(
            agentId = agentId,
            generation = generation,
            citationScore = citationScore,
            kgContributionScore = kgScore,
            toolAdoptionScore = toolScore,
            stagingSuccessScore = stagingScore,
            taskCompletionScore = taskScore,
            total = total
        )
    }

    /**
     * Scores all agents from [generation] and returns them sorted by fitness (highest first).
     * Only agents with at least one substantive reviewed staging submission are included.
     */
    suspend fun scoreGeneration(
        runId: String,
        groupId: String,
        generation: Int
    ): List<FitnessScore> {
        val agentIds = stagingDao.getDistinctAgentIds(runId, groupId, generation)
        return agentIds
            .map { agentId -> score(runId, groupId, agentId, generation) }
            .sortedByDescending { it.total }
    }

    /**
     * Returns the top [percentile] fraction of agents from the scored list.
     * Used to select candidates for Legacy Protocol extraction.
     */
    fun topPercentile(scores: List<FitnessScore>, percentile: Double = 0.20): List<FitnessScore> {
        val count = (scores.size * percentile).toInt().coerceAtLeast(1)
        return scores.take(count)
    }
}

/**
 * Fitness score for a single agent instance.
 *
 * @param total Max ~100 — used for Legacy Protocol extraction eligibility threshold.
 */
@Serializable
data class FitnessScore(
    val agentId: String,
    val generation: Int,
    val citationScore: Double,
    val kgContributionScore: Double,
    val toolAdoptionScore: Double,
    val stagingSuccessScore: Double,
    val taskCompletionScore: Double,
    val total: Double
)
