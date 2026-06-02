package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.processor.ResponseProcessor
import ai.koog.serialization.JSONSerializer

/**
 * Normalizes empty assistant turns so the agent graph can continue instead of stalling.
 *
 * Some provider/model combinations intermittently return an assistant message with no parts.
 * Koog treats that as a hard graph failure. For EvoKammer, the safer fallback is to convert the
 * empty turn into an explicit "retry" text so the agent can make another bounded attempt.
 */
object EmptyAssistantResponseRecoveryProcessor : ResponseProcessor() {
    override suspend fun process(
        executor: PromptExecutor,
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        response: Message.Assistant,
        serializer: JSONSerializer,
    ): Message.Assistant {
        if (response.parts.isNotEmpty()) return response

        return Message.Assistant(
            content = EMPTY_ASSISTANT_RECOVERY_TEXT,
            metaInfo = response.metaInfo,
            finishReason = response.finishReason,
            rawResponse = response.rawResponse,
            id = response.id
        )
    }
}
