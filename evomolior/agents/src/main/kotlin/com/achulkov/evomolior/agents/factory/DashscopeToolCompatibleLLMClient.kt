package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val DASHSCOPE_COMPATIBLE_CHAT_COMPLETIONS_URL =
    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
private const val QWEN_EXPLICIT_CACHE_MIN_CHARS = 4_096

/**
 * DashScope client wrapper that preserves Koog's no-tool path and fixes tool-message
 * continuation serialization for Qwen.
 *
 * Koog's generic OpenAI-compatible converter double-encodes `MessagePart.Tool.Call.args` when it
 * serializes prior assistant tool calls back into a continuation request. DashScope rejects that
 * as invalid `function.arguments`. This client sends `arguments` as the raw JSON string stored in
 * Koog's tool-call part while leaving Koog in charge of the actual tool execution loop.
 */
class DashscopeToolCompatibleLLMClient(
    private val apiKey: String,
    private val delegate: LLMClient,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
    private val clock: KoogClock = KoogClock.System
) : LLMClient() {
    private val toolSchemaGenerator = OpenAICompatibleToolDescriptorSchemaGenerator()

    override val clientName: String = "DashscopeToolCompatibleLLMClient"

    override fun llmProvider(): LLMProvider = LLMProvider.Alibaba

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        if (tools.isEmpty() &&
            prompt.params.schema == null &&
            !prompt.requestsMachineReadableJson()
        ) {
            return delegate.execute(prompt, model, tools)
        }

        val requestBody = buildChatCompletionRequest(prompt, model, tools)
        val responseBody = postChatCompletion(requestBody)
        return parseAssistantResponse(responseBody, model)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ) = if (tools.isEmpty() &&
        prompt.params.schema == null &&
        !prompt.requestsMachineReadableJson()
    ) {
        delegate.executeMultipleChoices(prompt, model, tools)
    } else {
        listOf(execute(prompt, model, tools))
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override suspend fun embed(text: String, model: LLModel): List<Double> = delegate.embed(text, model)

    override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> = delegate.embed(inputs, model)

    override fun close() {
        delegate.close()
    }

    private fun buildChatCompletionRequest(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): String = json.encodeToString(
        buildJsonObject {
            put("model", model.id)
            putJsonArray("messages") {
                prompt.messages
                    .flatMap(::toDashscopeMessages)
                    .let(::withExplicitCacheMarkers)
                    .forEach(::add)
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", toolSchemaGenerator.generate(tool))
                                }
                            }
                        )
                    }
                }
                put("tool_choice", "required")
                // DashScope rejects required/object tool_choice while Qwen thinking mode is on.
                // Tool-enabled EvoMolior agents need deterministic tool-call compliance more than
                // provider-side hidden reasoning, so disable thinking for these calls.
                put("enable_thinking", false)
            }
            val outputSchema = prompt.params.schema
            if (outputSchema != null) {
                require(model.supports(outputSchema.capability)) {
                    "Model ${model.id} does not support structured output schema ${outputSchema.name}"
                }
                val jsonSchema = outputSchema as? LLMParams.Schema.JSON
                    ?: error("DashScope supports only JSON output schemas")
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", jsonSchema.name)
                        put("strict", true)
                        put("schema", jsonSchema.schema)
                    }
                }
                put("enable_thinking", false)
            } else if (tools.isEmpty() && prompt.requestsMachineReadableJson()) {
                putJsonObject("response_format") {
                    put("type", "json_object")
                }
                put("enable_thinking", false)
            }
            prompt.params.temperature?.let { put("temperature", it) }
        }
    )

    private fun Prompt.requestsMachineReadableJson(): Boolean {
        val text = messages.joinToString("\n") { message ->
            message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
        }.lowercase()
        return listOf(
            "json object",
            "valid json",
            "return json",
            "emit json",
            "machine-readable",
            "machine readable",
            "schema"
        ).any { marker -> marker in text }
    }

    private fun toDashscopeMessages(message: Message): List<JsonObject> = when (message) {
        is Message.System -> message.parts.map { part ->
            buildJsonObject {
                put("role", "system")
                put("content", part.text)
            }
        }
        is Message.User -> buildList {
            message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach { result ->
                add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.id.orEmpty())
                        put("content", result.output)
                    }
                )
            }
            val text = message.parts.filterIsInstance<MessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    }
                )
            }
        }
        is Message.Assistant -> listOf(
            buildJsonObject {
                put("role", "assistant")
                val text = message.parts.filterIsInstance<MessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (text.isNotBlank()) {
                    put("content", text)
                } else {
                    put("content", JsonNull)
                }
                val toolCalls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
                if (toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", call.id.orEmpty())
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.tool)
                                        put("arguments", call.args)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun withExplicitCacheMarkers(messages: List<JsonObject>): List<JsonObject> {
        var remainingMarkers = 4
        return messages.map { message ->
            if (remainingMarkers <= 0) return@map message
            val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (role != "system" && role != "user") return@map message
            val content = message["content"] as? JsonPrimitive ?: return@map message
            val text = content.contentOrNull.orEmpty()
            if (text.length < QWEN_EXPLICIT_CACHE_MIN_CHARS) return@map message
            remainingMarkers--
            message.withEphemeralCacheControl(text)
        }
    }

    private fun JsonObject.withEphemeralCacheControl(text: String): JsonObject = buildJsonObject {
        entries.forEach { (key, value) ->
            if (key != "content") put(key, value)
        }
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    putJsonObject("cache_control") {
                        put("type", "ephemeral")
                    }
                }
            )
        }
    }

    private suspend fun postChatCompletion(requestBody: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DASHSCOPE_COMPATIBLE_CHAT_COMPLETIONS_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw LLMClientException(
                clientName,
                "DashScope chat completion failed with HTTP ${response.statusCode()}: ${response.body().take(600)}",
                null
            )
        }
        response.body()
    }

    private fun parseAssistantResponse(responseBody: String, model: LLModel): Message.Assistant {
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }
            .getOrElse { error ->
                throw LLMClientException(clientName, "Invalid DashScope JSON response: ${error.message}", error)
            }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LLMClientException(clientName, "DashScope response did not contain choices.", null)
        val message = choice["message"]?.jsonObject
            ?: throw LLMClientException(clientName, "DashScope response choice did not contain a message.", null)

        val parts = buildList<MessagePart.ResponsePart> {
            message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { content ->
                add(MessagePart.Text(content))
            }
            message["tool_calls"]?.jsonArray.orEmpty().forEach { callElement ->
                val call = callElement.jsonObject
                val function = call["function"]?.jsonObject
                    ?: throw LLMClientException(clientName, "DashScope tool call did not contain function.", null)
                val arguments = function["arguments"]?.asRawArgumentString()
                    ?: "{}"
                add(
                    MessagePart.Tool.Call(
                        id = call["id"]?.jsonPrimitive?.contentOrNull,
                        tool = function["name"]?.jsonPrimitive?.contentOrNull
                            ?: throw LLMClientException(clientName, "DashScope tool call function had no name.", null),
                        args = arguments
                    )
                )
            }
        }

        return Message.Assistant(
            parts = parts,
            metaInfo = ResponseMetaInfo.create(
                clock = clock,
                totalTokensCount = root["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull,
                inputTokensCount = root["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
                outputTokensCount = root["usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
                modelId = model.id
            ),
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            rawResponse = root,
            id = root["id"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun JsonElement.asRawArgumentString(): String = when (this) {
        is JsonObject -> json.encodeToString(this)
        else -> jsonPrimitive.contentOrNull ?: json.encodeToString(this)
    }
}
