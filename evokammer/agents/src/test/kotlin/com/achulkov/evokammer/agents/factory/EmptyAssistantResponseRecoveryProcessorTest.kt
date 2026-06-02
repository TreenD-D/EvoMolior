package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies that empty assistant responses are turned into an explicit retryable turn.
 */
class EmptyAssistantResponseRecoveryProcessorTest {

    @Test
    fun `empty assistant response becomes a retry instruction`() = runTest {
        val processor = EmptyAssistantResponseRecoveryProcessor
        val response = Message.Assistant(
            parts = emptyList(),
            metaInfo = ResponseMetaInfo.create(KoogClock.System)
        )

        val recovered = processor.process(
            executor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant = error("not used")

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
            },
            prompt = koogPrompt("recovery-test") { system("test") },
            model = OpenRouterModels.GPT4o,
            tools = emptyList<ToolDescriptor>(),
            response = response,
            serializer = KotlinxSerializer()
        )

        assertEquals(response.metaInfo, recovered.metaInfo)
        assertEquals(response.rawResponse, recovered.rawResponse)
        assertEquals(1, recovered.parts.size)
        val textPart = assertIs<MessagePart.Text>(recovered.parts.single())
        assertEquals(
            "The previous assistant response was empty. Respond with either a tool call or a concise answer.",
            textPart.text
        )
    }
}
