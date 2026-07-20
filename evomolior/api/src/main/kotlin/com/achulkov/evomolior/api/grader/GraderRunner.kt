@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.agents.factory.GraderAgentFactory
import com.achulkov.evomolior.agents.factory.LlmBackendToolSupportPolicy
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreAuthor
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRepairDraft
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRequest
import com.achulkov.evomolior.agents.factory.RolePromptBuilder
import com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyAuthor
import com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest
import com.achulkov.evomolior.agents.factory.ProviderCallCostRecorder
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeAuthor
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradePriorDecision
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeRequest
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.competition.grader.CanonSnapshotExporter
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.competition.grader.AutomatedTest
import com.achulkov.evomolior.competition.grader.FailureMode
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.grader.BoundedScore
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderBundleValidationFailure
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.RequirementResult
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import com.achulkov.evomolior.core.grader.SafeRegexMatcher
import com.achulkov.evomolior.core.grader.ScoreNormalizationTrace
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val JUDGE_MAX_CRITERIA_ENTRY_CHARS = 900
private const val CRITERIA_SEED_MAX_KB_DOCS = 4
private const val CRITERIA_SEED_MAX_KB_CHARS = 700
private const val GRADER_KNOWLEDGE_MAX_KB_DOCS = 8
private const val GRADER_KNOWLEDGE_MAX_KG_FACTS = 16
private const val GRADER_KNOWLEDGE_MAX_ENTRY_CHARS = 700
private const val RECOVERED_CRITERIA_MAX_CHARS = 4_000
private const val DETERMINISTIC_CRITERIA_SEED_ROLE = "DeterministicCriteriaSeed"
private const val DIRECT_TEXT_ONLY_CRITERIA_SIGNATURE_PREFIX = "TEXT_ONLY_BACKEND"
private const val DIRECT_AGENT_CRITERIA_SIGNATURE_PREFIX = "DIRECT_AGENT_OUTPUT"
private val graderScoreJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

internal data class AuthoredTestOutcome(
    val testId: String,
    val description: String,
    val passed: Boolean,
    val mandatory: Boolean,
    val penalty: Double
)

internal data class AuthoredTestAssessment(
    val outcomes: List<AuthoredTestOutcome>
) {
    val promptContext: String = outcomes.joinToString("\n") { outcome ->
        "- ${outcome.testId}: ${if (outcome.passed) "PASS" else "FAIL"}; " +
            "mandatory=${outcome.mandatory}; penalty=${outcome.penalty}; ${outcome.description}"
    }

    fun applyTo(
        scores: List<DimensionScore>
    ): List<DimensionScore> {
        if (scores.isEmpty()) return emptyList()
        if (outcomes.any { !it.passed && it.mandatory }) {
            return scores.map { score ->
                score.copy(
                    rawScore = 0.0,
                    justification = "Mandatory grader-authored automated test failed: " +
                        outcomes.filter { !it.passed && it.mandatory }.joinToString { it.testId }
                )
            }
        }
        val penalty = outcomes.sumOf { it.penalty }
        if (penalty <= 0.0) return scores
        return scores.map { score ->
            score.copy(
                rawScore = (score.rawScore - penalty).coerceAtLeast(0.0),
                justification = "${score.justification} Automated-test penalty: $penalty."
            )
        }
    }
}

/**
 * Orchestrates a full Grader Group evaluation round.
 *
 * For each grader group and each competing group, this runner:
 * 1. Exports a canon snapshot of the competing group's substrate.
 * 2. Runs three grader agents sequentially: GraderResearcher → GraderBuilder → GraderCritic.
 * 3. Each agent passes a summary of their findings to the next agent in the pipeline.
 * 4. Collected [DimensionScore]s are submitted to [GraderGroupManager].
 *
 * Grader agents run **post-round** (after all competing agents have completed) and
 * **pre-grading** (before GraderGroupManager.computeRoundResult).
 *
 * Lives in the `:engine:api` module because it depends on both `:engine:agents`
 * (via transitive path api → competition → orchestration → agents) and
 * `:engine:competition` directly, avoiding a circular dependency.
 *
 * @param openRouterApiKey OpenRouter API key passed to [GraderAgentFactory].
 * @param substrateFactory Factory for accessing both competing and grader substrate DBs.
 * @param localAgentRunner Suspend function that runs a [GraderAgentContext] agent.
 *   Called once per grader agent. Return value is the agent's final text output.
 * @param graderAgentTimeoutMs Maximum ms a single grader agent is allowed to run (default 4 min).
 * @param sealedGraderModelAssignments Immutable run authority mapping each grader role name to
 *   its exact model ID. When supplied, this authority cannot fall back to [graderModelOverride]
 *   or a factory default.
 */
class GraderRunner(
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val substrateFactory: SubstrateDatabaseFactory,
    private val localAgentRunner: suspend (context: GraderAgentContext) -> String,
    private val graderAgentTimeoutMs: Long = 4 * 60_000L,
    private val graderBudgetMultiplier: Double = 1.0,
    private val graderModelOverride: String? = null,
    sealedGraderModelAssignments: Map<String, String>? = null,
    private val structuredMethodologyAuthor:
        (suspend (StructuredGraderMethodologyRequest) -> String)? = null,
    private val structuredDimensionScoreAuthor:
        (suspend (StructuredDimensionScoreRequest) -> String)? = null,
    private val structuredNodeGradeAuthor:
        (suspend (StructuredNodeGradeRequest) -> String)? = null,
    private val costLedgerRepositoryProvider: (String) -> CostLedgerRepository = { runId ->
        CostLedgerRepository(substrateFactory.get(runId, "__cost_ledger__"))
    }
) {
    private val graderAuthorityRepository = GraderAuthorityRepository(substrateFactory)
    private val atlasTransactionRepository = AtlasTransactionRepository(substrateFactory)
    private val exactNodeEvaluationContextResolver =
        ExactNodeEvaluationContextResolver(substrateFactory)
    private val graderNodeLeaseAuthority =
        GraderNodeLeaseAuthority(graderAuthorityRepository)
    private val sealedGraderModelAssignments = sealedGraderModelAssignments?.toMap()

    init {
        sealedGraderModelAssignments?.let { assignments ->
            GRADER_ROLES.forEach { role ->
                require(!assignments[role.name].isNullOrBlank()) {
                    "Sealed grader model authority is missing a non-blank assignment for ${role.name}"
                }
            }
        }
    }

    /**
     * Resolves the only model permitted for [role].
     *
     * @param role Grader role whose model is required.
     * @return The sealed role assignment, or the legacy override when no sealed authority exists.
     */
    internal fun modelOverrideFor(role: Role): String? {
        require(role in GRADER_ROLES) {
            "Model assignment requested for non-grader role ${role.name}"
        }
        return sealedGraderModelAssignments?.getValue(role.name) ?: graderModelOverride
    }

    private fun graderToolsPath(runId: String, graderGroupId: String): String =
        substrateFactory.graderDbFilePath(runId, graderGroupId)
            .parentFile
            .resolve("tools")
            .absolutePath

    /**
     * Runs a full grader evaluation round.
     *
     * For each graderGroupId in [graderGroupIds], evaluates all [competingGroupIds] by running
     * 3 grader agents sequentially per target group. Scores are submitted to [graderGroupManager].
     *
     * This method catches and logs per-group errors so a single grader failure does not abort
     * the entire round. The [GraderGroupManager] will use whatever scores were collected.
     *
     * @param runId The current run ID.
     * @param graderGroupIds Grader group IDs (typically ["grader-0"] for single grader group).
     * @param competingGroupIds All competing group IDs to evaluate.
     * @param roundIndex Current round index (0-based).
     * @param runObjective The run's original objective string.
     * @param scoringDimensions All scoring dimensions for this run.
     * @param graderGroupManager Manager to submit collected scores to.
     * @param onProgress Optional callback for progress updates (human-readable status string).
     */
    @Suppress("unused") // Legacy; replaced by runCriteriaPhase + runJudgingPhase two-phase architecture
    suspend fun runGraderRound(
        runId: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)? = null
    ) {
        Napier.i(
            "GraderRunner: starting grader round=$roundIndex " +
            "graders=${graderGroupIds.size} targets=${competingGroupIds.size}"
        )

        for (graderGroupId in graderGroupIds) {
            val graderDb = substrateFactory.getGrader(runId, graderGroupId)

            // Collect scores via callback — each grader agent calls submit_grading_score
            val collectedScores = mutableMapOf<String, MutableList<DimensionScore>>()

            val factory = GraderAgentFactory(
                openRouterApiKey = openRouterApiKey,
                llmBackend = llmBackend,
                graderDb = graderDb,
                graderBudgetConfig = graderBudgetConfigFor(graderGroupId),
                graderGroupId = graderGroupId,
                costLedgerRepository = costLedgerRepositoryProvider(runId),
                toolsMountPath = graderToolsPath(runId, graderGroupId),
                onScoreSubmitted = { targetGroupId, score ->
                    collectedScores.getOrPut(targetGroupId) { mutableListOf() }.add(score)
                    Napier.d(
                        "GraderRunner [$graderGroupId] score collected: " +
                        "$targetGroupId | ${score.dimensionId} = ${
                            String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)
                        }"
                    )
                }
            )

            for (targetGroupId in competingGroupIds) {
                onProgress?.invoke("[$graderGroupId] evaluating $targetGroupId (round $roundIndex)")

                try {
                    evaluateGroup(
                        factory = factory,
                        runId = runId,
                        roundIndex = roundIndex,
                        targetGroupId = targetGroupId,
                        graderGroupId = graderGroupId,
                        runObjective = runObjective,
                        scoringDimensions = scoringDimensions
                    )
                } catch (e: Exception) {
                    Napier.w(
                        "GraderRunner: evaluation failed for $targetGroupId " +
                        "by $graderGroupId round=$roundIndex: ${e.message}"
                    )
                }

                // Submit whatever scores were collected (even partial from a crashed run)
                val scoresForGroup = collectedScores[targetGroupId] ?: emptyList()
                if (scoresForGroup.isNotEmpty()) {
                    graderGroupManager.submitScores(
                        graderGroupId = graderGroupId,
                        targetGroupId = targetGroupId,
                        roundIndex = roundIndex,
                        scores = scoresForGroup
                    )
                    Napier.i(
                        "GraderRunner [$graderGroupId] submitted ${scoresForGroup.size} scores " +
                        "for $targetGroupId round=$roundIndex"
                    )
                } else {
                    Napier.w(
                        "GraderRunner [$graderGroupId]: no scores collected for $targetGroupId " +
                        "round=$roundIndex — group will default to substrate metrics only"
                    )
                }
            }
        }

        Napier.i("GraderRunner: grader round=$roundIndex complete")
    }

    // ── Two-phase architecture: criteria development + round judging ────────────────────────

    /**
     * **Phase A: Criteria Development** — runs DURING the round, in parallel with main agents.
     *
     * For each grader group, spawns two agents sequentially:
     * 1. [Role.GraderResearcher] — researches and designs scoring criteria for each dimension
     * 2. [Role.GraderBuilder] — refines the criteria for operational precision
     *
     * Agents write their criteria to the grader group's staging area via `write_staging`.
     * They do NOT have access to any competing group's data and do NOT call `submit_grading_score`.
     *
     * Meant to be launched as a concurrent coroutine at round start.
     *
     * @param runId Current run ID.
     * @param graderGroupIds Grader group IDs.
     * @param roundIndex Round for which criteria are being developed.
     * @param runObjective The run's objective string.
     * @param scoringDimensions Dimensions to develop criteria for.
     * @param onProgress Optional progress callback.
     */
    suspend fun runCriteriaPhase(
        runId: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        onProgress: ((String) -> Unit)? = null
    ) {
        Napier.i("GraderRunner: starting criteria phase round=$roundIndex graderGroups=${graderGroupIds.size}")
        val graderAtlas = graderAuthorityRepository.currentAtlas(runId)
        val workerAtlas = graderAtlas?.let { atlas ->
            requireNotNull(atlasTransactionRepository.currentAtlas(runId)) {
                "Compiled grader criteria require the sealed worker Atlas"
            }.also { worker ->
                require(
                    worker.atlasHash == atlas.workerAtlasHash &&
                        worker.version == atlas.version
                ) {
                    "Current worker Atlas does not match compiled grader authority"
                }
            }
        }
        graderAtlas?.let { graderNodeLeaseAuthority.initialize(it) }
        return supervisorScope {
            graderGroupIds.map { graderGroupId ->
                async {
                    try {
                        val graderView = graderAtlas?.let { atlas ->
                            val currentView = graderNodeLeaseAuthority.view(
                                atlas,
                                graderGroupId,
                                roundIndex
                            )
                            if (currentView.nodes.none { it.leasedByThisGroup }) {
                                graderNodeLeaseAuthority.acquireNext(
                                    atlas = atlas,
                                    graderGroupId = graderGroupId,
                                    round = roundIndex,
                                    ttlMillis = graderAgentTimeoutMs * 3
                                )
                                graderNodeLeaseAuthority.view(
                                    atlas,
                                    graderGroupId,
                                    roundIndex
                                )
                            } else {
                                currentView
                            }
                        }
                        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
                        val methodologyBindingCatalog = graderAtlas?.let {
                            GraderMethodologyBindingCatalog.from(
                                requireNotNull(graderView) {
                                    "Compiled Grader Atlas criteria require a leased GraderView"
                                },
                                requireNotNull(workerAtlas)
                            )
                        }
                        val factory = GraderAgentFactory(
                            openRouterApiKey = openRouterApiKey,
                            llmBackend = llmBackend,
                            graderDb = graderDb,
                            graderBudgetConfig = graderBudgetConfigFor(graderGroupId),
                            graderGroupId = graderGroupId,
                            costLedgerRepository = costLedgerRepositoryProvider(runId),
                            toolsMountPath = graderToolsPath(runId, graderGroupId),
                            onScoreSubmitted = { _, _ -> /* Criteria agents never submit scores */ }
                        )

                        val failureTelemetryContext = readFailureTelemetryContext(graderDb, runId, graderGroupId)
                        val seedContext = readGraderSeedContext(graderDb, runId, graderGroupId)
                        val compoundedKnowledgeContext = readGraderKnowledgeContext(
                            graderDb = graderDb,
                            runId = runId,
                            graderGroupId = graderGroupId,
                            maxGeneration = roundIndex
                        )
                        val civilization = GraderCivilizationController(
                            db = graderDb,
                            runId = runId,
                            graderGroupId = graderGroupId
                        )
                        val generationPlan = civilization.planGeneration(
                            generation = roundIndex + 1,
                            scoringDimensions = scoringDimensions
                        )
                        ensureDeterministicCriteriaSeedPersisted(
                            graderDb = graderDb,
                            runId = runId,
                            graderGroupId = graderGroupId,
                            roundIndex = roundIndex,
                            runObjective = runObjective,
                            scoringDimensions = scoringDimensions,
                            failureTelemetryContext = failureTelemetryContext,
                            seedContext = seedContext
                        )
                        val seedPrefix = seedContext?.let {
                            buildString {
                                appendLine("=== GRADER FOUNDING BRIEF ===")
                                appendLine(it)
                            }
                        }
                        val telemetryPrefix = failureTelemetryContext?.let {
                            buildString {
                                appendLine("=== RECENT FAILURE TELEMETRY ===")
                                appendLine(it)
                            }
                        }

                        var previousCriteria: String? = buildString {
                            seedPrefix?.let { appendLine(it) }
                            compoundedKnowledgeContext?.let {
                                appendLine("=== GRADER COMPOUNDED SUBSTRATE ===")
                                appendLine(it)
                            }
                            telemetryPrefix?.let { appendLine(it) }
                        }.ifBlank { null }
                        for (assignment in generationPlan.assignments) {
                            val role = assignment.role
                            onProgress?.invoke("[$graderGroupId] criteria phase: spawning $role for round $roundIndex")
                            Napier.d("GraderRunner: criteria phase spawning $role for $graderGroupId round=$roundIndex")
                            val existingCriteriaIds = graderDb.stagingDao().getAll(runId, graderGroupId)
                                .filter {
                                    it.type == "PLAN_UPDATE" &&
                                        it.role == role.name &&
                                        it.generation == roundIndex + 1
                                }
                                .map { it.id }
                                .toSet()
                            val context = factory.createCriteria(
                                role = role,
                                runId = runId,
                                roundIndex = roundIndex,
                                runObjective = runObjective,
                                scoringDimensions = scoringDimensions,
                                modelOverride = modelOverrideFor(role),
                                previousCriteria = previousCriteria,
                                foundingBrief = seedContext,
                                wipTaskRef = assignment.taskId,
                                wipTaskDescription = assignment.taskDescription,
                                legacyContext = if (graderAtlas != null) {
                                    typedGraderLegacyContext(
                                        runId,
                                        graderGroupId,
                                        roundIndex
                                    )
                                } else {
                                    civilization.legacyContext(
                                        role = role,
                                        generation = generationPlan.generation
                                    )
                                },
                                graderAtlas = graderAtlas,
                                graderView = graderView,
                                workerAtlas = workerAtlas
                            )
                            civilization.claim(
                                assignment = assignment,
                                agentId = context.agentId,
                                generation = generationPlan.generation
                            )
                            val output = try {
                                withProgressHeartbeat(
                                    onProgress = onProgress,
                                    heartbeatMessage = "[$graderGroupId] $role still developing criteria for round $roundIndex"
                                ) {
                                    withTimeout(graderAgentTimeoutMs) { localAgentRunner(context) }
                                }
                            } catch (e: Exception) {
                                Napier.w("GraderRunner: criteria $role failed for $graderGroupId: ${e.message}")
                                "[$role criteria phase terminated: ${e.message?.take(200)}]"
                            }
                            ensureCriteriaPersisted(
                                graderDb = graderDb,
                                runId = runId,
                                graderGroupId = graderGroupId,
                                roundIndex = roundIndex,
                                role = role,
                                runObjective = runObjective,
                                scoringDimensions = scoringDimensions,
                                failureTelemetryContext = failureTelemetryContext,
                                seedContext = seedContext,
                                agentOutput = output,
                                agentId = context.agentId,
                                textOnlyCompatibilityMode = factoryUsesTextOnlyCompatibilityMode(),
                                existingCriteriaIds = existingCriteriaIds,
                                wipTaskRef = assignment.taskId
                            )
                            previousCriteria = buildString {
                                seedPrefix?.let { appendLine(it) }
                                compoundedKnowledgeContext?.let {
                                    appendLine("=== GRADER COMPOUNDED SUBSTRATE ===")
                                    appendLine(it)
                                }
                                telemetryPrefix?.let { appendLine(it) }
                                appendLine("=== ${role.name} CRITERIA ===")
                                appendLine(output.take(3_000))
                            }
                            Napier.d("GraderRunner: criteria $role completed for $graderGroupId round=$roundIndex (${output.length} chars)")
                        }
                        if (graderAtlas != null &&
                            !hasTypedMethodologyDraft(graderDb, runId, graderGroupId, roundIndex)
                        ) {
                            val builderAssignment = generationPlan.assignments
                                .lastOrNull { it.role == Role.GraderBuilder }
                            val structuredAuthorAgentId =
                                "criteria-structured-graderbuilder-" +
                                    java.util.UUID.randomUUID().toString().take(8)
                            val publisher = factory.createMethodologyPublicationService(
                                runId = runId,
                                roundIndex = roundIndex,
                                scoringDimensions = scoringDimensions,
                                graderAtlas = graderAtlas,
                                graderView = requireNotNull(graderView),
                                workerAtlas = requireNotNull(workerAtlas),
                                wipTaskRef = builderAssignment?.taskId,
                                agentId = structuredAuthorAgentId
                            )
                            val author = structuredMethodologyAuthor ?: run {
                                val productionAuthor = StructuredGraderMethodologyAuthor(
                                    apiKey = openRouterApiKey,
                                    llmBackend = llmBackend,
                                    modelId = modelOverrideFor(Role.GraderBuilder)
                                        ?: com.achulkov.evomolior.core.config.ModelSelectionConfig
                                            .DEFAULT_GRADER_MODEL,
                                    providerCostRecorder = ProviderCallCostRecorder(
                                        repository = costLedgerRepositoryProvider(runId),
                                        runId = runId,
                                        groupId = graderGroupId,
                                        agentId = structuredAuthorAgentId
                                    )
                                )
                                productionAuthor::author
                            }
                            var validationError: String? = latestRejectedMethodologyFeedback(
                                graderDb = graderDb,
                                runId = runId,
                                graderGroupId = graderGroupId,
                                roundIndex = roundIndex
                            )
                            var priorDraft: GraderMethodologyWireDraft? = null
                            repeat(TYPED_METHODOLOGY_REPAIR_ATTEMPTS) { repairIndex ->
                                if (hasTypedMethodologyDraft(
                                        graderDb,
                                        runId,
                                        graderGroupId,
                                        roundIndex
                                    )
                                ) {
                                    return@repeat
                                }
                                onProgress?.invoke(
                                    "[$graderGroupId] structured GraderBuilder methodology authorship " +
                                        "${repairIndex + 1}/$TYPED_METHODOLOGY_REPAIR_ATTEMPTS"
                                )
                                val request = StructuredGraderMethodologyRequest(
                                    runId = runId,
                                    graderGroupId = graderGroupId,
                                    roundIndex = roundIndex,
                                    runObjective = runObjective,
                                    scoringDimensions = scoringDimensions,
                                    bindingCatalog = requireNotNull(methodologyBindingCatalog),
                                    priorCriteria = previousCriteria,
                                    priorDraft = priorDraft,
                                    validationError = validationError
                                )
                                val rawDraft = runCatching {
                                    withProgressHeartbeat(
                                        onProgress = onProgress,
                                        heartbeatMessage =
                                            "[$graderGroupId] GraderBuilder authoring structured methodology"
                                    ) {
                                        withTimeout(graderAgentTimeoutMs) {
                                            author(request)
                                        }
                                    }
                                }.onFailure { error ->
                                    validationError = "model call failed: ${error.message}"
                                    Napier.w(
                                        "GraderRunner: structured methodology authorship failed for " +
                                            "$graderGroupId: ${error.message}"
                                    )
                                }.getOrNull() ?: return@repeat
                                val draft = runCatching {
                                    graderScoreJson.decodeFromString<GraderMethodologyWireDraft>(
                                        rawDraft.trim()
                                    )
                                }.onFailure { error ->
                                    validationError = "JSON decode failed: ${error.message}"
                                }.getOrNull() ?: return@repeat
                                priorDraft = draft
                                val publicationResult = publisher.publish(draft)
                                if (publicationResult.startsWith("Error:")) {
                                    validationError = publicationResult
                                    Napier.w(
                                        "GraderRunner: structured methodology rejected for " +
                                            "$graderGroupId: $publicationResult"
                                    )
                                }
                            }
                        }
                        onProgress?.invoke("[$graderGroupId] criteria phase complete for round $roundIndex")
                    } catch (e: Exception) {
                        Napier.w("GraderRunner: criteria phase failed for $graderGroupId round=$roundIndex: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        Napier.i("GraderRunner: criteria phase complete round=$roundIndex")
    }

    /**
     * **Phase B: Round Judging** — runs POST-round, after CriticPipeline.
     *
     * For each grader group, spawns a single RoundJudge agent that:
     * 1. Reads criteria developed during the criteria phase from the grader staging DB.
     * 2. Receives canon snapshots from all competing groups.
     * 3. Calls `submit_grading_score` for every (group × dimension) pair.
     *
     * The repository now proves grader DB-root isolation, criteria-phase runner isolation,
     * and judging-phase prompt wiring plus score aggregation through
     * `GraderRunnerJudgingPhaseTest`.
     *
     * Scores are submitted to [graderGroupManager] after the judge completes.
     *
     * @param runId Current run ID.
     * @param graderGroupIds Grader group IDs.
     * @param competingGroupIds All competing group IDs to be judged.
     * @param roundIndex Current round index.
     * @param runObjective The run's objective string.
     * @param scoringDimensions All scoring dimensions.
     * @param graderGroupManager Manager to submit collected scores to.
     * @param onProgress Optional progress callback.
     */
    suspend fun runJudgingPhase(
        runId: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        nodeGradingAssignments: List<NodeGradingAssignment> = emptyList(),
        onNodeGradeBundles: (List<NodeGradeBundle>) -> Unit = {},
        onProgress: ((String) -> Unit)? = null
    ): Map<String, String> {
        Napier.i(
            "GraderRunner: starting judging phase round=$roundIndex " +
            "graders=${graderGroupIds.size} targets=${competingGroupIds.size}"
        )

        val graderResults = supervisorScope {
            graderGroupIds.map { graderGroupId ->
                async {
                    val nodeGradeResponses = mutableListOf<NodeGraderResponse>()
                    try {
                        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
                        val collectedScores = mutableMapOf<String, MutableList<DimensionScore>>()

                        val factory = GraderAgentFactory(
                            openRouterApiKey = openRouterApiKey,
                            llmBackend = llmBackend,
                            graderDb = graderDb,
                            graderBudgetConfig = graderBudgetConfigFor(graderGroupId),
                            graderGroupId = graderGroupId,
                            costLedgerRepository = costLedgerRepositoryProvider(runId),
                            toolsMountPath = graderToolsPath(runId, graderGroupId),
                            onScoreSubmitted = { targetGroupId, score ->
                                collectedScores.getOrPut(targetGroupId) { mutableListOf() }.add(score)
                                Napier.d(
                                    "GraderRunner.judge [$graderGroupId] score: $targetGroupId | " +
                                        "${score.dimensionId} = ${String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)}"
                                )
                            },
                            onNodeGradeSubmitted = nodeGradeResponses::add
                        )

                        // Read criteria from grader staging DB — promoted PLAN_UPDATE entries are the criteria
                        val criteriaEntries = try {
                            graderDb.stagingDao().getAll(runId, graderGroupId)
                                .filter {
                                    it.type == "PLAN_UPDATE" &&
                                        it.generation <= roundIndex + 1 &&
                                        (it.status == "promoted" || it.status == "pending")
                                }
                                .sortedBy { it.submittedAtMs }
                        } catch (e: Exception) {
                            Napier.w("GraderRunner: failed to read criteria for $graderGroupId: ${e.message}")
                            emptyList()
                        }
                        val failureTelemetryContext = readFailureTelemetryContext(graderDb, runId, graderGroupId)
                        val seedContext = readGraderSeedContext(graderDb, runId, graderGroupId)
                        val compoundedKnowledgeContext = readGraderKnowledgeContext(
                            graderDb = graderDb,
                            runId = runId,
                            graderGroupId = graderGroupId,
                            maxGeneration = roundIndex + 1
                        )
                        val authoredDraft = latestAuthoredMethodology(
                            criteriaEntries = criteriaEntries,
                            roundIndex = roundIndex
                        )
                        authoredDraft?.let { draft ->
                            graderGroupManager.registerSheet(
                                draft.toGradingSheet(
                                    runId = runId,
                                    graderGroupId = graderGroupId,
                                    roundIndex = roundIndex,
                                    scoringDimensions = scoringDimensions
                                )
                            )
                        }
                        val baseCriteriaContext = buildJudgeCriteriaContext(
                            graderGroupId = graderGroupId,
                            roundIndex = roundIndex,
                            failureTelemetryContext = failureTelemetryContext,
                            criteriaEntries = criteriaEntries,
                            compoundedKnowledgeContext = compoundedKnowledgeContext
                        )

                        // Export canon snapshots for all competing groups in parallel.
                        val snapshotExports = competingGroupIds.associateWith { targetGroupId ->
                            async {
                                try {
                                    CanonSnapshotExporter.exportForJudge(
                                        substrateFactory.get(runId, targetGroupId),
                                        runId,
                                        targetGroupId,
                                        roundIndex
                                    )
                                } catch (e: Exception) {
                                    Napier.w("GraderRunner: snapshot export failed for $targetGroupId: ${e.message}")
                                    "(snapshot unavailable: ${e.message?.take(150)})"
                                }
                            }
                        }
                        val allGroupSnapshots = snapshotExports.mapValues { it.value.await() }
                        val automatedAssessments = allGroupSnapshots.mapValues { (_, snapshot) ->
                            authoredDraft?.assess(snapshot)
                        }
                        val judgeOutputs = mutableListOf<String>()
                        val authoritativeScoresByTarget =
                            mutableMapOf<String, List<DimensionScore>>()
                        for (targetGroupId in competingGroupIds) {
                            onProgress?.invoke("[$graderGroupId] RoundJudge spawning for round $roundIndex — scoring $targetGroupId")
                            Napier.i("GraderRunner: spawning RoundJudge for $graderGroupId round=$roundIndex target=$targetGroupId")

                            val targetSnapshot = allGroupSnapshots.getValue(targetGroupId)
                            val interactiveCriteriaContext = buildString {
                                append(baseCriteriaContext)
                                automatedAssessments[targetGroupId]?.let { assessment ->
                                    appendLine()
                                    appendLine()
                                    appendLine("=== GRADER-AUTHORED AUTOMATED TEST RESULTS ===")
                                    append(assessment.promptContext)
                                }
                            }
                            val targetAssignments = nodeGradingAssignments.filter {
                                it.packet.groupId.value == targetGroupId
                            }
                            val nodeEvaluationContexts =
                                exactNodeEvaluationContextResolver.resolve(
                                    runId = runId,
                                    roundIndex = roundIndex,
                                    graderGroupId = graderGroupId,
                                    assignments = targetAssignments
                                )
                            val judgeOutput = runRoundJudgeWithRetry(
                                factory = factory,
                                runId = runId,
                                roundIndex = roundIndex,
                                runObjective = runObjective,
                                scoringDimensions = scoringDimensions,
                                criteriaContext = interactiveCriteriaContext,
                                targetGroupId = targetGroupId,
                                targetSnapshot = targetSnapshot,
                                collectedScores = collectedScores,
                                nodeEvaluationContexts = nodeEvaluationContexts,
                                modelOverride = modelOverrideFor(Role.GraderCritic),
                                foundingBrief = seedContext,
                                graderGroupId = graderGroupId,
                                onProgress = onProgress
                            )
                            repairMissingDimensionScoresFromSealedAuthority(
                                runId = runId,
                                roundIndex = roundIndex,
                                graderGroupId = graderGroupId,
                                targetGroupId = targetGroupId,
                                runObjective = runObjective,
                                criteriaContext = baseCriteriaContext,
                                targetSnapshot = targetSnapshot,
                                scoringDimensions = scoringDimensions,
                                collectedScores = collectedScores,
                                onProgress = onProgress
                            )
                            repairMissingNodeGrades(
                                runId = runId,
                                graderGroupId = graderGroupId,
                                runObjective = runObjective,
                                evaluationContexts = nodeEvaluationContexts,
                                responses = nodeGradeResponses
                            )
                            judgeOutputs += "[$targetGroupId] ${judgeOutput.ifBlank { "no judge narrative returned" }}"
                        }

                        // Submit whatever scores were collected (even if judge partially failed)
                        val totalScores = collectedScores.values.sumOf { it.size }
                        Napier.i("GraderRunner.judge [$graderGroupId] collected $totalScores scores from RoundJudge")

                        for (targetGroupId in competingGroupIds) {
                            val deduplicatedScores = deduplicateScoresByDimension(
                                dimensions = scoringDimensions,
                                scores = collectedScores[targetGroupId] ?: emptyList()
                            )
                            val scoresForGroup = automatedAssessments[targetGroupId]
                                ?.applyTo(deduplicatedScores)
                                ?: deduplicatedScores
                            if (scoresForGroup.isNotEmpty()) {
                                authoritativeScoresByTarget[targetGroupId] = scoresForGroup
                                graderGroupManager.submitScores(
                                    graderGroupId = graderGroupId,
                                    targetGroupId = targetGroupId,
                                    roundIndex = roundIndex,
                                    scores = scoresForGroup
                                )
                                Napier.i("GraderRunner.judge [$graderGroupId] submitted ${scoresForGroup.size} scores for $targetGroupId")
                            } else {
                                Napier.w("GraderRunner.judge [$graderGroupId] no scores for $targetGroupId — will rely on substrate scoring")
                            }
                        }
                        persistExactGraderScoreBundles(
                            runId = runId,
                            roundIndex = roundIndex,
                            graderGroupId = graderGroupId,
                            competingGroupIds = competingGroupIds,
                            scoringDimensions = scoringDimensions,
                            allGroupSnapshots = allGroupSnapshots,
                            nodeGradingAssignments = nodeGradingAssignments,
                            nodeGradeResponses = nodeGradeResponses,
                            authoritativeScoresByTarget = authoritativeScoresByTarget,
                            automatedAssessments = automatedAssessments,
                            authoredDraft = authoredDraft
                        )
                        onProgress?.invoke("[$graderGroupId] judging phase complete for round $roundIndex — $totalScores scores submitted")
                        GraderJudgingOutput(
                            graderGroupId = graderGroupId,
                            narrative = judgeOutputs.joinToString(" | "),
                            nodeGradeResponses = nodeGradeResponses.toList()
                        )
                    } catch (e: Exception) {
                        Napier.w("GraderRunner: judging phase failed for $graderGroupId round=$roundIndex: ${e.message}")
                        GraderJudgingOutput(
                            graderGroupId = graderGroupId,
                            narrative = "[judging_failed] ${e.message ?: e::class.simpleName.orEmpty()}",
                            nodeGradeResponses = nodeGradeResponses.toList()
                        )
                    }
                }
            }.awaitAll()
        }
        val judgeOutputs = graderResults.associate { it.graderGroupId to it.narrative }
        onNodeGradeBundles(
            aggregateNodeGradeBundles(
                assignments = nodeGradingAssignments,
                expectedGraderIds = graderGroupIds.toSet(),
                responses = graderResults.flatMap(GraderJudgingOutput::nodeGradeResponses)
            )
        )

        Napier.i("GraderRunner: judging phase complete round=$roundIndex")
        return judgeOutputs
    }

    private suspend fun repairMissingDimensionScoresFromSealedAuthority(
        runId: String,
        roundIndex: Int,
        graderGroupId: String,
        targetGroupId: String,
        runObjective: String,
        criteriaContext: String,
        targetSnapshot: String,
        scoringDimensions: List<ScoringDimension>,
        collectedScores: MutableMap<String, MutableList<DimensionScore>>,
        onProgress: ((String) -> Unit)?
    ) {
        val freeze = graderAuthorityRepository.criteriaFreezeForRound(
            runId = runId,
            round = roundIndex,
            graderGroupId = graderGroupId
        ) ?: return
        val target = com.achulkov.evomolior.core.atlas.AtlasGroupId(targetGroupId)
        val targetSnapshotHash = AtlasHashing.hashText(targetSnapshot)
        val authorityValid = freeze.hasValidHash() &&
            freeze.runId == runId &&
            freeze.round == roundIndex &&
            freeze.graderGroupId == graderGroupId &&
            freeze.targetSnapshotHashes[target] == targetSnapshotHash
        if (!authorityValid) {
            Napier.w(
                "GraderRunner: structured dimension repair skipped for grader=$graderGroupId " +
                    "target=$targetGroupId because sealed criteria or snapshot authority mismatched"
            )
            return
        }
        onProgress?.invoke(
            "[$graderGroupId] structured RoundJudge dimension repair for $targetGroupId"
        )
        repairMissingDimensionScores(
            runId = runId,
            graderGroupId = graderGroupId,
            targetGroupId = targetGroupId,
            runObjective = runObjective,
            criteriaFreezeHash = freeze.freezeHash,
            targetSnapshotHash = targetSnapshotHash,
            criteriaContext = criteriaContext,
            targetSnapshot = targetSnapshot,
            scoringDimensions = scoringDimensions,
            collectedScores = collectedScores,
            onProgress = onProgress
        )
    }

    internal suspend fun repairMissingDimensionScores(
        runId: String,
        graderGroupId: String,
        targetGroupId: String,
        runObjective: String,
        criteriaFreezeHash: String,
        targetSnapshotHash: String,
        criteriaContext: String,
        targetSnapshot: String,
        scoringDimensions: List<ScoringDimension>,
        collectedScores: MutableMap<String, MutableList<DimensionScore>>,
        onProgress: ((String) -> Unit)? = null
    ) {
        val dimensionsById = scoringDimensions.associateBy(ScoringDimension::id)
        val existingScores = collectedScores[targetGroupId].orEmpty().toList()
        val existingIds = existingScores.map(DimensionScore::dimensionId)
        val existingAuthorityValid =
            criteriaFreezeHash.isNotBlank() &&
                targetSnapshotHash.isNotBlank() &&
                dimensionsById.size == scoringDimensions.size &&
                existingIds.size == existingIds.toSet().size &&
                existingScores.all { score ->
                    val dimension = dimensionsById[score.dimensionId]
                    dimension != null &&
                        score.rawScore.isFinite() &&
                        score.rawScore in 0.0..dimension.maxScore &&
                        score.justification.isNotBlank()
                }
        if (!existingAuthorityValid) {
            Napier.w(
                "GraderRunner: structured dimension repair rejected malformed existing authority " +
                    "for grader=$graderGroupId target=$targetGroupId"
            )
            return
        }
        val missingDimensions = scoringDimensions.filterNot { it.id in existingIds }
        if (missingDimensions.isEmpty()) return

        val agentId = "dimension-score-structured-$graderGroupId-$targetGroupId"
        val author = structuredDimensionScoreAuthor ?: StructuredDimensionScoreAuthor(
            apiKey = openRouterApiKey,
            llmBackend = llmBackend,
            modelId = modelOverrideFor(Role.GraderCritic)
                ?: com.achulkov.evomolior.core.config.ModelSelectionConfig.DEFAULT_GRADER_MODEL,
            providerCostRecorder = ProviderCallCostRecorder(
                repository = costLedgerRepositoryProvider(runId),
                runId = runId,
                groupId = graderGroupId,
                agentId = agentId
            )
        )::author
        val missingIds = missingDimensions.map(ScoringDimension::id)
        var validationError: String? = null
        repeat(STRUCTURED_DIMENSION_SCORE_ATTEMPTS) { attempt ->
            val raw = runCatching {
                withProgressHeartbeat(
                    onProgress = onProgress,
                    heartbeatMessage =
                        "[$graderGroupId] structured RoundJudge still repairing $targetGroupId"
                ) {
                    withTimeout(graderAgentTimeoutMs) {
                        author(
                            StructuredDimensionScoreRequest(
                                graderGroupId = graderGroupId,
                                targetGroupId = targetGroupId,
                                runObjective = runObjective,
                                criteriaFreezeHash = criteriaFreezeHash,
                                targetSnapshotHash = targetSnapshotHash,
                                criteriaContext = criteriaContext,
                                targetSnapshot = targetSnapshot,
                                alreadyAuthoredScores = existingScores,
                                missingDimensions = missingDimensions,
                                validationError = validationError
                            )
                        )
                    }
                }
            }.onFailure { validationError = "model call failed: ${it.message}" }
                .getOrNull() ?: return@repeat
            val draft = runCatching {
                graderScoreJson.decodeFromString<StructuredDimensionScoreRepairDraft>(raw)
            }.onFailure { validationError = "JSON decode failed: ${it.message}" }
                .getOrNull() ?: return@repeat
            val responseIds = draft.scores.map { it.dimensionId }
            val exactCoverage = responseIds == responseIds.distinct() &&
                responseIds.size == missingIds.size &&
                responseIds.toSet() == missingIds.toSet()
            val semanticsValid = exactCoverage && draft.scores.all { score ->
                val dimension = dimensionsById[score.dimensionId]
                dimension != null &&
                    score.rawScore.isFinite() &&
                    score.rawScore in 0.0..dimension.maxScore &&
                    score.rationale.isNotBlank()
            }
            if (!semanticsValid) {
                validationError =
                    "response must contain the exact missing IDs once, in range, with rationale"
                return@repeat
            }
            if (collectedScores[targetGroupId].orEmpty() != existingScores) {
                Napier.w(
                    "GraderRunner: structured dimension repair observed concurrent score mutation " +
                        "for grader=$graderGroupId target=$targetGroupId"
                )
                return
            }
            val draftsById = draft.scores.associateBy { it.dimensionId }
            val repairedScores = missingDimensions.map { dimension ->
                val score = draftsById.getValue(dimension.id)
                DimensionScore(
                    dimensionId = score.dimensionId,
                    rawScore = score.rawScore,
                    justification = score.rationale
                )
            }
            collectedScores.getOrPut(targetGroupId) { mutableListOf() }.addAll(repairedScores)
            Napier.i(
                "GraderRunner: structured dimension repair accepted " +
                    "grader=$graderGroupId target=$targetGroupId attempt=${attempt + 1} " +
                    "dimensions=${missingIds.joinToString()}"
            )
            return
        }
        Napier.w(
            "GraderRunner: structured dimension repair failed closed " +
                "grader=$graderGroupId target=$targetGroupId dimensions=${missingIds.joinToString()}"
        )
    }

    internal suspend fun repairMissingNodeGrades(
        runId: String,
        graderGroupId: String,
        runObjective: String,
        evaluationContexts: List<ExactNodeEvaluationContext>,
        responses: MutableList<NodeGraderResponse>
    ) {
        val agentId = "node-grade-structured-$graderGroupId"
        val author = structuredNodeGradeAuthor ?: StructuredNodeGradeAuthor(
            apiKey = openRouterApiKey,
            llmBackend = llmBackend,
            modelId = modelOverrideFor(Role.GraderCritic)
                ?: com.achulkov.evomolior.core.config.ModelSelectionConfig.DEFAULT_GRADER_MODEL,
            providerCostRecorder = ProviderCallCostRecorder(
                repository = costLedgerRepositoryProvider(runId),
                runId = runId,
                groupId = graderGroupId,
                agentId = agentId
            )
        )::author
        require(
            evaluationContexts.all {
                it.graderGroupId == graderGroupId && it.hasValidHash()
            }
        ) {
            "Structured node-grade repair requires exact valid grader-scoped contexts"
        }
        evaluationContexts.forEach assignmentLoop@ { evaluationContext ->
            val assignment = evaluationContext.assignment
            val matchingResponseIndexes = responses.indices.filter { index ->
                responses[index].assignmentId == assignment.assignmentId &&
                    responses[index].graderId == graderGroupId
            }
            if (matchingResponseIndexes.size > 1) {
                // Duplicate responses are defective authority and must remain visible.
                return@assignmentLoop
            }
            val existingIndex = matchingResponseIndexes.singleOrNull()
            val existingResponse = existingIndex?.let(responses::get)
            val authoritativeExisting = existingResponse?.takeIf {
                it.hasValidHash() && it.assignmentHash == assignment.assignmentHash
            }
            if (existingResponse != null && authoritativeExisting == null) {
                // Malformed imported authority must remain visible to fail-closed aggregation.
                return@assignmentLoop
            }
            when (authoritativeExisting?.verdict) {
                NodeGraderResponseVerdict.VALID,
                NodeGraderResponseVerdict.INVALID -> return@assignmentLoop
                NodeGraderResponseVerdict.ABSTAINED,
                null -> Unit
            }
            val priorDecision = authoritativeExisting
                ?.takeIf { it.verdict == NodeGraderResponseVerdict.ABSTAINED }
                ?.let {
                    StructuredNodeGradePriorDecision(
                        verdict = StructuredNodeGradeVerdict.ABSTAINED,
                        rationale = it.rationale
                    )
                }
            if (priorDecision != null) {
                Napier.i(
                    "GraderRunner: reconsidering abstained node grade " +
                        "grader=$graderGroupId assignment=${assignment.assignmentId} " +
                        "responseHash=${requireNotNull(authoritativeExisting).responseHash}"
                )
            }
            var validationError: String? = null
            repeat(STRUCTURED_NODE_GRADE_ATTEMPTS) { attempt ->
                val raw = runCatching {
                    author(
                        StructuredNodeGradeRequest(
                            graderGroupId = graderGroupId,
                            runObjective = runObjective,
                            evaluationContext = evaluationContext,
                            priorDecision = priorDecision,
                            validationError = validationError
                        )
                    )
                }.onFailure { validationError = "model call failed: ${it.message}" }
                    .getOrNull() ?: return@repeat
                val draft = runCatching {
                    graderScoreJson.decodeFromString<StructuredNodeGradeDraft>(raw)
                }.onFailure { validationError = "JSON decode failed: ${it.message}" }
                    .getOrNull() ?: return@repeat
                val values = draft.scoreVector?.let { vector ->
                    listOf(
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
                }.orEmpty()
                val semanticValid = draft.rationale.isNotBlank() &&
                    when (draft.verdict) {
                        StructuredNodeGradeVerdict.VALID ->
                            draft.scoreVector != null &&
                                values.all { it.isFinite() && it in 0.0..1.0 }
                        StructuredNodeGradeVerdict.ABSTAINED -> draft.scoreVector == null
                    }
                if (!semanticValid) {
                    validationError = "verdict, scoreVector, normalization, or rationale is invalid"
                    return@repeat
                }
                val repairedResponse = NodeGraderResponse(
                    responseId = AtlasHashing.hashText(
                        "${assignment.assignmentHash}|$graderGroupId"
                    ),
                    assignmentId = assignment.assignmentId,
                    assignmentHash = assignment.assignmentHash,
                    graderId = graderGroupId,
                    scoreVector = draft.scoreVector,
                    verdict = when (draft.verdict) {
                        StructuredNodeGradeVerdict.VALID -> NodeGraderResponseVerdict.VALID
                        StructuredNodeGradeVerdict.ABSTAINED ->
                            NodeGraderResponseVerdict.ABSTAINED
                    },
                    rationale = draft.rationale
                ).withComputedHash()
                if (existingIndex == null) {
                    responses += repairedResponse
                } else {
                    responses[existingIndex] = repairedResponse
                }
                if (priorDecision != null) {
                    Napier.i(
                        "GraderRunner: abstained node-grade reconsideration completed " +
                            "grader=$graderGroupId assignment=${assignment.assignmentId} " +
                            "attempt=${attempt + 1} verdict=${repairedResponse.verdict} " +
                            "responseHash=${repairedResponse.responseHash}"
                    )
                }
                return@assignmentLoop
            }
        }
    }

    internal suspend fun persistExactGraderScoreBundles(
        runId: String,
        roundIndex: Int,
        graderGroupId: String,
        competingGroupIds: List<String>,
        scoringDimensions: List<ScoringDimension>,
        allGroupSnapshots: Map<String, String>,
        nodeGradingAssignments: List<NodeGradingAssignment>,
        nodeGradeResponses: List<NodeGraderResponse>,
        authoritativeScoresByTarget: Map<String, List<DimensionScore>>,
        automatedAssessments: Map<String, AuthoredTestAssessment?>,
        authoredDraft: GraderMethodologyDraft?
    ) {
        val repository = graderAuthorityRepository
        val freeze = repository.criteriaFreezeForRound(
            runId,
            roundIndex,
            graderGroupId
        ) ?: return
        val requirements = repository.requirementsForRound(
            runId,
            roundIndex,
            graderGroupId
        ).filter { it.requirementHash in freeze.requirementHashes }
        val frozenTests = repository.hiddenTestsForFreeze(freeze)
        val authoredTestsByStableId = authoredDraft?.automatedTests.orEmpty().associateBy { draft ->
            com.achulkov.evomolior.core.grader.HiddenTestId(
                AtlasHashing.hashText(
                    "grader-hidden-test|$runId|$roundIndex|$graderGroupId|${draft.testId}"
                )
            )
        }
        val localTestIdsByStableHash = authoredDraft?.automatedTests.orEmpty().associate { draft ->
            val stableId = com.achulkov.evomolior.core.grader.HiddenTestId(
                AtlasHashing.hashText(
                    "grader-hidden-test|$runId|$roundIndex|$graderGroupId|${draft.testId}"
                )
            )
            stableId to draft.testId
        }
        val frozenTestAuthorityComplete =
            frozenTests.size == freeze.hiddenTestHashes.size &&
                frozenTests.all { test ->
                    val draft = authoredTestsByStableId[test.testId]
                    draft != null &&
                        test.executorRef ==
                        "grader-regex:${draft.testId}:${AtlasHashing.hashText(draft.expectedOutputPattern)}"
                } &&
                requirements.flatMap(GradingRequirement::executableTestIds)
                    .all(authoredTestsByStableId::containsKey)
        val persistenceFailures = mutableListOf<String>()
        competingGroupIds.forEach { targetGroupId ->
            runCatching {
                val target = com.achulkov.evomolior.core.atlas.AtlasGroupId(targetGroupId)
                val snapshot = allGroupSnapshots.getValue(targetGroupId)
                val snapshotHash = AtlasHashing.hashText(snapshot)
                val expectedSnapshotHash = freeze.targetSnapshotHashes[target]
                val assignments = nodeGradingAssignments.filter {
                    it.packet.groupId == target
                }
                val assignmentIdsUnique =
                    assignments.map(NodeGradingAssignment::assignmentId).distinct().size ==
                        assignments.size
                val assignmentNodesUnique =
                    assignments.map { it.packet.nodeId }.distinct().size == assignments.size
                val assignmentsValid = assignmentIdsUnique &&
                    assignmentNodesUnique &&
                    assignments.all { assignment ->
                        assignment.hasValidHash() &&
                            assignment.graderCriteriaFreezeHashes[graderGroupId] ==
                            freeze.freezeHash
                    }
                val assignmentResponses = assignments.map { assignment ->
                    assignment to nodeGradeResponses.filter {
                        it.assignmentId == assignment.assignmentId &&
                            it.graderId == graderGroupId
                    }
                }
                val responseStructureValid = assignmentsValid &&
                    assignmentResponses.all { (assignment, responses) ->
                        responses.size == 1 &&
                            responses.single().hasValidHash() &&
                            responses.single().assignmentHash == assignment.assignmentHash &&
                            when (responses.single().verdict) {
                                NodeGraderResponseVerdict.VALID ->
                                    responses.single().scoreVector != null
                                NodeGraderResponseVerdict.ABSTAINED,
                                NodeGraderResponseVerdict.INVALID ->
                                    responses.single().scoreVector == null
                            }
                    }
                val responseAuthorityValid = responseStructureValid &&
                    assignmentResponses.all { (_, responses) ->
                        responses.single().verdict == NodeGraderResponseVerdict.VALID
                    }
                val abstained = assignmentResponses.any { (_, responses) ->
                    responses.singleOrNull()?.verdict == NodeGraderResponseVerdict.ABSTAINED
                }
                val scores = authoritativeScoresByTarget[targetGroupId].orEmpty()
                val scoreIds = scores.map(DimensionScore::dimensionId)
                val requiredDimensionIds = scoringDimensions
                    .mapTo(linkedSetOf(), ScoringDimension::id)
                val dimensionsById = scoringDimensions.associateBy(ScoringDimension::id)
                val dimensionPolicyValid =
                    dimensionsById.size == scoringDimensions.size &&
                        scoringDimensions.all {
                            it.maxScore.isFinite() && it.maxScore > 0.0
                        }
                val dimensionsComplete = dimensionPolicyValid &&
                    scoreIds.size == requiredDimensionIds.size &&
                    scoreIds.toSet() == requiredDimensionIds &&
                    scores.all { score ->
                        val dimension = dimensionsById[score.dimensionId]
                        dimension != null &&
                            score.rawScore.isFinite() &&
                            score.rawScore in 0.0..dimension.maxScore
                    }
                val testResultsComplete = frozenTestAuthorityComplete &&
                    frozenTests.all { frozenTest ->
                        val localTestId = localTestIdsByStableHash[frozenTest.testId]
                        localTestId != null &&
                            automatedAssessments[targetGroupId]
                                ?.outcomes
                                .orEmpty()
                                .any { it.testId == localTestId }
                    }
                val nodeScores = assignmentResponses.mapNotNull { (assignment, responses) ->
                    responses.singleOrNull()
                        ?.takeIf {
                            it.hasValidHash() &&
                                it.assignmentHash == assignment.assignmentHash &&
                                it.verdict == NodeGraderResponseVerdict.VALID
                        }
                        ?.scoreVector
                        ?.let { assignment.packet.nodeId to it }
                }.toMap()
                val groupScores = scores.mapNotNull { score ->
                    dimensionsById[score.dimensionId]
                        ?.takeIf {
                            it.maxScore.isFinite() &&
                                it.maxScore > 0.0 &&
                                score.rawScore.isFinite() &&
                                score.rawScore in 0.0..it.maxScore
                        }
                        ?.let { dimension ->
                            score.dimensionId to BoundedScore(
                                value = score.rawScore / dimension.maxScore
                            )
                        }
                }.toMap()
                val outcomesByLocalTestId = automatedAssessments[targetGroupId]
                    ?.outcomes
                    .orEmpty()
                    .associateBy(AuthoredTestOutcome::testId)
                val requirementResults = requirements.associate { requirement ->
                    val relevant = assignmentResponses.filter { (assignment, _) ->
                        assignment.packet.nodeId in requirement.mappedWorkerNodeIds
                    }
                    val resultStatus = when {
                        relevant.isEmpty() -> RequirementResultStatus.NOT_APPLICABLE
                        relevant.any { (_, responses) ->
                            responses.singleOrNull()?.verdict == NodeGraderResponseVerdict.ABSTAINED
                        } -> RequirementResultStatus.CONTESTED
                        relevant.any { (_, responses) ->
                            responses.size != 1 ||
                                !responses.single().hasValidHash() ||
                                responses.single().verdict != NodeGraderResponseVerdict.VALID
                        } -> RequirementResultStatus.INVALID
                        relevant.any { (_, responses) ->
                            responses.single().scoreVector?.hardFailure == true
                        } -> RequirementResultStatus.FAIL
                        else -> RequirementResultStatus.PASS
                    }
                    val testResultHashes = requirement.executableTestIds.mapNotNullTo(
                        linkedSetOf()
                    ) { stableTestId ->
                        localTestIdsByStableHash[stableTestId]
                            ?.let(outcomesByLocalTestId::get)
                            ?.let { outcome ->
                                AtlasHashing.hashText(
                                    "${stableTestId.value}|${outcome.passed}|" +
                                        "${outcome.mandatory}|${outcome.penalty}"
                                )
                            }
                    }
                    requirement.requirementId to RequirementResult(
                        status = resultStatus,
                        observedEvidenceHashes = relevant.mapTo(linkedSetOf()) {
                            it.first.packet.packetHash
                        },
                        failureLevel = requirement.failureLevels
                            .filter { level ->
                                resultStatus == RequirementResultStatus.FAIL &&
                                    level.critical
                            }
                            .maxByOrNull { it.level }
                            ?.level,
                        criticalFailure =
                            resultStatus == RequirementResultStatus.FAIL &&
                                requirement.failureLevels.any { it.critical },
                        executableTestResultHashes = testResultHashes
                    )
                }
                val rationaleHashes = buildMap {
                    scores.forEach { score ->
                        put(
                            "dimension:${score.dimensionId}",
                            AtlasHashing.hashText(score.justification)
                        )
                    }
                    assignmentResponses.forEach { (assignment, responses) ->
                        responses.singleOrNull()?.let { response ->
                            put(
                                "node:${assignment.packet.nodeId.value}",
                                AtlasHashing.hashText(response.rationale)
                            )
                        }
                    }
                }
                val normalizationTrace = buildList {
                    scores.forEach { score ->
                        dimensionsById[score.dimensionId]
                            ?.takeIf {
                                it.maxScore.isFinite() &&
                                    it.maxScore > 0.0 &&
                                    score.rawScore.isFinite() &&
                                    score.rawScore in 0.0..it.maxScore
                            }
                            ?.let { dimension ->
                                add(
                                    ScoreNormalizationTrace(
                                        sourceId = "dimension:${score.dimensionId}",
                                        rawValue = score.rawScore,
                                        normalizedValue = score.rawScore / dimension.maxScore,
                                        weight = dimension.weight,
                                        policyHash = freeze.freezeHash
                                    )
                                )
                            }
                    }
                    assignmentResponses.forEach { (assignment, responses) ->
                        responses.singleOrNull()?.scoreVector?.let { vector ->
                            add(
                                ScoreNormalizationTrace(
                                    sourceId = "node:${assignment.packet.nodeId.value}",
                                    rawValue = assignment.scoreWeights.normalizedScore(vector),
                                    normalizedValue =
                                        assignment.scoreWeights.normalizedScore(vector),
                                    weight = 1.0,
                                    policyHash = assignment.criteriaHash
                                )
                            )
                        }
                    }
                }
                val validationFailures = buildSet {
                    if (expectedSnapshotHash != snapshotHash) {
                        add(GraderBundleValidationFailure.TARGET_SNAPSHOT_MISMATCH)
                    }
                    if (!responseStructureValid) {
                        add(GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_STRUCTURE_INVALID)
                    }
                    if (!responseAuthorityValid && !abstained) {
                        add(GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_AUTHORITY_INVALID)
                    }
                    if (!dimensionPolicyValid) {
                        add(GraderBundleValidationFailure.DIMENSION_POLICY_INVALID)
                    }
                    if (!dimensionsComplete) {
                        add(GraderBundleValidationFailure.DIMENSIONS_INCOMPLETE)
                    }
                    if (!testResultsComplete) {
                        add(GraderBundleValidationFailure.HIDDEN_TEST_RESULTS_INCOMPLETE)
                    }
                    if (assignments.isNotEmpty() &&
                        requirementResults.isNotEmpty() &&
                        requirementResults.values.all {
                            it.status == RequirementResultStatus.NOT_APPLICABLE
                        }
                    ) {
                        add(GraderBundleValidationFailure.ALL_REQUIREMENTS_NOT_APPLICABLE)
                    }
                    if (normalizationTrace.isEmpty()) {
                        add(GraderBundleValidationFailure.NORMALIZATION_TRACE_MISSING)
                    }
                    if (rationaleHashes.isEmpty()) {
                        add(GraderBundleValidationFailure.RATIONALE_HASHES_MISSING)
                    }
                }
                val status = when {
                    validationFailures.isNotEmpty() -> GraderScoreBundleStatus.INVALID
                    abstained -> GraderScoreBundleStatus.ABSTAINED
                    else -> GraderScoreBundleStatus.VALID
                }
                val bundle = GraderScoreBundle(
                    bundleId = AtlasHashing.hashText(
                        "grader-score-bundle|$runId|$roundIndex|$graderGroupId|$targetGroupId"
                    ),
                    runId = runId,
                    round = roundIndex,
                    graderGroupId = graderGroupId,
                    targetGroupId = target,
                    graderAtlasHash = freeze.graderAtlasHash,
                    criteriaFreezeHash = freeze.freezeHash,
                    targetSnapshotHash = snapshotHash,
                    nodeScores = nodeScores,
                    groupScores = groupScores,
                    requirementResults = requirementResults,
                    rationaleHashes = rationaleHashes,
                    normalizationTrace = normalizationTrace,
                    status = status,
                    validationFailures = validationFailures
                ).withComputedHash()
                repository.appendScoreBundle(
                    bundle = bundle,
                    requiredNodeIds = assignments.mapTo(linkedSetOf()) { it.packet.nodeId },
                    requiredDimensionIds = requiredDimensionIds,
                    requiredRequirementIds = requirements.mapTo(linkedSetOf()) {
                        it.requirementId
                    }
                )
            }.onFailure { error ->
                persistenceFailures += "$targetGroupId: ${error.message}"
                Napier.w(
                    "GraderRunner: exact score bundle failed closed for grader=$graderGroupId " +
                        "target=$targetGroupId round=$roundIndex: ${error.message}"
                )
            }
        }
        check(persistenceFailures.isEmpty()) {
            "Exact grader bundle persistence failed: ${persistenceFailures.joinToString()}"
        }
    }

    /**
     * Runs the identity-redacted novelty assessment phase in each isolated grader database.
     *
     * Missing, duplicate, malformed, or failed outputs are not synthesized. They remain absent so
     * the exact novelty resolver can defer or fail closed.
     *
     * @param runId Current run identifier.
     * @param graderGroupIds Exact active independent grader groups.
     * @param roundIndex Source round shared by all proposals.
     * @param runObjective Receiver objective used for relevance evaluation.
     * @param proposals Screened proposal authority, with author/group identity redacted by prompt.
     * @param onProgress Optional progress callback.
     * @return Exact assessments successfully persisted in grader-local databases.
     */
    suspend fun runNoveltyAssessmentPhase(
        runId: String,
        graderGroupIds: Set<String>,
        roundIndex: Int,
        runObjective: String,
        proposals: List<NoveltyVectorProposal>,
        onProgress: ((String) -> Unit)? = null
    ): List<NoveltyGraderAssessment> {
        require(graderGroupIds.isNotEmpty()) { "Novelty assessment requires grader groups" }
        require(
            proposals.map(NoveltyVectorProposal::proposalHash).toSet().size == proposals.size &&
                proposals.all {
                    it.runId == runId && it.round == roundIndex && it.hasValidHash()
                }
        ) {
            "Novelty assessment requires unique exact proposals for the source round"
        }
        val persisted = mutableListOf<NoveltyGraderAssessment>()
        for (graderGroupId in graderGroupIds.sorted()) {
            val graderDb = substrateFactory.getGrader(runId, graderGroupId)
            val repository = OutlierNoveltyRepository(graderDb)
            for (proposal in proposals.sortedBy(NoveltyVectorProposal::proposalHash)) {
                onProgress?.invoke(
                    "[$graderGroupId] assessing novelty ${proposal.proposalHash.take(12)}"
                )
                val submitted = mutableListOf<NoveltyGraderAssessment>()
                val factory = GraderAgentFactory(
                    openRouterApiKey = openRouterApiKey,
                    llmBackend = llmBackend,
                    graderDb = graderDb,
                    graderBudgetConfig = graderBudgetConfigFor(graderGroupId),
                    graderGroupId = graderGroupId,
                    costLedgerRepository = costLedgerRepositoryProvider(runId),
                    toolsMountPath = graderToolsPath(runId, graderGroupId),
                    onScoreSubmitted = { _, _ -> },
                    onNodeGradeSubmitted = {}
                )
                val context = factory.createNoveltyAssessor(
                    proposal = proposal,
                    roundIndex = roundIndex,
                    runObjective = runObjective,
                    modelOverride = modelOverrideFor(Role.GraderCritic),
                    onAssessmentSubmitted = submitted::add
                )
                val output = runCatching {
                    withTimeout(graderAgentTimeoutMs) {
                        localAgentRunner(context)
                    }
                }.getOrElse { error ->
                    Napier.w(
                        "Novelty assessor failed closed for grader=$graderGroupId " +
                            "proposal=${proposal.proposalHash}: ${error.message}"
                    )
                    continue
                }
                if (context.textOnlyCompatibilityMode && submitted.isEmpty()) {
                    parseTextOnlyNoveltyAssessment(
                        output = output,
                        proposalHash = proposal.proposalHash,
                        graderGroupId = graderGroupId
                    )?.let(submitted::add)
                }
                val exact = submitted.singleOrNull()?.takeIf {
                    it.proposalHash == proposal.proposalHash &&
                        it.graderGroupId == graderGroupId &&
                        it.hasValidHash()
                }
                if (exact == null) {
                    Napier.w(
                        "Novelty assessment cardinality failed closed for grader=$graderGroupId " +
                            "proposal=${proposal.proposalHash}; outputs=${submitted.size}"
                    )
                    continue
                }
                runCatching { repository.appendAssessment(exact) }
                    .onSuccess(persisted::add)
                    .onFailure { error ->
                        Napier.w(
                            "Novelty assessment persistence failed closed for " +
                                "grader=$graderGroupId proposal=${proposal.proposalHash}: " +
                                error.message
                        )
                    }
            }
        }
        return persisted
    }

    private suspend fun typedGraderLegacyContext(
        runId: String,
        graderGroupId: String,
        currentRound: Int
    ): String? {
        val latestFitness = graderAuthorityRepository
            .fitnessHistory(runId, graderGroupId)
            .filter {
                it.round < currentRound &&
                    it.plane ==
                    com.achulkov.evomolior.core.grader.GraderFitnessPlane.REQUIREMENT_DISCOVERY
            }
            .maxByOrNull { it.round }
            ?: return null
        val eligible = graderAuthorityRepository.requirementsForRound(
            runId,
            latestFitness.round,
            graderGroupId
        ).filter { it.requirementHash in latestFitness.eligibleRequirementHashes }
        if (eligible.isEmpty()) return null
        return buildString {
            appendLine(
                "Typed competitive grader Legacy from round ${latestFitness.round}; " +
                    "fitness=${latestFitness.aggregateScore}."
            )
            eligible.sortedBy { it.requirementId.value }.forEach { requirement ->
                appendLine(
                    "- ${requirement.criterion} | threshold=${requirement.passThreshold} | " +
                        "rationale=${requirement.rationale}"
                )
            }
        }.trim()
    }

    private suspend fun runRoundJudgeWithRetry(
        factory: GraderAgentFactory,
        runId: String,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        criteriaContext: String,
        targetGroupId: String,
        targetSnapshot: String,
        collectedScores: MutableMap<String, MutableList<DimensionScore>>,
        nodeEvaluationContexts: List<ExactNodeEvaluationContext>,
        modelOverride: String?,
        foundingBrief: String?,
        graderGroupId: String,
        onProgress: ((String) -> Unit)?
    ): String {
        val outputs = mutableListOf<String>()
        var attempt = 1
        var dimensionsToScore = scoringDimensions

        while (attempt <= 2 && dimensionsToScore.isNotEmpty()) {
            val retryContext = if (attempt == 1) {
                criteriaContext
            } else {
                buildString {
                    appendLine(criteriaContext)
                    appendLine()
                    appendLine("=== RETRY REQUIRED: MISSING GRADING SCORES ===")
                    appendLine("A previous RoundJudge attempt completed without submitting all required scores.")
                    appendLine("Score only these missing dimensions for group `$targetGroupId`:")
                    appendLine(dimensionsToScore.joinToString(", ") { it.id })
                    appendLine(
                        "Call submit_grading_score once for each listed dimension, or return only the strict " +
                            "JSON score object required by the text-only fallback contract."
                    )
                }
            }

            val judgeContext = factory.createRoundJudge(
                runId = runId,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = dimensionsToScore,
                criteriaContext = retryContext,
                allGroupSnapshots = mapOf(targetGroupId to targetSnapshot),
                nodeEvaluationContexts = if (attempt == 1) {
                    nodeEvaluationContexts
                } else {
                    emptyList()
                },
                modelOverride = modelOverride,
                foundingBrief = foundingBrief
            )

            val judgeOutput = try {
                withProgressHeartbeat(
                    onProgress = onProgress,
                    heartbeatMessage = "[$graderGroupId] RoundJudge still scoring round $roundIndex — $targetGroupId"
                ) {
                    withTimeout(graderAgentTimeoutMs) {
                        localAgentRunner(judgeContext)
                    }
                }
            } catch (e: Exception) {
                Napier.w(
                    "GraderRunner: RoundJudge failed for $graderGroupId round=$roundIndex " +
                        "target=$targetGroupId attempt=$attempt: ${e.message}"
                )
                ""
            }
            outputs += judgeOutput
            collectTextOnlyScores(
                output = judgeOutput,
                targetGroupId = targetGroupId,
                dimensions = dimensionsToScore,
                collectedScores = collectedScores
            )

            val collectedDimensionIds = collectedScores[targetGroupId]
                ?.map { it.dimensionId }
                ?.toSet()
                .orEmpty()
            dimensionsToScore = scoringDimensions.filterNot { it.id in collectedDimensionIds }
            if (dimensionsToScore.isNotEmpty() && attempt == 1) {
                Napier.w(
                    "GraderRunner.judge [$graderGroupId] missing scores for $targetGroupId " +
                        "dimensions=${dimensionsToScore.joinToString(", ") { it.id }}; retrying once"
                )
            }
            attempt += 1
        }

        if (dimensionsToScore.isNotEmpty()) {
            Napier.w(
                "GraderRunner.judge [$graderGroupId] interactive attempts omitted scores for " +
                    "$targetGroupId dimensions=${dimensionsToScore.joinToString(", ") { it.id }}; " +
                    "continuing to bounded structured repair"
            )
        }

        return outputs.filter { it.isNotBlank() }.joinToString(" | ")
    }

    private data class GraderJudgingOutput(
        val graderGroupId: String,
        val narrative: String,
        val nodeGradeResponses: List<NodeGraderResponse>
    )

    internal fun aggregateNodeGradeBundles(
        assignments: List<NodeGradingAssignment>,
        expectedGraderIds: Set<String>,
        responses: List<NodeGraderResponse>
    ): List<NodeGradeBundle> = assignments
        .sortedBy(NodeGradingAssignment::assignmentId)
        .map { assignment ->
            val assignedResponses = responses
                .filter { it.assignmentId == assignment.assignmentId }
                .sortedWith(compareBy(NodeGraderResponse::graderId, NodeGraderResponse::responseHash))
            val responsesByGrader = assignedResponses.groupBy(NodeGraderResponse::graderId)
            val authorityInvalid = !assignment.hasValidHash() ||
                assignment.requiredQuorum != expectedGraderIds.size ||
                responsesByGrader.keys.any { it !in expectedGraderIds } ||
                responsesByGrader.values.any { it.size != 1 } ||
                assignedResponses.any {
                    !it.hasValidHash() ||
                        it.assignmentHash != assignment.assignmentHash
                }
            val abstained = assignedResponses.any {
                it.verdict == NodeGraderResponseVerdict.ABSTAINED
            }
            val explicitlyInvalid = assignedResponses.any {
                it.verdict == NodeGraderResponseVerdict.INVALID
            }
            val validResponses = assignedResponses.filter {
                it.verdict == NodeGraderResponseVerdict.VALID &&
                    it.graderId in expectedGraderIds &&
                    it.scoreVector != null
            }
            val complete = responsesByGrader.keys == expectedGraderIds &&
                validResponses.size == assignment.requiredQuorum
            val vector = if (!authorityInvalid && !abstained && !explicitlyInvalid && complete) {
                averageVectors(validResponses.map { requireNotNull(it.scoreVector) })
            } else {
                null
            }
            val normalizedScore = vector?.let(assignment.scoreWeights::normalizedScore)
            val verdict = when {
                authorityInvalid -> NodeGradeBundleVerdict.MALFORMED
                abstained -> NodeGradeBundleVerdict.ABSTAINED
                explicitlyInvalid -> NodeGradeBundleVerdict.INVALID
                !complete -> NodeGradeBundleVerdict.INCOMPLETE_QUORUM
                requireNotNull(vector).hardFailure ||
                    requireNotNull(normalizedScore) < assignment.capturePolicy.minimumGrade ->
                    NodeGradeBundleVerdict.VALID_FAIL
                else -> NodeGradeBundleVerdict.VALID_PASS
            }
            val packet = assignment.packet
            val completedResponses = assignedResponses.filter {
                it.graderId in expectedGraderIds
            }
            val bundleId = AtlasHashing.hashText(
                listOf("node-grade-bundle", assignment.assignmentHash, packet.round.toString())
                    .joinToString("|")
            )
            NodeGradeBundle(
                bundleId = bundleId,
                runId = packet.runId,
                round = packet.round,
                groupId = packet.groupId,
                nodeId = packet.nodeId,
                atlasVersion = packet.atlasVersion,
                atlasHash = packet.atlasHash,
                nodePayloadHash = packet.nodePayloadHash,
                evidencePacketId = packet.packetId,
                evidencePacketHash = packet.packetHash,
                criteriaHash = assignment.criteriaHash,
                capturePolicyHash = assignment.capturePolicyHash,
                scoreVector = vector,
                normalizedScore = normalizedScore,
                verdict = verdict,
                requiredQuorum = assignment.requiredQuorum,
                graderIds = expectedGraderIds,
                completedGraderIds = completedResponses
                    .mapTo(linkedSetOf(), NodeGraderResponse::graderId),
                graderResponseHashes = completedResponses
                    .mapTo(linkedSetOf(), NodeGraderResponse::responseHash)
            ).withComputedHash()
        }

    private fun averageVectors(vectors: List<NodeScoreVector>): NodeScoreVector {
        require(vectors.isNotEmpty()) { "Cannot aggregate an empty node-grade response set" }
        fun average(selector: (NodeScoreVector) -> Double): Double =
            vectors.sumOf(selector) / vectors.size
        return NodeScoreVector(
            correctness = average(NodeScoreVector::correctness),
            evidenceQuality = average(NodeScoreVector::evidenceQuality),
            reproducibility = average(NodeScoreVector::reproducibility),
            robustness = average(NodeScoreVector::robustness),
            integrationUtility = average(NodeScoreVector::integrationUtility),
            receiverRelevance = average(NodeScoreVector::receiverRelevance),
            novelty = average(NodeScoreVector::novelty),
            efficiency = average(NodeScoreVector::efficiency),
            hardFailure = vectors.any(NodeScoreVector::hardFailure),
            confidence = average(NodeScoreVector::confidence)
        )
    }

    private fun collectTextOnlyScores(
        output: String,
        targetGroupId: String,
        dimensions: List<ScoringDimension>,
        collectedScores: MutableMap<String, MutableList<DimensionScore>>
    ) {
        if (output.isBlank() || dimensions.isEmpty()) return
        val allowedDimensions = dimensions.map { it.id }.toSet()
        val parsedScores = parseTextOnlyGradingScores(output, setOf(targetGroupId), allowedDimensions)
        if (parsedScores.isEmpty()) return

        val existingDimensionIds = collectedScores[targetGroupId]
            ?.map { it.dimensionId }
            ?.toMutableSet()
            ?: mutableSetOf()
        val accepted = parsedScores.filter { score -> existingDimensionIds.add(score.dimensionId) }
        if (accepted.isEmpty()) return

        collectedScores.getOrPut(targetGroupId) { mutableListOf() }.addAll(accepted)
        val provenance = if (factoryUsesTextOnlyCompatibilityMode()) {
            "accepted ${accepted.size} direct text-only score(s)"
        } else {
            "recovered ${accepted.size} strict JSON fallback score(s)"
        }
        Napier.i(
            "GraderRunner.judge $provenance for $targetGroupId " +
                "dimensions=${accepted.joinToString(", ") { it.dimensionId }}"
        )
    }

    internal fun parseTextOnlyGradingScores(
        output: String,
        allowedGroupIds: Set<String>,
        allowedDimensionIds: Set<String>
    ): List<DimensionScore> {
        if (output.isBlank()) return emptyList()
        return parseJsonGradingScores(output, allowedGroupIds, allowedDimensionIds)
    }

    internal fun parseTextOnlyNoveltyAssessment(
        output: String,
        proposalHash: String,
        graderGroupId: String
    ): NoveltyGraderAssessment? {
        val decoded = runCatching {
            graderScoreJson.decodeFromString(
                RecoveredNoveltyAssessment.serializer(),
                output.trim()
            )
        }.getOrNull() ?: return null
        val status = runCatching {
            NoveltyAssessmentStatus.valueOf(decoded.status)
        }.getOrNull() ?: return null
        val scores = listOf(
            decoded.novelty,
            decoded.receiverRelevance,
            decoded.testability,
            decoded.plausibility,
            decoded.expectedImpact,
            decoded.costDiscipline
        )
        val vector = when {
            status == NoveltyAssessmentStatus.VALID && scores.all { it != null } ->
                runCatching {
                    NoveltyGraderVector(
                        novelty = requireNotNull(decoded.novelty),
                        receiverRelevance = requireNotNull(decoded.receiverRelevance),
                        testability = requireNotNull(decoded.testability),
                        plausibility = requireNotNull(decoded.plausibility),
                        expectedImpact = requireNotNull(decoded.expectedImpact),
                        costDiscipline = requireNotNull(decoded.costDiscipline)
                    )
                }.getOrNull() ?: return null
            status != NoveltyAssessmentStatus.VALID && scores.all { it == null } -> null
            else -> return null
        }
        val rationale = decoded.rationale.trim().takeIf(String::isNotBlank) ?: return null
        return NoveltyGraderAssessment(
            assessmentId = AtlasHashing.hashText("$proposalHash|$graderGroupId"),
            proposalHash = proposalHash,
            graderGroupId = graderGroupId,
            status = status,
            vector = vector,
            rationaleHash = AtlasHashing.hashText(rationale)
        ).withComputedHash()
    }

    private fun parseJsonGradingScores(
        output: String,
        allowedGroupIds: Set<String>,
        allowedDimensionIds: Set<String>
    ): List<DimensionScore> {
        val payload = output.trim()
        val decodedScores = runCatching {
            graderScoreJson.decodeFromString(RecoveredGradingScorePayload.serializer(), payload).scores
        }.getOrElse { return emptyList() }

        val expectedCoverage = allowedGroupIds.flatMap { groupId ->
            allowedDimensionIds.map { dimensionId -> groupId to dimensionId }
        }.toSet()
        val actualCoverage = decodedScores.map { it.targetGroupId to it.dimensionId }
        // A text-only judgment is authoritative only as one complete, duplicate-free score matrix.
        if (
            actualCoverage.size != expectedCoverage.size ||
            actualCoverage.toSet() != expectedCoverage
        ) {
            return emptyList()
        }

        val parsedScores = decodedScores.mapNotNull { score ->
            buildParsedTextOnlyScore(
                groupId = score.targetGroupId,
                dimensionId = score.dimensionId,
                rawScore = score.rawScore,
                rationaleText = score.rationale,
                allowedGroupIds = allowedGroupIds,
                allowedDimensionIds = allowedDimensionIds
            )
        }
        return parsedScores.takeIf { it.size == expectedCoverage.size }.orEmpty()
    }

    @Serializable
    private data class RecoveredGradingScorePayload(
        val scores: List<RecoveredGradingScore>
    )

    @Serializable
    private data class RecoveredGradingScore(
        val targetGroupId: String,
        val dimensionId: String,
        val rawScore: Double,
        val rationale: String
    )

    @Serializable
    private data class RecoveredNoveltyAssessment(
        val status: String,
        val novelty: Double? = null,
        val receiverRelevance: Double? = null,
        val testability: Double? = null,
        val plausibility: Double? = null,
        val expectedImpact: Double? = null,
        val costDiscipline: Double? = null,
        val rationale: String
    )

    private fun buildParsedTextOnlyScore(
        groupId: String,
        dimensionId: String,
        rawScore: Double,
        rationaleText: String,
        allowedGroupIds: Set<String>,
        allowedDimensionIds: Set<String>
    ): DimensionScore? {
        if (groupId !in allowedGroupIds || dimensionId !in allowedDimensionIds) return null
        if (!rawScore.isFinite() || rawScore !in 0.0..100.0) return null
        val rationale = rationaleText.trim().takeIf { it.isNotBlank() } ?: return null
        return DimensionScore(
            dimensionId = dimensionId,
            rawScore = rawScore,
            justification = "Text-only RoundJudge score for $groupId: $rationale"
        )
    }

    private fun deduplicateScoresByDimension(
        dimensions: List<ScoringDimension>,
        scores: List<DimensionScore>
    ): List<DimensionScore> {
        val latestByDimension = scores.associateBy { it.dimensionId }
        return dimensions.mapNotNull { latestByDimension[it.id] }
    }

    internal fun buildRoundJudgePrompt(
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        criteriaContext: String,
        allGroupSnapshots: Map<String, String>,
        roundIndex: Int,
        nodeEvaluationContexts: List<ExactNodeEvaluationContext> = emptyList()
    ): String = RolePromptBuilder.buildRoundJudge(
        runObjective = runObjective,
        dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        },
        criteriaContext = criteriaContext,
        allGroupSnapshots = allGroupSnapshots,
        nodeEvaluationContexts = nodeEvaluationContexts,
        roundIndex = roundIndex
    )

    internal suspend fun readFailureTelemetryContext(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String
    ): String? {
        val telemetryEntries = try {
            graderDb.stagingDao().getAll(runId, graderGroupId)
                .filter { it.type == "FAILURE_TELEMETRY" && (it.status == "promoted" || it.status == "pending") }
                .sortedBy { it.submittedAtMs }
                .takeLast(10)
        } catch (e: Exception) {
            Napier.w("GraderRunner: failed to read failure telemetry for $graderGroupId: ${e.message}")
            emptyList()
        }

        if (telemetryEntries.isEmpty()) return null

        return telemetryEntries.joinToString("\n\n") { entry ->
            "[Failure telemetry]\n${entry.content}"
        }
    }

    internal suspend fun readGraderSeedContext(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String
    ): String? {
        val entries = try {
            graderDb.knowledgeBaseDao().search(
                runId = runId,
                groupId = graderGroupId,
                query = "grader",
                limit = CRITERIA_SEED_MAX_KB_DOCS
            ).filter { it.domain == "grader_seed" || it.domain == "evaluation" }
        } catch (e: Exception) {
            Napier.w("GraderRunner: failed to read grader seed context for $graderGroupId: ${e.message}")
            emptyList()
        }

        if (entries.isEmpty()) return null

        return entries.joinToString("\n\n") { entry ->
            "[Seed: ${entry.domain}]\n${entry.content.compactText(CRITERIA_SEED_MAX_KB_CHARS)}"
        }
    }

    internal suspend fun readGraderKnowledgeContext(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        maxGeneration: Int
    ): String? {
        if (maxGeneration <= 0) return null

        val kbEntries = runCatching {
            graderDb.knowledgeBaseDao()
                .listActiveOrganized(runId, graderGroupId, GRADER_KNOWLEDGE_MAX_KB_DOCS * 2)
                .asSequence()
                .filter { !it.isDeadEnd && it.generation <= maxGeneration }
                .filterNot { it.domain == "grader_seed" }
                .take(GRADER_KNOWLEDGE_MAX_KB_DOCS)
                .toList()
        }.getOrElse { error ->
            Napier.w("GraderRunner: failed to read compounded grader KB for $graderGroupId: ${error.message}")
            emptyList()
        }
        val kgFacts = runCatching {
            graderDb.knowledgeGraphFactDao()
                .queryByTriplePattern(
                    runId = runId,
                    groupId = graderGroupId,
                    subjectPattern = "%",
                    relationPattern = "%",
                    objectPattern = "%",
                    limit = GRADER_KNOWLEDGE_MAX_KG_FACTS * 2
                )
                .asSequence()
                .filter { it.generation <= maxGeneration }
                .take(GRADER_KNOWLEDGE_MAX_KG_FACTS)
                .toList()
        }.getOrElse { error ->
            Napier.w("GraderRunner: failed to read compounded grader KG for $graderGroupId: ${error.message}")
            emptyList()
        }

        if (kbEntries.isEmpty() && kgFacts.isEmpty()) return null

        return buildString {
            if (kbEntries.isNotEmpty()) {
                appendLine("Prior validated grader KB:")
                kbEntries.forEach { entry ->
                    appendLine(
                        "- [${entry.id}; generation=${entry.generation}; domain=${entry.domain}] " +
                            entry.content.compactText(GRADER_KNOWLEDGE_MAX_ENTRY_CHARS)
                    )
                }
            }
            if (kgFacts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Prior validated grader KG:")
                kgFacts.forEach { fact ->
                    appendLine(
                        "- [${fact.id}; generation=${fact.generation}] " +
                            "${fact.subject} --${fact.relation}--> ${fact.objectNode}"
                                .compactText(GRADER_KNOWLEDGE_MAX_ENTRY_CHARS)
                    )
                }
            }
        }.trim()
    }

    private fun buildJudgeCriteriaContext(
        graderGroupId: String,
        roundIndex: Int,
        failureTelemetryContext: String?,
        criteriaEntries: List<StagingSubmissionEntity>,
        compoundedKnowledgeContext: String? = null
    ): String {
        val latestPerRole = criteriaEntries
            .groupBy { it.role }
            .values
            .mapNotNull { entries -> entries.maxByOrNull { it.submittedAtMs } }
            .sortedBy { it.submittedAtMs }

        return buildString {
            compoundedKnowledgeContext?.let {
                appendLine("=== GRADER COMPOUNDED SUBSTRATE ===")
                appendLine(it)
                appendLine()
            }
            failureTelemetryContext?.let {
                appendLine("=== RECENT FAILURE TELEMETRY ===")
                appendLine(it)
                appendLine()
            }

            if (latestPerRole.isEmpty()) {
                Napier.w(
                    "GraderRunner: no criteria found for $graderGroupId round=$roundIndex — " +
                        "judge will use dimension descriptions only"
                )
                append("(no criteria developed — evaluate based on dimension descriptions and your own judgment)")
                return@buildString
            }

            appendLine("=== COMPACT CRITERIA DIGEST (${latestPerRole.size} entries) ===")
            latestPerRole.forEach { entry ->
                appendLine(formatCriteriaEntry(entry))
                appendLine()
            }

            val omittedCount = criteriaEntries.size - latestPerRole.size
            if (omittedCount > 0) {
                appendLine("... $omittedCount older criteria entries omitted")
            }
        }
    }

    private fun graderBudgetConfigFor(graderGroupId: String): BudgetConfig {
        val scaledProfile = GRADER_BUDGET_CONFIG.profile.copy(
            spawnBudget = (GRADER_BUDGET_CONFIG.profile.spawnBudget * graderBudgetMultiplier).coerceAtLeast(1.0)
        )
        return GRADER_BUDGET_CONFIG.copy(
            groupId = graderGroupId,
            profile = scaledProfile
        )
    }

    private fun formatCriteriaEntry(entry: StagingSubmissionEntity): String = buildString {
        appendLine("[Criteria by ${entry.role}]")
        appendLine("Type: ${entry.type} | Status: ${entry.status} | Generation: ${entry.generation}")
        appendLine(entry.content.compactText(JUDGE_MAX_CRITERIA_ENTRY_CHARS))
    }

    private fun latestAuthoredMethodology(
        criteriaEntries: List<StagingSubmissionEntity>,
        roundIndex: Int
    ): GraderMethodologyDraft? =
        criteriaEntries
            .asSequence()
            .filter {
                it.status == "promoted" &&
                    it.generation == roundIndex + 1 &&
                    it.budgetSignature.startsWith(
                        GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
                    )
            }
            .sortedByDescending { it.submittedAtMs }
            .mapNotNull { entry ->
                runCatching {
                    graderScoreJson.decodeFromString<GraderMethodologyDraft>(entry.content)
                }.onFailure { error ->
                    Napier.w("GraderRunner: invalid promoted methodology draft ${entry.id}: ${error.message}")
                }.getOrNull()
            }
            .firstOrNull()

    private suspend fun hasTypedMethodologyDraft(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        roundIndex: Int
    ): Boolean = graderDb.stagingDao().getAll(runId, graderGroupId)
        .asSequence()
        .filter {
            it.type == "PLAN_UPDATE" &&
                it.generation == roundIndex + 1 &&
                it.status in setOf("pending", "promoted") &&
                it.budgetSignature.startsWith(
                    GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
                )
        }
        .any {
            runCatching {
                graderScoreJson.decodeFromString<GraderMethodologyDraft>(it.content)
            }.getOrNull()?.requirements?.isNotEmpty() == true
        }

    private suspend fun latestRejectedMethodologyFeedback(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        roundIndex: Int
    ): String? = graderDb.stagingDao().getAll(runId, graderGroupId)
        .asSequence()
        .filter {
            it.type == "PLAN_UPDATE" &&
                it.status == "rejected" &&
                it.generation == roundIndex + 1 &&
                it.budgetSignature.startsWith(
                    GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
                ) &&
                !it.rejectionReason.isNullOrBlank()
        }
        .maxWithOrNull(
            compareBy<StagingSubmissionEntity>(
                { it.reviewedAtMs ?: Long.MIN_VALUE },
                StagingSubmissionEntity::submittedAtMs
            )
        )
        ?.rejectionReason
        ?.let { "Prior Critic verdict: $it" }

    private fun GraderMethodologyDraft.toGradingSheet(
        runId: String,
        graderGroupId: String,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>
    ): GradingSheet {
        val dimensionsById = scoringDimensions.associateBy { it.id }
        return GradingSheet(
            sheetId = "grader-authored-$graderGroupId-r${roundIndex + 1}",
            runId = runId,
            roundIndex = roundIndex,
            graderGroupId = graderGroupId,
            rubricDimensions = rubricDimensions.map { rubric ->
                val dimension = requireNotNull(dimensionsById[rubric.dimensionId]) {
                    "Unknown grader-authored dimension ${rubric.dimensionId}"
                }
                RubricDimension(
                    dimensionId = rubric.dimensionId,
                    name = dimension.name,
                    weight = dimension.weight,
                    scoringGuidance = rubric.scoringGuidance,
                    maxScore = dimension.maxScore
                )
            },
            automatedTests = automatedTests.map { test ->
                AutomatedTest(
                    testId = test.testId,
                    description = test.description,
                    expectedOutputPattern = test.expectedOutputPattern,
                    penaltyOnFailure = test.penaltyOnFailure,
                    isMandatory = test.isMandatory
                )
            },
            failureModes = failureModes.map { mode ->
                FailureMode(mode.modeId, mode.description, mode.penaltyWeight)
            },
            comparativePairs = emptyList(),
            sealedAt = System.currentTimeMillis()
        )
    }

    private fun GraderMethodologyDraft.assess(snapshot: String): AuthoredTestAssessment {
        val outcomes = automatedTests.map { test ->
            val passed = SafeRegexMatcher.containsMatch(test.expectedOutputPattern, snapshot)
            AuthoredTestOutcome(
                testId = test.testId,
                description = test.description,
                passed = passed,
                mandatory = test.isMandatory,
                penalty = if (passed) 0.0 else test.penaltyOnFailure
            )
        }
        return AuthoredTestAssessment(outcomes)
    }

    private suspend fun ensureCriteriaPersisted(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        roundIndex: Int,
        role: Role,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        failureTelemetryContext: String?,
        seedContext: String?,
        agentOutput: String,
        agentId: String,
        textOnlyCompatibilityMode: Boolean,
        existingCriteriaIds: Set<String>,
        wipTaskRef: String? = null
    ) {
        val currentCriteriaEntries = graderDb.stagingDao().getAll(runId, graderGroupId)
            .filter {
                it.type == "PLAN_UPDATE" &&
                    it.role == role.name &&
                    it.generation == roundIndex + 1
            }

        if (currentCriteriaEntries.any { it.id !in existingCriteriaIds }) {
            return
        }

        val recoveredCriteria = recoverCriteriaDocument(
            role = role,
            runObjective = runObjective,
            scoringDimensions = scoringDimensions,
            failureTelemetryContext = failureTelemetryContext,
            seedContext = seedContext,
            agentOutput = agentOutput,
            textOnlyCompatibilityMode = textOnlyCompatibilityMode
        )
        if (recoveredCriteria.kind == "synthetic" && hasDeterministicCriteriaSeed(
                graderDb = graderDb,
                runId = runId,
                graderGroupId = graderGroupId,
                roundIndex = roundIndex
            )
        ) {
            Napier.w(
                "GraderRunner: preserved deterministic criteria seed for $graderGroupId " +
                    "round=$roundIndex role=${role.name} because no role-authored PLAN_UPDATE was staged"
            )
            return
        }

        val now = System.currentTimeMillis()
        val criteriaSubmission = StagingSubmissionEntity(
            id = buildString {
                append("${recoveredCriteria.idPrefix}-${role.name.lowercase()}-$graderGroupId-r${roundIndex + 1}")
                wipTaskRef?.takeIf { it.isNotBlank() }?.let { append("-${it.substringAfterLast('-')}") }
            },
            agentId = if (recoveredCriteria.direct) agentId else "system-${role.name.lowercase()}",
            role = role.name,
            type = "PLAN_UPDATE",
            content = recoveredCriteria.content,
            wipTaskRef = wipTaskRef,
            budgetSignature = if (recoveredCriteria.direct) {
                val signaturePrefix = if (textOnlyCompatibilityMode) {
                    DIRECT_TEXT_ONLY_CRITERIA_SIGNATURE_PREFIX
                } else {
                    DIRECT_AGENT_CRITERIA_SIGNATURE_PREFIX
                }
                "$signaturePrefix:$agentId:$now"
            } else {
                "system:grader-criteria-fallback"
            },
            status = "promoted",
            generation = roundIndex + 1,
            groupId = graderGroupId,
            runId = runId,
            submittedAtMs = now,
            reviewedAtMs = now,
            initialTrustScore = recoveredCriteria.initialTrustScore
        )
        graderDb.stagingDao().insert(criteriaSubmission)
        val logMessage = "GraderRunner: persisted ${recoveredCriteria.kind} criteria for $graderGroupId " +
            "round=$roundIndex role=${role.name} because no PLAN_UPDATE was staged"
        if (recoveredCriteria.direct) Napier.i(logMessage) else Napier.w(logMessage)
    }

    private suspend fun ensureDeterministicCriteriaSeedPersisted(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        failureTelemetryContext: String?,
        seedContext: String?
    ) {
        if (hasDeterministicCriteriaSeed(graderDb, runId, graderGroupId, roundIndex)) {
            return
        }

        val now = System.currentTimeMillis()
        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "criteria-seed-$graderGroupId-r${roundIndex + 1}",
                agentId = "system-criteria-seed",
                role = DETERMINISTIC_CRITERIA_SEED_ROLE,
                type = "PLAN_UPDATE",
                content = buildDeterministicCriteriaSeedDocument(
                    runObjective = runObjective,
                    scoringDimensions = scoringDimensions,
                    failureTelemetryContext = failureTelemetryContext,
                    seedContext = seedContext
                ),
                budgetSignature = "system:grader-criteria-seed",
                status = "promoted",
                generation = roundIndex + 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = now,
                reviewedAtMs = now,
                initialTrustScore = 0.62
            )
        )
        Napier.i(
            "GraderRunner: persisted deterministic criteria seed for $graderGroupId round=$roundIndex"
        )
    }

    private suspend fun hasDeterministicCriteriaSeed(
        graderDb: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        graderGroupId: String,
        roundIndex: Int
    ): Boolean = graderDb.stagingDao().getAll(runId, graderGroupId).any {
        it.type == "PLAN_UPDATE" &&
            it.role == DETERMINISTIC_CRITERIA_SEED_ROLE &&
            it.generation == roundIndex + 1
    }

    private data class RecoveredCriteriaDocument(
        val kind: String,
        val idPrefix: String,
        val initialTrustScore: Double,
        val content: String,
        val direct: Boolean = false
    )

    private fun recoverCriteriaDocument(
        role: Role,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        failureTelemetryContext: String?,
        seedContext: String?,
        agentOutput: String,
        textOnlyCompatibilityMode: Boolean
    ): RecoveredCriteriaDocument {
        val recoveredOutput = agentOutput.trim()
        return if (looksLikeSubstantiveCriteria(recoveredOutput, scoringDimensions)) {
            RecoveredCriteriaDocument(
                kind = if (textOnlyCompatibilityMode) "direct-text-only" else "direct-agent-output",
                idPrefix = "direct",
                initialTrustScore = 0.7,
                content = recoveredOutput.take(RECOVERED_CRITERIA_MAX_CHARS),
                direct = true
            )
        } else {
            RecoveredCriteriaDocument(
                kind = "synthetic",
                idPrefix = "synthetic",
                initialTrustScore = 0.45,
                content = synthesizeCriteriaDocument(
                    role = role,
                    runObjective = runObjective,
                    scoringDimensions = scoringDimensions,
                    failureTelemetryContext = failureTelemetryContext,
                    seedContext = seedContext,
                    agentOutput = recoveredOutput
                )
            )
        }
    }

    private fun factoryUsesTextOnlyCompatibilityMode(): Boolean =
        LlmBackendToolSupportPolicy.resolve(llmBackend).textOnlyCompatibilityMode

    private fun looksLikeSubstantiveCriteria(
        agentOutput: String,
        scoringDimensions: List<ScoringDimension>
    ): Boolean {
        if (agentOutput.length < 180) return false
        val lower = agentOutput.lowercase()
        if (listOf("let me ", "i will ", "i'll ", "now i ").any { lower.startsWith(it) }) return false

        val dimensionHits = scoringDimensions.count { dimension ->
            lower.contains(dimension.id.lowercase()) || lower.contains(dimension.name.lowercase())
        }
        val rubricHits = listOf(
            "low",
            "medium",
            "high",
            "threshold",
            "indicator",
            "anti-pattern",
            "failure mode",
            "tie-breaker",
            "hard-to-fake",
            "criteria",
            "rubric"
        ).count { lower.contains(it) }

        return dimensionHits >= scoringDimensions.size.coerceAtMost(2) && rubricHits >= 3
    }

    private fun synthesizeCriteriaDocument(
        role: Role,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        failureTelemetryContext: String?,
        seedContext: String?,
        agentOutput: String
    ): String = buildString {
        appendLine("SYNTHETIC GRADER CRITERIA FALLBACK")
        appendLine("Role: ${role.name}")
        appendLine("Objective: $runObjective")
        seedContext?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Founding brief:")
            appendLine(it.take(1_000))
        }
        failureTelemetryContext?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Recent failure telemetry:")
            appendLine(it.take(1_000))
        }
        appendLine()
        scoringDimensions.forEach { dimension ->
            appendLine("Dimension: ${dimension.id} — ${dimension.name}")
            appendLine("Weight: ${String.format(java.util.Locale.ROOT, "%.2f", dimension.weight)}")
            appendLine("Description: ${dimension.description}")
            appendLine("LOW (0-30): sparse, generic, unsupported, or contradictory evidence.")
            appendLine("MEDIUM (30-70): some relevant evidence exists, but coverage or rigor is partial.")
            appendLine("HIGH (70-100): strong, specific, cross-supported evidence and resilient execution.")
            appendLine("EXCELLENT / EXCEEDS EXPECTATIONS (90-100): complete, auditable, uncertainty-aware deliverable that goes beyond minimum instructions with robust edge-case handling, reproducibility notes, and clear failure boundaries.")
            appendLine("Observable indicators:")
            appendLine("- cites concrete artefacts, KB entries, or KG facts rather than vague claims")
            appendLine("- demonstrates reasoning linked to the run objective and this dimension")
            appendLine("- shows consistency between proposed approach and produced evidence")
            appendLine("- expands the evaluation surface beyond minimum compliance to cover correctness, completeness, evidence fidelity, robustness, edge cases, usability, reproducibility, uncertainty handling, and graceful failure behavior")
            appendLine("Anti-patterns:")
            appendLine("- polished wording without evidentiary support")
            appendLine("- repetitive or inflated content volume masquerading as depth")
            appendLine("- contradictions, missing edge cases, or weak traceability")
            appendLine("Hard-to-fake signal:")
            appendLine("- artifacts, facts, and rationale mutually reinforce each other under scrutiny")
            appendLine("Tie-breaker:")
            appendLine("- prefer the group with more falsifiable, reproducible, and directly evidenced work")
            if (requiresSystematicReviewCriteria(runObjective)) {
                appendLine("Systematic-review candidate-table checks:")
                appendLine("- every candidate ID visible in the task corpus is covered by an include/exclude decision")
                appendLine("- every included/excluded decision has criterion-specific evidence from the allowed artifact")
                appendLine("- evidence_table.csv covers the same candidate universe as the decision tables")
                appendLine("- hard negatives are rejected for PICO/design/intervention/comparator reasons, not vague relevance")
                appendLine("- pooled conclusion follows only from included studies and flags missing/ambiguous outcome evidence")
            }
            appendLine()
        }
        if (agentOutput.isNotBlank()) {
            appendLine("Recovered agent output:")
            appendLine(agentOutput.take(1_500))
        }
    }

    private fun buildDeterministicCriteriaSeedDocument(
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        failureTelemetryContext: String?,
        seedContext: String?
    ): String = buildString {
        appendLine("DETERMINISTIC GRADER CRITERIA SEED")
        appendLine("Objective: $runObjective")
        appendLine(
            "Purpose: provide an auditable, non-generic baseline rubric before grader agents refine " +
                "it. Role-authored PLAN_UPDATE entries should expand or override this seed."
        )
        seedContext?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Founding brief:")
            appendLine(it.take(1_000))
        }
        failureTelemetryContext?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Recent failure telemetry:")
            appendLine(it.take(1_000))
        }
        appendLine()
        scoringDimensions.forEach { dimension ->
            appendLine("Dimension: ${dimension.id} — ${dimension.name}")
            appendLine("Weight: ${String.format(java.util.Locale.ROOT, "%.2f", dimension.weight)}")
            appendLine("Description: ${dimension.description}")
            appendLine("LOW (0-30): missing, generic, unsupported, contradictory, or not traceable to durable artifacts.")
            appendLine("MEDIUM (30-70): relevant work exists but coverage, evidence fidelity, robustness, or reproducibility is partial.")
            appendLine("HIGH (70-90): complete, specific, evidence-backed execution with clear traceability and few material gaps.")
            appendLine("EXCELLENT / EXCEEDS EXPECTATIONS (90-100): complete, auditable, uncertainty-aware work that goes beyond minimum instructions with robust edge-case handling, reproducibility notes, and explicit failure boundaries.")
            appendLine("Core indicators:")
            appendLine("- objective-specific correctness rather than surface fluency")
            appendLine("- concrete artifacts, KB entries, KG facts, or submitted documents back each claim")
            appendLine("- completeness across required entities, tables, decisions, and deliverable sections")
            appendLine("- evidence fidelity, contradiction handling, uncertainty handling, and reproducibility")
            appendLine("- graceful failure behavior and transparent limitations when evidence is insufficient")
            appendLine("Anti-patterns:")
            appendLine("- polished narrative without durable evidence")
            appendLine("- missing edge cases, omitted required entities, or duplicated unsupported assertions")
            appendLine("- claims that conflict with uploaded artifacts or substrate evidence")
            appendLine("Tie-breaker:")
            appendLine("- prefer the group with more falsifiable, reproducible, and directly evidenced work.")
            if (requiresSystematicReviewCriteria(runObjective)) {
                appendLine("Systematic-review candidate-table checks:")
                appendLine("- every candidate ID visible in the allowed corpus is covered by an include/exclude decision")
                appendLine("- included and excluded tables are non-empty when the candidate universe requires them")
                appendLine("- every decision cites candidate-specific PICO/design/outcome evidence from the allowed artifact")
                appendLine("- hard negatives are rejected using criterion-specific reasons rather than vague relevance")
                appendLine("- evidence and synthesis tables reconcile with the decision tables")
                appendLine("- pooled or directional conclusions follow only from included studies and flag ambiguity")
            }
            appendLine()
        }
    }

    private fun requiresSystematicReviewCriteria(runObjective: String): Boolean {
        val lower = runObjective.lowercase()
        return listOf(
            "systematic review",
            "systematic-review",
            "included_studies.csv",
            "excluded_hard_negatives.csv",
            "evidence_table.csv",
            "candidate"
        ).any { lower.contains(it) }
    }

    private fun String.compactText(maxChars: Int): String = if (length <= maxChars) this
    else take(maxChars) + "...[truncated]"

    private suspend fun <T> withProgressHeartbeat(
        onProgress: ((String) -> Unit)?,
        heartbeatMessage: String,
        block: suspend () -> T
    ): T = coroutineScope {
        val heartbeatJob = onProgress?.let { progress ->
            launch {
                while (isActive) {
                    delay(30_000L)
                    if (isActive) {
                        progress(heartbeatMessage)
                    }
                }
            }
        }

        try {
            block()
        } finally {
            heartbeatJob?.cancel()
        }
    }

    // ── Legacy 3-agent pipeline (kept for backward compatibility) ────────────────────────────

    /**
     * Runs the 3-agent sequential evaluation pipeline for one target group.
     *
     * Pipeline: GraderResearcher → GraderBuilder → GraderCritic
     * Each stage receives the prior stage's output as previousFindings.
     */
    private suspend fun evaluateGroup(
        factory: GraderAgentFactory,
        runId: String,
        roundIndex: Int,
        targetGroupId: String,
        graderGroupId: String,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>
    ) {
        Napier.d("GraderRunner: evaluating $targetGroupId by $graderGroupId round=$roundIndex")

        // Export the competing group's substrate as a read-only text snapshot
        val competingDb = substrateFactory.get(runId, targetGroupId)
        val snapshot = CanonSnapshotExporter.exportForJudge(
            competingDb,
            runId,
            targetGroupId,
            roundIndex
        )

        var previousFindings: String? = null

        for (role in listOf(Role.GraderResearcher, Role.GraderBuilder, Role.GraderCritic)) {
            Napier.d("GraderRunner: spawning $role for $targetGroupId")

            val context = factory.create(
                role = role,
                runId = runId,
                roundIndex = roundIndex,
                targetGroupId = targetGroupId,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                canonSnapshot = snapshot,
                modelOverride = modelOverrideFor(role),
                previousFindings = previousFindings
            )

            val agentOutput = try {
                withProgressHeartbeat(
                    onProgress = null,
                    heartbeatMessage = "[$graderGroupId] $role still scoring target=$targetGroupId round=$roundIndex"
                ) {
                    withTimeout(graderAgentTimeoutMs) {
                        localAgentRunner(context)
                    }
                }
            } catch (e: Exception) {
                Napier.w(
                    "GraderRunner: $role agent timed out or failed for $targetGroupId: ${e.message}"
                )
                "[$role terminated without output: ${e.message?.take(200)}]"
            }

            // Pass this agent's output as "previous findings" to the next agent
            previousFindings = buildString {
                appendLine("=== ${role.name} FINDINGS ===")
                appendLine(agentOutput.take(3_000))
            }

            Napier.d(
                "GraderRunner: $role completed for $targetGroupId " +
                "budget_remaining=${
                    String.format(java.util.Locale.ROOT, "%.1f", context.budgetLedger.balance)
                }"
            )
        }
    }

    companion object {
        private val GRADER_ROLES = setOf(
            Role.GraderResearcher,
            Role.GraderBuilder,
            Role.GraderCritic
        )
        private const val TYPED_METHODOLOGY_REPAIR_ATTEMPTS = 2
        private const val STRUCTURED_DIMENSION_SCORE_ATTEMPTS = 2
        private const val STRUCTURED_NODE_GRADE_ATTEMPTS = 2

        /**
         * Default budget profile for grader agents.
         *
         * Grader agents use the Library profile (read-heavy) but with a modest spawnBudget
         * since their primary work is reading + scoring, not writing.
         */
        private val GRADER_BUDGET_CONFIG = BudgetConfig(
            groupId = "grader",
            profile = GroupProfile(
                profileType = GroupProfileType.Library,
                webFetchMultiplier = 0.5,
                toolExecMultiplier = 2.0,
                interAgentMessageMultiplier = 1.0,
                writeStagingMultiplier = 1.0,
                llmCallMultiplier = 1.0,
                spawnBudget = 40.0,
                maxFleetSize = 3,
                kgEmphasis = 0.5,
                kbEmphasis = 0.5
            )
        )
    }
}
