package com.achulkov.evomolior.api.service

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinalComponentLineageV1Test {
    @Test
    fun `strict parser accepts exact component mapping including an honest empty component`() {
        val parsed = FinalComponentLineageV1Codec.parse(
            content = lineage(
                """
                {
                  "schemaVersion": "final-component-lineage-v1",
                  "outputRelativePath": "result.json",
                  "componentNodeIds": {
                    "answer": ["node-b", "node-a"],
                    "evidence": []
                  }
                }
                """.trimIndent()
            ),
            expectedOutputRelativePath = "result.json",
            requiredComponentIds = setOf("answer", "evidence"),
            availableNodeIds = setOf("node-a", "node-b")
        )

        assertEquals(
            mapOf(
                "answer" to setOf("node-a", "node-b"),
                "evidence" to emptySet()
            ),
            parsed
        )
    }

    @Test
    fun `strict parser rejects malformed and unknown wire fields`() {
        assertFailsWith<SerializationException> {
            FinalComponentLineageV1Codec.parse(
                content = lineage("""{"schemaVersion":"""),
                expectedOutputRelativePath = "result.json",
                requiredComponentIds = setOf("answer"),
                availableNodeIds = setOf("node-a")
            )
        }
        assertFailsWith<SerializationException> {
            FinalComponentLineageV1Codec.parse(
                content = lineage(
                    """
                    {
                      "schemaVersion": "final-component-lineage-v1",
                      "outputRelativePath": "result.json",
                      "componentNodeIds": {"answer": ["node-a"]},
                      "unexpected": true
                    }
                    """.trimIndent()
                ),
                expectedOutputRelativePath = "result.json",
                requiredComponentIds = setOf("answer"),
                availableNodeIds = setOf("node-a")
            )
        }
    }

    @Test
    fun `strict parser rejects missing extra duplicate unknown-node and wrong-path authority`() {
        val exact = """
            {
              "schemaVersion": "final-component-lineage-v1",
              "outputRelativePath": "result.json",
              "componentNodeIds": %s
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parse(exact.format("""{"answer": ["node-a"]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            parse(exact.format("""{"answer": ["node-a"], "evidence": [], "extra": []}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            parse(exact.format("""{"answer": ["node-a", "node-a"], "evidence": []}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            parse(exact.format("""{"answer": ["node-unknown"], "evidence": []}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            parse(
                exact.replace(
                    """"outputRelativePath": "result.json"""",
                    """"outputRelativePath": "wrong.json""""
                ).format("""{"answer": ["node-a"], "evidence": []}""")
            )
        }
    }

    @Test
    fun `prompt publishes exact components accepted nodes and mandatory staging order`() {
        val prompt = FinalComponentLineageV1Codec.promptRequirements(
            outputRelativePath = "result.json",
            requiredComponentIds = setOf("evidence", "answer"),
            availableNodes = mapOf(
                "node-b" to "Second accepted claim",
                "node-a" to "First accepted claim"
            )
        )

        assertTrue(prompt.contains(FINAL_COMPONENT_LINEAGE_V1_MARKER))
        assertTrue(prompt.contains("stage exactly one PLAN_UPDATE"))
        assertTrue(prompt.contains("then write the Death Note"))
        assertTrue(prompt.contains(""""answer":[],"evidence":[]"""))
        assertTrue(prompt.indexOf("- node-a:") < prompt.indexOf("- node-b:"))
    }

    @Test
    fun `component lineage ordering rejects equal timestamp boundaries`() {
        assertFailsWith<IllegalArgumentException> {
            FinalComponentLineageOrder.requireAfterDoc(
                docSubmittedAtMs = 100L,
                lineageSubmittedAtMs = 100L
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FinalComponentLineageOrder.requireBeforeDeathNote(
                lineageSubmittedAtMs = 101L,
                deathNoteSubmittedAtMs = 101L
            )
        }
        FinalComponentLineageOrder.requireAfterDoc(
            docSubmittedAtMs = 100L,
            lineageSubmittedAtMs = 101L
        )
        FinalComponentLineageOrder.requireBeforeDeathNote(
            lineageSubmittedAtMs = 101L,
            deathNoteSubmittedAtMs = 102L
        )
    }

    private fun parse(payload: String) = FinalComponentLineageV1Codec.parse(
        content = lineage(payload),
        expectedOutputRelativePath = "result.json",
        requiredComponentIds = setOf("answer", "evidence"),
        availableNodeIds = setOf("node-a")
    )

    private fun lineage(payload: String) =
        "$FINAL_COMPONENT_LINEAGE_V1_MARKER\n$payload"
}
