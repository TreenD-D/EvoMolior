package com.achulkov.evomolior.api

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.resourcemap.ResourceMap
import com.achulkov.evomolior.competition.resourcemap.ResourceMapEngine
import com.achulkov.evomolior.competition.resourcemap.LegacyResourceCaptureMode
import com.achulkov.evomolior.competition.resourcemap.ResourceNode
import com.achulkov.evomolior.competition.resourcemap.ResourceType
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.ResourceNodeSeed
import com.achulkov.evomolior.core.contract.ResourceYieldType
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringResolution
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the resource-node bridge emits claim events from seeded node graphs.
 */
class EvoMoliorRunServiceResourceNodeBridgeTest {

    @Test
    fun `generic round dimensions cannot claim or recapture council resource nodes`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-grade-bridge-test").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("LifecycleManager should not be constructed") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val engine = ResourceMapEngine(
            ResourceMap(
                listOf(
                    ResourceNode(
                        id = "resource-market",
                        name = "Market evidence",
                        resourceType = ResourceType.WEB_CACHE,
                        yieldMultiplier = 1.2,
                        deliverableNodeRef = "dst:section:market",
                        qualityThreshold = 0.6
                    )
                )
            ),
            ownershipAuthority = com.achulkov.evomolior.competition.resourcemap.LocalProjectionResourceOwnershipAuthority
        )
        service.javaClass.getDeclaredField("activeResourceMapEngine").apply {
            isAccessible = true
            set(service, engine)
        }
        val dimension = ScoringDimension(
            id = "market-quality",
            name = "Market quality",
            description = "Quality of the market deliverable part",
            weight = 1.0
        )
        val config = RunConfig(
            runId = "run-resource-grade",
            objective = "Grade capture map parts",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            scoringDimensions = listOf(dimension),
            openRouterApiKey = "sk-test"
        )

        service.publishGradedResourceNodeClaims(
            config = config,
            gradingResult = gradingResult(
                round = 0,
                scores = mapOf("group-a" to 55.0, "group-b" to 80.0),
                dimensionNodeRefs = mapOf(dimension.id to "dst:section:market")
            ),
            round = 0
        )
        assertEquals(null, engine.snapshotClaims().single().claimedByGroupId)

        service.publishGradedResourceNodeClaims(
            config = config,
            gradingResult = gradingResult(
                round = 1,
                scores = mapOf("group-a" to 95.0, "group-b" to 70.0),
                dimensionNodeRefs = mapOf(dimension.id to "dst:section:market")
            ),
            round = 1
        )
        assertEquals(null, engine.snapshotClaims().single().claimedByGroupId)
    }

    @Test
    fun `publishResourceNodeClaims emits resource node claim events from seeded node graph`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-bridge-test").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this bridge test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val resourceMapEngine = ResourceMapEngine(
            resourceMap = ResourceMap(
                listOf(
                    ResourceNode(
                        id = "node-a",
                        name = "node a",
                        resourceType = ResourceType.TOOL_CACHE,
                        yieldMultiplier = 1.10
                    ),
                    ResourceNode(
                        id = "node-b",
                        name = "node b",
                        resourceType = ResourceType.GPU_BURST,
                        yieldMultiplier = 1.20
                    )
                )
            ),
            ownershipAuthority = com.achulkov.evomolior.competition.resourcemap.LocalProjectionResourceOwnershipAuthority
        )

        val engineField = service.javaClass.getDeclaredField("activeResourceMapEngine").apply {
            isAccessible = true
        }
        engineField.set(service, resourceMapEngine)

        val claimDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            service.events.first { it is EvoMoliorEvent.ResourceNodeClaimed }
        }

        service.publishResourceNodeClaims(
            runId = "run-resource-test",
            completedWipTasksByGroup = mapOf(
                "group-a" to listOf("node-a"),
                "group-b" to listOf("node-b")
            ),
            round = 0,
            legacyMode = LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )

        val event = claimDeferred.await() as EvoMoliorEvent.ResourceNodeClaimed
        assertEquals("run-resource-test", event.runId)
        assertTrue(event.nodeId in setOf("node-a", "node-b"))
        assertTrue(event.groupId in setOf("group-a", "group-b"))
        val persisted = substrateFactory.get("run-resource-test", event.groupId)
            .eventLogDao()
            .getLatestByType("run-resource-test", event.groupId, EventTypes.NODE_CLAIMED, 1)
        assertEquals(1, persisted.size)
        assertTrue(persisted.single().payloadJson.contains("\"resourceNodeId\":\"${event.nodeId}\""))
    }

    @Test
    fun `deriveResourceNodes includes deliverable work nodes absent from knowledge graph`() {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-seed-union-test").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager should not be constructed in resource seed test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val deliverableTree = DeliverableSkeletonTree(
            runId = "run-resource-seed",
            groupId = "group-a",
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    title = "Root"
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:section:market",
                    parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                    title = "Market section"
                )
            )
        )

        val nodes = service.deriveResourceNodes(
            nodeGraph = """[{"nodeId":"market-landscape","description":"Market landscape"}]""",
            deliverableSkeletonTreeJson = Json.encodeToString(
                DeliverableSkeletonTree.serializer(),
                deliverableTree
            )
        )

        assertEquals(
            setOf("market-landscape", "dst:section:market"),
            nodes.mapTo(mutableSetOf()) { it.id }
        )
    }

    @Test
    fun `deriveResourceNodes preserves council thresholds yields and deliverable mappings`() {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-seed-typed-test").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("LifecycleManager should not be constructed") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val tree = DeliverableSkeletonTree(
            runId = "run-resource-seed",
            groupId = "group-a",
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = "dst:root",
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    title = "Root",
                    children = listOf("dst:market")
                ),
                DeliverableSkeletonNode(
                    nodeId = "dst:market",
                    parentId = "dst:root",
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                    title = "Market evidence"
                )
            )
        )

        val node = service.deriveResourceNodes(
            resourceNodeSeeds = listOf(
                ResourceNodeSeed(
                    nodeId = "resource-market",
                    name = "Market territory",
                    deliverableNodeRef = "dst:market",
                    scoringDimensionIds = emptyList(),
                    resourceType = ResourceYieldType.GPU_BURST,
                    qualityThreshold = 0.72,
                    yieldMultiplier = 1.25,
                    floorGroupId = "group-a"
                )
            ),
            nodeGraph = "[]",
            deliverableSkeletonTreeJson = Json.encodeToString(DeliverableSkeletonTree.serializer(), tree)
        ).single()

        assertEquals("dst:market", node.deliverableNodeRef)
        assertEquals(ResourceType.GPU_BURST, node.resourceType)
        assertEquals(0.72, node.qualityThreshold)
        assertEquals(1.25, node.yieldMultiplier)
        assertEquals("group-a", node.floorGroupId)
    }

    @Test
    fun `deriveResourceNodes ignores unrelated council artifact enum values`() {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-seed-forward-compatible-test").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager should not be constructed in resource seed test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val nodes = service.deriveResourceNodes(
            nodeGraph = "[]",
            deliverableSkeletonTreeJson = """
                {
                  "runId": "run-resource-seed",
                  "groupId": "group-a",
                  "nodes": [
                    {
                      "nodeId": "dst:root",
                      "nodeType": "ROOT",
                      "artifactType": "ARTIFACT_BUNDLE",
                      "title": "Root"
                    },
                    {
                      "nodeId": "dst:section:market",
                      "parentId": "dst:root",
                      "nodeType": "DOCUMENT_SECTION",
                      "artifactType": "FUTURE_COUNCIL_ARTIFACT",
                      "title": "Market section"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(listOf("dst:section:market"), nodes.map { it.id })
    }

    @Test
    fun `processMetaPlannerSignals resets stasis after completion-driven bridge`() = runTest {
        val metaPlannerRunner = MetaPlannerRunner(stasisGenerationThreshold = 2)

        val spawned = mutableListOf<String>()
        metaPlannerRunner.onGenerationTick("group-a") { groupId, role ->
            spawned += "$groupId:${role.name}"
        }
        metaPlannerRunner.onWipCompleted("group-a")

        metaPlannerRunner.onGenerationTick("group-a") { groupId, role ->
            spawned += "$groupId:${role.name}"
        }

        assertTrue(spawned.isEmpty(), "WiP completion should reset the MetaPlanner stasis counter before the next tick")
    }

    @Test
    fun `critic completion reaches resource map exactly once`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-completion-integration").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager should not be constructed in resource completion test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val resourceMapEngine = ResourceMapEngine(
            ResourceMap(
                listOf(
                    ResourceNode(
                        id = "knowledge-node-a",
                        name = "node a",
                        resourceType = ResourceType.TOOL_CACHE,
                        yieldMultiplier = 1.10
                    )
                )
            ),
            ownershipAuthority = com.achulkov.evomolior.competition.resourcemap.LocalProjectionResourceOwnershipAuthority
        )
        service.javaClass.getDeclaredField("activeResourceMapEngine").apply {
            isAccessible = true
            set(service, resourceMapEngine)
        }

        val db = substrateFactory.get("run-resource-completion", "group-a")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "dst:section:a",
                    runId = "run-resource-completion",
                    groupId = "group-a",
                    description = "Complete node a.",
                    claimedByAgentId = "agent-a",
                    status = "claimed",
                    lastChangedGeneration = 0,
                    linkedNodeId = "knowledge-node-a",
                    dstNodeRef = "dst:section:a"
                )
            )
        )
        listOf("doc-a-1", "doc-a-2").forEachIndexed { index, id ->
            db.stagingDao().insert(
                StagingSubmissionEntity(
                    id = id,
                    agentId = "agent-a",
                    role = "Researcher",
                    type = "DOC",
                    content = "Reviewed artifact $id.",
                    wipTaskRef = "dst:section:a",
                    budgetSignature = "sig:$id",
                    generation = 0,
                    groupId = "group-a",
                    runId = "run-resource-completion",
                    submittedAtMs = index.toLong()
                )
            )
        }

        CriticPipeline(
            db = db,
            runId = "run-resource-completion",
            groupId = "group-a",
            generation = 0,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            criticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.8
                )
            },
            toolsMountHostPath = substrateFactory.dbFilePath("run-resource-completion", "group-a")
                .parentFile
                .resolve("tools")
                .absolutePath,
            onWipTaskCompleted = { groupId, nodeId, _, _ ->
                service.recordWipCompletion(groupId, nodeId)
            }
        ).processPendingSubmissions()

        val completions = service.drainWipCompletions("group-a")
        assertEquals(listOf("knowledge-node-a"), completions)
        service.publishResourceNodeClaims(
            runId = "run-resource-completion",
            completedWipTasksByGroup = mapOf("group-a" to completions),
            round = 0,
            legacyMode = LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )
        assertEquals("group-a", resourceMapEngine.snapshotClaims().single().claimedByGroupId)
        val persistedClaims = db.eventLogDao().getLatestByType(
            "run-resource-completion",
            "group-a",
            EventTypes.NODE_CLAIMED,
            10
        )
        assertEquals(1, persistedClaims.size)
    }

    private fun gradingResult(
        round: Int,
        scores: Map<String, Double>,
        dimensionNodeRefs: Map<String, String>
    ): GradingResult = GradingResult(
        roundIndex = round,
        scores = scores.map { (groupId, rawScore) ->
            RoundScore(
                groupId = groupId,
                roundIndex = round,
                dimensionScores = listOf(
                    DimensionScore(
                        dimensionId = "market-quality",
                        rawScore = rawScore,
                        justification = "sealed node-specific grade"
                    )
                ),
                weightedTotal = rawScore
            )
        },
        winnerId = scores.maxBy { it.value }.key,
        resolution = ScoringResolution.RESOLVED,
        dimensionNodeRefs = dimensionNodeRefs
    )
}
