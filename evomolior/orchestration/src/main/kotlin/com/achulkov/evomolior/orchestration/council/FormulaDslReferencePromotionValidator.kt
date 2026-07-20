package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionOutputValidator
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One deterministic failure while proving a Council reference test through production runtime. */
internal data class FormulaDslReferencePromotionFailure(
    val testId: String,
    val message: String
)

/**
 * Executes Council-authored FORMULA_DSL tests through the production materializer and sandbox.
 *
 * This validator intentionally preserves exact JSON representation. An integer expected value
 * does not match a deterministic runtime result represented as a decimal.
 */
internal object FormulaDslReferencePromotionValidator {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun validate(
        opportunity: ToolOpportunity,
        fixtures: List<CouncilPromotionTestFixture>
    ): List<FormulaDslReferencePromotionFailure> {
        val program = opportunity.formulaDslReferenceProgram ?: return emptyList()
        val inputSchema = json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(opportunity.requiredInputs)
        ) as JsonObject
        val outputSchema = json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)
        ) as JsonObject
        val parsedTests = fixtures.sortedBy(CouncilPromotionTestFixture::testId).map { fixture ->
            ParsedReferenceTest(
                testId = fixture.testId,
                inputJson = fixture.input.toString(),
                expectedOutput = fixture.expectedOutput
            )
        }

        val root = runCatching {
            Files.createTempDirectory("council-formula-reference-").toFile()
        }.getOrElse { error ->
            return fixtures.map { fixture ->
                failure(
                    fixture.testId,
                    "production reference-test workspace could not be created: " +
                        error.message.orEmpty().take(200)
                )
            }
        }
        return try {
            val registryEntry = referenceRegistryEntry(program, inputSchema, outputSchema)
            val executable = runCatching {
                ToolMaterializer(root).materializeBlocking(registryEntry)
            }.getOrElse { error ->
                return parsedTests.map { parsed ->
                    failure(
                        parsed.testId,
                        "reference program could not be materialized: " +
                            error.message.orEmpty().take(200)
                    )
                }
            }
            val sandbox = ToolSubSandbox(
                workspaceDir = root.resolve("workspace"),
                maxOutputBytes = opportunity.maximumOutputBytes
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                timeoutMs = opportunity.maximumRuntimeMillis
            )
            parsedTests.mapNotNull { parsed ->
                val result = runCatching {
                    sandbox.executeBlocking(executable.absolutePath, parsed.inputJson)
                }.getOrElse { error ->
                    return@mapNotNull failure(
                        parsed.testId,
                        "reference execution failed: ${error.message.orEmpty().take(200)}"
                    )
                }
                when {
                    result.timedOut ->
                        failure(parsed.testId, "reference execution exceeded the sealed runtime")
                    result.truncated ->
                        failure(
                            parsed.testId,
                            "reference execution exceeded the sealed output bound"
                        )
                    result.exitCode != 0 ->
                        failure(
                            parsed.testId,
                            "reference execution exited ${result.exitCode}: " +
                                result.stderr.take(200)
                        )
                    else -> {
                        val validation = ToolPromotionOutputValidator.validate(
                            stdout = result.stdout,
                            outputSchema = outputSchema,
                            expectedOutput = parsed.expectedOutput,
                            json = json
                        )
                        when (validation.failure) {
                            null -> null
                            "unexpected_output" -> failure(
                                parsed.testId,
                                "exact expectedOutput=${parsed.expectedOutput} does not equal " +
                                    "production reference output=${validation.output}"
                            )
                            else -> failure(
                                parsed.testId,
                                "production reference output failed ${validation.failure}"
                            )
                        }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun referenceRegistryEntry(
        program: ToolDslProgram,
        inputSchema: JsonObject,
        outputSchema: JsonObject
    ): ToolRegistryEntryEntity = ToolRegistryEntryEntity(
        id = "council-formula-reference-v1",
        name = "council-formula-reference",
        version = 1,
        authorAgentId = "genesis-council",
        inputSchemaJson = inputSchema.toString(),
        outputSchemaJson = outputSchema.toString(),
        domain = "council-validation",
        code = json.encodeToString(program),
        codeLanguage = "dsl",
        generation = 0,
        groupId = "genesis-council",
        runId = "genesis-council",
        createdAtMs = 0L
    )

    private fun failure(
        testId: String,
        message: String
    ): FormulaDslReferencePromotionFailure =
        FormulaDslReferencePromotionFailure(testId, message)

    private data class ParsedReferenceTest(
        val testId: String,
        val inputJson: String,
        val expectedOutput: kotlinx.serialization.json.JsonElement
    )
}
