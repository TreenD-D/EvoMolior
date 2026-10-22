package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * Model-selectable outcome for an assignment whose immutable authority was runtime-validated.
 *
 * `INVALID` is intentionally absent: malformed assignment or response authority is classified by
 * deterministic runtime validation rather than declared by the grading model.
 */
@Serializable
enum class NodeGradeSubmissionVerdict {
    VALID,
    ABSTAINED
}

/**
 * Atomically reserves the one allowed response for each immutable node-grade assignment.
 *
 * A guard can be shared by every RoundJudge tool instance created for the same grader so retries
 * and repeated tool calls cannot create duplicate response authority.
 */
class NodeGradeSubmissionGuard {
    private val claimedAssignmentIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Claims an assignment for its first response.
     *
     * @param assignmentId Immutable assignment identifier.
     * @return `true` only for the first successful claim.
     */
    fun claim(assignmentId: String): Boolean = claimedAssignmentIds.add(assignmentId)

    /**
     * Releases a claim when response publication fails before authority is recorded.
     *
     * @param assignmentId Immutable assignment identifier.
     */
    fun release(assignmentId: String) {
        claimedAssignmentIds.remove(assignmentId)
    }
}

/**
 * Submits one packet-bound node vector against an immutable grader assignment.
 *
 * The model supplies only an assignment ID and grading decision. The runtime resolves the
 * immutable assignment and authors every packet, node, policy, criteria, and hash commitment.
 * Generic dimension scores are intentionally absent from this boundary.
 */
class SubmitNodeGradeTool(
    private val budgetLedger: BudgetLedger,
    private val graderId: String,
    assignments: Collection<NodeGradingAssignment>,
    private val submissionGuard: NodeGradeSubmissionGuard = NodeGradeSubmissionGuard(),
    private val onResponseSubmitted: (NodeGraderResponse) -> Unit
) : SimpleTool<SubmitNodeGradeTool.Args>(
    argsType = typeToken<Args>(),
    name = "submit_node_grade",
    description = """
        Submit one exact node grade for an assigned sealed evidence packet.
        Provide only the assignmentId shown in the exact node evaluation context; the runtime
        resolves and binds every immutable packet, node, criteria, and capture-policy commitment.
        For VALID, provide every normalized vector value in [0.0, 1.0].
        For ABSTAINED, omit scoreVector and explain why the exact frozen authority cannot be graded.
        INVALID is runtime-reserved and is not a model-selectable outcome.
    """.trimIndent()
) {
    private val assignmentsById = assignments.associateBy(NodeGradingAssignment::assignmentId)

    /** Model-authored decision fields bound to runtime-owned assignment authority. */
    @Serializable
    data class Args(
        val assignmentId: String,
        val verdict: NodeGradeSubmissionVerdict,
        val scoreVector: NodeScoreVector? = null,
        val rationale: String
    )

    override suspend fun execute(args: Args): String {
        val assignment = assignmentsById[args.assignmentId]
            ?: return "Error: assignmentId is not assigned to this grader."
        if (!assignment.hasValidHash()) return "Error: immutable assignment authority is invalid."
        if (args.rationale.isBlank()) return "Error: rationale must not be blank."
        if (args.verdict == NodeGradeSubmissionVerdict.VALID && args.scoreVector == null) {
            return "Error: VALID responses require a complete scoreVector."
        }
        if (args.verdict != NodeGradeSubmissionVerdict.VALID && args.scoreVector != null) {
            return "Error: non-VALID responses must not include a scoreVector."
        }
        args.scoreVector?.let { vector ->
            val values = listOf(
                vector.correctness,
                vector.evidenceQuality,
                vector.reproducibility,
                vector.robustness,
                vector.integrationUtility,
                vector.receiverRelevance,
                vector.novelty,
                vector.efficiency,
                vector.confidence
            )
            if (values.any { !it.isFinite() || it !in 0.0..1.0 }) {
                return "Error: every score and confidence value must be finite and normalized."
            }
        }

        val responseId = AtlasHashing.hashText(
            listOf(assignment.assignmentHash, graderId).joinToString("|")
        )
        val response = NodeGraderResponse(
            responseId = responseId,
            assignmentId = assignment.assignmentId,
            assignmentHash = assignment.assignmentHash,
            graderId = graderId,
            scoreVector = args.scoreVector,
            verdict = when (args.verdict) {
                NodeGradeSubmissionVerdict.VALID -> NodeGraderResponseVerdict.VALID
                NodeGradeSubmissionVerdict.ABSTAINED -> NodeGraderResponseVerdict.ABSTAINED
            },
            rationale = args.rationale
        ).withComputedHash()
        if (!submissionGuard.claim(assignment.assignmentId)) {
            return "Error: a node grade has already been recorded for assignment " +
                "${assignment.assignmentId}."
        }
        try {
            budgetLedger.deduct(ActionType.SUBMIT_GRADING_SCORE)
            onResponseSubmitted(response)
        } catch (failure: Throwable) {
            submissionGuard.release(assignment.assignmentId)
            throw failure
        }
        return "Node grade recorded for assignment ${assignment.assignmentId}."
    }
}
