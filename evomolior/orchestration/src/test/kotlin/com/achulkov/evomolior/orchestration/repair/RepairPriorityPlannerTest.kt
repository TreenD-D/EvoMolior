package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.core.repair.ToolValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepairPriorityPlannerTest {
    private val planner = RepairPriorityPlanner()
    private val commitment = powerToEnergyCommitment()
    private val diagnostic = ToolSemanticCompatibilityValidator().compileDiagnostics(
        review = com.achulkov.evomolior.core.repair.ToolSemanticReview(
            verdict = com.achulkov.evomolior.core.repair.ToolSemanticVerdict.REJECT,
            publicFindings = listOf(
                com.achulkov.evomolior.core.repair.PublicToolFinding(
                    code = com.achulkov.evomolior.core.repair.ToolRepairCode
                        .POWER_TO_ENERGY_UNIT_CONVERSION,
                    affectedPublicField = "energy_kwh",
                    quantitySpec = commitment.opportunity.requiredOutputs.single().quantitySpec
                )
            ),
            safeRepairCodes = setOf(
                com.achulkov.evomolior.core.repair.ToolRepairCode
                    .POWER_TO_ENERGY_UNIT_CONVERSION
            ),
            privateRationaleCommitment = "a".repeat(64)
        ),
        commitment = commitment,
        candidateHash = "b".repeat(64),
        groupId = "group-a",
        createdRound = 1
    ).single()

    @Test
    fun `visible repair stays high priority with distinct sealed group strategies`() {
        val groups = setOf(AtlasGroupId("group-a"), AtlasGroupId("group-b"))
        val plan = planner.plan(
            runId = commitment.runId,
            round = 2,
            nodeId = commitment.nodeId,
            opportunityId = commitment.opportunity.opportunityId,
            groupIds = groups,
            diagnostics = listOf(diagnostic),
            priorAttempts = emptyList()
        )

        assertEquals(groups, plan.priorityNodeIdsByGroup.keys)
        assertTrue(plan.priorityNodeIdsByGroup.values.all { it == setOf(commitment.nodeId) })
        assertEquals(2, plan.approachVectors.values.map { it.strategy }.distinct().size)
        assertTrue(plan.approachVectors.values.all(ApproachVector::hasValidHash))
    }

    @Test
    fun `fourth lineage attempt is not prioritized`() {
        val attempts = (1..3).map { number ->
            ToolRepairAttemptRecord(
                attemptId = "attempt-$number",
                opportunityId = commitment.opportunity.opportunityId,
                candidateHash = AtlasHashing.hashText("candidate-$number"),
                authorAgentId = "builder-$number",
                round = number + 1,
                consumedDiagnosticIds = listOf(diagnostic.diagnosticId),
                preflightResult = ToolValidationStatus.PASSED,
                criticResult = null,
                promotionResult = ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY
            ).withComputedHash()
        }

        val plan = planner.plan(
            runId = commitment.runId,
            round = 5,
            nodeId = commitment.nodeId,
            opportunityId = commitment.opportunity.opportunityId,
            groupIds = setOf(AtlasGroupId("group-a"), AtlasGroupId("group-b")),
            diagnostics = listOf(diagnostic),
            priorAttempts = attempts
        )

        assertTrue(plan.priorityNodeIdsByGroup.isEmpty())
        assertTrue(plan.approachVectors.isEmpty())
    }

    @Test
    fun `private-only diagnostic remains audit authority without scheduling worker repair`() {
        val privateOnly = ToolRepairDiagnosticV1(
            diagnosticId = "private-only",
            runId = commitment.runId,
            groupId = "group-a",
            nodeId = commitment.nodeId.value,
            opportunityId = commitment.opportunity.opportunityId,
            opportunityHash = commitment.commitmentHash,
            candidateHash = AtlasHashing.hashText("private-candidate"),
            source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
            disclosureClass = ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY,
            code = ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED,
            createdRound = 1,
            visibleFromRound = 2,
            rationaleCommitment = AtlasHashing.hashText("private-rationale"),
        ).withComputedHash()

        val plan = planner.plan(
            runId = commitment.runId,
            round = 2,
            nodeId = commitment.nodeId,
            opportunityId = commitment.opportunity.opportunityId,
            groupIds = setOf(AtlasGroupId("group-a")),
            diagnostics = listOf(privateOnly),
            priorAttempts = emptyList(),
        )

        assertTrue(plan.priorityNodeIdsByGroup.isEmpty())
        assertTrue(plan.approachVectors.isEmpty())
    }
}
