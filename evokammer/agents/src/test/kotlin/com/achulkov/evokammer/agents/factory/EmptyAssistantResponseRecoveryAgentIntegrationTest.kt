package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that a real Koog agent can recover from an empty assistant turn without stalling.
 */
class EmptyAssistantResponseRecoveryAgentIntegrationTest {

    @Test
    fun `agent completes when executor returns an empty assistant response`() = runTest {
        var executeCalls = 0
        val executor = object : PromptExecutor() {
            override suspend fun execute(
                prompt: ai.koog.prompt.Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Message.Assistant {
                executeCalls++
                return Message.Assistant(
                    parts = emptyList(),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System)
                )
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

        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                ai.koog.agents.core.agent.config.AIAgentConfig(
                    prompt = koogPrompt("empty-response-recovery") { system("You are a minimal assistant.") },
                    model = OpenRouterModels.GPT4o,
                    maxAgentIterations = 3,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(ToolRegistry {})
            .graphStrategy(singleRunStrategy())
            .maxIterations(3)
            .build()

        val result = agent.run("Say hello")

        assertEquals(1, executeCalls, "The fake executor should be invoked exactly once")
        assertTrue(
            result.contains("previous assistant response was empty", ignoreCase = true),
            "The recovery text should flow through the graph instead of throwing AIAgentStuckInTheNodeException"
        )
    }
}
