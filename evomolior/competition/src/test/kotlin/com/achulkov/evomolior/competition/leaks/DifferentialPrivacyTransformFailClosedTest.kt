package com.achulkov.evomolior.competition.leaks

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that privacy failures cannot relay the original cross-group payload.
 */
class DifferentialPrivacyTransformFailClosedTest {

    @Test
    fun `timeout fails closed instead of returning raw content`() = runTest {
        val transform = DifferentialPrivacyTransform(
            openRouterApiKey = "sk-test",
            timeoutMs = 10L,
            promptExecutor = executor {
                delay(100L)
                assistant("should never be returned")
            }
        )

        val failure = assertFailsWith<IllegalStateException> {
            transform.transform("source-identifying private implementation detail")
        }

        assertTrue(failure.message.orEmpty().contains("timed out"))
    }

    @Test
    fun `unchanged model output fails closed`() = runTest {
        val rawContent = "source-identifying private implementation detail"
        val transform = DifferentialPrivacyTransform(
            openRouterApiKey = "sk-test",
            promptExecutor = executor { assistant(rawContent) }
        )

        val failure = assertFailsWith<IllegalStateException> {
            transform.transform(rawContent)
        }

        assertTrue(failure.message.orEmpty().contains("failed"))
    }

    private fun executor(
        response: suspend () -> Message.Assistant
    ): PromptExecutor = object : PromptExecutor() {
        override suspend fun execute(
            prompt: ai.koog.prompt.Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant = response()

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

    private fun assistant(content: String): Message.Assistant = Message.Assistant(
        content = content,
        metaInfo = ResponseMetaInfo.create(KoogClock.System)
    )
}
