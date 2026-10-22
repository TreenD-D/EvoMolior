package com.achulkov.evomolior.competition.resourcemap

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.core.atlas.YieldConstraintPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeYieldSchedulerTest {
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")
    private val scheduler = NodeYieldScheduler()

    @Test
    fun `invalid judging freezes every schedule`() {
        val candidate = candidate("node-a", groupA, 5.0)

        val schedules = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = false,
                policy = policy(),
                candidates = listOf(candidate)
            )
        )

        assertTrue(schedules.isEmpty())
    }

    @Test
    fun `rewards activate next round with caps diminishing returns and frontier credit`() {
        val policy = policy(
            perGroupCap = 10.0,
            concentration = 1.0,
            diminishing = 0.5,
            frontierBonus = 0.5,
            validationCredit = 0.5
        )
        val schedules = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = true,
                policy = policy,
                candidates = listOf(
                    candidate(
                        nodeId = "node-a",
                        groupId = groupA,
                        amount = 8.0,
                        priorCaptureCount = 1,
                        mandatoryFrontier = true
                    ),
                    candidate(
                        nodeId = "node-b",
                        groupId = groupA,
                        amount = 8.0
                    ),
                    candidate(
                        nodeId = "node-c",
                        groupId = groupB,
                        amount = 4.0,
                        independentValidation = true
                    )
                )
            )
        )

        assertTrue(schedules.all { it.judgingRound == 2 && it.activationRound == 3 })
        assertTrue(schedules.all { it.constraintPolicyHash == policy.policyHash })
        assertEquals(
            10.0,
            schedules.filter { it.groupId == groupA }
                .sumOf { schedule -> schedule.rewards.sumOf(ScheduledReward::amount) },
            absoluteTolerance = 1e-9
        )
        assertEquals(
            4.5,
            schedules.single { it.groupId == groupB }.rewards.sumOf(ScheduledReward::amount),
            absoluteTolerance = 1e-9
        )
        assertTrue(schedules.single { it.nodeId.value == "node-a" }.frontierBonusApplied)
        assertTrue(
            schedules.single { it.nodeId.value == "node-c" }.independentValidationCredit
        )
    }

    @Test
    fun `batch allocation enforces maximum active yield concentration`() {
        val policy = policy(
            perGroupCap = 100.0,
            concentration = 0.6,
            diminishing = 1.0
        )
        val schedules = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = true,
                policy = policy,
                candidates = listOf(
                    candidate("node-a", groupA, 20.0),
                    candidate("node-b", groupB, 40.0)
                ),
                existingActiveYieldByGroup = mapOf(groupA to 80.0, groupB to 20.0)
            )
        )
        val newlyScheduled = schedules.groupBy { it.groupId }.mapValues { (_, groupSchedules) ->
            groupSchedules.sumOf { it.rewards.sumOf(ScheduledReward::amount) }
        }
        val totalA = 80.0 + newlyScheduled.getOrDefault(groupA, 0.0)
        val totalB = 20.0 + newlyScheduled.getOrDefault(groupB, 0.0)
        val concentration = maxOf(totalA, totalB) / (totalA + totalB)

        assertTrue(concentration <= 0.6 + 1e-9)
        assertEquals(10.0, newlyScheduled.getValue(groupA), absoluteTolerance = 1e-9)
        assertEquals(40.0, newlyScheduled.getValue(groupB), absoluteTolerance = 1e-9)
    }

    @Test
    fun `six yield eligible captures owned by one group under sixty percent concentration schedule zero rewards`() {
        val policy = policy(
            perGroupCap = 100.0,
            concentration = 0.6,
            diminishing = 1.0
        )

        val schedules = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = true,
                policy = policy,
                candidates = (1..6).map { index ->
                    candidate("node-$index", groupA, amount = 10.0)
                }
            )
        )

        assertTrue(schedules.isEmpty())
    }

    @Test
    fun `aggregate round cap scales all groups without changing relative allocation`() {
        val constrained = policy(
            perGroupCap = 10.0,
            concentration = 1.0,
            diminishing = 1.0
        ).copy(totalRoundCap = 10.0, policyHash = "").withComputedHash()

        val schedules = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = true,
                policy = constrained,
                candidates = listOf(
                    candidate("node-a", groupA, 6.0),
                    candidate("node-b", groupB, 8.0)
                )
            )
        )

        val byGroup = schedules.associate { schedule ->
            schedule.groupId to schedule.rewards.sumOf(ScheduledReward::amount)
        }
        assertEquals(10.0, byGroup.values.sum(), absoluteTolerance = 1e-9)
        assertEquals(
            0.75,
            byGroup.getValue(groupA) / byGroup.getValue(groupB),
            absoluteTolerance = 1e-9
        )
    }

    @Test
    fun `artifact validation reward remains typed and delayed`() {
        val schedule = scheduler.schedule(
            NodeYieldSchedulingBatch(
                judgingValid = true,
                policy = policy(),
                candidates = listOf(
                    candidate(
                        nodeId = "delivery-node",
                        groupId = groupA,
                        amount = 3.0,
                        nodeKind = NodeKind.DELIVERY,
                        rewardType = InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS
                    )
                )
            )
        ).single()

        assertEquals(1, schedule.activationRound - schedule.judgingRound)
        assertEquals(
            InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS,
            schedule.rewards.single().rewardType
        )
        assertEquals(3.0, schedule.rewards.single().amount)
    }

    private fun candidate(
        nodeId: String,
        groupId: AtlasGroupId,
        amount: Double,
        priorCaptureCount: Int = 0,
        mandatoryFrontier: Boolean = false,
        independentValidation: Boolean = false,
        nodeKind: NodeKind = NodeKind.EVIDENCE,
        rewardType: InstrumentalRewardType = InstrumentalRewardType.SPAWN_CREDITS
    ): NodeYieldCandidate {
        val node = node(nodeId, nodeKind)
        val bundleHash = "bundle-$nodeId-${groupId.value}"
        return NodeYieldCandidate(
            decision = decision(node, groupId, bundleHash),
            node = node,
            groupId = groupId,
            evidencePacketHash = "packet-$nodeId-${groupId.value}",
            scoreBundleHash = bundleHash,
            baseRewards = listOf(
                ScheduledReward(rewardType, amount)
            ),
            priorCaptureCount = priorCaptureCount,
            mandatoryFrontier = mandatoryFrontier,
            independentValidation = independentValidation
        )
    }

    private fun decision(
        node: LimitationNode,
        groupId: AtlasGroupId,
        bundleHash: String
    ): NodeCaptureDecision {
        val preliminary = NodeCaptureDecision(
            decisionId = "",
            runId = "run-yield",
            round = 2,
            nodeId = node.id,
            atlasVersion = 1,
            atlasHash = "atlas-hash",
            nodePayloadHash = node.payloadHash,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            candidateBundleHashes = setOf(bundleHash),
            authoritativeBundleHashes = setOf(bundleHash),
            resultingOwnerGroupIds = setOf(groupId),
            resultingCaptureState = CaptureState.CAPTURED,
            decisionType = NodeCaptureDecisionType.CAPTURED,
            winningScore = 0.9,
            yieldEligible = true,
            reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE
        )
        return preliminary.copy(
            decisionId = "capture:${AtlasHashing.hashNodeCaptureDecision(preliminary)}"
        ).withComputedHash()
    }

    private fun node(
        id: String,
        kind: NodeKind = NodeKind.EVIDENCE
    ): LimitationNode = LimitationNode(
        id = NodeId(id),
        kind = kind,
        layer = 0,
        parentIds = emptySet(),
        prerequisiteIds = emptySet(),
        receiverWhyRef = "outcome-1",
        limitationStatement = "Evidence is missing.",
        capabilityTarget = "Establish evidence.",
        atomicQuestion = "What evidence resolves this node?",
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("Correct")
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
        ),
        revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, amountCredits(id))
    ).withComputedPayloadHash()

    private fun amountCredits(id: String): Double = if (id.isBlank()) 0.0 else 1.0

    private fun policy(
        perGroupCap: Double = 20.0,
        concentration: Double = 1.0,
        diminishing: Double = 0.8,
        frontierBonus: Double = 0.0,
        validationCredit: Double = 0.0
    ): YieldConstraintPolicy = YieldConstraintPolicy(
        policyId = "yield-policy",
        policyHash = "",
        perGroupRoundCap = perGroupCap,
        totalRoundCap = 100.0,
        maximumActiveYieldFraction = concentration,
        repeatOwnershipDiminishingFactor = diminishing,
        mandatoryFrontierBonus = frontierBonus,
        independentValidationCredit = validationCredit
    ).withComputedHash()
}
