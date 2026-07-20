package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.SealedTestDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouncilPromotionTestContractValidatorTest {
    @Test
    fun `v68 rejects non-object promotion definitions exactly and accepts an object`() {
        val tests = listOf(
            test("array-definition", "[]"),
            test("boolean-definition", "true"),
            test("null-definition", "null"),
            test("string-definition", "\"fixture\""),
            test("valid-object", definition(expectedValues = 72))
        )

        val result = CouncilPromotionTestContractValidator.validate(
            opportunity = opportunity(tests.mapTo(linkedSetOf(), SealedTestDraft::testKey)),
            tests = tests
        )

        assertEquals(listOf("valid-object"), result.fixtures.map { it.testId })
        assertEquals(
            listOf(
                CouncilPromotionTestContractFailure(
                    testId = "array-definition",
                    component = CouncilPromotionTestComponent.DEFINITION,
                    message = "test definition must be one JSON object"
                ),
                CouncilPromotionTestContractFailure(
                    testId = "boolean-definition",
                    component = CouncilPromotionTestComponent.DEFINITION,
                    message = "test definition must be one JSON object"
                ),
                CouncilPromotionTestContractFailure(
                    testId = "null-definition",
                    component = CouncilPromotionTestComponent.DEFINITION,
                    message = "test definition must be one JSON object"
                ),
                CouncilPromotionTestContractFailure(
                    testId = "string-definition",
                    component = CouncilPromotionTestComponent.DEFINITION,
                    message = "test definition must be one JSON object"
                )
            ),
            result.failures
        )
    }

    @Test
    fun `generic fixtures enforce exact cardinality scalar shape and nested objects`() {
        val tests = listOf(
            test("valid", definition(expectedValues = 72)),
            test("short", definition(expectedValues = 71)),
            test(
                "scalar",
                """{"input":{"scenario":{"hours":72}},"expectedOutput":{"hourly_load":1.0}}"""
            ),
            test(
                "missing-nested",
                """{"input":{"scenario":{}},"expectedOutput":{"hourly_load":${values(72)}}}"""
            ),
            test(
                "extra-nested",
                """
                    {
                      "input":{"scenario":{"hours":72,"unknown":true}},
                      "expectedOutput":{"hourly_load":${values(72)}}
                    }
                """.trimIndent()
            )
        )

        val result = CouncilPromotionTestContractValidator.validate(
            opportunity = opportunity(tests.mapTo(linkedSetOf(), SealedTestDraft::testKey)),
            tests = tests
        )

        assertEquals(listOf("valid"), result.fixtures.map { it.testId })
        assertEquals(
            setOf("short", "scalar", "missing-nested", "extra-nested"),
            result.failures.mapTo(linkedSetOf()) { it.testId }
        )
    }

    @Test
    fun `paired fixture failures expose both exact cardinalities and freeze valid counterparts`() {
        val tests = listOf(
            test("both-invalid", v66Definition(inputValues = 71, outputValues = 73)),
            test("input-invalid", v66Definition(inputValues = 71, outputValues = 72)),
            test("output-invalid", v66Definition(inputValues = 72, outputValues = 73))
        )

        val result = CouncilPromotionTestContractValidator.validate(
            opportunity = v66Opportunity(tests.mapTo(linkedSetOf(), SealedTestDraft::testKey)),
            tests = tests
        )

        val failuresByTest = result.failures.groupBy(CouncilPromotionTestContractFailure::testId)
        assertEquals(2, failuresByTest.getValue("both-invalid").size)
        assertEquals(
            setOf(
                CouncilPromotionTestComponent.INPUT,
                CouncilPromotionTestComponent.EXPECTED_OUTPUT
            ),
            failuresByTest.getValue("both-invalid")
                .mapTo(linkedSetOf(), CouncilPromotionTestContractFailure::component)
        )
        val bothMessages = failuresByTest.getValue("both-invalid")
            .map(CouncilPromotionTestContractFailure::message)
        assertTrue(bothMessages.any {
            it.contains("too_few_items_root.scenario.outdoor_temperatures") &&
                it.contains("actualItems=71") &&
                it.contains("minimumItems=72") &&
                it.contains("missingItems=1")
        })
        assertTrue(bothMessages.any {
            it.contains("too_many_items_root.hourly_results") &&
                it.contains("actualItems=73") &&
                it.contains("maximumItems=72") &&
                it.contains("excessItems=1")
        })

        val inputOnly = failuresByTest.getValue("input-invalid").single()
        assertEquals(CouncilPromotionTestComponent.INPUT, inputOnly.component)
        assertTrue(
            inputOnly.message.contains(
                "expectedOutput already satisfies its sealed schema and must be copied unchanged"
            )
        )
        val outputOnly = failuresByTest.getValue("output-invalid").single()
        assertEquals(CouncilPromotionTestComponent.EXPECTED_OUTPUT, outputOnly.component)
        assertTrue(
            outputOnly.message.contains(
                "input already satisfies its sealed schema and must be copied unchanged"
            )
        )
    }

    private fun opportunity(testIds: Set<String>) = ToolOpportunity(
        opportunityId = "hourly-load-opportunity",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate exactly 72 hourly shelter loads.",
        requiredInputs = listOf(
            TypedField(
                name = "scenario",
                type = TypedFieldType.OBJECT,
                description = "Bounded shelter scenario.",
                objectFields = setOf(
                    TypedField(
                        name = "hours",
                        type = TypedFieldType.INTEGER,
                        description = "Forecast horizon."
                    )
                )
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "hourly_load",
                type = TypedFieldType.ARRAY,
                description = "Exactly 72 hourly loads.",
                itemType = TypedFieldType.NUMBER,
                minimumItems = 72,
                maximumItems = 72
            )
        ),
        validationStrategy = "Execute exact boundary fixtures.",
        expectedReuseNodeIds = setOf(NodeId("later-node")),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = testIds,
        maximumRuntimeMillis = 2_000,
        maximumOutputBytes = 32_768
    )

    private fun v66Opportunity(testIds: Set<String>) = opportunity(testIds).copy(
        requiredInputs = listOf(
            TypedField(
                name = "scenario",
                type = TypedFieldType.OBJECT,
                description = "Bounded shelter scenario.",
                objectFields = setOf(
                    TypedField(
                        name = "outdoor_temperatures",
                        type = TypedFieldType.ARRAY,
                        description = "Exactly 72 outdoor temperatures.",
                        itemType = TypedFieldType.NUMBER,
                        minimumItems = 72,
                        maximumItems = 72
                    )
                )
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "hourly_results",
                type = TypedFieldType.ARRAY,
                description = "Exactly 72 hourly results.",
                itemType = TypedFieldType.NUMBER,
                minimumItems = 72,
                maximumItems = 72
            )
        )
    )

    private fun definition(expectedValues: Int): String =
        """
            {
              "input":{"scenario":{"hours":72}},
              "expectedOutput":{"hourly_load":${values(expectedValues)}}
            }
        """.trimIndent()

    private fun values(count: Int): String =
        List(count) { "1.0" }.joinToString(prefix = "[", postfix = "]")

    private fun v66Definition(inputValues: Int, outputValues: Int): String =
        """
            {
              "input":{"scenario":{"outdoor_temperatures":${values(inputValues)}}},
              "expectedOutput":{"hourly_results":${values(outputValues)}}
            }
        """.trimIndent()

    private fun test(testId: String, definition: String) = SealedTestDraft(
        testKey = testId,
        nodeKey = LocalNodeKey("tool-node"),
        acceptedArtifactTypes = setOf(ArtifactType.TOOL),
        testDefinitionJson = definition,
        commitmentHash = CouncilContractHashing.sha256(definition)
    )
}
