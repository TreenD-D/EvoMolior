package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeliverableSkeletonTreeStoreGapTest {

    @Test
    fun `open gaps append only actionable notes from failing updates`() {
        val runId = "run-dst-gaps"
        val groupId = "group-0"
        val store = DeliverableSkeletonTreeStore(
            SubstrateDatabaseFactory(createTempDirectory("dst-gap-test-").toFile())
        )
        store.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = "dst:section:quality",
                        title = "Quality",
                        nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION
                    )
                )
            )
        )

        val passed = store.applyUpdate(
            runId = runId,
            groupId = groupId,
            update = update(
                graderNotes = listOf("Synthesised from round scoring for group group-0"),
                passedThreshold = true,
                trustScore = 0.6
            )
        )

        assertNotNull(passed)
        assertTrue(passed.nodes.single().openGaps.isEmpty())

        val failed = store.applyUpdate(
            runId = runId,
            groupId = groupId,
            update = update(
                graderNotes = listOf(
                    "",
                    "Synthesised from round scoring for group group-0",
                    "Improve Correctness to at least 60.0/100.0 (current 59.9/100.0)."
                ),
                passedThreshold = false,
                trustScore = 0.599
            )
        )

        assertNotNull(failed)
        val node = failed.nodes.single()
        assertEquals(DeliverableSkeletonNodeStatus.CONTESTED, node.status)
        assertEquals(
            listOf("Improve Correctness to at least 60.0/100.0 (current 59.9/100.0)."),
            node.openGaps
        )
        assertTrue(node.openGaps.none { it.startsWith("Synthesised from round scoring") })
    }

    private fun update(
        graderNotes: List<String>,
        passedThreshold: Boolean,
        trustScore: Double
    ) = DeliverableSkeletonNodeUpdate(
        dstNodeRef = "dst:section:quality",
        checkinId = "round-1",
        dimensionScores = mapOf("correctness" to trustScore * 100.0),
        graderIds = listOf("grader-1"),
        rubricVersion = "rubric-1",
        graderNotes = graderNotes,
        passedThreshold = passedThreshold,
        artifactRef = "group:group-0:round:1",
        artifactType = DeliverableArtifactType.REPO_SNAPSHOT,
        trustScore = trustScore,
        owningGeneration = 1
    )
}
