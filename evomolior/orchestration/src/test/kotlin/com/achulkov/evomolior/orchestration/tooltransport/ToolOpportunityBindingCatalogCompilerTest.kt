package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.WorkerView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolOpportunityBindingCatalogCompilerTest {
    @Test
    fun `active lease produces a short handle retaining historical commitment hashes`() {
        val fixture = ToolTransportTestFixture
        val node = RevealedNodeCard(
            nodeId = fixture.commitment.nodeId,
            kind = NodeKind.TOOL,
            receiverWhyRef = "receiver-tool",
            limitationStatement = "Repeated arithmetic is error prone.",
            capabilityTarget = "A reusable ratio calculator.",
            atomicQuestion = "Can the ratio calculation be automated?",
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("Public boundary test passes")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.TOOL)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = fixture.commitment.opportunity,
            payloadHash = "node-payload"
        )
        val view = WorkerView(
            runId = fixture.commitment.runId,
            groupId = AtlasGroupId("group-transport"),
            round = 2,
            stageId = "stage-tool",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-tool",
                atlasVersion = 2,
                atlasHash = "current-atlas-hash",
                rootNodeIds = setOf(node.nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(node),
            assignedNode = node,
            groupFeedback = null,
            assignedToolSkeleton = fixture.skeleton,
            assignedToolOpportunityOriginAtlasHash = fixture.commitment.atlasHash,
            assignedToolOpportunityCommitmentHash = fixture.commitment.commitmentHash
        ).withComputedHash()
        val capability = ToolOpportunityBindingCapability(
            runId = view.runId,
            groupId = view.groupId.value,
            round = view.round,
            workerViewHash = view.viewHash
        )

        val catalog = ToolOpportunityBindingCatalogCompiler.compile(
            view = view,
            lease = ToolOpportunityLeaseAuthority(
                leaseId = "lease-tool",
                nodeId = node.nodeId.value,
                capability = capability
            ),
            commitment = fixture.commitment
        )

        assertTrue(catalog.hasValidHash())
        assertEquals("T1", catalog.bindings.single().handle)
        assertEquals(
            fixture.commitment.commitmentHash,
            catalog.bindings.single().opportunityHash
        )
        assertFailsWith<IllegalArgumentException> {
            ToolOpportunityBindingCatalogCompiler.compile(
                view,
                ToolOpportunityLeaseAuthority("lease-foreign", "node-other", capability),
                fixture.commitment
            )
        }
    }
}
