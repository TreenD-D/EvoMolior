package com.achulkov.evokammer.agents.factory

import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider

/**
 * Builds the OpenRouter-backed Koog prompt executor used by EvoKammer runtime agents.
 *
 * Koog 1.0 publishes the client and multi-provider executor pieces separately, so the runtime
 * wires them directly instead of depending on the removed convenience helper.
 *
 * @param apiKey OpenRouter API key.
 * @return A prompt executor configured for the OpenRouter provider.
 */
fun createOpenRouterPromptExecutor(apiKey: String): MultiLLMPromptExecutor =
    MultiLLMPromptExecutor(LLMProvider.OpenRouter to OpenRouterLLMClient(apiKey))
