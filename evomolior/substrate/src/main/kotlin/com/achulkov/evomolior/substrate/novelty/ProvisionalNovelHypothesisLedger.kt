package com.achulkov.evomolior.substrate.novelty

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.HypothesisStatus
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import kotlinx.serialization.json.Json

/**
 * Typed read path for hypotheses that Critic promoted only as provisional artifacts.
 *
 * This ledger deliberately reads from the existing staging history rather than introducing a
 * second Room table. It fails closed if historical content violates the hypothesis contract.
 *
 * @param stagingDao Existing staging persistence for one isolated group database.
 * @param json Strict JSON decoder for the structured hypothesis payload.
 */
class ProvisionalNovelHypothesisLedger(
    private val stagingDao: StagingDao,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }
) {
    /**
     * Returns bounded promoted hypotheses eligible for provisional propagation.
     *
     * @param runId Run to query.
     * @param groupId Group to query.
     * @param limit Maximum number of hypotheses.
     * @return Typed, hash-verified provisional hypotheses in stable order.
     */
    suspend fun getPromoted(
        runId: String,
        groupId: String,
        limit: Int = 100
    ): List<ProvisionalNovelHypothesis> {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(limit > 0) { "limit must be positive" }
        return stagingDao.getPromotedHypotheses(runId, groupId, limit).map { submission ->
            require(submission.type == "HYPOTHESIS") {
                "Hypothesis ledger received a non-hypothesis staging row"
            }
            require(submission.status == "promoted" && submission.speculative) {
                "Hypothesis ledger received a non-promoted or non-speculative row"
            }
            val hypothesis = json.decodeFromString(
                ProvisionalNovelHypothesis.serializer(),
                submission.content
            )
            require(
                hypothesis.runId == submission.runId &&
                    hypothesis.groupId == AtlasGroupId(submission.groupId) &&
                    hypothesis.agentId == AtlasAgentId(submission.agentId) &&
                    hypothesis.round == submission.generation &&
                    hypothesis.status == HypothesisStatus.PROPOSED &&
                    hypothesis.hasValidHash()
            ) {
                "Promoted hypothesis ${submission.id} failed identity or hash validation"
            }
            hypothesis
        }
    }
}
