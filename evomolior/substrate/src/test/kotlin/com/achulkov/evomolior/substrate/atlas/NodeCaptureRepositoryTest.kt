package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.NodeYieldApplication
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.RewardUseLineageEvent
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeCaptureRepositoryTest {
    private val runId = "run-capture"
    private val nodeId = NodeId("node-capture")
    private val groupId = AtlasGroupId("group-capture")

    @Test
    fun `exact authority chain is append only idempotent and queryable`() = runTest {
        val root = createTempDirectory("node-capture-repository-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        try {
            val repository = NodeCaptureRepository(factory, now = { 10L })
            val packet = packet()
            val bundle = bundle(packet)
            val decision = decision(bundle)
            val schedule = schedule(packet, bundle, decision)
            val application = application(schedule)
            val use = rewardUse(application, decision, schedule)

            assertEquals(packet, repository.appendEvidencePacket(packet))
            assertEquals(packet, repository.appendEvidencePacket(packet))
            assertEquals(bundle, repository.appendGradeBundle(bundle))
            assertEquals(bundle, repository.appendGradeBundle(bundle))
            assertEquals(decision, repository.appendCaptureDecision(decision))
            assertEquals(decision, repository.appendCaptureDecision(decision))
            assertEquals(schedule, repository.appendYieldSchedule(schedule))
            assertEquals(schedule, repository.appendYieldSchedule(schedule))
            assertEquals(application, repository.appendYieldApplication(application))
            assertEquals(application, repository.appendYieldApplication(application))
            assertEquals(use, repository.appendRewardUseEvent(use))
            assertEquals(use, repository.appendRewardUseEvent(use))

            assertEquals(listOf(packet), repository.evidencePacketsForNode(runId, nodeId))
            assertEquals(listOf(bundle), repository.gradeBundlesForNode(runId, nodeId))
            assertEquals(listOf(decision), repository.captureDecisionsForNode(runId, nodeId))
            assertEquals(
                listOf(schedule),
                repository.yieldSchedulesForActivation(runId, groupId.value, 3)
            )
            assertEquals(
                listOf(use),
                repository.rewardUseEventsForApplication(runId, application.applicationHash)
            )
            assertEquals(
                setOf(
                    ContributionRelation.GRADED_AS,
                    ContributionRelation.AUTHORIZED_CAPTURE
                ),
                factory.get(runId, groupId.value).contributionGraphDao()
                    .forRun(runId, groupId.value)
                    .mapTo(linkedSetOf()) { ContributionRelation.valueOf(it.relation) }
            )
        } finally {
            factory.close(runId, "unused")
            factory.getAtlas(runId).close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `defective authority and same round yields fail closed`() = runTest {
        val root = createTempDirectory("node-capture-invalid-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        try {
            val repository = NodeCaptureRepository(factory)
            val packet = packet()
            repository.appendEvidencePacket(packet)

            assertFailsWith<IllegalArgumentException> {
                repository.appendGradeBundle(
                    bundle(packet).copy(
                        bundleId = "bundle-incomplete",
                        verdict = NodeGradeBundleVerdict.INCOMPLETE_QUORUM,
                        completedGraderIds = setOf("grader-a"),
                        graderResponseHashes = setOf("response-a"),
                        bundleHash = ""
                    ).withComputedHash()
                )
            }

            val bundle = repository.appendGradeBundle(bundle(packet))
            val frozen = decision(bundle).copy(
                decisionId = "decision-frozen",
                decisionType = NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
                authoritativeBundleHashes = emptySet(),
                resultingOwnerGroupIds = emptySet(),
                resultingCaptureState = CaptureState.FROZEN,
                winningScore = null,
                yieldEligible = false,
                decisionHash = ""
            ).withComputedHash()
            repository.appendCaptureDecision(frozen)

            assertFailsWith<IllegalArgumentException> {
                repository.appendYieldSchedule(
                    schedule(packet, bundle, frozen).copy(
                        scheduleId = "schedule-frozen",
                        scheduleHash = ""
                    ).withComputedHash()
                )
            }

            val eligible = repository.appendCaptureDecision(decision(bundle))
            assertFailsWith<IllegalArgumentException> {
                repository.appendYieldSchedule(
                    schedule(packet, bundle, eligible).copy(
                        scheduleId = "schedule-same-round",
                        activationRound = 2,
                        scheduleHash = ""
                    ).withComputedHash()
                )
            }
        } finally {
            factory.getAtlas(runId).close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `frozen audit retains a known candidate from a prior Atlas version`() = runTest {
        val root = createTempDirectory("node-capture-frozen-mismatch-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        try {
            val repository = NodeCaptureRepository(factory)
            val packet = repository.appendEvidencePacket(packet())
            val bundle = repository.appendGradeBundle(bundle(packet))
            val frozen = decision(bundle).copy(
                decisionId = "decision-frozen-mismatch",
                atlasVersion = 2,
                atlasHash = "atlas-hash-v2",
                authoritativeBundleHashes = emptySet(),
                resultingOwnerGroupIds = emptySet(),
                resultingCaptureState = CaptureState.FROZEN,
                decisionType = NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
                winningScore = null,
                yieldEligible = false,
                decisionHash = ""
            ).withComputedHash()

            assertEquals(frozen, repository.appendCaptureDecision(frozen))
            val unknownCandidate = frozen.copy(
                decisionId = "decision-frozen-unknown",
                candidateBundleHashes = setOf("unknown-bundle"),
                decisionHash = ""
            ).withComputedHash()
            assertEquals(unknownCandidate, repository.appendCaptureDecision(unknownCandidate))
            assertFailsWith<IllegalArgumentException> {
                repository.appendCaptureDecision(
                    frozen.copy(
                        decisionId = "decision-forged-frozen-yield",
                        yieldEligible = true,
                        decisionHash = ""
                    ).withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendCaptureDecision(
                    frozen.copy(
                        decisionId = "decision-forged-frozen-authority",
                        authoritativeBundleHashes = setOf(bundle.bundleHash),
                        decisionHash = ""
                    ).withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendCaptureDecision(
                    decision(bundle).copy(
                        decisionId = "decision-mutating-mismatch",
                        atlasVersion = 2,
                        atlasHash = "atlas-hash-v2",
                        decisionHash = ""
                    ).withComputedHash()
                )
            }
        } finally {
            factory.getAtlas(runId).close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `reward use must retain exact schedule capture and bounded reward authority`() = runTest {
        val root = createTempDirectory("node-capture-lineage-invalid-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        try {
            val repository = NodeCaptureRepository(factory)
            val packet = repository.appendEvidencePacket(packet())
            val bundle = repository.appendGradeBundle(bundle(packet))
            val decision = repository.appendCaptureDecision(decision(bundle))
            val schedule = repository.appendYieldSchedule(schedule(packet, bundle, decision))
            val application = repository.appendYieldApplication(application(schedule))

            assertFailsWith<IllegalArgumentException> {
                repository.appendRewardUseEvent(
                    rewardUse(application, decision, schedule).copy(
                        captureDecisionHash = "wrong-decision",
                        eventHash = ""
                    ).withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendRewardUseEvent(
                    rewardUse(application, decision, schedule).copy(
                        amountSpent = 2.0,
                        eventHash = ""
                    ).withComputedHash()
                )
            }
        } finally {
            factory.getAtlas(runId).close()
            root.deleteRecursively()
        }
    }

    private fun packet(): NodeEvidencePacket = NodeEvidencePacket(
        packetId = "packet-1",
        runId = runId,
        round = 2,
        groupId = groupId,
        nodeId = nodeId,
        atlasVersion = 1,
        atlasHash = "atlas-hash",
        nodePayloadHash = "payload-hash",
        acceptedArtifactIds = listOf("artifact-1"),
        provenanceRootHash = "provenance-hash",
        cost = NodeEvidenceCostSummary(totalCredits = 2.0),
        sealedAtEpochMillis = 1L
    ).withComputedHash()

    private fun bundle(packet: NodeEvidencePacket): NodeGradeBundle = NodeGradeBundle(
        bundleId = "bundle-1",
        runId = runId,
        round = 2,
        groupId = groupId,
        nodeId = nodeId,
        atlasVersion = 1,
        atlasHash = packet.atlasHash,
        nodePayloadHash = packet.nodePayloadHash,
        evidencePacketId = packet.packetId,
        evidencePacketHash = packet.packetHash,
        criteriaHash = "criteria-hash",
        capturePolicyHash = "capture-policy-hash",
        scoreVector = NodeScoreVector(
            correctness = 0.9,
            evidenceQuality = 0.9,
            reproducibility = 0.8,
            robustness = 0.8,
            integrationUtility = 0.8,
            receiverRelevance = 0.9,
            novelty = 0.7,
            efficiency = 0.8,
            hardFailure = false,
            confidence = 0.9
        ),
        normalizedScore = 0.85,
        verdict = NodeGradeBundleVerdict.VALID_PASS,
        requiredQuorum = 2,
        graderIds = setOf("grader-a", "grader-b"),
        completedGraderIds = setOf("grader-a", "grader-b"),
        graderResponseHashes = setOf("response-a", "response-b")
    ).withComputedHash()

    private fun decision(bundle: NodeGradeBundle): NodeCaptureDecision = NodeCaptureDecision(
        decisionId = "decision-1",
        runId = runId,
        round = 2,
        nodeId = nodeId,
        atlasVersion = 1,
        atlasHash = bundle.atlasHash,
        nodePayloadHash = bundle.nodePayloadHash,
        capturePolicyHash = bundle.capturePolicyHash,
        candidateBundleHashes = setOf(bundle.bundleHash),
        authoritativeBundleHashes = setOf(bundle.bundleHash),
        resultingOwnerGroupIds = setOf(groupId),
        resultingCaptureState = CaptureState.CAPTURED,
        decisionType = NodeCaptureDecisionType.CAPTURED,
        winningScore = bundle.normalizedScore,
        yieldEligible = true,
        reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE
    ).withComputedHash()

    private fun schedule(
        packet: NodeEvidencePacket,
        bundle: NodeGradeBundle,
        decision: NodeCaptureDecision
    ): NodeYieldSchedule = NodeYieldSchedule(
        scheduleId = "schedule-1",
        runId = runId,
        nodeId = nodeId,
        groupId = groupId,
        judgingRound = 2,
        activationRound = 3,
        captureDecisionId = decision.decisionId,
        captureDecisionHash = decision.decisionHash,
        evidencePacketHash = packet.packetHash,
        scoreBundleHash = bundle.bundleHash,
        constraintPolicyHash = "yield-policy-hash",
        rewards = listOf(ScheduledReward(InstrumentalRewardType.SPAWN_CREDITS, 1.0))
    ).withComputedHash()

    private fun application(schedule: NodeYieldSchedule): NodeYieldApplication =
        NodeYieldApplication(
            applicationId = "application-1",
            scheduleId = schedule.scheduleId,
            scheduleHash = schedule.scheduleHash,
            runId = runId,
            nodeId = nodeId,
            groupId = groupId,
            round = 3,
            appliedRewards = schedule.rewards
        ).withComputedHash()

    private fun rewardUse(
        application: NodeYieldApplication,
        decision: NodeCaptureDecision,
        schedule: NodeYieldSchedule
    ): RewardUseLineageEvent = RewardUseLineageEvent(
        eventId = "reward-use-1",
        runId = runId,
        groupId = groupId,
        nodeId = nodeId,
        captureDecisionHash = decision.decisionHash,
        yieldScheduleHash = schedule.scheduleHash,
        yieldApplicationHash = application.applicationHash,
        rewardType = InstrumentalRewardType.SPAWN_CREDITS,
        amountSpent = 1.0,
        spentRound = 3,
        downstreamArtifactIds = setOf("artifact-downstream")
    ).withComputedHash()
}
