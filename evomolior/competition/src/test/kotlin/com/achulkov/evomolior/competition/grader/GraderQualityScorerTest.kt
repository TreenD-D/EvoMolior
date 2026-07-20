package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderFitnessPlane
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.GradingRequirementOrigin
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestId
import com.achulkov.evomolior.core.grader.HiddenTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraderQualityScorerTest {
    private val scorer = GraderQualityScorer()
    private val workerNodes = setOf(NodeId("node-a"), NodeId("node-b"))
    private val receiverObligations = setOf("obligation-a", "obligation-b")

    @Test
    fun `controlled failure curriculum improves breadth depth robustness and creativity`() {
        val baselineRequirement = requirement(
            id = "baseline",
            mappedNodes = setOf(NodeId("node-a")),
            mappedObligations = setOf("obligation-a"),
            failureLevels = listOf(FailureLevel(1, "Misses one claim.", 0.7)),
            adversarialCases = emptyList(),
            novelty = "known"
        )
        val baseline = scorer.score(
            evidence(
                requirements = listOf(baselineRequirement),
                hiddenTests = emptyList(),
                peerNovelty = setOf("known"),
                detectedFailures = 0
            ),
            GraderFitnessPlane.REQUIREMENT_DISCOVERY
        )
        val improvedRequirement = requirement(
            id = "improved",
            mappedNodes = workerNodes,
            mappedObligations = receiverObligations,
            failureLevels = listOf(
                FailureLevel(1, "One claim is weak.", 0.8),
                FailureLevel(2, "A causal chain is absent.", 0.5),
                FailureLevel(3, "The receiver result is unsupported.", 0.2, critical = true)
            ),
            adversarialCases = listOf(
                AdversarialCase("a", "Swap a source.", "Detect mismatch.", 1.0),
                AdversarialCase("b", "Remove an edge case.", "Detect omission.", 1.0)
            ),
            novelty = "novel-failure"
        )
        val hiddenTest = hiddenTest(improvedRequirement.requirementId)
        val improved = scorer.score(
            evidence(
                requirements = listOf(improvedRequirement),
                hiddenTests = listOf(hiddenTest),
                peerNovelty = setOf("known"),
                detectedFailures = 2
            ),
            GraderFitnessPlane.REQUIREMENT_DISCOVERY
        )

        assertTrue(improved.quality.breadth > baseline.quality.breadth)
        assertTrue(improved.quality.depth > baseline.quality.depth)
        assertTrue(improved.quality.robustness > baseline.quality.robustness)
        assertTrue(improved.quality.creativity > baseline.quality.creativity)
        assertTrue(improved.aggregateScore > baseline.aggregateScore)
    }

    @Test
    fun `deterministic seed is excluded from grader fitness and cannot inflate quality`() {
        val authored = requirement(
            id = "authored",
            mappedNodes = setOf(NodeId("node-a")),
            mappedObligations = setOf("obligation-a"),
            failureLevels = listOf(FailureLevel(1, "Failure.", 0.5)),
            adversarialCases = emptyList(),
            novelty = "authored"
        )
        val seed = authored.copy(
            requirementId = GradingRequirementId("seed"),
            mappedWorkerNodeIds = workerNodes,
            mappedReceiverObligationRefs = receiverObligations,
            origin = GradingRequirementOrigin.DETERMINISTIC_SEED,
            requirementHash = ""
        ).withComputedHash()
        val record = scorer.score(
            evidence(
                requirements = listOf(authored, seed),
                hiddenTests = emptyList(),
                peerNovelty = emptySet(),
                detectedFailures = 1
            ),
            GraderFitnessPlane.REQUIREMENT_DISCOVERY
        )

        assertEquals(setOf(authored.requirementHash), record.eligibleRequirementHashes)
        assertEquals(setOf(seed.requirementHash), record.deterministicSeedHashes)
        assertEquals(0.5, record.quality.breadth)
        assertTrue(record.hasValidHash())
    }

    private fun evidence(
        requirements: List<GradingRequirement>,
        hiddenTests: List<HiddenTestDefinition>,
        peerNovelty: Set<String>,
        detectedFailures: Int
    ) = GraderQualityEvidence(
        runId = "run-1",
        round = 1,
        graderGroupId = "grader-a",
        receiverObligationUniverse = receiverObligations,
        workerNodeUniverse = workerNodes,
        requirements = requirements,
        hiddenTests = hiddenTests,
        peerNoveltyFingerprints = peerNovelty,
        calibrationScore = 0.7,
        discriminationScore = 0.7,
        predictiveValidity = 0.6,
        actionabilityScore = 0.6,
        detectedFailureCount = detectedFailures,
        totalEvaluationCost = 2.0
    )

    private fun requirement(
        id: String,
        mappedNodes: Set<NodeId>,
        mappedObligations: Set<String>,
        failureLevels: List<FailureLevel>,
        adversarialCases: List<AdversarialCase>,
        novelty: String
    ) = GradingRequirement(
        requirementId = GradingRequirementId(id),
        runId = "run-1",
        graderAtlasHash = "grader-atlas",
        graderAtlasNodeId = GraderNodeId("grader-node"),
        graderGroupId = "grader-a",
        round = 1,
        mappedWorkerNodeIds = mappedNodes,
        mappedReceiverObligationRefs = mappedObligations,
        criterion = "Detect the controlled failure.",
        observableEvidence = "Exact evidence packet.",
        passThreshold = "No controlled failure remains.",
        failureLevels = failureLevels,
        adversarialCases = adversarialCases,
        executableTestIds = if (id == "improved") listOf(HiddenTestId("test-1")) else emptyList(),
        disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
        rationale = "Receiver protection.",
        noveltyFingerprint = novelty,
        maximumEvaluationCost = 1.0
    ).withComputedHash()

    private fun hiddenTest(requirementId: GradingRequirementId) = HiddenTestDefinition(
        testId = HiddenTestId("test-1"),
        runId = "run-1",
        graderAtlasHash = "grader-atlas",
        graderGroupId = "grader-a",
        roundCreated = 1,
        mappedRequirementIds = setOf(requirementId),
        executorRef = "sandbox:test",
        inputSchemaHash = "input",
        expectedOutcomeHash = "outcome",
        difficulty = 0.7,
        detectionRate = 0.9,
        falsePositiveRate = 0.1,
        status = HiddenTestStatus.SEALED
    ).withComputedHash()
}
