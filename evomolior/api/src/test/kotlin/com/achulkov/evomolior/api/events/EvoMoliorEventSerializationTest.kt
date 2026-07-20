package com.achulkov.evomolior.api.events

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class EvoMoliorEventSerializationTest {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    @Test
    fun `run started decodes legacy payload without scoring dimensions`() {
        val payload = """
            {
              "type": "com.achulkov.evomolior.api.events.EvoMoliorEvent.RunStarted",
              "runId": "run-1",
              "objective": "legacy"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(EvoMoliorEvent.serializer(), payload)

        assertEquals(emptyList(), (decoded as EvoMoliorEvent.RunStarted).scoringDimensionIds)
    }

    @Test
    fun `run started serializes selected scoring dimensions`() {
        val encoded = json.encodeToString<EvoMoliorEvent>(
            EvoMoliorEvent.RunStarted(
                runId = "run-1",
                objective = "objective",
                scoringDimensionIds = listOf("correctness", "security_risk")
            )
        )
        val decoded = json.decodeFromString(EvoMoliorEvent.serializer(), encoded)

        assertEquals(
            listOf("correctness", "security_risk"),
            (decoded as EvoMoliorEvent.RunStarted).scoringDimensionIds
        )
    }
}
