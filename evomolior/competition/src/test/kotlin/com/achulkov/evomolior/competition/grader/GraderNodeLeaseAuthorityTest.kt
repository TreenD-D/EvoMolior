package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraderNodeLeaseAuthorityTest {
    @Test
    fun `frontier stays hidden until prerequisite requirements commit`() = runTest {
        var now = 100L
        val atlas = atlas("hidden-frontier")
        val repository = repository()
        repository.appendAtlas(atlas)
        val authority = GraderNodeLeaseAuthority(repository) { now }
        authority.initialize(atlas)

        val initial = authority.view(atlas, "grader-a", round = 0)
        assertEquals(listOf("root"), initial.nodes.map { it.nodeId.value })
        assertEquals(null, initial.nodes.single().activeLeaseId)
        assertEquals(null, initial.nodes.single().activeLeaseHash)

        val lease = authority.acquireNext(atlas, "grader-a", round = 0, ttlMillis = 50)
        val leasedView = authority.view(atlas, "grader-a", round = 0)
        assertTrue(leasedView.nodes.single().leasedByThisGroup)
        assertEquals(GraderNodeStatus.LEASED, leasedView.nodes.single().status)
        assertEquals(lease.leaseId, leasedView.nodes.single().activeLeaseId)
        assertEquals(lease.leaseHash, leasedView.nodes.single().activeLeaseHash)
        val independentLease =
            authority.acquireNext(atlas, "grader-b", round = 0, ttlMillis = 50)
        assertEquals(lease.nodeId, independentLease.nodeId)

        authority.commit(atlas, lease, setOf("requirement-hash"))
        val committedView = authority.view(atlas, "grader-b", round = 0)
        assertEquals(listOf("root"), committedView.nodes.map { it.nodeId.value })
        assertEquals(
            GraderNodeStatus.LEASED,
            committedView.nodes.single { it.nodeId.value == "root" }.status
        )
        authority.commit(atlas, independentLease, setOf("independent-requirement-hash"))
        val independentlyCommittedView = authority.view(atlas, "grader-b", round = 0)
        assertEquals(
            listOf("child", "root"),
            independentlyCommittedView.nodes.map { it.nodeId.value }
        )
        assertEquals(
            GraderNodeStatus.OPEN,
            independentlyCommittedView.nodes.single { it.nodeId.value == "child" }.status
        )
    }

    @Test
    fun `expired lease releases node for deterministic reacquisition`() = runTest {
        var now = 100L
        val atlas = atlas("expiry")
        val repository = repository()
        repository.appendAtlas(atlas)
        val authority = GraderNodeLeaseAuthority(repository) { now }
        authority.initialize(atlas)

        val first = authority.acquireNext(atlas, "grader-a", round = 0, ttlMillis = 10)
        now = 110L
        val afterExpiry = authority.view(atlas, "grader-b", round = 0)
        assertEquals(GraderNodeStatus.OPEN, afterExpiry.nodes.single().status)
        assertFalse(afterExpiry.nodes.single().leasedByThisGroup)
        assertEquals(null, afterExpiry.nodes.single().activeLeaseId)
        assertEquals(null, afterExpiry.nodes.single().activeLeaseHash)

        val second = authority.acquireNext(atlas, "grader-b", round = 0, ttlMillis = 10)
        assertEquals(first.nodeId, second.nodeId)
        assertEquals("grader-b", second.graderGroupId)
        assertFailsWith<IllegalArgumentException> {
            authority.commit(atlas, first, setOf("late-requirement"))
        }
    }

    private fun repository() = GraderAuthorityRepository(
        SubstrateDatabaseFactory(createTempDirectory("grader-node-lease-").toFile()),
        now = { 1L }
    )

    private fun atlas(suffix: String): GraderAtlas {
        val rootId = GraderNodeId("root")
        val childId = GraderNodeId("child")
        val workerNode = NodeId("worker")
        val root = GraderAtlasNode(
            nodeId = rootId,
            kind = GraderNodeKind.RECEIVER_COVERAGE,
            title = "Receiver coverage",
            evaluationQuestion = "Does the output satisfy the receiver?",
            receiverObligationRefs = setOf("receiver:result"),
            mappedWorkerNodeIds = setOf(workerNode)
        ).withComputedHash()
        val child = GraderAtlasNode(
            nodeId = childId,
            kind = GraderNodeKind.ADVERSARIAL_ROBUSTNESS,
            title = "Adversarial robustness",
            evaluationQuestion = "Does the output survive adversarial cases?",
            receiverObligationRefs = setOf("receiver:result"),
            mappedWorkerNodeIds = setOf(workerNode),
            prerequisiteNodeIds = setOf(rootId)
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas-$suffix",
            runId = "run-$suffix",
            version = 1,
            workerAtlasHash = "worker-atlas-hash",
            rootNodeIds = setOf(rootId),
            nodes = mapOf(rootId to root, childId to child)
        ).withComputedHash()
    }
}
