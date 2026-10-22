package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Deterministically validates staged TOOL submissions before Critic LLM review.
 *
 * @param runId The run identifier used for candidate registry metadata.
 * @param groupId The group identifier used for candidate registry metadata.
 * @param toolsMountHostPath The group's tools mount path; validation uses a sibling temp directory.
 * @param json JSON parser shared with the critic pipeline.
 * @param executionTimeoutSeconds Maximum runtime for each executable test case.
 * @param materializerFactory Factory used to materialise candidate tools into an executable file.
 * @param opportunityResolver Optional exact sealed opportunity lookup. When configured, generic
 * validation cannot pass a candidate that post-Critic promotion would reject deterministically.
 */
class ToolSubmissionValidator(
    private val runId: String,
    private val groupId: String,
    private val toolsMountHostPath: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val executionTimeoutSeconds: Long = 5,
    private val materializerFactory: (File) -> ToolMaterializer = ::ToolMaterializer,
    private val opportunityResolver:
        (suspend (StagingSubmissionEntity) -> ToolOpportunityCommitment?)? = null
) {
    private val promotionContractValidator = ToolPromotionContractValidator()

    /**
     * Validates [submission] by checking payload shape, JSON schema shape, executable tests,
     * process exit status, JSON stdout, output schema, and optional exact expected output.
     *
     * @param submission The staged TOOL submission to validate.
     * @return [ToolSubmissionValidationResult.Valid] or [ToolSubmissionValidationResult.Invalid].
     */
    suspend fun validate(submission: StagingSubmissionEntity): ToolSubmissionValidationResult {
        val toolPayload = runCatching {
            StrictToolPayloadJson.parseToJsonElement(submission.content) as? JsonObject
        }.getOrNull()
            ?: return invalid("tool_validation_invalid_json: TOOL submissions must be JSON objects.")
        val exactCommitment = opportunityResolver?.let { resolver ->
            resolver(submission)
                ?: return invalid("tool_promotion_unknown_opportunity")
        }
        if (exactCommitment != null) {
            if (!exactCommitment.hasValidHash() ||
                exactCommitment.runId != runId ||
                exactCommitment.nodeId.value != submission.wipTaskRef
            ) {
                return invalid("tool_promotion_invalid_atlas_provenance")
            }
            when (
                val result = promotionContractValidator.validate(toolPayload, exactCommitment)
            ) {
                is ToolPromotionContractValidationResult.Accepted -> Unit
                is ToolPromotionContractValidationResult.Rejected ->
                    return invalid(result.reason)
            }
        }
        val toolName = toolPayload["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
            ?: return invalid("tool_validation_invalid_name: TOOL name must be 1-80 characters of letters, numbers, dot, underscore, or dash.")
        val language = toolPayload["language"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "shell"
        if (language !in setOf("shell", "python", "kotlin", "dsl")) {
            return invalid("tool_validation_unsupported_language: Unsupported tool language '$language'.")
        }
        val submittedCode = toolPayload["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return invalid("tool_validation_missing_code: TOOL submissions require non-empty code.")
        val code = if (language == "dsl") {
            val program = runCatching {
                json.decodeFromString(ToolDslProgram.serializer(), submittedCode)
            }.getOrNull() ?: return invalid(
                "tool_validation_invalid_dsl: DSL code must be a closed typed AST."
            )
            json.encodeToString(ToolDslProgram.serializer(), program)
        } else {
            submittedCode
        }
        val inputSchema = toolPayload["inputSchema"] as? JsonObject
            ?: return invalid("tool_validation_missing_input_schema: TOOL submissions require an object inputSchema.")
        val outputSchema = toolPayload["outputSchema"] as? JsonObject
            ?: return invalid("tool_validation_missing_output_schema: TOOL submissions require an object outputSchema.")
        validateJsonSchemaShape(inputSchema, "input")?.let { return invalid(it) }
        validateJsonSchemaShape(outputSchema, "output")?.let { return invalid(it) }

        val tests = parseToolTests(toolPayload)
        if (tests.isEmpty()) {
            return invalid("tool_validation_missing_tests: TOOL submissions require at least one executable test case.")
        }

        val validationRoot = withContext(Dispatchers.IO) {
            Files.createTempDirectory(
                File(toolsMountHostPath).parentFile?.toPath(),
                "critic-tool-validation-"
            ).toFile()
        }
        return try {
            val candidate = ToolRegistryEntryEntity(
                id = "candidate-${submission.id}",
                name = toolName,
                version = 0,
                authorAgentId = submission.agentId,
                inputSchemaJson = inputSchema.toString(),
                outputSchemaJson = outputSchema.toString(),
                domain = toolPayload["domain"]?.jsonPrimitive?.contentOrNull ?: "general",
                code = code,
                codeLanguage = language,
                generation = submission.generation,
                groupId = groupId,
                runId = runId,
                createdAtMs = System.currentTimeMillis()
            )
            val executable = materializerFactory(validationRoot).materialize(candidate)
            for ((index, test) in tests.withIndex()) {
                val failure = validateJsonAgainstSchema(test.input, inputSchema, "test[$index].input")
                    ?: executeToolValidationCase(
                        executable = executable,
                        test = test,
                        outputSchema = outputSchema,
                        index = index,
                        maximumRuntimeMillis = exactCommitment
                            ?.opportunity
                            ?.maximumRuntimeMillis
                            ?: TimeUnit.SECONDS.toMillis(executionTimeoutSeconds),
                        maximumOutputBytes = exactCommitment
                            ?.opportunity
                            ?.maximumOutputBytes
                            ?: GENERIC_MAXIMUM_OUTPUT_BYTES
                    )
                if (failure != null) return invalid(failure)
            }
            ToolSubmissionValidationResult.Valid
        } catch (e: Exception) {
            invalid("tool_validation_execution_error: ${e.message?.take(300).orEmpty()}")
        } finally {
            withContext(Dispatchers.IO) { validationRoot.deleteRecursively() }
        }
    }

    private fun invalid(reason: String): ToolSubmissionValidationResult.Invalid =
        ToolSubmissionValidationResult.Invalid(reason)

    private data class ToolValidationCase(
        val input: JsonElement,
        val expectedOutput: JsonElement?
    )

    private fun parseToolTests(toolPayload: JsonObject): List<ToolValidationCase> {
        val tests = toolPayload["tests"] as? JsonArray ?: return emptyList()
        return tests.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val input = obj["input"] ?: obj["args"] ?: return@mapNotNull null
            ToolValidationCase(input = input, expectedOutput = obj["expectedOutput"])
        }
    }

    private fun validateJsonSchemaShape(schema: JsonObject, label: String): String? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        if (type != "object") return "tool_validation_${label}_schema_not_object: $label schema must declare type=object."
        val properties = schema["properties"]
        if (properties != null && properties !is JsonObject) {
            return "tool_validation_${label}_schema_invalid_properties: properties must be an object."
        }
        val required = schema["required"]
        if (required != null && required !is JsonArray) {
            return "tool_validation_${label}_schema_invalid_required: required must be an array."
        }
        return null
    }

    private suspend fun executeToolValidationCase(
        executable: File,
        test: ToolValidationCase,
        outputSchema: JsonObject,
        index: Int,
        maximumRuntimeMillis: Long,
        maximumOutputBytes: Long
    ): String? = withContext(Dispatchers.IO) {
        val sandbox = ToolSubSandbox(
            workspaceDir = executable.parentFile.resolve("workspace"),
            maxOutputBytes = maximumOutputBytes
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            timeoutMs = maximumRuntimeMillis
        )
        val result = sandbox.execute(executable.absolutePath, test.input.toString())
        if (result.timedOut) {
            return@withContext "tool_validation_test_timeout: test[$index] exceeded " +
                "$maximumRuntimeMillis milliseconds."
        }
        if (result.truncated) {
            return@withContext "tool_validation_output_limit_exceeded: test[$index] exceeded " +
                "$maximumOutputBytes output bytes."
        }
        if (result.exitCode != 0) {
            return@withContext "tool_validation_test_failed: test[$index] exited " +
                "${result.exitCode}; stderr=${result.stderr.take(300)}"
        }
        val parsedOutput = runCatching {
            json.parseToJsonElement(result.stdout.trim())
        }.getOrNull()
            ?: return@withContext "tool_validation_output_not_json: test[$index] stdout must be JSON."
        validateJsonAgainstSchema(parsedOutput, outputSchema, "test[$index].output")?.let {
            return@withContext it
        }
        TypedToolSchemaValidator.canonicalOutputFailure(
            parsedOutput,
            outputSchema
        )?.let { failure ->
            return@withContext "tool_validation_noncanonical_output: test[$index].output " +
                "failed canonical numeric representation: ${failure.stableCode}."
        }
        if (test.expectedOutput != null && parsedOutput != test.expectedOutput) {
            return@withContext "tool_validation_unexpected_output: test[$index] output did not " +
                "match expectedOutput."
        }
        null
    }

    private fun validateJsonAgainstSchema(
        value: JsonElement,
        schema: JsonObject,
        label: String
    ): String? = TypedToolSchemaValidator.validationError(value, schema)?.let { reason ->
        "tool_validation_schema_mismatch: $label failed sealed schema validation: $reason."
    }

    private companion object {
        const val GENERIC_MAXIMUM_OUTPUT_BYTES = 50_000L
    }
}

/** Validation result for [ToolSubmissionValidator]. */
sealed class ToolSubmissionValidationResult {
    /** The staged TOOL passed deterministic validation and may proceed to Critic review. */
    data object Valid : ToolSubmissionValidationResult()

    /**
     * The staged TOOL failed deterministic validation.
     *
     * @param reason Machine-readable rejection reason with human context.
     */
    data class Invalid(val reason: String) : ToolSubmissionValidationResult()
}
