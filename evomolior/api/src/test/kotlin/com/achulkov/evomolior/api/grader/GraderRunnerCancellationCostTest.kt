@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.grader

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises cancellation using factory-owned recorders and the durable ledger, without network calls. */
class GraderRunnerCancellationCostTest {
    @Test
    fun `cancelled criteria settle each blocked provider once with unknown usage`() = runBlocking {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-cancel-cost-").toFile())
        val runId = "run-cancel"
        val groups = listOf("grader-0", "grader-1", "grader-2")
        val ledgerDb = factory.get(runId, "__cost_ledger__")
        val ledger = CostLedgerRepository(ledgerDb)
        val started = Channel<GraderAgentContext>(groups.size)
        val progress = mutableListOf<String>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                assertNotNull(context.providerCostRecorder).starting("blocked", model)
                started.send(context)
                awaitCancellation()
            },
            costLedgerRepositoryProvider = { ledger }
        )
        val execution = async {
            runner.runCriteriaPhase(runId, groups, 0, "Test criteria", dimensions, progress::add)
        }
        try {
            // Every group has durably reserved before cancellation; no sleeps or live provider needed.
            val contexts = withTimeout(30_000L) {
                List(groups.size) { started.receive() }
            }
            assertEquals(3L, ledger.summary(runId).activeReservationCount)
            execution.cancelAndJoin()
            assertFailsWith<CancellationException> { execution.await() }

            assertEquals(0L, ledger.summary(runId).activeReservationCount)
            val finalized = ledger.entriesForRun(runId).filter { it.kind == CostLedgerEntryKind.FINALIZED }
            assertEquals(3, finalized.size)
            assertEquals(contexts.map { it.agentId }.toSet(), finalized.map { it.agentId }.toSet())
            finalized.forEach { entry ->
                assertEquals(MonetaryAmountStatus.UNKNOWN, entry.providerCost.status)
                assertNull(entry.providerCost.usd)
                assertNull(entry.inputTokens)
                assertNull(entry.outputTokens)
                assertTrue(entry.hasValidHash())
            }
            // A delayed/repeated Koog failure callback must be a no-op after runner cleanup.
            contexts.forEach { assertNotNull(it.providerCostRecorder).failedAll() }
            assertEquals(3L, ledger.summary(runId).finalizedEntryCount)
            assertEquals(3L, ledger.summary(runId).unknownMonetaryEntryCount)
            assertTrue(ledger.entriesForRun(runId).none { it.kind == CostLedgerEntryKind.RELEASED })
            assertTrue(progress.none { it.contains("criteria phase complete") })
            assertTrue(started.tryReceive().isFailure, "Cancellation must not launch another criteria role")
            assertEquals(CostLedgerEntryKind.PUBLICATION_CLOSED, ledger.closeForPublication(runId).kind)
        } finally {
            execution.cancelAndJoin()
            started.close()
            groups.forEach { factory.getGrader(runId, it).close() }
            ledgerDb.close()
        }
    }

    @Test
    fun `provider cancellation is propagated without converting it to criteria fallback`() = runBlocking {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-provider-cancel-").toFile())
        val runId = "run-provider-cancel"
        val ledgerDb = factory.get(runId, "__cost_ledger__")
        val ledger = CostLedgerRepository(ledgerDb)
        var calls = 0
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                assertNotNull(context.providerCostRecorder).starting("cancelled", model)
                calls++
                throw CancellationException("Provider cancelled")
            },
            costLedgerRepositoryProvider = { ledger }
        )
        try {
            assertFailsWith<CancellationException> {
                runner.runCriteriaPhase(runId, listOf("grader-0"), 0, "Test criteria", dimensions)
            }
            assertEquals(1, calls)
            assertEquals(0L, ledger.summary(runId).activeReservationCount)
            assertEquals(1L, ledger.summary(runId).unknownMonetaryEntryCount)
            val finalization = ledger.entriesForRun(runId).single { it.kind == CostLedgerEntryKind.FINALIZED }
            assertNull(finalization.inputTokens)
            assertNull(finalization.outputTokens)
            assertNull(finalization.providerCost.usd)
        } finally {
            factory.getGrader(runId, "grader-0").close()
            ledgerDb.close()
        }
    }

    @Test
    fun `runner cleanup preserves completed provider token evidence`() = runBlocking {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-completed-cost-").toFile())
        val runId = "run-completed"
        val ledgerDb = factory.get(runId, "__cost_ledger__")
        val ledger = CostLedgerRepository(ledgerDb)
        var calls = 0
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                val recorder = assertNotNull(context.providerCostRecorder)
                recorder.starting("completed", model)
                recorder.completed(
                    "completed",
                    ResponseMetaInfo.create(
                        clock = KoogClock.System,
                        inputTokensCount = 21,
                        outputTokensCount = 8,
                        totalTokensCount = 29,
                        modelId = model.id
                    )
                )
                calls++
                "Criteria drafted."
            },
            costLedgerRepositoryProvider = { ledger }
        )
        try {
            runner.runCriteriaPhase(runId, listOf("grader-0"), 0, "Test criteria", dimensions)
            assertTrue(calls > 0)
            val finalized = ledger.entriesForRun(runId).filter { it.kind == CostLedgerEntryKind.FINALIZED }
            assertEquals(calls, finalized.size)
            finalized.forEach {
                assertEquals(21L, it.inputTokens)
                assertEquals(8L, it.outputTokens)
                assertEquals(MonetaryAmountStatus.UNKNOWN, it.providerCost.status)
                assertNull(it.providerCost.usd)
            }
            assertEquals(0L, ledger.summary(runId).activeReservationCount)
        } finally {
            factory.getGrader(runId, "grader-0").close()
            ledgerDb.close()
        }
    }

    private val model = LLModel(LLMProvider.OpenRouter, "test-model")
    private val dimensions = listOf(
        ScoringDimension("accuracy", "Accuracy", "Evidence-grounded accuracy", 1.0)
    )
}
