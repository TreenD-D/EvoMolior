@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.agents.factory.CriticReviewAgent
import com.achulkov.evomolior.agents.factory.ProviderCallCostRecorder
import com.achulkov.evomolior.agents.factory.KgCriticArbitrationAgent
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.core.embedding.EmbeddingEngine
import com.achulkov.evomolior.core.kg.KgFactAttemptAuthority
import com.achulkov.evomolior.core.kg.KgFactLineParser
import com.achulkov.evomolior.core.kg.KgRelationResolver
import com.achulkov.evomolior.orchestration.kg.KGConsistencyChecker as KGConflictConsistencyChecker
import com.achulkov.evomolior.orchestration.kg.ConsistencyResult as KGConflictConsistencyResult
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KGSchemaEntity
import com.achulkov.evomolior.substrate.db.entity.KgSchemaConstraintEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.kg.KgFactAttemptRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.time.Instant

/** Exact decision controlling whether a promoted artifact may close its linked WIP node. */
data class WipCompletionAuthorityDecision(
    val authorized: Boolean,
    val reason: String? = null
) {
    companion object {
        /** Generic canonical promotion may complete its ordinary WIP claim. */
        val AUTHORIZED = WipCompletionAuthorityDecision(authorized = true)

        /** Creates a fail-closed decision with a bounded diagnostic. */
        fun denied(reason: String): WipCompletionAuthorityDecision =
            WipCompletionAuthorityDecision(
                authorized = false,
                reason = reason.take(500)
            )
    }
}

/**
 * Runs the Critic review pipeline for staged submissions.
 *
 * The pipeline:
 * 1. Reads pending submissions from staging
 * 2. Applies pre-review guards: self-submission, wip_task_reference, budget_signature
 * 3. Delegates the review call to a dedicated CriticReviewAgent wrapper
 * 4. On PROMOTE: writes to canonical substrate
 * 5. On REJECT: records rejection reason
 * 6. On DISPUTE: submits for second review; double-promote wins, double-reject loses
 * 7. Death Notes are auto-promoted (skip review)
 *
 * @param db The group's Room database.
 * @param runId The run identifier.
 * @param groupId The group identifier.
 * @param generation The current generation number.
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param llmBackend Backend used for Critic model resolution and prompt execution.
 * @param criticModel The model to use for Critic review.
 * @param kgSchema JSON-serialised KG schema from `StartingField`, used by `KGConsistencyChecker`
 *   to validate `KG_FACT` submissions before sending them to the LLM Critic.
 * @param criticReviewRunner Optional deterministic reviewer hook used by tests and other
 *   non-OpenRouter runtimes. When present, it receives the generated prompt and must return
 *   the final review verdict.
 */
class CriticPipeline(
    private val db: EvoMoliorDatabase,
    private val runId: String,
    private val groupId: String,
    private val generation: Int,
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val criticModel: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    private val criticAgentId: String? = null,
    private val criticAuthorBlocklist: Set<String> = emptySet(),
    private val kgSchema: String = "{}",
    private val criticReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String?,
        criticId: String
    ) -> CriticReview)? = null,
    private val toolsMountHostPath: String,
    private val onWipTaskCompleted: (suspend (groupId: String, resourceNodeId: String, agentId: String, generation: Int) -> Unit)? = null,
    private val onSubmissionPromoted: (suspend (submission: StagingSubmissionEntity, trustScore: Double) -> Unit)? = null,
    private val deliverableTreeStore: DeliverableSkeletonTreeStore? = null,
    private val toolPromotionService: ToolPromotionService? = null,
    private val wipCompletionAuthorizer: suspend (
        submission: StagingSubmissionEntity,
        promotedArtifactRef: String
    ) -> WipCompletionAuthorityDecision = { _, _ ->
        WipCompletionAuthorityDecision.AUTHORIZED
    },
    private val legacyDirectToolPromotionEnabled: Boolean = true,
    private val costLedgerRepository: CostLedgerRepository = CostLedgerRepository(db)
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val toolMaterializer = ToolMaterializer(File(toolsMountHostPath))
    private val toolSubmissionValidator = ToolSubmissionValidator(
        runId = runId,
        groupId = groupId,
        toolsMountHostPath = toolsMountHostPath,
        json = json
    )
    private val contributionGraphRepository = ContributionGraphRepository(db)
    private val kgFactAttemptRepository = KgFactAttemptRepository(db)
    private val criticReviewAgent = CriticReviewAgent(
        openRouterApiKey = openRouterApiKey,
        llmBackend = llmBackend,
        criticModelId = criticModel,
        deterministicReviewRunner = criticReviewRunner,
        providerCostRecorder = { criticId ->
            ProviderCallCostRecorder(
                repository = costLedgerRepository,
                runId = runId,
                groupId = groupId,
                agentId = criticId
            )
        }
    )
    private val kgCriticArbitrationAgent = KgCriticArbitrationAgent(
        openRouterApiKey = openRouterApiKey,
        llmBackend = llmBackend,
        criticModelId = criticModel,
        deterministicReviewRunner = criticReviewRunner,
        providerCostRecorder = { criticId ->
            ProviderCallCostRecorder(
                repository = costLedgerRepository,
                runId = runId,
                groupId = groupId,
                agentId = criticId
            )
        }
    )

    /**
     * Processes all pending submissions in the staging area for the current group.
     *
     * Should be called by the LifecycleManager after each generation boundary.
     *
     * DISPUTE handling: when a submission receives a DISPUTE verdict from the first Critic,
     * it is immediately re-reviewed a second time with the dispute reason as additional context.
     * - Both PROMOTE → promoted (trust score averaged)
     * - Both REJECT → rejected
     * - Split (PROMOTE + REJECT) → promoted at reduced trust (0.5)
     *
     * @return A [CriticSummary] with per-outcome counts.
     */
    suspend fun processPendingSubmissions(): CriticSummary {
        val pending = db.stagingDao().getPending(runId, groupId)
        var promoted = 0
        var rejected = 0
        var arbitrated = 0  // submissions that went through the second-pass dispute path

        // A-1: generate a single critic ID for the entire round so self-submissions can be detected.
        // The runtime can also supply a stable criticAgentId; either way, matching submissions
        // are rejected before any review prompt is sent.
        val roundCriticId = "critic-${UUID.randomUUID().toString().take(8)}"
        val reviewerAgentId = criticAgentId ?: roundCriticId
        val blockedCriticAuthors = criticAuthorBlocklist + reviewerAgentId

        for (submission in pending) {
            try {
                if (submission.type == "KG_FACT") {
                    recordKgFactAttempt(submission)
                }

                // Death Notes are always promoted (no Critic review)
                if (submission.type == "DEATH_NOTE") {
                    val reviewedAtMs = System.currentTimeMillis()
                    db.stagingDao().updateReview(
                        id = submission.id,
                        status = "promoted",
                        criticId = "system",
                        trustScore = 0.8,
                        reason = null,
                        reviewedAtMs = reviewedAtMs
                    )
                    val deadEndRef = promoteStagingToSubstrate(submission.id, 0.8)
                    if (deadEndRef != null) {
                        val artifactHash = AtlasHashing.hashText(submission.content)
                        check(
                            db.stagingDao().recordPromotionArtifact(
                                id = submission.id,
                                artifactRef = deadEndRef.ref,
                                artifactHash = artifactHash
                            ) == 1
                        ) {
                            "System promotion artifact authority was not recorded for ${submission.id}"
                        }
                        recordCriticDecision(
                            submission = submission,
                            review = CriticReview(
                                submissionId = submission.id,
                                outcome = CriticOutcome.PROMOTE,
                                criticAgentId = "system",
                                initialTrustScore = 0.8
                            ),
                            promoted = deadEndRef,
                            decidedAtMs = reviewedAtMs,
                            structuredDeadEnd = true
                        )
                        // Record dead-end reference on the linked DST node, if any.
                        val wipRef = submission.wipTaskRef?.takeIf { it.isNotBlank() }
                        if (wipRef != null) {
                            deliverableTreeStore?.read(runId, groupId)?.let { tree ->
                                val node = tree.nodes.firstOrNull { it.nodeId == wipRef }
                                if (node != null) {
                                    val updatedNode = node.copy(
                                        deadEndRefs = (node.deadEndRefs + deadEndRef.ref).distinct().take(20),
                                        updatedAtGeneration = submission.generation
                                    )
                                    deliverableTreeStore?.write(tree.replaceNode(updatedNode))
                                }
                            }
                        }
                    }
                    promoted++
                    continue
                }

                // A-1: Self-submission guard — an agent cannot act as both submitter and reviewer.
                if (submission.agentId in blockedCriticAuthors) {
                    Napier.w { "[$groupId] Self-submission violation: ${submission.agentId} tried to submit and review own content" }
                    applyReview(
                        submission,
                        CriticReview(
                            submissionId = submission.id,
                            outcome = CriticOutcome.REJECT,
                            criticAgentId = reviewerAgentId,
                            rejectionReason = "self_submission_violation: Critic cannot review its own staged submissions."
                        )
                    )
                    rejected++
                    continue
                }

                val firstReview = reviewSubmission(submission, criticId = reviewerAgentId)

                if (firstReview.outcome == CriticOutcome.DISPUTE) {
                    // Second-pass: re-review with dispute context injected
                    val secondReview = reviewSubmission(
                        submission,
                        disputeContext = firstReview.disputeReason,
                        criticId = reviewerAgentId
                    )
                    val resolvedReview = resolveDispute(firstReview, secondReview)
                    val actualOutcome = applyReview(submission, resolvedReview)
                    arbitrated++
                    if (actualOutcome == CriticOutcome.PROMOTE) promoted++ else rejected++
                } else {
                    val actualOutcome = applyReview(submission, firstReview)
                    if (actualOutcome == CriticOutcome.PROMOTE) promoted++ else rejected++
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e(e) { "[$groupId] Critic review failed for submission ${submission.id}" }
                rejected++ // count parse/network failures as rejected
            }
        }

        Napier.i("[$groupId] CriticPipeline gen=$generation: promoted=$promoted rejected=$rejected arbitrated=$arbitrated")
        return CriticSummary(promoted = promoted, rejected = rejected, arbitrated = arbitrated)
    }

    /**
     * Resolves a dispute by comparing two reviews.
     *
     * - PROMOTE + PROMOTE → PROMOTE (average trust)
     * - REJECT  + REJECT  → REJECT
     * - PROMOTE + REJECT (or vice-versa) → PROMOTE at reduced trust (0.5)
     */
    private fun resolveDispute(first: CriticReview, second: CriticReview): CriticReview {
        val bothPromote = first.outcome == CriticOutcome.PROMOTE && second.outcome == CriticOutcome.PROMOTE
        val bothReject  = first.outcome == CriticOutcome.REJECT  && second.outcome == CriticOutcome.REJECT
        return when {
            bothPromote -> {
                val avgTrust = listOfNotNull(first.initialTrustScore, second.initialTrustScore).average()
                    .takeIf { !it.isNaN() } ?: 0.7
                first.copy(outcome = CriticOutcome.PROMOTE, initialTrustScore = avgTrust)
            }
            bothReject ->
                first.copy(outcome = CriticOutcome.REJECT,
                    rejectionReason = "Arbitration: both Critics agreed to reject. ${first.rejectionReason ?: ""}")
            second.outcome == CriticOutcome.DISPUTE ->
                first.copy(
                    outcome = CriticOutcome.REJECT,
                    rejectionReason = "Arbitration remained unresolved after the second review: ${
                        second.disputeReason ?: first.disputeReason ?: "no reason provided"
                    }"
                )
            else ->
                // Split decision: promote at reduced trust
                first.copy(outcome = CriticOutcome.PROMOTE, initialTrustScore = 0.5,
                    rejectionReason = null)
        }
    }

    private suspend fun reviewSubmission(
        submission: StagingSubmissionEntity,
        disputeContext: String? = null,
        criticId: String = "critic-${UUID.randomUUID().toString().take(8)}"
    ): CriticReview {
        // A-5: WIP task reference required for KB docs, tools, and KG facts (spec §4).
        // Agents must be working on a declared task to produce canonical substrate contributions.
        val requiresWipRef = submission.type in listOf("DOC", "TOOL", "KG_FACT")
        if (requiresWipRef && submission.wipTaskRef.isNullOrBlank()) {
            Napier.d { "[$groupId] Auto-reject ${submission.id} (${submission.type}): missing wip_task_reference" }
            return CriticReview(
                submissionId = submission.id,
                outcome = CriticOutcome.REJECT,
                criticAgentId = criticId,
                rejectionReason = "missing_wip_task_reference: KB_DOC, TOOL, and KG_FACT submissions must reference an active WIP task."
            )
        }

        // A-6: Budget signature required on all non-death-note submissions (spec §4).
        // Verifies the submission came from an agent with a live budget allocation.
        if (submission.budgetSignature.isBlank()) {
            Napier.d { "[$groupId] Auto-reject ${submission.id} (${submission.type}): missing budget_signature" }
            return CriticReview(
                submissionId = submission.id,
                outcome = CriticOutcome.REJECT,
                criticAgentId = criticId,
                rejectionReason = "missing_budget_signature: All staging submissions require a valid budget signature token."
            )
        }

        var trustScorePenalty = 0.0

        // B-1: KG schema consistency check for KG_FACT submissions.
        // Validates relation types and hard constraints before the expensive critic call.
        // Uses the live DB schema if available (B-2 upserts KG_CONSTRAINT promotions there),
        // falling back to the startingField schema passed in via constructor.
        if (submission.type == "KG_FACT") {
            val liveSchemaJson: String = loadLiveKgSchemaJson()

            val consistencyResult = KGConsistencyChecker(liveSchemaJson).validate(submission)
            if (consistencyResult is ConsistencyResult.Invalid) {
                Napier.d { "[$groupId] KG_FACT ${submission.id} failed schema check — rejecting before LLM: ${consistencyResult.reason}" }
                return CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.REJECT,
                    criticAgentId = criticId,
                    rejectionReason = consistencyResult.reason
                )
            }
            if (consistencyResult is ConsistencyResult.ValidWithPenalty) {
                trustScorePenalty += consistencyResult.trustScoreReduction
                Napier.d {
                    "[$groupId] KG_FACT ${submission.id} matched soft schema constraint — " +
                        "trust penalty=${consistencyResult.trustScoreReduction}: ${consistencyResult.reason}"
                }
            }

            if (disputeContext == null) {
                when (
                    val conflictResult = KGConflictConsistencyChecker(
                        knowledgeGraphFactDao = db.knowledgeGraphFactDao(),
                        allowedRelations = extractRelationTypes(liveSchemaJson).toSet()
                    ).check(submission, runId, groupId)
                ) {
                    is KGConflictConsistencyResult.Reject -> {
                        Napier.d { "[$groupId] KG_FACT ${submission.id} failed conflict check — rejecting before critic: ${conflictResult.reason}" }
                        return CriticReview(
                            submissionId = submission.id,
                            outcome = CriticOutcome.REJECT,
                            criticAgentId = criticId,
                            rejectionReason = conflictResult.reason
                        )
                    }

                    is KGConflictConsistencyResult.Dispute -> {
                        val arbitrationContext = buildKgConflictArbitrationContext(conflictResult)
                        Napier.d { "[$groupId] KG_FACT ${submission.id} entered KG dispute arbitration: ${conflictResult.reason}" }
                        return CriticReview(
                            submissionId = submission.id,
                            outcome = CriticOutcome.DISPUTE,
                            criticAgentId = criticId,
                            disputeReason = arbitrationContext
                        )
                    }

                    is KGConflictConsistencyResult.PassWithPenalty -> {
                        trustScorePenalty += conflictResult.trustScoreReduction
                    }

                    KGConflictConsistencyResult.Pass -> Unit
                }
            }
        }

        if (submission.type == "TOOL") {
            when (val validation = toolSubmissionValidator.validate(submission)) {
                ToolSubmissionValidationResult.Valid -> Unit
                is ToolSubmissionValidationResult.Invalid -> {
                    Napier.d { "[$groupId] TOOL ${submission.id} failed deterministic validation before critic review: ${validation.reason}" }
                    return CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.REJECT,
                        criticAgentId = criticId,
                        rejectionReason = validation.reason
                    )
                }
            }
        }

        val responseReview = if (submission.type == "KG_FACT" && disputeContext != null) {
            kgCriticArbitrationAgent.reviewConflict(
                submission = submission,
                disputeContext = disputeContext,
                criticId = criticId
            )
        } else {
            criticReviewAgent.reviewSubmission(
                submission = submission,
                disputeContext = disputeContext,
                criticId = criticId
            )
        }

        return responseReview.adjustedForPenalty(trustScorePenalty)
    }

    private suspend fun buildKgConflictArbitrationContext(
        conflictResult: KGConflictConsistencyResult.Dispute
    ): String = buildString {
        appendLine(conflictResult.reason)
        appendLine()
        appendLine("Conflicting active fact(s):")

        conflictResult.conflictingFactIds.mapNotNull { factId ->
            db.knowledgeGraphFactDao().getById(factId)
        }.forEach { fact ->
            appendLine(
                "- factId=${fact.id} subject=${fact.subject} relation=${fact.relation} object=${fact.objectNode} " +
                    "trust=${fact.trustScore} generation=${fact.generation} status=${fact.status}"
            )
        }
    }

    private suspend fun applyReview(
        submission: StagingSubmissionEntity,
        review: CriticReview
    ): CriticOutcome {
        // DISPUTE is resolved to PROMOTE or REJECT before reaching here via resolveDispute().
        val newStatus = when (review.outcome) {
            CriticOutcome.PROMOTE -> "promoted"
            CriticOutcome.REJECT -> "rejected"
            CriticOutcome.DISPUTE -> "rejected" // safety fallback; should not occur in normal flow
        }

        val reviewedAtMs = System.currentTimeMillis()
        db.stagingDao().updateReview(
            id = submission.id,
            status = newStatus,
            criticId = review.criticAgentId,
            trustScore = review.initialTrustScore,
            reason = review.rejectionReason ?: review.disputeReason,
            reviewedAtMs = reviewedAtMs
        )

        if (review.outcome == CriticOutcome.PROMOTE) {
            val trustScore = review.initialTrustScore ?: 0.7
            val promoted = promoteStagingToSubstrate(submission.id, trustScore)
            if (promoted == null) {
                val rejectedReview = review.copy(
                    outcome = CriticOutcome.REJECT,
                    initialTrustScore = null,
                    rejectionReason =
                        "canonical_promotion_failed: reviewed content did not establish canonical authority.",
                    disputeReason = null
                )
                db.stagingDao().updateReview(
                    id = submission.id,
                    status = "rejected",
                    criticId = review.criticAgentId,
                    trustScore = review.initialTrustScore,
                    reason = rejectedReview.rejectionReason,
                    reviewedAtMs = reviewedAtMs
                )
                recordCriticDecision(
                    submission = submission,
                    review = rejectedReview,
                    promoted = null,
                    decidedAtMs = reviewedAtMs
                )
                releaseRejectedWipClaimIfSettled(submission)
                emitEvent(
                    EventTypes.STAGING_REJECT,
                    """{"submissionId":"${submission.id}","outcome":"REJECT","agentId":"${submission.agentId}"}"""
                )
                return CriticOutcome.REJECT
            }
            val completionAuthority = if (submission.isWipCompletionCandidate()) {
                wipCompletionAuthorizer(submission, promoted.ref)
            } else {
                WipCompletionAuthorityDecision.AUTHORIZED
            }
            if (submission.isWipCompletionCandidate() && completionAuthority.authorized) {
                val wipTaskRef = submission.wipTaskRef?.takeIf { it.isNotBlank() }
                if (wipTaskRef != null) {
                    deliverableTreeStore?.applyUpdate(
                        runId = runId,
                        groupId = groupId,
                        update = DeliverableSkeletonNodeUpdate(
                            dstNodeRef = wipTaskRef,
                            groupId = groupId,
                            dimensionScores = emptyMap(),
                            graderIds = listOfNotNull(review.criticAgentId),
                            rubricVersion = "critic-promotion",
                            graderNotes = listOfNotNull(review.rejectionReason, review.disputeReason).takeIf { it.isNotEmpty() } ?: emptyList(),
                            passedThreshold = true,
                            artifactRef = promoted.ref,
                            artifactType = promoted.artifactType,
                            trustScore = review.initialTrustScore ?: 0.7,
                            owningGeneration = submission.generation,
                            owningAgentId = submission.agentId
                        )
                    )
                    completePromotedWipClaim(submission, wipTaskRef)
                }
            } else if (
                submission.isWipCompletionCandidate() &&
                !completionAuthority.authorized
            ) {
                Napier.w {
                    "[$groupId] Canonical artifact ${submission.id} did not complete WIP " +
                        "${submission.wipTaskRef}: ${completionAuthority.reason.orEmpty()}"
                }
                releaseUncompletedWipClaimIfSettled(submission)
            }
            check(
                db.stagingDao().recordPromotionArtifact(
                    id = submission.id,
                    artifactRef = promoted.ref,
                    artifactHash = AtlasHashing.hashText(submission.content)
                ) == 1
            ) {
                "Critic promotion artifact authority was not recorded for ${submission.id}"
            }
            recordCriticDecision(
                submission = submission,
                review = review,
                promoted = promoted,
                decidedAtMs = reviewedAtMs
            )
            runCatching {
                onSubmissionPromoted?.invoke(submission, trustScore)
            }.onFailure { error ->
                Napier.w(error) {
                    "[$groupId] Promoted submission consumer failed for ${submission.id}"
                }
            }
        } else {
            recordCriticDecision(
                submission = submission,
                review = review.copy(
                    outcome = CriticOutcome.REJECT,
                    initialTrustScore = null,
                    disputeReason = null
                ),
                promoted = null,
                decidedAtMs = reviewedAtMs
            )
            releaseRejectedWipClaimIfSettled(submission)
        }

        emitEvent(
            when (review.outcome) {
                CriticOutcome.PROMOTE -> EventTypes.STAGING_PROMOTE
                CriticOutcome.REJECT -> EventTypes.STAGING_REJECT
                CriticOutcome.DISPUTE -> EventTypes.STAGING_DISPUTE
            },
            """{"submissionId":"${submission.id}","outcome":"${review.outcome.name}","agentId":"${submission.agentId}"}"""
        )
        return review.outcome
    }

    private suspend fun recordCriticDecision(
        submission: StagingSubmissionEntity,
        review: CriticReview,
        promoted: PromotedArtifact?,
        decidedAtMs: Long,
        structuredDeadEnd: Boolean = false
    ) {
        val contentHash = AtlasHashing.hashText(submission.content)
        val resultAuthority = promoted?.let {
            ContributionAuthorityRef(
                kind = if (structuredDeadEnd) {
                    ContributionAuthorityKind.STRUCTURED_DEAD_END
                } else {
                    ContributionAuthorityKind.CANONICAL_ARTIFACT
                },
                authorityId = it.ref,
                authorityHash = contentHash
            )
        }
        val decision = CriticDecisionRecord(
            decisionId = CouncilDeterministicIds.create(
                "critic-decision",
                runId,
                groupId,
                submission.id,
                review.criticAgentId,
                review.outcome.name,
                contentHash
            ),
            runId = runId,
            groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
            round = submission.generation,
            submissionId = submission.id,
            submissionContentHash = contentHash,
            resolvedReview = review,
            resultAuthority = resultAuthority,
            decidedAt = Instant.fromEpochMilliseconds(decidedAtMs)
        ).withComputedHash()
        contributionGraphRepository.recordCriticDecision(decision)
    }

    private data class PromotedArtifact(
        val ref: String,
        val artifactType: DeliverableArtifactType
    )

    private fun StagingSubmissionEntity.isWipCompletionCandidate(): Boolean =
        type != "DEATH_NOTE" &&
            !budgetSignature.startsWith("SUBSTRATE_CONTRACT_FALLBACK:") &&
            !budgetSignature.startsWith("NO_ACTION_FALLBACK:")

    private suspend fun completePromotedWipClaim(
        submission: StagingSubmissionEntity,
        nodeId: String
    ) {
        val task = db.wipTaskDao().getByNodeId(nodeId, runId, groupId)
            ?: return // Deliverable-only refs such as dst:root are not WiP claims.
        if (task.status == "completed" && task.claimedByAgentId == submission.agentId) {
            return
        }
        if (task.status != "claimed" || task.claimedByAgentId != submission.agentId) {
            Napier.w {
                "[$groupId] WiP task $nodeId could not be marked completed for " +
                    "${submission.agentId}: status=${task.status} owner=${task.claimedByAgentId}"
            }
            return
        }

        val completed = db.wipTaskDao().completeTask(
            nodeId = nodeId,
            runId = runId,
            groupId = groupId,
            agentId = submission.agentId,
            generation = submission.generation
        )
        if (completed > 0) {
            val resourceNodeId = task.linkedNodeId?.takeIf { it.isNotBlank() } ?: nodeId
            onWipTaskCompleted?.invoke(groupId, resourceNodeId, submission.agentId, submission.generation)
            Napier.i {
                "[$groupId] WiP task $nodeId completed by ${submission.agentId} " +
                    "at generation ${submission.generation} (resourceNode=$resourceNodeId)"
            }
            return
        }

        val current = db.wipTaskDao().getByNodeId(nodeId, runId, groupId)
        if (current?.status == "completed" && current.claimedByAgentId == submission.agentId) {
            return
        }
        Napier.w {
            "[$groupId] WiP task $nodeId could not be marked completed for " +
                "${submission.agentId}: state changed during settlement"
        }
    }

    private suspend fun releaseRejectedWipClaimIfSettled(submission: StagingSubmissionEntity) {
        releaseUncompletedWipClaimIfSettled(submission)
    }

    private suspend fun releaseUncompletedWipClaimIfSettled(
        submission: StagingSubmissionEntity
    ) {
        if (!submission.isWipCompletionCandidate()) return
        val nodeId = submission.wipTaskRef?.takeIf { it.isNotBlank() } ?: return
        val pendingCandidates = db.stagingDao().countPendingCompletionCandidates(
            runId = runId,
            groupId = groupId,
            agentId = submission.agentId,
            nodeId = nodeId
        )
        if (pendingCandidates > 0) return

        val released = db.wipTaskDao().abandonTask(
            nodeId = nodeId,
            runId = runId,
            groupId = groupId,
            agentId = submission.agentId,
            generation = submission.generation
        )
        if (released > 0) {
            Napier.i {
                "[$groupId] WiP task $nodeId released after all completion candidates from " +
                    "${submission.agentId} were rejected"
            }
        }
    }

    private suspend fun promoteStagingToSubstrate(submissionId: String, trustScore: Double): PromotedArtifact? {
        val submission = db.stagingDao().getById(submissionId) ?: return null

        return try {
            when (submission.type) {
                "DOC", "DEATH_NOTE" -> {
                    val isDeadEnd = submission.type == "DEATH_NOTE"
                    // Compute TF-IDF embedding for semantic search (SearchKBTool)
                    val kbId = UUID.randomUUID().toString()
                    val embedding = runCatching { EmbeddingEngine.embed(submission.content) }.getOrNull()
                    db.knowledgeBaseDao().insert(
                        KnowledgeBaseEntryEntity(
                            id = kbId,
                            content = submission.content,
                            embedding = embedding,
                            sourceAgentId = submission.agentId,
                            generation = submission.generation,
                            trustScore = trustScore,
                            domain = extractDomain(submission.content),
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = System.currentTimeMillis(),
                            isDeadEnd = isDeadEnd
                        )
                    )
                    PromotedArtifact(ref = "kb:$kbId", artifactType = DeliverableArtifactType.KB_DOC)
                }
                "TOOL" -> {
                    // Tool promotion is handled by a separate ToolPromotionService
                    // that parses the tool JSON and creates ToolRegistryEntryEntity
                    val toolId = promoteToolSubmission(submission, trustScore)
                    if (toolId != null) PromotedArtifact(ref = "tool:$toolId", artifactType = DeliverableArtifactType.TOOL) else null
                }
                "KG_FACT" -> {
                    // Parse and promote typed KG fact to the knowledge_graph_facts table
                    val factId = promoteKgFactSubmission(submission, trustScore)
                    if (factId != null) PromotedArtifact(ref = "kg:$factId", artifactType = DeliverableArtifactType.OTHER) else null
                }
                "KG_CONSTRAINT" -> {
                    // B-2: KG constraints are applied to the live KGSchemaDao so future KGConsistencyChecker
                    // validations (B-1) reflect agent-proposed schema extensions.
                    val constraintId = applyKgConstraintToSchema(submission)
                    if (constraintId != null) PromotedArtifact(ref = "constraint:$constraintId", artifactType = DeliverableArtifactType.OTHER) else null
                }
                else -> {
                    // PLAN_UPDATE, DISPATCH handled by respective services
                    Napier.d { "[$groupId] Non-KB submission promoted: ${submission.type} ${submission.id}" }
                    PromotedArtifact(ref = "submission:${submission.id}", artifactType = DeliverableArtifactType.OTHER)
                }
            }
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to promote staging submission ${submission.id}" }
            null
        }
    }

    /**
     * Parses a [StagingSubmissionEntity] of type KG_FACT and inserts it into the typed KG table.
     *
     * Expects content in `SUBJECT:/RELATION:/OBJECT:/CONFIDENCE:/DOMAIN:` line format
     * as written by [com.achulkov.evomolior.agents.tools.EmitFactTool].
     *
     * @param submission The staging submission to promote.
     * @param trustScore The trust score assigned by the Critic.
     */
    private suspend fun promoteKgFactSubmission(
        submission: StagingSubmissionEntity,
        trustScore: Double
    ): String? {
        try {
            val lineFields = KgFactLineParser.parse(submission.content)
            val subject = lineFields.subject
            val requestedRelation = lineFields.requestedRelation
            val obj = lineFields.objectNode
            val confidence = extractField(submission.content, "CONFIDENCE")?.toDoubleOrNull() ?: 0.7
            val provenance = extractField(submission.content, "PROVENANCE") ?: ""

            if (subject == null || requestedRelation == null || obj == null) {
                Napier.w { "[$groupId] KG_FACT ${submission.id} missing required fields — cannot promote to KG" }
                return null
            }
            val allowedRelations = extractRelationTypes(loadLiveKgSchemaJson())
            val relation = if (allowedRelations.isEmpty()) {
                requestedRelation
            } else {
                KgRelationResolver.resolve(requestedRelation, allowedRelations)
                    ?: run {
                        Napier.w {
                            "[$groupId] KG_FACT ${submission.id} relation '$requestedRelation' " +
                                "lost schema authority before promotion"
                        }
                        return null
                    }
            }

            val factId = UUID.randomUUID().toString()
            db.knowledgeGraphFactDao().insert(
                KnowledgeGraphFactEntity(
                    id = factId,
                    subject = subject,
                    relation = relation,
                    objectNode = obj,
                    confidence = confidence,
                    sourceAgentId = submission.agentId,
                    generation = submission.generation,
                    trustScore = trustScore,
                    provenanceJson = if (provenance.isNotBlank()) """["$provenance"]""" else "[]",
                    status = "active",
                    speculative = submission.speculative,
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            val conflictingFacts = db.knowledgeGraphFactDao()
                .queryByTriplePattern(runId, groupId, subject, relation, "%", 100)
                .filter { it.objectNode != obj && it.status == "active" }
            if (conflictingFacts.isNotEmpty()) {
                conflictingFacts.forEach { fact ->
                    db.knowledgeGraphFactDao().markDisputed(fact.id)
                }
                Napier.i {
                    "[$groupId] KG fact promoted: ($subject, $relation, $obj) trust=$trustScore; " +
                        "disputed ${conflictingFacts.size} conflicting active fact(s)"
                }
            } else {
                Napier.i { "[$groupId] KG fact promoted: ($subject, $relation, $obj) trust=$trustScore" }
            }
            return factId
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to promote KG_FACT submission ${submission.id}" }
            return null
        }
    }

    private suspend fun promoteToolSubmission(
        submission: StagingSubmissionEntity,
        @Suppress("UNUSED_PARAMETER") trustScore: Double
    ): String? {
        toolPromotionService?.let { service ->
            return when (
                val result = service.promote(
                    submission = submission,
                    criticAgentId = submission.criticAgentId ?: criticAgentId ?: "critic"
                )
            ) {
                is ToolPromotionResult.Promoted -> result.toolVersionId
                is ToolPromotionResult.Rejected -> {
                    Napier.w {
                        "[$groupId] Tool promotion rejected after Critic review: ${result.reason}"
                    }
                    null
                }
            }
        }
        if (!legacyDirectToolPromotionEnabled) {
            Napier.w {
                "[$groupId] Tool promotion rejected: no sealed opportunity authority is configured"
            }
            return null
        }
        try {
            val toolPayload = json.parseToJsonElement(submission.content) as? JsonObject ?: return null
            val toolName = toolPayload["name"]?.jsonPrimitive?.content ?: "unnamed_tool"
            val previousVersion = db.toolRegistryDao().getLatestVersionByName(runId, groupId, toolName)
            val nextVersion = (previousVersion?.version ?: 0) + 1
            val toolId = UUID.randomUUID().toString()
            val newTool = ToolRegistryEntryEntity(
                id = toolId,
                name = toolName,
                version = nextVersion,
                authorAgentId = submission.agentId,
                inputSchemaJson = toolPayload["inputSchema"]?.toString() ?: "{}",
                outputSchemaJson = toolPayload["outputSchema"]?.toString() ?: "{}",
                domain = toolPayload["domain"]?.jsonPrimitive?.content ?: "general",
                code = toolPayload["code"]?.jsonPrimitive?.content ?: "",
                codeLanguage = toolPayload["language"]?.jsonPrimitive?.content ?: "shell",
                generation = submission.generation,
                groupId = groupId,
                runId = runId,
                createdAtMs = System.currentTimeMillis(),
                previousVersionId = previousVersion?.id
            )
            toolMaterializer.materialize(newTool)
            if (previousVersion?.status == "active") {
                db.toolRegistryDao().update(previousVersion.copy(status = "archived"))
            }
            db.toolRegistryDao().insert(newTool)
            return toolId
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to promote tool submission ${submission.id}" }
            return null
        }
    }

    /**
     * B-2: Applies a promoted KG_CONSTRAINT submission to the live KG schema in [db].
     *
     * Supported content formats:
     * - `{"type":"hard","id":"HC-002","description":"…","expression":"…"}` — appends a hard constraint
     * - `{"type":"soft","id":"SC-003","description":"…","penaltyFactor":0.1}` — appends a soft constraint
     * - `{"relationType":"NEW_RELATION"}` — extends the relation types list
     * - `{"entityType":"NewEntity"}` — extends the entity types list
     * - Plain-text — stored as a hard constraint description
     *
     * If the schema row does not yet exist, a new row (version 1) is created.
     * Each update increments the schema version so `KGSchemaDao.get()` returns the latest.
     *
     * @param submission The `KG_CONSTRAINT` staging submission to apply.
     */
    private suspend fun applyKgConstraintToSchema(submission: StagingSubmissionEntity): String? {
        try {
            val constraintObj: JsonObject? = try {
                json.parseToJsonElement(submission.content) as? JsonObject
            } catch (_: Exception) { null }

            val now = System.currentTimeMillis()
            db.kgSchemaConstraintDao().insert(
                KgSchemaConstraintEntity(
                    id = submission.id,
                    constraintJson = submission.content,
                    source = submission.agentId,
                    runId = runId,
                    groupId = groupId,
                    createdAtMs = now
                )
            )
            val base: KGSchemaEntity = db.kgSchemaDao().get(runId, groupId)
                ?: KGSchemaEntity(
                    id = "${runId}_${groupId}_schema",
                    runId = runId,
                    groupId = groupId,
                    createdAtMs = now
                )

            val updated: KGSchemaEntity = when {
                constraintObj == null -> {
                    // Plain-text: store as hard constraint description
                    val escaped = submission.content.take(500)
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                    val newEntry = """{"id":"agent-${submission.id.take(8)}","description":"$escaped","expression":""}"""
                    base.copy(
                        hardConstraintsJson = appendToJsonArray(base.hardConstraintsJson, newEntry),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
                constraintObj["relationType"]?.jsonPrimitive?.content != null -> {
                    val rel = constraintObj["relationType"]!!.jsonPrimitive.content
                    val types = parseStringList(base.relationTypesJson)
                    if (rel in types) return null  // already present — no-op
                    base.copy(
                        relationTypesJson = stringListToJson(types + rel),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
                constraintObj["entityType"]?.jsonPrimitive?.content != null -> {
                    val et = constraintObj["entityType"]!!.jsonPrimitive.content
                    val types = parseStringList(base.entityTypesJson)
                    if (et in types) return null
                    base.copy(
                        entityTypesJson = stringListToJson(types + et),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
                constraintObj["type"]?.jsonPrimitive?.content == "soft" -> {
                    base.copy(
                        softConstraintsJson = appendToJsonArray(base.softConstraintsJson, submission.content),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
                else -> {
                    // "hard" or unrecognised type — append to hard constraints
                    base.copy(
                        hardConstraintsJson = appendToJsonArray(base.hardConstraintsJson, submission.content),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
            }

            db.kgSchemaDao().upsert(updated)
            Napier.i { "[$groupId] KG_CONSTRAINT applied to schema (v${updated.schemaVersion}): ${submission.id}" }
            return submission.id
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to apply KG_CONSTRAINT to schema: ${submission.id}" }
            return null
        }
    }

    /** Loads the live KG schema from the baseline row plus persisted KG constraint artifacts. */
    private suspend fun loadLiveKgSchemaJson(): String {
        val baseline = runCatching { db.kgSchemaDao().get(runId, groupId) }.getOrNull()
            ?: return kgSchema
        val constraints = runCatching { db.kgSchemaConstraintDao().getAll(runId, groupId) }
            .getOrDefault(emptyList())
        return buildUnifiedKgSchemaJson(baseline, constraints)
    }

    /**
     * Records the denominator authority for one attempted KG fact before unrelated review policy.
     */
    private suspend fun recordKgFactAttempt(submission: StagingSubmissionEntity) {
        kgFactAttemptRepository.bySubmission(runId, groupId, submission.id)?.let { existing ->
            require(existing.submissionContentHash == AtlasHashing.hashText(submission.content)) {
                "Persisted KG fact attempt content is immutable for ${submission.id}"
            }
            require(
                existing.agentId == submission.agentId &&
                    existing.generation == submission.generation
            ) {
                "Persisted KG fact attempt binding is immutable for ${submission.id}"
            }
            return
        }
        val liveSchemaJson = loadLiveKgSchemaJson()
        kgFactAttemptRepository.append(
            KgFactAttemptAuthority.classify(
                runId = runId,
                groupId = groupId,
                submissionId = submission.id,
                agentId = submission.agentId,
                generation = submission.generation,
                content = submission.content,
                allowedRelations = extractRelationTypes(liveSchemaJson),
                recordedAt = Instant.fromEpochMilliseconds(submission.submittedAtMs)
            )
        )
    }

    /**
     * Merges the baseline schema row with separately persisted KG constraint artifacts.
     *
     * This keeps promoted constraints auditable without changing the validation shape that
     * [KGConsistencyChecker] already understands.
     */
    private fun buildUnifiedKgSchemaJson(
        baseline: KGSchemaEntity,
        constraints: List<KgSchemaConstraintEntity>
    ): String {
        val entityTypes = parseStringList(baseline.entityTypesJson).toMutableList()
        val relationTypes = parseStringList(baseline.relationTypesJson).toMutableList()
        val hardConstraints = parseJsonObjectArray(baseline.hardConstraintsJson).toMutableList()
        val softConstraints = parseJsonObjectArray(baseline.softConstraintsJson).toMutableList()

        constraints.forEach { constraint ->
            val parsed = runCatching { json.parseToJsonElement(constraint.constraintJson) as? JsonObject }.getOrNull()
            if (parsed == null) {
                hardConstraints += normalizePlainTextConstraint(constraint)
                return@forEach
            }

            parsed["entityType"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { entityType ->
                if (entityType !in entityTypes) entityTypes += entityType
                return@forEach
            }

            parsed["relationType"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { relationType ->
                if (relationType !in relationTypes) relationTypes += relationType
                return@forEach
            }

            when (parsed["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "soft" -> softConstraints += parsed.toString()
                else -> hardConstraints += parsed.toString()
            }
        }

        return buildString {
            append('{')
            append("\"relationTypes\":").append(stringListToJson(relationTypes)).append(',')
            append("\"entityTypes\":").append(stringListToJson(entityTypes)).append(',')
            append("\"hardConstraints\":").append(jsonArrayLiteral(hardConstraints.distinct())).append(',')
            append("\"softConstraints\":").append(jsonArrayLiteral(softConstraints.distinct()))
            append('}')
        }
    }

    private fun normalizePlainTextConstraint(constraint: KgSchemaConstraintEntity): String {
        val escaped = constraint.constraintJson
            .take(500)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """{"id":"${constraint.id}","source":"${constraint.source}","description":"$escaped","expression":""}"""
    }

    private fun parseJsonObjectArray(arrayJson: String): List<String> = try {
        val arr = json.parseToJsonElement(arrayJson) as? JsonArray ?: return emptyList()
        arr.map { it.toString() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun jsonArrayLiteral(elements: List<String>): String = buildString {
        append('[')
        append(elements.joinToString(","))
        append(']')
    }

    /**
     * Appends [newEntryJson] (a single JSON object string) to a JSON array string.
     * If [arrayJson] is not a valid array, starts a fresh single-element array.
     */
    private fun appendToJsonArray(arrayJson: String, newEntryJson: String): String {
        val trimmed = arrayJson.trim()
        return if (trimmed == "[]" || trimmed.isBlank()) {
            "[$newEntryJson]"
        } else {
            // Remove trailing ']', append, re-close
            "${trimmed.dropLast(1).trimEnd().trimEnd(',')},$newEntryJson]"
        }
    }

    /** Parses a JSON array of strings into a Kotlin list. */
    private fun parseStringList(jsonArray: String): List<String> = try {
        val arr = json.parseToJsonElement(jsonArray)
        (arr as? JsonArray)?.mapNotNull {
            try { it.jsonPrimitive.content } catch (_: Exception) { null }
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun extractRelationTypes(schemaJson: String): List<String> = try {
        val schemaObject = json.parseToJsonElement(schemaJson) as? JsonObject ?: return emptyList()
        val direct = schemaObject["relationTypes"] as? JsonArray
        if (direct != null) {
            direct.mapNotNull { it.jsonPrimitive.contentOrNull }
        } else {
            schemaObject["relationTypesJson"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(::parseStringList)
                .orEmpty()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Encodes a list of strings as a JSON array string. */
    private fun stringListToJson(list: List<String>): String =
        "[${list.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }}]"

    /**
     * Extracts the DOMAIN field from structured content (e.g., written by EmitFactTool).
     * Falls back to "general" if no DOMAIN tag is present.
     *
     * @param content The submission content string.
     * @return The domain string, or "general" if absent.
     */
    private fun extractDomain(content: String): String =
        extractField(content, "DOMAIN") ?: "general"

    /**
     * Extracts a named field from content written in `FIELDNAME: value` line format.
     *
     * @param content The submission content string.
     * @param fieldName The field name to look for (case-sensitive prefix match).
     * @return The trimmed field value, or null if not found.
     */
    private fun extractField(content: String, fieldName: String): String? =
        KgFactLineParser.extract(content, fieldName)

    private fun CriticReview.adjustedForPenalty(penalty: Double): CriticReview {
        if (penalty <= 0.0 || outcome != CriticOutcome.PROMOTE) return this
        val baseTrust = initialTrustScore ?: 0.7
        return copy(initialTrustScore = (baseTrust - penalty).coerceIn(0.0, 1.0))
    }

    private suspend fun emitEvent(type: String, payload: String) {
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                eventType = type,
                groupId = groupId,
                runId = runId,
                generation = generation,
                payloadJson = payload
            )
        )
    }
}

/**
 * Summary of a [CriticPipeline.processPendingSubmissions] run.
 *
 * @param promoted  Number of submissions accepted into the canonical substrate.
 * @param rejected  Number of submissions rejected.
 * @param arbitrated Number of submissions that went through the second-pass dispute path
 *   (counted in addition to their final promoted/rejected outcome).
 */
data class CriticSummary(
    val promoted: Int,
    val rejected: Int,
    val arbitrated: Int = 0
) {
    val total: Int get() = promoted + rejected
}
