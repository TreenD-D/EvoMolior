@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreDraft
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRepairDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.model.FinalOutputSpec
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
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.contract.RecoveryPublicationState
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import com.achulkov.evomolior.core.metrics.RunClosureMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Exercises the strict six-round production graph when every group has mandatory failures.
 */
class StrictAcceptanceNoCandidateScenarioTest {
    @Test
    fun `six round production path publishes only a verified two-group provisional outcome`() =
        runBlocking {
            val scenario =
                StrictAcceptanceScenarioV1(
                    scenarioId = "strict-no-candidate-production-v1",
                    rounds = 6,
                    workerGroups = 2,
                    graderGroups = 3,
                    injectLifecycleRace = false,
                    firstToolFailure = ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED,
                    requireLaterAgentUse = false,
                    requireSimulationInfluence = false,
                    terminalPolicy = ScenarioTerminalPolicy.PROVISIONAL_NO_CANDIDATE,
                )
            val runId = scenario.scenarioId
            val workerGroupKeys = listOf("group-0", "group-1")
            val workerGroupIds = workerGroupKeys.map { key ->
                CouncilDeterministicIds.groupId(runId, "worker", key)
            }
            val graderGroupIds = (0 until scenario.graderGroups).map { index ->
                CouncilDeterministicIds.groupId(runId, "grader", "grader-$index")
            }
            val privateFixtureFragments =
                setOf(
                    "strict-private-expected-alpha",
                    "strict-private-expected-beta",
                    "strict-private-mandatory-pass-marker",
                )
            val json = Json
            val substrateFactory =
                SubstrateDatabaseFactory(
                    createTempDirectory("strict-no-candidate-production-").toFile(),
                )
            val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
            val service =
                EvoMoliorRunService(
                    lifecycleManagerProvider = { activeRunId, groupId, _, _, groupProfile ->
                        LifecycleManager(
                            runId = activeRunId,
                            groupId = groupId,
                            shuruVmManager = ShuruVmManager(),
                            substrateDatabaseFactory = substrateFactory,
                            agentJarPath = "/fake/agent.jar",
                            openRouterApiKey = "/tmp/no-provider",
                            groupProfile = groupProfile,
                            localAgentRunner = { bootContext, _ ->
                                seedExactWorkerEvidence(substrateFactory, bootContext)
                            },
                        )
                    },
                    groupCoordinatorProvider = { groupIds, dimensions ->
                        GroupCoordinator(groupIds, dimensions)
                    },
                    graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                    substrateFactory = substrateFactory,
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
                        assertEquals(
                            CouncilExecutionPurpose.STANDARD,
                            envelope.councilExecutionPurpose,
                        )
                        assertEquals(AcceptanceProfile.NORMAL, envelope.acceptanceProfile)
                        assertEquals(
                            scenario.graderGroups,
                            envelope.requiredGraderGroupCount.minimum,
                        )
                        compiledCouncilTestFixture(
                            runId = callbackRunId,
                            objective = objective,
                            envelope = envelope,
                            inputArtifacts = inputArtifacts,
                            workerGroupKeys = workerGroupKeys,
                            workerProfiles =
                                listOf(
                                    GroupProfileType.Library,
                                    GroupProfileType.Factory,
                                ),
                            generalTests =
                                privateFixtureFragments.sorted().mapIndexed {
                                    index,
                                    fragment,
                                    ->
                                    CompiledCouncilGeneralTestFixture(
                                        testId = "strict-private-test-$index",
                                        nodeKey = LocalNodeKey("receiver-private-$index"),
                                        nodeKind = NodeKind.DELIVERY,
                                        acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                                        expectedText = fragment,
                                    )
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
                        val repository = GraderAuthorityRepository(substrateFactory)
                        val graderAtlas =
                            requireNotNull(repository.currentAtlas(criteriaRunId)) {
                                "Strict scenario requires the compiled Grader Atlas."
                            }
                        val leases = GraderNodeLeaseAuthority(repository)
                        actualGraderGroupIds.forEach { graderGroupId ->
                            val lease =
                                leases.acquireNext(
                                    atlas = graderAtlas,
                                    graderGroupId = graderGroupId,
                                    round = roundIndex,
                                    ttlMillis = 60_000L,
                                )
                            val node = graderAtlas.nodes.getValue(lease.nodeId)
                            val requirements =
                                node.mappedWorkerNodeIds
                                    .sortedBy { it.value }
                                    .map { workerNodeId ->
                                        val requirementId =
                                            "mandatory-${lease.nodeId.value}-${workerNodeId.value}"
                                        GraderRequirementDraft(
                                            requirementId = requirementId,
                                            graderAtlasNodeId = lease.nodeId,
                                            mappedWorkerNodeIds = setOf(workerNodeId),
                                            mappedReceiverObligationRefs =
                                                node.receiverObligationRefs,
                                            criterion =
                                                "The mandatory receiver evidence must be present.",
                                            observableEvidence =
                                                "The exact sealed worker snapshot.",
                                            passThreshold =
                                                "The private mandatory pass marker is present.",
                                            failureLevels =
                                                listOf(
                                                    FailureLevel(
                                                        level = 3,
                                                        description =
                                                            "Mandatory receiver evidence is absent.",
                                                        scoreCeiling = 0.0,
                                                        critical = true,
                                                    ),
                                                ),
                                            adversarialCases = emptyList(),
                                            executableTestIds =
                                                listOf("test-$requirementId"),
                                            disclosureSensitivity =
                                                DisclosureSensitivity.GRADER_INTERNAL,
                                            rationale =
                                                "The scenario requires an exact mandatory failure.",
                                            noveltyFingerprint =
                                                "mandatory-$roundIndex-$graderGroupId-" +
                                                    workerNodeId.value,
                                            maximumEvaluationCost = 1.0,
                                        )
                                    }
                            val methodology =
                                GraderMethodologyDraft(
                                    rubricDimensions =
                                        scoringDimensions.map { dimension ->
                                            GraderRubricDraft(
                                                dimensionId = dimension.id,
                                                scoringGuidance =
                                                    "Mandatory failure forces zero authority.",
                                            )
                                        },
                                    automatedTests =
                                        requirements.map { requirement ->
                                            GraderAutomatedTestDraft(
                                                testId =
                                                    "test-${requirement.requirementId}",
                                                description =
                                                    "Require the sealed private pass marker.",
                                                expectedOutputPattern =
                                                    "strict-private-mandatory-pass-marker",
                                                penaltyOnFailure = 0.0,
                                                isMandatory = true,
                                                mappedRequirementIds =
                                                    setOf(requirement.requirementId),
                                                inputSchemaHash =
                                                    "strict-no-candidate-snapshot-v1",
                                                expectedOutcomeHash =
                                                    "mandatory-marker-present",
                                                difficulty = 1.0,
                                            )
                                        },
                                    failureModes =
                                        listOf(
                                            GraderFailureModeDraft(
                                                modeId = "mandatory-evidence-absent",
                                                description =
                                                    "The required receiver evidence is absent.",
                                                penaltyWeight = 1.0,
                                            ),
                                        ),
                                    requirements = requirements,
                                )
                            val now = System.currentTimeMillis()
                            substrateFactory
                                .getGrader(criteriaRunId, graderGroupId)
                                .stagingDao()
                                .insert(
                                    StagingSubmissionEntity(
                                        id =
                                            "strict-methodology-$roundIndex-$graderGroupId",
                                        agentId = "strict-grader-$graderGroupId",
                                        role = Role.GraderBuilder.name,
                                        type = "PLAN_UPDATE",
                                        content =
                                            json.encodeToString(
                                                GraderMethodologyDraft.serializer(),
                                                methodology,
                                            ),
                                        budgetSignature =
                                            "$GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX" +
                                                "strict-grader-$graderGroupId:" +
                                                "${roundIndex + 1}",
                                        status = "promoted",
                                        generation = roundIndex + 1,
                                        groupId = graderGroupId,
                                        runId = criteriaRunId,
                                        submittedAtMs = now,
                                        reviewedAtMs = now,
                                    ),
                                )
                        }
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
                            graderModelOverride =
                                ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                            structuredDimensionScoreAuthor = { request ->
                                json.encodeToString(
                                    StructuredDimensionScoreRepairDraft.serializer(),
                                    StructuredDimensionScoreRepairDraft(
                                        scores =
                                            request.missingDimensions.map { dimension ->
                                                StructuredDimensionScoreDraft(
                                                    dimensionId = dimension.id,
                                                    rawScore = dimension.maxScore,
                                                    rationale =
                                                        "Authored before the mandatory gate.",
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
                                        scoreVector =
                                            NodeScoreVector(
                                                correctness = 0.0,
                                                evidenceQuality = 0.0,
                                                reproducibility = 0.0,
                                                robustness = 0.0,
                                                integrationUtility = 0.0,
                                                receiverRelevance = 0.0,
                                                novelty = 0.0,
                                                efficiency = 0.0,
                                                hardFailure = true,
                                                confidence = 1.0,
                                            ),
                                        rationale =
                                            "Mandatory receiver evidence is absent.",
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
                    roundDurationMillisOverride = 1_500L,
                    roundHeartbeatIntervalMs = 1L,
                    inspectorCheckIntervalMs = 1L,
                )

            val eventCollector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event -> events += event }
                }
            val terminalEvent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(60_000L) {
                        service.events.first { event ->
                            event.runIdOrNull() == runId && event.isTerminal()
                        }
                    }
                }

            val canonicalSource =
                requireNotNull(
                    ArcAgi2NotebookQualificationAuthority.notebookValidationContract
                        .jupyterNotebookSource
                )
            val evolvedNotebookContract =
                ArcAgi2NotebookQualificationAuthority.notebookValidationContract.copy(
                    requiredMarkdownSectionSignals =
                        ArcAgi2NotebookQualificationAuthority.notebookValidationContract
                            .requiredMarkdownSectionSignals +
                            listOf("Kaggle receiver contract", "prior Kaggle baseline"),
                    jupyterNotebookSource = canonicalSource.copy(
                        requiredCodeSignals = canonicalSource.requiredCodeSignals +
                            listOf(
                                "arc-agi_evaluation_challenges.json",
                                "prior-kaggle-submission.json",
                                "leave_one_out",
                                "baseline fallback",
                                "one result row per test input",
                            ),
                    ),
                )
            val runConfig =
                RunConfig(
                    runId = runId,
                    objective = "Produce a receiver-usable deterministic comparison.",
                    groupCount = scenario.workerGroups,
                    groupProfiles =
                        listOf(
                            GroupProfileType.Library,
                            GroupProfileType.Factory,
                        ),
                    rounds = scenario.rounds,
                    roundDurationMinutes = 0,
                    maxAgentsPerGroup = 1,
                    scoringDimensions =
                        listOf(
                            ScoringDimension(
                                id = "quality",
                                name = "Quality",
                                description = "Receiver-facing quality.",
                                weight = 1.0,
                            ),
                        ),
                    openRouterApiKey = "/tmp/no-provider",
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                    acceptanceProfile = AcceptanceProfile.NORMAL,
                    finalOutputSpec =
                        FinalOutputSpec(
                            relativePath = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_RELATIVE_PATH,
                            mediaType = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_MEDIA_TYPE,
                            validation = evolvedNotebookContract,
                        ),
                )
            service.startRun(runConfig)

            val recovered = terminalEvent.await()
            eventCollector.cancel()
            assertTrue(
                recovered is EvoMoliorEvent.RunCompletedProvisional,
                "Unexpected terminal: $recovered",
            )
            assertEquals(null, recovered.sourceGroupId)
            assertFalse(events.any { it is EvoMoliorEvent.RunCompleted })
            assertFalse(events.any { it is EvoMoliorEvent.RunAborted })
            assertTrue(
                events
                    .filterIsInstance<EvoMoliorEvent.EngineProgress>()
                    .any {
                        it.runId == runId &&
                            it.phase == "final_round_result" &&
                            it.message.contains("scoringCandidate=none")
                    },
                "Final-round scoring must remain explicitly unresolved.",
            )
            assertTrue(
                events.any {
                    it is EvoMoliorEvent.EngineProgress &&
                        it.runId == runId &&
                        it.phase == "main_writer_spawn"
                },
                "The main writer must run before strict acceptance is resolved.",
            )
            val scaffoldReadyIndex = events.indexOfFirst {
                it is EvoMoliorEvent.EngineProgress &&
                    it.runId == runId &&
                    it.phase == "artifact_scaffold_ready"
            }
            val firstGraderIndex = events.indexOfFirst {
                it is EvoMoliorEvent.GraderRoundStarted &&
                    it.runId == runId
            }
            assertTrue(
                scaffoldReadyIndex >= 0 &&
                    firstGraderIndex >= 0 &&
                    scaffoldReadyIndex < firstGraderIndex,
                "Host artifact rescue/scaffold must be ready before expensive grading.",
            )
            assertFalse(
                events.any {
                    it is EvoMoliorEvent.EngineProgress &&
                        it.runId == runId &&
                        it.phase == "recovery_deliverable_spawn"
                },
                "A valid pre-acceptance artifact must not be authored by a recovery writer.",
            )
            val terminalOutcome = assertNotNull(
                (recovered as EvoMoliorEvent.RunCompletedProvisional).terminalOutcome
            )
            assertEquals(ExecutionCompletionStatus.COMPLETED, terminalOutcome.executionStatus)
            assertEquals(AcceptanceStatus.PROVISIONAL, terminalOutcome.acceptanceStatus)
            assertEquals(null, terminalOutcome.acceptedWinnerId)
            assertEquals(
                PrimaryArtifactValidationStatus.NOT_EVALUATED,
                terminalOutcome.primaryArtifactValidationStatus,
            )
            assertTrue(
                terminalOutcome.primaryArtifactRef?.relativePath
                    ?.endsWith("outputs/recovered/submission.ipynb") == true,
            )
            val presentation = assertNotNull(service.getRunOutcomePresentation(runId))
            assertEquals(ExecutionCompletionStatus.COMPLETED, presentation.executionStatus)
            assertEquals(AcceptanceStatus.PROVISIONAL, presentation.acceptanceStatus)
            assertEquals(
                "outputs/recovered/submission.ipynb",
                presentation.primaryArtifactPath,
            )
            assertEquals(
                PrimaryArtifactValidationStatus.NOT_EVALUATED,
                presentation.primaryArtifactValidationStatus,
            )

            val completedGraders =
                events
                    .filterIsInstance<EvoMoliorEvent.GraderRoundCompleted>()
                    .filter { it.runId == runId }
            val expectedCompletions =
                (0 until scenario.rounds)
                    .flatMap { round ->
                        graderGroupIds.map { graderGroupId -> round to graderGroupId }
                    }.toSet()
            assertEquals(
                expectedCompletions,
                completedGraders.map { it.roundIndex to it.graderGroupId }.toSet(),
            )
            assertEquals(expectedCompletions.size, completedGraders.size)
            val expectedScoresPerGrader =
                scenario.workerGroups * RunScoringDimensionPolicy.select(runConfig).size
            assertTrue(
                completedGraders.all {
                    it.scoresPerGroup.keys == workerGroupIds.toSet() &&
                        it.totalScoresCollected ==
                        expectedScoresPerGrader
                },
            )

            val graderAuthorityRepository = GraderAuthorityRepository(substrateFactory)
            val expectedTargets = workerGroupIds.mapTo(linkedSetOf(), ::AtlasGroupId)
            (0 until scenario.rounds).forEach { round ->
                val scoreBundles =
                    graderAuthorityRepository.scoreBundlesForRound(runId, round)
                assertEquals(
                    scenario.graderGroups * scenario.workerGroups,
                    scoreBundles.size,
                )
                assertEquals(
                    graderGroupIds
                        .flatMap { graderGroupId ->
                            expectedTargets.map { target -> graderGroupId to target }
                        }.toSet(),
                    scoreBundles
                        .map { bundle -> bundle.graderGroupId to bundle.targetGroupId }
                        .toSet(),
                )
                assertTrue(
                    scoreBundles.all { bundle ->
                        bundle.status == GraderScoreBundleStatus.VALID &&
                            bundle.hasValidHash() &&
                            bundle.nodeScores.isNotEmpty() &&
                            bundle.nodeScores.values.all(NodeScoreVector::hardFailure) &&
                            bundle.groupScores.values.all { score -> score.value == 0.0 } &&
                            bundle.requirementResults.isNotEmpty() &&
                            bundle.requirementResults.values.any { result ->
                                result.status == RequirementResultStatus.FAIL &&
                                    result.criticalFailure
                            } &&
                            bundle.requirementResults.values.all { result ->
                                result.status in
                                    setOf(
                                        RequirementResultStatus.FAIL,
                                        RequirementResultStatus.NOT_APPLICABLE,
                                    ) &&
                                    result.executableTestResultHashes.isNotEmpty() &&
                                    (
                                        result.status != RequirementResultStatus.FAIL ||
                                            result.criticalFailure
                                    )
                            }
                    },
                )
                val exactVerdict =
                    ExactGraderBundleResolver().resolve(
                        runId = runId,
                        round = round,
                        expectedGraderGroupIds = graderGroupIds.toSet(),
                        expectedTargetGroupIds = expectedTargets,
                        bundles = scoreBundles,
                    )
                assertEquals(ExactGraderRoundStatus.VALID, exactVerdict.status)
                assertEquals(scoreBundles.size, exactVerdict.acceptedBundleHashes.size)
                assertEquals(expectedTargets, exactVerdict.targetSnapshotHashes.keys)
                assertTrue(
                    exactVerdict.groupScores.values.all { scores ->
                        scores.values.all { score -> score == 0.0 }
                    },
                )
                assertTrue(
                    exactVerdict.nodeScores.values.all { scores ->
                        scores.isNotEmpty() &&
                            scores.values.all(NodeScoreVector::hardFailure)
                    },
                )
            }

            val nodeGradeBundles =
                NodeCaptureRepository(substrateFactory)
                    .gradeBundlesForRun(runId)
            assertEquals((0 until scenario.rounds).toSet(), nodeGradeBundles.map { it.round }.toSet())
            (0 until scenario.rounds).forEach { round ->
                val roundBundles = nodeGradeBundles.filter { it.round == round }
                assertTrue(roundBundles.isNotEmpty())
                val evaluatedBundles =
                    roundBundles.filter {
                        it.verdict == NodeGradeBundleVerdict.VALID_FAIL
                    }
                assertEquals(
                    workerGroupIds.toSet(),
                    evaluatedBundles.mapTo(linkedSetOf()) { it.groupId.value },
                )
                assertEquals(scenario.workerGroups, evaluatedBundles.size)
                assertTrue(
                    evaluatedBundles.all { bundle ->
                        bundle.hasValidHash() &&
                            bundle.evidencePacketId != null &&
                            bundle.evidencePacketHash != null &&
                            bundle.scoreVector?.hardFailure == true &&
                            bundle.requiredQuorum == scenario.graderGroups &&
                            bundle.graderIds == graderGroupIds.toSet() &&
                            bundle.completedGraderIds == graderGroupIds.toSet() &&
                            bundle.graderResponseHashes.size == scenario.graderGroups
                    },
                )
            }

            val mandatoryFailureSummaries =
                service
                    .getMetaArchiveEntries(runId, RunArchiveService.ENTRY_ROUND_SUMMARY)
                    .filter { it.groupId != null }
            assertEquals(scenario.rounds * scenario.workerGroups, mandatoryFailureSummaries.size)
            assertEquals(
                workerGroupIds.toSet(),
                mandatoryFailureSummaries
                    .mapNotNull {
                        it.groupId
                    }.toSet(),
            )
            assertTrue(
                mandatoryFailureSummaries.all { summary ->
                    summary.contentJson.contains("\"mandatoryFailure\":true") &&
                        !summary.contentJson.contains("\"automatedTestsFailed\":0")
                },
                "Every worker must retain a deterministic mandatory-failure authority each round.",
            )

            val atlasDatabase = substrateFactory.getAtlas(runId)
            val publicationRepository = PublicationStateRepository(atlasDatabase)
            assertTrue(publicationRepository.finalCandidates(runId).isEmpty())
            assertTrue(publicationRepository.history(runId).isEmpty())
            assertEquals(
                RecoveryPublicationState.entries,
                PublicationOutcomeRepository(atlasDatabase)
                    .recoveryHistory(runId)
                    .map { it.state },
            )
            assertTrue(
                service
                    .getMetaArchiveEntries(
                        runId,
                        RunArchiveService.ENTRY_RUN_COMPLETED,
                    ).isEmpty(),
            )
            assertTrue(
                service
                    .getMetaArchiveEntries(
                        runId,
                        RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE,
                    ).isEmpty(),
            )

            val bundle = assertNotNull(service.getResultsBundle(runId))
            val authority =
                RunOutcomeArchiveService().verifyProvisional(
                    bytes = bundle,
                    forbiddenPrivateFragments = privateFixtureFragments,
                )
            val sourceSet = authority.recoverySourceSets.single()
            assertEquals(workerGroupIds.toSet(), sourceSet.sourceGroupIds.toSet())
            assertEquals(
                workerGroupIds.sorted().mapIndexed { index, groupId ->
                    groupId to "NON_ACCEPTED_SOURCE_${'A' + index}"
                }.toMap(),
                sourceSet.sourceLabels,
            )
            assertEquals(null, authority.decisionPackage.acceptedAuthority)
            assertTrue(
                authority.decisionPackage.knownFailures.any { failure ->
                    failure.gateId == "sealed-suite:mandatory-failure"
                },
                "The provisional authority must preserve the mandatory-failure cause.",
            )
            assertEquals(null, authority.outcomeManifest.acceptedAuthorityHash)
            assertNotNull(authority.outcomeManifest.metricsHash)
            assertFalse(
                authority.outcomeManifest.entries.any { entry ->
                    entry.path.contains("final-reference") ||
                        entry.category.name.startsWith("ACCEPTED_")
                },
            )

            val archiveEntries = unzipEntries(bundle)
            assertTrue(
                "outputs/recovered/submission.ipynb" in archiveEntries,
                "The deterministic ARC fallback must be archived as a provisional deliverable.",
            )
            assertFalse(archiveEntries.keys.any { it.contains("final-reference") })
            assertTrue("recovery/run-metrics-v1.json" in archiveEntries)
            val metrics = json.decodeFromString(
                RunMetricsV1.serializer(),
                archiveEntries.getValue("recovery/run-metrics-v1.json"),
            )
            assertEquals(
                RunMetricCatalog.orderedMetricIds,
                metrics.observations.map { it.metricId },
            )
            assertEquals(
                RunClosureMetricCatalog.orderedMetricIds,
                metrics.closureObservations.map { it.metricId },
            )
            assertTrue(
                (metrics.observations + metrics.closureObservations)
                    .all { it.evidenceRefs.isNotEmpty() },
            )
            assertTrue(
                archiveEntries.values.none { content ->
                    privateFixtureFragments.any(content::contains)
                },
            )
        }

    private suspend fun seedExactWorkerEvidence(
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
    ) {
        val nodeId = bootContext.wipTaskClaim?.nodeId ?: return
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        val submissionId = "strict-evidence-" +
            AtlasHashing.hashText(
                "${bootContext.groupId}|${bootContext.generation}|$nodeId",
            ).take(32)
        if (database.stagingDao().getById(submissionId) != null) return

        val content =
            "Deterministic public evidence for ${bootContext.groupId}, " +
                "round ${bootContext.generation}, node $nodeId."
        val contentHash = AtlasHashing.hashText(content)
        val artifactRef = "kb:$submissionId"
        val submittedAtMs = System.currentTimeMillis()
        val reviewedAtMs = submittedAtMs + 1L
        val submission =
            StagingSubmissionEntity(
                id = submissionId,
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "DOC",
                content = content,
                wipTaskRef = nodeId,
                budgetSignature = "strict-scripted-worker",
                status = "promoted",
                criticAgentId = "strict-scripted-critic",
                initialTrustScore = 1.0,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = submittedAtMs,
                reviewedAtMs = reviewedAtMs,
                promotedArtifactRef = artifactRef,
                promotedArtifactHash = contentHash,
            )
        database.stagingDao().insert(submission)
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = submissionId,
                content = content,
                sourceAgentId = bootContext.agentId,
                generation = bootContext.generation,
                trustScore = 1.0,
                domain = "strict:public-evidence",
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                createdAtMs = reviewedAtMs,
            ),
        )
        ContributionGraphRepository(
            database = database,
            atlasDatabase = substrateFactory.getAtlas(bootContext.runId),
        ).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "strict-decision-$submissionId",
                runId = bootContext.runId,
                groupId = AtlasGroupId(bootContext.groupId),
                round = bootContext.generation,
                submissionId = submissionId,
                submissionContentHash = contentHash,
                resolvedReview =
                    CriticReview(
                        submissionId = submissionId,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = "strict-scripted-critic",
                        initialTrustScore = 1.0,
                    ),
                resultAuthority =
                    ContributionAuthorityRef(
                        kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        authorityId = artifactRef,
                        authorityHash = contentHash,
                    ),
                decidedAt = Instant.fromEpochMilliseconds(reviewedAtMs),
            ).withComputedHash(),
        )
        val preliminaryContribution =
            ContributionCommit(
                commitId = "strict-contribution-$submissionId",
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
                changedStateSummary = "Persisted deterministic public node evidence.",
                evidenceSummary = "Critic-promoted exact evidence.",
                confidence = 1.0,
                unresolvedRisks = emptyList(),
                successorRecommendations = emptyList(),
                costSummary = NodeEvidenceCostSummary(totalCredits = 1.0),
                dedupeHash = "",
                committedAt = Instant.fromEpochMilliseconds(reviewedAtMs + 1L),
            )
        WorkerExecutionRepository(database).commit(
            preliminaryContribution.copy(
                dedupeHash = AtlasHashing.hashContributionDedupe(preliminaryContribution),
            ),
        )
    }

    private fun EvoMoliorEvent.runIdOrNull(): String? =
        when (this) {
            is EvoMoliorEvent.RunCompletedProvisional -> runId
            is EvoMoliorEvent.RunRecovered -> runId
            is EvoMoliorEvent.RunCompleted -> runId
            is EvoMoliorEvent.RunAborted -> runId
            else -> null
        }

    private fun EvoMoliorEvent.isTerminal(): Boolean =
        this is EvoMoliorEvent.RunCompletedProvisional ||
            this is EvoMoliorEvent.RunRecovered ||
            this is EvoMoliorEvent.RunCompleted ||
            this is EvoMoliorEvent.RunAborted

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                entries[entry.name] = zip.readBytes().decodeToString()
                zip.closeEntry()
            }
        }
        return entries
    }
}
