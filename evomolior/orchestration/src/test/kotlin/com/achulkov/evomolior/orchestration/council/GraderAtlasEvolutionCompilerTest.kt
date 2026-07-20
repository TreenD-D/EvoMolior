package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderAtlasValidation
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraderAtlasEvolutionCompilerTest {
    private val workerNodeId = NodeId("worker-node")
    private val addedWorkerNodeId = NodeId("amended-worker-node")
    private val graderNodeId = GraderNodeId("grader-node")

    @Test
    fun `evolution preserves grader semantics and expands exact worker coverage`() {
        val currentWorker = workerAtlas()
        val nextWorker = currentWorker.copy(
            version = 2,
            parentAtlasHash = currentWorker.atlasHash,
            nodes = currentWorker.nodes + (
                addedWorkerNodeId to currentWorker.nodes.getValue(workerNodeId).copy(
                    id = addedWorkerNodeId,
                    payloadHash = ""
                ).withComputedPayloadHash()
                ),
            atlasHash = ""
        ).withComputedHash()
        val currentGrader = graderAtlas(currentWorker)

        val nextGrader = GraderAtlasEvolutionCompiler.evolve(
            currentGrader,
            currentWorker,
            nextWorker
        )

        assertEquals(currentGrader.graderAtlasId, nextGrader.graderAtlasId)
        assertEquals(2, nextGrader.version)
        assertEquals(nextWorker.atlasHash, nextGrader.workerAtlasHash)
        assertEquals(nextWorker.nodes.keys, nextGrader.nodes.getValue(graderNodeId).mappedWorkerNodeIds)
        assertEquals(
            currentGrader.nodes.getValue(graderNodeId).evaluationQuestion,
            nextGrader.nodes.getValue(graderNodeId).evaluationQuestion
        )
        assertTrue(
            GraderAtlasValidation.validateEvolution(
                currentGrader,
                nextGrader,
                currentWorker,
                nextWorker
            ).isEmpty()
        )
    }

    @Test
    fun `evolution rejects a worker child with the wrong parent`() {
        val currentWorker = workerAtlas()
        val wrongChild = currentWorker.copy(
            version = 2,
            parentAtlasHash = "different-parent",
            atlasHash = ""
        ).withComputedHash()

        assertFailsWith<IllegalArgumentException> {
            GraderAtlasEvolutionCompiler.evolve(
                graderAtlas(currentWorker),
                currentWorker,
                wrongChild
            )
        }
    }

    private fun workerAtlas(): NodeAtlas {
        val node = com.achulkov.evomolior.core.atlas.LimitationNode(
            id = workerNodeId,
            kind = com.achulkov.evomolior.core.atlas.NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver:evidence",
            limitationStatement = "Evidence is incomplete.",
            capabilityTarget = "Produce exact evidence.",
            atomicQuestion = "Is the evidence exact?",
            proofContract = com.achulkov.evomolior.core.atlas.ProofContract(
                com.achulkov.evomolior.core.atlas.ProofMethod.RECEIVER_VALIDATION,
                setOf("Exact evidence")
            ),
            deliverableContract =
                com.achulkov.evomolior.core.atlas.NodeDeliverableContract(
                    setOf(com.achulkov.evomolior.core.atlas.ArtifactType.EVIDENCE_TABLE)
                ),
            revealPolicy = com.achulkov.evomolior.core.atlas.RevealPolicy(
                "stage-0",
                com.achulkov.evomolior.core.atlas.RevealMode.INITIAL
            ),
            replicationPolicy = com.achulkov.evomolior.core.atlas.ReplicationPolicy(),
            capturePolicy = com.achulkov.evomolior.core.atlas.CapturePolicy(
                minimumGrade = 0.7
            ),
            rewardSpec = com.achulkov.evomolior.core.atlas.RewardSpec(
                com.achulkov.evomolior.core.atlas.RewardClass.SMALL,
                1.0
            )
        ).withComputedPayloadHash()
        val nodes = mapOf(workerNodeId to node)
        return NodeAtlas(
            atlasId = "worker-atlas",
            runId = "run-evolution",
            version = 1,
            receiverContractHash = AtlasHashing.hashText("receiver"),
            rootNodeIds = setOf(workerNodeId),
            nodes = nodes,
            stageDefinitions = listOf(
                com.achulkov.evomolior.core.atlas.DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Initial",
                    nodeIds = setOf(workerNodeId),
                    mandatoryNodeIds = setOf(workerNodeId),
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(workerNodeId),
                        nodes
                    )
                )
            )
        )
        .withComputedHash()
    }

    private fun graderAtlas(workerAtlas: NodeAtlas): GraderAtlas {
        val node = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is the evidence authentic?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = workerAtlas.nodes.keys
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas",
            runId = workerAtlas.runId,
            version = workerAtlas.version,
            workerAtlasHash = workerAtlas.atlasHash,
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(graderNodeId to node)
        ).withComputedHash()
    }
}
