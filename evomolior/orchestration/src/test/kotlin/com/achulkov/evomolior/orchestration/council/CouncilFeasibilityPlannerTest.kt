package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.CouncilFeasibilityReport
import com.achulkov.evomolior.core.council.CouncilFeasibilityStatus
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.creditAffordableWorkerSlots
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouncilFeasibilityPlannerTest {
    private val fixtures = StartingFieldCompilerTest()

    @Test
    fun `independent roots validation delivery need three dependency waves and four group slots`() {
        val report = CouncilFeasibilityPlanner.assess(
            fourNodes(), fixtures.envelope().copy(maxAgentsPerGroupPerRound = 1, maxRounds = 1), 0
        )
        assertEquals(CouncilFeasibilityStatus.PROVISIONAL_ONLY, report.status)
        assertEquals(3, report.dependencyWaveLowerBound)
        assertEquals(4, report.perGroupNodeRoundLowerBound)
        assertEquals(4, report.minimumRoundsLowerBound)
        assertEquals(2, report.workerGroupCount)
    }

    @Test
    fun `more workers cannot collapse prerequisite waves into one round`() {
        val report = CouncilFeasibilityPlanner.assess(
            fourNodes(), fixtures.envelope().copy(maxAgentsPerGroupPerRound = 4, maxRounds = 1), 0
        )
        assertEquals(3, report.minimumRoundsLowerBound)
        assertEquals(CouncilFeasibilityStatus.PROVISIONAL_ONLY, report.status)
    }

    @Test
    fun `sufficient unchanged horizon fits necessary bounds and report serializes`() {
        val report = CouncilFeasibilityPlanner.assess(
            fourNodes(), fixtures.envelope().copy(maxAgentsPerGroupPerRound = 1, maxRounds = 4), 1
        )
        assertEquals(CouncilFeasibilityStatus.FITS_SCHEDULING_BOUNDS, report.status)
        assertEquals(1, report.replansUsed)
        assertEquals(
            report,
            Json.decodeFromString(
                CouncilFeasibilityReport.serializer(),
                Json.encodeToString(CouncilFeasibilityReport.serializer(), report)
            )
        )
    }

    @Test
    fun `parent links are not executable prerequisites and unused alternatives do not consume capacity`() {
        val source = fixtures.outputs()
        val optional = source.taskAnatomist.nodes.first().copy(
            key = LocalNodeKey("optional"),
            alternativeToKeys = setOf(source.taskAnatomist.nodes.first().key)
        )
        val outputs = source.copy(taskAnatomist = source.taskAnatomist.copy(
            nodes = source.taskAnatomist.nodes + optional
        ))
        val report = CouncilFeasibilityPlanner.assess(
            outputs, fixtures.envelope().copy(maxAgentsPerGroupPerRound = 2, maxRounds = 1), 0
        )
        assertEquals(1, report.dependencyWaveLowerBound)
        assertEquals(1, report.perGroupNodeRoundLowerBound)
        assertEquals(CouncilFeasibilityStatus.FITS_SCHEDULING_BOUNDS, report.status)
    }

    @Test
    fun `mandatory prerequisite closure remains work even when prerequisite is not stage mandatory`() {
        val source = fourNodes()
        val outputs = source.copy(synthesis = source.synthesis.copy(
            stages = source.synthesis.stages.map { it.copy(mandatoryNodeKeys = setOf(LocalNodeKey("delivery"))) }
        ))
        val report = CouncilFeasibilityPlanner.assess(
            outputs, fixtures.envelope().copy(maxAgentsPerGroupPerRound = 1, maxRounds = 1), 0
        )
        assertEquals(4, report.perGroupNodeRoundLowerBound)
        assertEquals(3, report.dependencyWaveLowerBound)
    }

    @Test
    fun `credit-limited runs plan with affordable rather than nominal worker slots`() {
        // October 2026 mixed run shape: 300 credits, 2 groups, 4 rounds, 3 nominal slots,
        // Library groups (50-credit grants) -> 37.5 credits per group-round funds one worker.
        val envelope = fixtures.envelope().copy(
            maxAgentsPerGroupPerRound = 3,
            maxRounds = 4,
            cumulativeCostCap = 300.0,
            permittedProfiles = setOf(GroupProfileType.Library)
        )
        assertEquals(1, envelope.creditAffordableWorkerSlots)

        val report = CouncilFeasibilityPlanner.assess(fourNodes().withIndependentNodes(10), envelope, 0)

        assertEquals(CouncilFeasibilityStatus.PROVISIONAL_ONLY, report.status)
        assertEquals(1, report.slotsPerGroupPerRound)
        assertEquals(10, report.perGroupNodeRoundLowerBound)
        assertTrue(report.reasons.any { it.contains("Only 1 of 3 nominal worker slots") }, report.reasons.toString())
    }

    @Test
    fun `affordable slots never exceed nominal slots and ignore absent caps`() {
        val base = fixtures.envelope().copy(permittedProfiles = setOf(GroupProfileType.Library))
        assertEquals(4, base.copy(cumulativeCostCap = null).creditAffordableWorkerSlots)
        assertEquals(4, base.copy(cumulativeCostCap = 1_000_000.0).creditAffordableWorkerSlots)
        // 2 groups x 4 rounds; 1,200 credits = 150 per group-round = three 50-credit grants.
        assertEquals(3, base.copy(cumulativeCostCap = 1_200.0).creditAffordableWorkerSlots)
        assertEquals(1, base.copy(maxAgentsPerGroupPerRound = 1, cumulativeCostCap = 8.0).creditAffordableWorkerSlots)
    }

    private fun com.achulkov.evomolior.core.council.CouncilRoleOutputBundle.withIndependentNodes(count: Int) =
        let { source ->
            val template = source.taskAnatomist.nodes.first().copy(prerequisiteKeys = emptySet())
            val nodes = (1..count).map { template.copy(key = LocalNodeKey("independent-$it")) }
            source.copy(
                taskAnatomist = source.taskAnatomist.copy(nodes = nodes),
                synthesis = source.synthesis.copy(stages = source.synthesis.stages.map {
                    it.copy(nodeKeys = nodes.mapTo(linkedSetOf()) { node -> node.key },
                        mandatoryNodeKeys = nodes.mapTo(linkedSetOf()) { node -> node.key })
                })
            )
        }

    private fun fourNodes() = fixtures.outputs().let { source ->
        val template = source.taskAnatomist.nodes.first()
        val first = template.copy(key = LocalNodeKey("first"))
        val second = template.copy(key = LocalNodeKey("second"))
        val validation = template.copy(
            key = LocalNodeKey("validation"),
            prerequisiteKeys = setOf(first.key, second.key)
        )
        val delivery = template.copy(
            key = LocalNodeKey("delivery"),
            prerequisiteKeys = setOf(validation.key)
        )
        val nodes = listOf(first, second, validation, delivery)
        source.copy(
            taskAnatomist = source.taskAnatomist.copy(nodes = nodes),
            synthesis = source.synthesis.copy(stages = source.synthesis.stages.map {
                it.copy(nodeKeys = nodes.mapTo(linkedSetOf()) { it.key },
                    mandatoryNodeKeys = nodes.mapTo(linkedSetOf()) { it.key })
            })
        )
    }
}
