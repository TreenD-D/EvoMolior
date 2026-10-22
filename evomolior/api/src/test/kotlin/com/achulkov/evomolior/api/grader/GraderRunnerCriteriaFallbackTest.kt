package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingResolution
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRequirementWireDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraderRunnerCriteriaFallbackTest {
    @Test
    fun `structured repair receives handle diagnostics without exact IDs`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-combined-diagnostic-test").toFile()
        )
        val runId = "run-combined-diagnostic"
        val graderGroupId = "grader-0"
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(NodeId("worker-node"))
        )
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
        )
        val requests =
            mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No interactive methodology publication." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = { request ->
                requests += request
                val draft = if (requests.size == 1) {
                    validWireDraft(request.bindingCatalog).copy(
                        requirements = validWireDraft(request.bindingCatalog).requirements.map {
                            it.copy(mappedWorkerNodeHandles = setOf("wrong-worker-handle"))
                        }
                    )
                } else {
                    validWireDraft(request.bindingCatalog)
                }
                Json.encodeToString(GraderMethodologyWireDraft.serializer(), draft)
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        assertEquals(2, requests.size)
        val feedback = requests[1].validationError.orEmpty()
        assertTrue(feedback.contains("unknown_worker_handle"))
        assertTrue(feedback.contains("wrong-worker-handle"))
        assertTrue(!feedback.contains("actual="))
        assertTrue(!feedback.contains("allowed="))
        assertEquals(
            1,
            requests[1].bindingCatalog.modelView.rows.single().workerNodeHandles.size
        )
    }

    @Test
    fun `structured grader authorship repairs incomplete leased worker-node coverage`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-worker-coverage-test").toFile()
        )
        val runId = "run-worker-coverage"
        val graderGroupId = "grader-0"
        val secondWorkerNode = NodeId("worker-node-2")
        val workerNodeIds = setOf(NodeId("worker-node"), secondWorkerNode)
        val workerAtlas = initializeTestWorkerAtlas(factory, runId, workerNodeIds)
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerNodeIds, workerAtlas.atlasHash)
        )
        val requests =
            mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No interactive methodology publication." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = { request ->
                requests += request
                val catalog = request.bindingCatalog
                val allHandles = catalog.nodeBindings.single().workerBindings
                    .mapTo(linkedSetOf()) { it.workerNodeHandle }
                val draft = if (requests.size == 1) {
                    validWireDraft(catalog, setOf(allHandles.first()))
                } else {
                    validWireDraft(catalog, allHandles)
                }
                Json.encodeToString(GraderMethodologyWireDraft.serializer(), draft)
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        assertEquals(2, requests.size)
        assertTrue(
            requests[1].validationError?.contains(
                "incomplete_worker_coverage"
            ) == true
        )
        val allHandles = requests[1].bindingCatalog.nodeBindings.single().workerBindings
            .mapTo(linkedSetOf()) { it.workerNodeHandle }
        val priorHandles = requests[1].priorDraft
            ?.requirements
            .orEmpty()
            .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeHandles }
        val missingHandle = (allHandles - priorHandles).single()
        assertTrue(requests[1].validationError?.contains(missingHandle) == true)
        assertEquals(allHandles - missingHandle, priorHandles)
        assertTrue(requests[1].validationError?.contains("worker-node-2") == false)
        val typedDrafts = factory.getGrader(runId, graderGroupId).stagingDao()
            .getAll(runId, graderGroupId)
            .filter { it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:") }
        assertEquals(1, typedDrafts.size, typedDrafts.toString())
        assertEquals("pending", typedDrafts.single().status)
    }

    @Test
    fun `structured repair receives latest rejected draft without exact authority`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-critic-feedback-test").toFile()
        )
        val runId = "run-critic-feedback"
        val graderGroupId = "grader-0"
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(NodeId("worker-node"))
        )
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
        )
        val olderDraft = validTypedDraft()
        val latestDraft = olderDraft.copy(
            automatedTests = olderDraft.automatedTests.map {
                it.copy(
                    testId = "grader-node",
                    mappedRequirementIds = setOf("worker-node"),
                    inputSchemaHash = "grader-node",
                    expectedOutcomeHash = "worker-node"
                )
            },
            failureModes = olderDraft.failureModes.map {
                it.copy(modeId = "grader-node")
            },
            requirements = olderDraft.requirements.map {
                it.copy(
                    requirementId = "worker-node",
                    criterion = "The criterion for grader-node must preserve worker-node lineage.",
                    executableTestIds = listOf("grader-node"),
                    noveltyFingerprint = "worker-node"
                )
            }
        )
        val graderDb = factory.getGrader(runId, graderGroupId)
        listOf(
            StagingSubmissionEntity(
                id = "older-rejected-methodology",
                agentId = "grader-builder-old",
                role = Role.GraderBuilder.name,
                type = SubmissionType.PLAN_UPDATE.name,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    olderDraft
                ),
                budgetSignature = "GRADING_SHEET_DRAFT:grader-builder-old:1",
                status = "rejected",
                rejectionReason = "Older Critic verdict.",
                criticAgentId = "critic-grader-0-round-0-old",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 3_000L,
                reviewedAtMs = 1_000L
            ),
            StagingSubmissionEntity(
                id = "latest-rejected-methodology",
                agentId = "grader-builder-latest",
                role = Role.GraderBuilder.name,
                type = SubmissionType.PLAN_UPDATE.name,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    latestDraft
                ),
                budgetSignature = "GRADING_SHEET_DRAFT:grader-builder-latest:1",
                status = "rejected",
                rejectionReason =
                    "Rubric guidance is generic; grader-node and worker-node must remain private.",
                criticAgentId = "critic-grader-0-round-0-latest",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 2_000L
            )
        ).forEach { graderDb.stagingDao().insert(it) }
        val requests =
            mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No replacement methodology was published interactively." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = { request ->
                requests += request
                Json.encodeToString(
                    GraderMethodologyWireDraft.serializer(),
                    validWireDraft(request.bindingCatalog)
                )
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        val request = requests.single()
        assertEquals(
            "Prior Critic rejected the persisted methodology. Repair the supplied redacted prior " +
                "draft against the sealed objective-specific methodology contract.",
            request.validationError
        )
        val projected = assertNotNull(request.priorDraft)
        val providerRepairPayload = buildString {
            appendLine(request.validationError)
            append(Json.encodeToString(GraderMethodologyWireDraft.serializer(), projected))
        }
        assertTrue("grader-node" !in providerRepairPayload, providerRepairPayload)
        assertTrue("worker-node" !in providerRepairPayload, providerRepairPayload)
        val row = request.bindingCatalog.nodeBindings.single()
        assertTrue(row.graderNodeHandle in providerRepairPayload)
        assertTrue(row.workerBindings.single().workerNodeHandle in providerRepairPayload)
    }

    @Test
    fun `latest blank Critic verdict keeps its rejected draft as repair authority`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-blank-critic-feedback-test").toFile()
        )
        val runId = "run-blank-critic-feedback"
        val graderGroupId = "grader-0"
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(NodeId("worker-node"))
        )
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
        )
        val olderDraft = validTypedDraft()
        val latestDraft = olderDraft.copy(
            requirements = olderDraft.requirements.map {
                it.copy(criterion = "The latest blank-verdict criterion.")
            }
        )
        val graderDb = factory.getGrader(runId, graderGroupId)
        listOf(
            StagingSubmissionEntity(
                id = "older-rejected-methodology",
                agentId = "grader-builder-old",
                role = Role.GraderBuilder.name,
                type = SubmissionType.PLAN_UPDATE.name,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    olderDraft
                ),
                budgetSignature = "GRADING_SHEET_DRAFT:grader-builder-old:1",
                status = "rejected",
                rejectionReason = "Older Critic verdict.",
                criticAgentId = "critic-old",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_000L
            ),
            StagingSubmissionEntity(
                id = "latest-rejected-methodology",
                agentId = "grader-builder-latest",
                role = Role.GraderBuilder.name,
                type = SubmissionType.PLAN_UPDATE.name,
                content = Json.encodeToString(
                    GraderMethodologyDraft.serializer(),
                    latestDraft
                ),
                budgetSignature = "GRADING_SHEET_DRAFT:grader-builder-latest:1",
                status = "rejected",
                rejectionReason = "",
                criticAgentId = "critic-latest",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_000L
            )
        ).forEach { graderDb.stagingDao().insert(it) }
        val requests =
            mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No replacement methodology was published interactively." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = { request ->
                requests += request
                Json.encodeToString(
                    GraderMethodologyWireDraft.serializer(),
                    validWireDraft(request.bindingCatalog)
                )
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        val request = requests.single()
        assertEquals(
            "Prior Critic rejected the persisted methodology. Repair the supplied redacted prior " +
                "draft against the sealed objective-specific methodology contract.",
            request.validationError
        )
        val projected = assertNotNull(request.priorDraft)
        val resolved = assertIs<GraderMethodologyBindingResolution.Resolved>(
            request.bindingCatalog.resolve(projected)
        )
        assertEquals(latestDraft, resolved.draft)
    }

    @Test
    fun `structured repair keeps Critic feedback when rejected methodology cannot be projected`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("grader-malformed-critic-feedback-test").toFile()
            )
            val runId = "run-malformed-critic-feedback"
            val graderGroupId = "grader-0"
            val workerAtlas = initializeTestWorkerAtlas(
                factory,
                runId,
                setOf(NodeId("worker-node"))
            )
            GraderAuthorityRepository(factory).appendAtlas(
                typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
            )
            factory.getGrader(runId, graderGroupId).stagingDao().insert(
                StagingSubmissionEntity(
                    id = "malformed-rejected-methodology",
                    agentId = "grader-builder",
                    role = Role.GraderBuilder.name,
                    type = SubmissionType.PLAN_UPDATE.name,
                    content = """{"not":"a methodology"}""",
                    budgetSignature = "GRADING_SHEET_DRAFT:grader-builder:1",
                    status = "rejected",
                    rejectionReason = "The methodology is malformed.",
                    criticAgentId = "critic-grader-0-round-0",
                    generation = 1,
                    groupId = graderGroupId,
                    runId = runId,
                    submittedAtMs = 1_000L,
                    reviewedAtMs = 2_000L
                )
            )
            val requests =
                mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
            val runner = GraderRunner(
                openRouterApiKey = "sk-test",
                substrateFactory = factory,
                localAgentRunner = { "No replacement methodology was published interactively." },
                graderAgentTimeoutMs = 1_000L,
                structuredMethodologyAuthor = { request ->
                    requests += request
                    Json.encodeToString(
                        GraderMethodologyWireDraft.serializer(),
                        validWireDraft(request.bindingCatalog)
                    )
                }
            )

            runner.runCriteriaPhase(
                runId,
                listOf(graderGroupId),
                0,
                "Evaluate shelter cooling evidence.",
                dimensions()
            )

            assertEquals(
                "Prior Critic rejected a methodology that cannot be safely projected into the " +
                    "current sealed handle catalog. Author a fresh methodology against the sealed " +
                    "contracts.",
                requests.single().validationError
            )
            assertNull(requests.single().priorDraft)
        }

    @Test
    fun `structured grader authorship retries validation and publishes model draft in leased scope`() =
        runTest {
            val dataRoot = createTempDirectory("grader-structured-authorship-test").toFile()
            val factory = SubstrateDatabaseFactory(dataRoot)
            val runId = "run-structured"
            val graderGroupId = "grader-0"
            val workerAtlas = initializeTestWorkerAtlas(
                factory,
                runId,
                setOf(NodeId("worker-node"))
            )
            GraderAuthorityRepository(factory).appendAtlas(
                typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
            )
            val requests = mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
            var attempt = 0
            val runner = GraderRunner(
                openRouterApiKey = "sk-test",
                substrateFactory = factory,
                localAgentRunner = { "Criteria prose without tool publication." },
                graderAgentTimeoutMs = 1_000L,
                structuredMethodologyAuthor = { request ->
                    requests += request
                    attempt += 1
                    if (attempt == 1) """{"rubricDimensions":[]}"""
                    else Json.encodeToString(
                        GraderMethodologyWireDraft.serializer(),
                        validWireDraft(request.bindingCatalog)
                    )
                }
            )

            runner.runCriteriaPhase(
                runId = runId,
                graderGroupIds = listOf(graderGroupId),
                roundIndex = 0,
                runObjective = "Evaluate shelter cooling evidence.",
                scoringDimensions = dimensions()
            )

            val drafts = factory.getGrader(runId, graderGroupId).stagingDao()
                .getAll(runId, graderGroupId)
                .filter { it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:") }
            assertEquals(2, requests.size)
            assertTrue(requests[1].validationError?.contains("JSON decode failed") == true)
            assertEquals(1, requests[1].bindingCatalog.modelView.rows.size)
            assertTrue(
                requests[1].bindingCatalog.modelView.rows.single()
                    .workerNodeHandles.isNotEmpty()
            )
            assertEquals(1, drafts.size)
            assertEquals("pending", drafts.single().status)
            assertTrue(drafts.single().content.contains("\"requirementId\":\"requirement-1\""))
            assertTrue(drafts.single().content.contains("\"graderAtlasNodeId\":\"grader-node\""))
            assertTrue(!drafts.single().content.contains("\"graderNodeHandle\""))
        }

    @Test
    fun `structured grader authorship can repair progressive RE2 syntax failures`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-structured-regex-repair-test").toFile()
        )
        val runId = "run-structured-regex-repair"
        val graderGroupId = "grader-0"
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(NodeId("worker-node"))
        )
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
        )
        val requests =
            mutableListOf<com.achulkov.evomolior.agents.factory.StructuredGraderMethodologyRequest>()
        val patterns = listOf(
            "(?s)(?=.*72)(?=.*deterministic).*",
            "72[^\\n]{0,2000}(deterministic|reproducible)",
            "72[\\s\\S]{0,512}(deterministic|reproducible)"
        )
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No interactive methodology publication." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = { request ->
                requests += request
                val draft = validWireDraft(request.bindingCatalog)
                Json.encodeToString(
                    GraderMethodologyWireDraft.serializer(),
                    draft.copy(
                        automatedTests = draft.automatedTests.map {
                            it.copy(expectedOutputPattern = patterns[requests.lastIndex])
                        }
                    )
                )
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        assertEquals(3, requests.size)
        assertTrue(
            requests[1].validationError?.contains("no lookaround or backreferences") == true,
            requests[1].validationError
        )
        assertTrue(
            requests[2].validationError?.contains("upper limit of at most 1000") == true,
            requests[2].validationError
        )
        val drafts = factory.getGrader(runId, graderGroupId).stagingDao()
            .getAll(runId, graderGroupId)
            .filter { it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:") }
        assertEquals(1, drafts.size)
        assertTrue(drafts.single().content.contains("{0,512}"), drafts.single().content)
    }

    @Test
    fun `structured grader authorship fails closed after bounded malformed responses`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-structured-fail-closed-test").toFile()
        )
        val runId = "run-structured-invalid"
        val graderGroupId = "grader-0"
        val workerAtlas = initializeTestWorkerAtlas(
            factory,
            runId,
            setOf(NodeId("worker-node"))
        )
        GraderAuthorityRepository(factory).appendAtlas(
            typedAtlas(runId, workerAtlasHash = workerAtlas.atlasHash)
        )
        var attempts = 0
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { "No typed publication." },
            graderAgentTimeoutMs = 1_000L,
            structuredMethodologyAuthor = {
                attempts += 1
                """{"rubricDimensions":[]}"""
            }
        )

        runner.runCriteriaPhase(
            runId,
            listOf(graderGroupId),
            0,
            "Evaluate shelter cooling evidence.",
            dimensions()
        )

        assertEquals(3, attempts)
        assertTrue(
            factory.getGrader(runId, graderGroupId).stagingDao()
                .getAll(runId, graderGroupId)
                .none { it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:") }
        )
    }

    @Test
    fun `criteria phase keeps deterministic seed instead of synthetic criteria when agent writes none`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-fallback-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-fallback"
        val graderGroupId = "grader-0"

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                "Recovered output for ${context.role.name} without any tool writes."
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "judge worker groups rigorously",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based work.",
                    weight = 1.0
                )
            )
        )

        val graderDb = factory.getGrader(runId, graderGroupId)
        val criteriaEntries = graderDb.stagingDao().getAll(runId, graderGroupId)
            .filter { it.type == "PLAN_UPDATE" }

        assertEquals(1, criteriaEntries.size)
        assertTrue(criteriaEntries.all { it.status == "promoted" })
        assertTrue(criteriaEntries.single().content.contains("DETERMINISTIC GRADER CRITERIA SEED"))
        assertTrue(criteriaEntries.single().content.contains("EXCELLENT / EXCEEDS EXPECTATIONS"))
        assertTrue(criteriaEntries.none { it.role == Role.GraderResearcher.name || it.role == Role.GraderBuilder.name })
        assertTrue(criteriaEntries.none { it.content.contains("SYNTHETIC GRADER CRITERIA FALLBACK") })
    }

    private fun dimensions() = listOf(
        ScoringDimension(
            id = "accuracy",
            name = "Accuracy",
            description = "Evidence-based correctness.",
            weight = 1.0
        )
    )

    private fun typedAtlas(
        runId: String,
        mappedWorkerNodeIds: Set<NodeId> = setOf(NodeId("worker-node")),
        workerAtlasHash: String
    ): GraderAtlas {
        val nodeId = GraderNodeId("grader-node")
        val node = GraderAtlasNode(
            nodeId = nodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is shelter evidence authentic?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = mappedWorkerNodeIds
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas-$runId",
            runId = runId,
            version = 1,
            workerAtlasHash = workerAtlasHash,
            rootNodeIds = setOf(nodeId),
            nodes = mapOf(nodeId to node)
        ).withComputedHash()
    }

    private fun validTypedDraft() = GraderMethodologyDraft(
        rubricDimensions = listOf(
            GraderRubricDraft("accuracy", "Verify calculations and source lineage.")
        ),
        automatedTests = listOf(
            GraderAutomatedTestDraft(
                testId = "evidence-test",
                description = "Requires inspectable shelter evidence.",
                expectedOutputPattern = "(?i)evidence",
                mappedRequirementIds = setOf("requirement-1"),
                inputSchemaHash = "shelter-snapshot-v1",
                expectedOutcomeHash = "evidence-present-v1",
                difficulty = 0.6
            )
        ),
        failureModes = listOf(
            GraderFailureModeDraft("unsupported-claim", "Shelter claim lacks evidence.")
        ),
        requirements = listOf(
            GraderRequirementDraft(
                requirementId = "requirement-1",
                graderAtlasNodeId = GraderNodeId("grader-node"),
                mappedWorkerNodeIds = setOf(NodeId("worker-node")),
                mappedReceiverObligationRefs = setOf("receiver:evidence"),
                criterion = "Every cooling claim has inspectable evidence.",
                observableEvidence = "Promoted claim-to-source lineage.",
                passThreshold = "All material claims are source-bound.",
                failureLevels = listOf(
                    FailureLevel(1, "One material claim is unsupported.", 0.5)
                ),
                adversarialCases = listOf(
                    AdversarialCase(
                        "fabricated-source",
                        "Insert a plausible unsupported cooling claim.",
                        "Flag the unsupported claim.",
                        1.0
                    )
                ),
                executableTestIds = listOf("evidence-test"),
                disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                rationale = "Municipal receivers require auditable evidence.",
                noveltyFingerprint = "shelter-evidence-v1",
                maximumEvaluationCost = 2.0
            )
        )
    )

    private fun validWireDraft(
        catalog: GraderMethodologyBindingCatalog,
        workerHandles: Set<String> = catalog.nodeBindings.single().workerBindings
            .mapTo(linkedSetOf()) { it.workerNodeHandle }
    ): GraderMethodologyWireDraft {
        val exact = validTypedDraft()
        val row = catalog.nodeBindings.single()
        val requirement = exact.requirements.single()
        val requirements = workerHandles.sorted().mapIndexed { index, workerHandle ->
            GraderRequirementWireDraft(
                requirementId = "requirement-${index + 1}",
                graderNodeHandle = row.graderNodeHandle,
                mappedWorkerNodeHandles = setOf(workerHandle),
                mappedReceiverObligationRefs = requirement.mappedReceiverObligationRefs,
                criterion = requirement.criterion,
                observableEvidence = requirement.observableEvidence,
                passThreshold = requirement.passThreshold,
                failureLevels = requirement.failureLevels,
                adversarialCases = requirement.adversarialCases,
                executableTestIds = requirement.executableTestIds,
                disclosureSensitivity = requirement.disclosureSensitivity,
                rationale = requirement.rationale,
                noveltyFingerprint = "${requirement.noveltyFingerprint}-${index + 1}",
                maximumEvaluationCost = requirement.maximumEvaluationCost
            )
        }
        return GraderMethodologyWireDraft(
            rubricDimensions = exact.rubricDimensions,
            automatedTests = exact.automatedTests.map {
                it.copy(
                    mappedRequirementIds = requirements
                        .mapTo(linkedSetOf()) { draft -> draft.requirementId }
                )
            },
            failureModes = exact.failureModes,
            requirements = requirements
        )
    }
}
