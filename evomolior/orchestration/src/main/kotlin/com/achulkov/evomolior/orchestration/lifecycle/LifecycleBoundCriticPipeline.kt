package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.budget.BudgetLedgerSnapshot
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.orchestration.repair.StructuredToolPromotionOutcome
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Lifecycle-bound wrapper for [CriticPipeline].
 *
 * This makes the critic pass behave like a mortal agent: it has a stable identity, finite
 * budget, hard TTL, and a mandatory Death Note staging record written in a non-cancellable
 * epilogue by callers that invoke [run].
 *
 * @param db The group's Room database.
 * @param runId The run identifier.
 * @param groupId The group identifier.
 * @param generation The generation being reviewed.
 * @param criticAgentId Stable critic identity for this lifecycle.
 * @param ttlMs Maximum wall-clock time allowed for this critic lifecycle.
 * @param budgetLedger Budget ledger charged for review work.
 * @param budgetPolicy Policy used to estimate lifecycle review cost from the pending workload.
 * @param pipelineFactory Factory that creates the concrete pipeline for this lifecycle.
 */
class LifecycleBoundCriticPipeline(
    private val db: EvoMoliorDatabase,
    private val runId: String,
    private val groupId: String,
    private val generation: Int,
    private val criticAgentId: String,
    private val ttlMs: Long,
    private val budgetLedger: BudgetLedger,
    private val budgetPolicy: CriticLifecycleBudgetPolicy = CriticLifecycleBudgetPolicy(),
    private val pipelineFactory: (criticAgentId: String, criticAuthorBlocklist: Set<String>) -> CriticPipeline
) {
    /**
     * Runs the critic lifecycle to completion or fail-closed timeout/budget exhaustion.
     *
     * @return [CriticLifecycleSummary] containing the pipeline summary and lifecycle metadata.
     */
    suspend fun run(): CriticLifecycleSummary {
        val startedAtMs = System.currentTimeMillis()
        var outcome = "completed"
        var summary = CriticSummary(promoted = 0, rejected = 0, arbitrated = 0)
        var errorMessage: String? = null
        var pipeline: CriticPipeline? = null

        try {
            val pendingBeforeReview = db.stagingDao().getPending(runId, groupId)
            val deductResult = budgetLedger.deduct(
                ActionType.LLM_CALL,
                tokenCount = budgetPolicy.estimateReviewTokenCount(pendingBeforeReview)
            )
            if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
                outcome = "budget_exhausted"
            } else {
                summary = withContext(Dispatchers.Default) {
                    withTimeout(ttlMs) {
                        pipelineFactory(criticAgentId, setOf(criticAgentId))
                            .also { pipeline = it }
                            .processPendingSubmissions()
                    }
                }
                if (deductResult == BudgetDeductResult.FLOOR_REACHED || budgetLedger.isAtFloor) {
                    outcome = "budget_floor_reached"
                }
            }
        } catch (e: TimeoutCancellationException) {
            outcome = "ttl_expired"
            errorMessage = "Critic lifecycle exceeded TTL ${ttlMs}ms"
        } catch (e: LinkageError) {
            outcome = "failed"
            errorMessage = "${e::class.simpleName}: ${e.message}"
        } catch (e: Exception) {
            outcome = "failed"
            errorMessage = e.message
        } finally {
            // The timeout scope has settled; its normal return may have been cancelled even
            // though earlier reviews completed. Use the same counts for telemetry and the note.
            summary = pipeline?.summarySnapshot() ?: summary
            val finishedAtMs = System.currentTimeMillis()
            withContext(NonCancellable) {
                writeAndPromoteDeathNote(
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    outcome = outcome,
                    summary = summary,
                    errorMessage = errorMessage
                )
            }
        }
        return CriticLifecycleSummary(
            summary = summary,
            lifecycleOutcome = outcome,
            deathNoteWritten = true,
            budgetSnapshot = budgetLedger.snapshot(),
            repairDiagnostics = pipeline?.toolRepairDiagnosticsSnapshot().orEmpty(),
            promotionOutcomes = pipeline?.toolPromotionOutcomesSnapshot().orEmpty()
        )
    }

    private suspend fun writeAndPromoteDeathNote(
        startedAtMs: Long,
        finishedAtMs: Long,
        outcome: String,
        summary: CriticSummary,
        errorMessage: String?
    ) {
        budgetLedger.deduct(ActionType.WRITE_DEATH_NOTE)
        val deathNoteId = "critic-death-note-${UUID.randomUUID()}"
        val payload = buildString {
            appendLine("CRITIC_PIPELINE_DEATH_NOTE")
            appendLine("criticAgentId: $criticAgentId")
            appendLine("outcome: $outcome")
            appendLine("startedAtMs: $startedAtMs")
            appendLine("finishedAtMs: $finishedAtMs")
            appendLine("promoted: ${summary.promoted}")
            appendLine("rejected: ${summary.rejected}")
            appendLine("arbitrated: ${summary.arbitrated}")
            if (!errorMessage.isNullOrBlank()) appendLine("error: ${errorMessage.take(500)}")
        }
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = deathNoteId,
                agentId = criticAgentId,
                role = "Critic",
                type = "DEATH_NOTE",
                content = payload,
                budgetSignature = "critic-lifecycle-reserved",
                status = "pending",
                generation = generation,
                groupId = groupId,
                runId = runId,
                submittedAtMs = finishedAtMs
            )
        )
        db.stagingDao().updateReview(
            id = deathNoteId,
            status = "promoted",
            criticId = "system",
            trustScore = 0.8,
            reason = null,
            reviewedAtMs = finishedAtMs
        )
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-$deathNoteId",
                content = payload,
                sourceAgentId = criticAgentId,
                generation = generation,
                trustScore = 0.8,
                domain = "critic_lifecycle",
                groupId = groupId,
                runId = runId,
                createdAtMs = finishedAtMs,
                isDeadEnd = true
            )
        )
    }
}

/**
 * Estimates the budget charge for a critic lifecycle from the staged workload.
 *
 * @param baseTokenCharge Minimum lifecycle overhead for prompt setup and summary synthesis.
 * @param perSubmissionTokenCharge Fixed review overhead per pending submission.
 * @param contentCharsPerToken Approximate character-to-token conversion for staged payloads.
 */
data class CriticLifecycleBudgetPolicy(
    val baseTokenCharge: Int = 256,
    val perSubmissionTokenCharge: Int = 128,
    val contentCharsPerToken: Int = 4
) {
    /**
     * Estimates LLM-call token count from the actual pending submissions reviewed by this lifecycle.
     *
     * @param pendingSubmissions Pending submissions visible at lifecycle start.
     * @return Positive token count suitable for [ActionType.LLM_CALL] budget deduction.
     */
    fun estimateReviewTokenCount(pendingSubmissions: List<StagingSubmissionEntity>): Int {
        val charsPerToken = contentCharsPerToken.coerceAtLeast(1)
        val contentTokenEstimate = pendingSubmissions.sumOf { submission ->
            (submission.content.length / charsPerToken).coerceAtLeast(1)
        }
        return (baseTokenCharge + pendingSubmissions.size * perSubmissionTokenCharge + contentTokenEstimate)
            .coerceAtLeast(1)
    }
}

/**
 * Lifecycle execution result for [LifecycleBoundCriticPipeline].
 *
 * @param summary Critic promotion/rejection counts.
 * @param lifecycleOutcome Lifecycle status such as `completed`, `ttl_expired`, or `budget_exhausted`.
 * @param deathNoteWritten Whether the caller-observed summary was produced after Death Note insertion.
 * @param budgetSnapshot Final critic budget snapshot.
 */
data class CriticLifecycleSummary(
    val summary: CriticSummary,
    val lifecycleOutcome: String,
    val deathNoteWritten: Boolean,
    val budgetSnapshot: BudgetLedgerSnapshot,
    val repairDiagnostics: List<ToolRepairDiagnosticV1> = emptyList(),
    val promotionOutcomes: Map<String, StructuredToolPromotionOutcome> = emptyMap()
)
