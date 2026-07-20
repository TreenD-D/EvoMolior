package com.achulkov.evomolior.core.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OutlierNoveltyContractsTest {
    @Test
    fun `proposal hash excludes self hash and detects semantic mutation`() {
        val first = proposal().withComputedHash()

        assertTrue(first.hasValidHash())
        assertEquals(
            first.proposalHash,
            AtlasHashing.hashNoveltyVectorProposal(first.copy(proposalHash = "tampered"))
        )
        assertFalse(first.copy(proposedVector = "changed").hasValidHash())
    }

    @Test
    fun `proposal guardrails reject disconnected blank and non-finite inputs`() {
        assertFailsWith<IllegalArgumentException> {
            proposal(affectedVisibleNodeIds = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            proposal(missingLayerOrAssumption = " ", proposedVector = "test")
        }
        assertFailsWith<IllegalArgumentException> {
            proposal(expectedCost = Double.NaN)
        }
    }

    private fun proposal(
        affectedVisibleNodeIds: Set<NodeId> = linkedSetOf(NodeId("visible-a")),
        missingLayerOrAssumption: String = "A missing layer must be surfaced.",
        proposedVector: String = "Introduce a falsifiable novelty vector.",
        whyCurrentMapMayMissIt: String = "The current map lacks the supporting perspective.",
        falsifiablePrediction: String = "A minimal test will distinguish the new vector.",
        minimalTestOrArtifact: String = "Run one focused test artifact.",
        expectedReceiverImpact: String? = "Improves receiver usefulness.",
        expectedCost: Double = 1.0
    ): NoveltyVectorProposal = NoveltyVectorProposal(
        proposalId = "proposal-1",
        runId = "run-1",
        atlasHash = "atlas-hash",
        groupId = AtlasGroupId("group-1"),
        round = 4,
        proposerAgentId = AtlasAgentId("agent-1"),
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = affectedVisibleNodeIds,
        missingLayerOrAssumption = missingLayerOrAssumption,
        proposedVector = proposedVector,
        whyCurrentMapMayMissIt = whyCurrentMapMayMissIt,
        falsifiablePrediction = falsifiablePrediction,
        minimalTestOrArtifact = minimalTestOrArtifact,
        expectedReceiverImpact = expectedReceiverImpact,
        expectedCost = expectedCost,
        conflictWithExistingAssumptions = listOf("assumption-a", "assumption-b"),
        noveltyFingerprint = "fingerprint-1"
    )
}
