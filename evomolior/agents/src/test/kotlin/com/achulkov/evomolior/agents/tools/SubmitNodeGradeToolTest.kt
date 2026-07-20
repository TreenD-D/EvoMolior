package com.achulkov.evomolior.agents.tools

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
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubmitNodeGradeToolTest {
    @Test
    fun `valid response preserves complete vector and exact assignment commitments`() = runTest {
        val assignment = assignment()
        val responses = mutableListOf<NodeGraderResponse>()
        val tool = tool(assignment, responses)

        val result = tool.execute(args(assignment))

        assertTrue(result.startsWith("Node grade recorded"))
        val response = responses.single()
        assertEquals(assignment.assignmentHash, response.assignmentHash)
        assertEquals("grader-1", response.graderId)
        assertEquals(NodeGraderResponseVerdict.VALID, response.verdict)
        assertTrue(response.hasValidHash())
    }

    @Test
    fun `commitment mismatch and malformed verdict fail without callback`() = runTest {
        val assignment = assignment()
        val responses = mutableListOf<NodeGraderResponse>()
        val tool = tool(assignment, responses)

        assertTrue(
            tool.execute(args(assignment).copy(packetHash = "tampered"))
                .startsWith("Error: submitted commitments")
        )
        assertTrue(
            tool.execute(
                args(assignment).copy(
                    verdict = NodeGradeSubmissionVerdict.ABSTAINED,
                    scoreVector = vector()
                )
            ).startsWith("Error: non-VALID")
        )
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `runtime validated assignment cannot be model marked invalid`() {
        val assignment = assignment()
        val invalidPayload = JsonObject(
            Json.encodeToJsonElement(
                SubmitNodeGradeTool.Args.serializer(),
                args(assignment)
            ).jsonObject.toMutableMap().apply {
                this["verdict"] = JsonPrimitive("INVALID")
            }
        )

        assertFailsWith<SerializationException> {
            Json.decodeFromJsonElement(
                SubmitNodeGradeTool.Args.serializer(),
                invalidPayload
            )
        }
    }

    @Test
    fun `abstention remains explicit and scoreless`() = runTest {
        val assignment = assignment()
        val responses = mutableListOf<NodeGraderResponse>()
        val tool = tool(assignment, responses)

        val result = tool.execute(
            args(assignment).copy(
                verdict = NodeGradeSubmissionVerdict.ABSTAINED,
                scoreVector = null,
                rationale = "The exact frozen authority cannot be evaluated consistently."
            )
        )

        assertTrue(result.startsWith("Node grade recorded"))
        val response = responses.single()
        assertEquals(NodeGraderResponseVerdict.ABSTAINED, response.verdict)
        assertNull(response.scoreVector)
        assertTrue(response.hasValidHash())
    }

    @Test
    fun `shared guard rejects duplicate responses across judge tool instances`() = runTest {
        val assignment = assignment()
        val responses = mutableListOf<NodeGraderResponse>()
        val guard = NodeGradeSubmissionGuard()
        val firstTool = tool(assignment, responses, guard)
        val retryTool = tool(assignment, responses, guard)

        assertTrue(firstTool.execute(args(assignment)).startsWith("Node grade recorded"))
        assertTrue(retryTool.execute(args(assignment)).startsWith("Error: a node grade has already"))
        assertEquals(1, responses.size)
    }

    private fun tool(
        assignment: NodeGradingAssignment,
        responses: MutableList<NodeGraderResponse>,
        submissionGuard: NodeGradeSubmissionGuard = NodeGradeSubmissionGuard()
    ): SubmitNodeGradeTool {
        val config = BudgetConfig("grader-1", GroupProfile.default(GroupProfileType.Library))
        return SubmitNodeGradeTool(
            budgetLedger = BudgetLedger("judge-1", 50.0, 1.0, config),
            graderId = "grader-1",
            assignments = listOf(assignment),
            submissionGuard = submissionGuard,
            onResponseSubmitted = responses::add
        )
    }

    private fun assignment(): NodeGradingAssignment {
        val policy = CapturePolicy(minimumGrade = 0.7)
        val packet = NodeEvidencePacket(
            packetId = "packet-1",
            runId = "run-1",
            round = 2,
            groupId = AtlasGroupId("group-1"),
            nodeId = NodeId("node-1"),
            atlasVersion = 1,
            atlasHash = "atlas-hash",
            nodePayloadHash = "payload-hash",
            acceptedArtifactIds = listOf("artifact-1"),
            provenanceRootHash = "provenance-hash",
            cost = NodeEvidenceCostSummary(totalCredits = 4.0),
            sealedAtEpochMillis = 1_000
        ).withComputedHash()
        return NodeGradingAssignment(
            assignmentId = "assignment-1",
            packet = packet,
            criteriaHash = "criteria-hash",
            capturePolicy = policy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(policy),
            requiredQuorum = 2
        ).withComputedHash()
    }

    private fun args(assignment: NodeGradingAssignment) = SubmitNodeGradeTool.Args(
        assignmentId = assignment.assignmentId,
        assignmentHash = assignment.assignmentHash,
        packetId = assignment.packet.packetId,
        packetHash = assignment.packet.packetHash,
        nodeId = assignment.packet.nodeId.value,
        atlasHash = assignment.packet.atlasHash,
        nodePayloadHash = assignment.packet.nodePayloadHash,
        criteriaHash = assignment.criteriaHash,
        capturePolicyHash = assignment.capturePolicyHash,
        verdict = NodeGradeSubmissionVerdict.VALID,
        scoreVector = vector(),
        rationale = "The exact accepted artifact satisfies the frozen proof contract."
    )

    private fun vector() = NodeScoreVector(
        correctness = 0.9,
        evidenceQuality = 0.8,
        reproducibility = 0.7,
        robustness = 0.8,
        integrationUtility = 0.9,
        receiverRelevance = 0.9,
        novelty = 0.6,
        efficiency = 0.7,
        hardFailure = false,
        confidence = 0.85
    )
}
