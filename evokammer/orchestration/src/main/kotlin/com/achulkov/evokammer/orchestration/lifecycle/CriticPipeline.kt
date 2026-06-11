package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.agents.factory.CriticReviewAgent
import com.achulkov.evokammer.agents.factory.KgCriticArbitrationAgent
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.DeliverableArtifactType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.core.contract.CriticReview
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import com.achulkov.evokammer.core.embedding.EmbeddingEngine
import com.achulkov.evokammer.orchestration.kg.KGConsistencyChecker as KGConflictConsistencyChecker
import com.achulkov.evokammer.orchestration.kg.ConsistencyResult as KGConflictConsistencyResult
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.KgSchemaConstraintEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evokammer.substrate.tools.ToolMaterializer
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

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
 * @param openRouterApiKey OpenRouter API key.
 * @param criticModel The model to use for Critic review.
 * @param kgSchema JSON-serialised KG schema from `StartingField`, used by `KGConsistencyChecker`
 *   to validate `KG_FACT` submissions before sending them to the LLM Critic.
 * @param criticReviewRunner Optional deterministic reviewer hook used by tests and other
 *   non-OpenRouter runtimes. When present, it receives the generated prompt and must return
 *   the final review verdict.
 */
class CriticPipeline(
    private val db: EvoKammerDatabase,
    private val runId: String,
    private val groupId: String,
    private val generation: Int,
    private val openRouterApiKey: String,
    private val criticModel: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    private val criticAgentId: String? = null,
    private val kgSchema: String = "{}",
    private val criticReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String?,
        criticId: String
    ) -> CriticReview)? = null,
    private val toolsMountHostPath: String,
    private val onWipTaskCompleted: (suspend (groupId: String, nodeId: String, agentId: String, generation: Int) -> Unit)? = null,
    private val deliverableTreeStore: DeliverableSkeletonTreeStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val toolMaterializer = ToolMaterializer(File(toolsMountHostPath))
    private val criticReviewAgent = CriticReviewAgent(
        openRouterApiKey = openRouterApiKey,
        criticModelId = criticModel,
        deterministicReviewRunner = criticReviewRunner
    )
    private val kgCriticArbitrationAgent = KgCriticArbitrationAgent(
        openRouterApiKey = openRouterApiKey,
        criticModelId = criticModel,
        deterministicReviewRunner = criticReviewRunner
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

        for (submission in pending) {
            try {
                // Death Notes are always promoted (no Critic review)
                if (submission.type == "DEATH_NOTE") {
                    db.stagingDao().updateReview(
                        id = submission.id,
                        status = "promoted",
                        criticId = "system",
                        trustScore = 0.8,
                        reason = null,
                        reviewedAtMs = System.currentTimeMillis()
                    )
                    val deadEndRef = promoteStagingToSubstrate(submission.id, 0.8)
                    if (deadEndRef != null) {
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
                            // Mark the linked task as completed since a dead-end documents the failure.
                            db.wipTaskDao().completeTask(
                                nodeId = wipRef,
                                runId = runId,
                                groupId = groupId,
                                agentId = submission.agentId,
                                generation = submission.generation
                            )
                        }
                    }
                    promoted++
                    continue
                }

                // A-1: Self-submission guard — an agent cannot act as both submitter and reviewer.
                if (submission.agentId == reviewerAgentId) {
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
                    applyReview(submission, resolvedReview)
                    arbitrated++
                    if (resolvedReview.outcome == CriticOutcome.PROMOTE) promoted++ else rejected++
                } else {
                    applyReview(submission, firstReview)
                    if (firstReview.outcome == CriticOutcome.PROMOTE) promoted++ else rejected++
                }

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

            if (disputeContext == null) {
                when (
                    val conflictResult = KGConflictConsistencyChecker(
                        knowledgeGraphFactDao = db.knowledgeGraphFactDao()
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
    ) {
        // DISPUTE is resolved to PROMOTE or REJECT before reaching here via resolveDispute().
        val newStatus = when (review.outcome) {
            CriticOutcome.PROMOTE -> "promoted"
            CriticOutcome.REJECT -> "rejected"
            CriticOutcome.DISPUTE -> "rejected" // safety fallback; should not occur in normal flow
        }

        db.stagingDao().updateReview(
            id = submission.id,
            status = newStatus,
            criticId = review.criticAgentId,
            trustScore = review.initialTrustScore,
            reason = review.rejectionReason ?: review.disputeReason,
            reviewedAtMs = System.currentTimeMillis()
        )

        if (review.outcome == CriticOutcome.PROMOTE) {
            val promoted = promoteStagingToSubstrate(submission.id, review.initialTrustScore ?: 0.7)
            if (promoted != null) {
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
                    val completed = db.wipTaskDao().completeTask(
                        nodeId = wipTaskRef,
                        runId = runId,
                        groupId = groupId,
                        agentId = submission.agentId,
                        generation = submission.generation
                    )
                    if (completed > 0) {
                        onWipTaskCompleted?.invoke(groupId, wipTaskRef, submission.agentId, submission.generation)
                        Napier.i { "[$groupId] WiP task $wipTaskRef completed by ${submission.agentId} at generation ${submission.generation}" }
                    } else {
                        Napier.w { "[$groupId] WiP task $wipTaskRef could not be marked completed for ${submission.agentId}" }
                    }
                }
            }
        }

        emitEvent(
            when (review.outcome) {
                CriticOutcome.PROMOTE -> EventTypes.STAGING_PROMOTE
                CriticOutcome.REJECT -> EventTypes.STAGING_REJECT
                CriticOutcome.DISPUTE -> EventTypes.STAGING_DISPUTE
            },
            """{"submissionId":"${submission.id}","outcome":"${review.outcome.name}","agentId":"${submission.agentId}"}"""
        )
    }

    private data class PromotedArtifact(
        val ref: String,
        val artifactType: DeliverableArtifactType
    )

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
     * as written by [com.achulkov.evokammer.agents.tools.EmitFactTool].
     *
     * @param submission The staging submission to promote.
     * @param trustScore The trust score assigned by the Critic.
     */
    private suspend fun promoteKgFactSubmission(
        submission: StagingSubmissionEntity,
        trustScore: Double
    ): String? {
        try {
            val subject = extractField(submission.content, "SUBJECT")
            val relation = extractField(submission.content, "RELATION")
            val obj = extractField(submission.content, "OBJECT")
            val confidence = extractField(submission.content, "CONFIDENCE")?.toDoubleOrNull() ?: 0.7
            val provenance = extractField(submission.content, "PROVENANCE") ?: ""

            if (subject == null || relation == null || obj == null) {
                Napier.w { "[$groupId] KG_FACT ${submission.id} missing required fields — cannot promote to KG" }
                return null
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
            append("\"hardConstraints\":").append(jsonArrayLiteral(hardConstraints)).append(',')
            append("\"softConstraints\":").append(jsonArrayLiteral(softConstraints))
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
        content.lines()
            .firstOrNull { it.startsWith("$fieldName:") }
            ?.removePrefix("$fieldName:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

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
