package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.model.Role
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausalFitnessContractsTest {
    @Test
    fun `record hash commits exact plane components and authorities`() {
        val record = workerRecord().withComputedHash()

        assertTrue(record.hasValidHash())
        assertFalse(record.copy(recordId = "fitness-2").hasValidHash())
        assertFalse(
            record.copy(
                components = record.components.map {
                    it.copy(contributionEdgeIds = setOf("edge-2"))
                }
            ).hasValidHash()
        )
    }

    @Test
    fun `roles cannot cross planes and Outliers never receive Legacy`() {
        assertFailsWith<IllegalArgumentException> {
            workerRecord().copy(role = Role.Critic)
        }
        assertFailsWith<IllegalArgumentException> {
            workerRecord().copy(role = Role.Outlier, legacyEligible = true)
        }
        assertFailsWith<IllegalArgumentException> {
            workerRecord().copy(role = Role.CouncilSeedCurator)
        }
    }

    @Test
    fun `components reject untraceable values`() {
        assertFailsWith<IllegalArgumentException> {
            CausalFitnessComponent(
                kind = FitnessComponentKind.ACCEPTED_NODE_VALUE,
                value = 1.0
            )
        }
    }

    private fun workerRecord() = CausalFitnessRecord(
        recordId = "fitness-1",
        runId = "run-1",
        groupId = AtlasGroupId("group-1"),
        round = 1,
        agentId = "agent-1",
        role = Role.Researcher,
        plane = FitnessPlane.WORKER,
        components = listOf(
            CausalFitnessComponent(
                kind = FitnessComponentKind.EVIDENCE_LINEAGE_QUALITY,
                value = 1.0,
                contributionEdgeIds = setOf("edge-1")
            )
        ),
        total = 1.0,
        legacyEligible = true
    )
}
