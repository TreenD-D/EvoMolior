package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Regression coverage proving final deliverables surface ownership metadata in all formats.
 */
class FinalSynthesiserOwnershipTest {

    @Test
    fun `plaintext and json outputs include ownership summary and node ownership`() = runBlocking {
        val dataRoot = createTempDirectory("final-synth-owner-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-final-synth-owner"
        val groupId = "group-0"
        val treeStore = DeliverableSkeletonTreeStore(factory)

        val tree = DeliverableSkeletonTree(
            runId = runId,
            groupId = groupId,
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Root",
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    children = listOf("dst:doc"),
                    owningRole = Role.Planner
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:doc",
                    parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    title = "Document",
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                    owningRole = Role.Researcher
                )
            )
        )
        treeStore.write(tree)

        val synthesiser = FinalSynthesiser(
            treeStore = treeStore,
            db = factory.get(runId, groupId),
            runId = runId,
            groupId = groupId
        )

        val plaintext = synthesiser.synthesise(
            format = FinalSynthesiser.OutputFormat.PLAINTEXT,
            outputDir = createTempDirectory("final-synth-owner-plaintext-").toFile(),
            objective = "Ownership output"
        )
        val plaintextContent = plaintext.readText()
        assertContains(plaintextContent, "Ownership clarity:")
        assertContains(plaintextContent, "owner: Researcher")

        val json = synthesiser.synthesise(
            format = FinalSynthesiser.OutputFormat.JSON,
            outputDir = createTempDirectory("final-synth-owner-json-").toFile(),
            objective = "Ownership output"
        )
        val jsonContent = json.readText()
        assertContains(jsonContent, "\"ownershipClarity\":")
        assertContains(jsonContent, "\"unownedNodes\":")
        assertContains(jsonContent, "\"owningRole\": \"Researcher\"")
    }
}
