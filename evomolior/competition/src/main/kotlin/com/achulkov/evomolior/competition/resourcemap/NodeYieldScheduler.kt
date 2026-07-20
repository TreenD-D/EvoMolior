package com.achulkov.evomolior.competition.resourcemap

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.core.atlas.YieldConstraintPolicy
import kotlin.math.pow

/**
 * One valid capture requesting a next-round instrumental reward schedule.
 *
 * @param decision Exact grade-authoritative capture decision.
 * @param node Captured sealed node definition.
 * @param groupId Proposed reward recipient.
 * @param evidencePacketHash Exact winning evidence commitment.
 * @param scoreBundleHash Exact winning grade commitment.
 * @param baseRewards Incentive Designer rewards before systemic constraints.
 * @param priorCaptureCount Prior rewarded captures held by this group.
 * @param mandatoryFrontier Whether this was a previously uncaptured mandatory frontier node.
 * @param independentValidation Whether this group independently reproduced another result.
 */
data class NodeYieldCandidate(
    val decision: NodeCaptureDecision,
    val node: LimitationNode,
    val groupId: AtlasGroupId,
    val evidencePacketHash: String,
    val scoreBundleHash: String,
    val baseRewards: List<ScheduledReward>,
    val priorCaptureCount: Int,
    val mandatoryFrontier: Boolean = false,
    val independentValidation: Boolean = false
)

/**
 * Batch input required to enforce round caps and global concentration simultaneously.
 *
 * @param judgingValid Whether the complete judging authority is valid and non-abstained.
 * @param policy Frozen Incentive Designer constraint policy.
 * @param candidates Valid capture candidates from one judging round.
 * @param existingActiveYieldByGroup Active yield amount before this batch.
 * @param alreadyScheduledForActivationByGroup Amount already scheduled for the target round.
 */
data class NodeYieldSchedulingBatch(
    val judgingValid: Boolean,
    val policy: YieldConstraintPolicy,
    val candidates: List<NodeYieldCandidate>,
    val existingActiveYieldByGroup: Map<AtlasGroupId, Double> = emptyMap(),
    val alreadyScheduledForActivationByGroup: Map<AtlasGroupId, Double> = emptyMap()
)

/**
 * Deterministically creates delayed, capped, anti-concentrated node reward schedules.
 *
 * Invalid or abstained judging returns no schedules. Every accepted schedule activates exactly
 * one round after judging and remains bound to its evidence, grade, decision, and constraint
 * policy hashes.
 */
class NodeYieldScheduler {
    /** Applies all systemic constraints and returns deterministic next-round schedules. */
    fun schedule(batch: NodeYieldSchedulingBatch): List<NodeYieldSchedule> {
        if (!batch.judgingValid) return emptyList()
        validatePolicy(batch.policy)
        if (batch.candidates.isEmpty()) return emptyList()
        val judgingRounds = batch.candidates.map { it.decision.round }.distinct()
        require(judgingRounds.size == 1) { "A yield batch must contain exactly one judging round" }
        require(
            batch.existingActiveYieldByGroup.values.all(::validNonNegativeAmount) &&
                batch.alreadyScheduledForActivationByGroup.values.all(::validNonNegativeAmount)
        ) {
            "Existing and scheduled yield amounts must be finite and non-negative"
        }
        require(
            batch.candidates
                .map { it.decision.decisionHash to it.groupId }
                .distinct()
                .size == batch.candidates.size
        ) {
            "A capture decision can schedule at most one reward per group"
        }
        batch.candidates.forEach(::validateCandidate)

        val unconstrained = batch.candidates.associateWith { candidate ->
            adjustedRewards(candidate, batch.policy)
        }
        val afterRoundCaps = applyPerGroupRoundCaps(
            rewardsByCandidate = unconstrained,
            policy = batch.policy,
            alreadyScheduled = batch.alreadyScheduledForActivationByGroup
        )
        val afterTotalCap = applyTotalRoundCap(
            rewardsByCandidate = afterRoundCaps,
            totalRoundCap = batch.policy.totalRoundCap,
            alreadyScheduled = batch.alreadyScheduledForActivationByGroup.values.sum()
        )
        val afterConcentration = applyConcentrationLimit(
            rewardsByCandidate = afterTotalCap,
            maximumFraction = batch.policy.maximumActiveYieldFraction,
            existingActive = batch.existingActiveYieldByGroup
        )
        return afterConcentration.entries
            .filter { (_, rewards) -> rewards.sumOf(ScheduledReward::amount) > AMOUNT_TOLERANCE }
            .sortedWith(
                compareBy<Map.Entry<NodeYieldCandidate, List<ScheduledReward>>>(
                    { it.key.groupId.value },
                    { it.key.node.id.value },
                    { it.key.decision.decisionHash }
                )
            )
            .map { (candidate, rewards) ->
                val preliminary = NodeYieldSchedule(
                    scheduleId = "",
                    runId = candidate.decision.runId,
                    nodeId = candidate.node.id,
                    groupId = candidate.groupId,
                    judgingRound = candidate.decision.round,
                    activationRound = candidate.decision.round + 1,
                    captureDecisionId = candidate.decision.decisionId,
                    captureDecisionHash = candidate.decision.decisionHash,
                    evidencePacketHash = candidate.evidencePacketHash,
                    scoreBundleHash = candidate.scoreBundleHash,
                    constraintPolicyHash = batch.policy.policyHash,
                    rewards = rewards,
                    frontierBonusApplied = candidate.mandatoryFrontier,
                    independentValidationCredit = candidate.independentValidation
                )
                val scheduleId = "yield:${AtlasHashing.hashNodeYieldSchedule(preliminary)}"
                preliminary.copy(scheduleId = scheduleId).withComputedHash()
            }
    }

    private fun applyTotalRoundCap(
        rewardsByCandidate: Map<NodeYieldCandidate, List<ScheduledReward>>,
        totalRoundCap: Double,
        alreadyScheduled: Double
    ): Map<NodeYieldCandidate, List<ScheduledReward>> {
        val proposed = rewardsByCandidate.values.sumOf { rewards ->
            rewards.sumOf(ScheduledReward::amount)
        }
        val room = (totalRoundCap - alreadyScheduled).coerceAtLeast(0.0)
        if (proposed <= room || proposed <= AMOUNT_TOLERANCE) return rewardsByCandidate
        val scale = room / proposed
        return rewardsByCandidate.mapValues { (_, rewards) -> rewards.scaled(scale) }
    }

    private fun adjustedRewards(
        candidate: NodeYieldCandidate,
        policy: YieldConstraintPolicy
    ): List<ScheduledReward> {
        val repeatedOwnershipFactor =
            policy.repeatOwnershipDiminishingFactor.pow(candidate.priorCaptureCount)
        val frontierFactor = if (candidate.mandatoryFrontier) {
            1.0 + policy.mandatoryFrontierBonus
        } else {
            1.0
        }
        val diminished = candidate.baseRewards.scaled(
            repeatedOwnershipFactor * frontierFactor
        )
        if (!candidate.independentValidation ||
            policy.independentValidationCredit <= AMOUNT_TOLERANCE
        ) {
            return diminished
        }
        val diminishedTotal = diminished.sumOf(ScheduledReward::amount)
        if (diminishedTotal <= AMOUNT_TOLERANCE) return emptyList()
        return diminished.scaled(
            (diminishedTotal + policy.independentValidationCredit) / diminishedTotal
        )
    }

    private fun applyPerGroupRoundCaps(
        rewardsByCandidate: Map<NodeYieldCandidate, List<ScheduledReward>>,
        policy: YieldConstraintPolicy,
        alreadyScheduled: Map<AtlasGroupId, Double>
    ): Map<NodeYieldCandidate, List<ScheduledReward>> {
        val proposedByGroup = rewardsByCandidate.entries.groupBy { it.key.groupId }
        return proposedByGroup.flatMap { (groupId, entries) ->
            val proposed = entries.sumOf { (_, rewards) -> rewards.sumOf(ScheduledReward::amount) }
            val room = (policy.perGroupRoundCap - alreadyScheduled.getOrDefault(groupId, 0.0))
                .coerceAtLeast(0.0)
            val scale = if (proposed <= room || proposed <= AMOUNT_TOLERANCE) {
                1.0
            } else {
                room / proposed
            }
            entries.map { (candidate, rewards) -> candidate to rewards.scaled(scale) }
        }.toMap()
    }

    private fun applyConcentrationLimit(
        rewardsByCandidate: Map<NodeYieldCandidate, List<ScheduledReward>>,
        maximumFraction: Double,
        existingActive: Map<AtlasGroupId, Double>
    ): Map<NodeYieldCandidate, List<ScheduledReward>> {
        if (maximumFraction >= 1.0) return rewardsByCandidate
        val mutable = rewardsByCandidate.toMutableMap()
        repeat(MAX_CONCENTRATION_PASSES) {
            val proposedByGroup = mutable.entries.groupBy { it.key.groupId }
                .mapValues { (_, entries) ->
                    entries.sumOf { (_, rewards) -> rewards.sumOf(ScheduledReward::amount) }
                }
            val groups = (existingActive.keys + proposedByGroup.keys).sortedBy(AtlasGroupId::value)
            var changed = false
            groups.forEach { groupId ->
                val existingForGroup = existingActive.getOrDefault(groupId, 0.0)
                val proposedForGroup = proposedByGroup.getOrDefault(groupId, 0.0)
                if (proposedForGroup <= AMOUNT_TOLERANCE) return@forEach
                val otherTotal = groups
                    .filterNot { it == groupId }
                    .sumOf { other ->
                        existingActive.getOrDefault(other, 0.0) +
                            proposedByGroup.getOrDefault(other, 0.0)
                    }
                val maximumGroupTotal =
                    maximumFraction * otherTotal / (1.0 - maximumFraction)
                val allowedProposal = (maximumGroupTotal - existingForGroup).coerceAtLeast(0.0)
                if (proposedForGroup > allowedProposal + AMOUNT_TOLERANCE) {
                    val scale = allowedProposal / proposedForGroup
                    mutable.keys.filter { it.groupId == groupId }.forEach { candidate ->
                        mutable[candidate] = mutable.getValue(candidate).scaled(scale)
                    }
                    changed = true
                }
            }
            if (!changed) return mutable
        }
        return mutable
    }

    private fun validateCandidate(candidate: NodeYieldCandidate) {
        val decision = candidate.decision
        require(decision.hasValidHash()) { "Yield candidate capture decision hash is invalid" }
        require(decision.yieldEligible) { "Capture decision is not yield eligible" }
        require(decision.decisionType in YIELD_ELIGIBLE_DECISIONS) {
            "Capture decision type cannot grant a yield"
        }
        require(candidate.node.id == decision.nodeId &&
            candidate.node.payloadHash == decision.nodePayloadHash &&
            candidate.node.hasValidPayloadHash()
        ) {
            "Yield candidate node does not match the capture decision"
        }
        require(candidate.groupId in decision.resultingOwnerGroupIds) {
            "Yield recipient is not an owner or validation-credit group"
        }
        require(candidate.scoreBundleHash in decision.authoritativeBundleHashes) {
            "Yield score bundle is not authoritative for the capture decision"
        }
        require(candidate.evidencePacketHash.isNotBlank()) {
            "Yield candidate requires an exact evidence packet hash"
        }
        require(candidate.priorCaptureCount >= 0) { "Prior capture count cannot be negative" }
        require(candidate.baseRewards.isNotEmpty()) { "Yield candidate requires a reward" }
        require(candidate.baseRewards.map(ScheduledReward::rewardType).distinct().size ==
            candidate.baseRewards.size
        ) {
            "Yield candidate reward types must be unique"
        }
        require(candidate.baseRewards.all { validPositiveAmount(it.amount) }) {
            "Base reward amounts must be finite and positive"
        }
        if (candidate.baseRewards.any {
                it.rewardType == InstrumentalRewardType.TOOL_COMPUTE_CREDITS
            }
        ) {
            require(candidate.node.kind in TOOL_REWARD_NODE_KINDS) {
                "Tool compute credits require tool, simulation, or experiment capture"
            }
        }
    }

    private fun validatePolicy(policy: YieldConstraintPolicy) {
        require(policy.hasValidHash()) { "Yield constraint policy hash is invalid" }
        require(validPositiveAmount(policy.perGroupRoundCap)) {
            "Per-group round cap must be finite and positive"
        }
        require(validPositiveAmount(policy.totalRoundCap) &&
            policy.perGroupRoundCap <= policy.totalRoundCap
        ) {
            "Total round cap must be finite, positive, and no smaller than the per-group cap"
        }
        require(policy.maximumActiveYieldFraction > 0.0 &&
            policy.maximumActiveYieldFraction <= 1.0 &&
            policy.maximumActiveYieldFraction.isFinite()
        ) {
            "Maximum active yield fraction must be finite and in (0, 1]"
        }
        require(policy.repeatOwnershipDiminishingFactor > 0.0 &&
            policy.repeatOwnershipDiminishingFactor <= 1.0 &&
            policy.repeatOwnershipDiminishingFactor.isFinite()
        ) {
            "Repeat ownership factor must be finite and in (0, 1]"
        }
        require(validNonNegativeAmount(policy.mandatoryFrontierBonus) &&
            validNonNegativeAmount(policy.independentValidationCredit)
        ) {
            "Yield bonuses must be finite and non-negative"
        }
    }

    private fun List<ScheduledReward>.scaled(scale: Double): List<ScheduledReward> =
        mapNotNull { reward ->
            val amount = reward.amount * scale
            reward.copy(amount = amount).takeIf { amount > AMOUNT_TOLERANCE }
        }

    private fun validPositiveAmount(value: Double): Boolean =
        value > 0.0 && value.isFinite()

    private fun validNonNegativeAmount(value: Double): Boolean =
        value >= 0.0 && value.isFinite()

    private companion object {
        const val AMOUNT_TOLERANCE = 1e-9
        const val MAX_CONCENTRATION_PASSES = 16
        val YIELD_ELIGIBLE_DECISIONS = setOf(
            NodeCaptureDecisionType.CAPTURED,
            NodeCaptureDecisionType.RECAPTURED,
            NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
        )
        val TOOL_REWARD_NODE_KINDS = setOf(
            NodeKind.TOOL,
            NodeKind.SIMULATION,
            NodeKind.EXPERIMENT
        )
    }
}
