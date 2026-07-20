package com.achulkov.evomolior.orchestration

import com.achulkov.evomolior.orchestration.epigenetic.CrossoverEngine
import com.achulkov.evomolior.substrate.db.entity.LegacyProtocolEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CrossoverEngine] — pure logic, no DB dependencies.
 */
class CrossoverEngineTest {

    private val engine = CrossoverEngine()

    private fun protocol(
        id: String = "proto-default",
        role: String = "Researcher",
        generation: Int = 1,
        expiresAt: Int = 3,
        reasoning: String = "Reasoning $id",
        toolHeuristics: String = "{}",
        failureMap: String = "{}",
        kgHabits: String = "{}",
        sourceAgentIds: String = "agent-$id",
        groupId: String = "group-A",
        runId: String = "run-1"
    ) = LegacyProtocolEntity(
        id = id,
        role = role,
        extractedFromGeneration = generation,
        expiresAtGeneration = expiresAt,
        reasoningPosture = reasoning,
        toolHeuristicsJson = toolHeuristics,
        failureMapJson = failureMap,
        kgHabitsJson = kgHabits,
        sourceAgentIds = sourceAgentIds,
        groupId = groupId,
        runId = runId,
        createdAtMs = 0L
    )

    // ── crossover ──────────────────────────────────────────────────────────────

    @Test
    fun `crossover merges reasoning postures with separator`() {
        val a = protocol(id = "a1", reasoning = "Be methodical")
        val b = protocol(id = "b1", reasoning = "Use patterns")
        val merged = engine.crossover(a, b)
        assertTrue(merged.reasoningPosture.contains("Be methodical"))
        assertTrue(merged.reasoningPosture.contains("Use patterns"))
        assertTrue(merged.reasoningPosture.contains("---"))
    }

    @Test
    fun `crossover sums tool heuristic counts for shared tools`() {
        val a = protocol(id = "a2", toolHeuristics = """{"read_kb":3,"search_kb":1}""")
        val b = protocol(id = "b2", toolHeuristics = """{"read_kb":5,"emit_fact":2}""")
        val merged = engine.crossover(a, b)
        // read_kb should be 3+5=8
        assertTrue(merged.toolHeuristicsJson.contains(""""read_kb":8"""),
            "Expected read_kb:8 in ${merged.toolHeuristicsJson}")
        assertTrue(merged.toolHeuristicsJson.contains(""""search_kb":1"""))
        assertTrue(merged.toolHeuristicsJson.contains(""""emit_fact":2"""))
    }

    @Test
    fun `crossover sums failure counts for shared tools`() {
        val a = protocol(id = "a3", failureMap = """{"web_fetch":{"failures":2,"note":"..."}}""")
        val b = protocol(id = "b3", failureMap = """{"web_fetch":{"failures":3,"note":"..."}}""")
        val merged = engine.crossover(a, b)
        // web_fetch failures should be 2+3=5
        assertTrue(merged.failureMapJson.contains(""""failures":5"""),
            "Expected failures:5 in ${merged.failureMapJson}")
    }

    @Test
    fun `crossover uses earliest expiry generation`() {
        val a = protocol(id = "a4", generation = 2, expiresAt = 4)
        val b = protocol(id = "b4", generation = 3, expiresAt = 5)
        val merged = engine.crossover(a, b)
        assertEquals(4, merged.expiresAtGeneration)   // min(4, 5)
        assertEquals(3, merged.extractedFromGeneration)  // max(2, 3)
    }

    @Test
    fun `crossover combines source agent IDs`() {
        val a = protocol(id = "a5", sourceAgentIds = "agent-1,agent-2")
        val b = protocol(id = "b5", sourceAgentIds = "agent-3")
        val merged = engine.crossover(a, b)
        assertTrue(merged.sourceAgentIds.contains("agent-1"))
        assertTrue(merged.sourceAgentIds.contains("agent-3"))
    }

    @Test
    fun `crossover merges KG habits by summing relation counts`() {
        val a = protocol(
            id = "a6",
            kgHabits = """{"dominantRelations":[{"relation":"CAUSES","count":3},{"relation":"SUPPORTS","count":1}]}"""
        )
        val b = protocol(
            id = "b6",
            kgHabits = """{"dominantRelations":[{"relation":"CAUSES","count":2},{"relation":"CONTRADICTS","count":4}]}"""
        )
        val merged = engine.crossover(a, b)
        // CAUSES should be 3+2=5
        assertTrue(merged.kgHabitsJson.contains(""""count":5"""),
            "Expected CAUSES count=5 in ${merged.kgHabitsJson}")
    }

    @Test
    fun `crossover throws for different roles`() {
        val a = protocol(id = "a7", role = "Researcher")
        val b = protocol(id = "b7", role = "Engineer")
        try {
            engine.crossover(a, b)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("different roles"))
        }
    }

    // ── mergeAll ──────────────────────────────────────────────────────────────

    @Test
    fun `mergeAll returns null for empty list`() {
        assertNull(engine.mergeAll(emptyList()))
    }

    @Test
    fun `mergeAll returns single item unchanged`() {
        val p = protocol(id = "single")
        assertEquals(p, engine.mergeAll(listOf(p)))
    }

    @Test
    fun `mergeAll reduces multiple protocols into one`() {
        val protocols = listOf(
            protocol(id = "m1", toolHeuristics = """{"tool_a":1}"""),
            protocol(id = "m2", toolHeuristics = """{"tool_b":2}"""),
            protocol(id = "m3", toolHeuristics = """{"tool_a":3}""")
        )
        val merged = engine.mergeAll(protocols)!!
        // tool_a should be 1+3=4
        assertTrue(merged.toolHeuristicsJson.contains(""""tool_a":4"""),
            "Expected tool_a:4 in ${merged.toolHeuristicsJson}")
        assertTrue(merged.toolHeuristicsJson.contains(""""tool_b":2"""))
    }

    @Test
    fun `mergeAll truncates reasoning posture to 4000 chars`() {
        val longText = "x".repeat(3000)
        val protocols = listOf(
            protocol(id = "t1", reasoning = longText),
            protocol(id = "t2", reasoning = longText)
        )
        val merged = engine.mergeAll(protocols)!!
        assertTrue(merged.reasoningPosture.length <= 4000,
            "Reasoning posture exceeds 4000 chars: ${merged.reasoningPosture.length}")
    }
}
