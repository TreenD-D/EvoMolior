package com.achulkov.evomolior.agents.factory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Provider-level smoke test for OpenRouter chat completions.
 *
 * This test is deliberately opt-in because it reaches the live provider. It validates that
 * the raw HTTP response is non-empty, JSON-decodable, and contains either content or tool
 * calls for the Gemini models we rely on in production.
 */
class OpenRouterProviderSmokeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val enabledEnvVar = "EVOMOLIOR_ENABLE_OPENROUTER_SMOKE_TEST"
    private val apiKeyEnvVar = "OPENROUTER_API_KEY"
    private val endpoint = URI.create("https://openrouter.ai/api/v1/chat/completions")

    @Test
    fun `chat completions return non-empty tool-capable responses for supported Gemini models`() {
        assumeTrue(
            System.getenv(enabledEnvVar) == "1",
            "Set $enabledEnvVar=1 to run the live OpenRouter smoke test"
        )

        val apiKey = System.getenv(apiKeyEnvVar)
        assumeTrue(!apiKey.isNullOrBlank(), "Set $apiKeyEnvVar to run the live OpenRouter smoke test")

        listOf(
            "openai/gpt-oss-120b",
            "x-ai/grok-4.3",
            "google/gemini-2.5-flash",
            "google/gemini-3-flash-preview"
        ).forEach { modelId ->
            val rawBody = postChatCompletionRaw(
                apiKey = apiKey,
                modelId = modelId
            )

            assertTrue(rawBody.isNotBlank(), "OpenRouter returned a blank body for $modelId")
            assertTrue(
                rawBody.contains("\"choices\""),
                "Raw OpenRouter response for $modelId must contain choices"
            )

            val response = runCatching { json.parseToJsonElement(rawBody).jsonObject }
                .getOrElse { error("OpenRouter returned invalid JSON for $modelId: ${it.message}\nRaw body:\n$rawBody") }

            val errorObject = response["error"]?.jsonObject
            if (errorObject != null) {
                fail("OpenRouter returned an error for $modelId: $errorObject\nRaw body:\n$rawBody")
            }

            val choices = response["choices"] as? JsonArray
                ?: fail("OpenRouter response for $modelId did not contain a choices array\nRaw body:\n$rawBody")
            assertTrue(choices.isNotEmpty(), "OpenRouter response for $modelId contained no choices\nRaw body:\n$rawBody")

            val firstChoice = choices.first().jsonObject
            val message = firstChoice["message"]?.jsonObject
                ?: fail("OpenRouter response for $modelId did not contain a message payload\nRaw body:\n$rawBody")
            val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val toolCalls = message["tool_calls"] as? JsonArray

            assertTrue(
                content.isNotEmpty() || toolCalls?.isNotEmpty() == true,
                "OpenRouter response for $modelId was empty and had no tool calls\nRaw body:\n$rawBody"
            )
        }
    }

    @Test
    fun `deprecated grok fast returns a 404 deprecation body`() {
        assumeTrue(
            System.getenv(enabledEnvVar) == "1",
            "Set $enabledEnvVar=1 to run the live OpenRouter smoke test"
        )

        val apiKey = System.getenv(apiKeyEnvVar)
        assumeTrue(!apiKey.isNullOrBlank(), "Set $apiKeyEnvVar to run the live OpenRouter smoke test")

        val response = postChatCompletionResponse(apiKey, "x-ai/grok-4-fast")
        assertEquals(
            404,
            response.statusCode(),
            "Deprecated Grok 4 Fast should return a 404 deprecation response"
        )
        assertTrue(
            response.body().contains("Grok 4 Fast is deprecated"),
            "Raw response body should surface the provider deprecation message"
        )
        assertTrue(
            response.body().contains("x-ai/grok-4.3"),
            "Raw response body should point callers to Grok 4.3"
        )
    }

    private fun postChatCompletionRaw(apiKey: String, modelId: String): String {
        return postChatCompletionResponse(apiKey, modelId).body()
    }

    private fun postChatCompletionResponse(apiKey: String, modelId: String): HttpResponse<String> {
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "content",
                                JsonPrimitive(
                                    "Call the tool noop_tool exactly once. If you cannot call tools, reply with TOOL_UNAVAILABLE."
                                )
                            )
                        }
                    )
                }
            )
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("function"))
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", JsonPrimitive("noop_tool"))
                                    put("description", JsonPrimitive("No-op diagnostic tool"))
                                    put(
                                        "parameters",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("object"))
                                            put("additionalProperties", JsonPrimitive(false))
                                            put("properties", buildJsonObject { })
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put("tool_choice", JsonPrimitive("auto"))
            put("max_tokens", JsonPrimitive(32))
            put("temperature", JsonPrimitive(0))
        }.toString()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/achulkov/EvoMolior")
            .header("X-Title", "EvoMolior OpenRouter smoke test")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
