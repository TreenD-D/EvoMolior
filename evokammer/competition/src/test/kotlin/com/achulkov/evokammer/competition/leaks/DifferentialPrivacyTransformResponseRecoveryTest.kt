package com.achulkov.evokammer.competition.leaks

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that DifferentialPrivacyTransform survives an empty assistant turn without stalling.
 */
class DifferentialPrivacyTransformResponseRecoveryTest {

    @Test
    fun `empty assistant turn is recovered into a transformed output`() = runTest {
        val responses = ArrayDeque<Message.Assistant>(
            listOf(
                Message.Assistant(
                    parts = emptyList(),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System)
                ),
                Message.Assistant(
                    content = "anonymised leak content",
                    metaInfo = ResponseMetaInfo.create(KoogClock.System)
                )
            )
        )

        val transform = DifferentialPrivacyTransform(
            openRouterApiKey = "sk-test",
            promptExecutor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant = responses.removeFirst()

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

        val output = transform.transform(
            rawContent = "internal implementation detail",
            domain = "general"
        )

        assertEquals(
            "The previous assistant response was empty. Respond with either a tool call or a concise answer.",
            output
        )
        assertTrue(output.isNotBlank())
    }
}
