package com.achulkov.evokammer.orchestration.council

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies Genesis Council parsing behavior for sealed suite rescue paths.
 */
class GenesisCouncilTest {

    @Test
    fun `parseStartingField rescues sealed suite fields when full decode fails`() {
        val council = GenesisCouncil(
            objective = "align the council",
            openRouterApiKey = "sk-test"
        )

        val parseMethod = council.javaClass.getDeclaredMethod(
            "parseStartingField",
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }

        val rawOutput = """
            {
              "sealedTestSuiteJson": "{\"tests\":[{\"id\":\"alpha\"}]}",
              "sealedTestSuiteHash": "hash-1",
              "consistencyReport": "sealed suite rescued"
            }
        """.trimIndent()

        val field = parseMethod.invoke(council, rawOutput, "align the council") as com.achulkov.evokammer.core.contract.StartingField

        assertTrue(field.sealedTestSuiteJson.isNotBlank())
        assertTrue(field.sealedTestSuiteJson.contains("alpha"))
        assertEquals("hash-1", field.sealedTestSuiteHash)
        assertEquals("sealed suite rescued", field.consistencyReport)
    }

    @Test
    fun `parseStartingField rescues disclosure stage contents when full decode fails`() {
        val council = GenesisCouncil(
            objective = "align the council",
            openRouterApiKey = "sk-test"
        )

        val parseMethod = council.javaClass.getDeclaredMethod(
            "parseStartingField",
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }

        val rawOutput = """
            {
              "stageContents": ["Stage 0 constraints", "Stage 1 constraints"],
              "consistencyReport": "stages rescued"
            }
        """.trimIndent()

        val field = parseMethod.invoke(council, rawOutput, "align the council") as com.achulkov.evokammer.core.contract.StartingField

        assertEquals(listOf("Stage 0 constraints", "Stage 1 constraints"), field.stageContents)
        assertEquals("stages rescued", field.consistencyReport)
    }
}
