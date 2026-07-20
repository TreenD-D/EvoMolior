package com.achulkov.evomolior.core.council

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TypedJsonValueTest {
    @Test
    fun `all typed JSON branches round trip through tagged and ordinary JSON`() {
        val value = complexValue()
        val encoded = TypedJsonValueCodec.encode(value)
        val decoded = TypedJsonValueCodec.decode(encoded)

        assertEquals(value, decoded)
        assertEquals(encoded, TypedJsonValueCodec.encode(decoded))
        assertEquals(
            """{"a":[null,true,1,1.0,"text"],"z":{"nested":false}}""",
            TypedJsonValueCodec.toCanonicalJson(value)
        )
        assertEquals(
            value,
            TypedJsonValueCodec.fromJsonElement(
                TypedJsonValueCodec.toJsonElement(value)
            )
        )
    }

    @Test
    fun `ordinary JSON conversion normalizes object keys and preserves integer versus number`() {
        val raw = Json.parseToJsonElement(
            """{"z":1.0,"a":1,"nested":{"b":2.5,"a":2}}"""
        )
        val typed = assertIs<TypedJsonValue.ObjectValue>(
            TypedJsonValueCodec.fromJsonElement(raw)
        )

        assertEquals(listOf("a", "nested", "z"), typed.entries.map(TypedJsonObjectEntry::key))
        assertIs<TypedJsonValue.IntegerValue>(typed.entries[0].value)
        assertIs<TypedJsonValue.NumberValue>(typed.entries[2].value)
        assertEquals(raw.jsonObject, TypedJsonValueCodec.toJsonElement(typed).jsonObject)
        assertNotEquals(
            TypedJsonValueCodec.hash(TypedJsonValue.IntegerValue(1)),
            TypedJsonValueCodec.hash(TypedJsonValue.NumberValue(1.0))
        )
    }

    @Test
    fun `ordinary JSON numeric conversion rejects values outside bounded exact semantics`() {
        listOf(
            "9223372036854775808",
            "9007199254740993.0",
            "0.12345678901234567890"
        ).forEach { numeric ->
            assertFails("Expected bounded numeric rejection for $numeric") {
                TypedJsonValueCodec.fromJsonElement(Json.parseToJsonElement(numeric))
            }
        }

        assertEquals(
            TypedJsonValue.NumberValue(1.23),
            TypedJsonValueCodec.fromJsonElement(Json.parseToJsonElement("1.2300"))
        )
        assertEquals(
            TypedJsonValue.NumberValue(1000.0),
            TypedJsonValueCodec.fromJsonElement(Json.parseToJsonElement("1e3"))
        )
        assertEquals(
            TypedJsonValue.IntegerValue(0),
            TypedJsonValueCodec.fromJsonElement(Json.parseToJsonElement("-0"))
        )
    }

    @Test
    fun `typed JSON object and number constructors fail closed`() {
        assertFails {
            TypedJsonValue.ObjectValue(
                listOf(
                    TypedJsonObjectEntry("z", TypedJsonValue.NullValue),
                    TypedJsonObjectEntry("a", TypedJsonValue.NullValue)
                )
            )
        }
        assertFails {
            TypedJsonValue.ObjectValue(
                listOf(
                    TypedJsonObjectEntry("a", TypedJsonValue.NullValue),
                    TypedJsonObjectEntry("a", TypedJsonValue.BooleanValue(true))
                )
            )
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFails { TypedJsonValue.NumberValue(it) }
        }
    }

    @Test
    fun `strict tagged codec rejects raw null unknown missing and extra shapes`() {
        listOf(
            "null",
            "{}",
            """{"type":"UNKNOWN"}""",
            """{"type":"STRING"}""",
            """{"type":"STRING","value":"x","extra":true}""",
            """{"type":"OBJECT","entries":[{"key":"z","value":{"type":"NULL"}},{"key":"a","value":{"type":"NULL"}}]}"""
        ).forEach { invalid ->
            assertFails("Expected strict typed JSON failure for $invalid") {
                TypedJsonValueCodec.decode(invalid)
            }
        }
    }

    @Test
    fun `typed JSON canonical bytes and hash have stable golden values`() {
        val value = complexValue()

        assertEquals(
            """{"entries":[{"key":"a","value":{"type":"ARRAY","values":[{"type":"NULL"},{"type":"BOOLEAN","value":true},{"type":"INTEGER","value":1},{"type":"NUMBER","value":1.0},{"type":"STRING","value":"text"}]}},{"key":"z","value":{"entries":[{"key":"nested","value":{"type":"BOOLEAN","value":false}}],"type":"OBJECT"}}],"type":"OBJECT"}""",
            TypedJsonValueCodec.encode(value)
        )
        assertEquals(
            "f3a3e372a902a06afce0bcb7d00adee53723480afb4bfa09baffe187f382b5ad",
            TypedJsonValueCodec.hash(value)
        )
    }

    private fun complexValue(): TypedJsonValue =
        TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry(
                    "a",
                    TypedJsonValue.ArrayValue(
                        listOf(
                            TypedJsonValue.NullValue,
                            TypedJsonValue.BooleanValue(true),
                            TypedJsonValue.IntegerValue(1),
                            TypedJsonValue.NumberValue(1.0),
                            TypedJsonValue.StringValue("text")
                        )
                    )
                ),
                TypedJsonObjectEntry(
                    "z",
                    TypedJsonValue.ObjectValue(
                        listOf(
                            TypedJsonObjectEntry(
                                "nested",
                                TypedJsonValue.BooleanValue(false)
                            )
                        )
                    )
                )
            )
        )
}
