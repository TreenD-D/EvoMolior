package com.achulkov.evomolior.core.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeCaptureContractsTest {
    @Test
    fun `packet hash ignores accepted-reference and cost-map insertion order`() {
        val first = evidencePacket(reverseInsertion = false).withComputedHash()
        val second = evidencePacket(reverseInsertion = true).withComputedHash()

        assertEquals(first.packetHash, second.packetHash)
        assertTrue(first.hasValidHash())
        assertTrue(second.hasValidHash())
    }

    @Test
    fun `packet hash excludes self hash and detects semantic tampering`() {
        val packet = evidencePacket().withComputedHash()

        assertEquals(packet.packetHash, AtlasHashing.hashNodeEvidencePacket(packet))
        assertEquals(
            packet.packetHash,
            AtlasHashing.hashNodeEvidencePacket(packet.copy(packetHash = "not-authority"))
        )
        assertFalse(packet.copy(nodePayloadHash = "tampered").hasValidHash())
    }

    @Test
    fun `grade bundle hash normalizes independent grader sets and binds exact packet`() {
        val packet = evidencePacket().withComputedHash()
        val first = gradeBundle(packet, reverseInsertion = false).withComputedHash()
        val second = gradeBundle(packet, reverseInsertion = true).withComputedHash()

        assertEquals(first.bundleHash, second.bundleHash)
        assertTrue(first.hasValidHash())
        assertNotEquals(
            first.bundleHash,
            gradeBundle(packet.copy(packetHash = "different")).withComputedHash().bundleHash
        )
    }

    @Test
    fun `node grading assignment and response bind exact immutable authority`() {
        val packet = evidencePacket().withComputedHash()
        val policy = CapturePolicy(minimumGrade = 0.7, recaptureMargin = 0.1)
        val assignment = NodeGradingAssignment(
            assignmentId = "assignment-1",
            packet = packet,
            criteriaHash = "criteria-hash",
            capturePolicy = policy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(policy),
            requiredQuorum = 2
        ).withComputedHash()
        val response = NodeGraderResponse(
            responseId = "response-1",
            assignmentId = assignment.assignmentId,
            assignmentHash = assignment.assignmentHash,
            graderId = "grader-1",
            scoreVector = gradeBundle(packet).scoreVector,
            verdict = NodeGraderResponseVerdict.VALID,
            rationale = "Exact packet evidence satisfies the frozen criteria."
        ).withComputedHash()

        assertTrue(assignment.hasValidHash())
        assertTrue(response.hasValidHash())
        assertFalse(assignment.copy(criteriaHash = "tampered").hasValidHash())
        assertFalse(response.copy(graderId = "other-grader").hasValidHash())
    }

    @Test
    fun `node score weights are explicit and deterministic`() {
        val vector = requireNotNull(gradeBundle(evidencePacket()).scoreVector)
        val weights = NodeScoreWeights(correctness = 3.0, novelty = 0.5)

        assertEquals(weights.normalizedScore(vector), weights.normalizedScore(vector))
        assertTrue(weights.normalizedScore(vector) in 0.0..1.0)
    }

    @Test
    fun `valid no evidence remains distinct from defective grader authority`() {
        val packet = evidencePacket().withComputedHash()
        val noEvidence = gradeBundle(packet).copy(
            evidencePacketId = null,
            evidencePacketHash = null,
            scoreVector = null,
            normalizedScore = null,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE
        ).withComputedHash()
        val incomplete = noEvidence.copy(
            verdict = NodeGradeBundleVerdict.INCOMPLETE_QUORUM,
            bundleHash = ""
        ).withComputedHash()

        assertEquals(NodeGradeBundleVerdict.NO_EVIDENCE, noEvidence.verdict)
        assertNull(noEvidence.normalizedScore)
        assertEquals(NodeGradeBundleVerdict.INCOMPLETE_QUORUM, incomplete.verdict)
        assertNotEquals(noEvidence.bundleHash, incomplete.bundleHash)
    }

    @Test
    fun `capture decision hash is order independent and binds prior authority`() {
        val first = captureDecision(reverseInsertion = false).withComputedHash()
        val second = captureDecision(reverseInsertion = true).withComputedHash()

        assertEquals(first.decisionHash, second.decisionHash)
        assertTrue(first.hasValidHash())
        assertFalse(
            first.copy(previousAuthoritativeBundleHash = "different-prior-bundle").hasValidHash()
        )
    }

    @Test
    fun `yield and reward lineage hashes bind the full causal chain`() {
        val schedule = yieldSchedule().withComputedHash()
        val reorderedSchedule = schedule.copy(
            rewards = schedule.rewards.reversed(),
            scheduleHash = ""
        ).withComputedHash()
        val application = NodeYieldApplication(
            applicationId = "application-1",
            scheduleId = schedule.scheduleId,
            scheduleHash = schedule.scheduleHash,
            runId = schedule.runId,
            nodeId = schedule.nodeId,
            groupId = schedule.groupId,
            round = schedule.activationRound,
            appliedRewards = schedule.rewards
        ).withComputedHash()
        val use = RewardUseLineageEvent(
            eventId = "use-1",
            runId = schedule.runId,
            groupId = schedule.groupId,
            nodeId = schedule.nodeId,
            captureDecisionHash = schedule.captureDecisionHash,
            yieldScheduleHash = schedule.scheduleHash,
            yieldApplicationHash = application.applicationHash,
            rewardType = InstrumentalRewardType.SPAWN_CREDITS,
            amountSpent = 1.0,
            spentRound = 4,
            downstreamArtifactIds = linkedSetOf("artifact-b", "artifact-a")
        ).withComputedHash()
        val reordered = use.copy(
            downstreamArtifactIds = linkedSetOf("artifact-a", "artifact-b"),
            eventHash = ""
        ).withComputedHash()

        assertTrue(schedule.hasValidHash())
        assertEquals(schedule.scheduleHash, reorderedSchedule.scheduleHash)
        assertTrue(application.hasValidHash())
        assertTrue(use.hasValidHash())
        assertEquals(use.eventHash, reordered.eventHash)
        assertFalse(
            use.copy(yieldApplicationHash = "unrelated-application").hasValidHash()
        )
    }

    @Test
    fun `yield constraint policy hash excludes only its self hash`() {
        val policy = YieldConstraintPolicy(
            policyId = "policy-1",
            policyHash = "",
            perGroupRoundCap = 10.0,
            maximumActiveYieldFraction = 0.4,
            repeatOwnershipDiminishingFactor = 0.8,
            mandatoryFrontierBonus = 1.5,
            independentValidationCredit = 0.5
        ).withComputedHash()

        assertTrue(policy.hasValidHash())
        assertEquals(
            policy.policyHash,
            AtlasHashing.hashYieldConstraintPolicy(policy.copy(policyHash = "ignored"))
        )
        assertFalse(policy.copy(perGroupRoundCap = 11.0).hasValidHash())
    }

    private fun evidencePacket(reverseInsertion: Boolean = false): NodeEvidencePacket =
        NodeEvidencePacket(
            packetId = "packet-1",
            runId = "run-1",
            round = 2,
            groupId = AtlasGroupId("group-1"),
            nodeId = NodeId("node-1"),
            atlasVersion = 3,
            atlasHash = "atlas-hash",
            nodePayloadHash = "payload-hash",
            acceptedArtifactIds = insertionList(
                reverseInsertion,
                "artifact-a",
                "artifact-b"
            ),
            acceptedClaimIds = insertionList(reverseInsertion, "claim-a", "claim-b"),
            toolRunIds = insertionList(reverseInsertion, "tool-a", "tool-b"),
            testResultIds = insertionList(reverseInsertion, "test-a", "test-b"),
            negativeResultIds = insertionList(reverseInsertion, "negative-a", "negative-b"),
            provenanceRootHash = "provenance-hash",
            cost = NodeEvidenceCostSummary(
                totalCredits = 10.0,
                llmTokens = 500,
                toolExecutions = 2,
                sourceFetches = 3,
                wallClockMillis = 1_000,
                resourceCosts = insertionMap(reverseInsertion, "compute" to 4.0, "source" to 6.0)
            ),
            sealedAtEpochMillis = 123_456
        )

    private fun gradeBundle(
        packet: NodeEvidencePacket,
        reverseInsertion: Boolean = false
    ): NodeGradeBundle = NodeGradeBundle(
        bundleId = "bundle-1",
        runId = packet.runId,
        round = packet.round,
        groupId = packet.groupId,
        nodeId = packet.nodeId,
        atlasVersion = packet.atlasVersion,
        atlasHash = packet.atlasHash,
        nodePayloadHash = packet.nodePayloadHash,
        evidencePacketId = packet.packetId,
        evidencePacketHash = packet.packetHash,
        criteriaHash = "criteria-hash",
        capturePolicyHash = "capture-policy-hash",
        scoreVector = NodeScoreVector(
            correctness = 0.9,
            evidenceQuality = 0.8,
            reproducibility = 0.7,
            robustness = 0.6,
            integrationUtility = 0.9,
            receiverRelevance = 0.8,
            novelty = 0.5,
            efficiency = 0.7,
            hardFailure = false,
            confidence = 0.85
        ),
        normalizedScore = 0.79,
        verdict = NodeGradeBundleVerdict.VALID_PASS,
        requiredQuorum = 2,
        graderIds = insertionSet(reverseInsertion, "grader-a", "grader-b"),
        completedGraderIds = insertionSet(reverseInsertion, "grader-a", "grader-b"),
        graderResponseHashes = insertionSet(reverseInsertion, "response-a", "response-b")
    )

    private fun captureDecision(reverseInsertion: Boolean = false): NodeCaptureDecision =
        NodeCaptureDecision(
            decisionId = "decision-1",
            runId = "run-1",
            round = 2,
            nodeId = NodeId("node-1"),
            atlasVersion = 3,
            atlasHash = "atlas-hash",
            nodePayloadHash = "payload-hash",
            capturePolicyHash = "capture-policy-hash",
            candidateBundleHashes = insertionSet(reverseInsertion, "bundle-a", "bundle-b"),
            authoritativeBundleHashes = insertionSet(reverseInsertion, "bundle-a", "bundle-b"),
            previousOwnerGroupId = AtlasGroupId("group-old"),
            previousAuthoritativeBundleHash = "bundle-old",
            resultingOwnerGroupIds = insertionSet(
                reverseInsertion,
                AtlasGroupId("group-a"),
                AtlasGroupId("group-b")
            ),
            resultingCaptureState = CaptureState.CONTESTED,
            decisionType = NodeCaptureDecisionType.CONTESTED,
            winningScore = 0.9,
            priorWinningScore = 0.8,
            yieldEligible = false,
            reasonCode = NodeCaptureReasonCode.NEAR_TIE
        )

    private fun yieldSchedule(): NodeYieldSchedule = NodeYieldSchedule(
        scheduleId = "schedule-1",
        runId = "run-1",
        nodeId = NodeId("node-1"),
        groupId = AtlasGroupId("group-a"),
        judgingRound = 2,
        activationRound = 3,
        captureDecisionId = "decision-1",
        captureDecisionHash = "decision-hash",
        evidencePacketHash = "packet-hash",
        scoreBundleHash = "bundle-hash",
        constraintPolicyHash = "yield-policy-hash",
        rewards = listOf(
            ScheduledReward(InstrumentalRewardType.SPAWN_CREDITS, 1.0),
            ScheduledReward(InstrumentalRewardType.RETRIEVAL_BUDGET, 2.0)
        ),
        frontierBonusApplied = true
    )

    private fun <T> insertionList(reverse: Boolean, first: T, second: T): List<T> =
        if (reverse) listOf(second, first) else listOf(first, second)

    private fun <T> insertionSet(reverse: Boolean, first: T, second: T): Set<T> =
        if (reverse) linkedSetOf(second, first) else linkedSetOf(first, second)

    private fun <K, V> insertionMap(
        reverse: Boolean,
        first: Pair<K, V>,
        second: Pair<K, V>
    ): Map<K, V> = if (reverse) {
        linkedMapOf(second, first)
    } else {
        linkedMapOf(first, second)
    }
}
