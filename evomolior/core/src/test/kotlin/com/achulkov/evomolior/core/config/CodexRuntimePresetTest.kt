package com.achulkov.evomolior.core.config

import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexRuntimePresetTest {
    @Test
    fun `full Luna pins every role including council graders and utility roles`() {
        val preset = CodexRuntimePreset.FULL_LUNA
        Role.entries.forEach { role ->
            assertEquals(listOf(CodexRuntimePreset.LUNA), preset.modelPool(role))
            assertEquals(CodexRuntimePreset.LUNA, preset.defaultModel(role))
        }
        assertEquals(setOf(CodexRuntimePreset.LUNA), preset.supportedModelIds)
        assertFalse(preset.runtimeAssignments().keys.any { it.startsWith("Council") })
    }

    @Test
    fun `mixed preset has exact Outlier and specialist sampling weights`() {
        val preset = CodexRuntimePreset.MIXED_FAMILIES
        fun counts(role: Role) = (0 until 100)
            .map { preset.selectModel(role, preset.modelPool(role), TicketRandom(it)) }
            .groupingBy { it }.eachCount()
        assertEquals(
            mapOf(
                CodexRuntimePreset.LUNA to 85,
                CodexRuntimePreset.SOL to 7,
                CodexRuntimePreset.TERRA to 7,
                CodexRuntimePreset.ASTRA to 1,
            ),
            counts(Role.Outlier),
        )
        assertEquals(
            mapOf(CodexRuntimePreset.SOL to 50, CodexRuntimePreset.TERRA to 50),
            counts(Role.Planner),
        )
        Role.entries.filter { it.isCouncilRole || it.isGraderRole }.forEach {
            assertEquals(CodexRuntimePreset.ASTRA, preset.defaultModel(it))
        }
        listOf(Role.Researcher, Role.Builder, Role.Archivist, Role.Educator, Role.Connector)
            .forEach { assertEquals(CodexRuntimePreset.LUNA, preset.defaultModel(it)) }
    }

    @Test
    fun `selection stays inside sealed pools and exact overrides`() {
        val preset = CodexRuntimePreset.MIXED_FAMILIES
        assertEquals(
            CodexRuntimePreset.ASTRA,
            preset.selectModel(Role.Researcher, listOf(CodexRuntimePreset.ASTRA)),
        )
        assertFailsWith<IllegalArgumentException> {
            preset.selectModel(Role.Outlier, listOf("unsupported-model"))
        }
        assertFailsWith<IllegalArgumentException> {
            CodexRuntimePreset.FULL_LUNA.selectModel(Role.Outlier, listOf(CodexRuntimePreset.ASTRA))
        }
        assertFailsWith<IllegalArgumentException> {
            preset.selectModel(Role.Outlier, listOf(CodexRuntimePreset.LUNA, CodexRuntimePreset.LUNA))
        }
    }

    @Test
    fun `presets share bounded reasoning tiers for fair model comparison`() {
        for (role in Role.entries) {
            for (ceiling in CodexReasoningEffort.entries) {
                val baseline = CodexRuntimePreset.FULL_LUNA.reasoningEffort(role, ceiling)
                assertEquals(baseline, CodexRuntimePreset.MIXED_FAMILIES.reasoningEffort(role, ceiling))
                assertTrue(baseline.ordinal <= ceiling.ordinal)
            }
        }
        assertEquals(CodexReasoningEffort.HIGH, CodexRuntimePreset.FULL_LUNA.reasoningEffort(Role.Critic))
        assertEquals(CodexReasoningEffort.MEDIUM, CodexRuntimePreset.FULL_LUNA.reasoningEffort(Role.Builder))
        assertEquals(CodexReasoningEffort.LOW, CodexRuntimePreset.FULL_LUNA.reasoningEffort(Role.Archivist))
        for (value in listOf("xhigh", "max", "ultra")) {
            assertFailsWith<IllegalArgumentException> { CodexReasoningEffort.parse(value) }
        }
    }

    @Test
    fun `preset identifiers round trip and unknown values fail closed`() {
        CodexRuntimePreset.entries.forEach { preset ->
            assertEquals(preset, CodexRuntimePreset.parse(preset.id))
            assertEquals(preset, Json.decodeFromString<CodexRuntimePreset>(Json.encodeToString(preset)))
        }
        assertFailsWith<IllegalArgumentException> { CodexRuntimePreset.parse("auto") }
    }

    private class TicketRandom(private val ticket: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = error("Expected a bounded ticket request")
        override fun nextInt(until: Int): Int = ticket.also { require(it in 0 until until) }
    }
}
