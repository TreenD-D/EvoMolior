package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that CouncilRunner rethrows blocking aborts instead of converting them into fallback JSON.
 */
class CouncilRunnerBlockingAbortTest {

    @Test
    fun `blocking abort is rethrown`() = runTest {
        val runner = CouncilRunner(
            openRouterApiKey = "sk-test",
            executionEnvelope = councilTestExecutionEnvelope(),
            promptExecutor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant = throw BlockingAgentAbortException("tool validation failed")

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

        assertFailsWith<BlockingAgentAbortException> {
            runner.runCouncilAgent(
                role = com.achulkov.evomolior.core.model.Role.CouncilTaskAnatomist,
                contextJson = """{"objective":"test"}"""
            )
        }
    }

    @Test
    fun `ordinary council execution failure is surfaced instead of returning empty JSON`() = runTest {
        val runner = CouncilRunner(
            openRouterApiKey = "sk-test",
            executionEnvelope = councilTestExecutionEnvelope(),
            promptExecutor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant = error("malformed provider response")

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

        val error = assertFailsWith<CouncilRoleExecutionException> {
            runner.runCouncilAgent(
                role = com.achulkov.evomolior.core.model.Role.CouncilTaskAnatomist,
                contextJson = """{"objective":"test"}"""
            )
        }
        assertTrue(error.message.orEmpty().contains("CouncilTaskAnatomist"))
    }
}
