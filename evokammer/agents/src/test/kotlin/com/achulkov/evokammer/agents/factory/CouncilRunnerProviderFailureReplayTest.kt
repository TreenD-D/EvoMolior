package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Replays the provider-502 path through the real CouncilRunner orchestration entry point.
 *
 * This is the closest deterministic integration coverage we can get without depending on a live
 * OpenRouter outage: the agent is built normally, the prompt executor throws the 502-shaped error,
 * and the shared retry policy must escalate it into a blocking abort.
 */
class CouncilRunnerProviderFailureReplayTest {

    @Test
    fun `provider 502 escalates to blocking abort through CouncilRunner`() = runTest {
        var attempts = 0

        val runner = CouncilRunner(
            openRouterApiKey = "sk-test",
            promptExecutor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant {
                    attempts++
                    error("OpenRouter API error: Provider returned error (code: 502)")
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

        val ex = assertFailsWith<BlockingAgentAbortException> {
            runner.runCouncilAgent(
                role = Role.CouncilSynthesis,
                contextJson = """{"objective":"test"}"""
            )
        }

        assertTrue(attempts >= 2, "The runner should retry the provider failure before aborting")
        assertTrue(
            ex.message.orEmpty().contains("Council[CouncilSynthesis] aborted"),
            "The abort message should identify the council entry point"
        )
    }
}
