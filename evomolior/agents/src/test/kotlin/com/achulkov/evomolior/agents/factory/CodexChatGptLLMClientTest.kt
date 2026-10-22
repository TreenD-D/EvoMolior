package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CodexChatGptLLMClientTest {
    @Test
    fun `failure diagnostics keep the tail of a long transcript and short streams verbatim`() {
        assertEquals("exit: auth expired", boundedCodexFailureDiagnostic("  exit: auth expired\n"))

        val transcript = (1..5_000).joinToString("\n") { """{"type":"item","n":$it}""" } +
            "\nERROR: No space left on device"
        val bounded = boundedCodexFailureDiagnostic(transcript)

        assertTrue(bounded.length < CODEX_FAILURE_DIAGNOSTIC_MAX_CHARS + 64)
        assertTrue(bounded.startsWith("[... "))
        assertTrue(bounded.endsWith("ERROR: No space left on device"))
    }

    private val model = CodexChatGptModelResolver.resolve(
        ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
    )

    @Test
    fun `mandatory search succeeds on first pass without retry`() = runBlocking {
        val prompts = mutableListOf<String>()
        val events = mutableListOf<CodexSearchEvent>()
        val client = client(
            results = listOf(success(searchEvents = listOf(searchEvent("first-pass")))),
            prompts = prompts,
            observedEvents = events
        )

        val response = client.execute(requiredSearchPrompt(), model, emptyList())

        assertEquals("completion-1", response.textContent())
        assertEquals(1, prompts.size)
        assertEquals(listOf("first-pass"), events.map { it.query })
    }

    @Test
    fun `mandatory search retries once and accepts retry search events`() = runBlocking {
        val prompts = mutableListOf<String>()
        val events = mutableListOf<CodexSearchEvent>()
        val client = client(
            results = listOf(
                success(),
                success(searchEvents = listOf(searchEvent("retry-pass")))
            ),
            prompts = prompts,
            observedEvents = events
        )

        val response = client.execute(requiredSearchPrompt(), model, emptyList())

        assertEquals("completion-2", response.textContent())
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("MANDATORY RECOVERY"))
        assertEquals(listOf("retry-pass"), events.map { it.query })
    }

    @Test
    fun `mandatory search fails closed after retry also omits search and cleans output`() = runBlocking {
        val prompts = mutableListOf<String>()
        val outputPaths = mutableListOf<Path>()
        val client = client(
            results = listOf(success(), success()),
            prompts = prompts,
            outputPaths = outputPaths
        )

        val failure = assertFailsWith<CodexNativeSearchRequiredException> {
            client.execute(requiredSearchPrompt(), model, emptyList())
        }

        assertEquals(model.id, failure.modelId)
        assertEquals(2, prompts.size)
        assertTrue(outputPaths.isNotEmpty())
        assertTrue(outputPaths.all { path -> !path.toFile().exists() })
    }

    @Test
    fun `prompt without mandatory marker remains single pass when search is omitted`() = runBlocking {
        val prompts = mutableListOf<String>()
        val schemaPaths = mutableListOf<Path?>()
        val timeouts = mutableListOf<Duration>()
        val client = client(
            results = listOf(success()),
            prompts = prompts,
            schemaPaths = schemaPaths,
            observedTimeouts = timeouts
        )

        val response = client.execute(
            prompt("ordinary") { user("Summarise the supplied text.") },
            model,
            emptyList()
        )

        assertEquals("completion-1", response.textContent())
        assertEquals(1, prompts.size)
        assertEquals(listOf<Path?>(null), schemaPaths)
        assertEquals(listOf(30.minutes), timeouts)
        assertFalse(prompts.single().contains(CODEX_NATIVE_SEARCH_REQUIRED_MARKER))
    }

    @Test
    fun `json schema is materialized exactly and deleted after success`() = runBlocking {
        val expectedSchema = LLMParams.Schema.JSON.Standard(
            name = "BoundedResponse",
            schema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("result", buildJsonObject { put("type", "string") })
                    }
                )
            }
        )
        val schemaPaths = mutableListOf<Path?>()
        val observedSchemas = mutableListOf<String>()
        val observedRequests = mutableListOf<CodexProcessRequest>()
        val client = client(
            results = listOf(success()),
            prompts = mutableListOf(),
            schemaPaths = schemaPaths,
            observedSchemas = observedSchemas,
            observedRequests = observedRequests
        )

        val response = client.execute(
            prompt(
                id = "schema-success",
                params = LLMParams(schema = expectedSchema)
            ) {
                user("Return the bounded response.")
            },
            model,
            emptyList()
        )

        assertEquals("completion-1", response.textContent())
        assertEquals(listOf(expectedSchema.schema.toString()), observedSchemas)
        val schemaPath = schemaPaths.single()
        assertTrue(schemaPath != null)
        assertFalse(schemaPath.exists())
        val request = observedRequests.single()
        assertTrue(request.structuredOutputOnly)
        assertFalse(request.nativeSearchEnabled)
        assertFalse(request.prompt.contains("EVOMOLIOR CODE MODE"))
    }

    @Test
    fun `json schema file is deleted after provider failure`() = runBlocking {
        val schema = LLMParams.Schema.JSON.Standard(
            name = "FailureResponse",
            schema = buildJsonObject { put("type", "object") }
        )
        val schemaPaths = mutableListOf<Path?>()
        val client = client(
            results = listOf(
                CodexProcessResult(exitCode = 1, stdout = "", stderr = "provider failed")
            ),
            prompts = mutableListOf(),
            schemaPaths = schemaPaths
        )

        assertFailsWith<IllegalStateException> {
            client.execute(
                prompt("schema-failure", LLMParams(schema = schema)) {
                    user("Return the bounded response.")
                },
                model,
                emptyList()
            )
        }

        val schemaPath = schemaPaths.single()
        assertTrue(schemaPath != null)
        assertFalse(schemaPath.exists())
    }

    @Test
    fun `structured model capacity failure receives bounded backoff retries`() = runBlocking {
        val prompts = mutableListOf<String>()
        val retryAttempts = mutableListOf<Int>()
        val schemaPaths = mutableListOf<Path?>()
        val schema = LLMParams.Schema.JSON.Standard(
            name = "CapacityResponse",
            schema = buildJsonObject { put("type", "object") }
        )
        val timeouts = mutableListOf<Duration>()
        val client = client(
            results = listOf(capacityFailure(), capacityFailure(), success()),
            prompts = prompts,
            schemaPaths = schemaPaths,
            observedTimeouts = timeouts,
            executionTimeout = 45.minutes,
            capacityRetryDelay = retryAttempts::add
        )

        val response = client.execute(
            prompt("capacity-retry", LLMParams(schema = schema)) {
                user("Evaluate this bounded artifact.")
            },
            model,
            emptyList()
        )

        assertEquals("completion-3", response.textContent())
        assertEquals(3, prompts.size)
        assertEquals(listOf(1, 2), retryAttempts)
        assertEquals(List(3) { 45.minutes }, timeouts)
        assertEquals(1, schemaPaths.filterNotNull().distinct().size)
        assertTrue(schemaPaths.all { it != null })
        assertFalse(schemaPaths.filterNotNull().distinct().single().exists())
    }

    @Test
    fun `invalid structured response retains bounded owner-only schema evidence`() = runBlocking {
        val diagnosticRoot = createTempDirectory("codex-structured-diagnostics-")
        val schema = LLMParams.Schema.JSON.Standard(
            name = "DiagnosticResponse",
            schema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("result", buildJsonObject { put("type", "string") })
                    }
                )
                put(
                    "required",
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("result"))
                    }
                )
                put("additionalProperties", false)
            }
        )
        val expectedSchema = schema.schema.toString()
        try {
            val client = client(
                results = listOf(success()),
                prompts = mutableListOf(),
                structuredOutputDiagnosticRoot = diagnosticRoot
            )

            client.execute(
                prompt("council-CouncilSynthesis", LLMParams(schema = schema)) {
                    user("SUPER_SECRET_PROMPT_CONTENT")
                },
                model,
                emptyList()
            )

            val recordRoot = Files.list(diagnosticRoot).use { it.toList().single() }
            assertEquals(
                expectedSchema,
                recordRoot.resolve("output-schema.json").readText(Charsets.UTF_8)
            )
            assertEquals(
                "completion-1",
                recordRoot.resolve("raw-response.json").readText(Charsets.UTF_8)
            )
            val manifest = Json.parseToJsonElement(
                recordRoot.resolve("manifest.json").readText(Charsets.UTF_8)
            ).jsonObject
            assertEquals(
                CodexOutputSchemaDocument.from(schema).sha256,
                manifest.getValue("schemaSha256").jsonPrimitive.content
            )
            assertEquals(
                CodexStructuredOutputValidator.sha256("completion-1".toByteArray()),
                manifest.getValue("responseSha256").jsonPrimitive.content
            )
            assertTrue(manifest.getValue("responseRetained").jsonPrimitive.content.toBoolean())
            assertFalse(
                Files.walk(recordRoot).use { paths ->
                    paths.filter(Files::isRegularFile)
                        .map { it.readText(Charsets.UTF_8) }
                        .anyMatch { "SUPER_SECRET_PROMPT_CONTENT" in it }
                }
            )
            assertOwnerOnlyDirectory(diagnosticRoot)
            assertOwnerOnlyDirectory(recordRoot)
            Files.list(recordRoot).use { paths ->
                paths.filter(Files::isRegularFile).forEach(::assertOwnerOnlyFile)
            }
            assertFalse(
                Files.list(diagnosticRoot).use { paths ->
                    paths.anyMatch { it.fileName.toString().startsWith(".record-") }
                }
            )
        } finally {
            diagnosticRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `oversized structured response is hashed but not retained`() {
        val diagnosticRoot = createTempDirectory("codex-structured-diagnostics-bound-")
        val schema = CodexOutputSchemaDocument.from(
            LLMParams.Schema.JSON.Standard(
                name = "Bounded",
                schema = buildJsonObject { put("type", "string") }
            )
        )
        try {
            val response = ByteArray(
                CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_RESPONSE_BYTES + 1
            ) { 'x'.code.toByte() }
            val record = requireNotNull(
                CodexStructuredOutputDiagnosticRecorder(
                    root = diagnosticRoot,
                    maximumRecords = 1
                ).record(
                    promptId = "council-CouncilSynthesis",
                    schema = schema,
                    response = response,
                    violations = listOf("wrong_type_root")
                )
            )

            assertFalse(record.responseRetained)
            assertFalse(record.root.resolve("raw-response.json").exists())
            assertTrue(record.root.resolve("manifest.json").exists())
        } finally {
            diagnosticRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `raw response is omitted when owner-only file permissions cannot be established`() {
        val diagnosticRoot = createTempDirectory("codex-structured-diagnostics-permissions-")
        val schema = CodexOutputSchemaDocument.from(
            LLMParams.Schema.JSON.Standard(
                name = "PermissionFallback",
                schema = buildJsonObject { put("type", "string") }
            )
        )
        val permissionEnforcer = object : CodexDiagnosticPermissionEnforcer {
            override fun enforceDirectory(path: Path) {
                enforceOwnerOnlyDirectory(path)
            }

            override fun enforceFile(path: Path) {
                if (path.fileName.toString().contains("raw-response")) {
                    throw UnsupportedOperationException("simulated permission failure")
                }
                enforceOwnerOnlyFile(path)
            }
        }
        try {
            val record = requireNotNull(
                CodexStructuredOutputDiagnosticRecorder(
                    root = diagnosticRoot,
                    maximumRecords = 1,
                    permissions = permissionEnforcer
                ).record(
                    promptId = "council-CouncilSynthesis",
                    schema = schema,
                    response = "invalid".toByteArray(),
                    violations = listOf("wrong_type_root")
                )
            )

            assertFalse(record.responseRetained)
            assertFalse(record.root.resolve("raw-response.json").exists())
            val manifest = Json.parseToJsonElement(
                record.root.resolve("manifest.json").readText(Charsets.UTF_8)
            ).jsonObject
            assertFalse(manifest.getValue("responseRetained").jsonPrimitive.content.toBoolean())
            assertOwnerOnlyDirectory(record.root)
            assertOwnerOnlyFile(record.root.resolve("output-schema.json"))
            assertOwnerOnlyFile(record.root.resolve("manifest.json"))
        } finally {
            diagnosticRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed diagnostic publication does not consume successful record capacity`() {
        val diagnosticRoot = createTempDirectory("codex-structured-diagnostics-retry-")
        val schema = CodexOutputSchemaDocument.from(
            LLMParams.Schema.JSON.Standard(
                name = "RetryAfterFailure",
                schema = buildJsonObject { put("type", "string") }
            )
        )
        var rejectManifest = true
        val permissionEnforcer = object : CodexDiagnosticPermissionEnforcer {
            override fun enforceDirectory(path: Path) {
                enforceOwnerOnlyDirectory(path)
            }

            override fun enforceFile(path: Path) {
                if (rejectManifest && path.fileName.toString().contains("manifest")) {
                    rejectManifest = false
                    throw UnsupportedOperationException("simulated manifest permission failure")
                }
                enforceOwnerOnlyFile(path)
            }
        }
        try {
            val recorder = CodexStructuredOutputDiagnosticRecorder(
                root = diagnosticRoot,
                maximumRecords = 1,
                permissions = permissionEnforcer
            )
            assertFailsWith<UnsupportedOperationException> {
                recorder.record(
                    promptId = "first",
                    schema = schema,
                    response = "invalid-first".toByteArray(),
                    violations = listOf("wrong_type_root")
                )
            }

            val record = requireNotNull(
                recorder.record(
                    promptId = "second",
                    schema = schema,
                    response = "invalid-second".toByteArray(),
                    violations = listOf("wrong_type_root")
                )
            )

            assertTrue(record.root.fileName.toString().endsWith("-second"))
            assertEquals(
                1,
                Files.list(diagnosticRoot).use { paths ->
                    paths.filter(Files::isDirectory).count()
                }
            )
        } finally {
            diagnosticRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `owner-verified stale temporary records are reclaimed before publication`() {
        val diagnosticRoot = createTempDirectory("codex-structured-diagnostics-stale-")
        val staleRoot = Files.createDirectory(diagnosticRoot.resolve(".record-stale"))
        val staleSchema = Files.writeString(
            staleRoot.resolve("output-schema.json"),
            """{"type":"string"}"""
        )
        enforceOwnerOnlyDirectory(staleRoot)
        enforceOwnerOnlyFile(staleSchema)
        val schema = CodexOutputSchemaDocument.from(
            LLMParams.Schema.JSON.Standard(
                name = "AfterStaleRecord",
                schema = buildJsonObject { put("type", "string") }
            )
        )
        try {
            val record = requireNotNull(
                CodexStructuredOutputDiagnosticRecorder(
                    root = diagnosticRoot,
                    maximumRecords = 1
                ).record(
                    promptId = "after-stale",
                    schema = schema,
                    response = "invalid".toByteArray(),
                    violations = listOf("wrong_type_root")
                )
            )

            assertFalse(staleRoot.exists())
            assertTrue(record.root.exists())
            assertEquals(
                1,
                Files.list(diagnosticRoot).use { paths ->
                    paths.filter(Files::isDirectory).count()
                }
            )
        } finally {
            diagnosticRoot.toFile().deleteRecursively()
        }
    }

    private fun requiredSearchPrompt() = prompt("required-search") {
        user("$CODEX_NATIVE_SEARCH_REQUIRED_MARKER\nResearch current public evidence.")
    }

    private fun client(
        results: List<CodexProcessResult>,
        prompts: MutableList<String>,
        observedEvents: MutableList<CodexSearchEvent> = mutableListOf(),
        outputPaths: MutableList<Path> = mutableListOf(),
        schemaPaths: MutableList<Path?> = mutableListOf(),
        observedSchemas: MutableList<String> = mutableListOf(),
        observedTimeouts: MutableList<Duration> = mutableListOf(),
        observedRequests: MutableList<CodexProcessRequest> = mutableListOf(),
        structuredOutputDiagnosticRoot: Path? = null,
        executionTimeout: Duration = 30.minutes,
        capacityRetryDelay: suspend (Int) -> Unit = {}
    ): CodexChatGptLLMClient {
        var invocation = 0
        return CodexChatGptLLMClient(
            cliPath = "/unused/codex",
            onSearchEvents = { observedEvents += it },
            executionTimeout = executionTimeout,
            structuredOutputDiagnosticRoot = structuredOutputDiagnosticRoot,
            processRunner = CodexProcessRunner { request ->
                observedRequests += request
                outputPaths.add(request.outputPath)
                schemaPaths.add(request.outputSchemaPath)
                observedTimeouts += request.executionTimeout
                request.outputSchemaPath?.let { observedSchemas.add(it.readText(Charsets.UTF_8)) }
                prompts.add(request.prompt)
                val result = results[invocation]
                invocation += 1
                request.outputPath.toFile().writeText("completion-$invocation")
                result
            },
            capacityRetryDelay = capacityRetryDelay
        )
    }

    private fun success(
        searchEvents: List<CodexSearchEvent> = emptyList()
    ): CodexProcessResult = CodexProcessResult(
        exitCode = 0,
        stdout = "",
        stderr = "",
        searchEvents = searchEvents
    )

    private fun capacityFailure(): CodexProcessResult = CodexProcessResult(
        exitCode = 1,
        stdout = """
            {"type":"error","message":"Selected model is at capacity. Please try a different model."}
            {"type":"turn.failed","error":{"message":"Selected model is at capacity. Please try a different model."}}
        """.trimIndent(),
        stderr = ""
    )

    private fun searchEvent(query: String) = CodexSearchEvent(
        query = query,
        queries = listOf(query),
        rawJson = """{"type":"item.completed","item":{"type":"web_search","query":"$query"}}"""
    )

    private fun assertOwnerOnlyDirectory(path: Path) {
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ),
            Files.getPosixFilePermissions(path)
        )
    }

    private fun assertOwnerOnlyFile(path: Path) {
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ),
            Files.getPosixFilePermissions(path)
        )
    }

    private fun enforceOwnerOnlyDirectory(path: Path) {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )
    }

    private fun enforceOwnerOnlyFile(path: Path) {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
        )
    }
}
