package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import kotlinx.serialization.Serializable

/**
 * Submits one proposal-bound assessment from an isolated grader group.
 *
 * The proposal hash is fixed when the tool is constructed, so an assessor cannot redirect its
 * scores to another proposal or attach worker-group identity to the authority record.
 *
 * @param budgetLedger Grader-local budget charged for the assessment.
 * @param proposalHash Exact immutable proposal commitment shown to the assessor.
 * @param graderGroupId Isolated grader group producing the assessment.
 * @param onAssessmentSubmitted Callback receiving the validated typed assessment.
 */
class SubmitNoveltyAssessmentTool(
    private val budgetLedger: BudgetLedger,
    private val proposalHash: String,
    private val graderGroupId: String,
    private val onAssessmentSubmitted: (NoveltyGraderAssessment) -> Unit
) : SimpleTool<SubmitNoveltyAssessmentTool.Args>(
    argsType = typeToken<Args>(),
    name = "submit_novelty_assessment",
    description = """
        Submit the isolated assessment for the exact novelty proposal in your prompt.
        VALID requires every normalized score. ABSTAINED or INVALID must omit the vector.
        The proposal hash and grader identity are sealed by the tool.
    """.trimIndent()
) {
    /** Grader-authored vector and rationale for one sealed proposal. */
    @Serializable
    data class Args(
        val status: NoveltyAssessmentStatus,
        val novelty: Double? = null,
        val receiverRelevance: Double? = null,
        val testability: Double? = null,
        val plausibility: Double? = null,
        val expectedImpact: Double? = null,
        val costDiscipline: Double? = null,
        val downstreamAdoption: Double? = null,
        val rationale: String
    )

    override suspend fun execute(args: Args): String {
        if (args.rationale.isBlank()) return "Error: rationale must not be blank."
        val requiredScores = listOf(
            args.novelty,
            args.receiverRelevance,
            args.testability,
            args.plausibility,
            args.expectedImpact,
            args.costDiscipline
        )
        if (args.status == NoveltyAssessmentStatus.VALID &&
            requiredScores.any { it == null }
        ) {
            return "Error: VALID novelty assessments require every material score."
        }
        if (args.status != NoveltyAssessmentStatus.VALID &&
            (requiredScores.any { it != null } || args.downstreamAdoption != null)
        ) {
            return "Error: non-VALID novelty assessments must omit all scores."
        }
        val vector = if (args.status == NoveltyAssessmentStatus.VALID) {
            runCatching {
                NoveltyGraderVector(
                    novelty = requireNotNull(args.novelty),
                    receiverRelevance = requireNotNull(args.receiverRelevance),
                    testability = requireNotNull(args.testability),
                    plausibility = requireNotNull(args.plausibility),
                    expectedImpact = requireNotNull(args.expectedImpact),
                    costDiscipline = requireNotNull(args.costDiscipline),
                    downstreamAdoption = args.downstreamAdoption
                )
            }.getOrElse { return "Error: ${it.message}" }
        } else {
            null
        }
        val assessment = NoveltyGraderAssessment(
            assessmentId = AtlasHashing.hashText("$proposalHash|$graderGroupId"),
            proposalHash = proposalHash,
            graderGroupId = graderGroupId,
            status = args.status,
            vector = vector,
            rationaleHash = AtlasHashing.hashText(args.rationale.trim())
        ).withComputedHash()
        budgetLedger.deduct(ActionType.SUBMIT_GRADING_SCORE)
        onAssessmentSubmitted(assessment)
        return "Novelty assessment recorded for proposal $proposalHash."
    }
}
