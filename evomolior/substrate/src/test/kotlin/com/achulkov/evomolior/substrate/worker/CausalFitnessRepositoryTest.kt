package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.CausalFitnessComponent
import com.achulkov.evomolior.core.contract.CausalFitnessRecord
import com.achulkov.evomolior.core.contract.FitnessComponentKind
import com.achulkov.evomolior.core.contract.FitnessPlane
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CausalFitnessRepositoryTest {
    private val runId = "run-causal-fitness"
    private val groupId = AtlasGroupId("group-causal-fitness")

    @Test
    fun `records remain plane separated and require existing contribution edges`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("causal-fitness-repository-").toFile()
        ).get(runId, groupId.value)
        try {
            val graphRepository = ContributionGraphRepository(database)
            val graphHarness = ContributionGraphTestHarness(database, runId, groupId)
            val edge = graphHarness.persistStagedEdge(graphRepository)
            val repository = CausalFitnessRepository(database, now = { 2_000 })
            val record = CausalFitnessRecord(
                recordId = "fitness-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                agentId = "agent-1",
                role = Role.Researcher,
                plane = FitnessPlane.WORKER,
                components = listOf(
                    CausalFitnessComponent(
                        kind = FitnessComponentKind.EVIDENCE_LINEAGE_QUALITY,
                        value = 1.0,
                        contributionEdgeIds = setOf(edge.edgeId)
                    )
                ),
                total = 1.0,
                legacyEligible = true
            ).withComputedHash()

            assertEquals(record, repository.append(record))
            assertEquals(record, repository.append(record))
            assertEquals(
                listOf(record),
                repository.forPlane(runId, groupId.value, 1, FitnessPlane.WORKER)
            )
            assertEquals(
                listOf(record),
                repository.forAgent(runId, groupId.value, "agent-1")
            )

            val missingEdge = record.copy(
                recordId = "fitness-missing",
                components = record.components.map {
                    it.copy(contributionEdgeIds = setOf("missing-edge"))
                },
                recordHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.append(missingEdge)
            }
        } finally {
            database.close()
        }
    }
}
