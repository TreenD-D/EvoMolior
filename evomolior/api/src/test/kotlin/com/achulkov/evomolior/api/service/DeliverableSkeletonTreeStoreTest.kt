package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                    owningRole = Role.Researcher
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
                owningAgentId = "agent-1",
                owningRole = Role.Researcher
            )
        )

        assertNotNull(updated)
        val updatedNode = updated.nodes.first { it.nodeId == "dst:doc:intro" }
        assertEquals(DeliverableSkeletonNodeStatus.COMPLETE, updatedNode.status)
        assertEquals("kb:123", updatedNode.currentArtifactRef)
        assertFalse(updatedNode.openGaps.contains("Add a closing summary"))
        assertTrue(updatedNode.gradingHistory.single().graderNotes.contains("Add a closing summary"))
        assertEquals(1, updatedNode.gradingHistory.size)
        assertEquals(Role.Researcher, updatedNode.owningRole)
        assertEquals(2, updated.version)
    }

    @Test
    fun `tree store treats council tree as a template for the active run and group`() {
        val dataRoot = createTempDirectory("dst-store-identity-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val store = DeliverableSkeletonTreeStore(factory, json)
        val activeRunId = "run-active"
        val activeGroupId = "group-1"
        val councilTreeJson = """
            {
              "runId": "default",
              "groupId": "group-0",
              "nodes": [
                {
                  "nodeId": "dst:root",
                  "nodeType": "ROOT",
                  "artifactType": "ARTIFACT_BUNDLE",
                  "title": "Decision brief",
                  "children": ["dst:section:market"]
                },
                {
                  "nodeId": "dst:section:market",
                  "parentId": "dst:root",
                  "nodeType": "DOCUMENT_SECTION",
                  "artifactType": "FUTURE_COUNCIL_ARTIFACT",
                  "title": "Market"
                }
              ]
            }
        """.trimIndent()
        val startingField = StartingField.default(
            objective = "template identity",
            runId = activeRunId,
            groupCount = 2
        ).copy(deliverableSkeletonTreeJson = councilTreeJson)

        val initial = store.initialise(activeRunId, activeGroupId, startingField)

        assertEquals(activeRunId, initial.runId)
        assertEquals(activeGroupId, initial.groupId)
        assertEquals(2, initial.nodes.size)
        assertEquals(DeliverableArtifactType.OTHER, initial.nodes.first().artifactType)
        assertTrue(store.resolveFile(activeRunId, activeGroupId).isFile)
        assertFalse(store.resolveFile("default", "group-0").exists())
        assertEquals(initial, store.read(activeRunId, activeGroupId))
    }
}
