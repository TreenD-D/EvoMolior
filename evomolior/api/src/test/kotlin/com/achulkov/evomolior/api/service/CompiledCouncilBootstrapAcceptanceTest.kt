package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.benchmark.ComparativeRuntimeInputs
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.VisibilityClass
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.core.council.CompiledFieldState
import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

class CompiledCouncilBootstrapAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `StartingFieldCompilesWithoutMaterialCanonicalizationTest sealed topology group models stages and nodes reach worker execution unchanged`() =
        runBlocking {
            val root = createTempDirectory("compiled-worker-execution-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(root)
            val runId = "compiled-worker-execution"
            val objective = "Carry sealed Council authority into ordinary worker execution"
            val semanticGroupKeys = listOf("evidence-monastery", "delivery-mercenaries")
            val semanticProfiles = listOf(
                GroupProfileType.Monastery,
                GroupProfileType.Mercenary
            )
            val runConfig = config(runId, objective).copy(
                groupProfiles = semanticProfiles,
                // The researcher needs a slot alongside the Critic and artifact-owner Builder.
                maxAgentsPerGroup = 3,
                roundDurationMinutes = 1
            )
            val compiled = compiledCouncilTestFixture(
                runId = runId,
                objective = objective,
                envelope = CouncilExecutionEnvelopeFactory.create(runConfig),
                workerGroupKeys = semanticGroupKeys,
                workerProfiles = semanticProfiles,
                topologyOrder = semanticGroupKeys.reversed(),
                generalTests = listOf(
                    CompiledCouncilGeneralTestFixture(
                        testId = "receiver-contract",
                        nodeKey = com.achulkov.evomolior.core.council.LocalNodeKey(
                            "receiver-contract"
                        ),
                        nodeKind = NodeKind.KNOWLEDGE,
                        acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
                        expectedText = "receiver contract"
                    ),
                    CompiledCouncilGeneralTestFixture(
                        testId = "evidence-chain",
                        nodeKey = com.achulkov.evomolior.core.council.LocalNodeKey(
                            "evidence-chain"
                        ),
                        nodeKind = NodeKind.EVIDENCE,
                        acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                        expectedText = "evidence chain"
                    ),
                    CompiledCouncilGeneralTestFixture(
                        testId = "implementation",
                        nodeKey = com.achulkov.evomolior.core.council.LocalNodeKey(
                            "implementation"
                        ),
                        nodeKind = NodeKind.TOOL,
                        acceptedArtifactTypes = setOf(ArtifactType.TOOL),
                        expectedText = "implementation"
                    ),
                    CompiledCouncilGeneralTestFixture(
                        testId = "delivery",
                        nodeKey = com.achulkov.evomolior.core.council.LocalNodeKey("delivery"),
                        nodeKind = NodeKind.DELIVERY,
                        acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                        expectedText = "delivery"
                    )
                )
            )
            val capturedContexts = ConcurrentHashMap<String, AgentBootContext>()
            val capturedProfiles = ConcurrentHashMap<String, GroupProfileType>()
            val lifecycleManagers = ConcurrentHashMap<String, LifecycleManager>()
            val providerOrder = mutableListOf<String>()
            val researcherContextsCaptured = CompletableDeferred<Unit>()
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = {
                        callbackRunId,
                        groupId,
                        openRouterApiKey,
                        _,
                        groupProfile ->
                    synchronized(providerOrder) {
                        providerOrder += groupId
                    }
                    capturedProfiles[groupId] = groupProfile.profileType
                    LifecycleManager(
                        runId = callbackRunId,
                        groupId = groupId,
                        shuruVmManager = ShuruVmManager(),
                        substrateDatabaseFactory = substrateFactory,
                        agentJarPath = "/fake/agent.jar",
                        openRouterApiKey = openRouterApiKey,
                        groupProfile = groupProfile,
                        localAgentRunner = { bootContext, _ ->
                            if (bootContext.role == Role.Researcher) {
                                capturedContexts[bootContext.groupId] = bootContext
                                if (
                                    compiled.workerField.ringTopology.all(
                                        capturedContexts::containsKey
                                    )
                                ) {
                                    researcherContextsCaptured.complete(Unit)
                                }
                            }
                            awaitCancellation()
                        }
                    ).also { lifecycleManagers[groupId] = it }
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = substrateFactory,
                archiveService = RunArchiveService(substrateFactory),
                metaPlannerRunner = MetaPlannerRunner(),
                genesisCouncilRunner = {
                        _,
                        _,
                        _,
                        _,
                        _,
                        _,
                        onInvocationStarted ->
                    onInvocationStarted()
                    compiled
                }
            )

            try {
                service.startRun(runConfig)
                withTimeout(10_000) {
                    researcherContextsCaptured.await()
                }

                val expectedField = compiled.workerField
                val expectedAtlas = compiled.atlas
                val expectedStage = expectedAtlas.stageDefinitions.single()
                val configuredStartingField = LifecycleManager::class.java
                    .getDeclaredField("startingField")
                    .apply { isAccessible = true }
                expectedField.ringTopology.forEach { groupId ->
                    assertEquals(
                        expectedField,
                        configuredStartingField.get(assertNotNull(lifecycleManagers[groupId]))
                    )
                }
                assertEquals(
                    expectedAtlas.stageDefinitions.map { it.stageId },
                    expectedField.stageContents.map { content ->
                        json.parseToJsonElement(content)
                            .jsonObject
                            .getValue("stageId")
                            .jsonPrimitive
                            .content
                    }
                )
                assertEquals(semanticGroupKeys.size, expectedField.groupCount)
                assertEquals(expectedField.ringTopology.toSet(), expectedField.groupProfiles.keys)
                assertEquals(semanticProfiles.toSet(), expectedField.groupProfiles.values.toSet())
                assertTrue(expectedField.ringTopology.none { it in setOf("group-0", "group-1") })
                assertEquals(expectedField.ringTopology, synchronized(providerOrder) {
                    providerOrder.toList()
                })
                assertEquals(expectedField.groupProfiles, capturedProfiles)

                val sealedResearcherPool = assertNotNull(
                    expectedField.modelPools[Role.Researcher.name]
                )
                assertTrue(sealedResearcherPool.isNotEmpty())
                assertEquals(expectedField.ringTopology.toSet(), capturedContexts.keys)

                capturedContexts.values.forEach { bootContext ->
                    val workerView = assertNotNull(bootContext.workerView)
                    val assignedNode = assertNotNull(workerView.assignedNode)
                    val atlasNode = assertNotNull(expectedAtlas.nodes[assignedNode.nodeId])

                    assertEquals(runId, bootContext.runId)
                    assertEquals(
                        expectedField.groupProfiles.getValue(bootContext.groupId),
                        capturedProfiles.getValue(bootContext.groupId)
                    )
                    assertTrue(bootContext.modelAssignment in sealedResearcherPool)
                    assertEquals(null, bootContext.startingField)
                    assertEquals(null, bootContext.canonicalWorkingContext)
                    assertEquals(
                        "Use only the hash-bound REDACTED WORKER VIEW supplied in this boot context.",
                        bootContext.stageDisclosure
                    )
                    assertFalse(expectedField.stageContents.contains(bootContext.stageDisclosure))
                    assertTrue(workerView.hasValidHash())
                    assertEquals(bootContext.groupId, workerView.groupId.value)
                    assertEquals(expectedStage.stageId, workerView.stageId)
                    assertEquals(expectedAtlas.atlasId, workerView.visibleAtlasSkeleton.atlasId)
                    assertEquals(expectedAtlas.version, workerView.visibleAtlasSkeleton.atlasVersion)
                    assertEquals(expectedAtlas.atlasHash, workerView.visibleAtlasSkeleton.atlasHash)
                    assertEquals(expectedAtlas.rootNodeIds, workerView.visibleAtlasSkeleton.rootNodeIds)
                    assertEquals(expectedAtlas.nodes.keys, workerView.visibleAtlasSkeleton.nodes.keys)
                    expectedAtlas.nodes.forEach { (nodeId, node) ->
                        val skeletonNode = workerView.visibleAtlasSkeleton.nodes.getValue(nodeId)
                        assertEquals(node.kind, skeletonNode.kind)
                        assertEquals(node.layer, skeletonNode.layer)
                        assertEquals(node.parentIds, skeletonNode.parentIds)
                        assertEquals(node.prerequisiteIds, skeletonNode.prerequisiteIds)
                        assertEquals(node.alternativeToIds, skeletonNode.alternativeToIds)
                        assertEquals(node.revealPolicy.stageId, skeletonNode.stageId)
                        assertEquals(node.payloadHash, skeletonNode.payloadHash)
                    }
                    assertEquals(
                        expectedStage.nodeIds.associateWith {
                            expectedAtlas.nodes.getValue(it).toRevealedNodeCard()
                        },
                        workerView.revealedNodes.associateBy { it.nodeId }
                    )
                    assertEquals(atlasNode.toRevealedNodeCard(), assignedNode)
                    assertEquals(assignedNode, workerView.revealedNodes.single {
                        it.nodeId == assignedNode.nodeId
                    })
                    assertEquals(assignedNode.nodeId, assertNotNull(bootContext.assignedLease).nodeId)
                    assertEquals(
                        assignedNode.nodeId.value,
                        assertNotNull(bootContext.wipTaskClaim).nodeId
                    )
                    assertEquals(
                        setOf(
                            VisibilityClass.RECEIVER_PRIVATE,
                            VisibilityClass.COUNCIL_PRIVATE,
                            VisibilityClass.GRADER_PRIVATE,
                            VisibilityClass.FUTURE_STAGE,
                            VisibilityClass.OTHER_GROUP_PRIVATE
                        ),
                        workerView.forbiddenKnowledgeClasses
                    )
                    assertEquals(null, workerView.groupFeedback)
                }
            } finally {
                if (service.getState().activeRunId == runId) {
                    service.abortRun(runId, "compiled worker boot contexts captured")
                }
            }
        }

    @Test
    fun `short outer Council timeout aborts before runtime activation`() = runTest {
        val root = createTempDirectory("compiled-council-timeout-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val lifecycleCalls = AtomicInteger()
        val coordinatorCalls = AtomicInteger()
        val runId = "compiled-timeout"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                coordinatorCalls.incrementAndGet()
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { _, _, _, _, _, _, onInvocationStarted ->
                onInvocationStarted()
                delay(Long.MAX_VALUE)
                error("unreachable")
            },
            genesisCouncilTimeoutResolver = { 50.milliseconds }
        )
        var stateObservedWithAbort: EngineState? = null
        val aborted = async(start = CoroutineStart.UNDISPATCHED) {
            service.events.first { event ->
                if (event is EvoMoliorEvent.RunAborted && event.runId == runId) {
                    stateObservedWithAbort = service.getState()
                    true
                } else {
                    false
                }
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(
                config(runId, "Abort a timed-out Council").copy(
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    openRouterApiKey = "/tmp/codex"
                )
            )
        }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertIs<EvoMoliorEvent.RunAborted>(aborted.await())
        assertEquals(0, lifecycleCalls.get())
        assertEquals(0, coordinatorCalls.get())
        assertEquals("IDLE", service.getState().status.name)
        assertEquals("IDLE", stateObservedWithAbort?.status?.name)
        assertEquals(null, stateObservedWithAbort?.activeRunId)
        val activeRunId = service.javaClass.getDeclaredField("activeRunId").apply {
            isAccessible = true
        }
        assertEquals(null, activeRunId.get(service))
    }

    @Test
    fun `frozen comparative Atlas is initialized exactly before worker activation`() = runTest {
        val root = createTempDirectory("comparative-frozen-atlas-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "compare-experiment-task"
        val objective = "Use one exact frozen comparative authority"
        val baseConfig = config(runId, objective)
        val envelope = CouncilExecutionEnvelopeFactory.create(baseConfig)
        val compiled = compiledCouncilTestFixture(
            runId = runId,
            objective = objective,
            envelope = envelope,
            workerGroupKeys = listOf("group-0", "group-1"),
            workerProfiles = baseConfig.groupProfiles
        )
        val runtimeInputs = ComparativeRuntimeInputs(
            receiverContract = compiled.receiverContract,
            atlas = compiled.atlas,
            startingFieldTemplate = compiled.workerField,
            taskToolIds = setOf("read_run_artifact", "fetch_source")
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("stop after frozen Atlas initialization")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            comparativeRuntimeInputs = runtimeInputs
        )

        assertFailsWith<IllegalStateException> {
            service.startRun(
                baseConfig.copy(
                    isTestRun = true,
                    graderGroupCountOverride = compiled.graderField.groupCount
                )
            )
        }

        assertEquals(1, lifecycleCalls.get())
        assertEquals(
            compiled.atlas,
            AtlasTransactionRepository(substrateFactory)
                .currentAtlas(runId, compiled.atlas.atlasId)
        )
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `invalid compiled production field aborts before runtime activation`() = runTest {
        val root = createTempDirectory("compiled-council-invalid-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val lifecycleCalls = AtomicInteger()
        val coordinatorCalls = AtomicInteger()
        val runId = "compiled-invalid"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                coordinatorCalls.incrementAndGet()
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { _, _, _, _, _, _, onInvocationStarted ->
                onInvocationStarted()
                StartingFieldCompileResult.Invalid(
                    errors = listOf(
                        FieldCompileError(
                            code = FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            path = ContractPath(listOf("workerField", "groupCount")),
                            message = "worker field violates the execution envelope",
                            responsibleCouncilRoles = setOf(Role.CouncilTaskAnatomist)
                        )
                    ),
                    responsibleCouncilRoles = setOf(Role.CouncilTaskAnatomist)
                )
            }
        )
        val aborted = async(start = CoroutineStart.UNDISPATCHED) {
            service.events.first { it is EvoMoliorEvent.RunAborted && it.runId == runId }
        }

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(config(runId, "Reject invalid compiled field"))
        }

        assertTrue(failure.message.orEmpty().contains("field compilation failed"))
        assertIs<EvoMoliorEvent.RunAborted>(aborted.await())
        assertEquals(0, lifecycleCalls.get())
        assertEquals(0, coordinatorCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `acceptance purpose compiled field persists exact sealed authority before activation`() = runTest {
        val root = createTempDirectory("compiled-council-valid-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "compiled-valid"
        val objective = "Preserve exact compiled authority"
        var compiled: StartingFieldCompileResult.Valid? = null
        var envelope: ExecutionEnvelope? = null
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("stop after compiled authority persistence")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = {
                    callbackRunId,
                    callbackObjective,
                    _,
                    callbackEnvelope,
                    artifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                envelope = callbackEnvelope
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = callbackObjective,
                    envelope = callbackEnvelope,
                    inputArtifacts = artifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                    topologyOrder = listOf("south", "north")
                ).also { compiled = it }
            }
        )

        val runConfig = config(runId, objective).copy(
            openRouterApiKey = "/tmp/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            finalOutputSpec = FinalOutputSpec(
                relativePath = "compiled-authority.md",
                mediaType = "text/markdown"
            )
        )
        val phases = mutableListOf<String>()
        val phaseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                if (event is EvoMoliorEvent.EngineProgress && event.runId == runId) {
                    phases += event.phase
                }
            }
        }
        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(runConfig)
        }
        phaseCollector.cancel()
        assertTrue(failure.message.orEmpty().contains("stop after compiled authority persistence"))
        assertEquals(
            listOf(
                "council_candidate_persisted",
                "council_field_activated",
                "council_canonical_authority_persisted",
                "council_substrates_seeded"
            ),
            phases.filter { it.startsWith("council_") }
        )

        val expected = assertNotNull(compiled)
        val expectedEnvelope = assertNotNull(envelope)
        val entries = substrateFactory.get(runId, "__meta_archive__")
            .metaArchiveDao()
            .getByRun(runId)
            .associateBy { it.entryType }
        val worker = json.decodeFromString(
            StartingField.serializer(),
            assertNotNull(entries[RunArchiveService.ENTRY_STARTING_FIELD]).contentJson
        )
        val candidateEntry = assertNotNull(
            entries[RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST]
        )
        val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
            candidateEntry.contentJson
        )
        val active = ActiveCompiledCouncilManifestV3Codec.decodeVerified(
            assertNotNull(
                entries[RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST]
            ).contentJson
        )
        val atlas = assertNotNull(
            AtlasTransactionRepository(substrateFactory).currentAtlas(runId, expected.atlas.atlasId)
        )
        val graderAtlas = assertNotNull(
            GraderAuthorityRepository(substrateFactory).currentAtlas(runId)
        )
        val persistedState = assertNotNull(PersistedRunStateStore.read(root, runId))

        assertEquals(expected.workerField, worker)
        assertEquals(expected.workerField, candidate.workerStartingField)
        assertEquals(expected.graderField, candidate.graderStartingField)
        assertEquals(expectedEnvelope, candidate.executionEnvelope)
        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            candidate.executionEnvelope.councilExecutionPurpose
        )
        assertEquals(expected.report, candidate.consistencyReport)
        assertEquals(expected.receiverContract, candidate.receiverContract)
        assertEquals(expected.atlas, candidate.workerAtlas)
        assertEquals(expected.graderAtlas, candidate.graderAtlas)
        assertEquals(expected.sealedTests.privateSuite, candidate.privateSuite)
        assertEquals(
            expected.sealedTests.workerCommitmentView,
            candidate.workerCommitmentView
        )
        assertEquals(
            expected.sealedTests.validationContext,
            candidate.validationContext
        )
        assertEquals(CompiledFieldState.EXECUTABILITY_VERIFIED, candidate.state)
        assertEquals(CompiledFieldState.ACTIVE, active.state)
        assertEquals(candidateEntry.id, active.candidateArchiveEntryId)
        assertEquals(candidate.manifestHash, active.candidateManifestHash)
        assertEquals(
            ActiveCompiledCouncilManifestV3.SCHEMA_VERSION,
            persistedState.activeCouncilManifestSchemaVersion
        )
        assertEquals(active.activationHash, persistedState.activeCouncilManifestHash)
        assertEquals(expected.atlas, atlas)
        assertEquals(expected.graderAtlas, graderAtlas)
        assertTrue(candidateEntry.entryType != RunArchiveService.ENTRY_STARTING_FIELD)
        assertEquals(objective, worker.objective)
        assertEquals(expected.workerField.ringTopology, worker.ringTopology)
        assertEquals(expected.workerField.modelAssignment, worker.modelAssignment)
        assertTrue(candidate.hasValidHash())
        assertTrue(active.hasValidHash())
    }

    @Test
    fun `compiled resume restores grader field and verifies the shared seal before activation`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-valid")
        val lifecycleCalls = AtomicInteger()
        var observedGraderField: StartingField? = null
        var observedGraderModelAssignments: Map<String, String>? = null
        var observedValidationContext: Any? = null
        lateinit var service: EvoMoliorRunService
        service = EvoMoliorRunService(
            lifecycleManagerProvider = { runId, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                observedGraderField = service.javaClass
                    .getDeclaredField("activeGraderStartingField")
                    .apply { isAccessible = true }
                    .get(service) as StartingField?
                observedGraderModelAssignments =
                    service.activeSealedGraderModelAssignments(runId)
                observedValidationContext = service.javaClass
                    .getDeclaredField("activeSealedTestSuiteValidationContext")
                    .apply { isAccessible = true }
                    .get(service)
                error("stop at resumed runtime activation")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("resumed runtime activation"))
        assertEquals(1, lifecycleCalls.get())
        assertEquals(seeded.compiled.graderField, observedGraderField)
        assertEquals(
            seeded.compiled.graderField.modelAssignment,
            observedGraderModelAssignments
        )
        assertEquals(
            seeded.compiled.sealedTests.validationContext,
            observedValidationContext
        )
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled resume coordinator failure remains a preactivation startup rejection`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-coordinator-rejection")
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("lifecycle activation must not begin")
            },
            groupCoordinatorProvider = { _, _ ->
                error("resume coordinator construction failed")
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("resume coordinator construction failed"))
        val rejection = assertNotNull(
            StartupRejectionFileStore(seeded.substrateFactory::runControlDir)
                .read(seeded.config.runId)
        )
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled candidate persistence collision leaves no active or canonical projections`() = runTest {
        val root = createTempDirectory("compiled-candidate-collision-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "compiled-candidate-collision"
        val config = config(runId, "Reject candidate collision")
        substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "existing-candidate",
                runId = runId,
                entryType = RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST,
                groupId = null,
                label = "existing candidate",
                contentJson = "{}",
                runObjective = config.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        var compiled: StartingFieldCompileResult.Valid? = null
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { ids, dimensions -> GroupCoordinator(ids, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            genesisCouncilRunner = { callbackRunId, objective, _, envelope, artifacts, _, started ->
                started()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = artifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory)
                ).also { compiled = it }
            }
        )

        val failure = assertFailsWith<IllegalStateException> { service.startRun(config) }
        assertTrue(failure.message.orEmpty().contains("candidate already exists"))
        val entries = substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().getByRun(runId)
        assertFalse(entries.any {
            it.entryType == RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST ||
                it.entryType == RunArchiveService.ENTRY_STARTING_FIELD
        })
        val workerGroupId = assertNotNull(compiled).workerField.ringTopology.first()
        assertEquals(
            null,
            substrateFactory.get(runId, workerGroupId).sealedTestSuiteDao().get(runId, workerGroupId)
        )
        assertEquals(
            null,
            AtlasTransactionRepository(substrateFactory).currentAtlas(
                runId,
                assertNotNull(compiled).atlas.atlasId
            )
        )
        assertEquals(
            emptyList(),
            substrateFactory.get(runId, workerGroupId).wipTaskDao().getAll(runId, workerGroupId)
        )
    }

    @Test
    fun `compiled resume rejects a tampered candidate manifest before activation`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-tampered")
        val archiveDao = seeded.substrateFactory.get(seeded.config.runId, "__meta_archive__").metaArchiveDao()
        val candidateEntry = archiveDao.getByRun(seeded.config.runId)
            .single {
                it.entryType ==
                    RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
            }
        val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
            candidateEntry.contentJson
        )
        archiveDao.insert(
            candidateEntry.copy(
                contentJson = json.encodeToString(
                    CompiledCouncilCandidateManifestV3.serializer(),
                    candidate.copy(
                        graderStartingField = candidate.graderStartingField.copy(
                            norms = candidate.graderStartingField.norms + " tampered"
                        )
                    )
                )
            )
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("candidate manifest is invalid"))
        assertEquals(0, lifecycleCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled resume rejects recovery purpose that differs from active manifest`() = runTest {
        val seeded = seedCompiledResumeState(
            runId = "compiled-resume-missing-purpose",
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
        )
        val stateFile = seeded.substrateFactory.runControlDir(seeded.config.runId)
            .resolve(PersistedRunStateStore.STATE_FILE_NAME)
        val persisted = assertNotNull(
            PersistedRunStateStore.read(stateFile)
        )
        PersistedRunStateStore.write(
            stateFile,
            persisted.copy(councilExecutionPurpose = CouncilExecutionPurpose.STANDARD)
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(
                seeded.config.copy(
                    councilExecutionPurpose = CouncilExecutionPurpose.STANDARD
                )
            )
        }

        assertTrue(failure.message.orEmpty().contains("does not match recovery purpose"))
        assertEquals(0, lifecycleCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled candidate without ACTIVE manifest is not resumable`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-candidate-only")
        seeded.substrateFactory.get(seeded.config.runId, "__meta_archive__")
            .metaArchiveDao()
            .deleteByRunAndType(
                seeded.config.runId,
                RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST
            )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("not resumable"))
        assertEquals(0, lifecycleCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled resume rejects v2 candidate bytes before activation`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-v2-candidate")
        val archiveDao = seeded.substrateFactory
            .get(seeded.config.runId, "__meta_archive__")
            .metaArchiveDao()
        val candidateEntry = archiveDao.getByRun(seeded.config.runId).single {
            it.entryType ==
                RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
        }
        archiveDao.insert(
            candidateEntry.copy(
                contentJson = candidateEntry.contentJson.replace(
                    CompiledCouncilCandidateManifestV3.SCHEMA_VERSION,
                    "compiled-council-manifest-v2"
                )
            )
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("candidate manifest is invalid"))
        assertEquals(0, lifecycleCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `compiled resume rejects persisted ACTIVE hash mismatch before activation`() = runTest {
        val seeded = seedCompiledResumeState("compiled-resume-active-state-mismatch")
        val stateFile = seeded.substrateFactory.runControlDir(seeded.config.runId)
            .resolve(PersistedRunStateStore.STATE_FILE_NAME)
        val state = assertNotNull(PersistedRunStateStore.read(stateFile))
        PersistedRunStateStore.write(
            stateFile,
            state.copy(activeCouncilManifestHash = "0".repeat(64))
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("runtime activation must not begin")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = seeded.substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(seeded.config)
        }

        assertTrue(failure.message.orEmpty().contains("exact ACTIVE Council manifest"))
        assertEquals(0, lifecycleCalls.get())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `archive recovery re-evaluates exact output through active v3 authority`() = runTest {
        val seeded = seedCompiledResumeState("compiled-archive-v3-recheck")
        val entries = seeded.substrateFactory
            .get(seeded.config.runId, "__meta_archive__")
            .metaArchiveDao()
            .getByRun(seeded.config.runId)
        val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
            entries.single {
                it.entryType ==
                    RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
            }.contentJson
        )
        val groupId = candidate.workerStartingField.ringTopology.first()
        val outputSpec = FinalOutputSpec(
            relativePath = candidate.validationContext.finalOutputSpec.relativePath,
            mediaType = candidate.validationContext.finalOutputSpec.mediaType
        )
        val archiveService = RunArchiveService(seeded.substrateFactory)
        val resolver = archiveService::class.declaredFunctions
            .first { it.name == "resolveGeneratedOutputFiles" }
            .apply { isAccessible = true }
        val accepted = RunCompletionRecord(
            runId = seeded.config.runId,
            winnerId = groupId,
            completedRoundCount = 1,
            generatedOutputFiles = listOf(
                GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = "receiver-delivery-accepted",
                    sourceGroupId = groupId
                )
            ),
            requestedOutputSpec = outputSpec
        )

        @Suppress("UNCHECKED_CAST")
        val resolved = resolver.callSuspend(
            archiveService,
            seeded.config.runId,
            entries,
            accepted,
            outputSpec
        ) as List<GeneratedOutputFile>
        assertEquals(accepted.generatedOutputFiles.single().content, resolved.single().content)

        val failure = assertFailsWith<IllegalStateException> {
            resolver.callSuspend(
                archiveService,
                seeded.config.runId,
                entries,
                accepted.copy(
                    generatedOutputFiles = listOf(
                        accepted.generatedOutputFiles.single().copy(
                            content = "not accepted",
                            contentHash = com.achulkov.evomolior.core.atlas.AtlasHashing
                                .hashText("not accepted")
                        )
                    )
                ),
                outputSpec
            )
        }
        assertTrue(failure.message.orEmpty().contains("sealed-tests-v3"))
    }

    @Test
    fun `active v3 bundle preserves approved html bytes without slide post-processing`() =
        runTest {
            val outputSpec = FinalOutputSpec(
                relativePath = "final-article.html",
                mediaType = "text/html"
            )
            val seeded = seedCompiledResumeState(
                runId = "compiled-archive-v3-html",
                requestedFinalOutputSpec = outputSpec
            )
            val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                seeded.substrateFactory
                    .get(seeded.config.runId, "__meta_archive__")
                    .metaArchiveDao()
                    .getByRun(seeded.config.runId)
                    .single {
                        it.entryType ==
                            RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
                    }
                    .contentJson
            )
            val groupId = candidate.workerStartingField.ringTopology.first()
            val exactHtml = """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Approved output</title></head>
                <body>
                  <article>
                    <h1>receiver-delivery-accepted</h1>
                    <div class="slide-card">
                      <span class="slide-number">1</span>
                      <h5>Exact copy</h5>
                      <p>These bytes are already approved by sealed-tests-v3.</p>
                    </div>
                  </article>
                  <footer role="contentinfo">Approved footer.</footer>
                </body>
                </html>
            """.trimIndent()
            val completion = RunCompletionRecord(
                runId = seeded.config.runId,
                winnerId = groupId,
                completedRoundCount = 1,
                generatedOutputFiles = listOf(
                    GeneratedOutputFile(
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        content = exactHtml,
                        sourceGroupId = groupId
                    )
                ),
                requestedOutputSpec = outputSpec
            )

            val bundle = assertNotNull(
                RunArchiveService(seeded.substrateFactory).buildResultsBundle(
                    runId = seeded.config.runId,
                    completionRecord = completion,
                    requestedOutputSpec = outputSpec
                )
            )
            val zipEntries = unzipTextEntries(bundle)
            assertEquals(exactHtml, zipEntries["outputs/${outputSpec.relativePath}"])
            assertFalse(zipEntries.keys.any { it.startsWith("outputs/assets/slides/") })

            val noFooterHtml = exactHtml.replace(
                "  <footer role=\"contentinfo\">Approved footer.</footer>\n",
                ""
            )
            val failure = assertFailsWith<IllegalStateException> {
                RunArchiveService(seeded.substrateFactory).buildResultsBundle(
                    runId = seeded.config.runId,
                    completionRecord = completion.copy(
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = outputSpec.relativePath,
                                mediaType = outputSpec.mediaType,
                                content = noFooterHtml,
                                sourceGroupId = groupId
                            )
                        )
                    ),
                    requestedOutputSpec = outputSpec
                )
            }
            assertTrue(failure.message.orEmpty().contains("authored footer"))
        }

    private suspend fun seedCompiledResumeState(
        runId: String,
        councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
        requestedFinalOutputSpec: FinalOutputSpec? = null
    ): SeededCompiledResume {
        val root = createTempDirectory("compiled-resume-seed-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val config = config(runId, "Resume exact compiled authority").let { base ->
            when (councilExecutionPurpose) {
                CouncilExecutionPurpose.STANDARD -> base
                CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE -> base.copy(
                    openRouterApiKey = "/tmp/codex",
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    councilExecutionPurpose = councilExecutionPurpose,
                    finalOutputSpec = FinalOutputSpec(
                        relativePath = "compiled-resume.md",
                        mediaType = "text/markdown"
                    )
                )
            }
        }.let { resolved ->
            requestedFinalOutputSpec?.let { resolved.copy(finalOutputSpec = it) } ?: resolved
        }
        var compiled: StartingFieldCompileResult.Valid? = null
        val seedingService = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("stop after compiled resume state is sealed")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = {
                    callbackRunId,
                    objective,
                    _,
                    envelope,
                    artifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = artifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory)
                ).also { compiled = it }
            }
        )
        assertFailsWith<IllegalStateException> {
            seedingService.startRun(config)
        }
        val expected = assertNotNull(compiled)
        val state = assertNotNull(PersistedRunStateStore.read(root, runId))
        assertEquals(
            ActiveCompiledCouncilManifestV3.SCHEMA_VERSION,
            state.activeCouncilManifestSchemaVersion
        )
        assertNotNull(state.activeCouncilManifestHash)
        return SeededCompiledResume(
            substrateFactory = substrateFactory,
            config = config,
            compiled = expected
        )
    }

    private data class SeededCompiledResume(
        val substrateFactory: SubstrateDatabaseFactory,
        val config: RunConfig,
        val compiled: StartingFieldCompileResult.Valid
    )

    private fun config(runId: String, objective: String): RunConfig = RunConfig(
        runId = runId,
        objective = objective,
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 1,
        roundDurationMinutes = 0,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Evaluate exact compiled authority.",
                weight = 1.0
            )
        ),
        openRouterApiKey = "sk-test"
    )

    private fun unzipTextEntries(bundle: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return entries
    }
}
