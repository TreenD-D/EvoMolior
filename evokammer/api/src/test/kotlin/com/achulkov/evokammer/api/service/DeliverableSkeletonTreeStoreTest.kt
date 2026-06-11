package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.DeliverableArtifactType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeliverableSkeletonTreeStoreTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `tree store initialises from starting field and applies node updates`() {
        val dataRoot = createTempDirectory("dst-store-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val store = DeliverableSkeletonTreeStore(factory, json)
        val runId = "run-dst"
        val groupId = "group-0"

        val tree = DeliverableSkeletonTree(
            runId = runId,
            groupId = groupId,
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Root",
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    children = listOf("dst:doc:intro")
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:doc:intro",
                    parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Introduction",
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION
                )
            )
        )

        val startingField = StartingField.default("deliverable tree", runId = runId, groupCount = 1, groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)).copy(
            deliverableSkeletonTreeJson = json.encodeToString(DeliverableSkeletonTree.serializer(), tree)
        )

        val initial = store.initialise(runId, groupId, startingField)
        assertEquals(2, initial.nodes.size)
        assertEquals(0, initial.summarize().openGapCount)

        val updated = store.applyUpdate(
            runId = runId,
            groupId = groupId,
            update = DeliverableSkeletonNodeUpdate(
                dstNodeRef = "dst:doc:intro",
                checkinId = "ci-1",
                dimensionScores = mapOf("accuracy" to 88.0),
                graderIds = listOf("grader-1"),
                rubricVersion = "rubric-1",
                graderNotes = listOf("Add a closing summary"),
                passedThreshold = true,
                artifactRef = "kb:123",
                artifactType = DeliverableArtifactType.KB_DOC,
                trustScore = 0.9,
                owningGeneration = 2,
                owningAgentId = "agent-1"
            )
        )

        assertNotNull(updated)
        val updatedNode = updated.nodes.first { it.nodeId == "dst:doc:intro" }
        assertEquals(DeliverableSkeletonNodeStatus.COMPLETE, updatedNode.status)
        assertEquals("kb:123", updatedNode.currentArtifactRef)
        assertTrue(updatedNode.openGaps.contains("Add a closing summary"))
        assertEquals(1, updatedNode.gradingHistory.size)
        assertEquals(2, updated.version)
    }
}
