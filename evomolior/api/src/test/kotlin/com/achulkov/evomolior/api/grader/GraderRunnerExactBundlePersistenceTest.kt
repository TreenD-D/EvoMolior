package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GraderBundleValidationFailure
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestId
import com.achulkov.evomolior.core.grader.HiddenTestStatus
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraderRunnerExactBundlePersistenceTest {
    @Test
    fun `mandatory hidden test failure cannot synthesize missing dimension scores`() {
        val assessment = AuthoredTestAssessment(
            listOf(
                AuthoredTestOutcome(
                    testId = "mandatory-test",
                    description = "Required evidence is absent.",
                    passed = false,
                    mandatory = true,
                    penalty = 0.0
                )
            )
        )

        assertTrue(assessment.applyTo(emptyList()).isEmpty())
    }

    @Test
    fun `sealed completeness probe does not falsely report structured output repair`() = runTest {
        val fixture = fixture("complete-probe")
        val progress = mutableListOf<String>()
        val scores = mutableMapOf(
            fixture.targetGroupId.value to mutableListOf(DimensionScore("accuracy", 80.0, "raw"))
        )
        fixture.runner.repairMissingDimensionScoresFromSealedAuthority(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            targetGroupId = fixture.targetGroupId.value,
            runObjective = "Evaluate evidence",
            criteriaContext = "criteria",
            targetSnapshot = fixture.snapshot,
            scoringDimensions = fixture.dimensions,
            collectedScores = scores,
            onProgress = progress::add
        )
        assertTrue(progress.isEmpty())
        assertEquals(80.0, scores.getValue(fixture.targetGroupId.value).single().rawScore)
    }

    @Test
    fun `grading diagnostic binds raw and gated judgments to exact frozen tests and snapshot`() = runTest {
        val fixture = fixture("diagnostic", snapshot = "No accepted source lineage.")
        val raw = listOf(DimensionScore("accuracy", 80.0, "Raw rationale"))
        val assessment = with(fixture.runner) { fixture.methodology.assess(fixture.snapshot) }
        fixture.runner.persistAuthoredGradingDiagnostic(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            targetGroupId = fixture.targetGroupId.value,
            snapshot = fixture.snapshot,
            assessment = assessment,
            rawScores = raw,
            gatedScores = assessment.applyTo(raw)
        )
        val event = fixture.factory.getGrader(fixture.runId, fixture.graderGroupId)
            .eventLogDao().getByGeneration(fixture.runId, fixture.graderGroupId, 1)
            .single { it.eventType == "AUTHORED_GRADING_DIAGNOSTIC" }
        val diagnostic = Json.decodeFromString<AuthoredGradingDiagnostic>(event.payloadJson)
        assertEquals(AuthoredTargetAuthorityStatus.MATCHED, diagnostic.targetAuthorityStatus)
        assertEquals(AtlasHashing.hashText(fixture.snapshot), diagnostic.targetSnapshotHash)
        assertEquals(
            AtlasHashing.hashText("grader-hidden-test|${fixture.runId}|0|${fixture.graderGroupId}|${fixture.localTestId}"),
            diagnostic.tests.single().frozenTestId
        )
        assertEquals(80.0, diagnostic.rawScores.single().rawScore)
        assertEquals(0.0, diagnostic.gatedScores.single().rawScore)
        assertEquals(AuthoredGateStatus.MANDATORY_FAILURE, diagnostic.gateStatus)
        assertEquals(AuthoredTestExecutionStatus.FAIL, diagnostic.tests.single().status)
    }

    @Test
    fun `executed mandatory failure retains valid zero bundle and independent high node score`() = runTest {
        val fixture = fixture("mandatory-zero", snapshot = "No accepted source lineage.")
        val assessment = with(fixture.runner) { fixture.methodology.assess(fixture.snapshot) }
        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(fixture.response()),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to assessment.applyTo(
                    listOf(DimensionScore("accuracy", 80.0, "Raw quality"))
                )
            ),
            automatedAssessments = mapOf(fixture.targetGroupId.value to assessment),
            authoredDraft = fixture.methodology
        )
        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.VALID, bundle.status)
        assertEquals(0.0, bundle.groupScores.getValue("accuracy").value)
        assertEquals(0.8, bundle.nodeScores.getValue(fixture.workerNodeId).correctness)
        assertTrue(bundle.hasValidHash())
    }

    @Test
    fun `missing or mismatched test authority and execution errors invalidate bundle and diagnostic`() = runTest {
        for (case in listOf("error", "duplicate", "snapshot", "predicate", "missing-snapshot", "missing-predicate", "missing-assessment")) {
            val fixture = fixture("test-authority-$case")
            val outcome = AuthoredTestOutcome(
                testId = fixture.localTestId,
                description = "Evidence marker",
                passed = true,
                mandatory = true,
                penalty = 0.0,
                expectedOutputPattern = when (case) {
                    "missing-predicate" -> null
                    "predicate" -> "different"
                    else -> "(?i)evidence"
                },
                executionError = if (case == "error") "unsupported syntax" else null
            )
            val assessment = if (case == "missing-assessment") null else AuthoredTestAssessment(
                outcomes = if (case == "duplicate") listOf(outcome, outcome) else listOf(outcome),
                targetSnapshotHash = if (case == "missing-snapshot") null else AtlasHashing.hashText(
                    if (case == "snapshot") "other candidate" else fixture.snapshot
                )
            )
            val rawScores = listOf(DimensionScore("accuracy", 80.0, "raw"))
            fixture.runner.persistAuthoredGradingDiagnostic(
                runId = fixture.runId,
                roundIndex = 0,
                graderGroupId = fixture.graderGroupId,
                targetGroupId = fixture.targetGroupId.value,
                snapshot = fixture.snapshot,
                assessment = assessment,
                rawScores = rawScores,
                gatedScores = assessment?.applyTo(rawScores) ?: rawScores
            )
            val event = fixture.factory.getGrader(fixture.runId, fixture.graderGroupId)
                .eventLogDao().getByGeneration(fixture.runId, fixture.graderGroupId, 1)
                .single { it.eventType == "AUTHORED_GRADING_DIAGNOSTIC" }
            val diagnostic = Json.decodeFromString<AuthoredGradingDiagnostic>(event.payloadJson)
            assertEquals(AuthoredTargetAuthorityStatus.MISMATCH, diagnostic.targetAuthorityStatus, case)
            fixture.runner.persistExactGraderScoreBundles(
                runId = fixture.runId,
                roundIndex = 0,
                graderGroupId = fixture.graderGroupId,
                competingGroupIds = listOf(fixture.targetGroupId.value),
                scoringDimensions = fixture.dimensions,
                allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
                nodeGradingAssignments = listOf(fixture.assignment),
                nodeGradeResponses = listOf(fixture.response()),
                authoritativeScoresByTarget = mapOf(
                    fixture.targetGroupId.value to rawScores
                ),
                automatedAssessments = mapOf(fixture.targetGroupId.value to assessment),
                authoredDraft = fixture.methodology
            )
            val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
            assertEquals(GraderScoreBundleStatus.INVALID, bundle.status, case)
            assertTrue(GraderBundleValidationFailure.HIDDEN_TEST_RESULTS_INCOMPLETE in bundle.validationFailures, case)
        }
    }

    @Test
    fun `production assembler persists complete exact grader bundle`() = runTest {
        val fixture = fixture("valid")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(fixture.response()),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Exact evidence supports this score.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.VALID, bundle.status)
        assertEquals(setOf(fixture.workerNodeId), bundle.nodeScores.keys)
        assertEquals(0.8, bundle.groupScores.getValue("accuracy").value)
        assertEquals(
            RequirementResultStatus.PASS,
            bundle.requirementResults.getValue(fixture.requirementId).status
        )
        assertTrue(
            bundle.requirementResults.getValue(fixture.requirementId)
                .executableTestResultHashes
                .isNotEmpty()
        )
        assertEquals(2, bundle.normalizationTrace.size)

        fixture.service().persistCompetitiveGraderFitness(
            runId = fixture.runId,
            round = 0,
            graderGroupIds = listOf(fixture.graderGroupId),
            graderAtlas = fixture.atlas,
            bundles = listOf(bundle),
            consensusScores = mapOf(
                fixture.targetGroupId to mapOf("accuracy" to 0.8)
            )
        )
        val fitness = fixture.repository.fitnessHistory(
            fixture.runId,
            fixture.graderGroupId
        )
        val requirementHash = fixture.repository.requirementsForRound(
            fixture.runId,
            0,
            fixture.graderGroupId
        ).single().requirementHash
        assertEquals(2, fitness.size)
        assertTrue(fitness.all { it.eligibleRequirementHashes == setOf(requirementHash) })
        assertTrue(fitness.all { it.deterministicSeedHashes.isEmpty() })
    }

    @Test
    fun `conflicting duplicate bundle fails closed and preserves first authority`() = runTest {
        val fixture = fixture("duplicate-bundle")
        suspend fun persist(score: Double) {
            fixture.runner.persistExactGraderScoreBundles(
                runId = fixture.runId,
                roundIndex = 0,
                graderGroupId = fixture.graderGroupId,
                competingGroupIds = listOf(fixture.targetGroupId.value),
                scoringDimensions = fixture.dimensions,
                allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
                nodeGradingAssignments = listOf(fixture.assignment),
                nodeGradeResponses = listOf(fixture.response()),
                authoritativeScoresByTarget = mapOf(
                    fixture.targetGroupId.value to listOf(
                        DimensionScore("accuracy", score, "Exact evidence supports this score.")
                    )
                ),
                automatedAssessments = mapOf(
                    fixture.targetGroupId.value to fixture.assessment()
                ),
                authoredDraft = fixture.methodology
            )
        }

        persist(80.0)
        val original = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()

        assertFailsWith<IllegalStateException> {
            persist(70.0)
        }

        val persisted = fixture.repository.scoreBundlesForRound(fixture.runId, 0)
        assertEquals(1, persisted.size)
        assertEquals(original, persisted.single())
        assertEquals(original.payloadHash, persisted.single().payloadHash)
    }

    @Test
    fun `abstained node response remains scoreless and visible`() = runTest {
        val fixture = fixture("abstained")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(
                fixture.response(
                    verdict = NodeGraderResponseVerdict.ABSTAINED,
                    scoreVector = null
                )
            ),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Exact broad-dimension evidence.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.ABSTAINED, bundle.status)
        assertTrue(bundle.nodeScores.isEmpty())
        assertTrue(bundle.validationFailures.isEmpty())
        assertEquals(
            RequirementResultStatus.CONTESTED,
            bundle.requirementResults.getValue(fixture.requirementId).status
        )
    }

    @Test
    fun `invalid response authority remains invalid and cannot synthesize node scores`() = runTest {
        val fixture = fixture("invalid-response")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(
                fixture.response(
                    verdict = NodeGraderResponseVerdict.INVALID,
                    scoreVector = null
                )
            ),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Exact broad-dimension evidence.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.INVALID, bundle.status)
        assertTrue(bundle.nodeScores.isEmpty())
        assertEquals(
            setOf(GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_AUTHORITY_INVALID),
            bundle.validationFailures
        )
        assertEquals(
            RequirementResultStatus.INVALID,
            bundle.requirementResults.getValue(fixture.requirementId).status
        )
    }

    @Test
    fun `missing frozen test implementation persists invalid authority`() = runTest {
        val fixture = fixture("missing-test")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(fixture.response()),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Score rationale.")
                )
            ),
            automatedAssessments = emptyMap(),
            authoredDraft = null
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.INVALID, bundle.status)
        assertEquals(
            setOf(GraderBundleValidationFailure.HIDDEN_TEST_RESULTS_INCOMPLETE),
            bundle.validationFailures
        )
    }

    @Test
    fun `missing broad dimension authority persists invalid bundle without zero fill`() = runTest {
        val fixture = fixture("missing-dimensions")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(fixture.response()),
            authoritativeScoresByTarget = emptyMap(),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.INVALID, bundle.status)
        assertTrue(bundle.groupScores.isEmpty())
        assertEquals(
            setOf(GraderBundleValidationFailure.DIMENSIONS_INCOMPLETE),
            bundle.validationFailures
        )
    }

    @Test
    fun `target without evidence persists valid empty node authority`() = runTest {
        val fixture = fixture("no-evidence")

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = emptyList(),
            nodeGradeResponses = emptyList(),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "No node evidence was submitted.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment(passed = false)
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.VALID, bundle.status)
        assertTrue(bundle.nodeScores.isEmpty())
        assertTrue(bundle.validationFailures.isEmpty())
        assertTrue(
            bundle.requirementResults.values.all {
                it.status == RequirementResultStatus.NOT_APPLICABLE
            }
        )
    }

    @Test
    fun `abstention cannot mask duplicate assignment authority`() = runTest {
        val fixture = fixture("abstain-duplicate")
        val abstained = fixture.response(
            verdict = NodeGraderResponseVerdict.ABSTAINED,
            scoreVector = null
        )

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment, fixture.assignment),
            nodeGradeResponses = listOf(abstained),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Score rationale.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.INVALID, bundle.status)
        assertTrue(
            GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_STRUCTURE_INVALID in
                bundle.validationFailures
        )
    }

    @Test
    fun `duplicate response authority persists invalid without a node score`() = runTest {
        val fixture = fixture("duplicate-response")
        val first = fixture.response()
        val second = first.copy(
            rationale = "A conflicting duplicate response.",
            responseHash = ""
        ).withComputedHash()

        fixture.runner.persistExactGraderScoreBundles(
            runId = fixture.runId,
            roundIndex = 0,
            graderGroupId = fixture.graderGroupId,
            competingGroupIds = listOf(fixture.targetGroupId.value),
            scoringDimensions = fixture.dimensions,
            allGroupSnapshots = mapOf(fixture.targetGroupId.value to fixture.snapshot),
            nodeGradingAssignments = listOf(fixture.assignment),
            nodeGradeResponses = listOf(first, second),
            authoritativeScoresByTarget = mapOf(
                fixture.targetGroupId.value to listOf(
                    DimensionScore("accuracy", 80.0, "Exact broad-dimension evidence.")
                )
            ),
            automatedAssessments = mapOf(
                fixture.targetGroupId.value to fixture.assessment()
            ),
            authoredDraft = fixture.methodology
        )

        val bundle = fixture.repository.scoreBundlesForRound(fixture.runId, 0).single()
        assertEquals(GraderScoreBundleStatus.INVALID, bundle.status)
        assertTrue(bundle.nodeScores.isEmpty())
        assertTrue(
            GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_STRUCTURE_INVALID in
                bundle.validationFailures
        )
    }

    private suspend fun fixture(
        suffix: String,
        snapshot: String = "Promoted evidence with exact source lineage."
    ): Fixture {
        val runId = "run-exact-$suffix"
        val graderGroupId = "grader-a"
        val targetGroupId = AtlasGroupId("worker-a")
        val workerNodeId = NodeId("worker-node")
        val graderNodeId = GraderNodeId("grader-node")
        val localRequirementId = "requirement-local"
        val localTestId = "test-local"
        val requirementId = GradingRequirementId(
            AtlasHashing.hashText(
                "grader-requirement|$runId|0|$graderGroupId|$localRequirementId"
            )
        )
        val testId = HiddenTestId(
            AtlasHashing.hashText("grader-hidden-test|$runId|0|$graderGroupId|$localTestId")
        )
        val testPattern = "(?i)evidence"
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-exact-bundle-").toFile()
        )
        val repository = GraderAuthorityRepository(factory, now = { 100L })
        val atlasNode = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is the evidence authentic?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = setOf(workerNodeId)
        ).withComputedHash()
        val atlas = repository.appendAtlas(
            GraderAtlas(
                graderAtlasId = "grader-atlas-$runId",
                runId = runId,
                version = 1,
                workerAtlasHash = "worker-atlas-hash",
                rootNodeIds = setOf(graderNodeId),
                nodes = mapOf(graderNodeId to atlasNode)
            ).withComputedHash()
        )
        val requirement = GradingRequirement(
            requirementId = requirementId,
            runId = runId,
            graderAtlasHash = atlas.atlasHash,
            graderAtlasNodeId = graderNodeId,
            graderGroupId = graderGroupId,
            round = 0,
            mappedWorkerNodeIds = setOf(workerNodeId),
            mappedReceiverObligationRefs = setOf("receiver:evidence"),
            criterion = "Every claim is evidence-bound.",
            observableEvidence = "Exact packet evidence.",
            passThreshold = "All material claims have support.",
            failureLevels = listOf(FailureLevel(1, "Unsupported claim.", 0.4)),
            adversarialCases = listOf(
                AdversarialCase("case-1", "Remove a source.", "Detect it.", 1.0)
            ),
            executableTestIds = listOf(testId),
            disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
            rationale = "Receiver needs auditable evidence.",
            noveltyFingerprint = "evidence-v1",
            maximumEvaluationCost = 1.0
        ).withComputedHash()
        val hiddenTest = HiddenTestDefinition(
            testId = testId,
            runId = runId,
            graderAtlasHash = atlas.atlasHash,
            graderGroupId = graderGroupId,
            roundCreated = 0,
            mappedRequirementIds = setOf(requirementId),
            executorRef = "grader-regex:$localTestId:${AtlasHashing.hashText(testPattern)}",
            inputSchemaHash = "snapshot-v1",
            expectedOutcomeHash = "evidence-present",
            difficulty = 0.5,
            status = HiddenTestStatus.SEALED
        ).withComputedHash()
        repository.appendCriteriaBatch(runId, listOf(requirement), listOf(hiddenTest))
        val snapshotHash = AtlasHashing.hashText(snapshot)
        val freeze = repository.appendCriteriaFreeze(
            GraderCriteriaFreeze(
                freezeId = "freeze-$runId",
                runId = runId,
                round = 0,
                graderGroupId = graderGroupId,
                graderAtlasHash = atlas.atlasHash,
                requirementHashes = setOf(requirement.requirementHash),
                hiddenTestHashes = setOf(hiddenTest.testHash),
                targetSnapshotHashes = mapOf(targetGroupId to snapshotHash),
                sealedAtEpochMillis = 10,
                workerRevealEpochMillis = 10
            ).withComputedHash(),
            expectedTargetSnapshotHashes = mapOf(targetGroupId to snapshotHash)
        )
        val packet = NodeEvidencePacket(
            packetId = "packet-$runId",
            runId = runId,
            round = 0,
            groupId = targetGroupId,
            nodeId = workerNodeId,
            atlasVersion = 1,
            atlasHash = "worker-atlas-hash",
            nodePayloadHash = "worker-node-hash",
            acceptedArtifactIds = listOf("artifact-1"),
            provenanceRootHash = "provenance-root",
            cost = NodeEvidenceCostSummary(totalCredits = 1.0),
            sealedAtEpochMillis = 20
        ).withComputedHash()
        val capturePolicy = CapturePolicy(minimumGrade = 0.6)
        val assignment = NodeGradingAssignment(
            assignmentId = "assignment-$runId",
            packet = packet,
            criteriaHash = "node-criteria-hash",
            graderCriteriaFreezeHashes = mapOf(graderGroupId to freeze.freezeHash),
            capturePolicy = capturePolicy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(capturePolicy),
            requiredQuorum = 1
        ).withComputedHash()
        val methodology = GraderMethodologyDraft(
            rubricDimensions = listOf(
                GraderRubricDraft("accuracy", "Grade exact evidence.")
            ),
            automatedTests = listOf(
                GraderAutomatedTestDraft(
                    testId = localTestId,
                    description = "Evidence marker",
                    expectedOutputPattern = testPattern,
                    isMandatory = true,
                    mappedRequirementIds = setOf(localRequirementId),
                    inputSchemaHash = "snapshot-v1",
                    expectedOutcomeHash = "evidence-present",
                    difficulty = 0.5
                )
            ),
            failureModes = listOf(
                GraderFailureModeDraft("unsupported", "Unsupported claim.")
            ),
            requirements = listOf(
                GraderRequirementDraft(
                    requirementId = localRequirementId,
                    graderAtlasNodeId = graderNodeId,
                    mappedWorkerNodeIds = setOf(workerNodeId),
                    mappedReceiverObligationRefs = setOf("receiver:evidence"),
                    criterion = "Every claim is evidence-bound.",
                    observableEvidence = "Exact packet evidence.",
                    passThreshold = "All material claims have support.",
                    failureLevels = listOf(FailureLevel(1, "Unsupported claim.", 0.4)),
                    adversarialCases = emptyList(),
                    executableTestIds = listOf(localTestId),
                    disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
                    rationale = "Receiver needs evidence.",
                    noveltyFingerprint = "evidence-v1",
                    maximumEvaluationCost = 1.0
                )
            )
        )
        return Fixture(
            runId = runId,
            graderGroupId = graderGroupId,
            targetGroupId = targetGroupId,
            workerNodeId = workerNodeId,
            requirementId = requirementId,
            localTestId = localTestId,
            snapshot = snapshot,
            dimensions = listOf(
                ScoringDimension("accuracy", "Accuracy", "Exact evidence.", 1.0)
            ),
            assignment = assignment,
            methodology = methodology,
            repository = repository,
            atlas = atlas,
            factory = factory,
            runner = GraderRunner(
                openRouterApiKey = "test",
                substrateFactory = factory,
                localAgentRunner = { "" }
            )
        )
    }

    private data class Fixture(
        val runId: String,
        val graderGroupId: String,
        val targetGroupId: AtlasGroupId,
        val workerNodeId: NodeId,
        val requirementId: GradingRequirementId,
        val localTestId: String,
        val snapshot: String,
        val dimensions: List<ScoringDimension>,
        val assignment: NodeGradingAssignment,
        val methodology: GraderMethodologyDraft,
        val repository: GraderAuthorityRepository,
        val atlas: GraderAtlas,
        val factory: SubstrateDatabaseFactory,
        val runner: GraderRunner
    ) {
        fun assessment(passed: Boolean = true) = AuthoredTestAssessment(
            outcomes = listOf(
                AuthoredTestOutcome(
                    testId = localTestId,
                    description = "Evidence marker",
                    passed = passed,
                    mandatory = true,
                    penalty = 0.0,
                    expectedOutputPattern = methodology.automatedTests.single().expectedOutputPattern
                )
            ),
            targetSnapshotHash = AtlasHashing.hashText(snapshot)
        )

        fun service() = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory
        )

        fun response(
            verdict: NodeGraderResponseVerdict = NodeGraderResponseVerdict.VALID,
            scoreVector: NodeScoreVector? = NodeScoreVector(
                correctness = 0.8,
                evidenceQuality = 0.8,
                reproducibility = 0.8,
                robustness = 0.8,
                integrationUtility = 0.8,
                receiverRelevance = 0.8,
                novelty = 0.7,
                efficiency = 0.7,
                hardFailure = false,
                confidence = 0.9
            )
        ): NodeGraderResponse = NodeGraderResponse(
            responseId = "response-$runId-${verdict.name}",
            assignmentId = assignment.assignmentId,
            assignmentHash = assignment.assignmentHash,
            graderId = graderGroupId,
            scoreVector = scoreVector,
            verdict = verdict,
            rationale = "Exact packet rationale."
        ).withComputedHash()
    }
}
