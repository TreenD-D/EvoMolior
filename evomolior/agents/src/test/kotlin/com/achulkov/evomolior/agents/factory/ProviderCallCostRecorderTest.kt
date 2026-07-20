@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

class ProviderCallCostRecorderTest {
    @Test
    fun `completed and failed attempts retain exact available usage independently`() = runTest {
        val runId = "run-provider-cost"
        val database = SubstrateDatabaseFactory(
            createTempDirectory("provider-cost-").toFile()
        ).get(runId, "group-1")
        try {
            val repository = CostLedgerRepository(database)
            var nowMs = 1_000L
            val recorder = ProviderCallCostRecorder(
                repository = repository,
                runId = runId,
                groupId = "group-1",
                agentId = "agent-1",
                now = { Instant.fromEpochMilliseconds(nowMs) }
            )
            val model = LLModel(LLMProvider.OpenRouter, "model-1")

            recorder.starting("event-success", model)
            nowMs = 1_125L
            recorder.completed(
                "event-success",
                ResponseMetaInfo.create(
                    clock = KoogClock.System,
                    inputTokensCount = 21,
                    outputTokensCount = 8,
                    totalTokensCount = 29,
                    modelId = model.id
                )
            )

            recorder.starting("event-failure", model)
            nowMs = 1_200L
            recorder.failedAll()

            val success = assertNotNull(
                repository.byId(
                    "provider-cost:agent-1:event-success:finalized"
                )
            )
            assertEquals(21L, success.inputTokens)
            assertEquals(8L, success.outputTokens)
            assertEquals(125L, success.wallTimeMillis)
            assertEquals(MonetaryAmountStatus.UNKNOWN, success.providerCost.status)

            val failure = assertNotNull(
                repository.byId(
                    "provider-cost:agent-1:event-failure:finalized"
                )
            )
            assertEquals(null, failure.inputTokens)
            assertEquals(null, failure.outputTokens)
            assertEquals(75L, failure.wallTimeMillis)
            assertEquals(2L, repository.summary(runId).finalizedEntryCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun `configured cap rejects unbounded provider attempt before dispatch`() = runTest {
        val runId = "run-provider-cap"
        val database = SubstrateDatabaseFactory(
            createTempDirectory("provider-cap-").toFile()
        ).get(runId, "group-1")
        try {
            val repository = CostLedgerRepository(
                database,
                CostLedgerPolicy(cumulativeCapUsd = 1.0)
            )
            val recorder = ProviderCallCostRecorder(
                repository = repository,
                runId = runId,
                groupId = "group-1",
                agentId = "agent-1"
            )

            assertFailsWith<IllegalStateException> {
                recorder.starting(
                    "event-capped",
                    LLModel(LLMProvider.OpenRouter, "model-1")
                )
            }
            assertEquals(0L, repository.summary(runId).finalizedEntryCount)
            assertEquals(0.0, repository.summary(runId).activeReservedMaximumUsd)
        } finally {
            database.close()
        }
    }
}
