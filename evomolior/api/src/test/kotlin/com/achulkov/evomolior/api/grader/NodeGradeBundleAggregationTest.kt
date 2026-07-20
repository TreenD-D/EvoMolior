package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceContent
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceKind
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeGradeBundleAggregationTest {
    @Test
    fun `structured repair binds model semantics to immutable assignment commitments`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val requests = mutableListOf<com.achulkov.evomolior.agents.factory.StructuredNodeGradeRequest>()
        val responses = mutableListOf<NodeGraderResponse>()
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-structured-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = { request ->
                requests += request
                """
                {"verdict":"VALID","scoreVector":{"correctness":0.8,
                "evidenceQuality":0.7,"reproducibility":0.6,"robustness":0.7,
                "integrationUtility":0.8,"receiverRelevance":0.9,"novelty":0.5,
                "efficiency":0.7,"hardFailure":false,"confidence":0.85},
                "rationale":"The exact packet contains inspectable accepted evidence."}
                """.trimIndent()
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(1, requests.size)
        assertEquals(
            "Complete exact accepted artifact evidence.",
            requests.single().evaluationContext.evidence.single().content
        )
        val response = responses.single()
        assertEquals(assignment.assignmentId, response.assignmentId)
        assertEquals(assignment.assignmentHash, response.assignmentHash)
        assertEquals("grader-a", response.graderId)
        assertEquals(NodeGraderResponseVerdict.VALID, response.verdict)
        assertTrue(response.hasValidHash())
    }

    @Test
    fun `structured reconsideration may replace one abstention with a model authored grade`() =
        runTest {
            val assignment = assignment(requiredQuorum = 1)
            val original = abstainedResponse(
                assignment = assignment,
                rationale = "The packet appears insufficient to support a grade."
            )
            val responses = mutableListOf(original)
            val requests =
                mutableListOf<com.achulkov.evomolior.agents.factory.StructuredNodeGradeRequest>()
            val runner = GraderRunner(
                openRouterApiKey = "test",
                substrateFactory = SubstrateDatabaseFactory(
                    createTempDirectory("node-grade-abstention-repair-").toFile()
                ),
                localAgentRunner = { "" },
                structuredNodeGradeAuthor = { request ->
                    requests += request
                    """
                    {"verdict":"VALID","scoreVector":{"correctness":0.3,
                    "evidenceQuality":0.4,"reproducibility":0.2,"robustness":0.2,
                    "integrationUtility":0.3,"receiverRelevance":0.4,"novelty":0.1,
                    "efficiency":0.5,"hardFailure":true,"confidence":0.75},
                    "rationale":"The exact packet is evaluable but fails mandatory criteria."}
                    """.trimIndent()
                }
            )

            runner.repairMissingNodeGrades(
                runId = "run-1",
                graderGroupId = "grader-a",
                runObjective = "Evaluate exact evidence.",
                evaluationContexts = listOf(evaluationContext(assignment)),
                responses = responses
            )

            assertEquals(1, requests.size)
            assertEquals(
                StructuredNodeGradeVerdict.ABSTAINED,
                requests.single().priorDecision?.verdict
            )
            assertEquals(original.rationale, requests.single().priorDecision?.rationale)
            val repaired = responses.single()
            assertEquals(NodeGraderResponseVerdict.VALID, repaired.verdict)
            assertTrue(requireNotNull(repaired.scoreVector).hardFailure)
            assertTrue(repaired.hasValidHash())
        }

    @Test
    fun `structured reconsideration preserves a repeated model abstention`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val responses = mutableListOf(
            abstainedResponse(
                assignment = assignment,
                rationale = "The immutable packet authority is contradictory."
            )
        )
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-repeat-abstention-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = {
                attempts += 1
                """
                {"verdict":"ABSTAINED","scoreVector":null,
                "rationale":"The contradiction remains after reconsidering the exact authority."}
                """.trimIndent()
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(1, attempts)
        val preserved = responses.single()
        assertEquals(NodeGraderResponseVerdict.ABSTAINED, preserved.verdict)
        assertNull(preserved.scoreVector)
        assertEquals(
            "The contradiction remains after reconsidering the exact authority.",
            preserved.rationale
        )
        assertTrue(preserved.hasValidHash())
    }

    @Test
    fun `malformed reconsideration cannot synthesize a score or erase the abstention`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val original = abstainedResponse(
            assignment = assignment,
            rationale = "The immutable packet authority is incomplete."
        )
        val responses = mutableListOf(original)
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-malformed-reconsideration-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = {
                attempts += 1
                """{"verdict":"VALID","scoreVector":null,"rationale":"Unsupported grade."}"""
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(2, attempts)
        assertEquals(listOf(original), responses)
    }

    @Test
    fun `structured repair cannot author a model invalid response`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val responses = mutableListOf<NodeGraderResponse>()
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-model-invalid-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = {
                attempts += 1
                """
                {"verdict":"INVALID","scoreVector":null,
                "rationale":"The grading model attempted to classify runtime authority."}
                """.trimIndent()
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(2, attempts)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `existing invalid authority remains visible and is not reconsidered`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val original = NodeGraderResponse(
            responseId = "grader-a-response",
            assignmentId = assignment.assignmentId,
            assignmentHash = assignment.assignmentHash,
            graderId = "grader-a",
            scoreVector = null,
            verdict = NodeGraderResponseVerdict.INVALID,
            rationale = "Imported response authority is invalid."
        ).withComputedHash()
        val responses = mutableListOf(original)
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-existing-invalid-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = {
                attempts += 1
                error("Existing invalid authority must not be reconsidered")
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(0, attempts)
        assertEquals(listOf(original), responses)
    }

    @Test
    fun `malformed imported invalid authority remains visible and is not reconsidered`() = runTest {
        val assignment = assignment(requiredQuorum = 1)
        val original = NodeGraderResponse(
            responseId = "grader-a-response",
            assignmentId = assignment.assignmentId,
            assignmentHash = assignment.assignmentHash,
            graderId = "grader-a",
            scoreVector = null,
            verdict = NodeGraderResponseVerdict.INVALID,
            rationale = "Imported response authority is malformed.",
            responseHash = "tampered-response-hash"
        )
        val responses = mutableListOf(original)
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("node-grade-malformed-invalid-").toFile()
            ),
            localAgentRunner = { "" },
            structuredNodeGradeAuthor = {
                attempts += 1
                error("Malformed imported authority must not be reconsidered")
            }
        )

        runner.repairMissingNodeGrades(
            runId = "run-1",
            graderGroupId = "grader-a",
            runObjective = "Evaluate exact evidence.",
            evaluationContexts = listOf(evaluationContext(assignment)),
            responses = responses
        )

        assertEquals(0, attempts)
        assertEquals(listOf(original), responses)
    }

    @Test
    fun `complete independent quorum produces deterministic exact bundle`() {
        val assignment = assignment(requiredQuorum = 2)
        val first = response(assignment, "grader-a", 0.8)
        val second = response(assignment, "grader-b", 0.6)
        val runner = runner()

        val forward = runner.aggregateNodeGradeBundles(
            listOf(assignment),
            setOf("grader-a", "grader-b"),
            listOf(first, second)
        ).single()
        val reverse = runner.aggregateNodeGradeBundles(
            listOf(assignment),
            linkedSetOf("grader-b", "grader-a"),
            listOf(second, first)
        ).single()

        assertEquals(NodeGradeBundleVerdict.VALID_PASS, forward.verdict)
        assertEquals(0.7, requireNotNull(forward.normalizedScore), absoluteTolerance = 1e-12)
        assertEquals(forward.bundleHash, reverse.bundleHash)
        assertEquals(setOf(first.responseHash, second.responseHash), forward.graderResponseHashes)
    }

    @Test
    fun `duplicate responses remain malformed and preserve distinct response hashes`() {
        val assignment = assignment(requiredQuorum = 1)
        val first = response(assignment, "grader-a", 0.8)
        val second = first.copy(
            rationale = "A conflicting duplicate response.",
            responseHash = ""
        ).withComputedHash()

        val bundle = runner().aggregateNodeGradeBundles(
            assignments = listOf(assignment),
            expectedGraderIds = setOf("grader-a"),
            responses = listOf(first, second)
        ).single()

        assertEquals(NodeGradeBundleVerdict.MALFORMED, bundle.verdict)
        assertNull(bundle.scoreVector)
        assertNull(bundle.normalizedScore)
        assertEquals(setOf(first.responseHash, second.responseHash), bundle.graderResponseHashes)
        assertEquals(setOf("grader-a"), bundle.completedGraderIds)
    }

    @Test
    fun `missing abstained and tampered responses fail closed without a score`() {
        val assignment = assignment(requiredQuorum = 2)
        val runner = runner()
        val missing = runner.aggregateNodeGradeBundles(
            listOf(assignment),
            setOf("grader-a", "grader-b"),
            listOf(response(assignment, "grader-a", 0.8))
        ).single()
        val abstained = runner.aggregateNodeGradeBundles(
            listOf(assignment),
            setOf("grader-a", "grader-b"),
            listOf(
                response(assignment, "grader-a", 0.8),
                response(assignment, "grader-b", 0.8).copy(
                    scoreVector = null,
                    verdict = NodeGraderResponseVerdict.ABSTAINED,
                    responseHash = ""
                ).withComputedHash()
            )
        ).single()
        val tampered = runner.aggregateNodeGradeBundles(
            listOf(assignment),
            setOf("grader-a", "grader-b"),
            listOf(
                response(assignment, "grader-a", 0.8),
                response(assignment, "grader-b", 0.8).copy(assignmentHash = "tampered")
            )
        ).single()

        assertEquals(NodeGradeBundleVerdict.INCOMPLETE_QUORUM, missing.verdict)
        assertEquals(NodeGradeBundleVerdict.ABSTAINED, abstained.verdict)
        assertEquals(NodeGradeBundleVerdict.MALFORMED, tampered.verdict)
        listOf(missing, abstained, tampered).forEach {
            assertNull(it.normalizedScore)
            assertNull(it.scoreVector)
            assertTrue(it.hasValidHash())
        }
    }

    private fun runner() = GraderRunner(
        openRouterApiKey = "test",
        substrateFactory = SubstrateDatabaseFactory(createTempDirectory("node-grade-").toFile()),
        localAgentRunner = { "" }
    )

    private fun assignment(requiredQuorum: Int): NodeGradingAssignment {
        val node = nodeContract()
        val freeze = criteriaFreeze()
        val policy = node.capturePolicy
        return NodeGradingAssignment(
            assignmentId = "assignment-1",
            packet = NodeEvidencePacket(
                packetId = "packet-1",
                runId = "run-1",
                round = 2,
                groupId = AtlasGroupId("group-1"),
                nodeId = NodeId("node-1"),
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                nodePayloadHash = node.payloadHash,
                acceptedArtifactIds = listOf("artifact-1"),
                provenanceRootHash = "provenance-hash",
                cost = NodeEvidenceCostSummary(totalCredits = 2.0),
                sealedAtEpochMillis = 1_000
            ).withComputedHash(),
            criteriaHash = AtlasHashing.hashNodeGradingAuthority(
                node,
                mapOf("grader-a" to freeze.freezeHash)
            ),
            graderCriteriaFreezeHashes = mapOf("grader-a" to freeze.freezeHash),
            capturePolicy = policy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(policy),
            requiredQuorum = requiredQuorum
        ).withComputedHash()
    }

    private fun evaluationContext(
        assignment: NodeGradingAssignment
    ): ExactNodeEvaluationContext {
        val content = "Complete exact accepted artifact evidence."
        return ExactNodeEvaluationContext(
            graderGroupId = "grader-a",
            assignment = assignment,
            nodeContract = nodeContract(),
            criteriaFreeze = criteriaFreeze(),
            mappedRequirements = listOf(gradingRequirement()),
            evidence = listOf(
                ExactNodeEvidenceContent(
                    referenceId = "artifact-1",
                    kind = ExactNodeEvidenceKind.ARTIFACT,
                    submissionType = "DOC",
                    content = content,
                    promotedArtifactRef = "kb:artifact-1",
                    promotedArtifactHash = AtlasHashing.hashText(content)
                )
            )
        ).withComputedHash()
    }

    private fun nodeContract(): LimitationNode = LimitationNode(
        id = NodeId("node-1"),
        kind = NodeKind.EVIDENCE,
        layer = 0,
        parentIds = emptySet(),
        prerequisiteIds = emptySet(),
        receiverWhyRef = "receiver:evidence",
        limitationStatement = "The exact claim is not yet supported.",
        capabilityTarget = "One inspectable supported claim.",
        atomicQuestion = "Does the exact evidence support this claim?",
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("Provide inspectable evidence.")
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
            requiredFields = setOf("claim", "evidence")
        ),
        revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.65),
        rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
    ).withComputedPayloadHash()

    private fun gradingRequirement(): GradingRequirement =
        GradingRequirement(
            requirementId = GradingRequirementId("requirement-1"),
            runId = "run-1",
            graderAtlasHash = "grader-atlas-hash",
            graderAtlasNodeId = GraderNodeId("grader-node-1"),
            graderGroupId = "grader-a",
            round = 2,
            mappedWorkerNodeIds = setOf(NodeId("node-1")),
            mappedReceiverObligationRefs = setOf("receiver:evidence"),
            criterion = "The exact node-local claim has inspectable evidence.",
            observableEvidence = "The complete promoted packet content.",
            passThreshold = "The source supports the claim.",
            failureLevels = emptyList(),
            adversarialCases = emptyList(),
            executableTestIds = emptyList(),
            disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
            rationale = "The node cannot be captured without local evidence.",
            noveltyFingerprint = "node-local-evidence-v1",
            maximumEvaluationCost = 1.0
        ).withComputedHash()

    private fun criteriaFreeze(): GraderCriteriaFreeze {
        val requirement = gradingRequirement()
        return GraderCriteriaFreeze(
            freezeId = "freeze-1",
            runId = "run-1",
            round = 2,
            graderGroupId = "grader-a",
            graderAtlasHash = "grader-atlas-hash",
            requirementHashes = setOf(requirement.requirementHash),
            hiddenTestHashes = emptySet(),
            targetSnapshotHashes = mapOf(
                AtlasGroupId("group-1") to "snapshot-hash"
            ),
            sealedAtEpochMillis = 900,
            workerRevealEpochMillis = 900
        ).withComputedHash()
    }

    private fun response(
        assignment: NodeGradingAssignment,
        graderId: String,
        score: Double
    ) = NodeGraderResponse(
        responseId = "$graderId-response",
        assignmentId = assignment.assignmentId,
        assignmentHash = assignment.assignmentHash,
        graderId = graderId,
        scoreVector = NodeScoreVector(
            correctness = score,
            evidenceQuality = score,
            reproducibility = score,
            robustness = score,
            integrationUtility = score,
            receiverRelevance = score,
            novelty = score,
            efficiency = score,
            hardFailure = false,
            confidence = 0.8
        ),
        verdict = NodeGraderResponseVerdict.VALID,
        rationale = "Exact packet evidence."
    ).withComputedHash()

    private fun abstainedResponse(
        assignment: NodeGradingAssignment,
        rationale: String
    ) = NodeGraderResponse(
        responseId = "grader-a-response",
        assignmentId = assignment.assignmentId,
        assignmentHash = assignment.assignmentHash,
        graderId = "grader-a",
        scoreVector = null,
        verdict = NodeGraderResponseVerdict.ABSTAINED,
        rationale = rationale
    ).withComputedHash()
}
