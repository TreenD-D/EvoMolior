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
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Runs the Critic review pipeline for staged submissions.
 *
 * The pipeline:
 * 1. Reads pending submissions from staging
 * 2. Uses a Koog AIAgent to evaluate each submission
 * 3. On PROMOTE: writes to canonical substrate
 * 4. On REJECT: records rejection reason
 * 5. On DISPUTE: submits for second review; double-promote wins, double-reject loses
 * 6. Death Notes are auto-promoted (skip review)
 *
 * @param db The group's Room database.
 * @param runId The run identifier.
 * @param groupId The group identifier.
 * @param generation The current generation number.
 * @param openRouterApiKey OpenRouter API key.
 * @param criticModel The model to use for Critic review.
 */
class CriticPipeline(
    private val db: EvoKammerDatabase,
    private val runId: String,
    private val groupId: String,
    private val generation: Int,
    private val openRouterApiKey: String,
    private val criticModel: String = "anthropic/claude-3-5-sonnet"
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Processes all pending submissions in the staging area for the current group.
     *
     * Should be called by the LifecycleManager after each generation boundary.
     *
     * @return The number of submissions processed.
     */
    suspend fun processPendingSubmissions(): Int {
        val pending = db.stagingDao().getPending(runId, groupId)
        var count = 0

        for (submission in pending) {
            try {
                // Death Notes are always promoted
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
                    count++
                    continue
                }

                val review = reviewSubmission(submission)
                applyReview(submission, review)
                count++

            } catch (e: Exception) {
                Napier.e(e) { "[$groupId] Critic review failed for submission ${submission.id}" }
            }
        }

        return count
    }

    private suspend fun reviewSubmission(submission: com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity): CriticReview {
        val criticId = "critic-${UUID.randomUUID().toString().take(8)}"
        val prompt = buildCriticPrompt(submission)

        val model: LLModel = when (criticModel) {
            "anthropic/claude-3-5-sonnet" -> OpenRouterModels.Claude3_5Sonnet
            "anthropic/claude-3-haiku"    -> OpenRouterModels.Claude3Haiku
            "openai/gpt-4o"               -> OpenRouterModels.GPT4o
            "openai/gpt-4o-mini"          -> OpenRouterModels.GPT4oMini
            else -> LLModel(provider = LLMProvider.OpenRouter, id = criticModel)
        }

        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(simpleOpenRouterExecutor(openRouterApiKey))
            .llmModel(model)
            .systemPrompt(CRITIC_SYSTEM_PROMPT)
            .toolRegistry(ToolRegistry {})
            .graphStrategy(singleRunStrategy())
            .build()

        val responseText = agent.run(prompt)

        return parseCriticResponse(responseText, criticId, submission.id)
    }

    private fun buildCriticPrompt(submission: com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity): String = """
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
    """.trimIndent()

    private fun parseCriticResponse(responseText: String, criticId: String, submissionId: String): CriticReview {
        return try {
            // Try to extract JSON from response
            val jsonStr = responseText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
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
        submission: com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity,
        review: CriticReview
    ) {
        val newStatus = when (review.outcome) {
            CriticOutcome.PROMOTE -> "promoted"
            CriticOutcome.REJECT -> "rejected"
            CriticOutcome.DISPUTE -> "disputed"
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
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = UUID.randomUUID().toString(),
                        content = submission.content,
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
            else -> {
                // FACT, KG_CONSTRAINT, PLAN_UPDATE, DISPATCH handled by respective services
                Napier.d { "[$groupId] Non-KB submission promoted: ${submission.type} ${submission.id}" }
            }
        }
    }

    private suspend fun promoteToolSubmission(
        submission: com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity,
        trustScore: Double
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

    private fun extractDomain(content: String): String {
        // Simple heuristic domain extraction — in Phase 3+ this uses KG schema tags
        return "general"
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
