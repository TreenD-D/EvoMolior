package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.RepairStrategy
import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1

/** Repair priority and sealed strategy assignments for one opportunity lineage. */
data class RepairPriorityPlan(
    val priorityNodeIdsByGroup: Map<AtlasGroupId, Set<NodeId>>,
    val approachVectors: Map<AtlasGroupId, ApproachVector>
)

/**
 * Keeps diagnosed mandatory tool work high-priority while bounding one lineage to three attempts.
 */
class RepairPriorityPlanner {
    /**
     * Builds deterministic priority and distinct strategy authority.
     *
     * @param runId Owning run.
     * @param round Current round.
     * @param nodeId Mandatory tool node.
     * @param opportunityId Sealed opportunity.
     * @param groupIds Competing groups receiving repair work.
     * @param diagnostics Typed diagnostics available to this lineage.
     * @param priorAttempts Existing attempt records for this opportunity.
     */
    fun plan(
        runId: String,
        round: Int,
        nodeId: NodeId,
        opportunityId: String,
        groupIds: Set<AtlasGroupId>,
        diagnostics: List<ToolRepairDiagnosticV1>,
        priorAttempts: List<ToolRepairAttemptRecord>
    ): RepairPriorityPlan {
        require(runId.isNotBlank() && opportunityId.isNotBlank() && round >= 0) {
            "Repair priority requires run, opportunity, and round authority"
        }
        require(groupIds.isNotEmpty() && groupIds.size <= RepairStrategy.entries.size) {
            "Repair strategy diversity requires one to three competing groups"
        }
        require(diagnostics.all {
            it.hasValidHash() &&
                it.runId == runId &&
                it.nodeId == nodeId.value &&
                it.opportunityId == opportunityId &&
                it.visibleFromRound <= round
        }) { "Repair priority diagnostics must be valid and visible for this exact lineage" }
        require(priorAttempts.all {
            it.hasValidHash() && it.opportunityId == opportunityId && it.round < round
        }) { "Repair priority attempts must be valid prior records for this opportunity" }

        val projectableDiagnostics = diagnostics.filter(
            ToolRepairDiagnosticV1::isWorkerProjectable
        )
        val attemptNumber = priorAttempts.size + 1
        if (
            projectableDiagnostics.isEmpty() ||
            attemptNumber > ApproachVector.MAX_LINEAGE_ATTEMPTS
        ) {
            return RepairPriorityPlan(emptyMap(), emptyMap())
        }
        val orderedGroups = groupIds.sortedBy(AtlasGroupId::value)
        val strategies = RepairStrategy.entries.drop((attemptNumber - 1) % RepairStrategy.entries.size) +
            RepairStrategy.entries.take((attemptNumber - 1) % RepairStrategy.entries.size)
        val vectors = orderedGroups.mapIndexed { index, groupId ->
            val strategy = strategies[index]
            val vector = ApproachVector(
                vectorId = "approach-${runId.hashCode().toUInt().toString(16)}-" +
                    "${groupId.value}-${round}-${strategy.name.lowercase()}",
                runId = runId,
                groupId = groupId.value,
                opportunityId = opportunityId,
                round = round,
                lineageAttempt = attemptNumber,
                strategy = strategy
            ).withComputedHash()
            groupId to vector
        }.toMap()
        check(vectors.values.map(ApproachVector::strategy).distinct().size == vectors.size) {
            "Competing repair groups must receive distinct sealed strategies"
        }
        return RepairPriorityPlan(
            priorityNodeIdsByGroup = orderedGroups.associateWith { setOf(nodeId) },
            approachVectors = vectors
        )
    }
}
