package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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

    @Test
    fun `markdown renders clean node titles and complete artifact content once`() = runBlocking {
        val dataRoot = createTempDirectory("final-synth-markdown-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-final-synth-markdown"
        val groupId = "group-0"
        val treeStore = DeliverableSkeletonTreeStore(factory)
        val db = factory.get(runId, groupId)
        val longContent = "BEGIN-" + "control evidence ".repeat(220) + "-END"
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "artifact-1",
                content = longContent,
                sourceAgentId = "agent-1",
                generation = 1,
                domain = "audit",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1L
            )
        )
        treeStore.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Root",
                        nodeType = DeliverableSkeletonNodeType.ROOT,
                        children = listOf("dst:controls")
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:controls",
                        parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Control Design",
                        nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                        currentArtifactRef = "kb:artifact-1",
                        owningRole = Role.Researcher
                    )
                )
            )
        )

        val markdown = FinalSynthesiser(treeStore, db, runId, groupId).synthesise(
            format = FinalSynthesiser.OutputFormat.MARKDOWN,
            outputDir = createTempDirectory("final-synth-markdown-output-").toFile(),
            objective = "Operational audit"
        ).readText()

        assertContains(markdown, "## Control Design")
        assertContains(markdown, "-END")
        assertEquals(1, Regex("> \\*\\*Ownership\\*\\*: Researcher").findAll(markdown).count())
        kotlin.test.assertFalse("DeliverableSkeletonNode(" in markdown)
    }

    @Test
    fun `markdown applies explicit artifact publication limit with visible marker`() = runBlocking {
        val dataRoot = createTempDirectory("final-synth-limit-").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-final-synth-limit"
        val groupId = "group-0"
        val treeStore = DeliverableSkeletonTreeStore(factory)
        val db = factory.get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "large-artifact",
                content = "x".repeat(80_000),
                sourceAgentId = "agent-1",
                generation = 1,
                domain = "audit",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1L
            )
        )
        treeStore.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Root",
                        nodeType = DeliverableSkeletonNodeType.ROOT,
                        children = listOf("dst:large")
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:large",
                        parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Large Artifact",
                        nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                        currentArtifactRef = "kb:large-artifact"
                    )
                )
            )
        )

        val markdown = FinalSynthesiser(treeStore, db, runId, groupId).synthesise(
            format = FinalSynthesiser.OutputFormat.MARKDOWN,
            outputDir = createTempDirectory("final-synth-limit-output-").toFile(),
            objective = "Bounded output"
        ).readText()

        assertContains(markdown, "[Artifact content truncated by EvoMolior publication limits.]")
        kotlin.test.assertTrue(markdown.length < 70_000)
    }
}
