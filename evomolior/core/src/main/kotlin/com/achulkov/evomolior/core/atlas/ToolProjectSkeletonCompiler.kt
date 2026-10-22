package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure deterministic compiler for bounded tool projects derived from sealed Atlas opportunities.
 *
 * Keeping this compiler in core lets every authority boundary produce the same project without
 * introducing an orchestration dependency into worker-view or prompt contracts.
 */
object ToolProjectSkeletonCompiler {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /**
     * Generates the exact implementation and promotion-test project for [commitment].
     *
     * @param commitment Valid sealed opportunity provenance.
     * @return Deterministically hashed project skeleton.
     */
    fun compile(commitment: ToolOpportunityCommitment): ToolProjectSkeleton {
        require(commitment.hasValidHash()) {
            "Tool skeleton requires a valid opportunity commitment"
        }
        val opportunity = commitment.opportunity
        val inputSchema = TypedToolSchemaCompiler.compile(opportunity.requiredInputs)
        val outputSchema = TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)
        val implementationFiles = when (opportunity.preferredImplementationMode) {
            ToolImplementationMode.SHELL -> listOf(
                ToolSkeletonFile(
                    relativePath = "tool.sh",
                    content = shellTemplate(opportunity.maximumOutputBytes),
                    executable = true
                )
            )
            ToolImplementationMode.PYTHON -> listOf(
                ToolSkeletonFile(
                    relativePath = "tool.py",
                    content = pythonTemplate(opportunity.maximumOutputBytes),
                    executable = true
                )
            )
            ToolImplementationMode.FORMULA_DSL,
            ToolImplementationMode.RULE_DSL,
            ToolImplementationMode.SIMULATION_DSL -> dslFiles(
                opportunity
            )
        }
        val contract = ToolSkeletonFile(
            relativePath = "contract.json",
            content = """
                {
                  "inputSchema": $inputSchema,
                  "maximumOutputBytes": ${opportunity.maximumOutputBytes},
                  "maximumRuntimeMillis": ${opportunity.maximumRuntimeMillis},
                  "outputSchema": $outputSchema,
                  "promotionTestIds": ${jsonStrings(opportunity.promotionTestIds)}
                }
            """.trimIndent()
        )
        val tests = ToolSkeletonFile(
            relativePath = "promotion-tests.json",
            content = """
                {
                  "committedTestIds": ${jsonStrings(opportunity.promotionTestIds)},
                  "cases": []
                }
            """.trimIndent()
        )
        return ToolProjectSkeleton(
            skeletonId = "skeleton-${commitment.commitmentHash.take(24)}",
            opportunityCommitmentHash = commitment.commitmentHash,
            implementationMode = opportunity.preferredImplementationMode,
            inputSchemaJson = inputSchema,
            outputSchemaJson = outputSchema,
            files = implementationFiles + listOf(contract, tests)
        ).withComputedHash()
    }

    private fun shellTemplate(maximumOutputBytes: Long): String = """
        #!/bin/sh
        set -eu
        # Read one JSON object from stdin and emit one JSON object no larger than $maximumOutputBytes bytes.
        input_json=${'$'}(cat)
        run_tool() {
          printf '%s\n' '{"error":"implementation_required"}'
        }
        run_tool "${'$'}input_json"
    """.trimIndent()

    private fun pythonTemplate(maximumOutputBytes: Long): String = """
        #!/usr/bin/env python3
        import json
        import sys

        MAXIMUM_OUTPUT_BYTES = $maximumOutputBytes

        def run_tool(input_value):
            # Every accepted decision-relevant field, including nested values, must affect a
            # declared output or be rejected/removed from the accepted input contract.
            raise NotImplementedError("Implement exactly one bounded tool function")

        value = run_tool(json.load(sys.stdin))
        encoded = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
        if len(encoded) > MAXIMUM_OUTPUT_BYTES:
            raise ValueError("output exceeds committed bound")
        sys.stdout.buffer.write(encoded)
    """.trimIndent()

    private fun dslFiles(
        opportunity: ToolOpportunity
    ): List<ToolSkeletonFile> {
        val mode = opportunity.preferredImplementationMode
        val kind = when (mode) {
            ToolImplementationMode.FORMULA_DSL -> ToolDslKind.FORMULA
            ToolImplementationMode.RULE_DSL -> ToolDslKind.RULE
            ToolImplementationMode.SIMULATION_DSL -> ToolDslKind.SIMULATION
            else -> error("Non-DSL mode cannot generate a DSL skeleton")
        }
        val program = when (mode) {
            ToolImplementationMode.FORMULA_DSL ->
                requireNotNull(opportunity.formulaDslReferenceProgram) {
                    "FORMULA_DSL skeleton requires a Council-authored reference program"
                }

            ToolImplementationMode.RULE_DSL,
            ToolImplementationMode.SIMULATION_DSL -> ToolDslProgram(
                kind = kind,
                outputs = opportunity.requiredOutputs.sortedBy(TypedField::name).map { field ->
                    ToolDslOutput(field.name, ToolDslExpression.Literal(field.placeholder))
                },
                randomSeedInputField = opportunity.simulationRecordBindings?.randomSeedInputField
            )

            else -> error("Non-DSL mode cannot generate a DSL skeleton")
        }
        val source = json.encodeToString(program)
        val sourceName = when (kind) {
            ToolDslKind.FORMULA -> "formula.json"
            ToolDslKind.RULE -> "rules.json"
            ToolDslKind.SIMULATION -> "simulation.json"
        }
        return listOf(
            ToolSkeletonFile(relativePath = sourceName, content = source),
            ToolSkeletonFile(
                relativePath = "run-dsl.py",
                content = ToolDslPythonRuntime.render(source),
                executable = true
            )
        )
    }

    private val TypedField.placeholder: kotlinx.serialization.json.JsonElement
        get() = when (type) {
            TypedFieldType.STRING -> JsonPrimitive(enumValues.sorted().firstOrNull().orEmpty())
            TypedFieldType.INTEGER -> JsonPrimitive(0)
            TypedFieldType.NUMBER -> JsonPrimitive(0.0)
            TypedFieldType.BOOLEAN -> JsonPrimitive(false)
            TypedFieldType.ARRAY -> JsonArray(
                List(requireNotNull(minimumItems)) { arrayItemPlaceholder }
            )
            TypedFieldType.JSON -> JsonObject(emptyMap())
            TypedFieldType.OBJECT -> objectPlaceholder
        }

    private val TypedField.arrayItemPlaceholder: kotlinx.serialization.json.JsonElement
        get() = when (requireNotNull(itemType)) {
            TypedFieldType.STRING -> JsonPrimitive("")
            TypedFieldType.INTEGER -> JsonPrimitive(0)
            TypedFieldType.NUMBER -> JsonPrimitive(0.0)
            TypedFieldType.BOOLEAN -> JsonPrimitive(false)
            TypedFieldType.JSON -> JsonObject(emptyMap())
            TypedFieldType.OBJECT -> objectPlaceholder
            TypedFieldType.ARRAY -> error("Nested arrays are not a typed-field item")
        }

    private val TypedField.objectPlaceholder: JsonObject
        get() = JsonObject(
            objectFields.filter(TypedField::required)
                .sortedBy(TypedField::name)
                .associate { field -> field.name to field.placeholder }
        )

    private fun jsonStrings(values: Collection<String>): String =
        json.encodeToString(JsonArray(values.sorted().map(::JsonPrimitive)))
}
