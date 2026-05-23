package com.achulkov.evokammer.orchestration.lifecycle

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.core.contract.CriticReview
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import com.achulkov.evokammer.core.embedding.EmbeddingEngine
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Runs the Critic review pipeline for staged submissions.
 *
 * The pipeline:
 * 1. Reads pending submissions from staging
 * 2. Applies pre-review guards: self-submission, wip_task_reference, budget_signature
 * 3. Uses a Koog AIAgent to evaluate each submission
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
 */
class CriticPipeline(
    private val db: EvoKammerDatabase,
    private val runId: String,
    private val groupId: String,
    private val generation: Int,
    private val openRouterApiKey: String,
    private val criticModel: String = "anthropic/claude-sonnet-4",
    private val kgSchema: String = "{}"
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Cached OpenRouter executor — created once per [CriticPipeline] instance and shared
     * across all [reviewSubmission] calls in the same round.
     *
     * Without caching, each submission review would create a fresh HTTP client, which can
     * exhaust OS socket/thread limits in large staging backlogs.
     */
    private val executor by lazy { simpleOpenRouterExecutor(openRouterApiKey) }

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
        // If a future LifecycleManager assigns a specific critic agent ID here, this check will block
        // any staging submissions made by that same agent from being self-reviewed.
        val roundCriticId = "critic-${UUID.randomUUID().toString().take(8)}"

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
                    promoteStagingToSubstrate(submission.id, 0.8)
                    promoted++
                    continue
                }

                // A-1: Self-submission guard — an agent cannot act as both submitter and reviewer.
                if (submission.agentId == roundCriticId) {
                    Napier.w { "[$groupId] Self-submission violation: ${submission.agentId} tried to submit and review own content" }
                    applyReview(
                        submission,
                        CriticReview(
                            submissionId = submission.id,
                            outcome = CriticOutcome.REJECT,
                            criticAgentId = roundCriticId,
                            rejectionReason = "self_submission_violation: Critic cannot review its own staged submissions."
                        )
                    )
                    rejected++
                    continue
                }

                val firstReview = reviewSubmission(submission, criticId = roundCriticId)

                if (firstReview.outcome == CriticOutcome.DISPUTE) {
                    // Second-pass: re-review with dispute context injected
                    val secondReview = reviewSubmission(
                        submission,
                        disputeContext = firstReview.disputeReason,
                        criticId = roundCriticId
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

        // B-1: KG schema consistency check for KG_FACT submissions.
        // Validates relation types and hard constraints before the expensive LLM call.
        // Uses the live DB schema if available (B-2 upserts KG_CONSTRAINT promotions there),
        // falling back to the startingField schema passed in via constructor.
        if (submission.type == "KG_FACT" && disputeContext == null) {
            val liveSchemaJson: String = runCatching {
                db.kgSchemaDao().get(runId, groupId)?.let { entity ->
                    // Compose a unified schema JSON from the entity's separate JSON arrays
                    """{"relationTypes":${entity.relationTypesJson},"entityTypes":${entity.entityTypesJson},"hardConstraints":${entity.hardConstraintsJson},"softConstraints":${entity.softConstraintsJson}}"""
                }
            }.getOrNull() ?: kgSchema  // fall back to startingField schema if DB query fails

            val consistencyResult = KGConsistencyChecker(liveSchemaJson).validate(submission)
            if (consistencyResult is ConsistencyResult.Invalid) {
                Napier.d { "[$groupId] KG_FACT ${submission.id} failed schema check — escalating to DISPUTE: ${consistencyResult.reason}" }
                return CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.DISPUTE,
                    criticAgentId = criticId,
                    disputeReason = consistencyResult.reason
                )
            }
        }

        val prompt = buildCriticPrompt(submission, disputeContext)

        val model: LLModel = when (criticModel) {
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-sonnet-4"   -> OpenRouterModels.Claude4Sonnet
            "anthropic/claude-haiku-4.5"  -> OpenRouterModels.Claude4_5Haiku
            "anthropic/claude-sonnet-4.5" -> OpenRouterModels.Claude4_5Sonnet
            "anthropic/claude-3-5-sonnet" -> OpenRouterModels.Claude3_5Sonnet
            "anthropic/claude-3-haiku"    -> OpenRouterModels.Claude3Haiku
            "openai/gpt-4o"               -> OpenRouterModels.GPT4o
            "openai/gpt-4o-mini"          -> OpenRouterModels.GPT4oMini
            "google/gemini-2.5-flash"     -> OpenRouterModels.Gemini2_5Flash
            else -> LLModel(
                provider = LLMProvider.OpenRouter,
                id = criticModel,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Temperature,
                    ai.koog.prompt.llm.LLMCapability.Speculation,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Completion
                )
            )
        }

        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .systemPrompt(CRITIC_SYSTEM_PROMPT)
            .toolRegistry(ToolRegistry {})
            .graphStrategy(singleRunStrategy())
            .build()

        val responseText = agent.run(prompt)

        return parseCriticResponse(responseText, criticId, submission.id)
    }

    private fun buildCriticPrompt(
        submission: StagingSubmissionEntity,
        disputeContext: String? = null
    ): String = buildString {
        append("""
        Review the following ${submission.type} submission from a ${submission.role} agent.
        
        Submission ID: ${submission.id}
        Type: ${submission.type}
        Speculative: ${submission.speculative}
        Outlier: ${submission.outlier}
        
        Content:
        ---
        ${submission.content.take(8000)}
        ${if (submission.content.length > 8000) "\n[...truncated...]" else ""}
        ---
        
        Respond with a JSON object containing:
        - "outcome": "PROMOTE", "REJECT", or "DISPUTE"
        - "initialTrustScore": number 0.0-1.0 (only if PROMOTE)
        - "reason": string (required if REJECT or DISPUTE, optional if PROMOTE)
        
        Criteria:
        - PROMOTE if the submission is accurate, useful, well-structured, and adds value to the KB
        - REJECT if it's incorrect, duplicate, low quality, incoherent, or violates norms
        - DISPUTE if quality is borderline or you need a second opinion
        
        For speculative submissions: lower bar for promotion (0.5+ is fine), but mark as speculative in reason.
        For Outlier submissions: allow unconventional approaches; only reject if factually incorrect.
        
        JSON response only:
        """.trimIndent())
        if (disputeContext != null) {
            append("\n\nNOTE — This is a SECOND REVIEW (arbitration). The first Critic disputed this submission with reason: \"$disputeContext\". Your verdict is final.")
        }
    }

    private fun parseCriticResponse(responseText: String, criticId: String, submissionId: String): CriticReview {
        return try {
            // Extract the outermost JSON object using DOT_MATCHES_ALL regex to handle nested objects
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
                .find(responseText)?.value ?: "{}"
            val obj = json.parseToJsonElement(jsonStr) as JsonObject
            val outcome = obj["outcome"]?.jsonPrimitive?.content?.let {
                CriticOutcome.valueOf(it)
            } ?: CriticOutcome.REJECT

            CriticReview(
                submissionId = submissionId,
                outcome = outcome,
                criticAgentId = criticId,
                initialTrustScore = obj["initialTrustScore"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                rejectionReason = if (outcome == CriticOutcome.REJECT) obj["reason"]?.jsonPrimitive?.content else null,
                disputeReason = if (outcome == CriticOutcome.DISPUTE) obj["reason"]?.jsonPrimitive?.content else null
            )
        } catch (e: Exception) {
            Napier.w(e) { "Failed to parse Critic response: $responseText" }
            // Default to DISPUTE on parse failure
            CriticReview(
                submissionId = submissionId,
                outcome = CriticOutcome.DISPUTE,
                criticAgentId = criticId,
                disputeReason = "Critic response parse error: ${e.message}"
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
            promoteStagingToSubstrate(submission.id, review.initialTrustScore ?: 0.7)
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

    private suspend fun promoteStagingToSubstrate(submissionId: String, trustScore: Double) {
        val submission = db.stagingDao().getById(submissionId) ?: return

        when (submission.type) {
            "DOC", "DEATH_NOTE" -> {
                val isDeadEnd = submission.type == "DEATH_NOTE"
                // Compute TF-IDF embedding for semantic search (SearchKBTool)
                val embedding = runCatching { EmbeddingEngine.embed(submission.content) }.getOrNull()
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = UUID.randomUUID().toString(),
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
            }
            "TOOL" -> {
                // Tool promotion is handled by a separate ToolPromotionService
                // that parses the tool JSON and creates ToolRegistryEntryEntity
                promoteToolSubmission(submission, trustScore)
            }
            "KG_FACT" -> {
                // Parse and promote typed KG fact to the knowledge_graph_facts table
                promoteKgFactSubmission(submission, trustScore)
            }
            "KG_CONSTRAINT" -> {
                // B-2: KG constraints are applied to the live KGSchemaDao so future KGConsistencyChecker
                // validations (B-1) reflect agent-proposed schema extensions.
                applyKgConstraintToSchema(submission)
            }
            else -> {
                // PLAN_UPDATE, DISPATCH handled by respective services
                Napier.d { "[$groupId] Non-KB submission promoted: ${submission.type} ${submission.id}" }
            }
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
    ) {
        try {
            val subject = extractField(submission.content, "SUBJECT")
            val relation = extractField(submission.content, "RELATION")
            val obj = extractField(submission.content, "OBJECT")
            val confidence = extractField(submission.content, "CONFIDENCE")?.toDoubleOrNull() ?: 0.7
            val provenance = extractField(submission.content, "PROVENANCE") ?: ""

            if (subject == null || relation == null || obj == null) {
                Napier.w { "[$groupId] KG_FACT ${submission.id} missing required fields — cannot promote to KG" }
                return
            }

            db.knowledgeGraphFactDao().insert(
                KnowledgeGraphFactEntity(
                    id = UUID.randomUUID().toString(),
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
            Napier.i { "[$groupId] KG fact promoted: ($subject, $relation, $obj) trust=$trustScore" }
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to promote KG_FACT submission ${submission.id}" }
        }
    }

    private suspend fun promoteToolSubmission(
        submission: StagingSubmissionEntity,
        @Suppress("UNUSED_PARAMETER") trustScore: Double
    ) {
        try {
            val toolPayload = json.parseToJsonElement(submission.content) as? JsonObject ?: return
            db.toolRegistryDao().insert(
                ToolRegistryEntryEntity(
                    id = UUID.randomUUID().toString(),
                    name = toolPayload["name"]?.jsonPrimitive?.content ?: "unnamed_tool",
                    authorAgentId = submission.agentId,
                    inputSchemaJson = toolPayload["inputSchema"]?.toString() ?: "{}",
                    outputSchemaJson = toolPayload["outputSchema"]?.toString() ?: "{}",
                    domain = toolPayload["domain"]?.jsonPrimitive?.content ?: "general",
                    code = toolPayload["code"]?.jsonPrimitive?.content ?: "",
                    codeLanguage = toolPayload["language"]?.jsonPrimitive?.content ?: "shell",
                    generation = submission.generation,
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to promote tool submission ${submission.id}" }
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
    private suspend fun applyKgConstraintToSchema(submission: StagingSubmissionEntity) {
        try {
            val constraintObj: JsonObject? = try {
                json.parseToJsonElement(submission.content) as? JsonObject
            } catch (_: Exception) { null }

            val now = System.currentTimeMillis()
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
                    if (rel in types) return  // already present — no-op
                    base.copy(
                        relationTypesJson = stringListToJson(types + rel),
                        schemaVersion = base.schemaVersion + 1
                    )
                }
                constraintObj["entityType"]?.jsonPrimitive?.content != null -> {
                    val et = constraintObj["entityType"]!!.jsonPrimitive.content
                    val types = parseStringList(base.entityTypesJson)
                    if (et in types) return
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
        } catch (e: Exception) {
            Napier.e(e) { "[$groupId] Failed to apply KG_CONSTRAINT to schema: ${submission.id}" }
        }
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

    companion object {
        private val CRITIC_SYSTEM_PROMPT = """
            You are a Critic agent in the EvoKammer civilisational multi-agent system.
            Your role is to evaluate submissions from other agents and decide whether to promote,
            reject, or dispute them.
            
            You are rigorous but fair. You promote high-quality, accurate, and useful content.
            You reject content that is incorrect, duplicate, incoherent, or violates system norms.
            You dispute borderline content that needs a second opinion.
            
            Always respond with a JSON object only.
        """.trimIndent()
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
