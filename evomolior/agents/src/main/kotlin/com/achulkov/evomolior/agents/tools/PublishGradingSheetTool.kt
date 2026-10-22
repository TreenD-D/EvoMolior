package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingResolution
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.contract.GraderRequirementWireDraft
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderView
import com.achulkov.evomolior.core.grader.SafeRegexMatcher
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Publishes a typed grader-authored methodology sheet to the isolated grader staging area.
 *
 * @param stagingDao Grader-local staging DAO.
 * @param budgetLedger Agent budget ledger.
 * @param runId Run identifier.
 * @param graderGroupId Grader civilisation identifier.
 * @param agentId Authoring grader agent.
 * @param generation One-based grader generation.
 * @param allowedDimensionIds Council scoring-dimension IDs accepted by this run.
 * @param graderAtlas Optional compiled Grader Atlas that makes typed requirements mandatory.
 * @param graderView Redacted frontier and lease authority visible to this grader civilisation.
 * @param workerAtlas Sealed worker Atlas supplying node-local methodology semantics.
 * @param defaultWipTaskRef Claimed grader WiP task.
 * @param actionGate Constitutional staging boundary.
 */
class PublishGradingSheetTool(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val graderGroupId: String,
    private val agentId: String,
    private val generation: Int,
    private val allowedDimensionIds: Set<String>,
    private val graderAtlas: GraderAtlas? = null,
    private val graderView: GraderView? = null,
    private val workerAtlas: NodeAtlas? = null,
    private val defaultWipTaskRef: String?,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<PublishGradingSheetTool.Args>(
    argsType = typeToken<Args>(),
    name = "publish_grading_sheet",
    description = """
        Publish the grader civilisation's typed rubric, automated snapshot tests, and failure modes.
        Every configured scoring dimension must appear exactly once. Test patterns must be valid
        linear-time RE2/J expressions without lookaround or backreferences. Compiled grader
        requirements must use only the short handles in the sealed prompt binding view.
        Cost: 1 budget unit.
    """.trimIndent()
) {
    /** Model-facing tool arguments using short binding handles for compiled grader authority. */
    @Serializable
    data class Args(
        val rubricDimensions: List<GraderRubricDraft>,
        val automatedTests: List<GraderAutomatedTestDraft>,
        val failureModes: List<GraderFailureModeDraft>,
        val requirements: List<GraderRequirementWireDraft> = emptyList()
    ) {
        /** Java/reflection compatibility for legacy callers without typed requirements. */
        constructor(
            rubricDimensions: List<GraderRubricDraft>,
            automatedTests: List<GraderAutomatedTestDraft>,
            failureModes: List<GraderFailureModeDraft>
        ) : this(rubricDimensions, automatedTests, failureModes, emptyList())
    }

    override suspend fun execute(args: Args): String {
        val draft = GraderMethodologyWireDraft(
            rubricDimensions = args.rubricDimensions,
            automatedTests = args.automatedTests,
            failureModes = args.failureModes,
            requirements = args.requirements
        )
        return GraderMethodologyPublicationService(
            stagingDao = stagingDao,
            budgetLedger = budgetLedger,
            runId = runId,
            graderGroupId = graderGroupId,
            agentId = agentId,
            generation = generation,
            allowedDimensionIds = allowedDimensionIds,
            graderAtlas = graderAtlas,
            graderView = graderView,
            workerAtlas = workerAtlas,
            defaultWipTaskRef = defaultWipTaskRef,
            actionGate = actionGate
        ).publish(draft)
    }
}

/**
 * Single validation and persistence authority for model-authored grader methodologies.
 *
 * Both interactive tool publication and structured model generation pass through this boundary.
 */
class GraderMethodologyPublicationService(
    private val stagingDao: StagingDao,
    private val budgetLedger: BudgetLedger,
    private val runId: String,
    private val graderGroupId: String,
    private val agentId: String,
    private val generation: Int,
    private val allowedDimensionIds: Set<String>,
    private val graderAtlas: GraderAtlas? = null,
    private val graderView: GraderView? = null,
    private val workerAtlas: NodeAtlas? = null,
    private val defaultWipTaskRef: String?,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) {
    init {
        graderAtlas?.let { atlas ->
            val view = requireNotNull(graderView) {
                "Compiled Grader Atlas publication requires a leased GraderView"
            }
            require(
                view.runId == runId &&
                    view.graderGroupId == graderGroupId &&
                    view.graderAtlasHash == atlas.atlasHash
            ) {
                "Grader methodology publication scope does not match its Atlas and view"
            }
            val worker = requireNotNull(workerAtlas) {
                "Compiled Grader Atlas publication requires its sealed worker Atlas"
            }
            require(
                worker.runId == runId &&
                    worker.atlasHash == atlas.workerAtlasHash &&
                    worker.version == atlas.version
            ) {
                "Grader methodology publication worker Atlas does not match grader authority"
            }
            worker.validate().requireValid()
        }
    }

    private val bindingCatalog = graderView?.let { view ->
        GraderMethodologyBindingCatalog.from(
            view,
            requireNotNull(workerAtlas) {
                "A leased grader view requires its sealed worker Atlas"
            }
        )
    }

    /**
     * Resolves one model-wire draft through the sealed handle catalog before exact validation.
     *
     * @param draft Model-authored handle-bound draft.
     * @return Publication confirmation or recoverable fail-closed error.
     */
    suspend fun publish(draft: GraderMethodologyWireDraft): String {
        val exactDraft = if (graderAtlas == null) {
            if (draft.requirements.isNotEmpty()) {
                return "Error: typed requirements require a compiled Grader Atlas binding catalog."
            }
            GraderMethodologyDraft(
                rubricDimensions = draft.rubricDimensions,
                automatedTests = draft.automatedTests,
                failureModes = draft.failureModes
            )
        } else {
            val catalog = bindingCatalog
                ?: return "Error: compiled Grader Atlas publication requires a leased binding catalog."
            when (val resolution = catalog.resolve(draft)) {
                is GraderMethodologyBindingResolution.Resolved -> resolution.draft
                is GraderMethodologyBindingResolution.Rejected -> {
                    return "Error: methodology binding failed: " +
                        resolution.errors.joinToString(" | ") { error ->
                            buildString {
                                append(error.code.name.lowercase())
                                append("(path=")
                                append(error.path)
                                if (error.code.name != "INVALID_CATALOG" &&
                                    error.rejectedValue.isNotBlank()
                                ) {
                                    append(";value=")
                                    append(error.rejectedValue)
                                }
                                append("): ")
                                append(error.message)
                            }
                        }.take(8_000)
                }
            }
        }
        return publish(exactDraft)
    }

    /**
     * Validates and stages one exact-ID methodology draft.
     *
     * This overload is a trusted runtime compatibility boundary. Model-facing callers must use
     * [publish] with [GraderMethodologyWireDraft] so exact identities remain runtime-owned.
     *
     * @param draft Exact-ID methodology draft.
     * @return Publication confirmation or exact fail-closed error.
     */
    suspend fun publish(draft: GraderMethodologyDraft): String {
        val rubricIds = draft.rubricDimensions.map { it.dimensionId }
        if (rubricIds.toSet() != allowedDimensionIds || rubricIds.size != allowedDimensionIds.size) {
            return "Error: rubricDimensions must cover every configured dimension exactly once."
        }
        if (draft.rubricDimensions.any { it.scoringGuidance.isBlank() }) {
            return "Error: every rubric dimension requires scoringGuidance."
        }
        if (draft.automatedTests.isEmpty()) {
            return "Error: at least one automated test is required."
        }
        if (draft.automatedTests.map { it.testId }.toSet().size != draft.automatedTests.size) {
            return "Error: automated test IDs must be unique."
        }
        val validationFailures = mutableListOf<String>()
        val invalidPattern = draft.automatedTests.firstNotNullOfOrNull { test ->
            val error = if (test.expectedOutputPattern.isBlank()) {
                "pattern is blank"
            } else {
                SafeRegexMatcher.validationError(test.expectedOutputPattern)
            }
            error?.let { Triple(test.testId, test.expectedOutputPattern, it) }
        }
        if (invalidPattern != null) {
            validationFailures +=
                "automated test '${invalidPattern.first}' has invalid RE2/J pattern " +
                    "'${invalidPattern.second.take(300)}': ${invalidPattern.third}. " +
                    "Replace the entire pattern using no lookaround or backreferences and a " +
                    "bounded-quantifier upper limit of at most 1000; for ordered tokens prefer " +
                    "[\\s\\S]{0,512} or a smaller bound."
        }
        if (draft.automatedTests.any { it.penaltyOnFailure < 0.0 }) {
            return "Error: automated-test penalties cannot be negative."
        }
        graderAtlas?.let { atlas ->
            if (draft.requirements.isEmpty()) {
                return "Error: a compiled Grader Atlas requires typed requirements."
            }
            if (draft.requirements.map { it.requirementId }.toSet().size != draft.requirements.size) {
                return "Error: typed requirement IDs must be unique."
            }
            val knownTestIds = draft.automatedTests.mapTo(linkedSetOf()) { it.testId }
            val leasedViewNodes = graderView?.nodes
                ?.filter { it.leasedByThisGroup }
                .orEmpty()
            val allowedGraderNodeIds = (
                if (graderView == null) {
                    atlas.nodes.keys
                } else {
                    leasedViewNodes.mapTo(linkedSetOf()) { it.nodeId }
                }
                )
                .sortedBy { it.value }
                .joinToString(",") { it.value }
            val invalidRequirement = draft.requirements.firstNotNullOfOrNull { requirement ->
                val atlasNode = atlas.nodes[requirement.graderAtlasNodeId]
                val predicates = buildList {
                    if (atlasNode == null) {
                        add(
                            "grader_node_unknown(actual=${requirement.graderAtlasNodeId.value};" +
                                "allowed=$allowedGraderNodeIds)"
                        )
                    }
                    if (graderView != null &&
                        leasedViewNodes.none { it.nodeId == requirement.graderAtlasNodeId }
                    ) {
                        add(
                            "grader_node_not_leased(actual=${requirement.graderAtlasNodeId.value};" +
                                "allowed=$allowedGraderNodeIds)"
                        )
                    }
                    if (requirement.mappedWorkerNodeIds.isEmpty()) {
                        add(
                            "worker_nodes_empty(allowed=" +
                                atlasNode?.mappedWorkerNodeIds
                                    ?.sortedBy { it.value }
                                    ?.joinToString(",") { it.value } +
                                ")"
                        )
                    }
                    if (requirement.mappedWorkerNodeIds.size > 1) {
                        add(
                            "worker_nodes_non_atomic(actual_count=" +
                                requirement.mappedWorkerNodeIds.size +
                                ";required=1)"
                        )
                    }
                    if (atlasNode != null &&
                        !atlasNode.mappedWorkerNodeIds.containsAll(requirement.mappedWorkerNodeIds)
                    ) {
                        add(
                            "worker_nodes_outside_atlas_node(actual=" +
                                requirement.mappedWorkerNodeIds
                                    .sortedBy { it.value }
                                    .joinToString(",") { it.value } +
                                ";allowed=" +
                                atlasNode.mappedWorkerNodeIds
                                    .sortedBy { it.value }
                                    .joinToString(",") { it.value } +
                                ")"
                        )
                    }
                    if (requirement.mappedReceiverObligationRefs.isEmpty()) {
                        add(
                            "receiver_refs_empty(allowed=" +
                                atlasNode?.receiverObligationRefs
                                    ?.sorted()
                                    ?.joinToString(",") +
                                ")"
                        )
                    }
                    if (atlasNode != null &&
                        !atlasNode.receiverObligationRefs.containsAll(
                            requirement.mappedReceiverObligationRefs
                        )
                    ) {
                        add(
                            "receiver_refs_outside_atlas_node(actual=" +
                                requirement.mappedReceiverObligationRefs.sorted().joinToString(",") +
                                ";allowed=" +
                                atlasNode.receiverObligationRefs.sorted().joinToString(",") +
                                ")"
                        )
                    }
                    if (requirement.criterion.isBlank()) add("criterion_blank")
                    if (requirement.observableEvidence.isBlank()) add("observable_evidence_blank")
                    if (requirement.passThreshold.isBlank()) add("pass_threshold_blank")
                    if (requirement.failureLevels.isEmpty()) add("failure_levels_empty")
                    if (requirement.failureLevels.map { it.level }.let { levels ->
                            levels != levels.sorted() || levels.toSet().size != levels.size
                        }
                    ) add("failure_levels_not_unique_and_sorted")
                    if (requirement.failureLevels.any {
                            it.description.isBlank() || it.scoreCeiling !in 0.0..1.0
                        }
                    ) add("failure_level_invalid")
                    if (requirement.adversarialCases.any {
                            it.caseId.isBlank() ||
                                it.setup.isBlank() ||
                                it.expectedDetection.isBlank() ||
                                !it.boundedCost.isFinite() ||
                                it.boundedCost < 0.0
                        }
                    ) add("adversarial_case_invalid")
                    if (requirement.rationale.isBlank()) add("rationale_blank")
                    if (requirement.noveltyFingerprint.isBlank()) {
                        add("novelty_fingerprint_blank")
                    }
                    if (!requirement.maximumEvaluationCost.isFinite() ||
                        requirement.maximumEvaluationCost < 0.0
                    ) add("maximum_evaluation_cost_invalid")
                    if (!knownTestIds.containsAll(requirement.executableTestIds)) {
                        add("executable_tests_unknown")
                    }
                }
                predicates.takeIf(List<String>::isNotEmpty)
                    ?.let { requirement.requirementId to it }
            }
            if (invalidRequirement != null) {
                validationFailures +=
                    "typed requirement '${invalidRequirement.first}' is invalid: " +
                    invalidRequirement.second.joinToString(",")
            }
            graderView?.nodes
                ?.filter { it.leasedByThisGroup }
                ?.flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
                ?.let { requiredWorkerNodeIds ->
                    val coveredWorkerNodeIds = draft.requirements
                        .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
                    val missingWorkerNodeIds = requiredWorkerNodeIds - coveredWorkerNodeIds
                    if (missingWorkerNodeIds.isNotEmpty()) {
                        validationFailures +=
                            "methodology worker-node coverage is incomplete: " +
                            missingWorkerNodeIds
                                .sortedBy { it.value }
                                .joinToString(",") { workerNodeId ->
                                    val allowedRows = leasedViewNodes
                                        .filter { workerNodeId in it.mappedWorkerNodeIds }
                                        .map { it.nodeId.value }
                                        .sorted()
                                    "${workerNodeId.value}(allowedGraderAtlasNodeIds=" +
                                        "${allowedRows.joinToString(",")})"
                                }
                    }
                }
            val requirementIds = draft.requirements.mapTo(linkedSetOf()) { it.requirementId }
            if (draft.automatedTests.any {
                    it.mappedRequirementIds.isEmpty() ||
                        !requirementIds.containsAll(it.mappedRequirementIds) ||
                        it.inputSchemaHash.isBlank() ||
                        it.expectedOutcomeHash.isBlank() ||
                        (it.difficulty?.let { difficulty ->
                            difficulty !in 0.0..1.0
                        } == true)
                }
            ) {
                validationFailures +=
                    "every hidden test must bind known requirements and exact contracts"
            }
        }
        if (validationFailures.isNotEmpty()) {
            return "Error: methodology validation failed: " +
                validationFailures.joinToString(" | ").take(8_000)
        }
        val content = Json.encodeToString(GraderMethodologyDraft.serializer(), draft)
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = agentId,
                detail = "GRADING_SHEET_DRAFT",
                sizeBytes = content.toByteArray(Charsets.UTF_8).size
            )
        )
        val deduction = budgetLedger.deduct(ActionType.WRITE_STAGING)
        if (deduction == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot publish grading sheet."
        }
        val id = "grading-sheet-draft-${UUID.randomUUID()}"
        stagingDao.insert(
            StagingSubmissionEntity(
                id = id,
                agentId = agentId,
                role = "GraderBuilder",
                type = "PLAN_UPDATE",
                content = content,
                wipTaskRef = defaultWipTaskRef,
                speculative = false,
                outlier = false,
                budgetSignature =
                    "$GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX$agentId:$generation",
                status = "pending",
                initialTrustScore = 0.7,
                generation = generation,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = System.currentTimeMillis()
            )
        )
        return "Grading sheet draft published (ID: $id). Awaiting Grader Critic review."
    }
}
