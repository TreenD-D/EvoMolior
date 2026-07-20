package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompetitiveGraderCriteriaFreezeAcceptanceTest {
    @Test
    fun `production criteria freeze reacquires an expired deterministic frontier lease`() =
        runTest {
            val runId = "run-expired-freeze-lease"
            val graderGroupId = "grader-a"
            val targetGroupId = "worker-a"
            val workerNodeId = NodeId("worker-node")
            val graderNodeId = GraderNodeId("grader-node")
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("competitive-expired-freeze-lease-").toFile()
            )
            val repository = GraderAuthorityRepository(factory)
            val workerAtlas = initializeTestWorkerAtlas(
                factory,
                runId,
                setOf(workerNodeId),
                targetGroupId
            )
            val atlas = repository.appendAtlas(
                graderAtlas(runId, graderNodeId, workerNodeId, workerAtlas.atlasHash)
            )
            val expiredLeaseAuthority = GraderNodeLeaseAuthority(repository) { 0L }
            expiredLeaseAuthority.initialize(atlas)
            expiredLeaseAuthority.acquireNext(
                atlas = atlas,
                graderGroupId = graderGroupId,
                round = 0,
                ttlMillis = 1L
            )
            factory.getGrader(runId, graderGroupId).stagingDao().insert(
                promotedEntry(
                    id = "typed-methodology-after-expiry",
                    runId = runId,
                    groupId = graderGroupId,
                    content = Json.encodeToString(
                        GraderMethodologyDraft.serializer(),
                        methodology(graderNodeId, workerNodeId, "criterion-after-expiry")
                    ),
                    submittedAtMs = 200
                )
            )

            val freeze = service(factory).freezeCompetitiveGraderCriteria(
                runId = runId,
                graderGroupIds = listOf(graderGroupId),
                competingGroupIds = listOf(targetGroupId),
                round = 0
            )

            assertEquals(setOf(graderGroupId), freeze.keys)
            assertEquals(
                GraderNodeStatus.REQUIREMENTS_COMMITTED,
                repository.currentState(
                    runId,
                    atlas.atlasHash,
                    graderGroupId,
                    graderNodeId
                )?.status
            )
            assertEquals(
                "criterion-after-expiry",
                repository.requirementsForRound(runId, 0, graderGroupId).single().criterion
            )
        }

    @Test
    fun `production criteria freeze is immutable and rejects changed worker snapshot`() = runTest {
        val runId = "run-freeze"
        val graderGroupId = "grader-a"
        val targetGroupId = "worker-a"
        val workerNodeId = NodeId("worker-node")
        val graderNodeId = GraderNodeId("grader-node")
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("competitive-criteria-freeze-").toFile()
        )
        val repository = GraderAuthorityRepository(factory)
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(workerNodeId),
            targetGroupId
        )
        val atlas = repository.appendAtlas(
            graderAtlas(runId, graderNodeId, workerNodeId, workerAtlas.atlasHash)
        )
        val leaseAuthority = GraderNodeLeaseAuthority(repository)
        leaseAuthority.initialize(atlas)
        leaseAuthority.acquireNext(
            atlas = atlas,
            graderGroupId = graderGroupId,
            round = 0,
            ttlMillis = 60 * 60_000L
        )
        factory.get(runId, targetGroupId).stagingDao().insert(
            promotedEntry(
                id = "worker-before-freeze",
                runId = runId,
                groupId = targetGroupId,
                content = "Promoted worker evidence before reveal.",
                submittedAtMs = 100
            )
        )
        val graderDb = factory.getGrader(runId, graderGroupId)
        graderDb.stagingDao().insert(
            promotedEntry(
                id = "typed-methodology-v1",
                runId = runId,
                groupId = graderGroupId,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    methodology(graderNodeId, workerNodeId, "criterion-v1")
                ),
                submittedAtMs = 200
            )
        )
        val service = service(factory)

        val first = service.freezeCompetitiveGraderCriteria(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(targetGroupId),
            round = 0
        ).getValue(graderGroupId)
        val requirements = repository.requirementsForRound(runId, 0, graderGroupId)
        val tests = repository.hiddenTestsForFreeze(first)
        assertEquals(1, requirements.size)
        assertEquals(1, tests.size)
        assertEquals(setOf(requirements.single().requirementHash), first.requirementHashes)
        assertEquals(setOf(tests.single().testHash), first.hiddenTestHashes)
        assertTrue(tests.single().executorRef.contains("grader-regex:test-local:"))

        graderDb.stagingDao().insert(
            promotedEntry(
                id = "typed-methodology-v2",
                runId = runId,
                groupId = graderGroupId,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    methodology(graderNodeId, workerNodeId, "criterion-mutated")
                ),
                submittedAtMs = 300
            )
        )
        val second = service.freezeCompetitiveGraderCriteria(
            runId,
            listOf(graderGroupId),
            listOf(targetGroupId),
            0
        ).getValue(graderGroupId)
        assertEquals(first, second)
        assertEquals("criterion-v1", repository.requirementsForRound(
            runId,
            0,
            graderGroupId
        ).single().criterion)

        factory.get(runId, targetGroupId).stagingDao().insert(
            promotedEntry(
                id = "worker-after-freeze",
                runId = runId,
                groupId = targetGroupId,
                content = "Mutation after the sealed worker snapshot.",
                submittedAtMs = 400
            )
        )
        val rejected = service.freezeCompetitiveGraderCriteria(
            runId,
            listOf(graderGroupId),
            listOf(targetGroupId),
            0
        )
        assertTrue(rejected.isEmpty())
    }

    private fun service(factory: SubstrateDatabaseFactory) = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = factory
    )

    private fun graderAtlas(
        runId: String,
        graderNodeId: GraderNodeId,
        workerNodeId: NodeId,
        workerAtlasHash: String
    ): GraderAtlas {
        val node = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is every material claim supported?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = setOf(workerNodeId)
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas-$runId",
            runId = runId,
            version = 1,
            workerAtlasHash = workerAtlasHash,
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(graderNodeId to node)
        ).withComputedHash()
    }

    private fun methodology(
        graderNodeId: GraderNodeId,
        workerNodeId: NodeId,
        criterion: String
    ) = GraderMethodologyDraft(
        rubricDimensions = listOf(
            GraderRubricDraft("accuracy", "Grade exact evidence.")
        ),
        automatedTests = listOf(
            GraderAutomatedTestDraft(
                testId = "test-local",
                description = "Evidence marker",
                expectedOutputPattern = "(?i)evidence",
                mappedRequirementIds = setOf("requirement-local"),
                inputSchemaHash = "snapshot-v1",
                expectedOutcomeHash = "evidence-present",
                difficulty = 0.5
            )
        ),
        failureModes = listOf(
            GraderFailureModeDraft("unsupported", "Unsupported material claim.")
        ),
        requirements = listOf(
            GraderRequirementDraft(
                requirementId = "requirement-local",
                graderAtlasNodeId = graderNodeId,
                mappedWorkerNodeIds = setOf(workerNodeId),
                mappedReceiverObligationRefs = setOf("receiver:evidence"),
                criterion = criterion,
                observableEvidence = "Exact promoted evidence.",
                passThreshold = "All material claims are supported.",
                failureLevels = listOf(FailureLevel(1, "Unsupported claim.", 0.4)),
                adversarialCases = emptyList(),
                executableTestIds = listOf("test-local"),
                disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
                rationale = "Receiver needs auditable evidence.",
                noveltyFingerprint = "evidence-v1",
                maximumEvaluationCost = 1.0
            )
        )
    )

    private fun promotedEntry(
        id: String,
        runId: String,
        groupId: String,
        content: String,
        submittedAtMs: Long
    ) = StagingSubmissionEntity(
        id = id,
        agentId = "agent-$id",
        role = "GraderResearcher",
        type = "PLAN_UPDATE",
        content = content,
        budgetSignature = "GRADING_SHEET_DRAFT:agent-$id:1",
        status = "promoted",
        generation = 1,
        groupId = groupId,
        runId = runId,
        submittedAtMs = submittedAtMs,
        reviewedAtMs = submittedAtMs + 1
    )
}
