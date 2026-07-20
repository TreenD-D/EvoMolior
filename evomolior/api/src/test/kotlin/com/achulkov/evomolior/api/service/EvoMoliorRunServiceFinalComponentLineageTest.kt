package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceFinalComponentLineageTest {
    @Test
    fun `final output waits for valid same-writer component lineage plan update`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("final-component-lineage-service-").toFile()
        )
        val runId = "run-final-component-lineage"
        val groupId = "group-0"
        val nodeId = NodeId("node-a")
        val atlas = initializeTestWorkerAtlas(
            factory = factory,
            runId = runId,
            nodeIds = setOf(nodeId),
            groupId = groupId,
            requiredSections = setOf("answer", "evidence"),
            capturedNodeIds = setOf(nodeId)
        )
        val node = atlas.nodes.getValue(nodeId)
        NodeCaptureRepository(factory).appendCaptureDecision(
            NodeCaptureDecision(
                decisionId = "capture-node-a",
                runId = runId,
                round = 0,
                nodeId = nodeId,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                nodePayloadHash = node.payloadHash,
                capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
                candidateBundleHashes = emptySet(),
                resultingOwnerGroupIds = setOf(AtlasGroupId(groupId)),
                resultingCaptureState = CaptureState.CAPTURED,
                decisionType = NodeCaptureDecisionType.CAPTURED,
                winningScore = 0.9,
                yieldEligible = true,
                reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE
            ).withComputedHash()
        )
        val database = factory.get(runId, groupId)
        database.stagingDao().insert(
            staging(
                id = "writer-doc",
                agentId = "writer",
                type = "DOC",
                content = """{"answer":"grounded","evidence":[]}""",
                submittedAtMs = 100L
            )
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager is not used by this focused read test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val outputSpec = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json",
            validation = FinalOutputValidationContract(
                requiredMarkdownSectionSignals = listOf("answer", "evidence")
            )
        )
        val read = service::class.declaredFunctions
            .first { it.name == "readFinalDeliverableFile" }
            .apply { isAccessible = true }

        suspend fun current(): GeneratedOutputFile? = read.callSuspend(
            service,
            runId,
            groupId,
            50L,
            outputSpec
        ) as GeneratedOutputFile?

        val fileOnlySpec = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json"
        )
        val fileOnlyOutput = read.callSuspend(
            service,
            runId,
            groupId,
            50L,
            fileOnlySpec
        ) as GeneratedOutputFile?
        assertEquals(emptyMap(), fileOnlyOutput?.componentNodeIds)
        assertNull(current())
        database.stagingDao().insert(
            staging(
                id = "equal-time-writer-lineage",
                agentId = "writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "result.json"),
                submittedAtMs = 100L
            )
        )
        assertNull(current())
        database.stagingDao().insert(
            staging(
                id = "other-writer-lineage",
                agentId = "other-writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "result.json"),
                submittedAtMs = 101L
            )
        )
        assertNull(current())
        database.stagingDao().insert(
            staging(
                id = "wrong-path-lineage",
                agentId = "writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "wrong.json"),
                submittedAtMs = 102L
            )
        )
        assertNull(current())
        database.stagingDao().insert(
            staging(
                id = "writer-lineage",
                agentId = "writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "result.json"),
                submittedAtMs = 103L
            )
        )

        val generated = current()
        assertEquals(
            mapOf("answer" to setOf("node-a"), "evidence" to emptySet()),
            generated?.componentNodeIds
        )
        assertEquals("writer-lineage", generated?.componentLineageSubmissionId)
        assertEquals(103L, generated?.componentLineageSubmittedAtMs)
        val record = service::class.declaredFunctions
            .first { it.name == "recordFinalDeliverableReferences" }
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val references = record.callSuspend(
            service,
            runId,
            groupId,
            0,
            requireNotNull(generated),
            outputSpec
        ) as List<FinalDeliverableReferenceRecord>

        assertEquals(
            setOf("answer", "evidence"),
            references.filter { it.nodeId == null }.mapTo(linkedSetOf()) {
                requireNotNull(it.componentId)
            }
        )
        assertEquals(
            listOf("answer" to "node-a"),
            references.filter { it.nodeId != null }.map {
                requireNotNull(it.componentId) to requireNotNull(it.nodeId)
            }
        )
        assertTrue(references.none { it.componentId == "evidence" && it.nodeId != null })

        @Suppress("UNCHECKED_CAST")
        val fileOnlyReferences = record.callSuspend(
            service,
            runId,
            groupId,
            0,
            requireNotNull(fileOnlyOutput),
            fileOnlySpec
        ) as List<FinalDeliverableReferenceRecord>
        assertEquals(
            setOf("result.json"),
            fileOnlyReferences.mapTo(linkedSetOf()) { requireNotNull(it.componentId) }
        )
        assertEquals(1, fileOnlyReferences.count { it.nodeId == null })
        assertEquals(listOf("node-a"), fileOnlyReferences.mapNotNull { it.nodeId })
    }

    private fun staging(
        id: String,
        agentId: String,
        type: String,
        content: String,
        submittedAtMs: Long
    ) = StagingSubmissionEntity(
        id = id,
        agentId = agentId,
        role = "Builder",
        type = type,
        content = content,
        budgetSignature = "signed",
        generation = 1,
        groupId = "group-0",
        runId = "run-final-component-lineage",
        submittedAtMs = submittedAtMs
    )

    private fun lineage(path: String) = """
        $FINAL_COMPONENT_LINEAGE_V1_MARKER
        {
          "schemaVersion": "final-component-lineage-v1",
          "outputRelativePath": "$path",
          "componentNodeIds": {
            "answer": ["node-a"],
            "evidence": []
          }
        }
    """.trimIndent()
}
