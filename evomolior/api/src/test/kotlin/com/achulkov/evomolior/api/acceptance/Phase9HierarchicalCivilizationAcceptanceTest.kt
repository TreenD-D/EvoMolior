package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.api.service.FinalSynthesiser
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.nested.NestedGroupConfig
import com.achulkov.evomolior.orchestration.nested.NestedGroupResult
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 9 acceptance: an inner civilization must produce useful artifacts that are rolled into
 * the outer substrate and consumed by outer execution and final composition.
 */
class Phase9HierarchicalCivilizationAcceptanceTest {
    @Test
    fun `inner document and executable tool are consumed by the outer final deliverable`() = runBlocking {
        val root = createTempDirectory("phase-9-hierarchy-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val runId = "phase-9-run"
        val outerGroupId = "group-0"
        val nestedGroupId = "inner-1"
        val nestedRunId = "$runId-nested-$nestedGroupId"
        val profile = GroupProfile.default(GroupProfileType.Library)
        val manager = LifecycleManager(
            runId = runId,
            groupId = outerGroupId,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = factory,
            agentJarPath = "/unused/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { _, _ -> error("Ordinary agents are outside this acceptance boundary") },
            nestedGroupRunBlock = { config ->
                val nestedDb = factory.get(nestedRunId, config.nestedGroupId)
                nestedDb.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "inner-doc",
                        content = "INNER_ROLLUP_DOCUMENT: invoke the nested verifier before publication.",
                        sourceAgentId = "inner-researcher",
                        generation = 1,
                        trustScore = 0.95,
                        citationCount = 2,
                        domain = "verification",
                        status = "active",
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 1_000L
                    )
                )
                nestedDb.toolRegistryDao().insert(
                    ToolRegistryEntryEntity(
                        id = "inner-tool",
                        name = "nested_verifier",
                        version = 1,
                        authorAgentId = "inner-builder",
                        successRate = 1.0,
                        inputSchemaJson = "{}",
                        outputSchemaJson = """{"type":"string"}""",
                        domain = "verification",
                        status = "active",
                        code = "echo INNER_TOOL_EXECUTED",
                        codeLanguage = "shell",
                        generation = 1,
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 2_000L
                    )
                )
                NestedGroupResult(
                    nestedGroupId = config.nestedGroupId,
                    outerGroupId = config.outerGroupId,
                    generationsCompleted = 2,
                    kbDocumentIds = listOf("inner-doc"),
                    kgFactIds = emptyList(),
                    promotedToolIds = listOf("inner-tool"),
                    budgetConsumed = 12.0,
                    completedNormally = true
                )
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completed = CompletableDeferred<NestedGroupResult>()

        val job = manager.spawnNestedGroup(
            NestedGroupConfig(
                nestedGroupId = nestedGroupId,
                outerGroupId = outerGroupId,
                outerRunId = runId,
                objective = "Produce a reusable verification procedure and tool.",
                budgetAllocation = 40.0,
                maxGenerations = 2,
                openRouterApiKey = "sk-test",
                agentJarPath = "/unused/agent.jar"
            ),
            scope,
            onCompleted = completed::complete
        )
        val result = completed.await()
        job.join()

        val importedDocumentId = result.importedKbDocumentIds.single()
        val importedToolId = result.importedToolIds.single()
        val outerDb = factory.get(runId, outerGroupId)
        assertNotNull(outerDb.knowledgeBaseDao().getById(importedDocumentId))
        val importedTool = assertNotNull(outerDb.toolRegistryDao().getById(importedToolId))
        val toolsRoot = factory.dbFilePath(runId, outerGroupId).parentFile.resolve("tools")
        assertTrue(toolsRoot.resolve("${importedTool.name}-v${importedTool.version}").canExecute())

        val executeResult = ToolSubSandbox(root.resolve("outer-workspace")).execute(
            toolsRoot.resolve("${importedTool.name}-v${importedTool.version}").absolutePath,
            "{}"
        )
        assertTrue(executeResult.success, executeResult.stderr)
        assertContains(executeResult.stdout, "INNER_TOOL_EXECUTED")

        val treeStore = DeliverableSkeletonTreeStore(factory)
        treeStore.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = outerGroupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Outer final deliverable",
                        nodeType = DeliverableSkeletonNodeType.ROOT,
                        children = listOf("dst:inner-doc", "dst:inner-tool"),
                        owningRole = Role.Planner
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:inner-doc",
                        parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Nested procedure",
                        nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                        status = DeliverableSkeletonNodeStatus.COMPLETE,
                        currentArtifactRef = "kb:$importedDocumentId",
                        artifactVersion = 1,
                        artifactType = DeliverableArtifactType.KB_DOC,
                        owningRole = Role.Researcher,
                        trustScore = 0.95
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:inner-tool",
                        parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        title = "Nested verifier",
                        nodeType = DeliverableSkeletonNodeType.TOOL,
                        status = DeliverableSkeletonNodeStatus.COMPLETE,
                        currentArtifactRef = "tool:$importedToolId",
                        artifactVersion = importedTool.version,
                        artifactType = DeliverableArtifactType.TOOL,
                        owningRole = Role.Builder,
                        trustScore = 1.0
                    )
                )
            )
        )
        val output = FinalSynthesiser(treeStore, outerDb, runId, outerGroupId)
            .synthesise(
                format = FinalSynthesiser.OutputFormat.MARKDOWN,
                outputDir = root.resolve("final-output"),
                objective = "Compose inner verification work into the outer result."
            )
            .readText()

        assertContains(output, "kb:$importedDocumentId")
        assertContains(output, "INNER_ROLLUP_DOCUMENT")
        assertContains(output, "tool:$importedToolId")
        assertContains(output, "INNER_TOOL_EXECUTED")
        scope.cancel()
    }
}
