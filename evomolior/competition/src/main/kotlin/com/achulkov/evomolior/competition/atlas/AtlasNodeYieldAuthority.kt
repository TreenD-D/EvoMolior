package com.achulkov.evomolior.competition.atlas

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeYield
import com.achulkov.evomolior.core.atlas.NodeYieldApplication
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.substrate.atlas.AtlasCommitResult
import com.achulkov.evomolior.substrate.atlas.AtlasExecutionCommitRequest
import com.achulkov.evomolior.substrate.atlas.AtlasMutationType
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.AtlasYieldCommitRequest
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository

/**
 * Applies persisted delayed node-yield schedules through the Atlas transaction authority.
 *
 * Schedules are consumed only in their exact activation round. Each application and the
 * corresponding [NodeExecutionState.yieldHistory] append are committed atomically.
 */
class AtlasNodeYieldAuthority(
    private val runId: String,
    private val atlasId: String,
    private val atlasRepository: AtlasTransactionRepository,
    private val captureRepository: NodeCaptureRepository
) {
    /** One atomically persisted application and its Atlas commit. */
    data class AppliedYield(
        val schedule: NodeYieldSchedule,
        val application: NodeYieldApplication,
        val commit: AtlasCommitResult
    )

    /**
     * Applies every due schedule for one group.
     *
     * @param groupId Reward recipient.
     * @param round Exact activation round.
     * @return Successful Atlas commits in deterministic schedule order.
     */
    suspend fun applyDue(groupId: AtlasGroupId, round: Int): List<AppliedYield> {
        require(round >= 0) { "Yield activation round cannot be negative" }
        return captureRepository
            .yieldSchedulesForActivation(runId, groupId.value, round)
            .filter { schedule ->
                captureRepository.yieldApplicationForSchedule(runId, schedule.scheduleId) == null
            }
            .map { schedule -> apply(schedule, round) }
    }

    private suspend fun apply(
        schedule: NodeYieldSchedule,
        round: Int
    ): AppliedYield {
        require(schedule.hasValidHash()) { "Due yield schedule hash is invalid" }
        require(schedule.runId == runId &&
            schedule.activationRound == round
        ) {
            "Yield schedule is not due for this run and round"
        }
        val atlas = requireNotNull(atlasRepository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val current = atlasRepository.currentStates(runId, atlasId).getValue(schedule.nodeId)
        val expectedStateHash = AtlasHashing.hashExecutionState(current)
        val application = NodeYieldApplication(
            applicationId = "yield-application:${schedule.scheduleHash}",
            scheduleId = schedule.scheduleId,
            scheduleHash = schedule.scheduleHash,
            runId = runId,
            nodeId = schedule.nodeId,
            groupId = schedule.groupId,
            round = round,
            appliedRewards = schedule.rewards
        ).withComputedHash()
        val nextState = current.copy(
            round = maxOf(current.round, round),
            yieldHistory = current.yieldHistory + schedule.rewards.mapIndexed { index, reward ->
                reward.toNodeYield(schedule, index)
            }
        )
        val projections = requireNotNull(atlasRepository.currentProjections(runId, atlasId)) {
            "Atlas $atlasId has no committed projections"
        }
        val commit = atlasRepository.commitYield(
            AtlasYieldCommitRequest(
                execution = AtlasExecutionCommitRequest(
                    runId = runId,
                    atlasId = atlasId,
                    expectedAtlasHash = atlas.atlasHash,
                    expectedStateHashes = mapOf(schedule.nodeId to expectedStateHash),
                    mutationType = AtlasMutationType.YIELD.name,
                    changedStates = listOf(nextState),
                    projections = projections
                ),
                application = application
            )
        )
        return AppliedYield(schedule = schedule, application = application, commit = commit)
    }

    private fun ScheduledReward.toNodeYield(
        schedule: NodeYieldSchedule,
        index: Int
    ): NodeYield = NodeYield(
        yieldId = "node-yield:${schedule.scheduleHash}:$index",
        groupId = schedule.groupId,
        rewardClass = amount.toLegacyRewardClass(),
        credits = if (rewardType == InstrumentalRewardType.SPAWN_CREDITS) amount else 0.0,
        capabilityYields = if (rewardType == InstrumentalRewardType.SPAWN_CREDITS) {
            emptySet()
        } else {
            setOf("${rewardType.name}:$amount")
        },
        round = schedule.activationRound,
        scheduleId = schedule.scheduleId,
        scheduleHash = schedule.scheduleHash,
        captureDecisionHash = schedule.captureDecisionHash,
        evidencePacketHash = schedule.evidencePacketHash,
        scoreBundleHash = schedule.scoreBundleHash
    )

    private fun Double.toLegacyRewardClass(): RewardClass = when {
        this <= 0.0 -> RewardClass.NONE
        this < 10.0 -> RewardClass.SMALL
        this < 25.0 -> RewardClass.MEDIUM
        this < 50.0 -> RewardClass.LARGE
        else -> RewardClass.CRITICAL
    }
}
