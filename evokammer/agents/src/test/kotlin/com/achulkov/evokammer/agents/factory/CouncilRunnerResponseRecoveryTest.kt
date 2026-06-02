package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt as koogPrompt
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
import kotlin.test.assertTrue

/**
 * Verifies that CouncilRunner can survive an empty assistant turn via the shared recovery processor.
 */
class CouncilRunnerResponseRecoveryTest {

    @Test
    fun `council agent recovers from an empty assistant turn`() = runTest {
        val responses = ArrayDeque<Message.Assistant>(
            listOf(
                Message.Assistant(
                    parts = emptyList(),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System)
                ),
                Message.Assistant(
                    content = """{"status":"ok"}""",
                    metaInfo = ResponseMetaInfo.create(KoogClock.System)
                )
            )
        )

        val runner = CouncilRunner(
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

        val output = runner.runCouncilAgent(
            role = com.achulkov.evokammer.core.model.Role.CouncilSynthesis,
            contextJson = """{"objective":"test"}"""
        )

        assertTrue(output.isNotBlank(), "CouncilRunner should not collapse to a blank result")
        assertTrue(output != "{}", "CouncilRunner should not fall back to the hard failure stub")
        assertTrue(
            output.contains("assistant response was empty", ignoreCase = true) || output.contains("ok"),
            "CouncilRunner should surface either the recovery text or the recovered structured output"
        )
    }
}
