package com.achulkov.evomolior.substrate.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.BoundedScore
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfiler
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderFitnessPlane
import com.achulkov.evomolior.core.grader.GraderFitnessRecord
import com.achulkov.evomolior.core.grader.GraderNodeExecutionState
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeLease
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.core.grader.GraderQualityVector
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestId
import com.achulkov.evomolior.core.grader.HiddenTestStatus
import com.achulkov.evomolior.core.grader.RequirementResult
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import com.achulkov.evomolior.core.grader.ScoreNormalizationTrace
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraderAuthorityRepositoryTest {
    private val runId = "run-grader-authority"
    private val graderGroupId = "grader-a"
    private val targetGroupId = AtlasGroupId("worker-a")
    private val workerNodeId = NodeId("worker-node")
    private val graderNodeId = GraderNodeId("grader-node")
    private val requirementId = GradingRequirementId("requirement-1")

    @Test
    fun `authority chain is append only exact and queryable`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-authority-repository-").toFile()
        )
        val repository = GraderAuthorityRepository(factory, now = { 100L })
        val atlas = atlas()
        assertEquals(atlas, repository.appendAtlas(atlas))
        assertEquals(atlas, repository.appendAtlas(atlas))

        val state = GraderNodeExecutionState(
            nodeId = graderNodeId,
            runId = runId,
            graderAtlasHash = atlas.atlasHash,
            graderGroupId = graderGroupId,
            revision = 0,
            status = GraderNodeStatus.OPEN
        ).withComputedHash()
        assertEquals(state, repository.appendState(state))
        assertEquals(state, repository.appendState(state))
        assertEquals(
            state,
            repository.currentState(runId, atlas.atlasHash, graderGroupId, graderNodeId)
        )

        val otherGroupState = state.copy(
            graderGroupId = "grader-b",
            stateHash = ""
        ).withComputedHash()
        assertEquals(otherGroupState, repository.appendState(otherGroupState))
        assertEquals(
            state,
            repository.currentState(runId, atlas.atlasHash, graderGroupId, graderNodeId)
        )
        assertEquals(
            otherGroupState,
            repository.currentState(runId, atlas.atlasHash, "grader-b", graderNodeId)
        )

        val lease = GraderNodeLease(
            leaseId = "lease-1",
            runId = runId,
            graderAtlasHash = atlas.atlasHash,
            nodeId = graderNodeId,
            graderGroupId = graderGroupId,
            acquiredAtEpochMillis = 10,
            expiresAtEpochMillis = 20
        ).withComputedHash()
        assertEquals(lease, repository.appendLease(lease))

        val requirement = requirement(atlas)
        val hiddenTest = hiddenTest(atlas)
        val (requirements, tests) = repository.appendCriteriaBatch(
            runId,
            listOf(requirement),
            listOf(hiddenTest)
        )
        assertEquals(listOf(requirement), requirements)
        assertEquals(listOf(hiddenTest), tests)

        val freeze = freeze(atlas, requirement, hiddenTest)
        assertEquals(
            freeze,
            repository.appendCriteriaFreeze(freeze, freeze.targetSnapshotHashes)
        )
        assertEquals(
            freeze,
            repository.appendCriteriaFreeze(freeze, freeze.targetSnapshotHashes)
        )

        val bundle = bundle(atlas, freeze)
        assertEquals(
            bundle,
            repository.appendScoreBundle(
                bundle,
                requiredNodeIds = setOf(workerNodeId),
                requiredDimensionIds = setOf("summary"),
                requiredRequirementIds = setOf(requirementId)
            )
        )
        assertEquals(listOf(bundle), repository.scoreBundlesForRound(runId, 1))

        val fitness = fitness(requirement)
        assertEquals(fitness, repository.appendFitnessRecord(fitness))
        assertEquals(listOf(fitness), repository.fitnessHistory(runId, graderGroupId))

        val independence = EvaluationIndependenceProfiler.profile(
            workerFamilies = setOf("family-a"),
            graderFamilies = setOf("family-a"),
            providers = setOf("provider-a"),
            sharedPromptTemplateFraction = 0.5
        )
        assertEquals(
            independence,
            repository.appendIndependenceProfile(runId, independence)
        )
    }

    @Test
    fun `criteria freeze and score cardinality fail closed`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-authority-invalid-").toFile()
        )
        val repository = GraderAuthorityRepository(factory)
        val atlas = repository.appendAtlas(atlas())
        val requirement = requirement(atlas)
        val hiddenTest = hiddenTest(atlas)
        repository.appendCriteriaBatch(runId, listOf(requirement), listOf(hiddenTest))

        assertFailsWith<IllegalArgumentException> {
            repository.appendCriteriaFreeze(
                freeze(atlas, requirement, hiddenTest).copy(
                    targetSnapshotHashes = mapOf(targetGroupId to "tampered"),
                    freezeHash = ""
                ).withComputedHash(),
                expectedTargetSnapshotHashes = mapOf(targetGroupId to "worker-snapshot")
            )
        }

        val freeze = repository.appendCriteriaFreeze(
            freeze(atlas, requirement, hiddenTest),
            expectedTargetSnapshotHashes = mapOf(targetGroupId to "worker-snapshot")
        )
        assertFailsWith<IllegalArgumentException> {
            repository.appendScoreBundle(
                bundle(atlas, freeze),
                requiredNodeIds = setOf(workerNodeId, NodeId("missing")),
                requiredDimensionIds = setOf("summary"),
                requiredRequirementIds = setOf(requirementId)
            )
        }

        val contaminated = fitness(requirement).copy(
            eligibleRequirementHashes = setOf(
                requirement.requirementHash,
                "deterministic-seed"
            ),
            recordHash = ""
        ).withComputedHash()
        assertFailsWith<IllegalArgumentException> {
            repository.appendFitnessRecord(contaminated)
        }
    }

    private fun atlas(): GraderAtlas {
        val node = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.RECEIVER_COVERAGE,
            title = "Receiver coverage",
            evaluationQuestion = "Does the worker evidence satisfy the receiver?",
            receiverObligationRefs = setOf("receiver-result"),
            mappedWorkerNodeIds = setOf(workerNodeId)
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas",
            runId = runId,
            version = 1,
            workerAtlasHash = "worker-atlas-hash",
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(graderNodeId to node)
        ).withComputedHash()
    }

    private fun requirement(atlas: GraderAtlas) = GradingRequirement(
        requirementId = requirementId,
        runId = runId,
        graderAtlasHash = atlas.atlasHash,
        graderAtlasNodeId = graderNodeId,
        graderGroupId = graderGroupId,
        round = 1,
        mappedWorkerNodeIds = setOf(workerNodeId),
        mappedReceiverObligationRefs = setOf("receiver-result"),
        criterion = "Evidence directly supports the receiver result.",
        observableEvidence = "Hash-bound node packet.",
        passThreshold = "All material claims have authentic support.",
        failureLevels = listOf(FailureLevel(1, "Material support is absent.", 0.4)),
        adversarialCases = listOf(
            AdversarialCase("case-1", "Substitute an unrelated source.", "Fail.", 1.0)
        ),
        executableTestIds = listOf(HiddenTestId("test-1")),
        disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
        rationale = "Prevents unsupported output.",
        noveltyFingerprint = "receiver-result-v1",
        maximumEvaluationCost = 1.0
    ).withComputedHash()

    private fun hiddenTest(atlas: GraderAtlas) = HiddenTestDefinition(
        testId = HiddenTestId("test-1"),
        runId = runId,
        graderAtlasHash = atlas.atlasHash,
        graderGroupId = graderGroupId,
        roundCreated = 1,
        mappedRequirementIds = setOf(requirementId),
        executorRef = "sandbox:test-1",
        inputSchemaHash = "input",
        expectedOutcomeHash = "outcome",
        difficulty = 0.5,
        detectionRate = 0.8,
        falsePositiveRate = 0.1,
        status = HiddenTestStatus.SEALED
    ).withComputedHash()

    private fun freeze(
        atlas: GraderAtlas,
        requirement: GradingRequirement,
        hiddenTest: HiddenTestDefinition
    ) = GraderCriteriaFreeze(
        freezeId = "freeze-1",
        runId = runId,
        round = 1,
        graderGroupId = graderGroupId,
        graderAtlasHash = atlas.atlasHash,
        requirementHashes = setOf(requirement.requirementHash),
        hiddenTestHashes = setOf(hiddenTest.testHash),
        targetSnapshotHashes = mapOf(targetGroupId to "worker-snapshot"),
        sealedAtEpochMillis = 10,
        workerRevealEpochMillis = 20
    ).withComputedHash()

    private fun bundle(
        atlas: GraderAtlas,
        freeze: GraderCriteriaFreeze
    ) = GraderScoreBundle(
        bundleId = "bundle-1",
        runId = runId,
        round = 1,
        graderGroupId = graderGroupId,
        targetGroupId = targetGroupId,
        graderAtlasHash = atlas.atlasHash,
        criteriaFreezeHash = freeze.freezeHash,
        targetSnapshotHash = "worker-snapshot",
        nodeScores = mapOf(
            workerNodeId to NodeScoreVector(
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
        ),
        groupScores = mapOf("summary" to BoundedScore(0.8)),
        requirementResults = mapOf(
            requirementId to RequirementResult(RequirementResultStatus.PASS)
        ),
        rationaleHashes = mapOf(requirementId.value to "rationale"),
        normalizationTrace = listOf(
            ScoreNormalizationTrace(requirementId.value, 8.0, 0.8, 1.0, "policy")
        ),
        status = GraderScoreBundleStatus.VALID
    ).withComputedHash()

    private fun fitness(requirement: GradingRequirement) = GraderFitnessRecord(
        recordId = "fitness-1",
        runId = runId,
        round = 1,
        graderGroupId = graderGroupId,
        plane = GraderFitnessPlane.REQUIREMENT_DISCOVERY,
        quality = GraderQualityVector(
            breadth = 0.8,
            depth = 0.7,
            robustness = 0.7,
            creativity = 0.6,
            executability = 0.8,
            calibration = 0.7,
            discrimination = 0.7,
            predictiveValidity = 0.6,
            parsimony = 0.8,
            actionability = 0.7
        ),
        eligibleRequirementHashes = setOf(requirement.requirementHash),
        deterministicSeedHashes = setOf("deterministic-seed"),
        aggregateScore = 0.71,
        policyHash = "fitness-policy"
    ).withComputedHash()
}
