package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliverableSkeletonTreeCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `older starting field payloads still fall back to node graph when tree seed is absent`() {
        val dataRoot = createTempDirectory("dst-compat-test-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val store = DeliverableSkeletonTreeStore(factory, json)
        val runId = "run-dst-compat"
        val groupId = "group-0"

        val legacyPayload = """
            {
              "runId": "$runId",
              "objective": "legacy objective",
              "globalBudgetCeiling": 1000.0,
              "generationCeiling": 10,
              "groupCount": 1,
              "groupProfiles": {"group-0": "Library"},
              "ringTopology": ["group-0"],
              "checkinMilestones": [5],
              "leakIntervalGenerations": 20,
              "dispatchLagGenerations": 5,
              "graderGroupCount": 0,
              "rolePrompts": {},
              "modelAssignment": {},
              "kgSchema": "{}",
              "actionCosts": {},
              "nodeGraph": "[{\"nodeId\":\"dst:doc:legacy\",\"title\":\"Legacy doc\",\"description\":\"Legacy doc\"}]",
              "primordialKb": [],
              "primordialKgFacts": [],
              "primordialTools": [],
              "deathNoteSchema": "{}",
              "legacyProtocolTemplate": "{}",
              "sealedTestSuiteHash": "",
              "norms": "",
              "stageContents": []
            }
        """.trimIndent()

        val startingField = json.decodeFromString(StartingField.serializer(), legacyPayload)
        assertEquals("{}", startingField.deliverableSkeletonTreeJson)

        val tree = store.initialise(runId, groupId, startingField)
        assertEquals(2, tree.nodes.size)
        assertEquals("dst:doc:legacy", tree.nodes.first { it.nodeId != DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID }.nodeId)
    }

    @Test
    fun `legacy deliverable skeleton json without owning role still deserializes`() {
        val legacyTreeJson = """
            {
              "runId": "run-legacy-tree",
              "groupId": "group-0",
              "rootNodeId": "dst:root",
              "nodes": [
                {
                  "nodeId": "dst:root",
                  "title": "Root",
                  "nodeType": "ROOT",
                  "children": ["dst:doc:legacy"]
                },
                {
                  "nodeId": "dst:doc:legacy",
                  "parentId": "dst:root",
                  "title": "Legacy doc",
                  "nodeType": "DOCUMENT_SECTION"
                }
              ]
            }
        """.trimIndent()

        val tree = json.decodeFromString(DeliverableSkeletonTree.serializer(), legacyTreeJson)

        assertEquals(null, tree.nodes.first { it.nodeId == "dst:doc:legacy" }.owningRole)
        assertEquals(2, tree.nodes.size)
        assertEquals(0.0, tree.summarize().ownershipClarityScore)
    }
}
