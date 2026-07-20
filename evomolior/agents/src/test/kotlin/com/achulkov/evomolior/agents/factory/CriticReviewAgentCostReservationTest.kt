@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class CriticReviewAgentCostReservationTest {
    @Test
    fun `cancellation finalizes the active provider cost reservation`() = runTest {
        val runId = "run-critic-cost-cancellation"
        val groupId = "group-1"
        val database = SubstrateDatabaseFactory(
            createTempDirectory("critic-cost-cancellation-").toFile()
        ).get(runId, groupId)
        try {
            val repository = CostLedgerRepository(database)
            val providerStarted = CompletableDeferred<Unit>()
            val agent = CriticReviewAgent(
                openRouterApiKey = "sk-test",
                criticModelId = "anthropic/claude-haiku-4.5",
                providerCostRecorder = { criticId ->
                    ProviderCallCostRecorder(
                        repository = repository,
                        runId = runId,
                        groupId = groupId,
                        agentId = criticId
                    )
                },
                promptExecutor = object : PromptExecutor() {
                    override suspend fun execute(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Message.Assistant {
                        providerStarted.complete(Unit)
                        awaitCancellation()
                    }

                    override fun executeStreaming(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Flow<StreamFrame> = emptyFlow()

                    override suspend fun moderate(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel
                    ): ModerationResult = error("not used")

                    override fun close() = Unit
                }
            )

            val reviewJob = launch {
                agent.reviewSubmission(
                    submission = StagingSubmissionEntity(
                        id = "submission-1",
                        agentId = "agent-1",
                        role = "Researcher",
                        type = "DOC",
                        content = "Review this cancellation-bound contribution.",
                        budgetSignature = "sig",
                        wipTaskRef = "node-1",
                        speculative = false,
                        outlier = false,
                        generation = 1,
                        groupId = groupId,
                        runId = runId,
                        submittedAtMs = 1_000L
                    ),
                    criticId = "critic-timeout"
                )
            }
            providerStarted.await()
            reviewJob.cancelAndJoin()

            val summary = repository.summary(runId)
            assertEquals(0L, summary.activeReservationCount)
            assertEquals(1L, summary.finalizedEntryCount)
            assertEquals(1L, summary.unknownMonetaryEntryCount)
        } finally {
            database.close()
        }
    }
}
