package com.achulkov.evomolior.agents.factory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexJsonSchemaTransportTest {
    @Test
    fun `provider transport removes unsupported uniqueness keyword recursively`() {
        val schema = buildJsonObject {
            put(
                "properties",
                buildJsonObject {
                    put(
                        "requiredInputs",
                        buildJsonObject {
                            put("type", "array")
                            put("uniqueItems", true)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "array")
                                    put("uniqueItems", true)
                                }
                            )
                        }
                    )
                }
            )
        }

        val transported = schema.withoutCodexUnsupportedKeywords().jsonObject
        val requiredInputs = transported
            .getValue("properties")
            .jsonObject
            .getValue("requiredInputs")
            .jsonObject
        val nestedItems = requiredInputs.getValue("items").jsonObject

        assertFalse("uniqueItems" in transported.toString())
        assertFalse("uniqueItems" in requiredInputs)
        assertFalse("uniqueItems" in nestedItems)
        assertTrue(requiredInputs.getValue("type").toString().contains("array"))
    }

    @Test
    fun `provider transport preserves other array constraints`() {
        val schema = Json.parseToJsonElement(
            """{"type":"array","minItems":2,"maxItems":2,"uniqueItems":true,"items":{"type":"string"}}"""
        ).jsonObject

        val transported = schema.withoutCodexUnsupportedKeywords().jsonObject

        assertTrue(transported.getValue("minItems").toString().contains("2"))
        assertTrue(transported.getValue("maxItems").toString().contains("2"))
        assertTrue(
            transported.getValue("items")
                .jsonObject
                .getValue("type")
                .toString()
                .contains("string")
        )
        assertFalse("uniqueItems" in transported)
    }
}
