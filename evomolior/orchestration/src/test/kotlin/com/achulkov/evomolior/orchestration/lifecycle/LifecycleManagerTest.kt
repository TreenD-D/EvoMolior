package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.config.CodexRuntimePreset

import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.contract.AtlasSkeletonNodeView
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.PersistExitResult
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeStatus
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.AgentStatus
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.orchestration.nested.NestedGroupConfig
import com.achulkov.evomolior.orchestration.nested.NestedGroupResult
import com.achulkov.evomolior.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionRecord
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import com.achulkov.evomolior.sandbox.shuru.NetworkAllowlistConfig
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasLeaseAuthority
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evomolior.substrate.db.entity.LegacyProtocolEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Integration-style tests for [LifecycleManager] using the in-process agent runner.
 *
 * No Shuru CLI, Room DB, or LLM is required.
 * Tests verify the spawn/kill/budget-poll lifecycle using a fake runner.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class LifecycleManagerTest {

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `compiled ordinary spawn receives only redacted worker authority`() = runTest {
        val observed = CompletableDeferred<AgentBootContext>()
        val harness = makeManagerHarness { bootContext, _ ->
            observed.complete(bootContext)
        }
        seedWorkerWip(harness.substrateFactory)
        harness.manager.configureStartingField(
            StartingField.default(
                objective = "HIDDEN_RECEIVER_OBJECTIVE",
                runId = "run-test",
                groupCount = 2
            )
        )
        harness.manager.configureRunArtifacts(
            inputArtifacts = listOf(
                RunInputArtifact(
                    relativePath = "input.txt",
                    sourceName = "input.txt",
                    mimeType = "text/plain",
                    sizeBytes = 20,
                    sha256 = "artifact-hash",
                    kind = RunInputArtifactKind.TEXT,
                    textPreview = "HIDDEN_ARTIFACT_PREVIEW"
                )
            ),
            artifactsMountPath = "/tmp/artifacts"
        )
        harness.manager.configureWorkerAuthority { request ->
            assignedWorkerAuthority(request)
        }

        harness.manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "HIDDEN_STAGE_DISCLOSURE",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )

        val context = withTimeout(5_000) { observed.await() }
        assertNull(context.startingField)
        val workerView = assertNotNull(context.workerView)
        assertTrue(workerView.hasValidHash())
        assertEquals(
            "Use only the hash-bound REDACTED WORKER VIEW supplied in this boot context.",
            context.stageDisclosure
        )
        assertEquals(null, context.inputArtifacts.single().textPreview)
        assertNull(context.canonicalWorkingContext)
        context.requireValidWorkerAuthority()
        harness.manager.terminateAll()
    }

    @Test
    fun `surplus compiled workers do not spawn without portfolio authority`() = runTest {
        val harness = makeManagerHarness { _, _ -> }
        seedWorkerWip(harness.substrateFactory)
        val spawned = java.util.concurrent.atomic.AtomicInteger()
        val authorityAllocations = java.util.concurrent.atomic.AtomicInteger()
        harness.manager.onAgentSpawned = { _, _, _ -> spawned.incrementAndGet() }
        harness.manager.configureWorkerAuthority { request ->
            authorityAllocations.incrementAndGet()
            assignedWorkerAuthority(request)
        }

        harness.manager.spawnGeneration(
            allocation = RoleAllocation(
                runId = "run-test",
                groupId = "group-0",
                generation = 1,
                entries = listOf(RoleAllocationEntry(Role.Researcher, 2, 50.0)),
                totalBudgetCeiling = 100.0,
                rationale = "one portfolio node for two requested workers"
            ),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )

        assertEquals(1, authorityAllocations.get())
        assertEquals(1, spawned.get())
        harness.manager.terminateAll()
    }

    @Test
    fun `AgentUsefulDeathTest structured dead end survives useful death while node remains open`() =
        kotlinx.coroutines.runBlocking {
            val nodeId = NodeId("node-structured-dead-end")
            val groupId = AtlasGroupId("group-0")
            val atlasId = "atlas-structured-dead-end"
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            lateinit var harness: ManagerHarness
            val exited = CompletableDeferred<String>()
            harness = makeManagerHarness { bootContext, _ ->
                val draft = ContributionCommit(
                    commitId = "commit:${bootContext.agentId}",
                    runId = bootContext.runId,
                    groupId = AtlasGroupId(bootContext.groupId),
                    round = bootContext.generation,
                    agentId = AtlasAgentId(bootContext.agentId),
                    nodeId = nodeId,
                    commitType = CommitType.STRUCTURED_DEAD_END,
                    artifactIds = emptyList(),
                    claimIds = emptyList(),
                    toolRunIds = emptyList(),
                    testsRun = emptyList(),
                    changedStateSummary = "Ruled out the attempted approach without closing the node.",
                    evidenceSummary = "The falsifying observation remains durable for successors.",
                    confidence = 0.9,
                    unresolvedRisks = listOf("A different approach may still resolve the node."),
                    successorRecommendations = listOf("Keep the node open and try an independent method."),
                    dedupeHash = "",
                    committedAt = Instant.parse("2026-07-20T08:00:00Z")
                )
                WorkerExecutionRepository(
                    harness.substrateFactory.get(bootContext.runId, bootContext.groupId)
                ).commit(
                    draft.copy(dedupeHash = AtlasHashing.hashContributionDedupe(draft))
                )
            }
            seedWorkerWip(harness.substrateFactory, nodeId.value)
            val atlasRepository = AtlasTransactionRepository(harness.substrateFactory)
            val atlas = initializeOpenAtlas(
                repository = atlasRepository,
                atlasId = atlasId,
                nodeId = nodeId,
                groupId = groupId
            )
            val leaseAuthority = AtlasLeaseAuthority(
                repository = atlasRepository,
                now = { Instant.parse("2026-07-20T08:00:00Z") }
            )
            harness.manager.configureWorkerAuthority { request ->
                val lease = leaseAuthority.acquire(
                    runId = "run-test",
                    atlasId = atlasId,
                    groupId = groupId,
                    agentId = AtlasAgentId(request.agentId),
                    nodeId = nodeId,
                    round = request.generation,
                    expiresAt = Instant.parse("2026-07-20T08:10:00Z")
                )
                assignedWorkerAuthority(
                    request = request,
                    finalizeLease = { committed ->
                        leaseAuthority.finalize(
                            runId = "run-test",
                            atlasId = atlasId,
                            leaseId = lease.leaseId,
                            committed = committed
                        )
                    }
                ).copy(
                    assignedLease = WorkerLeaseAssignment(
                        leaseId = lease.leaseId,
                        nodeId = lease.nodeId,
                        expiresAt = lease.expiresAt,
                        replicationSlot = lease.replicationSlot
                    )
                )
            }
            harness.manager.onAgentDied = { agentId, _ -> exited.complete(agentId) }

            try {
                harness.manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )

                val agentId = withTimeout(10_000) { exited.await() }
                val workerRepository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", groupId.value)
                )
                val contribution = workerRepository
                    .contributionsForAgent("run-test", groupId.value, agentId)
                    .single()
                val exit = workerRepository.exits("run-test", groupId.value).single()
                val summary = workerRepository.usefulExitSummary("run-test", groupId.value)
                val state = atlasRepository.currentStates("run-test", atlasId).getValue(nodeId)
                val task = assertNotNull(
                    harness.substrateFactory.get("run-test", groupId.value)
                        .wipTaskDao()
                        .getByNodeId(nodeId.value, "run-test", groupId.value)
                )

                assertEquals(CommitType.STRUCTURED_DEAD_END, contribution.commitType)
                assertEquals(contribution.commitId, exit.commitId)
                assertEquals(AgentExecutionPhase.NEGATIVE_RESULT_STAGED, exit.finalPhase)
                assertTrue(exit.acceptedStructuredDeadEnd)
                assertTrue(exit.useful)
                assertEquals(1, summary.usefulExitCount)
                assertEquals(1, summary.ordinaryExitCount)
                assertEquals(LeaseState.ABANDONED, state.leases.single().state)
                assertEquals(emptyList(), state.grades)
                assertEquals(null, state.ownerGroupId)
                assertEquals(emptyList(), state.yieldHistory)
                assertEquals("unclaimed", task.status)
                assertEquals(atlas, atlasRepository.currentAtlas("run-test", atlasId))
            } finally {
                harness.manager.terminateAll()
                agentScope.cancel()
            }
        }

    private data class ManagerHarness(
        val manager: LifecycleManager,
        val substrateFactory: SubstrateDatabaseFactory
    )

    // ── helpers ────────────────────────────────────────────────────────────────

    private val profile = GroupProfile.default(GroupProfileType.Library)
    private val budgetConfig = BudgetConfig(groupId = "group-0", profile = profile)

    /**
     * Creates a [LifecycleManager] in in-process mode with the given [runner].
     *
     * A fake [com.achulkov.evomolior.substrate.SubstrateDatabaseFactory] and
     * [com.achulkov.evomolior.sandbox.shuru.ShuruVmManager] are provided; they
     * will not be invoked in in-process mode.
     */
    private fun makeManager(
        modelSelectionRandom: Random = Random(0),
        nestedRunner: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
        codexRuntimePreset: CodexRuntimePreset? = null,
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): LifecycleManager = makeManagerHarness(
        modelSelectionRandom = modelSelectionRandom,
        nestedRunner = nestedRunner,
        codexRuntimePreset = codexRuntimePreset,
        runner = runner
    ).manager

    private fun makeManagerHarness(
        modelSelectionRandom: Random = Random(0),
        nestedRunner: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
        budgetPollingIntervalMs: Long = 100L,
        existingSubstrateFactory: SubstrateDatabaseFactory? = null,
        codexRuntimePreset: CodexRuntimePreset? = null,
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): ManagerHarness {
        val substrateFactory = existingSubstrateFactory ?: SubstrateDatabaseFactory(
            createTempDirectory("lm-test-").toFile()
        )
        val shuruVmManager = ShuruVmManager()

        return ManagerHarness(
            manager = LifecycleManager(
                runId = "run-test",
                groupId = "group-0",
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = profile,
                modelSelectionRandom = modelSelectionRandom,
                codexRuntimePreset = codexRuntimePreset,
                localAgentRunner = runner,
                nestedGroupRunBlock = nestedRunner,
                gracePeriodSeconds = 1,
                budgetPollingIntervalMs = budgetPollingIntervalMs
            ),
            substrateFactory = substrateFactory
        )
    }

    private fun nestedConfig(
        nestedGroupId: String = "inner-1",
        outerGroupId: String = "group-0",
        outerRunId: String = "run-test",
        maxGenerations: Int = 5
    ): NestedGroupConfig = NestedGroupConfig(
        nestedGroupId = nestedGroupId,
        outerGroupId = outerGroupId,
        outerRunId = outerRunId,
        objective = "nested objective",
        budgetAllocation = 300.0,
        maxGenerations = maxGenerations,
        openRouterApiKey = "sk-test",
        agentJarPath = "/fake/agent.jar"
    )


    @Test
    fun `nested lifecycle manager inherits starting field into child boot contexts`() = runTest {
        val observedStartingField = CompletableDeferred<StartingField?>()
        val startingField = StartingField.default(
            objective = "propagate the original run seed",
            runId = "run-test",
            groupCount = 2
        )

        val harness = makeManagerHarness(
            nestedRunner = null,
            runner = { bootContext, _ ->
                observedStartingField.complete(bootContext.startingField)
            }
        )
        harness.manager.configureStartingField(startingField)

        val childManager = harness.manager.buildNestedLifecycleManager(
            config = nestedConfig(maxGenerations = 1)
        )
        childManager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "nested objective",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )

        val childStartingField = withTimeout(5_000) { observedStartingField.await() }
        childManager.terminateAll()

        assertNotNull(childStartingField)
        assertEquals(startingField.runId, childStartingField.runId)
        assertEquals(startingField.objective, childStartingField.objective)
    }

    private fun singleResearcherAllocation(budgetPerAgent: Double = 50.0) = RoleAllocation(
        runId = "run-test",
        groupId = "group-0",
        generation = 1,
        entries = listOf(RoleAllocationEntry(Role.Researcher, 1, budgetPerAgent)),
        totalBudgetCeiling = budgetPerAgent,
        rationale = "test"
    )

    private suspend fun seedWorkerWip(
        substrateFactory: SubstrateDatabaseFactory,
        nodeId: String = "node-worker"
    ) {
        substrateFactory.get("run-test", "group-0").wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = nodeId,
                    runId = "run-test",
                    groupId = "group-0",
                    description = "Resolve the assigned worker node.",
                    lastChangedGeneration = 0
                )
            )
        )
    }

    private suspend fun initializeOpenAtlas(
        repository: AtlasTransactionRepository,
        atlasId: String,
        nodeId: NodeId,
        groupId: AtlasGroupId
    ): NodeAtlas {
        val receiver = ReceiverContract(
            rawObjective = "Resolve one bounded limitation.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Evidence review",
            decisionOrUse = "Choose the next method",
            whyNeeded = "Avoid repeating a disproven approach",
            successOutcomes = listOf("A supported result or useful negative result"),
            unacceptableOutcomes = listOf("Closing an unresolved node"),
            constraints = listOf("Preserve negative evidence"),
            finalDeliverableContract = FinalOutputSpec("result.md", "text/markdown"),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val node = com.achulkov.evomolior.core.atlas.LimitationNode(
            id = nodeId,
            kind = NodeKind.HYPOTHESIS,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver",
            limitationStatement = "The current approach may not work.",
            capabilityTarget = "A falsifiable resolution path.",
            atomicQuestion = "Does the attempted approach survive the stated falsification?",
            proofContract = ProofContract(
                method = ProofMethod.EXPERIMENT,
                acceptanceCriteria = setOf("The negative result is reproducible"),
                failureIsUseful = true
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EXPERIMENT_REPORT)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(nodeId to node)
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Initial",
            nodeIds = setOf(nodeId),
            mandatoryNodeIds = setOf(nodeId),
            evidenceCoverageThreshold = 0.8,
            integrationReadinessThreshold = 0.7,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                "stage-0",
                setOf(nodeId),
                nodes
            )
        )
        val atlas = NodeAtlas(
            atlasId = atlasId,
            runId = "run-test",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
        repository.initialize(
            PreparedAtlasMigration(
                receiverContract = receiver,
                atlas = atlas,
                executionStates = listOf(
                    NodeExecutionState(
                        nodeId = nodeId,
                        round = 0,
                        revealedToGroups = setOf(groupId)
                    )
                ),
                projections = AtlasProjectionSet(
                    wip = emptyList(),
                    deliverables = emptyList(),
                    resources = ResourceProjection("run-test", emptyList()),
                    disclosure = DisclosureProjection(
                        runId = "run-test",
                        stages = listOf(stage),
                        groupStageIndices = mapOf(groupId.value to 0)
                    ),
                    mappings = emptyList()
                )
            )
        )
        return atlas
    }

    private fun assignedWorkerAuthority(
        request: WorkerAuthorityRequest,
        finalizeLease: suspend (Boolean) -> Unit = {}
    ): WorkerAuthorityAssignment {
        val nodeId = NodeId(requireNotNull(request.wipTaskClaim).nodeId)
        val assignedNode = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.KNOWLEDGE,
            receiverWhyRef = "receiver obligation",
            limitationStatement = "The assigned evidence gap remains unresolved.",
            capabilityTarget = "A verified evidence result.",
            atomicQuestion = request.wipTaskClaim.description,
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Evidence is cited")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "payload-${nodeId.value}"
        )
        val skeletonNode = AtlasSkeletonNodeView(
            nodeId = nodeId,
            kind = assignedNode.kind,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            alternativeToIds = emptySet(),
            stageId = "stage-0",
            payloadHash = assignedNode.payloadHash,
            status = NodeStatus.SEALED,
            congestionLevel = 0
        )
        val leaseExpiry = Instant.parse("2029-12-31T23:59:00Z")
        return WorkerAuthorityAssignment(
            workerView = WorkerView(
                runId = "run-test",
                groupId = AtlasGroupId("group-0"),
                round = request.generation,
                stageId = "stage-0",
                visibleAtlasSkeleton = AtlasSkeletonView(
                    atlasId = "atlas-test",
                    atlasVersion = 1,
                    atlasHash = "atlas-hash",
                    rootNodeIds = setOf(nodeId),
                    nodes = mapOf(nodeId to skeletonNode)
                ),
                revealedNodes = listOf(assignedNode),
                assignedNode = assignedNode,
                groupFeedback = null
            ).withComputedHash(),
            assignedLease = WorkerLeaseAssignment(
                leaseId = "lease-${request.agentId}",
                nodeId = nodeId,
                expiresAt = leaseExpiry,
                replicationSlot = 0
            ),
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2030-01-01T00:00:00Z")
            ),
            finalizeLease = finalizeLease
        )
    }

    private fun imbalancedThreeRoleAllocation() = RoleAllocation(
        runId = "run-test",
        groupId = "group-0",
        generation = 1,
        entries = listOf(
            RoleAllocationEntry(Role.Researcher, 1, 200.0),
            RoleAllocationEntry(Role.Builder, 1, 50.0),
            RoleAllocationEntry(Role.Critic, 1, 50.0)
        ),
        totalBudgetCeiling = 300.0,
        rationale = "test turn-taking imbalance"
    )

    @Test
    fun `dominantTurnTakingSignal returns a signal when one role exceeds the cutoff`() {
        val signal = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = SubstrateDatabaseFactory(createTempDirectory("lm-test-pure-").toFile()),
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = null
        ).dominantTurnTakingSignal(
            valuesByRole = mapOf(
                Role.Researcher to 200.0,
                Role.Builder to 50.0,
                Role.Critic to 50.0
            ),
            signalKind = "allocated",
            minimumDistinctRoles = 2
        )

        assertNotNull(signal)
        assertEquals(Role.Researcher, signal.dominantRole)
        assertEquals("allocated", signal.signalKind)
        assertEquals(3, signal.activeRoleCount)
        assertEquals(0.666, signal.dominantShare, 0.01)
    }

    @Test
    fun `participation entropy is higher for balanced role distributions than dominant ones`() {
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = SubstrateDatabaseFactory(createTempDirectory("lm-entropy-test-").toFile()),
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = null
        )

        val balanced = manager.participationEntropyMetrics(
            mapOf(
                Role.Researcher to 100.0,
                Role.Builder to 100.0,
                Role.Critic to 100.0
            )
        )
        val dominant = manager.participationEntropyMetrics(
            mapOf(
                Role.Researcher to 200.0,
                Role.Builder to 50.0,
                Role.Critic to 50.0
            )
        )

        assertNotNull(balanced)
        assertNotNull(dominant)
        assertTrue(balanced.normalizedEntropy > dominant.normalizedEntropy)
        assertTrue(balanced.entropy > dominant.entropy)
    }

    @Test
    fun `dominantTurnTakingSignal ignores balanced or under-participated role distributions`() {
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = SubstrateDatabaseFactory(createTempDirectory("lm-test-pure-2-").toFile()),
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = null
        )

        assertNull(
            manager.dominantTurnTakingSignal(
                valuesByRole = mapOf(
                    Role.Researcher to 100.0,
                    Role.Builder to 100.0,
                    Role.Critic to 100.0
                ),
                signalKind = "allocated",
                minimumDistinctRoles = 2
            )
        )
        assertNull(
            manager.dominantTurnTakingSignal(
                valuesByRole = mapOf(Role.Researcher to 300.0),
                signalKind = "allocated",
                minimumDistinctRoles = 2
            )
        )
    }

    /**
     * Polls a value on a real dispatcher until it becomes available.
     *
     * The lifecycle manager runs some work on background coroutines outside the test scheduler,
     * so tests that observe DB or boot-context side effects need a real-time wait helper.
     */
    private suspend fun <T : Any> awaitRealTimeValue(
        timeoutMs: Long = 5_000,
        pollIntervalMs: Long = 10,
        supplier: suspend () -> T?
    ): T = withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (true) {
                val value = supplier()
                if (value != null) return@withTimeout value
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            error("unreachable")
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `usesInProcessMode is true when localAgentRunner is provided`() {
        val manager = makeManager { _, _ -> }
        assertTrue(manager.usesInProcessMode,
            "Manager with localAgentRunner must report usesInProcessMode=true")
    }

    @Test
    fun `networkAllowlistFor maps web roles to broad public web access and offline roles to no internet`() {
        val manager = makeManager { _, _ -> }

        assertEquals(NetworkAllowlistConfig.RESEARCHER, manager.networkAllowlistFor(Role.Researcher))
        assertEquals(NetworkAllowlistConfig.BUILDER, manager.networkAllowlistFor(Role.Builder))
        assertEquals(NetworkAllowlistConfig.BROAD, manager.networkAllowlistFor(Role.Outlier))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Planner))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Critic))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Archivist))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.MetaPlanner))
    }

    @Test
    fun `networkAllowlistFor merges explicitly linked hosts into the role policy`() {
        val manager = makeManager { _, _ -> }
        val allowlist = manager.networkAllowlistFor(
            Role.Planner,
            ContextUrlAllowlistExtractor.extractHosts(
                "Review https://docs.example.com/guide and www.example.org/reference before you proceed."
            )
        )

        assertEquals(
            setOf("docs.example.com", "www.example.org"),
            allowlist.allowedDomains
        )
    }

    @Test
    fun `spawnGeneration launches an in-process agent and reports spawned callback`() = runTest {
        val spawnedAgents = mutableListOf<String>()
        val manager = makeManager { _, onBudgetLedger ->
            // Register a simple ledger
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            // Agent completes immediately
        }
        manager.onAgentSpawned = { agentId, _, _ -> spawnedAgents.add(agentId) }

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "Stage 1",
            modelAssignments = mapOf(Role.Researcher.name to "openai/gpt-4o-mini"),
            modelPools = emptyMap(),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )

        // Give the coroutine a chance to run
        testScheduler.advanceUntilIdle()

        assertEquals(1, spawnedAgents.size, "Exactly one agent should have been spawned")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `allocatedBudget in bootContext matches budgetPerAgent from allocation`() = runTest {
        var receivedBudget = 0.0
        val manager = makeManager { bootContext, onBudgetLedger ->
            receivedBudget = bootContext.allocatedBudget
            val ledger = BudgetLedger(bootContext.agentId, bootContext.allocatedBudget, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
        }

        val allocationBudget = 75.0
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(budgetPerAgent = allocationBudget),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(allocationBudget, receivedBudget, 0.001,
            "bootContext.allocatedBudget must equal the RoleAllocationEntry.budgetPerAgent")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `spawnGeneration emits an inspector flag when one role dominates the allocation`() = runTest {
        val harness = makeManagerHarness { bootContext, onBudgetLedger ->
            val ledger = BudgetLedger(
                agentId = bootContext.agentId,
                initialBudget = bootContext.allocatedBudget,
                floor = 0.0,
                config = budgetConfig
            )
            onBudgetLedger?.invoke(ledger)

            delay(1_000)
        }

        harness.manager.spawnGeneration(
            allocation = imbalancedThreeRoleAllocation(),
            stageDisclosure = "Stage fairness",
            modelAssignments = mapOf(
                Role.Researcher.name to "openai/gpt-4o-mini",
                Role.Builder.name to "openai/gpt-4o-mini",
                Role.Critic.name to "openai/gpt-4o-mini"
            ),
            modelPools = emptyMap(),
            rolePrompts = mapOf(
                Role.Researcher.name to "You are a researcher.",
                Role.Builder.name to "You are a builder.",
                Role.Critic.name to "You are a critic."
            ),
            scope = backgroundScope
        )

        testScheduler.advanceTimeBy(200)
        testScheduler.advanceUntilIdle()

        val inspectorFlags = awaitRealTimeValue(timeoutMs = 5_000) {
            harness.substrateFactory
                .get("run-test", "group-0")
                .eventLogDao()
                .getLatestByType("run-test", "group-0", EventTypes.INSPECTOR_FLAG, 10)
                .takeIf { it.isNotEmpty() }
        }

        assertTrue(inspectorFlags.isNotEmpty(), "Expected an inspector flag for turn-taking imbalance")
        assertTrue(
            inspectorFlags.any { it.payloadJson.contains("turn_taking") },
            "Inspector flag payload should classify the issue as turn-taking imbalance"
        )
        assertTrue(
            inspectorFlags.any { it.payloadJson.contains("conversationEntropy") && it.payloadJson.contains("normalizedConversationEntropy") },
            "Inspector flag payload should include conversation entropy metrics"
        )

        harness.manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `getLiveAgentCount decrements after agent completes`() = kotlinx.coroutines.runBlocking {
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            // Agent completes immediately
        }
        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = agentScope
        )
        awaitRealTimeValue { manager.getLiveAgentCount().takeIf { it == 0 } }

        assertEquals(0, manager.getLiveAgentCount(),
            "Live agent count should be 0 after agent completes")
        manager.terminateAll()
        agentScope.cancel()
    }

    @Test
    fun `snapshotLiveAgentBudgets reads current live state`() =
        kotlinx.coroutines.runBlocking {
            val runnerStarted = CompletableDeferred<Unit>()
            val releaseRunner = CompletableDeferred<Unit>()
            val harness = makeManagerHarness { _, onBudgetLedger ->
                onBudgetLedger?.invoke(BudgetLedger("snapshot", 50.0, 2.5, budgetConfig))
                runnerStarted.complete(Unit)
                releaseRunner.await()
            }
            val manager = harness.manager
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = agentScope
            )
            withTimeout(2_000L) { runnerStarted.await() }

            val snapshot = awaitRealTimeValue {
                manager.snapshotLiveAgentBudgets().takeIf { it.size == 1 }
            }
            assertEquals(listOf(50.0), snapshot.values.toList())

            releaseRunner.complete(Unit)
            awaitRealTimeValue {
                manager.snapshotLiveAgentBudgets().takeIf { it.isEmpty() }
            }
            agentScope.cancel()
        }

    @Test
    fun `spawnGeneration draws models from the role pool per agent spawn`() = runTest {
        val seenModels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val manager = makeManager(
            runner = { bootContext, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                seenModels.add(bootContext.modelAssignment)
            },
            modelSelectionRandom = Random(1)
        )

        val allocation = RoleAllocation(
            runId = "run-test",
            groupId = "group-0",
            generation = 1,
            entries = listOf(RoleAllocationEntry(Role.Researcher, 2, 50.0)),
            totalBudgetCeiling = 100.0,
            rationale = "test"
        )

        manager.spawnGeneration(
            allocation = allocation,
            stageDisclosure = "",
            modelAssignments = mapOf(Role.Researcher.name to "openai/gpt-4o-mini"),
            modelPools = mapOf(
                Role.Researcher.name to listOf(
                    "openai/gpt-4o-mini",
                    "deepseek/deepseek-v4-flash"
                )
            ),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(2, seenModels.size)
        assertEquals(
            setOf("deepseek/deepseek-v4-flash", "openai/gpt-4o-mini"),
            seenModels.toSet()
        )
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `preset lifecycle selection respects exact weighted boundaries`() {
        val preset = CodexRuntimePreset.MIXED_FAMILIES
        val cases = listOf(
            Triple(Role.Outlier, 0, CodexRuntimePreset.LUNA),
            Triple(Role.Outlier, 84, CodexRuntimePreset.LUNA),
            Triple(Role.Outlier, 85, CodexRuntimePreset.SOL),
            Triple(Role.Outlier, 91, CodexRuntimePreset.SOL),
            Triple(Role.Outlier, 92, CodexRuntimePreset.TERRA),
            Triple(Role.Outlier, 98, CodexRuntimePreset.TERRA),
            Triple(Role.Outlier, 99, CodexRuntimePreset.ASTRA),
            Triple(Role.Planner, 49, CodexRuntimePreset.SOL),
            Triple(Role.Planner, 50, CodexRuntimePreset.TERRA),
            Triple(Role.Critic, 49, CodexRuntimePreset.SOL),
            Triple(Role.Critic, 50, CodexRuntimePreset.TERRA),
            Triple(Role.Inspector, 49, CodexRuntimePreset.SOL),
            Triple(Role.Inspector, 50, CodexRuntimePreset.TERRA),
            Triple(Role.PerspectiveTaker, 49, CodexRuntimePreset.SOL),
            Triple(Role.PerspectiveTaker, 50, CodexRuntimePreset.TERRA)
        )
        cases.forEach { (role, draw, expected) ->
            val random = object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Unexpected random call")
                override fun nextInt(until: Int): Int {
                    assertEquals(100, until)
                    return draw
                }
            }
            val manager = makeManager(
                modelSelectionRandom = random,
                codexRuntimePreset = preset
            ) { _, _ -> }
            assertEquals(
                expected,
                manager.resolveModelForRole(
                    role, preset.runtimeAssignments(), preset.runtimeModelPools()
                )
            )
        }
    }

    @Test
    fun `preset lifecycle singleton and explicit overrides precede distributions`() {
        val manager = makeManager(
            codexRuntimePreset = CodexRuntimePreset.MIXED_FAMILIES,
            modelSelectionRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Singleton must not sample")
            }
        ) { _, _ -> }
        assertEquals(
            CodexRuntimePreset.LUNA,
            manager.resolveModelForRole(
                Role.Planner,
                mapOf(Role.Planner.name to CodexRuntimePreset.ASTRA),
                mapOf(Role.Planner.name to listOf(CodexRuntimePreset.LUNA))
            )
        )
        assertEquals(
            CodexRuntimePreset.ASTRA,
            manager.resolveModelForRole(
                Role.Outlier, emptyMap(),
                CodexRuntimePreset.MIXED_FAMILIES.runtimeModelPools(),
                explicitModelAssignment = CodexRuntimePreset.ASTRA
            )
        )
    }

    @Test
    fun `full Luna lifecycle fallback cannot select legacy provider models`() {
        val manager = makeManager(codexRuntimePreset = CodexRuntimePreset.FULL_LUNA) { _, _ -> }
        Role.entries.forEach { role ->
            assertEquals(
                CodexRuntimePreset.LUNA,
                manager.resolveModelForRole(role, emptyMap(), emptyMap())
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            manager.resolveModelForRole(
                Role.Researcher, mapOf(Role.Researcher.name to "openai/gpt-4o-mini"), emptyMap()
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            manager.resolveModelForRole(
                Role.Researcher, emptyMap(),
                mapOf(Role.Researcher.name to listOf("openai/gpt-4o-mini"))
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            manager.resolveModelForRole(
                Role.Planner, emptyMap(), emptyMap(),
                explicitModelAssignment = CodexRuntimePreset.ASTRA
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            manager.resolveModelForRole(
                Role.Planner, emptyMap(),
                mapOf(Role.Planner.name to listOf(CodexRuntimePreset.ASTRA))
            )
        }
    }

    @Test
    fun `terminateAll cancels running agents`() = runTest {
        val startedFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelledFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val diedFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            startedFlag.complete(Unit)
            // Long-running agent — blocks until cancelled
            try {
                kotlinx.coroutines.delay(600_000)
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelledFlag.complete(Unit)
                throw kotlinx.coroutines.CancellationException()
            }
        }
        manager.onAgentDied = { _, _ -> diedFlag.complete(Unit) }

        val agentJob = launch {
            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = backgroundScope
            )
        }
        testScheduler.advanceUntilIdle()
        startedFlag.await()

        manager.terminateAll()
        testScheduler.advanceUntilIdle()

        assertTrue(
            cancelledFlag.isCompleted || diedFlag.isCompleted || manager.getLiveAgentCount() == 0,
            "terminateAll should cancel the running agent coroutine and/or report agent death"
        )
        agentJob.cancel()
    }

    @Test
    fun `spawnSpecialAgent does not inject WIP claim or Legacy Protocol`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val manager = makeManager { bootContext, _ ->
            seenContexts += bootContext
        }

        manager.spawnSpecialAgent(
            role = Role.MetaPlanner,
            generation = 7,
            budgetPerAgent = 60.0,
            stageDisclosure = "",
            modelAssignment = "anthropic/claude-sonnet-4",
            rolePrompt = "You are a MetaPlanner.",
            scope = backgroundScope
        )

        val seenContext = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()

        assertEquals(1, seenContexts.size, "Special spawn should create exactly one boot context")
        assertNull(seenContext.wipTaskClaim, "MetaPlanner must not consume a WIP task claim")
        assertNull(seenContext.legacyProtocol, "MetaPlanner must not inherit a Legacy Protocol")
    }

    @Test
    fun `ordinary same role agents inherit active legacy protocol but outliers do not`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val harness = makeManagerHarness { bootContext, _ ->
            seenContexts += bootContext
        }
        val db = harness.substrateFactory.get("run-test", "group-0")
        db.legacyProtocolDao().insert(
            LegacyProtocolEntity(
                id = "legacy-researcher-1",
                role = Role.Researcher.name,
                extractedFromGeneration = 1,
                expiresAtGeneration = 4,
                reasoningPosture = "Preserve validated claims before expanding.",
                toolHeuristicsJson = """{"read_kb":2}""",
                failureMapJson = """{"execute_tool":{"failures":1}}""",
                kgHabitsJson = """{"dominantRelations":[{"relation":"SUPPORTS","count":1}]}""",
                sourceAgentIds = "agent-old",
                groupId = "group-0",
                runId = "run-test",
                createdAtMs = 1_000L
            )
        )

        harness.manager.spawnGeneration(
            allocation = RoleAllocation(
                runId = "run-test",
                groupId = "group-0",
                generation = 2,
                entries = listOf(
                    RoleAllocationEntry(Role.Researcher, 1, 50.0),
                    RoleAllocationEntry(Role.Outlier, 1, 50.0, forceOutlier = true)
                ),
                totalBudgetCeiling = 100.0,
                rationale = "legacy protocol inheritance test"
            ),
            stageDisclosure = "Stage",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )

        awaitRealTimeValue { seenContexts.takeIf { it.size == 2 } }
        harness.manager.terminateAll()

        assertEquals("legacy-researcher-1", seenContexts.single { it.role == Role.Researcher }.legacyProtocol?.protocolId)
        assertNull(seenContexts.single { it.role == Role.Outlier }.legacyProtocol)
    }

    @Test
    fun `spawned agents receive a bounded canonical working context`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val harness = makeManagerHarness { bootContext, _ -> seenContexts += bootContext }
        val db = harness.substrateFactory.get("run-test", "group-0")
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-grounded",
                content = "Verified market observation from a prior agent.",
                sourceAgentId = "agent-prior",
                generation = 1,
                trustScore = 0.9,
                citationCount = 2,
                domain = "market",
                groupId = "group-0",
                runId = "run-test",
                createdAtMs = 1L
            )
        )
        db.knowledgeBaseDao().insertAll(
            (0 until 11).map { index ->
                KnowledgeBaseEntryEntity(
                    id = "kb-priority-$index",
                    content = "High-priority ordinary canonical knowledge $index.",
                    sourceAgentId = "agent-prior",
                    generation = 2,
                    trustScore = 0.99,
                    citationCount = 10,
                    domain = "market",
                    groupId = "group-0",
                    runId = "run-test",
                    createdAtMs = 10L + index
                )
            }
        )
        db.knowledgeBaseDao().insertAll(
            listOf(
                KnowledgeBaseEntryEntity(
                    id = "dispatch-inbox-grounded",
                    content = "FORMAL DISPATCH: neighbouring evidence that must reach later agents.",
                    sourceAgentId = "dispatch:group-1",
                    generation = 1,
                    trustScore = 0.5,
                    domain = "dispatch_inbox",
                    groupId = "group-0",
                    runId = "run-test",
                    createdAtMs = 30L
                ),
                KnowledgeBaseEntryEntity(
                    id = "anonymous-leak-grounded",
                    content = "Privacy-transformed anonymous insight that must reach later agents.",
                    sourceAgentId = "watchdog-leak-oracle",
                    generation = 1,
                    trustScore = 0.65,
                    domain = "intelligence_leaks",
                    groupId = "group-0",
                    runId = "run-test",
                    createdAtMs = 31L
                )
            )
        )
        db.knowledgeGraphFactDao().insert(
            KnowledgeGraphFactEntity(
                id = "kg-grounded",
                subject = "Company A",
                relation = "HAS_PRICE",
                objectNode = "\$10/month",
                confidence = 0.8,
                sourceAgentId = "agent-prior",
                generation = 1,
                provenanceJson = """["https://example.com/pricing"]""",
                groupId = "group-0",
                runId = "run-test",
                createdAtMs = 2L
            )
        )
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "doc-promoted",
                agentId = "agent-prior",
                role = Role.Researcher.name,
                type = "DOC",
                content = "Promoted competitor comparison.",
                budgetSignature = "test",
                status = "promoted",
                generation = 1,
                groupId = "group-0",
                runId = "run-test",
                submittedAtMs = 3L
            )
        )
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "death-auto",
                agentId = "agent-prior",
                role = Role.Researcher.name,
                type = "DEATH_NOTE",
                content = "AUTO-GENERATED terminal note.",
                budgetSignature = "test",
                status = "promoted",
                generation = 1,
                groupId = "group-0",
                runId = "run-test",
                submittedAtMs = 4L
            )
        )
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "wip-pricing",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "Validate pricing evidence.",
                    lastChangedGeneration = 1
                )
            )
        )

        harness.manager.spawnSpecialAgent(
            role = Role.Researcher,
            generation = 2,
            budgetPerAgent = 20.0,
            stageDisclosure = "Use prior evidence.",
            modelAssignment = "test-model",
            rolePrompt = "You are a Researcher.",
            scope = backgroundScope
        )

        val context = awaitRealTimeValue { seenContexts.firstOrNull() }
        harness.manager.terminateAll()
        val snapshot = assertNotNull(context.canonicalWorkingContext)
        assertTrue(snapshot.length <= 16_000)
        assertTrue(snapshot.contains("kb-grounded"))
        assertTrue(snapshot.contains("kg-grounded"))
        assertTrue(snapshot.contains("doc-promoted"))
        assertTrue(snapshot.contains("wip-pricing"))
        assertTrue(snapshot.contains("https://example.com/pricing"))
        assertTrue(snapshot.contains("CROSS-GROUP INBOX (2 shown)"))
        assertTrue(snapshot.contains("dispatch-inbox-grounded"))
        assertTrue(snapshot.contains("anonymous-leak-grounded"))
        assertTrue(!snapshot.contains("death-auto"))
        assertTrue(!snapshot.contains("AUTO-GENERATED terminal note"))
    }

    @Test
    fun `in process agents receive host artifact path instead of vm mount path`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val manager = makeManager { bootContext, _ ->
            seenContexts += bootContext
        }
        val artifactsRoot = createTempDirectory("lm-artifacts-").toFile()

        manager.configureRunArtifacts(
            inputArtifacts = listOf(
                RunInputArtifact(
                    relativePath = "previous-stage/results-bundle/outputs/final-article.html",
                    sourceName = "final-article.html",
                    mimeType = "text/html",
                    sizeBytes = 128,
                    sha256 = "test-sha",
                    kind = RunInputArtifactKind.TEXT,
                    textPreview = "<!doctype html>"
                )
            ),
            artifactsMountPath = artifactsRoot.absolutePath
        )

        manager.spawnSpecialAgent(
            role = Role.Builder,
            generation = 1,
            budgetPerAgent = 12.5,
            stageDisclosure = "",
            modelAssignment = "test-model",
            rolePrompt = "You are a Final Deliverable Writer.",
            scope = backgroundScope
        )

        val seenContext = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()

        assertEquals(
            artifactsRoot.absolutePath,
            seenContext.artifactsMountPath,
            "In-process agents must receive the host artifact path so read_run_artifact can open files"
        )
    }

    @Test
    fun `connector receives explicit text-only dispatch intent`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val manager = makeManager { bootContext, _ ->
            seenContexts += bootContext
        }

        manager.spawnSpecialAgent(
            role = Role.Connector,
            generation = 1,
            budgetPerAgent = 12.5,
            stageDisclosure = "",
            modelAssignment = "test-model",
            rolePrompt = "You are a Connector.",
            scope = backgroundScope
        )

        val context = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()
        assertEquals(SubmissionType.DISPATCH, context.textOnlySubmissionType)
    }

    @Test
    fun `spawned agents receive current and dynamically approved source hosts`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val harness = makeManagerHarness { bootContext, _ ->
            seenContexts += bootContext
        }
        val manager = harness.manager
        val runRoot = harness.substrateFactory.dbFilePath("run-test", "group-0")
            .parentFile
            .parentFile
            .parentFile
        val auditFile = runRoot.resolve("control/${SourceAccessDecisionStore.FILE_NAME}")
        SourceAccessDecisionStore(auditFile).append(
            SourceAccessDecisionRecord(
                runId = "run-test",
                groupId = "group-0",
                agentId = "agent-prior",
                role = "Researcher",
                generation = 0,
                url = "https://help.aliyun.com/document_detail/test",
                host = "help.aliyun.com",
                purpose = "Approved during a previous source request.",
                allowed = true,
                reason = "Host matches Qwen subject."
            )
        )

        manager.configureRunArtifacts(
            inputArtifacts = emptyList(),
            artifactsMountPath = null,
            initialContextAllowedHosts = setOf("reference.example.com")
        )

        manager.spawnSpecialAgent(
            role = Role.Researcher,
            generation = 1,
            budgetPerAgent = 12.5,
            stageDisclosure = "Read https://docs.example.com/reference if available.",
            modelAssignment = "test-model",
            rolePrompt = "You are a Researcher.",
            scope = backgroundScope
        )

        val seenContext = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()

        assertTrue("reference.example.com" in seenContext.sourceAllowedHosts)
        assertTrue("docs.example.com" in seenContext.sourceAllowedHosts)
        assertTrue("help.aliyun.com" in seenContext.sourceAllowedHosts)
        assertEquals(auditFile.absolutePath, seenContext.sourceAccessAuditPath)
    }

    @Test
    fun `budget telemetry retains settled unused credits without changing legacy availability`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<BudgetLedger>()
        val manager = makeManager { context, register ->
            val ledger = BudgetLedger(context.agentId, context.allocatedBudget, 2.5, budgetConfig)
            register?.invoke(ledger)
            ledger.deductRaw(12.0, "telemetry-test")
            ready.complete(ledger)
            finish.await()
        }
        val observations = java.util.concurrent.CopyOnWriteArrayList<LifecycleBudgetTelemetry>()
        manager.onBudgetTelemetryChanged = { observations += it }
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        ready.await()
        val live = manager.snapshotBudgetTelemetry(1)
        assertEquals(50.0, live.allocatedCredits)
        assertEquals(50.0, live.liveAllocatedCredits)
        assertEquals(38.0, live.liveCredits)
        assertEquals(12.0, live.spentCredits)
        assertEquals(0.0, live.returnedCredits)
        assertEquals(1, live.liveAgentCount)
        finish.complete(Unit)
        awaitRealTimeValue { manager.getLiveAgentCount().takeIf { it == 0 } }
        awaitRealTimeValue { observations.lastOrNull()?.takeIf { it.liveAgentCount == 0 } }
        val settled = manager.snapshotBudgetTelemetry(1)
        assertEquals(50.0, settled.allocatedCredits)
        assertEquals(0.0, settled.liveAllocatedCredits)
        assertEquals(0.0, settled.liveCredits)
        assertEquals(12.0, settled.spentCredits)
        assertEquals(38.0, settled.returnedCredits)
        assertEquals(0, settled.liveAgentCount)
        assertEquals(38.0, live.liveCredits, "Prior observation must remain immutable")
        assertEquals(0.0, manager.snapshotBudgetTelemetry(2).allocatedCredits)
        manager.terminateAll()
        assertEquals(settled.returnedCredits, manager.snapshotBudgetTelemetry(1).returnedCredits)
    }

    @Test
    fun `targeted quiescence timeout retains executing agent ownership`() = runTest {
        val ready = CompletableDeferred<String>()
        val finish = CompletableDeferred<Unit>()
        val manager = makeManager { context, _ ->
            ready.complete(context.agentId)
            withContext(NonCancellable) { finish.await() }
        }
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        val agentId = ready.await()
        manager.terminateAgent(agentId)
        assertFailsWith<TimeoutCancellationException> {
            manager.awaitAgentQuiescence(agentId, timeoutMs = 100)
        }
        assertEquals(1, manager.snapshotBudgetTelemetry(1).liveAgentCount)
        assertEquals(0.0, manager.snapshotBudgetTelemetry(1).returnedCredits)
        finish.complete(Unit)
        withContext(Dispatchers.Default) {
            manager.awaitAgentQuiescence(agentId, timeoutMs = 5_000)
        }
        assertEquals(0, manager.snapshotBudgetTelemetry(1).liveAgentCount)
        assertEquals(50.0, manager.snapshotBudgetTelemetry(1).returnedCredits)
        manager.terminateAll()
    }

    @Test
    fun `candidate staged stop carries expected marker and preserves killed exit accounting`() = runTest {
        val started = CompletableDeferred<String>()
        val stopped = CompletableDeferred<kotlinx.coroutines.CancellationException>()
        val harness = makeManagerHarness { context, _ ->
            started.complete(context.agentId)
            try {
                awaitCancellation()
            } catch (error: kotlinx.coroutines.CancellationException) {
                stopped.complete(error)
                throw error
            }
        }
        val manager = harness.manager
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        val agentId = started.await()
        assertTrue(manager.terminateAgentAfterCandidateStaged(agentId))
        val cancellation = stopped.await()
        assertTrue(cancellation is com.achulkov.evomolior.core.contract.ExpectedAgentStopCancellationException)
        assertEquals("candidate_staged", cancellation.reason)
        withContext(Dispatchers.Default) {
            manager.awaitAgentQuiescence(agentId, timeoutMs = 5_000)
        }
        val exit = WorkerExecutionRepository(harness.substrateFactory.get("run-test", "group-0"))
            .exits("run-test", "group-0").single()
        assertTrue(exit.wasKilled, "Controlled cancellation must not manufacture natural completion")
        assertEquals(0, manager.getLiveAgentCount())
        assertFalse(manager.terminateAgentAfterCandidateStaged(agentId))
        manager.terminateAll()
    }

    @Test
    fun `polling includes prior policy refund while another agent spends`() = runTest {
        val ordinal = java.util.concurrent.atomic.AtomicInteger()
        val abort = CompletableDeferred<Unit>()
        val survivorLedger = CompletableDeferred<BudgetLedger>()
        val harness = makeManagerHarness { context, register ->
            val ledger = BudgetLedger(context.agentId, context.allocatedBudget, 2.5, budgetConfig)
            register?.invoke(ledger)
            if (ordinal.getAndIncrement() == 0) {
                abort.await()
                ledger.deductRaw(12.0, "aborted-spend")
                throw BlockingAgentAbortException("blocking-test")
            } else {
                survivorLedger.complete(ledger)
                awaitCancellation()
            }
        }
        val manager = harness.manager
        val available = java.util.concurrent.atomic.AtomicReference(-1.0)
        manager.onBudgetChanged = { available.set(it) }
        manager.spawnGeneration(
            allocation = singleResearcherAllocation().copy(
                entries = listOf(RoleAllocationEntry(Role.Researcher, 2, 50.0)),
                totalBudgetCeiling = 100.0
            ),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        val survivor = survivorLedger.await()
        abort.complete(Unit)
        awaitRealTimeValue { available.get().takeIf { it == 100.0 } }
        survivor.deductRaw(10.0, "survivor-spend")
        testScheduler.advanceTimeBy(200)
        awaitRealTimeValue { available.get().takeIf { it == 90.0 } }
        val telemetry = manager.snapshotBudgetTelemetry(1)
        assertEquals(100.0, telemetry.allocatedCredits)
        assertEquals(50.0, telemetry.liveAllocatedCredits)
        assertEquals(40.0, telemetry.liveCredits)
        assertEquals(22.0, telemetry.spentCredits)
        assertEquals(38.0, telemetry.returnedCredits)
        assertEquals(1, telemetry.liveAgentCount)
        manager.terminateAll()
    }

    @Test
    fun `blocking agent abort is treated as a kill and refunds the full group allocation`() = runTest {
        var lastBudgetTotal = -1.0
        val readyToAbort = kotlinx.coroutines.CompletableDeferred<Unit>()
        val spawned = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            readyToAbort.await()
            ledger.deductRaw(12.0, "blocking-test-spend")
            throw BlockingAgentAbortException("tool validation failed")
        }
        manager.onBudgetChanged = { lastBudgetTotal = it }
        manager.onAgentSpawned = { _, _, _ -> spawned.complete(Unit) }

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )
        spawned.await()
        readyToAbort.complete(Unit)
        testScheduler.advanceUntilIdle()
        awaitRealTimeValue { manager.getLiveAgentCount().takeIf { it == 0 } }
        awaitRealTimeValue { lastBudgetTotal.takeIf { it >= 0.0 } }
        assertEquals(0, manager.getLiveAgentCount(), "Blocking failure must remove the agent from the live set")
        assertEquals(50.0, lastBudgetTotal, 0.001, "Blocking failure must refund the full agent allocation")
        val telemetry = manager.snapshotBudgetTelemetry(1)
        assertEquals(50.0, telemetry.allocatedCredits)
        assertEquals(12.0, telemetry.spentCredits, "A policy refund must not erase actual ledger consumption")
        assertEquals(38.0, telemetry.returnedCredits, "Returned telemetry is unused balance, not policy refund")
        assertEquals(0, telemetry.liveAgentCount)
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `claimed WIP task is injected into the boot context`() = runTest {
        val tmpDir = createTempDirectory("lm-wip-abandon-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val db = substrateFactory.get("run-test", "group-0")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-1",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "draft the first plan",
                    status = "unclaimed",
                    lastChangedGeneration = 0
                )
            )
        )

        val seenClaims = mutableListOf<String?>()
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = com.achulkov.evomolior.sandbox.shuru.ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { bootContext, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                seenClaims += bootContext.wipTaskClaim?.nodeId
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, seenClaims.size)
        assertEquals("node-1", seenClaims.single())
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `claimed WIP task remains claimed while exited agent contribution awaits critic review`() = runTest {
        val tmpDir = createTempDirectory("lm-wip-pending-review-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val db = substrateFactory.get("run-test", "group-0")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-pending-review",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "produce a critic-reviewed artifact",
                    status = "unclaimed",
                    lastChangedGeneration = 0
                )
            )
        )

        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { bootContext, _ ->
                db.stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "pending-review-doc",
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        type = "DOC",
                        content = "Substantive task artifact awaiting Critic review.",
                        wipTaskRef = bootContext.wipTaskClaim?.nodeId,
                        budgetSignature = "sig",
                        generation = bootContext.generation,
                        groupId = bootContext.groupId,
                        runId = bootContext.runId,
                        submittedAtMs = 1_000L
                    )
                )
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        val stored = assertNotNull(
            db.wipTaskDao().getByNodeId("node-pending-review", "run-test", "group-0")
        )
        assertEquals("claimed", stored.status)
        assertNotNull(stored.claimedByAgentId)
        assertEquals(0, stored.abandonCount)
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `claimed WIP task is abandoned on forced shutdown`() = kotlinx.coroutines.runBlocking {
        val tmpDir = createTempDirectory("lm-wip-abandon-kill-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val db = substrateFactory.get("run-test", "group-0")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-2",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "finish the plan",
                    status = "unclaimed",
                    lastChangedGeneration = 0
                )
            )
        )

        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = com.achulkov.evomolior.sandbox.shuru.ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { _, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                kotlinx.coroutines.delay(60_000)
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )

        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = agentScope
        )

        val claimed = awaitRealTimeValue {
            db.wipTaskDao().getByNodeId("node-2", "run-test", "group-0")
                ?.takeIf { it.status == "claimed" }
        }
        assertEquals("claimed", claimed.status)

        manager.terminateAll()
        val stored = awaitRealTimeValue {
            db.wipTaskDao().getByNodeId("node-2", "run-test", "group-0")
                ?.takeIf { it.status == "unclaimed" && it.abandonCount == 1 }
        }
        assertEquals("unclaimed", stored.status)
        assertEquals(1, stored.abandonCount)
        agentScope.cancel()
    }

    @Test
    fun `shutdown drain waits for delayed provider cancellation and persists cleanup exactly once`() =
        kotlinx.coroutines.runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancellationReceived = CompletableDeferred<Unit>()
            val releaseProvider = CompletableDeferred<Unit>()
            val returned = java.util.concurrent.atomic.AtomicBoolean(false)
            val deaths = java.util.concurrent.atomic.AtomicInteger()
            val harness = makeManagerHarness { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cancellationReceived.complete(Unit)
                        releaseProvider.await()
                        returned.set(true)
                    }
                }
            }
            val manager = harness.manager
            manager.onAgentDied = { _, _ -> deaths.incrementAndGet() }
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { started.await() }
                withTimeout(2_000L) { manager.terminateAll() }
                withTimeout(2_000L) { cancellationReceived.await() }
                val repository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", "group-0")
                )
                val frozenExit = repository.exits("run-test", "group-0").single()
                val firstDrain = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.awaitQuiescence(5_000L)
                }
                val concurrentDrain = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.awaitQuiescence(5_000L)
                }
                assertFalse(firstDrain.isCompleted)
                assertFalse(concurrentDrain.isCompleted)
                assertFalse(manager.publicationAccounting().isQuiescent)
                assertEquals(0, deaths.get())
                releaseProvider.complete(Unit)

                assertTrue(firstDrain.await().isQuiescent)
                assertTrue(concurrentDrain.await().isQuiescent)
                assertTrue(returned.get())
                val finalState = repository.runtimeStateEvents(
                    "run-test", "group-0", frozenExit.agentId.value, frozenExit.round
                ).last().state
                assertTrue(finalState.executionSettled)
                assertTrue(finalState.costSettled)
                assertTrue(finalState.cleanupComplete)
                manager.terminateAll()
                assertTrue(manager.awaitQuiescence(2_000L).isQuiescent)
                assertEquals(listOf(frozenExit), repository.exits("run-test", "group-0"))
                assertEquals(1, deaths.get())
                assertEquals(0, manager.lifecycleReconciliationMetrics().deathCallbackDuplicateCount)
            } finally {
                releaseProvider.complete(Unit)
                agentScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `shutdown drain times out truthfully and caller cancellation does not settle stuck runner`() =
        kotlinx.coroutines.runBlocking {
            val started = CompletableDeferred<Unit>()
            val releaseProvider = CompletableDeferred<Unit>()
            val harness = makeManagerHarness { _, _ ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    releaseProvider.await()
                }
            }
            val manager = harness.manager
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { started.await() }
                withTimeout(2_000L) { manager.terminateAll() }
                val before = manager.publicationAccounting()
                val began = System.nanoTime()
                withTimeout(5_000L) {
                    assertFailsWith<TimeoutCancellationException> {
                        manager.awaitQuiescence(100L)
                    }
                }
                assertTrue((System.nanoTime() - began) / 1_000_000L < 4_000L)
                assertEquals(before, manager.publicationAccounting())
                assertEquals(1, before.liveNonDaemonAgentIds.size)
                assertFalse(before.isQuiescent)

                val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.awaitQuiescence()
                }
                withTimeout(2_000L) { cancelledWaiter.cancelAndJoin() }
                assertTrue(cancelledWaiter.isCancelled)
                assertEquals(before, manager.publicationAccounting())
                val repository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", "group-0")
                )
                val state = repository.runtimeStateEvents(
                    "run-test", "group-0", before.liveNonDaemonAgentIds.single(),
                    singleResearcherAllocation().generation
                ).last().state
                assertFalse(state.executionSettled)
                assertFalse(state.costSettled)
                assertFalse(state.cleanupComplete)
                releaseProvider.complete(Unit)
                assertTrue(manager.awaitQuiescence(5_000L).isQuiescent)
            } finally {
                releaseProvider.complete(Unit)
                agentScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `shutdown drain handles execution cancelled before launch body begins`() =
        kotlinx.coroutines.runBlocking {
            val invoked = java.util.concurrent.atomic.AtomicBoolean(false)
            val manager = makeManager { _, _ -> invoked.set(true) }
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            agentScope.cancel()
            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = agentScope
            )
            manager.terminateAll()
            assertTrue(manager.awaitQuiescence(5_000L).isQuiescent)
            assertFalse(invoked.get())
            assertFailsWith<IllegalArgumentException> { manager.awaitQuiescence(0L) }
        }

    @Test
    fun `cancellation resistant in-process agent blocks quiescence until execution finishes`() =
        kotlinx.coroutines.runBlocking {
        val releaseRunner = CompletableDeferred<Unit>()
        val runnerStarted = CompletableDeferred<Unit>()
        val harness = makeManagerHarness { _, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                runnerStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRunner.await()
                }
            }
        val manager = harness.manager
        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = agentScope
        )
        withTimeout(2_000L) { runnerStarted.await() }
        assertEquals(1, manager.getLiveAgentCount())

        withTimeout(1_000L) {
            manager.terminateAll()
        }

        assertEquals(1, manager.getLiveAgentCount())
        assertEquals(
            1,
            WorkerExecutionRepository(
                harness.substrateFactory.get("run-test", "group-0")
            ).exits("run-test", "group-0").size
        )
        assertFalse(manager.publicationAccounting().isQuiescent)
        releaseRunner.complete(Unit)
        awaitRealTimeValue {
            manager.publicationAccounting().takeIf { it.isQuiescent }
        }
        assertEquals(0, manager.getLiveAgentCount())
        agentScope.cancel()
    }

    @Test
    fun `durable exit precedes fallible lease cleanup and retry remains non-quiescent`() =
        kotlinx.coroutines.runBlocking {
            val cleanupAttempts = java.util.concurrent.atomic.AtomicInteger()
            val deathCallbackCount = java.util.concurrent.atomic.AtomicInteger()
            val durableBeforeCleanup = CompletableDeferred<Unit>()
            val harness = makeManagerHarness(budgetPollingIntervalMs = 60_000L) { _, _ -> }
            seedWorkerWip(harness.substrateFactory)
            val manager = harness.manager
            manager.configureWorkerAuthority { request ->
                assignedWorkerAuthority(
                    request = request,
                    finalizeLease = {
                        val exits = WorkerExecutionRepository(
                            harness.substrateFactory.get("run-test", "group-0")
                        ).exits("run-test", "group-0")
                        assertEquals(1, exits.size)
                        assertEquals(request.agentId, exits.single().agentId.value)
                        durableBeforeCleanup.complete(Unit)
                        if (cleanupAttempts.incrementAndGet() == 1) {
                            error("transient lease cleanup failure")
                        }
                    }
                )
            }
            manager.onAgentDied = { _, _ -> deathCallbackCount.incrementAndGet() }
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { durableBeforeCleanup.await() }

                val duringRetry = manager.publicationAccounting()
                assertEquals(1, duringRetry.exitedNonDaemonAgentIds.size)
                assertTrue(duringRetry.unaccountedNonDaemonAgentIds.isEmpty())
                assertFalse(duringRetry.isQuiescent)
                assertEquals(0, deathCallbackCount.get())

                withTimeout(2_000L) { manager.terminateAll() }

                assertEquals(2, cleanupAttempts.get())
                assertEquals(1, deathCallbackCount.get())
                assertEquals(
                    1,
                    WorkerExecutionRepository(
                        harness.substrateFactory.get("run-test", "group-0")
                    ).exits("run-test", "group-0").size
                )
                assertTrue(manager.publicationAccounting().isQuiescent)
            } finally {
                agentScope.cancel()
            }
        }

    @Test
    fun `ttl expiry cleans frozen authority but blocks quiescence until runner finishes`() =
        kotlinx.coroutines.runBlocking {
            val releaseRunner = CompletableDeferred<Unit>()
            val runnerStarted = CompletableDeferred<Unit>()
            val agentDied = CompletableDeferred<Unit>()
            val leaseFinalizationCount = java.util.concurrent.atomic.AtomicInteger()
            val deathCallbackCount = java.util.concurrent.atomic.AtomicInteger()
            val harness = makeManagerHarness(budgetPollingIntervalMs = 10L) { _, onBudgetLedger ->
                val ledger = BudgetLedger("ttl-worker", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                runnerStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRunner.await()
                }
            }
            seedWorkerWip(harness.substrateFactory)
            val manager = harness.manager
            manager.configureWorkerAuthority { request ->
                val expiry = kotlin.time.Clock.System.now() + 2.seconds
                assignedWorkerAuthority(
                    request = request,
                    finalizeLease = { leaseFinalizationCount.incrementAndGet() }
                ).copy(
                    assignedLease = WorkerLeaseAssignment(
                        leaseId = "ttl-lease-${request.agentId}",
                        nodeId = NodeId(requireNotNull(request.wipTaskClaim).nodeId),
                        expiresAt = expiry,
                        replicationSlot = 0
                    ),
                    ttlPolicy = AgentTtlPolicy(
                        lifetimeSeconds = 1L,
                        deadline = expiry
                    )
                )
            }
            manager.onAgentDied = { _, _ ->
                deathCallbackCount.incrementAndGet()
                agentDied.complete(Unit)
            }
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { runnerStarted.await() }

                val repository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", "group-0")
                )
                val exit = awaitRealTimeValue {
                    repository.exits("run-test", "group-0").singleOrNull()
                }
                assertTrue(exit.wasKilled)
                assertEquals(1, leaseFinalizationCount.get())
                assertEquals(0, deathCallbackCount.get())
                assertEquals(
                    listOf(
                        TtlCheckpointKind.WORK_WARNING,
                        TtlCheckpointKind.COMMIT_SELECTION_REQUIRED,
                        TtlCheckpointKind.BROAD_EXPLORATION_DISABLED,
                        TtlCheckpointKind.DEATH_NOTE_GRACE,
                        TtlCheckpointKind.TERMINATED
                    ),
                    repository.checkpointsForAgent("run-test", "group-0", exit.agentId.value)
                        .map { it.checkpoint }
                )
                assertEquals(1, manager.getLiveAgentCount())
                assertFalse(manager.publicationAccounting().isQuiescent)
                val releasedBeforeRunnerSettlement = assertNotNull(
                    harness.substrateFactory.get("run-test", "group-0")
                        .wipTaskDao()
                        .getByNodeId("node-worker", "run-test", "group-0")
                )
                assertEquals("unclaimed", releasedBeforeRunnerSettlement.status)
                assertEquals(1, releasedBeforeRunnerSettlement.abandonCount)

                releaseRunner.complete(Unit)
                withTimeout(2_000L) { agentDied.await() }
                awaitRealTimeValue {
                    manager.publicationAccounting().takeIf { it.isQuiescent }
                }

                assertEquals(1, leaseFinalizationCount.get())
                assertEquals(1, deathCallbackCount.get())
                assertEquals(0, manager.getLiveAgentCount())
                val releasedWip = assertNotNull(
                    harness.substrateFactory.get("run-test", "group-0")
                        .wipTaskDao()
                        .getByNodeId("node-worker", "run-test", "group-0")
                )
                assertEquals("unclaimed", releasedWip.status)
                assertEquals(1, releasedWip.abandonCount)

                delay(100L)

                assertEquals(1, repository.exits("run-test", "group-0").size)
                assertEquals(1, leaseFinalizationCount.get())
                assertEquals(1, deathCallbackCount.get())
            } finally {
                releaseRunner.complete(Unit)
                manager.terminateAll()
                agentScope.cancel()
            }
        }

    @Test
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun `cancellation race finalizes worker authority and death callback exactly once`() =
        kotlinx.coroutines.runBlocking {
            val releaseRunner = CompletableDeferred<Unit>()
            val runnerStarted = CompletableDeferred<Unit>()
            val finalizationCount = java.util.concurrent.atomic.AtomicInteger()
            val deathCallbackCount = java.util.concurrent.atomic.AtomicInteger()
            val harness = makeManagerHarness { _, _ ->
                runnerStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRunner.await()
                }
            }
            val manager = harness.manager
            seedWorkerWip(harness.substrateFactory)
            manager.configureWorkerAuthority { request ->
                assignedWorkerAuthority(
                    request = request,
                    finalizeLease = {
                        finalizationCount.incrementAndGet()
                    }
                )
            }
            manager.onAgentDied = { _, _ ->
                deathCallbackCount.incrementAndGet()
            }
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = agentScope
            )
            withTimeout(2_000L) { runnerStarted.await() }

            withTimeout(1_000L) {
                manager.terminateAll()
            }
            releaseRunner.complete(Unit)
            withTimeout(2_000L) {
                while (
                    deathCallbackCount.get() != 1 ||
                    !manager.publicationAccounting().isQuiescent
                ) {
                    delay(10L)
                }
            }

            assertEquals(1, finalizationCount.get())
            assertEquals(1, deathCallbackCount.get())
            agentScope.cancel()
        }

    @Test
    fun `concurrent kill requests preserve one frozen exit identity`() =
        kotlinx.coroutines.runBlocking {
            val releaseRunner = CompletableDeferred<Unit>()
            val runnerStarted = CompletableDeferred<Unit>()
            val harness = makeManagerHarness { _, _ ->
                runnerStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRunner.await()
                }
            }
            val manager = harness.manager
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { runnerStarted.await() }

                kotlinx.coroutines.coroutineScope {
                    repeat(32) {
                        launch(Dispatchers.Default) {
                            manager.terminateAll()
                        }
                    }
                }

                val repository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", "group-0")
                )
                assertEquals(1, repository.exits("run-test", "group-0").size)
                assertFalse(manager.publicationAccounting().isQuiescent)

                releaseRunner.complete(Unit)
                awaitRealTimeValue {
                    manager.publicationAccounting().takeIf { it.isQuiescent }
                }
                assertEquals(1, repository.exits("run-test", "group-0").size)
            } finally {
                releaseRunner.complete(Unit)
                manager.terminateAll()
                agentScope.cancel()
            }
        }

    @Test
    fun `kill request wins classification when execution completes concurrently`() =
        kotlinx.coroutines.runBlocking {
            val releaseRunner = CompletableDeferred<Unit>()
            val runnerStarted = CompletableDeferred<Unit>()
            val harness = makeManagerHarness { _, _ ->
                runnerStarted.complete(Unit)
                releaseRunner.await()
            }
            val manager = harness.manager
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope
                )
                withTimeout(2_000L) { runnerStarted.await() }

                val termination = launch(Dispatchers.Default) {
                    manager.terminateAll()
                }
                withTimeout(2_000L) {
                    manager.agentStates.first { states ->
                        states.values.singleOrNull()?.status == AgentStatus.DyingGracefully
                    }
                }
                releaseRunner.complete(Unit)
                termination.join()

                val repository = WorkerExecutionRepository(
                    harness.substrateFactory.get("run-test", "group-0")
                )
                val exit = awaitRealTimeValue {
                    repository.exits("run-test", "group-0").singleOrNull()
                }
                assertTrue(exit.wasKilled)
                awaitRealTimeValue {
                    manager.publicationAccounting().takeIf { it.isQuiescent }
                }
            } finally {
                releaseRunner.complete(Unit)
                manager.terminateAll()
                agentScope.cancel()
            }
        }

    @Test
    fun `restart projection reuses frozen exit and blocks until orphan execution settles`() =
        kotlinx.coroutines.runBlocking {
            val releaseRunner = CompletableDeferred<Unit>()
            val runnerStarted = CompletableDeferred<Unit>()
            val original = makeManagerHarness(budgetPollingIntervalMs = 60_000L) { _, _ ->
                runnerStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseRunner.await()
                }
            }
            val originalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                original.manager.spawnGeneration(
                    allocation = singleResearcherAllocation(),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = originalScope
                )
                withTimeout(2_000L) { runnerStarted.await() }
                original.manager.terminateAll()

                val repository = WorkerExecutionRepository(
                    original.substrateFactory.get("run-test", "group-0")
                )
                val frozenExit = repository.exits("run-test", "group-0").single()
                val restarted = makeManagerHarness(
                    existingSubstrateFactory = original.substrateFactory,
                    runner = { _, _ -> }
                ).manager

                val restartAccounting = restarted.publicationAccounting()
                assertFalse(restartAccounting.isQuiescent)
                assertEquals(
                    setOf(frozenExit.agentId.value),
                    restartAccounting.orphanedUnsettledAgentIds
                )
                assertEquals(
                    PersistExitResult.AlreadyPersisted(
                        AtlasHashing.hashAgentExitRecord(frozenExit)
                    ),
                    repository.recordExit(frozenExit)
                )

                releaseRunner.complete(Unit)
                awaitRealTimeValue {
                    original.manager.publicationAccounting().takeIf { it.isQuiescent }
                }
                assertTrue(restarted.publicationAccounting().isQuiescent)
                val metrics = restarted.lifecycleReconciliationMetrics()
                assertEquals(0, metrics.exitIdentityConflictCount)
                assertEquals(0, metrics.exitFinalizationRetryCount)
                assertEquals(0, metrics.registeredWithoutExitCount)
                assertEquals(0, metrics.exitWithoutRegisteredAgentCount)
                assertEquals(0, metrics.liveExecutionAfterArchiveBarrier)
                assertEquals(0, metrics.deathCallbackDuplicateCount)
                assertEquals(0, metrics.unsettledCostReservationCount)
                assertNotNull(metrics.maxExitToQuiescenceMs)
            } finally {
                releaseRunner.complete(Unit)
                original.manager.terminateAll()
                originalScope.cancel()
            }
        }

    @Test
    fun `terminateAll cancels active nested group jobs`() = runTest {
        val nestedStarted = CompletableDeferred<Unit>()
        val nestedCancelled = CompletableDeferred<Unit>()
        val manager = makeManager(
            runner = { _, _ -> },
            nestedRunner = {
                nestedStarted.complete(Unit)
                try {
                    delay(60_000)
                    NestedGroupResult(
                        nestedGroupId = it.nestedGroupId,
                        outerGroupId = it.outerGroupId,
                        generationsCompleted = 0,
                        kbDocumentIds = emptyList(),
                        kgFactIds = emptyList(),
                        promotedToolIds = emptyList(),
                        budgetConsumed = 0.0,
                        completedNormally = false
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    nestedCancelled.complete(Unit)
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        )

        val nestedJob = manager.spawnNestedGroup(
            config = NestedGroupConfig(
                nestedGroupId = "inner-1",
                outerGroupId = "group-0",
                outerRunId = "run-test",
                objective = "nested objective",
                budgetAllocation = 40.0,
                openRouterApiKey = "sk-test",
                agentJarPath = "/fake/agent.jar"
            ),
            scope = backgroundScope
        )

        nestedStarted.await()
        assertEquals(1, manager.getLiveNestedGroupCount())

        manager.terminateAll()
        nestedCancelled.await()
        awaitRealTimeValue<Int> { manager.getLiveNestedGroupCount().takeIf { it == 0 } }

        assertTrue(nestedJob.isCancelled, "Parent termination should cancel the nested group job")
        assertEquals(0, manager.getLiveNestedGroupCount())
    }

    @Test
    fun `spawnNestedGroup rejects duplicate nested group ids while active`() = runTest {
        val nestedStarted = CompletableDeferred<Unit>()
        val manager = makeManager(
            runner = { _, _ -> },
            nestedRunner = {
                nestedStarted.complete(Unit)
                delay(60_000)
                NestedGroupResult(
                    nestedGroupId = it.nestedGroupId,
                    outerGroupId = it.outerGroupId,
                    generationsCompleted = 0,
                    kbDocumentIds = emptyList(),
                    kgFactIds = emptyList(),
                    promotedToolIds = emptyList(),
                    budgetConsumed = 0.0,
                    completedNormally = true
                )
            }
        )

        manager.spawnNestedGroup(
            config = nestedConfig(),
            scope = backgroundScope
        )
        nestedStarted.await()

        val error = assertFailsWith<IllegalStateException> {
            manager.spawnNestedGroup(
                config = nestedConfig(),
                scope = backgroundScope
            )
        }

        assertTrue(error.message?.contains("already active") == true)
        manager.terminateAll()
        awaitRealTimeValue<Int> { manager.getLiveNestedGroupCount().takeIf { it == 0 } }
    }

    @Test
    fun `spawnNestedGroup validates outer identifiers`() = runTest {
        val manager = makeManager { _, _ -> }

        assertFailsWith<IllegalArgumentException> {
            manager.spawnNestedGroup(
                config = nestedConfig(outerGroupId = "group-1"),
                scope = backgroundScope
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.spawnNestedGroup(
                config = nestedConfig(outerRunId = "run-other"),
                scope = backgroundScope
            )
        }
    }

    @Test
    fun `spawnNestedGroup rolls nested artifacts into the outer substrate and cleans up state`() = runTest {
        val tmpDir = createTempDirectory("lm-nested-rollup-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { _, _ -> },
            nestedGroupRunBlock = { config ->
                val nestedRunId = "${config.outerRunId}-nested-${config.nestedGroupId}"
                val nestedDb = substrateFactory.get(nestedRunId, config.nestedGroupId)

                nestedDb.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "kb-1",
                        content = "Nested KB entry",
                        sourceAgentId = "nested-agent-kb",
                        generation = 1,
                        trustScore = 0.92,
                        citationCount = 3,
                        domain = "general",
                        status = "active",
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 1_000L
                    )
                )
                nestedDb.knowledgeGraphFactDao().insert(
                    KnowledgeGraphFactEntity(
                        id = "kg-1",
                        subject = "Nested Subject",
                        relation = "SUPPORTS",
                        objectNode = "Nested Object",
                        confidence = 0.86,
                        sourceAgentId = "nested-agent-kg",
                        generation = 1,
                        trustScore = 0.89,
                        provenanceJson = """["kb-1"]""",
                        status = "active",
                        speculative = false,
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 2_000L
                    )
                )
                nestedDb.toolRegistryDao().insert(
                    ToolRegistryEntryEntity(
                        id = "tool-1",
                        name = "nested_tool",
                        version = 1,
                        authorAgentId = "nested-agent-tool",
                        successRate = 1.0,
                        inputSchemaJson = "{}",
                        outputSchemaJson = "{}",
                        domain = "general",
                        status = "active",
                        code = "echo nested",
                        codeLanguage = "shell",
                        generation = 1,
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 3_000L
                    )
                )

                NestedGroupResult(
                    nestedGroupId = config.nestedGroupId,
                    outerGroupId = config.outerGroupId,
                    generationsCompleted = 2,
                    kbDocumentIds = listOf("kb-1"),
                    kgFactIds = listOf("kg-1"),
                    promotedToolIds = listOf("tool-1"),
                    budgetConsumed = 12.5,
                    completedNormally = true
                )
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )
        val outerDb = substrateFactory.get("run-test", "group-0")
        outerDb.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "outer-tool-1",
                name = "nested_tool",
                version = 1,
                authorAgentId = "outer-agent-tool",
                successRate = 0.8,
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "general",
                status = "active",
                code = "echo outer",
                codeLanguage = "shell",
                generation = 0,
                groupId = "group-0",
                runId = "run-test",
                createdAtMs = 500L
            )
        )

        val completed = CompletableDeferred<NestedGroupResult>()
        val nestedJob = manager.spawnNestedGroup(
            config = nestedConfig(),
            scope = backgroundScope,
            onCompleted = { completed.complete(it) }
        )

        val result = completed.await()
        nestedJob.join()

        assertEquals(0, manager.getLiveNestedGroupCount())
        assertEquals(1, result.importedKbDocumentIds.size)
        assertEquals(1, result.importedKgFactIds.size)
        assertEquals(1, result.importedToolIds.size)

        val importedKb = outerDb.knowledgeBaseDao().getById(result.importedKbDocumentIds.single())
        assertNotNull(importedKb)
        assertEquals("run-test", importedKb.runId)
        assertEquals("group-0", importedKb.groupId)
        assertEquals("Nested KB entry", importedKb.content)
        assertNull(importedKb.previousVersionId)

        val importedKg = outerDb.knowledgeGraphFactDao().getById(result.importedKgFactIds.single())
        assertNotNull(importedKg)
        assertEquals("run-test", importedKg.runId)
        assertEquals("group-0", importedKg.groupId)
        assertTrue(importedKg.provenanceJson.contains("nested://run-test-nested-inner-1/inner-1/kg/kg-1"))

        val importedTool = outerDb.toolRegistryDao().getById(result.importedToolIds.single())
        assertNotNull(importedTool)
        assertEquals("run-test", importedTool.runId)
        assertEquals("group-0", importedTool.groupId)
        assertEquals(2, importedTool.version)
        assertEquals("outer-tool-1", importedTool.previousVersionId)
        val materializedTool = substrateFactory.dbFilePath("run-test", "group-0")
            .parentFile!!
            .resolve("tools/${importedTool.name}-v${importedTool.version}")
        assertTrue(materializedTool.canExecute(), "Imported nested tool must be materialized for execute_tool reuse")

        val rollupEvent = outerDb.eventLogDao()
            .getLatestByType("run-test", "group-0", EventTypes.NESTED_GROUP_ROLLUP, 1)
            .single()
        assertTrue(rollupEvent.payloadJson.contains("\"nestedGroupId\":\"inner-1\""))
        assertTrue(rollupEvent.payloadJson.contains("\"kbImported\":1"))
    }
}
