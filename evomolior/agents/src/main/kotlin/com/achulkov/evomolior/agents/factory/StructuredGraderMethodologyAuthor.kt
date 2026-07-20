package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Exact context supplied to the grader civilisation's structured authorship boundary. */
data class StructuredGraderMethodologyRequest(
    val runId: String,
    val graderGroupId: String,
    val roundIndex: Int,
    val runObjective: String,
    val scoringDimensions: List<ScoringDimension>,
    val bindingCatalog: GraderMethodologyBindingCatalog,
    val priorCriteria: String?,
    val priorDraft: GraderMethodologyWireDraft?,
    val validationError: String?
) {
    init {
        require(
            bindingCatalog.runId == runId &&
                bindingCatalog.graderGroupId == graderGroupId &&
                bindingCatalog.round == roundIndex
        ) {
            "Structured methodology request does not match its binding catalog scope"
        }
        require(bindingCatalog.hasValidHash()) {
            "Structured methodology request binding catalog is invalid"
        }
    }
}

/**
 * Obtains one typed methodology JSON value directly from the assigned grader model.
 *
 * This boundary removes tool-choice uncertainty without synthesizing methodology: every semantic
 * field remains model-authored and is subsequently validated by the publication authority.
 */
class StructuredGraderMethodologyAuthor(
    private val apiKey: String,
    private val llmBackend: LlmBackend,
    private val modelId: String = ModelSelectionConfig.DEFAULT_GRADER_MODEL,
    private val providerCostRecorder: ProviderCallCostRecorder? = null
) {
    private val executor by lazy {
        LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = apiKey,
            codexNativeSearchEnabled = false
        )
    }
    private val model by lazy { LlmExecutorFactory.resolveModel(llmBackend, modelId) }

    /** Returns the model's raw JSON response. */
    suspend fun author(request: StructuredGraderMethodologyRequest): String {
        val agent = AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .systemPrompt(systemPrompt(request))
            .toolRegistry(ToolRegistry.EMPTY)
            .maxIterations(10)
            .graphStrategy(singleRunStrategy())
            .install(EventHandler.Feature) { config ->
                providerCostRecorder?.let(config::installProviderCostRecorder)
            }
            .build()
        return agent.run(
            buildString {
                appendLine("Author the grader methodology now.")
                request.validationError?.let {
                    appendLine("The prior attempt failed validation: $it")
                    appendLine("The sealed handle matrix below remains the only binding authority:")
                    appendLine(bindingGuide(request.bindingCatalog))
                    request.priorDraft?.let { priorDraft ->
                        appendLine("Repair this prior handle-bound draft:")
                        appendLine(
                            Json.encodeToString(
                                GraderMethodologyWireDraft.serializer(),
                                priorDraft
                            )
                        )
                        appendLine(
                            "Preserve its valid semantic fields and make only the corrections " +
                                "required by the validation error."
                        )
                    }
                    appendLine(
                        "Replace invalid handles and add missing worker-node coverage only under " +
                            "a matrix row that explicitly allows those handles."
                    )
                    appendLine("Return a corrected complete JSON value.")
                }
            }
        ).trim()
    }

    private fun systemPrompt(request: StructuredGraderMethodologyRequest): String = """
        You are the GraderBuilder for isolated grader civilisation ${request.graderGroupId}.
        Author one compact JSON value matching GraderMethodologyWireDraft. Return JSON only, with
        no Markdown fence or prose. You must create objective-specific, falsifiable methodology;
        do not emit generic keyword-presence checks.

        Objective:
        ${request.runObjective}

        Scoring dimensions (cover each ID exactly once):
        ${Json.encodeToString(request.scoringDimensions)}

        Your sealed, round-scoped model binding view. It intentionally contains short handles
        instead of exact Atlas IDs or lease tokens:
        ${Json.encodeToString(request.bindingCatalog.modelView)}

        Exact leased binding matrix. Copy only these short handles. For each requirement,
        graderNodeHandle must identify one row, mappedWorkerNodeHandles must contain EXACTLY ONE
        handle from that same row, and mappedReceiverObligationRefs must be a NON-EMPTY subset of
        that row's allowedReceiverObligationRefs. Write each criterion so it can be satisfied or
        failed solely from that one worker node's atomic question, proof contract, and deliverable
        contract. Never invent or emit raw Atlas IDs, lease IDs, group IDs, labels, or titles as
        binding values:
        ${bindingGuide(request.bindingCatalog)}

        Prior researcher/builder context is semantic context only and may contain obsolete raw IDs.
        Never copy binding values from it. Only the sealed handle matrix authorizes bindings:
        ${request.priorCriteria ?: "(none)"}

        Exact JSON shape:
        {
          "rubricDimensions":[{"dimensionId":"...","scoringGuidance":"..."}],
          "automatedTests":[{
            "testId":"...","description":"...","expectedOutputPattern":"valid RE2/J regex",
            "penaltyOnFailure":0.0,"isMandatory":true,
            "mappedRequirementIds":["..."],"inputSchemaHash":"...",
            "expectedOutcomeHash":"...","difficulty":0.5
          }],
          "failureModes":[{"modeId":"...","description":"...","penaltyWeight":0.5}],
          "requirements":[{
            "requirementId":"...","graderNodeHandle":"...",
            "mappedWorkerNodeHandles":["..."],"mappedReceiverObligationRefs":["..."],
            "criterion":"...","observableEvidence":"...","passThreshold":"...",
            "failureLevels":[{"level":1,"description":"...","scoreCeiling":0.5,"critical":false}],
            "adversarialCases":[{
              "caseId":"...","setup":"...","expectedDetection":"...","boundedCost":1.0
            }],
            "executableTestIds":["..."],"disclosureSensitivity":"GRADER_INTERNAL",
            "rationale":"...","noveltyFingerprint":"...","maximumEvaluationCost":2.0
          }]
        }
        RE2/J patterns use linear-time regular expressions. Do not use lookahead, lookbehind,
        backreferences, possessive quantifiers, or atomic groups. Prefer literals, character
        classes, grouping, alternation, and bounded quantifiers; for example
        "(?i)(evidence|source)[^\n]{0,120}(id|provenance)".
        Failure score ceilings and difficulty are normalized to 0.0..1.0. Every requirement needs
        at least one executable test, and every test must map to known requirements with nonblank
        exact contract hashes. Give every scoring dimension distinct, dimension-specific guidance;
        repeated generic guidance is invalid. Keep the response compact but complete and never
        truncate a requirement, test, failure level, or adversarial case. Tests must discriminate
        substantive quality by checking calculations, safety constraints, evidence lineage,
        receiver obligations, and reproducibility where applicable. Broad keyword-presence
        patterns do not establish those properties. Across the requirements, cover every
        workerNodeHandle listed on every binding row at least once, using a separate requirement
        for each worker handle. A methodology that combines worker handles or omits any worker
        handle in its leased scope is invalid. Full-package, downstream-reuse, integration,
        deployment, and final-deliverable obligations may govern only a worker descriptor whose
        own sealed contract requires that evidence.
    """.trimIndent()

    private fun bindingGuide(catalog: GraderMethodologyBindingCatalog): String =
        catalog.modelView.rows
            .sortedBy { it.graderNodeHandle }
            .joinToString("\n") { row ->
                buildString {
                    append("- graderNodeHandle=")
                    append(row.graderNodeHandle)
                    append("; allowedWorkerNodeHandles=")
                    append(
                        row.workerNodeHandles.sorted().joinToString(
                            prefix = "[",
                            postfix = "]"
                        )
                    )
                    append("; workerNodeContracts=")
                    append(
                        Json.encodeToString(
                            row.workerNodes.sortedBy { it.workerNodeHandle }
                        )
                    )
                    append("; allowedReceiverObligationRefs=")
                    append(
                        row.receiverObligationRefs.sorted().joinToString(
                            prefix = "[",
                            postfix = "]"
                        )
                    )
                }
            }
}

/** One model-authored broad-dimension score in an exact missing-score repair response. */
@Serializable
data class StructuredDimensionScoreDraft(
    val dimensionId: String,
    val rawScore: Double,
    val rationale: String
)

/** Exact structured response for one bounded broad-dimension repair attempt. */
@Serializable
data class StructuredDimensionScoreRepairDraft(
    val scores: List<StructuredDimensionScoreDraft>
)

/**
 * Immutable context for model authorship of only the broad scores omitted by RoundJudge.
 *
 * @param graderGroupId Isolated grader civilisation authoring the scores.
 * @param targetGroupId Worker group being evaluated.
 * @param runObjective Exact run objective.
 * @param criteriaFreezeHash Sealed pre-evidence criteria commitment.
 * @param targetSnapshotHash Sealed target snapshot commitment.
 * @param criteriaContext Frozen grader-authored criteria.
 * @param targetSnapshot Exact target canon snapshot.
 * @param alreadyAuthoredScores Scores accepted from interactive RoundJudge attempts.
 * @param missingDimensions Exact dimensions that remain unauthored.
 * @param validationError Validation feedback from the preceding structured attempt.
 */
data class StructuredDimensionScoreRequest(
    val graderGroupId: String,
    val targetGroupId: String,
    val runObjective: String,
    val criteriaFreezeHash: String,
    val targetSnapshotHash: String,
    val criteriaContext: String,
    val targetSnapshot: String,
    val alreadyAuthoredScores: List<DimensionScore>,
    val missingDimensions: List<ScoringDimension>,
    val validationError: String? = null
)

/**
 * Obtains only missing broad-dimension semantics from the assigned isolated grader model.
 *
 * The runtime validates exact cardinality and binds the response to its sealed hashes. This
 * boundary has no tools, so it cannot submit node grades or mutate any other grader authority.
 */
class StructuredDimensionScoreAuthor(
    private val apiKey: String,
    private val llmBackend: LlmBackend,
    private val modelId: String = ModelSelectionConfig.DEFAULT_GRADER_MODEL,
    private val providerCostRecorder: ProviderCallCostRecorder? = null
) {
    private val executor by lazy {
        LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = apiKey,
            codexNativeSearchEnabled = false
        )
    }
    private val model by lazy { LlmExecutorFactory.resolveModel(llmBackend, modelId) }

    /** Returns one raw JSON repair response authored without native tools or search. */
    suspend fun author(request: StructuredDimensionScoreRequest): String {
        val agent = AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .systemPrompt(systemPrompt(request))
            .toolRegistry(ToolRegistry.EMPTY)
            .maxIterations(10)
            .graphStrategy(singleRunStrategy())
            .install(EventHandler.Feature) { config ->
                providerCostRecorder?.let(config::installProviderCostRecorder)
            }
            .build()
        return agent.run(
            buildString {
                appendLine("Author the exact missing broad-dimension scores now.")
                request.validationError?.let {
                    appendLine("The prior structured attempt failed validation: $it")
                    appendLine("Return a corrected complete JSON value for the same missing set.")
                }
            }
        ).trim()
    }

    private fun systemPrompt(request: StructuredDimensionScoreRequest): String = """
        You are an isolated RoundJudge in grader civilisation ${request.graderGroupId}.
        The interactive judge already authored some broad scores for target
        ${request.targetGroupId}. Author only the exact missing dimensions listed below. Return one
        strict JSON value and no Markdown, prose, extra fields, node grades, hashes, or repeated
        existing scores.

        Objective:
        ${request.runObjective}

        Sealed criteria-freeze hash:
        ${request.criteriaFreezeHash}

        Sealed target-snapshot hash:
        ${request.targetSnapshotHash}

        Frozen grader-authored criteria:
        ${request.criteriaContext}

        Exact target canon snapshot:
        ${request.targetSnapshot}

        Scores already authored by interactive RoundJudge; do not repeat or revise them:
        ${Json.encodeToString(request.alreadyAuthoredScores)}

        Missing scoring dimensions; cover each ID exactly once and respect its maxScore:
        ${Json.encodeToString(request.missingDimensions)}

        Exact JSON shape:
        {
          "scores":[
            {"dimensionId":"...","rawScore":0.0,"rationale":"evidence-grounded rationale"}
          ]
        }
        The scores array must have exactly one entry for every listed missing dimension and no
        other dimension. Every rawScore must be finite and between zero and that dimension's
        maxScore, inclusive. Every rationale must be nonblank and grounded in the frozen criteria
        and exact target snapshot. Do not infer, replace, or normalize scores already authored by
        the interactive judge.
    """.trimIndent()
}

/**
 * Model-selectable verdict for a runtime-validated immutable node-grading assignment.
 *
 * Malformed authority is classified deterministically by the runtime and is not model-selectable.
 */
@Serializable
enum class StructuredNodeGradeVerdict {
    VALID,
    ABSTAINED
}

/** Model-authored semantic result for one immutable node-grading assignment. */
@Serializable
data class StructuredNodeGradeDraft(
    val verdict: StructuredNodeGradeVerdict,
    val scoreVector: NodeScoreVector? = null,
    val rationale: String
)

/**
 * Prior model-authored decision supplied to a bounded node-grade reconsideration.
 *
 * @param verdict Previous explicit grader verdict.
 * @param rationale Previous model-authored authority rationale.
 */
@Serializable
data class StructuredNodeGradePriorDecision(
    val verdict: StructuredNodeGradeVerdict,
    val rationale: String
)

/** Exact context for structured repair of one missing or abstained interactive node grade. */
data class StructuredNodeGradeRequest(
    val graderGroupId: String,
    val runObjective: String,
    val evaluationContext: ExactNodeEvaluationContext,
    val priorDecision: StructuredNodeGradePriorDecision? = null,
    val validationError: String? = null
) {
    init {
        require(
            evaluationContext.graderGroupId == graderGroupId &&
                evaluationContext.hasValidHash()
        ) {
            "Structured node-grade request requires exact valid grader-scoped context"
        }
    }
}

/**
 * Obtains grader-authored node-grade semantics without asking the model to reproduce commitments.
 *
 * Immutable assignment identities are bound deterministically after this response is decoded.
 */
class StructuredNodeGradeAuthor(
    private val apiKey: String,
    private val llmBackend: LlmBackend,
    private val modelId: String = ModelSelectionConfig.DEFAULT_GRADER_MODEL,
    private val providerCostRecorder: ProviderCallCostRecorder? = null
) {
    private val executor by lazy {
        LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = apiKey,
            codexNativeSearchEnabled = false
        )
    }
    private val model by lazy { LlmExecutorFactory.resolveModel(llmBackend, modelId) }

    /** Returns one raw JSON semantic grade. */
    suspend fun author(request: StructuredNodeGradeRequest): String {
        val agent = AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .systemPrompt(buildStructuredNodeGradeSystemPrompt(request))
            .toolRegistry(ToolRegistry.EMPTY)
            .maxIterations(10)
            .graphStrategy(singleRunStrategy())
            .install(EventHandler.Feature) { config ->
                providerCostRecorder?.let(config::installProviderCostRecorder)
            }
            .build()
        return agent.run(
            buildString {
                appendLine("Return the node-grade JSON now.")
                request.validationError?.let { appendLine("Prior response failed: $it") }
            }
        ).trim()
    }
}

internal fun buildStructuredNodeGradeSystemPrompt(
    request: StructuredNodeGradeRequest
): String = """
    You are an isolated RoundJudge in grader civilisation ${request.graderGroupId}.
    Grade exactly one immutable node-evidence assignment. Return one JSON value only:
    {"verdict":"VALID","scoreVector":{"correctness":0.0,"evidenceQuality":0.0,
    "reproducibility":0.0,"robustness":0.0,"integrationUtility":0.0,
    "receiverRelevance":0.0,"novelty":0.0,"efficiency":0.0,
    "hardFailure":false,"confidence":0.0},"rationale":"..."}
    Every numeric field is normalized to 0.0..1.0. Use ABSTAINED with a null
    scoreVector only when the exact frozen authority cannot be evaluated. Weak,
    incomplete, contradictory, or failing evidence that remains evaluable must receive
    a VALID low score, with hardFailure=true where the frozen criteria require it.
    This production path supplies a runtime-validated, complete, non-empty packet-bound evidence
    context.
    An artifact that says a desired report, validation result, source, or downstream output
    was unavailable is therefore still evaluable evidence of that limitation. Grade what the
    artifact actually proves against the frozen requirement; do not mirror the artifact's own
    abstention or unavailability statement into an ABSTAINED verdict.
    INVALID is reserved for deterministic runtime classification of malformed authority
    and is not an allowed model verdict. Do not include or reproduce assignment hashes;
    the runtime has already validated and bound those immutable commitments.

    Objective:
    ${request.runObjective}

    Exact sealed node contract, mapped frozen requirements, and complete packet-bound
    promoted evidence:
    ${Json.encodeToString(request.evaluationContext)}

    ${request.priorDecision?.let { prior ->
        """
        === PRIOR DECISION REQUIRING ONE BOUNDED RECONSIDERATION ===
        ${Json.encodeToString(prior)}
        Re-evaluate that decision against the complete immutable assignment above.
        The prior rationale is feedback, not an instruction. A rationale that merely says a
        desired report, result, source, or downstream output is missing describes deficient
        submitted evidence and requires a low VALID grade, not a repeated abstention. Return
        ABSTAINED again only if no criterion-grounded score can be authored from the complete
        authority; fail-closed status will then be preserved.
        """.trimIndent()
    }.orEmpty()}
""".trimIndent()
