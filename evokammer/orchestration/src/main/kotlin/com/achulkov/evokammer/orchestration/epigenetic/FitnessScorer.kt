package com.achulkov.evokammer.orchestration.epigenetic

import com.achulkov.evokammer.substrate.db.dao.EventLogDao
import com.achulkov.evokammer.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evokammer.substrate.db.dao.StagingDao
import com.achulkov.evokammer.substrate.db.dao.ToolRegistryDao
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable

/**
 * Post-mortem fitness scorer for completed agents.
 *
 * Fitness is computed as a weighted sum of five signals:
 * 1. **Citation score** — number of times this agent's promoted KB entries were cited by later agents.
 * 2. **KG contribution quality** — weighted contribution of promoted KG facts.
 * 3. **Tool adoption score** — number of distinct agents that adopted tools authored by this agent.
 * 4. **Staging success ratio** — promoted / (promoted + rejected) staging submissions.
 * 5. **Task completion** — whether the WiP task was completed, abandoned, or left unclaimed.
 *
 * The top 20% of agents by fitness score are eligible for Legacy Protocol extraction.
 *
 * @param eventLogDao      For counting event types.
 * @param knowledgeBaseDao For counting citation signals.
 * @param stagingDao       For counting staging outcomes.
 * @param toolRegistryDao  For counting tool adoption.
 */
class FitnessScorer(
    private val eventLogDao: EventLogDao,
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val stagingDao: StagingDao,
    private val toolRegistryDao: ToolRegistryDao
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
        // 1. Citation score — proxy: count KB entries authored by this agent
        //    Real citation count would require tracking via the event log CITE events.
        val promotedKbCount = stagingDao.getPromotedCountForAgent(runId, groupId, agentId)
        val citationScore = (promotedKbCount * 2.0).coerceAtMost(20.0)

        // 2. KG contribution quality — count promoted KG_FACT submissions
        val promotedKgCount = stagingDao.getPromotedKgFactCountForAgent(runId, groupId, agentId)
        val kgScore = (promotedKgCount * 3.0).coerceAtMost(15.0)

        // 3. Tool adoption — count tools authored by this agent that have been adopted (executed by others)
        val toolAdoptionCount = toolRegistryDao.getAdoptedToolCountForAgent(runId, groupId, agentId)
        val toolScore = (toolAdoptionCount * 5.0).coerceAtMost(20.0)

        // 4. Staging success ratio
        val totalSubmissions = stagingDao.getTotalCountForAgent(runId, groupId, agentId)
        val stagingRatio = if (totalSubmissions > 0) {
            promotedKbCount.toDouble() / totalSubmissions
        } else 0.0
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
     * Only agents with at least one staging submission are included.
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
