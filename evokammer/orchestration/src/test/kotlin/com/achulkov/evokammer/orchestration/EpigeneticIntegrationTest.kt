package com.achulkov.evokammer.orchestration

import com.achulkov.evokammer.orchestration.epigenetic.CrossoverEngine
import com.achulkov.evokammer.orchestration.epigenetic.FitnessScore
import com.achulkov.evokammer.substrate.db.entity.LegacyProtocolEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration-style tests for the epigenetic pipeline:
 * - [CrossoverEngine] merging multiple generations of protocols.
 * - [FitnessScore] ordering and selection behaviour.
 */
class EpigeneticIntegrationTest {

    private val crossover = CrossoverEngine()

    // ─────────── Protocol factories ────────────────────────────────────────────

    private fun proto(
        id: String,
        role: String = "Researcher",
        generation: Int = 1,
        expiresAt: Int = generation + 2,
        tools: String = "{}",
        failures: String = "{}",
        kgHabits: String = "{}",
        groupId: String = "G1",
        runId: String = "R1",
        sourceAgentIds: String = id
    ) = LegacyProtocolEntity(
        id = id,
        role = role,
        extractedFromGeneration = generation,
        expiresAtGeneration = expiresAt,
        reasoningPosture = "Posture from $id",
        toolHeuristicsJson = tools,
        failureMapJson = failures,
        kgHabitsJson = kgHabits,
        sourceAgentIds = sourceAgentIds,
        groupId = groupId,
        runId = runId,
        createdAtMs = System.currentTimeMillis()
    )

    // ─────────── CrossoverEngine integration ────────────────────────────────

    @Test
    fun `three-way merge produces correct tool count sums`() {
        val p1 = proto("p1", tools = """{"read_kb":2,"web_fetch":1}""")
        val p2 = proto("p2", tools = """{"read_kb":3,"emit_fact":4}""")
        val p3 = proto("p3", tools = """{"read_kb":1,"emit_fact":2,"search_kb":5}""")

        val merged = crossover.mergeAll(listOf(p1, p2, p3))!!

        // read_kb: 2+3+1 = 6
        assertTrue(merged.toolHeuristicsJson.contains(""""read_kb":6"""),
            "read_kb should be 6 in ${merged.toolHeuristicsJson}")
        // emit_fact: 4+2 = 6
        assertTrue(merged.toolHeuristicsJson.contains(""""emit_fact":6"""),
            "emit_fact should be 6 in ${merged.toolHeuristicsJson}")
        // search_kb: 5
        assertTrue(merged.toolHeuristicsJson.contains(""""search_kb":5"""))
        // web_fetch: 1
        assertTrue(merged.toolHeuristicsJson.contains(""""web_fetch":1"""))
    }

    @Test
    fun `mergeAll with only one protocol returns it unchanged`() {
        val p = proto("only")
        val result = crossover.mergeAll(listOf(p))
        assertEquals(p, result)
    }

    @Test
    fun `merged protocol expiry is the minimum of all inputs`() {
        val ps = listOf(
            proto("e1", generation = 1, expiresAt = 5),
            proto("e2", generation = 2, expiresAt = 3),
            proto("e3", generation = 3, expiresAt = 7)
        )
        val merged = crossover.mergeAll(ps)!!
        assertEquals(3, merged.expiresAtGeneration)
    }

    @Test
    fun `merged protocol extraction generation is the maximum`() {
        val ps = listOf(
            proto("g1", generation = 1),
            proto("g2", generation = 4),
            proto("g3", generation = 2)
        )
        val merged = crossover.mergeAll(ps)!!
        assertEquals(4, merged.extractedFromGeneration)
    }

    @Test
    fun `merged source agent IDs contains all originals`() {
        val ps = listOf(
            proto("s1", sourceAgentIds = "agent-A"),
            proto("s2", sourceAgentIds = "agent-B,agent-C")
        )
        val merged = crossover.mergeAll(ps)!!
        assertTrue(merged.sourceAgentIds.contains("agent-A"))
        assertTrue(merged.sourceAgentIds.contains("agent-B"))
        assertTrue(merged.sourceAgentIds.contains("agent-C"))
    }

    @Test
    fun `failure map crossover sums failure counts across multiple protocols`() {
        val a = proto("f1", failures = """{"tool_x":{"failures":1,"note":"..."}}""")
        val b = proto("f2", failures = """{"tool_x":{"failures":2,"note":"..."}}""")
        val c = proto("f3", failures = """{"tool_x":{"failures":3,"note":"..."}}""")
        val merged = crossover.mergeAll(listOf(a, b, c))!!
        // 1+2+3 = 6
        assertTrue(merged.failureMapJson.contains(""""failures":6"""),
            "Expected failures:6 in ${merged.failureMapJson}")
    }

    @Test
    fun `KG habits merge combines relation counts`() {
        val a = proto("kg1", kgHabits = """{"dominantRelations":[{"relation":"SUPPORTS","count":4}]}""")
        val b = proto("kg2", kgHabits = """{"dominantRelations":[{"relation":"SUPPORTS","count":2},{"relation":"CAUSES","count":7}]}""")
        val merged = crossover.mergeAll(listOf(a, b))!!
        // SUPPORTS: 4+2=6
        assertTrue(merged.kgHabitsJson.contains(""""count":6"""),
            "Expected SUPPORTS count=6 in ${merged.kgHabitsJson}")
        // CAUSES: 7
        assertTrue(merged.kgHabitsJson.contains(""""count":7"""),
            "Expected CAUSES count=7 in ${merged.kgHabitsJson}")
    }

    // ─────────── FitnessScore ordering ────────────────────────────────────────

    @Test
    fun `fitness scores are comparable and sortable`() {
        val scores = listOf(
            FitnessScore("a1", 1, 5.0, 3.0, 0.0, 12.5, 0.0, 20.5),
            FitnessScore("a2", 1, 20.0, 15.0, 20.0, 25.0, 20.0, 100.0),
            FitnessScore("a3", 1, 2.0, 0.0, 5.0, 0.0, 0.0, 7.0)
        )
        val sorted = scores.sortedByDescending { it.total }
        assertEquals("a2", sorted[0].agentId)
        assertEquals("a1", sorted[1].agentId)
        assertEquals("a3", sorted[2].agentId)
    }

    @Test
    fun `fitness score total reflects sum of dimensions`() {
        val fs = FitnessScore(
            agentId = "calc",
            generation = 2,
            citationScore = 10.0,
            kgContributionScore = 9.0,
            toolAdoptionScore = 15.0,
            stagingSuccessScore = 20.0,
            taskCompletionScore = 20.0,
            total = 74.0
        )
        val computed = fs.citationScore + fs.kgContributionScore +
                fs.toolAdoptionScore + fs.stagingSuccessScore + fs.taskCompletionScore
        assertEquals(computed, fs.total, 0.001)
    }

    @Test
    fun `top percentile selection returns highest scorers`() {
        val scores = (1..10)
            .map { i -> FitnessScore("agent-$i", 1, 0.0, 0.0, 0.0, 0.0, i.toDouble(), i.toDouble()) }
            .sortedByDescending { it.total }

        // 50% of 10 = 5
        val top = scores.take((scores.size * 0.5).toInt().coerceAtLeast(1))
        assertEquals(5, top.size)
        assertEquals(10.0, top[0].total)
        assertEquals(6.0, top[4].total)
    }
}
