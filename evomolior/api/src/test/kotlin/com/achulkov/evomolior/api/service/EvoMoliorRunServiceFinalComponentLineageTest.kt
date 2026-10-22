package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
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
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceFinalComponentLineageTest {
    @Test
    fun `headed Markdown candidate retains staging provenance without admitted component authority`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("provisional-component-lineage-").toFile()
            )
            val runId = "run-final-component-lineage"
            val groupId = "group-0"
            val database = factory.get(runId, groupId)
            database.stagingDao().insert(
                staging(
                    id = "writer-doc",
                    agentId = "writer",
                    type = "DOC",
                    content = "# Answer\n\nA provisional receiver artifact.",
                    submittedAtMs = 100L,
                )
            )
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ ->
                    error("This focused extraction test must not spawn agents")
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = factory,
            )
            val sources = com.achulkov.evomolior.substrate.worker.ContributionGraphRepository(
                database = database,
                atlasDatabase = factory.getAtlas(runId),
            ).finalDeliverableSources(runId, groupId)
            assertTrue(sources.isEmpty())
            val outputSpec = FinalOutputSpec(
                relativePath = "answer.md",
                mediaType = "text/markdown",
                validation = FinalOutputValidationContract(
                    requiredMarkdownSectionSignals = listOf("Answer"),
                ),
            )
            val read = service::class.declaredFunctions
                .first { it.name == "readFinalDeliverableFile" }
                .apply { isAccessible = true }
            val candidate = assertNotNull(
                read.callSuspend(service, runId, groupId, 50L, outputSpec, false, null, null)
                    as GeneratedOutputFile?
            )
            assertTrue(FinalOutputContractValidator.validate(candidate, outputSpec).isEmpty())
            assertEquals("writer-doc", candidate.sourceSubmissionId)
            assertEquals("writer", candidate.sourceAgentId)
            assertEquals(groupId, candidate.sourceGroupId)
            assertEquals(emptyMap(), candidate.componentNodeIds)
            assertNull(candidate.componentLineageSubmissionId)
            assertNull(
                read.callSuspend(service, runId, groupId, 50L, outputSpec, true, null, null),
                "The same candidate must remain ineligible for accepted-lineage extraction.",
            )
        }

    @Test
    fun `accepted reuse refreshes exact cached DOC after valid same-writer component lineage arrives`() = runTest {
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
            outputSpec,
            true,
            null,
            null,
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
            fileOnlySpec,
            true,
            null,
            null,
        ) as GeneratedOutputFile?
        assertEquals(emptyMap(), fileOnlyOutput?.componentNodeIds)
        assertNull(current())
        val provisional = read.callSuspend(
            service,
            runId,
            groupId,
            50L,
            outputSpec,
            false,
            null,
            null,
        ) as GeneratedOutputFile
        assertEquals("writer-doc", provisional.sourceSubmissionId)
        assertEquals("writer", provisional.sourceAgentId)
        assertEquals(groupId, provisional.sourceGroupId)
        assertEquals(emptyMap(), provisional.componentNodeIds)
        assertNull(provisional.componentLineageSubmissionId)
        database.stagingDao().insert(
            staging(
                id = "writer-doc-note",
                agentId = "writer",
                type = "DOC",
                content = "This ordinary note is not a JSON receiver artifact.",
                submittedAtMs = 101L,
            )
        )
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
        // A newer same-writer DOC and its lineage must not replace or relabel the cached DOC.
        database.stagingDao().insert(
            staging(
                id = "newer-writer-doc",
                agentId = "writer",
                type = "DOC",
                content = """{"answer":"different artifact","evidence":[]}""",
                submittedAtMs = 104L,
            )
        )
        database.stagingDao().insert(
            staging(
                id = "newer-writer-lineage",
                agentId = "writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "result.json"),
                submittedAtMs = 105L,
            )
        )
        val refresh = service::class.declaredFunctions
            .first { it.name == "refreshAcceptedCandidateLineage" }
            .apply { isAccessible = true }
        val refreshed = refresh.callSuspend(
            service,
            RunConfig(
                runId = runId,
                objective = "Produce a grounded result.",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
                scoringDimensions = listOf(
                    ScoringDimension("quality", "Quality", "Grounded result quality", 1.0)
                ),
                openRouterApiKey = "provider-free",
                isTestRun = true,
            ),
            groupId,
            outputSpec,
            provisional,
        ) as GeneratedOutputFile
        assertEquals(provisional.content, refreshed.content)
        assertEquals(provisional.contentHash, refreshed.contentHash)
        assertEquals(provisional.sourceSubmissionId, refreshed.sourceSubmissionId)
        assertEquals("writer-lineage", refreshed.componentLineageSubmissionId)
        assertEquals(103L, refreshed.componentLineageSubmittedAtMs)
        assertEquals(generated?.componentNodeIds, refreshed.componentNodeIds)

        suspend fun readExact(
            candidate: GeneratedOutputFile,
            requestedRunId: String = runId,
            requestedGroupId: String = groupId,
        ): GeneratedOutputFile? = read.callSuspend(
            service,
            requestedRunId,
            requestedGroupId,
            50L,
            outputSpec,
            true,
            candidate,
            null,
        ) as GeneratedOutputFile?
        assertNull(readExact(provisional.copy(sourceSubmissionId = "missing-doc")))
        assertNull(readExact(provisional.copy(sourceSubmissionId = "writer-lineage")))
        assertNull(readExact(provisional.copy(sourceSubmissionId = "newer-writer-doc")))
        assertNull(readExact(provisional.copy(sourceAgentId = "other-writer")))
        assertNull(readExact(provisional.copy(sourceGroupId = "other-group")))
        assertNull(readExact(provisional.copy(sourceRole = Role.Critic)))
        assertNull(readExact(provisional, requestedRunId = "other-run"))
        assertNull(readExact(provisional, requestedGroupId = "other-group"))
        assertNull(readExact(provisional.copy(relativePath = "other.json")))
        assertNull(readExact(provisional.copy(mediaType = "text/plain")))
        assertNull(
            readExact(
                provisional.copy(
                    content = "tampered",
                    contentHash = AtlasHashing.hashText("tampered"),
                )
            )
        )
        assertNull(readExact(provisional.copy(contentHash = AtlasHashing.hashText("tampered"))))
        val unboundDoc = staging(
            id = "unbound-doc",
            agentId = "unbound-writer",
            type = "DOC",
            content = provisional.content,
            submittedAtMs = 200L,
        )
        database.stagingDao().insert(unboundDoc)
        database.stagingDao().insert(
            unboundDoc.copy(
                id = "intervening-doc",
                content = """{"answer":"replacement","evidence":[]}""",
                submittedAtMs = 201L,
            )
        )
        database.stagingDao().insert(
            staging(
                id = "replacement-lineage",
                agentId = "unbound-writer",
                type = "PLAN_UPDATE",
                content = lineage(path = "result.json"),
                submittedAtMs = 202L,
            )
        )
        assertNull(
            readExact(
                provisional.copy(sourceSubmissionId = "unbound-doc", sourceAgentId = "unbound-writer")
            ),
            "Lineage following a replacement DOC cannot establish authority for the earlier DOC.",
        )
        val filteredRead = service::class.declaredFunctions
            .first { it.name == "readFinalDeliverableCandidate" }
            .apply { isAccessible = true }
        val onlyOriginal: (GeneratedOutputFile) -> Boolean = { it.sourceSubmissionId == "unbound-doc" }
        assertNull(
            filteredRead.callSuspend(
                service, runId, groupId, 50L, outputSpec, true, null, "unbound-writer", onlyOriginal,
            ),
            "Contract fallback must not borrow a replacement's accepted component lineage.",
        )
        val record = service::class.declaredFunctions
            .first { it.name == "recordFinalDeliverableReferences" }
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val references = record.callSuspend(
            service,
            runId,
            groupId,
            0,
            refreshed,
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
