package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.Authenticator
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashscopeToolCompatibleLLMClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes tools assistant tool calls and tool results in DashScope compatible shape`() = runBlocking {
        val httpClient = CapturingHttpClient(
            responseBody = """
                {
                  "id": "chatcmpl-test",
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call-next",
                            "type": "function",
                            "function": {
                              "name": "write_staging",
                              "arguments": "{\"type\":\"DOC\",\"content\":\"ok\"}"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": { "prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7 }
                }
            """.trimIndent()
        )
        val client = DashscopeToolCompatibleLLMClient(
            apiKey = "test-key",
            delegate = NoopLLMClient,
            httpClient = httpClient
        )
        val prompt = Prompt(
            id = "dashscope-tool-shape",
            messages = listOf(
                Message.System("system", RequestMetaInfo.create(KoogClock.System)),
                Message.User("call a tool", RequestMetaInfo.create(KoogClock.System)),
                Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "call-prev",
                            tool = "read_messages",
                            args = "{\"includeBroadcast\":false}"
                        )
                    ),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System),
                    finishReason = "tool_calls"
                ),
                Message.User(
                    part = MessagePart.Tool.Result(
                        id = "call-prev",
                        tool = "read_messages",
                        output = "[]"
                    ),
                    metaInfo = RequestMetaInfo.create(KoogClock.System)
                )
            )
        )
        val tool = ToolDescriptor(
            name = "write_staging",
            description = "Persist staged output.",
            requiredParameters = listOf(
                ToolParameterDescriptor("type", "Submission type.", ToolParameterType.String),
                ToolParameterDescriptor("content", "Submission body.", ToolParameterType.String)
            )
        )

        val assistant = client.execute(
            prompt = prompt,
            model = QwenCloudModelResolver.resolve("qwen3.7-plus"),
            tools = listOf(tool)
        )

        val request = json.parseToJsonElement(httpClient.lastRequestBody).jsonObject
        assertEquals("qwen3.7-plus", request["model"]?.jsonPrimitive?.content)
        assertEquals("required", request["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("false", request["enable_thinking"]?.jsonPrimitive?.content)
        val tools = request.getValue("tools").jsonArray
        assertEquals("function", tools.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "write_staging",
            tools.single().jsonObject.getValue("function").jsonObject["name"]?.jsonPrimitive?.content
        )
        val messages = request.getValue("messages").jsonArray
        val assistantMessage = messages.first { element ->
            element.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject
        assertEquals(
            "{\"includeBroadcast\":false}",
            assistantMessage
                .getValue("tool_calls").jsonArray.single().jsonObject
                .getValue("function").jsonObject
                .getValue("arguments").jsonPrimitive.content
        )
        val toolMessage = messages.first { element ->
            element.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }.jsonObject
        assertEquals("call-prev", toolMessage["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("[]", toolMessage["content"]?.jsonPrimitive?.content)

        val toolCall = assertIs<MessagePart.Tool.Call>(assistant.parts.single())
        assertEquals("call-next", toolCall.id)
        assertEquals("write_staging", toolCall.tool)
        assertEquals("{\"type\":\"DOC\",\"content\":\"ok\"}", toolCall.args)
    }

    @Test
    fun `adds explicit qwen cache marker to long stable prompt messages`() = runBlocking {
        val httpClient = CapturingHttpClient(
            responseBody = """
                {
                  "id": "chatcmpl-cache-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": { "role": "assistant", "content": "ok" }
                    }
                  ],
                  "usage": { "prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4 }
                }
            """.trimIndent()
        )
        val client = DashscopeToolCompatibleLLMClient(
            apiKey = "test-key",
            delegate = NoopLLMClient,
            httpClient = httpClient
        )
        val prompt = Prompt(
            id = "dashscope-cache-shape",
            messages = listOf(
                Message.System("stable role prompt ".repeat(300), RequestMetaInfo.create(KoogClock.System)),
                Message.User("dynamic suffix", RequestMetaInfo.create(KoogClock.System))
            )
        )
        val tool = ToolDescriptor(
            name = "read_run_artifact",
            description = "Read uploaded artifacts.",
            requiredParameters = emptyList()
        )

        client.execute(
            prompt = prompt,
            model = QwenCloudModelResolver.resolve("qwen3.7-plus"),
            tools = listOf(tool)
        )

        val request = json.parseToJsonElement(httpClient.lastRequestBody).jsonObject
        val systemMessage = request.getValue("messages").jsonArray.first().jsonObject
        val systemContent = systemMessage.getValue("content").jsonArray
        val cacheControl = systemContent.first().jsonObject.getValue("cache_control").jsonObject
        assertEquals("ephemeral", cacheControl.getValue("type").jsonPrimitive.content)
        assertTrue(request.toString().contains("read_run_artifact"))
    }

    @Test
    fun `uses qwen json object mode for no-tool machine readable emission`() = runBlocking {
        val httpClient = CapturingHttpClient(
            responseBody = """
                {
                  "id": "chatcmpl-json-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": { "role": "assistant", "content": "{\"ok\":true}" }
                    }
                  ],
                  "usage": { "prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6 }
                }
            """.trimIndent()
        )
        val client = DashscopeToolCompatibleLLMClient(
            apiKey = "test-key",
            delegate = NoopLLMClient,
            httpClient = httpClient
        )
        val prompt = Prompt(
            id = "dashscope-json-mode",
            messages = listOf(
                Message.System("Return a valid JSON object matching the schema.", RequestMetaInfo.create(KoogClock.System)),
                Message.User("Emit machine-readable final output.", RequestMetaInfo.create(KoogClock.System))
            )
        )

        val assistant = client.execute(
            prompt = prompt,
            model = QwenCloudModelResolver.resolve("qwen3.7-plus"),
            tools = emptyList()
        )

        val request = json.parseToJsonElement(httpClient.lastRequestBody).jsonObject
        assertEquals("json_object", request.getValue("response_format").jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("false", request["enable_thinking"]?.jsonPrimitive?.content)
        assertTrue("tools" !in request)
        assertEquals("{\"ok\":true}", assistant.textContent())
    }

    @Test
    fun `propagates the exact Council schema through qwen native structured output`() = runBlocking {
        val httpClient = CapturingHttpClient(
            responseBody = """
                {
                  "id": "chatcmpl-schema-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": { "role": "assistant", "content": "{}" }
                    }
                  ],
                  "usage": { "prompt_tokens": 4, "completion_tokens": 1, "total_tokens": 5 }
                }
            """.trimIndent()
        )
        val client = DashscopeToolCompatibleLLMClient(
            apiKey = "test-key",
            delegate = NoopLLMClient,
            httpClient = httpClient
        )
        val councilSchema = CouncilRoleSchemas.forRole(
            Role.CouncilSynthesis,
            CouncilSynthesisWireAuthority(emptyList())
        )
        val prompt = Prompt(
            id = "dashscope-council-output",
            messages = listOf(
                Message.System("Produce the requested response.", RequestMetaInfo.create(KoogClock.System)),
                Message.User("Proceed.", RequestMetaInfo.create(KoogClock.System))
            ),
            params = LLMParams(schema = councilSchema)
        )

        client.execute(
            prompt = prompt,
            model = QwenCloudModelResolver.resolve("qwen3.7-plus"),
            tools = emptyList()
        )

        val request = json.parseToJsonElement(httpClient.lastRequestBody).jsonObject
        val responseFormat = request.getValue("response_format").jsonObject
        val nativeSchema = responseFormat.getValue("json_schema").jsonObject
        assertEquals("json_schema", responseFormat.getValue("type").jsonPrimitive.content)
        assertEquals(councilSchema.name, nativeSchema.getValue("name").jsonPrimitive.content)
        assertEquals("true", nativeSchema.getValue("strict").jsonPrimitive.content)
        assertEquals(councilSchema.schema, nativeSchema.getValue("schema").jsonObject)
        assertEquals("false", request.getValue("enable_thinking").jsonPrimitive.content)
    }

    private object NoopLLMClient : LLMClient() {
        override val clientName: String = "NoopLLMClient"
        override fun llmProvider() = ai.koog.prompt.llm.LLMProvider.Alibaba
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            Message.Assistant("", ResponseMetaInfo.create(KoogClock.System))
        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            emptyList<Message.Assistant>()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderate is not used by this test")
        override suspend fun models(): List<LLModel> = emptyList()
        override suspend fun embed(text: String, model: LLModel): List<Double> = emptyList()
        override suspend fun embed(inputs: List<String>, model: LLModel): List<List<Double>> = emptyList()
        override fun close() = Unit
    }

    private class CapturingHttpClient(private val responseBody: String) : HttpClient() {
        lateinit var lastRequestBody: String

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): HttpResponse<T> {
            lastRequestBody = readUtf8(request.bodyPublisher().orElseThrow())
            @Suppress("UNCHECKED_CAST")
            return FixedHttpResponse(
                request = request,
                body = responseBody as T
            )
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        private fun readUtf8(bodyPublisher: HttpRequest.BodyPublisher): String {
            val bytes = mutableListOf<Byte>()
            val completed = CompletableFuture<Unit>()
            bodyPublisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: ByteBuffer) {
                    while (item.hasRemaining()) {
                        bytes += item.get()
                    }
                }

                override fun onError(throwable: Throwable) {
                    completed.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    completed.complete(Unit)
                }
            })
            completed.join()
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private class FixedHttpResponse<T>(
        private val request: HttpRequest,
        private val body: T
    ) : HttpResponse<T> {
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): T = body
        override fun sslSession() = Optional.empty<javax.net.ssl.SSLSession>()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
