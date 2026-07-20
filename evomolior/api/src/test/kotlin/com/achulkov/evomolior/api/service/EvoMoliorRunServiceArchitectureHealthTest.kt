package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceArchitectureHealthTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `primordial seeding creates distinct group strategy hypotheses`() = runTest {
        val root = createTempDirectory("run-service-group-strategy-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-group-strategy"
        val groupIds = listOf("group-0", "group-1", "group-2")
        val startingField = StartingField.default(
            objective = "Create an evidence-grounded architecture audit.",
            runId = runId,
            groupCount = groupIds.size
        )

        service::class.declaredFunctions.first { it.name == "seedPrimordialData" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, groupIds, startingField, emptyList<String>(), null)

        val strategyContents = groupIds.map { groupId ->
            val entries = substrateFactory.get(runId, groupId)
                .knowledgeBaseDao()
                .queryByDomain(runId, groupId, "group_strategy", 10)
            assertEquals(1, entries.size)
            assertTrue(entries.single().content.startsWith("GROUP STRATEGY HYPOTHESIS"))
            entries.single().content
        }

        assertEquals(groupIds.size, strategyContents.distinct().size)
        assertTrue(strategyContents.any { it.contains("Evidence-first compounding") })
        assertTrue(strategyContents.any { it.contains("Counterargument-first critique") })
        assertTrue(strategyContents.any { it.contains("Tooling-first validation") })
    }

    @Test
    fun `compiler-derived node roots are seeded and claimable`() = runTest {
        val root = createTempDirectory("run-service-compiler-root-seeding-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-compiler-root-seeding"
        val groupId = "group-0"
        val rootNodeId = CouncilDeterministicIds.nodeId(runId, LocalNodeKey("root"))
        val childNodeId = CouncilDeterministicIds.nodeId(runId, LocalNodeKey("child"))
        val startingField = startingFieldWithTree(
            runId = runId,
            groupId = groupId,
            rootNodeId = rootNodeId,
            childNodeId = childNodeId
        )

        service::class.declaredFunctions.first { it.name == "seedPrimordialData" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupId), startingField, emptyList<String>(), null)

        val dao = substrateFactory.get(runId, groupId).wipTaskDao()
        val rootTask = assertNotNull(dao.getByNodeId(rootNodeId, runId, groupId))
        assertEquals("unclaimed", rootTask.status)
        assertEquals(rootNodeId, rootTask.linkedNodeId)
        assertEquals(rootNodeId, rootTask.dstNodeRef)
        assertEquals(1, dao.claimTask(rootNodeId, runId, groupId, "agent-compiler", 1))
        assertEquals("claimed", dao.getByNodeId(rootNodeId, runId, groupId)!!.status)
        assertEquals(2, dao.countAll(runId, groupId))
        assertEquals(1, dao.countUnclaimed(runId, groupId))
    }

    @Test
    fun `synthetic dst root remains omitted from primordial seeding`() = runTest {
        val root = createTempDirectory("run-service-synthetic-root-seeding-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-synthetic-root-seeding"
        val groupId = "group-0"
        val startingField = startingFieldWithTree(
            runId = runId,
            groupId = groupId,
            rootNodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
            childNodeId = "dst:section:analysis"
        )

        service::class.declaredFunctions.first { it.name == "seedPrimordialData" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupId), startingField, emptyList<String>(), null)

        val dao = substrateFactory.get(runId, groupId).wipTaskDao()
        assertNull(dao.getByNodeId(DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID, runId, groupId))
        assertEquals(1, dao.countAll(runId, groupId))
        assertEquals("dst:section:analysis", dao.getNextUnclaimed(runId, groupId)?.nodeId)
    }

    @Test
    fun `trust decay writes structured health event and archived count`() = runTest {
        val root = createTempDirectory("run-service-trust-decay-health-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-trust-decay-health"
        val groupId = "group-0"
        val db = substrateFactory.get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "low-trust-kb",
                content = "Low-trust claim to archive.",
                sourceAgentId = "agent-low-trust",
                generation = 1,
                trustScore = 0.2,
                domain = "general",
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        service::class.declaredFunctions.first { it.name == "applyTrustDecayForRound" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupId))

        val health = service::class.declaredFunctions.first { it.name == "collectRunSubstrateHealth" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupId), emptyMap<String, String?>()) as List<*>

        val summary = health.single() as RunSubstrateHealthSummary
        assertEquals(1, summary.trustDecayEventCount)
        assertEquals(1, summary.lowTrustArchivedByDecayCount)
        assertEquals(0, summary.kbCount)
    }

    @Test
    fun `generation boundary archives stale uncited KB entries but preserves reused novel and dead end entries`() = runTest {
        val root = createTempDirectory("run-service-kb-organisation-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-kb-organisation"
        val groupId = "group-0"
        val db = substrateFactory.get(runId, groupId)
        db.knowledgeBaseDao().insertAll(
            listOf(
                KnowledgeBaseEntryEntity(
                    id = "old-uncited",
                    content = "Old uncited lower-priority note.",
                    sourceAgentId = "agent-old",
                    generation = 0,
                    trustScore = 0.7,
                    citationCount = 0,
                    domain = "general",
                    status = "active",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 1_000L
                ),
                KnowledgeBaseEntryEntity(
                    id = "old-reused",
                    content = "Old but reused synthesis.",
                    sourceAgentId = "agent-reused",
                    generation = 0,
                    trustScore = 0.7,
                    citationCount = 2,
                    domain = "general",
                    status = "active",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 2_000L
                ),
                KnowledgeBaseEntryEntity(
                    id = "old-high-trust",
                    content = "Old high-trust canon.",
                    sourceAgentId = "agent-canon",
                    generation = 0,
                    trustScore = 0.95,
                    citationCount = 0,
                    domain = "general",
                    status = "active",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 3_000L
                ),
                KnowledgeBaseEntryEntity(
                    id = "fresh",
                    content = "Fresh current-generation finding.",
                    sourceAgentId = "agent-fresh",
                    generation = 3,
                    trustScore = 0.7,
                    citationCount = 0,
                    domain = "general",
                    status = "active",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 4_000L
                ),
                KnowledgeBaseEntryEntity(
                    id = "dead-end",
                    content = "Known failed approach.",
                    sourceAgentId = "agent-dead",
                    generation = 0,
                    trustScore = 0.1,
                    citationCount = 0,
                    domain = "dead_ends",
                    status = "active",
                    isDeadEnd = true,
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 5_000L
                )
            )
        )

        service::class.declaredFunctions.first { it.name == "organizeKnowledgeBasesForGeneration" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupId), 3)

        assertEquals("archived", db.knowledgeBaseDao().getById("old-uncited")!!.status)
        assertEquals("active", db.knowledgeBaseDao().getById("old-reused")!!.status)
        assertEquals("active", db.knowledgeBaseDao().getById("old-high-trust")!!.status)
        assertEquals("active", db.knowledgeBaseDao().getById("fresh")!!.status)
        assertEquals("active", db.knowledgeBaseDao().getById("dead-end")!!.status)

        val event = db.eventLogDao()
            .getLatestByType(runId, groupId, EventTypes.KB_ORGANIZED, 1)
            .single()
        assertEquals(3, event.generation)
        assertTrue(event.payloadJson.contains("\"archivedStaleKb\":1"))
    }

    @Test
    fun `KB organisation failure is recorded as structured substrate event`() = runTest {
        val root = createTempDirectory("run-service-kb-organisation-failure-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-kb-organisation-failure"
        val groupId = "group-0"
        val db = substrateFactory.get(runId, groupId)

        service::class.declaredFunctions.first { it.name == "recordKbOrganizationFailure" }
            .apply { isAccessible = true }
            .callSuspend(
                service,
                db,
                runId,
                groupId,
                4,
                """broken "archive" query""",
                12_345L
            )

        val event = db.eventLogDao()
            .getLatestByType(runId, groupId, EventTypes.KB_ORGANIZATION_FAILED, 1)
            .single()
        assertEquals(4, event.generation)
        assertEquals(12_345L, event.timestampMs)
        assertTrue(event.payloadJson.contains("broken \\\"archive\\\" query"))
    }

    @Test
    fun `declared KG and tool requirements gate substrate scores when active substrate is missing`() = runTest {
        val root = createTempDirectory("run-service-substrate-requirement-gate-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = service(root, substrateFactory)
        val runId = "run-requirement-gate"
        val groupId = "group-0"
        val db = substrateFactory.get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-1",
                content = "Useful KB content that would normally score on density.",
                sourceAgentId = "agent-kb",
                generation = 1,
                trustScore = 0.8,
                domain = "general",
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val assessment = service::class.declaredFunctions.first { it.name == "assessSubstrateGroup" }
            .apply { isAccessible = true }
            .callSuspend(
                service,
                db,
                runId,
                groupId,
                0,
                listOf(
                    ScoringDimension(
                        id = "kb_density",
                        name = "KB Density",
                        description = "Knowledge-base density.",
                        weight = 1.0
                    )
                ),
                """
                    Mandatory substrate work:
                    - Emit at least one concrete KG fact.
                    - Propose at least one reusable TOOL artifact.
                """.trimIndent()
            )

        val properties = assessment!!::class.memberProperties.associateBy { it.name }
        val scoreProperty = properties.getValue("dimensionScores").apply { isAccessible = true }
        val failureProperty = properties.getValue("failureModesTriggered").apply { isAccessible = true }
        val scores = scoreProperty.getter.call(assessment) as List<DimensionScore>
        val failures = failureProperty.getter.call(assessment) as List<String>

        assertEquals(0.0, scores.single().rawScore)
        assertTrue(failures.contains("missing_required_kg_fact"))
        assertTrue(failures.contains("missing_required_tool"))
    }

    @Test
    fun `required substrate caveat distinguishes all zero tie break winner from task success`() {
        val root = createTempDirectory("run-service-substrate-winner-caveat-test-").toFile()
        val service = service(root)
        val method = service::class.declaredFunctions.first { it.name == "buildRequiredSubstrateWinnerCaveat" }
            .apply { isAccessible = true }
        val objective = """
            Mandatory substrate work:
            - Emit at least one concrete KG fact.
            - Propose at least one reusable TOOL artifact.
        """.trimIndent()
        val missingHealth = listOf(
            substrateHealth("group-0", kgCount = 0, activeToolCount = 0),
            substrateHealth("group-1", kgCount = 0, activeToolCount = 0)
        )
        val satisfiedHealth = listOf(
            substrateHealth("group-0", kgCount = 1, activeToolCount = 1),
            substrateHealth("group-1", kgCount = 0, activeToolCount = 0)
        )

        val caveat = method.call(service, objective, missingHealth) as String?

        assertTrue(caveat!!.contains("Required substrate caveat"))
        assertTrue(caveat.contains("tie-break"))
        assertNull(method.call(service, objective, satisfiedHealth))
    }

    @Test
    fun `final winner tie break prefers substantive deliverable and substrate over empty first group`() {
        val root = createTempDirectory("run-service-final-winner-tiebreak-test-").toFile()
        val service = service(root)
        val method = service::class.declaredFunctions.first { it.name == "selectFinalWinnerId" }
            .apply { isAccessible = true }
        val groupSummaries = listOf(
            groupSummary("group-0", score = 0.0),
            groupSummary("group-1", score = 0.0),
            groupSummary("group-2", score = 0.0)
        )
        val submissionSummaries = mapOf(
            "group-0" to "ROUND SUBMISSION SUMMARY\nEmpty tie-break winner.",
            "group-1" to """
                ROUND SUBMISSION SUMMARY
                FINAL DELIVERABLE HTML
                <!doctype html><html><body>Substantive article.</body></html>
            """.trimIndent(),
            "group-2" to """
                ROUND SUBMISSION SUMMARY
                FINAL DELIVERABLE HTML
                <!doctype html><html><body>Substantive article with KG support.</body></html>
            """.trimIndent()
        )
        val health = listOf(
            substrateHealth("group-0", kgCount = 0, activeToolCount = 0),
            substrateHealth("group-1", kgCount = 0, activeToolCount = 0),
            substrateHealth("group-2", kgCount = 2, activeToolCount = 0)
        )

        val selected = method.call(service, groupSummaries, submissionSummaries, health)

        assertEquals("group-2", selected)
    }

    private fun groupSummary(
        groupId: String,
        score: Double?
    ): GroupSummary = GroupSummary(
        groupId = groupId,
        profileType = "Library",
        activeAgents = 0,
        remainingBudget = 0.0,
        currentRoundScore = score
    )

    private fun substrateHealth(
        groupId: String,
        kgCount: Int,
        activeToolCount: Int
    ): RunSubstrateHealthSummary = RunSubstrateHealthSummary(
        groupId = groupId,
        kbCount = 0,
        kgCount = kgCount,
        activeToolCount = activeToolCount,
        toolExecutionCount = 0,
        stagedSubmissionCount = 0,
        promotedSubmissionCount = 0,
        pendingSubmissionCount = 0,
        rejectedSubmissionCount = 0,
        outlierSubmissionCount = 0,
        roundSubmissionSummaryPresent = false
    )

    private fun service(
        root: java.io.File,
        substrateFactory: SubstrateDatabaseFactory = SubstrateDatabaseFactory(root)
    ): EvoMoliorRunService = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ ->
            throw IllegalStateException("LifecycleManager should not be constructed")
        },
        groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = substrateFactory,
        metaPlannerRunner = MetaPlannerRunner()
    )

    private fun startingFieldWithTree(
        runId: String,
        groupId: String,
        rootNodeId: String,
        childNodeId: String
    ): StartingField {
        val tree = DeliverableSkeletonTree(
            runId = runId,
            groupId = groupId,
            rootNodeId = rootNodeId,
            nodes = listOf(
                DeliverableSkeletonNode(
                    nodeId = rootNodeId,
                    title = "Compiler root",
                    nodeType = DeliverableSkeletonNodeType.ROOT,
                    children = listOf(childNodeId)
                ),
                DeliverableSkeletonNode(
                    nodeId = childNodeId,
                    parentId = rootNodeId,
                    title = "Child deliverable",
                    nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION
                )
            )
        )
        return StartingField.default(
            objective = "Verify WIP seeding for $rootNodeId",
            runId = runId,
            groupCount = 1,
            groupProfilesMap = mapOf(groupId to GroupProfileType.Library)
        ).copy(
            deliverableSkeletonTreeJson = json.encodeToString(DeliverableSkeletonTree.serializer(), tree)
        )
    }
}
