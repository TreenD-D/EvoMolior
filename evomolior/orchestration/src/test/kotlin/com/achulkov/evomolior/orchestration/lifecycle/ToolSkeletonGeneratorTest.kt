package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolSkeletonGeneratorTest {
    @Test
    fun `all supported modes generate deterministic bounded one executable skeletons`() {
        ToolImplementationMode.entries.forEach { mode ->
            val commitment = commitment(mode).withComputedHash()
            val first = ToolSkeletonGenerator.generate(commitment)
            val second = ToolSkeletonGenerator.generate(commitment)

            assertEquals(first, second)
            assertTrue(first.hasValidHash())
            assertEquals(1, first.files.count { it.executable })
            assertTrue(first.files.any { it.relativePath == "contract.json" })
            assertTrue(first.files.any { it.relativePath == "promotion-tests.json" })
            Json.parseToJsonElement(first.inputSchemaJson)
            Json.parseToJsonElement(first.outputSchemaJson)
        }
    }

    @Test
    fun `promotion test ids are serialized as valid JSON strings`() {
        val ids = setOf("line\nbreak", "tab\tvalue", "unicode-\u03bc")
        val skeleton = ToolSkeletonGenerator.generate(
            commitment(ToolImplementationMode.PYTHON)
                .copy(
                    opportunity = commitment(ToolImplementationMode.PYTHON).opportunity.copy(
                        promotionTestIds = ids
                    )
                )
                .withComputedHash()
        )

        val contract = Json.parseToJsonElement(
            skeleton.files.single { it.relativePath == "contract.json" }.content
        ).jsonObject
        val tests = Json.parseToJsonElement(
            skeleton.files.single { it.relativePath == "promotion-tests.json" }.content
        ).jsonObject

        assertEquals(
            ids.sorted(),
            contract.getValue("promotionTestIds").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            ids.sorted(),
            tests.getValue("committedTestIds").jsonArray.map { it.jsonPrimitive.content }
        )
    }

    private fun commitment(mode: ToolImplementationMode) = ToolOpportunityCommitment(
        runId = "run-1",
        atlasHash = "atlas-hash",
        nodeId = NodeId("node-origin"),
        opportunity = ToolOpportunity(
            opportunityId = "opportunity-${mode.name}",
            opportunityType = if (mode == ToolImplementationMode.SIMULATION_DSL) {
                ToolOpportunityType.SIMULATION
            } else {
                ToolOpportunityType.CALCULATION
            },
            repeatedQuestionPattern = "Apply one repeated bounded operation.",
            requiredInputs = listOf(
                TypedField(
                    name = "values",
                    type = TypedFieldType.ARRAY,
                    description = "Input values.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 1,
                    maximumItems = 64,
                    validationRules = setOf("non-empty")
                )
            ) + if (mode == ToolImplementationMode.SIMULATION_DSL) {
                listOf(TypedField("randomSeed", TypedFieldType.INTEGER, "Explicit random seed."))
            } else {
                emptyList()
            },
            requiredOutputs = listOf(
                TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
            ) + if (mode == ToolImplementationMode.SIMULATION_DSL) {
                listOf(
                    TypedField("summary", TypedFieldType.STRING, "Bounded result summary."),
                    TypedField("uncertainty", TypedFieldType.STRING, "Explicit uncertainty.")
                )
            } else {
                emptyList()
            },
            validationStrategy = "Run exact committed boundary cases.",
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = mode,
            behavior = if (mode == ToolImplementationMode.SIMULATION_DSL) {
                ToolBehavior.STOCHASTIC
            } else {
                ToolBehavior.DETERMINISTIC
            },
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("test-b", "test-a"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 8_192,
            assumptions = listOf("Inputs are finite."),
            knownInvalidDomains = listOf("Empty arrays."),
            simulationRecordBindings = if (mode == ToolImplementationMode.SIMULATION_DSL) {
                SimulationRecordBindings(
                    resultSummaryOutputField = "summary",
                    uncertaintyOutputField = "uncertainty",
                    randomSeedInputField = "randomSeed"
                )
            } else {
                null
            }
        )
    )
}
