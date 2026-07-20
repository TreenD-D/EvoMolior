package com.achulkov.evomolior.core.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraderAtlasContractsTest {
    private val workerNodeId = NodeId("worker-node")
    private val graderNodeId = GraderNodeId("grader-node")
    private val requirementId = GradingRequirementId("requirement-1")
    private val targetGroupId = AtlasGroupId("worker-group")

    @Test
    fun `grader atlas hashes are deterministic and validation rejects cycles`() {
        val first = atlas().withComputedHash()
        val reordered = first.copy(
            rootNodeIds = linkedSetOf(graderNodeId),
            nodes = linkedMapOf(graderNodeId to first.nodes.getValue(graderNodeId)),
            atlasHash = ""
        ).withComputedHash()

        assertEquals(first.atlasHash, reordered.atlasHash)
        assertTrue(first.hasValidHash())
        assertTrue(GraderAtlasValidation.validate(first).isEmpty())

        val cyclicNode = first.nodes.getValue(graderNodeId).copy(
            prerequisiteNodeIds = setOf(graderNodeId),
            payloadHash = ""
        ).withComputedHash()
        val cyclic = first.copy(
            nodes = mapOf(graderNodeId to cyclicNode),
            atlasHash = ""
        ).withComputedHash()
        assertTrue(GraderAtlasValidation.validate(cyclic).any { "cycle" in it })
    }

    @Test
    fun `typed requirement binds mappings tests and deterministic seed eligibility`() {
        val atlas = atlas().withComputedHash()
        val test = hiddenTest()
        val authored = requirement().withComputedHash()
        val seed = authored.copy(
            requirementId = GradingRequirementId("seed"),
            origin = GradingRequirementOrigin.DETERMINISTIC_SEED,
            requirementHash = ""
        ).withComputedHash()

        assertTrue(
            GraderAtlasValidation.validate(authored, atlas, mapOf(test.testId to test)).isEmpty()
        )
        assertTrue(authored.isFitnessEligible)
        assertFalse(seed.isFitnessEligible)
        assertFalse(authored.copy(maximumEvaluationCost = 2.0).hasValidHash())
    }

    @Test
    fun `capture requirement cannot govern multiple worker nodes`() {
        val secondWorkerNodeId = NodeId("worker-node-2")
        val baseAtlas = atlas().withComputedHash()
        val expandedNode = baseAtlas.nodes.getValue(graderNodeId).copy(
            mappedWorkerNodeIds = setOf(workerNodeId, secondWorkerNodeId),
            payloadHash = ""
        ).withComputedHash()
        val expandedAtlas = baseAtlas.copy(
            nodes = mapOf(graderNodeId to expandedNode),
            atlasHash = ""
        ).withComputedHash()
        val test = hiddenTest().copy(
            graderAtlasHash = expandedAtlas.atlasHash,
            testHash = ""
        ).withComputedHash()
        val broadRequirement = requirement().copy(
            graderAtlasHash = expandedAtlas.atlasHash,
            mappedWorkerNodeIds = setOf(workerNodeId, secondWorkerNodeId),
            requirementHash = ""
        ).withComputedHash()

        assertTrue(
            GraderAtlasValidation.validate(
                broadRequirement,
                expandedAtlas,
                mapOf(test.testId to test)
            ).any { "exactly one worker node" in it }
        )
    }

    @Test
    fun `criteria freeze rejects post reveal sealing and snapshot mismatch`() {
        val requirement = requirement().withComputedHash()
        val test = hiddenTest()
        val snapshots = mapOf(targetGroupId to "worker-snapshot")
        val freeze = freeze(requirement, test, snapshots)

        assertTrue(
            GraderAtlasValidation.validate(
                freeze,
                listOf(requirement),
                listOf(test),
                snapshots
            ).isEmpty()
        )
        assertFalse(
            freeze.copy(
                sealedAtEpochMillis = 20,
                workerRevealEpochMillis = 10,
                freezeHash = ""
            ).withComputedHash().hasValidHash()
        )
        assertTrue(
            GraderAtlasValidation.validate(
                freeze,
                listOf(requirement),
                listOf(test),
                mapOf(targetGroupId to "tampered-snapshot")
            ).any { "snapshot" in it }
        )
    }

    @Test
    fun `exact score bundle verifies target snapshot and cardinality`() {
        val requirement = requirement().withComputedHash()
        val test = hiddenTest()
        val freeze = freeze(
            requirement,
            test,
            mapOf(targetGroupId to "worker-snapshot")
        )
        val bundle = bundle(freeze)

        assertTrue(
            GraderAtlasValidation.validate(
                bundle,
                freeze,
                requiredNodeIds = setOf(workerNodeId),
                requiredDimensionIds = setOf("summary"),
                requiredRequirementIds = setOf(requirementId)
            ).isEmpty()
        )
        assertTrue(
            GraderAtlasValidation.validate(
                bundle.copy(targetSnapshotHash = "tampered", payloadHash = "")
                    .withComputedHash(),
                freeze,
                setOf(workerNodeId),
                setOf("summary"),
                setOf(requirementId)
            ).any { "snapshot" in it }
        )
        assertTrue(
            GraderAtlasValidation.validate(
                bundle,
                freeze,
                requiredNodeIds = setOf(workerNodeId, NodeId("missing-node")),
                requiredDimensionIds = setOf("summary"),
                requiredRequirementIds = setOf(requirementId)
            ).any { "cardinality" in it }
        )
    }

    @Test
    fun `fitness planes exclude deterministic seed and same family is low independence`() {
        val record = GraderFitnessRecord(
            recordId = "fitness-1",
            runId = "run-1",
            round = 1,
            graderGroupId = "grader-a",
            plane = GraderFitnessPlane.REQUIREMENT_DISCOVERY,
            quality = quality(),
            eligibleRequirementHashes = setOf("authored"),
            deterministicSeedHashes = setOf("seed"),
            aggregateScore = 0.7,
            policyHash = "policy"
        ).withComputedHash()
        val contaminated = record.copy(
            eligibleRequirementHashes = setOf("authored", "seed"),
            recordHash = ""
        ).withComputedHash()
        val profile = EvaluationIndependenceProfiler.profile(
            workerFamilies = setOf("family-a"),
            graderFamilies = setOf("family-a"),
            providers = setOf("provider-a"),
            sharedPromptTemplateFraction = 0.8
        )

        assertTrue(record.hasValidHash())
        assertFalse(contaminated.hasValidHash())
        assertEquals(IndependenceLabel.LOW_SINGLE_FAMILY, profile.independenceLabel)
        assertTrue(profile.hasValidHash())
    }

    private fun atlas(): GraderAtlas {
        val node = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.RECEIVER_COVERAGE,
            title = "Receiver coverage",
            evaluationQuestion = "Does evidence satisfy the receiver obligation?",
            receiverObligationRefs = setOf("verified-result"),
            mappedWorkerNodeIds = setOf(workerNodeId)
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas",
            runId = "run-1",
            version = 1,
            workerAtlasHash = "worker-atlas",
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(graderNodeId to node)
        )
    }

    private fun requirement() = GradingRequirement(
        requirementId = requirementId,
        runId = "run-1",
        graderAtlasHash = atlas().withComputedHash().atlasHash,
        graderAtlasNodeId = graderNodeId,
        graderGroupId = "grader-a",
        round = 1,
        mappedWorkerNodeIds = setOf(workerNodeId),
        mappedReceiverObligationRefs = setOf("verified-result"),
        criterion = "The accepted evidence directly supports the receiver result.",
        observableEvidence = "Hash-bound evidence packet.",
        passThreshold = "All cited evidence is authentic and relevant.",
        failureLevels = listOf(
            FailureLevel(1, "One material claim is unsupported.", 0.7),
            FailureLevel(2, "The receiver conclusion is unsupported.", 0.2, critical = true)
        ),
        adversarialCases = listOf(
            AdversarialCase(
                "case-1",
                "Replace one citation with an unrelated source.",
                "The requirement fails.",
                1.0
            )
        ),
        executableTestIds = listOf(HiddenTestId("test-1")),
        disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
        rationale = "Prevents unsupported receiver conclusions.",
        noveltyFingerprint = "receiver-coverage-v1",
        maximumEvaluationCost = 1.0
    )

    private fun hiddenTest() = HiddenTestDefinition(
        testId = HiddenTestId("test-1"),
        runId = "run-1",
        graderAtlasHash = atlas().withComputedHash().atlasHash,
        graderGroupId = "grader-a",
        roundCreated = 1,
        mappedRequirementIds = setOf(requirementId),
        executorRef = "sandbox:test-1",
        inputSchemaHash = "input-hash",
        expectedOutcomeHash = "outcome-hash",
        difficulty = 0.5,
        detectionRate = 0.8,
        falsePositiveRate = 0.1,
        status = HiddenTestStatus.SEALED
    ).withComputedHash()

    private fun freeze(
        requirement: GradingRequirement,
        test: HiddenTestDefinition,
        snapshots: Map<AtlasGroupId, String>
    ) = GraderCriteriaFreeze(
        freezeId = "freeze-1",
        runId = "run-1",
        round = 1,
        graderGroupId = "grader-a",
        graderAtlasHash = atlas().withComputedHash().atlasHash,
        requirementHashes = setOf(requirement.requirementHash),
        hiddenTestHashes = setOf(test.testHash),
        targetSnapshotHashes = snapshots,
        sealedAtEpochMillis = 5,
        workerRevealEpochMillis = 10
    ).withComputedHash()

    private fun bundle(freeze: GraderCriteriaFreeze) = GraderScoreBundle(
        bundleId = "score-bundle",
        runId = freeze.runId,
        round = freeze.round,
        graderGroupId = freeze.graderGroupId,
        targetGroupId = targetGroupId,
        graderAtlasHash = freeze.graderAtlasHash,
        criteriaFreezeHash = freeze.freezeHash,
        targetSnapshotHash = freeze.targetSnapshotHashes.getValue(targetGroupId),
        nodeScores = mapOf(workerNodeId to scoreVector()),
        groupScores = mapOf("summary" to BoundedScore(0.8)),
        requirementResults = mapOf(
            requirementId to RequirementResult(RequirementResultStatus.PASS)
        ),
        rationaleHashes = mapOf(requirementId.value to "rationale-hash"),
        normalizationTrace = listOf(
            ScoreNormalizationTrace(requirementId.value, 8.0, 0.8, 1.0, "policy")
        ),
        status = GraderScoreBundleStatus.VALID
    ).withComputedHash()

    private fun scoreVector() = NodeScoreVector(
        correctness = 0.8,
        evidenceQuality = 0.8,
        reproducibility = 0.8,
        robustness = 0.8,
        integrationUtility = 0.8,
        receiverRelevance = 0.8,
        novelty = 0.8,
        efficiency = 0.8,
        hardFailure = false,
        confidence = 0.8
    )

    private fun quality() = GraderQualityVector(
        breadth = 0.7,
        depth = 0.7,
        robustness = 0.7,
        creativity = 0.7,
        executability = 0.7,
        calibration = 0.7,
        discrimination = 0.7,
        predictiveValidity = 0.7,
        parsimony = 0.7,
        actionability = 0.7
    )
}
