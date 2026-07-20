package com.achulkov.evomolior.competition.leaks

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

/**
 * Verifies that DifferentialPrivacyTransform rethrows blocking aborts instead of falling back to the original content.
 */
class DifferentialPrivacyTransformBlockingAbortTest {

    @Test
    fun `blocking abort is rethrown`() = runTest {
        val transform = DifferentialPrivacyTransform(
            openRouterApiKey = "sk-test",
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
            transform.transform("raw content", domain = "general")
        }
    }
}
