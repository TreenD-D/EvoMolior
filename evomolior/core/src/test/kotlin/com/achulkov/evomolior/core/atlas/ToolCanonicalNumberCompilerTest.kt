package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolCanonicalNumberCompilerTest {
    private val schema = Json.parseToJsonElement(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "count":{"type":"integer"},
            "ratio":{"type":"number"},
            "nested":{
              "type":"array",
              "items":{
                "type":"object",
                "additionalProperties":false,
                "properties":{"value":{"type":"number"}},
                "required":["value"]
              }
            }
          },
          "required":["count","ratio","nested"]
        }
        """.trimIndent()
    ) as JsonObject

    @Test
    fun `number and integer values use one promotion compatible representation`() {
        val authored = Json.parseToJsonElement(
            """{"ratio":2,"nested":[{"value":1e-7}],"count":2}"""
        )

        assertEquals(
            """{"count":2,"nested":[{"value":1.0E-7}],"ratio":2.0}""",
            ToolCanonicalTypedValueEncoder.encodeToString(authored, schema)
        )
    }

    @Test
    fun `integer distinction and non finite values reject`() {
        val fractionalInteger = Json.parseToJsonElement(
            """{"count":2.5,"ratio":2,"nested":[{"value":1}]}"""
        )
        assertEquals(
            "wrong_type",
            assertFailsWith<ToolCanonicalValueException> {
                ToolCanonicalTypedValueEncoder.encode(fractionalInteger, schema)
            }.code
        )

        val numberSchema = Json.parseToJsonElement("""{"type":"number"}""") as JsonObject
        val nonFinite = kotlinx.serialization.json.JsonPrimitive(Double.POSITIVE_INFINITY)
        assertEquals(
            "non_finite_number",
            assertFailsWith<ToolCanonicalValueException> {
                ToolCanonicalTypedValueEncoder.encode(nonFinite, numberSchema)
            }.code
        )
    }
}
