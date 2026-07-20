package com.achulkov.evomolior.core.council

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class GeneralTestDefinitionV1Test {
    @Test
    fun `all version one branches round trip with stable canonical bytes`() {
        definitions().forEach { definition ->
            val canonical = GeneralTestDefinitionV1Codec.encode(definition)
            val decoded = GeneralTestDefinitionV1Codec.decode(canonical)

            assertEquals(definition, decoded)
            assertEquals(canonical, GeneralTestDefinitionV1Codec.encode(decoded))
            assertEquals(
                CouncilContractHashing.sha256(canonical),
                GeneralTestDefinitionV1Codec.hash(definition)
            )
            assertFalse(canonical.contains(":null"))
        }
    }

    @Test
    fun `canonical text contains the stable nested type discriminators`() {
        val definition = GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.FinalOutputText,
            assertion = GeneralAssertionV1.TextContains(
                expected = "required evidence",
                caseSensitive = false
            )
        )

        assertEquals(
            """{"assertion":{"caseSensitive":false,"expected":"required evidence","type":"TEXT_CONTAINS"},"target":{"type":"FINAL_OUTPUT_TEXT"}}""",
            GeneralTestDefinitionV1Codec.encode(definition)
        )
    }

    @Test
    fun `strict codec rejects untyped nullable map and unknown shapes`() {
        invalidDefinitions().forEach { raw ->
            assertFails("Expected strict decode failure for $raw") {
                GeneralTestDefinitionV1Codec.decode(raw)
            }
        }
    }

    @Test
    fun `special floating point ranges cannot enter canonical JSON`() {
        val definition = GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
            assertion = GeneralAssertionV1.JsonPointerNumberRange(
                pointer = "/score",
                minimumInclusive = Double.NaN,
                maximumInclusive = 1.0
            )
        )

        assertFails {
            GeneralTestDefinitionV1Codec.encode(definition)
        }
    }

    private fun definitions(): List<GeneralTestDefinitionV1> = listOf(
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.FinalOutputText,
            assertion = GeneralAssertionV1.TextContains(
                expected = "bounded recommendation",
                caseSensitive = true
            )
        ),
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputTextFile("reports/final.md"),
            assertion = GeneralAssertionV1.TextMatchesRe2("risk\\s+register")
        ),
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
            assertion = GeneralAssertionV1.JsonPointerExists("/metrics/0")
        ),
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
            assertion = GeneralAssertionV1.JsonPointerEqualsString(
                pointer = "/status",
                expected = "AVAILABLE"
            )
        ),
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
            assertion = GeneralAssertionV1.JsonPointerNumberRange(
                pointer = "/score",
                minimumInclusive = 0.0,
                maximumInclusive = 1.0
            )
        )
    )

    private fun invalidDefinitions(): List<String> = listOf(
        "null",
        "true",
        "[]",
        "{}",
        """{"target":null,"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":false}}""",
        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":null}""",
        """{"target":{"type":"UNKNOWN"},"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":false}}""",
        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"UNKNOWN"}}""",
        """{"target":{"type":"OUTPUT_TEXT_FILE"},"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":false}}""",
        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"TEXT_CONTAINS","caseSensitive":false}}""",
        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":false,"extra":"forbidden"}}""",
        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":false},"extra":{}}"""
    )
}
