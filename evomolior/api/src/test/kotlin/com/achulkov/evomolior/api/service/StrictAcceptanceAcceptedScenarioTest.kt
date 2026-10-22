@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreDraft
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRepairDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.agents.tools.AssignedToolSubmissionAuthority
import com.achulkov.evomolior.agents.tools.CommitContributionTool
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.agents.tools.ToolAuthoringRuntime
import com.achulkov.evomolior.agents.tools.ToolAuthoringWorkspaceTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.ExactGraderBundleResolver
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitmentAuthority
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.DisclosureStageDraft
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.SealedTestDraft
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunClosureMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.lifecycle.AgentTerminationCause
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidationResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidator
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.tooltransport.ToolCandidateWireCompiler
import com.achulkov.evomolior.orchestration.tooltransport.ToolContractWorkspaceCompiler
import com.achulkov.evomolior.orchestration.tooltransport.ToolContractWorkspaceMaterializer
import com.achulkov.evomolior.orchestration.tooltransport.ToolOpportunityBindingCatalogCompiler
import com.achulkov.evomolior.orchestration.tooltransport.ToolOpportunityLeaseAuthority
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.deliverable.DeliverablePersistenceRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Deterministically exercises the accepted six-round production graph without a provider call.
 *
 * Scripted actors provide only bounded model-facing responses; lifecycle, critic, grader,
 * capture, publication, archive, and persistence authorities remain the production ones.
 */
class StrictAcceptanceAcceptedScenarioTest {
    @Test
    fun `six round production path publishes a verified accepted outcome`() = runBlocking {
        val scenario = StrictAcceptanceScenarioV1(
            scenarioId = "strict-accepted-production-v1",
            injectLifecycleRace = true,
            firstToolFailure = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
            requireLaterAgentUse = true,
            requireSimulationInfluence = true,
            terminalPolicy = ScenarioTerminalPolicy.ACCEPTED,
        )
        val runId = scenario.scenarioId
        val workerGroupKeys = listOf("group-0", "group-1")
        val workerGroupIds = workerGroupKeys.map { key ->
            CouncilDeterministicIds.groupId(runId, "worker", key)
        }
        // The initial non-replicated origin node is allocated to the lexicographically first
        // deterministic group ID, independent of the Council's semantic group-key order.
        val toolGroupId = workerGroupIds.min()
        val graderGroupIds = (0 until scenario.graderGroups).map { index ->
            CouncilDeterministicIds.groupId(runId, "grader", "grader-$index")
        }
        val outputMarkers = (0 until 8).map { "strict-accepted-marker-$it" }
        val originNodeId = NodeId(
            CouncilDeterministicIds.nodeId(runId, LocalNodeKey(ORIGIN_KEY)),
        )
        val reuseNodeId = NodeId(
            CouncilDeterministicIds.nodeId(runId, LocalNodeKey(REUSE_KEY)),
        )
        val validationNodeId = NodeId(
            CouncilDeterministicIds.nodeId(runId, LocalNodeKey(VALIDATION_KEY)),
        )
        val raceStarted = AtomicBoolean(false)
        val raceBudgetDepleted = AtomicBoolean(false)
        val raceBudgetPublishedBeforeExit = AtomicBoolean(false)
        val raceAgentId = AtomicReference<String?>(null)
        val raceLedger = AtomicReference<BudgetLedger?>(null)
        val raceTtlTerminationEntered = CompletableDeferred<Unit>()
        val raceBudgetPublished = CompletableDeferred<Unit>()
        val firstToolAuthorId = AtomicReference<String?>(null)
        val repairedToolAuthorId = AtomicReference<String?>(null)
        val toolAuthoringPhase = AtomicReference("not-started")
        val validationAgentId = AtomicReference<String?>(null)
        val validationExecutionId = AtomicReference<String?>(null)
        val reuseAgentId = AtomicReference<String?>(null)
        val reuseExecutionId = AtomicReference<String?>(null)
        val localRunnerFailure = AtomicReference<Throwable?>(null)
        val lifecycleManagers = ConcurrentHashMap<String, LifecycleManager>()
        val runRoot = createTempDirectory("strict-accepted-production-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(runRoot)
        val distributionManifestHash = AtlasHashing.hashText("strict-accepted-distribution")
        val admission = AcceptanceAdmissionArtifact(
            runId = runId,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            admittedAtUtc = "2026-07-26T00:00:00Z",
            ownerPid = 1L,
            distributionManifestSha256 = distributionManifestHash,
            sourceRevision = "strict-accepted-fixture",
            configurationSha256 = AtlasHashing.hashText("strict-accepted-config"),
            modelAssignmentsSha256 = AtlasHashing.hashText("strict-accepted-models"),
            jvmVersion = System.getProperty("java.runtime.version"),
            javaHome = "/test/jdk",
            javaExecutableSha256 = AtlasHashing.hashText("strict-accepted-java"),
            javaReleaseSha256 = AtlasHashing.hashText("strict-accepted-java-release"),
            javaModulesSha256 = AtlasHashing.hashText("strict-accepted-java-modules"),
            codexCliPath = "/test/codex",
            codexCliSha256 = AtlasHashing.hashText("strict-accepted-codex"),
            codexCliVersion = "test",
            estimatedMaximumRunArchiveBytes = 1L,
            requiredFreeBytes = 1L,
            checks = listOf(AcceptanceAdmissionCheck("fixture", "provider-free acceptance")),
        )
        substrateFactory.runControlDir(runId).mkdirs()
        File(
            substrateFactory.runControlDir(runId),
            AcceptanceAdmissionArtifact.FILE_NAME,
        ).writeText(
            Json.encodeToString(AcceptanceAdmissionArtifact.serializer(), admission)
        )
        val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
        val json = Json
        val componentNodes = listOf(
            LocalNodeKey(ORIGIN_KEY) to NodeKind.SIMULATION,
            LocalNodeKey(DELIVERY_KEY) to NodeKind.DELIVERY,
            LocalNodeKey("receiver-decision") to NodeKind.DECISION,
            LocalNodeKey("receiver-evidence") to NodeKind.EVIDENCE,
            LocalNodeKey("receiver-hypothesis") to NodeKind.HYPOTHESIS,
            LocalNodeKey(VALIDATION_KEY) to NodeKind.VALIDATION,
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { activeRunId, groupId, _, _, groupProfile ->
                LifecycleManager(
                    runId = activeRunId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "/tmp/no-provider",
                    groupProfile = groupProfile,
                    budgetPollingIntervalMs = 1L,
                    terminationCommitBarrier = { agentId, cause ->
                        if (agentId == raceAgentId.get()) {
                            assertEquals(AgentTerminationCause.TTL_EXPIRED, cause)
                            raceTtlTerminationEntered.complete(Unit)
                            withTimeout(5_000L) { raceBudgetPublished.await() }
                            assertTrue(assertNotNull(raceLedger.get()).isAtFloor)
                            assertTrue(
                                WorkerExecutionRepository(
                                    substrateFactory.get(activeRunId, groupId),
                                ).exits(activeRunId, groupId)
                                    .none { it.agentId.value == agentId },
                                "The race barrier must publish budget before exit freezing.",
                            )
                            raceBudgetPublishedBeforeExit.set(true)
                        }
                    },
                    localAgentRunner = { bootContext, registerLedger ->
                        val ledger = BudgetLedger(
                            agentId = bootContext.agentId,
                            initialBudget = bootContext.allocatedBudget,
                            floor = 1.0,
                            config = BudgetConfig(
                                bootContext.groupId,
                                com.achulkov.evomolior.core.model.GroupProfile.default(groupProfile.profileType),
                            ),
                        )
                        registerLedger?.invoke(ledger)
                        val workerView = bootContext.workerView
                        val assignedNode = workerView?.assignedNode
                        val toolSkeleton = workerView?.assignedToolSkeleton
                        val reuseCard = workerView?.availableToolCards
                            .orEmpty()
                            .singleOrNull { it.requiredForNodeId == reuseNodeId }
                        val validationCard = workerView?.availableToolCards
                            .orEmpty()
                            .singleOrNull { it.requiredForNodeId == validationNodeId }
                        runCatching {
                            when {
                                bootContext.role == Role.Archivist &&
                                    bootContext.rolePrompt.contains(
                                        "Round Submission Summariser",
                                    ) -> seedAcceptedWorkerEvidence(
                                    substrateFactory,
                                    bootContext,
                                    reuseExecutionId.get(),
                                    validationExecutionId.get(),
                                )

                                bootContext.role == Role.Builder &&
                                    bootContext.rolePrompt.contains(
                                        "Final Deliverable Writer",
                                    ) -> seedAcceptedWorkerEvidence(
                                    substrateFactory,
                                    bootContext,
                                    reuseExecutionId.get(),
                                    validationExecutionId.get(),
                                )

                                bootContext.groupId == toolGroupId &&
                                    toolSkeleton != null -> authorPowerToEnergyCandidate(
                                    runRoot = runRoot,
                                    substrateFactory = substrateFactory,
                                    bootContext = bootContext,
                                    ledger = ledger,
                                    firstToolAuthorId = firstToolAuthorId,
                                    repairedToolAuthorId = repairedToolAuthorId,
                                    phase = toolAuthoringPhase,
                                )

                                toolSkeleton != null -> seedAcceptedWorkerEvidence(
                                    substrateFactory,
                                    bootContext,
                                    reuseExecutionId.get(),
                                    validationExecutionId.get(),
                                )

                                bootContext.groupId == toolGroupId &&
                                    assignedNode?.nodeId == validationNodeId &&
                                    validationCard != null -> executePowerToEnergyValidation(
                                    runRoot = runRoot,
                                    substrateFactory = substrateFactory,
                                    bootContext = bootContext,
                                    ledger = ledger,
                                    card = validationCard,
                                    validationAgentId = validationAgentId,
                                    validationExecutionId = validationExecutionId,
                                )

                                bootContext.groupId == toolGroupId &&
                                    assignedNode?.nodeId == reuseNodeId &&
                                    reuseCard != null -> executePowerToEnergyReuse(
                                    runRoot = runRoot,
                                    substrateFactory = substrateFactory,
                                    bootContext = bootContext,
                                    ledger = ledger,
                                    card = reuseCard,
                                    reuseAgentId = reuseAgentId,
                                    reuseExecutionId = reuseExecutionId,
                                )

                                assignedNode?.nodeId == reuseNodeId -> commitNoContribution(
                                    substrateFactory,
                                    bootContext,
                                    ledger,
                                    "No exact promoted AGENT_USE card is visible for this reuse node.",
                                )

                                scenario.injectLifecycleRace &&
                                    assignedNode != null &&
                                    raceStarted.compareAndSet(false, true) -> {
                                    raceAgentId.set(bootContext.agentId)
                                    raceLedger.set(ledger)
                                    withTimeout(15_000L) {
                                        raceTtlTerminationEntered.await()
                                    }
                                    ledger.deductRaw(
                                        ledger.initialBudget,
                                        "strict-ttl-budget-race",
                                    )
                                    raceBudgetDepleted.set(true)
                                    raceBudgetPublished.complete(Unit)
                                    awaitCancellation()
                                }

                                else -> seedAcceptedWorkerEvidence(
                                    substrateFactory,
                                    bootContext,
                                    reuseExecutionId.get(),
                                    validationExecutionId.get(),
                                )
                            }
                        }.onFailure { failure ->
                            if (failure !is kotlinx.coroutines.CancellationException) {
                                localRunnerFailure.compareAndSet(null, failure)
                            }
                        }.getOrThrow()
                    },
                ).also { lifecycleManagers[groupId] = it }
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            ordinaryAgentTtlSecondsOverride = 10L,
            workerCriticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 1.0,
                )
            },
            workerToolSemanticReviewRunner = { submission, _, _ ->
                assertEquals("TOOL", submission.type)
                ToolSemanticReview(
                    verdict = ToolSemanticVerdict.PROMOTE,
                    publicFindings = emptyList(),
                    safeRepairCodes = emptySet(),
                    privateRationaleCommitment = "a".repeat(64),
                )
            },
            genesisCouncilRunner = {
                    callbackRunId,
                    objective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted,
                ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = workerGroupKeys,
                    workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                    generalTests = outputMarkers.mapIndexed { index, marker ->
                        val (nodeKey, nodeKind) = componentNodes[index % componentNodes.size]
                        CompiledCouncilGeneralTestFixture(
                            testId = "strict-accepted-general-$index",
                            nodeKey = nodeKey,
                            nodeKind = nodeKind,
                            acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                            expectedText = marker,
                        )
                    },
                    outputTransform = { output ->
                        acceptedTwoStageTopology(output, callbackRunId)
                    },
                )
            },
            graderCriteriaRunner = {
                    criteriaRunId,
                    _,
                    actualGraderGroupIds,
                    roundIndex,
                    _,
                    scoringDimensions,
                    _,
                ->
                assertEquals(graderGroupIds, actualGraderGroupIds)
                persistPassingGraderMethodology(
                    substrateFactory = substrateFactory,
                    runId = criteriaRunId,
                    graderGroupIds = actualGraderGroupIds,
                    roundIndex = roundIndex,
                    scoringDimensions = scoringDimensions,
                    json = json,
                )
            },
            graderCriteriaReviewRunner = { _, _, _, _, _ -> },
            graderJudgingRunner = {
                    judgingRunId,
                    _,
                    actualGraderGroupIds,
                    actualWorkerGroupIds,
                    roundIndex,
                    objective,
                    scoringDimensions,
                    graderGroupManager,
                    nodeAssignments,
                    onNodeGradeBundles,
                    onProgress,
                ->
                assertEquals(graderGroupIds, actualGraderGroupIds)
                assertEquals(workerGroupIds, actualWorkerGroupIds)
                GraderRunner(
                    openRouterApiKey = "no-provider",
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    substrateFactory = substrateFactory,
                    localAgentRunner = { "" },
                    graderAgentTimeoutMs = 1_000L,
                    graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    structuredDimensionScoreAuthor = { request ->
                        json.encodeToString(
                            StructuredDimensionScoreRepairDraft.serializer(),
                            StructuredDimensionScoreRepairDraft(
                                scores = request.missingDimensions.map { dimension ->
                                    StructuredDimensionScoreDraft(
                                        dimensionId = dimension.id,
                                        rawScore = if (request.targetGroupId == toolGroupId) {
                                            dimension.maxScore
                                        } else {
                                            dimension.maxScore * 0.8
                                        },
                                        rationale = "Deterministic accepted-scenario score.",
                                    )
                                },
                            ),
                        )
                    },
                    structuredNodeGradeAuthor = {
                        json.encodeToString(
                            StructuredNodeGradeDraft.serializer(),
                            StructuredNodeGradeDraft(
                                verdict = StructuredNodeGradeVerdict.VALID,
                                scoreVector = NodeScoreVector(
                                    correctness = 0.95,
                                    evidenceQuality = 0.95,
                                    reproducibility = 0.95,
                                    robustness = 0.9,
                                    integrationUtility = 0.95,
                                    receiverRelevance = 0.95,
                                    novelty = 0.8,
                                    efficiency = 0.9,
                                    hardFailure = false,
                                    confidence = 0.95,
                                ),
                                rationale = "Sealed evidence is sufficient for capture.",
                            ),
                        )
                    },
                ).runJudgingPhase(
                    runId = judgingRunId,
                    graderGroupIds = actualGraderGroupIds,
                    competingGroupIds = actualWorkerGroupIds,
                    roundIndex = roundIndex,
                    runObjective = objective,
                    scoringDimensions = scoringDimensions,
                    graderGroupManager = graderGroupManager,
                    nodeGradingAssignments = nodeAssignments,
                    onNodeGradeBundles = onNodeGradeBundles,
                    onProgress = onProgress,
                )
            },
            metaPlannerRunner = MetaPlannerRunner(),
            archiveService = RunArchiveService(substrateFactory),
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ -> },
            distributionManifestHashProvider = { distributionManifestHash },
            roundDurationMillisOverride = 11_500L,
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L,
        )

        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event -> events += event }
        }
        val terminalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(90_000L) {
                service.events.first { event ->
                    event.runIdOrNull() == runId && event.isTerminal()
                }
            }
        }

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Produce an accepted power-to-energy receiver decision.",
                groupCount = scenario.workerGroups,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = scenario.rounds,
                roundDurationMinutes = 0,
                // Preserve an ordinary node-bound worker beside the Critic and artifact owner.
                maxAgentsPerGroup = 3,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "quality",
                        name = "Quality",
                        description = "Receiver-facing quality.",
                        weight = 1.0,
                    ),
                ),
                openRouterApiKey = "/tmp/no-provider",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
                acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "receiver/result.md",
                    mediaType = "text/markdown",
                    validation = FinalOutputValidationContract(
                        requiredMarkdownSectionSignals = DELIVERABLE_COMPONENT_IDS,
                    ),
                ),
            ),
        )

        val completed = terminalEvent.await()
        eventCollector.cancel()
        val debugToolDatabase = substrateFactory.get(runId, toolGroupId)
        val debugToolSubmissions = debugToolDatabase.stagingDao()
            .getAll(runId, toolGroupId)
            .filter { it.type == SubmissionType.TOOL.name }
            .map { "${it.generation}:${it.status}:${it.criticAgentId}" }
        val debugRepairRepository = ToolRepairPersistenceRepository(debugToolDatabase)
        val debugRepairAttempts = debugRepairRepository.attempts(POWER_OPPORTUNITY_ID)
        val debugVisibleDiagnostics = (0..scenario.rounds).associateWith { round ->
            debugRepairRepository.visibleDiagnostics(
                runId,
                toolGroupId,
                POWER_OPPORTUNITY_ID,
                round,
            ).map { it.code }
        }
        val debugSnapshot = DeliverablePersistenceRepository(substrateFactory.getAtlas(runId))
            .snapshots(runId, toolGroupId)
            .lastOrNull()
        val debugComponentAuthorities = debugSnapshot?.let { snapshot ->
            buildMap {
                snapshot.acceptedComponentIds.forEach { put(it, "ACCEPTED") }
                snapshot.provisionalComponentIds.forEach { put(it, "PROVISIONAL") }
                snapshot.withheldComponentIds.forEach { put(it, "WITHHELD") }
            }.toSortedMap()
        }
        assertTrue(
            completed is EvoMoliorEvent.RunCompleted,
            "Unexpected terminal: $completed\n" +
                "Local runner failure: ${localRunnerFailure.get()?.stackTraceToString()}\n" +
                "Tool authoring phase: ${toolAuthoringPhase.get()}; " +
                "first=${firstToolAuthorId.get()}; repaired=${repairedToolAuthorId.get()}\n" +
                "Validation execution: ${validationExecutionId.get()}; " +
                "reuse execution: ${reuseExecutionId.get()}\n" +
                "Tool submissions: $debugToolSubmissions\n" +
                "Repair attempts: $debugRepairAttempts\n" +
                "Visible diagnostics: $debugVisibleDiagnostics\n" +
                "Latest snapshot: round=${debugSnapshot?.round}; " +
                "authorities=$debugComponentAuthorities; " +
                "failedGates=${debugSnapshot?.failedGateIds}; " +
                "missingAuthority=${debugSnapshot?.missingAuthorityComponentIds}; " +
                "evidenceRefs=${debugSnapshot?.evidenceRefs}\n" +
                events.filterIsInstance<EvoMoliorEvent.EngineProgress>()
                    .joinToString("\n") { "${it.phase}: ${it.message}" },
        )
        assertFalse(events.any { it is EvoMoliorEvent.RunRecovered })
        assertFalse(events.any { it is EvoMoliorEvent.RunAborted })

        val completedGraders = events.filterIsInstance<EvoMoliorEvent.GraderRoundCompleted>()
            .filter { it.runId == runId }
        val expectedCompletions = (0 until scenario.rounds).flatMap { round ->
            graderGroupIds.map { graderGroupId -> round to graderGroupId }
        }.toSet()
        assertEquals(
            expectedCompletions,
            completedGraders.map { it.roundIndex to it.graderGroupId }.toSet(),
        )
        assertEquals(expectedCompletions.size, completedGraders.size)

        val graderRepository = GraderAuthorityRepository(substrateFactory)
        val expectedTargets = workerGroupIds.mapTo(linkedSetOf(), ::AtlasGroupId)
        (0 until scenario.rounds).forEach { round ->
            val bundles = graderRepository.scoreBundlesForRound(runId, round)
            assertEquals(scenario.graderGroups * scenario.workerGroups, bundles.size)
            assertTrue(bundles.all { bundle ->
                bundle.status == GraderScoreBundleStatus.VALID &&
                    bundle.hasValidHash() &&
                    bundle.nodeScores.values.all { !it.hardFailure }
            })
            assertEquals(
                ExactGraderRoundStatus.VALID,
                ExactGraderBundleResolver().resolve(
                    runId = runId,
                    round = round,
                    expectedGraderGroupIds = graderGroupIds.toSet(),
                    expectedTargetGroupIds = expectedTargets,
                    bundles = bundles,
                ).status,
            )
        }
        val nodeBundles = NodeCaptureRepository(substrateFactory).gradeBundlesForRun(runId)
        assertTrue(nodeBundles.any { it.verdict == NodeGradeBundleVerdict.VALID_PASS })
        assertTrue(nodeBundles.all { bundle ->
            bundle.requiredQuorum == scenario.graderGroups &&
                bundle.graderIds == graderGroupIds.toSet() &&
                bundle.completedGraderIds == graderGroupIds.toSet()
        })

        val summaries = service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_ROUND_SUMMARY)
            .filter { it.groupId != null }
        assertEquals(scenario.rounds * scenario.workerGroups, summaries.size)
        val toolGroupSummaries = summaries.filter { it.groupId == toolGroupId }
        assertTrue(
            toolGroupSummaries.any {
                it.generation == 0 &&
                    it.contentJson.contains("\"mandatoryFailure\":true")
            },
            "The first private promotion failure must remain visible in round authority.",
        )
        assertTrue(
            toolGroupSummaries.any {
                it.generation > 0 &&
                    it.contentJson.contains("\"mandatoryFailure\":false")
            },
            "A later repaired tool round must satisfy the complete sealed suite.",
        )

        val atlasDatabase = substrateFactory.getAtlas(runId)
        val publication = PublicationStateRepository(atlasDatabase)
        assertTrue(publication.finalCandidates(runId).isNotEmpty())
        assertEquals(
            com.achulkov.evomolior.core.contract.PublicationState.ARCHIVED,
            publication.history(runId).last().state,
        )
        assertTrue(PublicationOutcomeRepository(atlasDatabase).acceptedHistory(runId).isNotEmpty())
        assertTrue(service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isNotEmpty())

        val archive = assertNotNull(service.getResultsBundle(runId))
        val manifest = RunManifestVerifier.verify(archive, expectedRunId = runId)
        assertTrue(manifest.isValid, manifest.errors.joinToString())
        val acceptedHistory = RunOutcomeArchiveService().acceptedPublicationHistory(archive)
        assertEquals(com.achulkov.evomolior.core.contract.PublicationState.ARCHIVED, acceptedHistory.last().state)
        val archiveEntries = unzipEntries(archive)
        assertTrue("accepted/run-metrics-v1.json" in archiveEntries)
        val runMetrics = json.decodeFromString(
            RunMetricsV1.serializer(),
            archiveEntries.getValue("accepted/run-metrics-v1.json"),
        )
        assertEquals(
            RunMetricCatalog.orderedMetricIds,
            runMetrics.observations.map { it.metricId },
        )
        assertEquals(
            RunClosureMetricCatalog.orderedMetricIds,
            runMetrics.closureObservations.map { it.metricId },
        )
        assertTrue(
            (runMetrics.observations + runMetrics.closureObservations)
                .all { it.evidenceRefs.isNotEmpty() },
        )
        val closureMetrics = runMetrics.closureObservations.associateBy { it.metricId }
        listOf("exit_identity_conflicts", "exit_finalization_retries").forEach { metricId ->
            val observation = assertNotNull(closureMetrics[metricId])
            assertEquals(MetricAvailability.AVAILABLE, observation.status)
            assertEquals(0.0, observation.value)
        }
        AppendixBFullPublicationPolicy.requireAccepted(runMetrics)
        assertTrue(archiveEntries.keys.any { it.contains("final-reference") })
        assertTrue(outputMarkers.all { marker -> archiveEntries.values.any { marker in it } })
        val exactReuseExecutionId = assertNotNull(reuseExecutionId.get())
        val exactValidationExecutionId = assertNotNull(validationExecutionId.get())
        assertTrue(archiveEntries.values.any { exactReuseExecutionId in it })
        assertTrue(archiveEntries.values.any { exactValidationExecutionId in it })
        assertFalse(archiveEntries.values.any { PRIVATE_FIXTURE_ID in it })
        assertFalse(archiveEntries.values.any { "\"power_watts\":1733.0" in it })
        assertFalse(archiveEntries.values.any { "\"energy_kwh\":4.3325" in it })

        val toolDatabase = substrateFactory.get(runId, toolGroupId)
        val toolSubmissions = toolDatabase.stagingDao()
            .getAll(runId, toolGroupId)
            .filter { it.type == SubmissionType.TOOL.name }
            .sortedBy { it.generation }
        assertEquals(2, toolSubmissions.size)
        assertEquals("rejected", toolSubmissions.first().status)
        assertEquals("promoted", toolSubmissions.last().status)
        assertFalse(toolSubmissions.any { PRIVATE_FIXTURE_ID in it.content })
        assertFalse(toolSubmissions.any { "1733" in it.content || "4.3325" in it.content })

        val repairRepository = ToolRepairPersistenceRepository(toolDatabase)
        val repairAttempts = repairRepository.attempts(POWER_OPPORTUNITY_ID)
        assertEquals(2, repairAttempts.size)
        assertEquals(
            ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY,
            repairAttempts.first().promotionResult,
        )
        assertEquals(ToolPromotionStatus.PROMOTED, repairAttempts.last().promotionResult)
        val repairApproach = assertNotNull(repairAttempts.last().approachVector)
        assertTrue(repairApproach.hasValidHash())
        assertEquals(toolGroupId, repairApproach.groupId)
        assertEquals(1, repairApproach.round)
        val consumedDiagnosticId = repairAttempts.last().consumedDiagnosticIds.single()
        val repairDiagnostic = assertNotNull(
            repairRepository.diagnostic(consumedDiagnosticId),
        )
        assertEquals(scenario.firstToolFailure, repairDiagnostic.code)
        assertEquals(0, repairDiagnostic.createdRound)
        assertEquals(1, repairDiagnostic.visibleFromRound)
        assertEquals(
            firstToolAuthorId.get(),
            repairAttempts.first().authorAgentId,
        )
        assertEquals(
            repairedToolAuthorId.get(),
            repairAttempts.last().authorAgentId,
        )
        assertTrue(firstToolAuthorId.get() != repairedToolAuthorId.get())
        assertEquals(
            repairAttempts,
            ToolRepairPersistenceRepository(substrateFactory.getAtlas(runId))
                .attempts(POWER_OPPORTUNITY_ID),
        )

        val transportLedger = ToolTransportAttemptStore(substrateFactory).read(runId)
        assertEquals(2, transportLedger.attempts.size)
        assertTrue(transportLedger.attempts.all {
            it.outcome == ToolTransportAttemptOutcome.COMPILED
        })
        val registry = toolDatabase.toolRegistryDao()
            .getActive(runId, toolGroupId, null)
            .single()
        val versionId = ToolVersionId(registry.id)
        val toolRepository = ToolInfrastructureRepository(toolDatabase)
        val validationExecution = assertNotNull(
            toolRepository.execution(exactValidationExecutionId),
        )
        val execution = assertNotNull(toolRepository.execution(exactReuseExecutionId))
        assertEquals(ToolExecutionPurpose.VALIDATION_TEST, validationExecution.purpose)
        assertEquals(validationNodeId, validationExecution.nodeId)
        assertEquals(
            assertNotNull(validationAgentId.get()),
            validationExecution.agentId.value,
        )
        assertTrue(validationExecution.agentId.value != registry.authorAgentId)
        assertTrue(validationExecution.round > registry.generation)
        assertEquals(ToolExecutionPurpose.AGENT_USE, execution.purpose)
        assertEquals(reuseNodeId, execution.nodeId)
        assertEquals(assertNotNull(reuseAgentId.get()), execution.agentId.value)
        assertTrue(execution.agentId.value != registry.authorAgentId)
        assertTrue(
            execution.round > validationExecution.round,
            "AGENT_USE must occur in a later round than independent VALIDATION_TEST.",
        )
        assertTrue(execution.executionId != validationExecution.executionId)
        val simulation = toolRepository.simulationsForRun(runId)
            .single { it.executionId == exactReuseExecutionId }
        assertEquals(execution.agentId, simulation.executedBy)
        val reusePacket = NodeCaptureRepository(substrateFactory)
            .evidencePacketsForNode(runId, reuseNodeId)
            .single { exactReuseExecutionId in it.toolRunIds }
        assertEquals(
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
            toolRepository.lifecycle(versionId).last().state,
        )
        val reuse = toolRepository.reuseForVersion(versionId)
            .single { it.executionId == exactReuseExecutionId }
        assertEquals(reusePacket.packetHash, reuse.nodeEvidencePacketHash)
        assertEquals(reuseNodeId, reuse.reusedOnNodeId)
        val fitness = toolRepository.fitnessForVersion(versionId)
        assertTrue(fitness.isNotEmpty())
        assertTrue(fitness.last().validationPasses >= 1)
        assertTrue(
            NodeCaptureRepository(substrateFactory)
                .captureDecisionsForNode(runId, reuseNodeId)
                .isNotEmpty(),
        )
        assertEquals(originNodeId, toolRepository.lifecycle(versionId).first().originNodeId)

        val exits = workerGroupIds.flatMap { groupId ->
            WorkerExecutionRepository(substrateFactory.get(runId, groupId)).exits(runId, groupId)
        }
        assertTrue(exits.isNotEmpty())
        assertEquals(exits.map { it.exitId }.distinct().size, exits.size)
        assertTrue(raceStarted.get(), "The TTL/budget race worker was never started.")
        assertTrue(
            raceBudgetDepleted.get(),
            "The targeted worker did not reach the competing budget-floor condition.",
        )
        assertTrue(
            raceBudgetPublishedBeforeExit.get(),
            "Budget publication did not overlap the owned TTL termination window.",
        )
        assertEquals(
            1,
            exits.count { it.agentId.value == assertNotNull(raceAgentId.get()) },
            "The TTL/budget race must persist exactly one exit identity.",
        )
        val raceExit = exits.single { it.agentId.value == raceAgentId.get() }
        assertTrue(raceExit.wasKilled, "The TTL/budget race must terminate the targeted worker.")
        val raceCheckpoints = WorkerExecutionRepository(
            substrateFactory.get(runId, raceExit.groupId.value),
        ).checkpointsForAgent(runId, raceExit.groupId.value, raceExit.agentId.value)
        assertTrue(
            raceCheckpoints.isNotEmpty(),
            "The targeted worker must persist TTL checkpoint evidence before termination.",
        )
        assertEquals(workerGroupIds.toSet(), lifecycleManagers.keys)
        for ((groupId, manager) in lifecycleManagers) {
            assertTrue(manager.publicationAccounting().isQuiescent, "$groupId is not quiescent.")
            val metrics = manager.lifecycleReconciliationMetrics()
            assertEquals(0, metrics.exitIdentityConflictCount)
            assertEquals(0, metrics.exitFinalizationRetryCount)
            assertEquals(0, metrics.registeredWithoutExitCount)
            assertEquals(0, metrics.exitWithoutRegisteredAgentCount)
            assertEquals(0, metrics.liveExecutionAfterArchiveBarrier)
            assertEquals(0, metrics.unsettledCostReservationCount)
        }

    }

    private fun acceptedTwoStageTopology(
        base: CouncilRoleOutputBundle,
        runId: String,
    ): CouncilRoleOutputBundle {
        val origin = base.taskAnatomist.nodes.first { it.key.value == ORIGIN_KEY }
        val validation = base.taskAnatomist.nodes.first {
            it.key.value == VALIDATION_KEY
        }
        val reuse = base.taskAnatomist.nodes.first { it.key.value == REUSE_KEY }
        val originStage = origin.copy(
            stageKey = "stage-zero",
            toolOpportunity = powerToEnergyOpportunity(runId, reuse.key),
        )
        val validationStage = validation.copy(
            parentKeys = setOf(origin.key),
            prerequisiteKeys = setOf(origin.key),
            proofContract = validation.proofContract.copy(
                method = com.achulkov.evomolior.core.atlas.ProofMethod.REPRODUCTION,
            ),
            stageKey = "stage-one",
            revealMode = com.achulkov.evomolior.core.atlas.RevealMode.STAGE_PASS,
        )
        val reuseStage = reuse.copy(
            parentKeys = setOf(validation.key),
            prerequisiteKeys = setOf(validation.key),
            stageKey = "stage-one",
            revealMode =
                com.achulkov.evomolior.core.atlas.RevealMode.PREREQUISITES_COMPLETE,
        )
        val laterNodes = base.taskAnatomist.nodes
            .filter { it.key !in setOf(origin.key, validation.key, reuse.key) }
            .map {
                it.copy(
                    stageKey = "stage-one",
                    revealMode =
                        com.achulkov.evomolior.core.atlas.RevealMode.STAGE_PASS,
                )
            } + validationStage + reuseStage
        val transformedNodes = listOf(originStage) + laterNodes
        val privateFixtureDefinition = """
            {
              "input":{"power_watts":1733.0,"duration_hours":2.5},
              "expectedOutput":{
                "energy_kwh":4.3325,
                "result_summary":"Converted power to energy.",
                "uncertainty":"Deterministic arithmetic; input uncertainty is unchanged."
              }
            }
        """.trimIndent()
        return base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = transformedNodes,
                rootNodeKeys = base.taskAnatomist.rootNodeKeys -
                    setOf(validation.key, reuse.key),
                atomicityReport = transformedNodes.map { node ->
                    com.achulkov.evomolior.core.council.NodeAtomicityAssessment(
                        node.key,
                        true,
                        node.estimatedEffort,
                        "Strict accepted-scenario atomic node.",
                    )
                },
            ),
            // Preserve the fixture's GRADE_AUTHORITY policies; LocalLimitationNode keeps the
            // pre-policy placeholder while IncentiveDesigner owns the sealed capture map.
            incentiveDesigner = base.incentiveDesigner,
            synthesis = base.synthesis.copy(
                stages = listOf(
                    DisclosureStageDraft(
                        stageKey = "stage-zero",
                        title = "Tool origin",
                        nodeKeys = setOf(originStage.key),
                        mandatoryNodeKeys = setOf(originStage.key),
                        evidenceCoverageThreshold = 0.0,
                        integrationReadinessThreshold = 0.0,
                    ),
                    DisclosureStageDraft(
                        stageKey = "stage-one",
                        title = "Independent reuse",
                        nodeKeys = laterNodes.mapTo(linkedSetOf()) { it.key },
                        mandatoryNodeKeys = laterNodes.mapTo(linkedSetOf()) { it.key },
                        evidenceCoverageThreshold = 0.0,
                        integrationReadinessThreshold = 0.0,
                    ),
                ),
                sealedTests = base.synthesis.sealedTests + SealedTestDraft(
                    testKey = PRIVATE_FIXTURE_ID,
                    nodeKey = originStage.key,
                    acceptedArtifactTypes =
                        originStage.deliverableContract.acceptedArtifactTypes,
                    testDefinitionJson = privateFixtureDefinition,
                    commitmentHash =
                        CouncilContractHashing.sha256(privateFixtureDefinition),
                ),
            ),
        )
    }

    private fun powerToEnergyOpportunity(
        runId: String,
        reuseKey: LocalNodeKey,
    ): ToolOpportunity {
        val power = PhysicalDimension(mapOf("power" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val energy = PhysicalDimension(mapOf("energy" to 1))
        return ToolOpportunity(
            opportunityId = POWER_OPPORTUNITY_ID,
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern = "Convert constant power over hours to total energy.",
            requiredInputs = listOf(
                TypedField(
                    "power_watts",
                    TypedFieldType.NUMBER,
                    "Constant input power.",
                    physicalDimension = power,
                    quantitySpec = QuantitySpec(
                        power,
                        "W",
                        1.0,
                        AggregationSemantics.INSTANTANEOUS,
                    ),
                ),
                TypedField(
                    "duration_hours",
                    TypedFieldType.NUMBER,
                    "Duration in hours.",
                    physicalDimension = time,
                    quantitySpec = QuantitySpec(
                        time,
                        "h",
                        3_600.0,
                        AggregationSemantics.INSTANTANEOUS,
                    ),
                ),
            ),
            requiredOutputs = listOf(
                TypedField(
                    "energy_kwh",
                    TypedFieldType.NUMBER,
                    "Total energy in kilowatt-hours.",
                    physicalDimension = energy,
                    quantitySpec = QuantitySpec(
                        energy,
                        "kWh",
                        3_600_000.0,
                        AggregationSemantics.SUM_OVER_INTERVALS,
                        TimeBasis.HOUR,
                    ),
                ),
                TypedField(
                    "result_summary",
                    TypedFieldType.STRING,
                    "Bounded result summary.",
                ),
                TypedField(
                    "uncertainty",
                    TypedFieldType.STRING,
                    "Explicit uncertainty statement.",
                ),
            ),
            validationStrategy = "Compare exact dimensional reference calculations.",
            expectedReuseNodeIds = setOf(
                NodeId(CouncilDeterministicIds.nodeId(runId, reuseKey)),
            ),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("python"),
            promotionTestIds = setOf(PRIVATE_FIXTURE_ID),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 4_096,
            assumptions = listOf("Power remains constant for the supplied duration."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "result_summary",
                uncertaintyOutputField = "uncertainty",
            ),
        )
    }

    private suspend fun authorPowerToEnergyCandidate(
        runRoot: File,
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
        ledger: BudgetLedger,
        firstToolAuthorId: AtomicReference<String?>,
        repairedToolAuthorId: AtomicReference<String?>,
        phase: AtomicReference<String>,
    ) {
        phase.set("creating-runtime")
        val view = requireNotNull(bootContext.workerView)
        val runtime = createScriptedToolAuthoringRuntime(
            runRoot,
            substrateFactory,
            bootContext,
        )
        phase.set("runtime-created")
        val diagnostics = view.assignedToolRepairDiagnostics
        val isRepair = diagnostics.isNotEmpty()
        if (isRepair) {
            assertEquals(1, diagnostics.size)
            assertEquals(
                ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                diagnostics.single().code,
            )
            repairedToolAuthorId.compareAndSet(null, bootContext.agentId)
        } else {
            firstToolAuthorId.compareAndSet(null, bootContext.agentId)
        }
        val workspaceTool = ToolAuthoringWorkspaceTool(
            runtime = runtime.runtime,
            agentId = bootContext.agentId,
        )
        val sourceResult = workspaceTool.execute(
            ToolAuthoringWorkspaceTool.Args(
                operation = "WRITE",
                relativePath = "src/main.py",
                content = if (isRepair) correctedPowerToEnergySource() else {
                    incorrectPowerToEnergySource()
                },
            ),
        )
        phase.set("source-written")
        assertTrue(sourceResult.startsWith("Workspace file updated:"), sourceResult)
        val publicExpectedEnergy = if (isRepair) 3.0 else 3_000.0
        val testResult = workspaceTool.execute(
            ToolAuthoringWorkspaceTool.Args(
                operation = "WRITE",
                relativePath = "tests/author-tests.json",
                content = """
                    {"cases":[{"input":{"power_watts":1500.0,"duration_hours":2.0},"expectedOutput":{"energy_kwh":$publicExpectedEnergy,"result_summary":"Converted power to energy.","uncertainty":"Deterministic arithmetic; input uncertainty is unchanged."}}]}
                """.trimIndent(),
            ),
        )
        phase.set("tests-written")
        assertTrue(testResult.startsWith("Workspace file updated:"), testResult)

        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val toolsRoot = substrateFactory.dbFilePath(
            bootContext.runId,
            bootContext.groupId,
        ).parentFile.resolve("tools")
        val validator = ToolSubmissionValidator(
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            toolsMountHostPath = toolsRoot.absolutePath,
            opportunityResolver = { runtime.commitment },
        )
        val skeleton = requireNotNull(view.assignedToolSkeleton)
        val opportunity = requireNotNull(view.assignedNode?.toolOpportunity)
        val writer = WriteStagingTool(
            stagingDao = database.stagingDao(),
            budgetLedger = ledger,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            agentId = bootContext.agentId,
            role = bootContext.role.name,
            generation = bootContext.generation,
            defaultWipTaskRef = bootContext.assignedLease?.nodeId?.value,
            requireAssignedToolAuthority = true,
            assignedToolAuthority = AssignedToolSubmissionAuthority(
                nodeId = requireNotNull(bootContext.assignedLease).nodeId.value,
                opportunityId = opportunity.opportunityId,
                opportunityCommitmentHash = skeleton.opportunityCommitmentHash,
            ),
            toolCandidateWireCompiler = runtime.runtime.compileCandidate,
            toolSubmissionPreflight = { submission ->
                when (val validation = validator.validate(submission)) {
                    ToolSubmissionValidationResult.Valid -> null
                    is ToolSubmissionValidationResult.Invalid -> validation.reason
                }
            },
        )
        val submissionResult = writer.execute(
            WriteStagingTool.Args(
                type = SubmissionType.TOOL.name,
                toolCandidate = ToolCandidateWireDraft(
                    opportunityHandle = "T1",
                    sourceArtifactPath = "src/main.py",
                    entryPoint = "run_tool",
                    supplementalTestsPath = "tests/author-tests.json",
                    declaredAssumptions =
                        listOf("Power remains constant for the supplied duration."),
                    consumedRepairDiagnosticIds =
                        diagnostics.map { it.diagnosticId },
                ),
            ),
        )
        phase.set("submission-written")
        assertTrue(submissionResult.startsWith("Submission accepted"), submissionResult)
        val submission = database.stagingDao()
            .getAll(bootContext.runId, bootContext.groupId)
            .single {
                it.agentId == bootContext.agentId &&
                    it.type == SubmissionType.TOOL.name
            }
        val commitResult = CommitContributionTool(
            repository = WorkerExecutionRepository(database),
            bootContext = bootContext,
            budgetLedger = ledger,
            contributionGraphRepository = ContributionGraphRepository(
                database,
                substrateFactory.getAtlas(bootContext.runId),
            ),
        ).execute(
            CommitContributionTool.Args(
                commitType = if (isRepair) {
                    CommitType.REUSABLE_TOOL.name
                } else {
                    CommitType.NO_CONTRIBUTION.name
                },
                artifactIds = if (isRepair) listOf(submission.id) else emptyList(),
                changedStateSummary = if (isRepair) {
                    "Submitted the corrected power-to-energy tool candidate."
                } else {
                    "Submitted the first bounded candidate for private promotion evaluation."
                },
                evidenceSummary = if (isRepair) {
                    "Consumed the delayed typed unit-conversion diagnostic."
                } else {
                    "Awaiting private promotion authority."
                },
                confidence = if (isRepair) 0.95 else 0.5,
            ),
        )
        phase.set("contribution-committed")
        assertTrue(commitResult.startsWith("Contribution committed"), commitResult)
    }

    private suspend fun createScriptedToolAuthoringRuntime(
        runRoot: File,
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
    ): ScriptedToolAuthoringRuntime {
        val view = requireNotNull(bootContext.workerView)
        val skeleton = requireNotNull(view.assignedToolSkeleton)
        val assignedNode = requireNotNull(view.assignedNode)
        val opportunity = requireNotNull(assignedNode.toolOpportunity)
        val lease = requireNotNull(bootContext.assignedLease)
        require(lease.nodeId == assignedNode.nodeId)
        val atlasRepository = AtlasTransactionRepository(substrateFactory)
        val atlas = requireNotNull(atlasRepository.currentAtlas(bootContext.runId))
        val history = atlasRepository.versionHistory(bootContext.runId, atlas.atlasId)
        require(history.lastOrNull() == atlas)
        val commitment = requireNotNull(
            ToolOpportunityCommitmentAuthority.resolve(history)
                .byOpportunityId[opportunity.opportunityId],
        )
        val capability = ToolOpportunityBindingCapability(
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            round = bootContext.generation,
            workerViewHash = view.viewHash,
        )
        val catalog = ToolOpportunityBindingCatalogCompiler.compile(
            view = view,
            lease = ToolOpportunityLeaseAuthority(
                leaseId = lease.leaseId,
                nodeId = lease.nodeId.value,
                capability = capability,
            ),
            commitment = commitment,
        )
        val workspace = ToolContractWorkspaceCompiler.compile(
            catalog = catalog,
            capability = capability,
            commitment = commitment,
            skeleton = skeleton,
            publicTests = emptyList(),
        )
        val durableDiagnostics = ToolRepairPersistenceRepository(
            substrateFactory.get(bootContext.runId, bootContext.groupId),
        ).visibleDiagnostics(
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            opportunityId = opportunity.opportunityId,
            round = bootContext.generation,
        ).associateBy { it.diagnosticId }
        val visibleDiagnostics = view.assignedToolRepairDiagnostics
            .map { projected ->
                requireNotNull(durableDiagnostics[projected.diagnosticId])
            }
        val workspaceRoot = File(
            runRoot,
            "scripted-tool-authoring/${AtlasHashing.hashText(bootContext.agentId)}",
        ).toPath().toAbsolutePath().normalize()
        require(Files.notExists(workspaceRoot))
        ToolContractWorkspaceMaterializer.materialize(workspace, workspaceRoot)
        val compiler = ToolCandidateWireCompiler(
            catalog = catalog,
            capability = capability,
            workspace = workspace,
            commitment = commitment,
            workspaceRoot = workspaceRoot,
            visibleRepairDiagnostics = visibleDiagnostics,
            repairApproachVector = view.assignedToolRepairApproach,
        )
        val sequence = AtomicInteger(0)
        val transportStore = ToolTransportAttemptStore(substrateFactory)
        return ScriptedToolAuthoringRuntime(
            runtime = ToolAuthoringRuntime(
                workspace = workspace,
                workspaceRoot = workspaceRoot,
                compileCandidate = { draft ->
                    compiler.compile(draft).also { compilation ->
                        transportStore.append(
                            runId = bootContext.runId,
                            groupId = bootContext.groupId,
                            agentId = bootContext.agentId,
                            round = bootContext.generation,
                            compilation = compilation,
                            attemptId = "strict-tool-wire-${bootContext.agentId}-" +
                                sequence.getAndIncrement(),
                        )
                    }
                },
            ),
            commitment = commitment,
        )
    }

    private suspend fun executePowerToEnergyReuse(
        runRoot: File,
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
        ledger: BudgetLedger,
        card: WorkerToolCard,
        reuseAgentId: AtomicReference<String?>,
        reuseExecutionId: AtomicReference<String?>,
    ) {
        assertEquals(ToolExecutionPurpose.AGENT_USE, card.executionPurpose)
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val toolRepository = ToolInfrastructureRepository(database)
        val executionId =
            "strict-power-energy-use-${AtlasHashing.hashText(bootContext.agentId).take(16)}"
        val response = ExecuteToolTool(
            toolRegistryDao = database.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(
                File(runRoot, "strict-tool-use/${bootContext.agentId}"),
            ),
            budgetLedger = ledger,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            toolsMountPath = substrateFactory.dbFilePath(
                bootContext.runId,
                bootContext.groupId,
            ).parentFile.resolve("tools").absolutePath,
            agentId = bootContext.agentId,
            assignedNodeId = bootContext.assignedLease?.nodeId,
            availableToolCards = bootContext.workerView?.availableToolCards.orEmpty(),
            round = bootContext.generation,
            infrastructureRepository = toolRepository,
            executionIdFactory = { executionId },
        ).execute(
            ExecuteToolTool.Args(
                toolName = card.name,
                toolVersion = card.version,
                argsJson = """{"power_watts":2000.0,"duration_hours":1.5}""",
            ),
        )
        assertTrue(response.contains("Status: SUCCESS"), response)
        assertTrue(response.contains("\"energy_kwh\":3.0"), response)
        reuseAgentId.compareAndSet(null, bootContext.agentId)
        reuseExecutionId.compareAndSet(null, executionId)
        val writer = WriteStagingTool(
            stagingDao = database.stagingDao(),
            budgetLedger = ledger,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            agentId = bootContext.agentId,
            role = bootContext.role.name,
            generation = bootContext.generation,
            defaultWipTaskRef = bootContext.assignedLease?.nodeId?.value,
        )
        val evidenceContent = "Independent power-to-energy simulation $executionId: $response"
        val writeResult = writer.execute(
            WriteStagingTool.Args(
                type = SubmissionType.DOC.name,
                content = evidenceContent,
            ),
        )
        assertTrue(writeResult.startsWith("Submission accepted"), writeResult)
        val submission = database.stagingDao()
            .getAll(bootContext.runId, bootContext.groupId)
            .single {
                it.agentId == bootContext.agentId &&
                    it.type == SubmissionType.DOC.name &&
                    it.content == evidenceContent
            }
        val commitResult = CommitContributionTool(
            repository = WorkerExecutionRepository(database),
            bootContext = bootContext,
            budgetLedger = ledger,
            toolInfrastructureRepository = toolRepository,
            contributionGraphRepository = ContributionGraphRepository(
                database,
                substrateFactory.getAtlas(bootContext.runId),
            ),
        ).execute(
            CommitContributionTool.Args(
                commitType = CommitType.POSITIVE_RESULT.name,
                artifactIds = listOf(submission.id),
                changedStateSummary =
                    "Executed the repaired promoted power-to-energy simulator.",
                evidenceSummary =
                    "The persisted simulation output supports the independent reuse node.",
                confidence = 0.95,
            ),
        )
        assertTrue(commitResult.startsWith("Contribution committed"), commitResult)
    }

    private suspend fun executePowerToEnergyValidation(
        runRoot: File,
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
        ledger: BudgetLedger,
        card: WorkerToolCard,
        validationAgentId: AtomicReference<String?>,
        validationExecutionId: AtomicReference<String?>,
    ) {
        assertEquals(ToolExecutionPurpose.VALIDATION_TEST, card.executionPurpose)
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val toolRepository = ToolInfrastructureRepository(
            database = database,
            validationExecutionAuthorities = setOf(
                ToolValidationExecutionAuthority(
                    runId = bootContext.runId,
                    groupId = AtlasGroupId(bootContext.groupId),
                    toolVersionId = ToolVersionId(card.toolId),
                    opportunityId = card.opportunityId,
                    opportunityCommitmentHash = card.opportunityCommitmentHash,
                    validationNodeId = card.requiredForNodeId,
                ),
            ),
        )
        val executionId =
            "strict-power-energy-validation-${AtlasHashing.hashText(bootContext.agentId).take(16)}"
        val response = ExecuteToolTool(
            toolRegistryDao = database.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(
                File(runRoot, "strict-tool-validation/${bootContext.agentId}"),
            ),
            budgetLedger = ledger,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            toolsMountPath = substrateFactory.dbFilePath(
                bootContext.runId,
                bootContext.groupId,
            ).parentFile.resolve("tools").absolutePath,
            agentId = bootContext.agentId,
            assignedNodeId = bootContext.assignedLease?.nodeId,
            availableToolCards = bootContext.workerView?.availableToolCards.orEmpty(),
            round = bootContext.generation,
            infrastructureRepository = toolRepository,
            executionIdFactory = { executionId },
        ).execute(
            ExecuteToolTool.Args(
                toolName = card.name,
                toolVersion = card.version,
                argsJson = """{"power_watts":1250.0,"duration_hours":2.0}""",
            ),
        )
        assertTrue(response.contains("Status: SUCCESS"), response)
        assertTrue(response.contains("\"energy_kwh\":2.5"), response)
        validationAgentId.compareAndSet(null, bootContext.agentId)
        validationExecutionId.compareAndSet(null, executionId)

        val evidenceContent =
            "Independent power-to-energy validation $executionId: $response"
        val writer = WriteStagingTool(
            stagingDao = database.stagingDao(),
            budgetLedger = ledger,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            agentId = bootContext.agentId,
            role = bootContext.role.name,
            generation = bootContext.generation,
            defaultWipTaskRef = bootContext.assignedLease?.nodeId?.value,
        )
        val writeResult = writer.execute(
            WriteStagingTool.Args(
                type = SubmissionType.DOC.name,
                content = evidenceContent,
            ),
        )
        assertTrue(writeResult.startsWith("Submission accepted"), writeResult)
        val submission = database.stagingDao()
            .getAll(bootContext.runId, bootContext.groupId)
            .single {
                it.agentId == bootContext.agentId &&
                    it.type == SubmissionType.DOC.name &&
                    it.content == evidenceContent
            }
        val commitResult = CommitContributionTool(
            repository = WorkerExecutionRepository(database),
            bootContext = bootContext,
            budgetLedger = ledger,
            toolInfrastructureRepository = toolRepository,
            contributionGraphRepository = ContributionGraphRepository(
                database,
                substrateFactory.getAtlas(bootContext.runId),
            ),
        ).execute(
            CommitContributionTool.Args(
                commitType = CommitType.POSITIVE_RESULT.name,
                artifactIds = listOf(submission.id),
                changedStateSummary =
                    "Executed the repaired promoted tool under validation authority.",
                evidenceSummary =
                    "The exact validation execution satisfies the reproduction node.",
                confidence = 0.95,
            ),
        )
        assertTrue(commitResult.startsWith("Contribution committed"), commitResult)
    }

    private suspend fun commitNoContribution(
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
        ledger: BudgetLedger,
        reason: String,
    ) {
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val result = CommitContributionTool(
            repository = WorkerExecutionRepository(database),
            bootContext = bootContext,
            budgetLedger = ledger,
            contributionGraphRepository = ContributionGraphRepository(
                database,
                substrateFactory.getAtlas(bootContext.runId),
            ),
        ).execute(
            CommitContributionTool.Args(
                commitType = CommitType.NO_CONTRIBUTION.name,
                changedStateSummary = reason,
                evidenceSummary = reason,
                confidence = 1.0,
            ),
        )
        assertTrue(result.startsWith("Contribution committed"), result)
    }

    private fun incorrectPowerToEnergySource(): String = """
        import json
        import sys

        value = json.load(sys.stdin)
        energy_kwh = value["power_watts"] * value["duration_hours"]
        sys.stdout.write(json.dumps({
            "energy_kwh": energy_kwh,
            "result_summary": "Converted power to energy.",
            "uncertainty": "Deterministic arithmetic; input uncertainty is unchanged."
        }, separators=(",", ":")))
    """.trimIndent()

    private fun correctedPowerToEnergySource(): String = """
        import json
        import sys

        value = json.load(sys.stdin)
        energy_kwh = value["power_watts"] * value["duration_hours"] / 1000.0
        sys.stdout.write(json.dumps({
            "energy_kwh": energy_kwh,
            "result_summary": "Converted power to energy.",
            "uncertainty": "Deterministic arithmetic; input uncertainty is unchanged."
        }, separators=(",", ":")))
    """.trimIndent()

    private data class ScriptedToolAuthoringRuntime(
        val runtime: ToolAuthoringRuntime,
        val commitment: ToolOpportunityCommitment,
    )

    private suspend fun persistPassingGraderMethodology(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>,
        json: Json,
    ) {
        val repository = GraderAuthorityRepository(substrateFactory)
        val graderAtlas = requireNotNull(repository.currentAtlas(runId))
        val leases = GraderNodeLeaseAuthority(repository)
        graderGroupIds.forEach { graderGroupId ->
            val lease = leases.acquireNext(graderAtlas, graderGroupId, roundIndex, 60_000L)
            val node = graderAtlas.nodes.getValue(lease.nodeId)
            val requirements = node.mappedWorkerNodeIds.sortedBy { it.value }.map { workerNodeId ->
                val id = "accepted-${lease.nodeId.value}-${workerNodeId.value}"
                GraderRequirementDraft(
                    requirementId = id,
                    graderAtlasNodeId = lease.nodeId,
                    mappedWorkerNodeIds = setOf(workerNodeId),
                    mappedReceiverObligationRefs = node.receiverObligationRefs,
                    criterion = "The sealed receiver evidence is present.",
                    observableEvidence = "Exact sealed worker snapshot.",
                    passThreshold = "Snapshot is available.",
                    failureLevels = listOf(FailureLevel(1, "Evidence absent.", 0.0)),
                    adversarialCases = emptyList(),
                    executableTestIds = listOf("test-$id"),
                    disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                    rationale = "Strict accepted-scenario criterion.",
                    noveltyFingerprint = "accepted-$roundIndex-$graderGroupId-${workerNodeId.value}",
                    maximumEvaluationCost = 1.0,
                )
            }
            val methodology = GraderMethodologyDraft(
                rubricDimensions = scoringDimensions.map {
                    GraderRubricDraft(it.id, "Score complete sealed evidence.")
                },
                automatedTests = requirements.map { requirement ->
                    GraderAutomatedTestDraft(
                        testId = "test-${requirement.requirementId}",
                        description = "Require the sealed snapshot.",
                        expectedOutputPattern = ".*",
                        penaltyOnFailure = 0.0,
                        isMandatory = true,
                        mappedRequirementIds = setOf(requirement.requirementId),
                        inputSchemaHash = "strict-accepted-snapshot-v1",
                        expectedOutcomeHash = "snapshot-present",
                        difficulty = 0.0,
                    )
                },
                failureModes = listOf(
                    GraderFailureModeDraft("missing-snapshot", "Snapshot unavailable.", 0.0),
                ),
                requirements = requirements,
            )
            val now = System.currentTimeMillis()
            substrateFactory.getGrader(runId, graderGroupId).stagingDao().insert(
                StagingSubmissionEntity(
                    id = "strict-accepted-methodology-$roundIndex-$graderGroupId",
                    agentId = "strict-accepted-grader-$graderGroupId",
                    role = Role.GraderBuilder.name,
                    type = "PLAN_UPDATE",
                    content = json.encodeToString(GraderMethodologyDraft.serializer(), methodology),
                    budgetSignature =
                        "$GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX" +
                            "strict-accepted-$graderGroupId:${roundIndex + 1}",
                    status = "promoted",
                    generation = roundIndex + 1,
                    groupId = graderGroupId,
                    runId = runId,
                    submittedAtMs = now,
                    reviewedAtMs = now,
                ),
            )
        }
    }

    private suspend fun seedAcceptedWorkerEvidence(
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
        simulationExecutionId: String?,
        validationExecutionId: String?,
    ) {
        if (bootContext.role == Role.Archivist &&
            bootContext.rolePrompt.contains("Round Submission Summariser")
        ) {
            val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
            val now = System.currentTimeMillis()
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "strict-summary-${bootContext.agentId}",
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    type = "PLAN_UPDATE",
                    content = (0 until 8).joinToString(
                        prefix = "ROUND SUBMISSION SUMMARY\n\n# Accepted evidence summary\n\n",
                        separator = "\n",
                    ) { "- strict-accepted-marker-$it" },
                    budgetSignature = "strict-round-summary",
                    status = "pending",
                    generation = bootContext.generation,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    submittedAtMs = now,
                ),
            )
            return
        }
        if (bootContext.role == Role.Builder &&
            bootContext.rolePrompt.contains("Final Deliverable Writer")
        ) {
            val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
            val now = System.currentTimeMillis()
            val exactSimulationExecutionId = requireNotNull(simulationExecutionId) {
                "Accepted final writer requires the independent simulation execution authority."
            }
            val exactValidationExecutionId = requireNotNull(validationExecutionId) {
                "Accepted final writer requires the independent validation execution authority."
            }
            val selectedNodeIds = ContributionGraphRepository(
                database = database,
                atlasDatabase = substrateFactory.getAtlas(bootContext.runId),
            ).finalDeliverableSources(bootContext.runId, bootContext.groupId)
                .mapNotNullTo(sortedSetOf()) { it.nodeId }
            val reuseNodeId = CouncilDeterministicIds.nodeId(
                bootContext.runId,
                LocalNodeKey(REUSE_KEY),
            )
            require(reuseNodeId in selectedNodeIds) {
                "Accepted final writer requires the captured simulation-reuse node."
            }
            val markerBody = (0 until 8).joinToString("\n") {
                "strict-accepted-marker-$it"
            }
            val componentSections = DELIVERABLE_COMPONENT_IDS.joinToString("\n\n") {
                "## $it\n\nAccepted authority is mapped in the final component lineage."
            }
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "strict-final-${bootContext.agentId}",
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    type = "DOC",
                    content = "# Accepted power-to-energy decision\n\n" +
                        "$markerBody\n\n$componentSections" +
                        "\n\nValidation: $exactValidationExecutionId." +
                        "\n\nExecution: $exactSimulationExecutionId." +
                        "\n\nUncertainty: deterministic arithmetic.",
                    budgetSignature = "strict-final-writer",
                    status = "pending",
                    generation = bootContext.generation,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    submittedAtMs = now,
                ),
            )
            val allSelectedNodeIdsJson = selectedNodeIds.joinToString(",") {
                "\"$it\""
            }
            val componentNodeIdsJson = DELIVERABLE_COMPONENT_IDS.joinToString(",") {
                "\"$it\":[$allSelectedNodeIdsJson]"
            }
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "strict-final-lineage-${bootContext.agentId}",
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    type = "PLAN_UPDATE",
                    content = """
                        $FINAL_COMPONENT_LINEAGE_V1_MARKER
                        {
                          "schemaVersion":"final-component-lineage-v1",
                          "outputRelativePath":"receiver/result.md",
                          "componentNodeIds":{$componentNodeIdsJson}
                        }
                    """.trimIndent(),
                    budgetSignature = "strict-final-lineage",
                    status = "pending",
                    generation = bootContext.generation,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    submittedAtMs = now + 1L,
                ),
            )
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "strict-final-death-note-${bootContext.agentId}",
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    type = "DEATH_NOTE",
                    content = "Accepted final deliverable staged.",
                    budgetSignature = "strict-final-death-note",
                    status = "promoted",
                    generation = bootContext.generation,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    submittedAtMs = now + 2L,
                    reviewedAtMs = now + 2L,
                ),
            )
            return
        }

        val nodeId = bootContext.wipTaskClaim?.nodeId ?: return
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val submissionId = "strict-accepted-evidence-" +
            AtlasHashing.hashText(
                "${bootContext.groupId}|${bootContext.generation}|$nodeId",
            ).take(32)
        if (database.stagingDao().getById(submissionId) != null) return
        val content = "Accepted deterministic evidence for ${bootContext.groupId}, node $nodeId."
        val hash = AtlasHashing.hashText(content)
        val now = System.currentTimeMillis()
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = submissionId,
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "DOC",
                content = content,
                wipTaskRef = nodeId,
                budgetSignature = "strict-accepted-worker",
                status = "promoted",
                criticAgentId = "strict-accepted-critic",
                initialTrustScore = 1.0,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = now,
                reviewedAtMs = now + 1L,
                promotedArtifactRef = "kb:$submissionId",
                promotedArtifactHash = hash,
            ),
        )
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = submissionId,
                content = content,
                sourceAgentId = bootContext.agentId,
                generation = bootContext.generation,
                trustScore = 1.0,
                domain = "strict:accepted-evidence",
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                createdAtMs = now + 1L,
            ),
        )
        ContributionGraphRepository(
            database = database,
            atlasDatabase = substrateFactory.getAtlas(bootContext.runId),
        ).recordCriticDecision(
            com.achulkov.evomolior.core.contract.CriticDecisionRecord(
                decisionId = "strict-accepted-decision-$submissionId",
                runId = bootContext.runId,
                groupId = AtlasGroupId(bootContext.groupId),
                round = bootContext.generation,
                submissionId = submissionId,
                submissionContentHash = hash,
                resolvedReview = CriticReview(
                    submissionId = submissionId,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "strict-accepted-critic",
                    initialTrustScore = 1.0,
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:$submissionId",
                    authorityHash = hash,
                ),
                decidedAt = Instant.fromEpochMilliseconds(now + 1L),
            ).withComputedHash(),
        )
        val contribution = ContributionCommit(
            commitId = "strict-accepted-contribution-$submissionId",
            runId = bootContext.runId,
            groupId = AtlasGroupId(bootContext.groupId),
            round = bootContext.generation,
            agentId = AtlasAgentId(bootContext.agentId),
            nodeId = NodeId(nodeId),
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = listOf(submissionId),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = emptyList(),
            changedStateSummary = "Persisted accepted evidence.",
            evidenceSummary = "Critic-promoted exact evidence.",
            confidence = 1.0,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = NodeEvidenceCostSummary(totalCredits = 1.0),
            dedupeHash = "",
            committedAt = Instant.fromEpochMilliseconds(now + 2L),
        )
        WorkerExecutionRepository(database).commit(
            contribution.copy(dedupeHash = AtlasHashing.hashContributionDedupe(contribution)),
        )
    }

    private fun EvoMoliorEvent.runIdOrNull(): String? = when (this) {
        is EvoMoliorEvent.RunRecovered -> runId
        is EvoMoliorEvent.RunCompleted -> runId
        is EvoMoliorEvent.RunAborted -> runId
        else -> null
    }

    private fun EvoMoliorEvent.isTerminal(): Boolean =
        this is EvoMoliorEvent.RunRecovered ||
            this is EvoMoliorEvent.RunCompleted ||
            this is EvoMoliorEvent.RunAborted

    private fun unzipEntries(bytes: ByteArray): Map<String, String> =
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap {
                generateSequence { zip.nextEntry }.forEach { entry ->
                    put(entry.name, zip.readBytes().decodeToString())
                    zip.closeEntry()
                }
            }
        }

    private companion object {
        const val ORIGIN_KEY = "power-origin"
        const val DELIVERY_KEY = "receiver-delivery"
        const val VALIDATION_KEY = "receiver-validation"
        const val REUSE_KEY = "receiver-evidence"
        const val POWER_OPPORTUNITY_ID = "power-to-energy"
        const val PRIVATE_FIXTURE_ID = "strict-power-energy-private"
        val DELIVERABLE_COMPONENT_IDS = listOf(
            "executive-summary",
            "decision-matrix",
            "recommendation",
            "evidence-ledger",
            "assumptions",
            "uncertainties",
            "risk-register",
            "implementation-plan",
            "action-checklist",
            "tool-and-simulation-results",
            "alternative-views",
            "limitations-and-next-evidence",
            "technical-appendix",
        )
    }
}
