@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.competition.atlas.AtlasDisclosureStageAuthority
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.disclosure.DisclosureAtlasProjectionAdapter
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.grader.ExactGraderBundleResolver
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.YieldConstraintPolicy
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.FitnessPlane
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.CausalFitnessRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EvoMoliorExactNodeCaptureAcceptanceTest {
    private val runId = "run-exact-capture"
    private val atlasId = "atlas-exact-capture"
    private val nodeId = NodeId("node-exact-capture")
    private val futureNodeId = NodeId("node-future-disclosure")
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")

    @Test
    fun `slower highest exact grade captures schedules next round and emits hash complete event`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("exact-node-capture-api-").toFile()
            )
            val migration = migration()
            AtlasTransactionRepository(factory).initialize(migration)
            val captureRepository = NodeCaptureRepository(factory)
            val packets = listOf(groupA, groupB).associateWith { group ->
                captureRepository.appendEvidencePacket(packet(migration.atlas, group))
            }
            val bundles = listOf(
                bundle(migration.atlas, packets.getValue(groupA), score = 0.76),
                bundle(migration.atlas, packets.getValue(groupB), score = 0.93)
            )
            val service = service(factory)
            setPrivate(service, "activeWorkerAtlasId", atlasId)
            setPrivate(
                service,
                "activeYieldConstraintPolicy",
                YieldConstraintPolicy(
                    policyId = "policy-1",
                    policyHash = "",
                    perGroupRoundCap = 5.0,
                    totalRoundCap = 8.0,
                    maximumActiveYieldFraction = 1.0,
                    repeatOwnershipDiminishingFactor = 0.8,
                    mandatoryFrontierBonus = 0.1,
                    independentValidationCredit = 0.5
                ).withComputedHash()
            )
            val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                service.events.first {
                    it is EvoMoliorEvent.ResourceNodeClaimed &&
                        it.captureDecisionHash != null
                } as EvoMoliorEvent.ResourceNodeClaimed
            }

            service.publishGradedResourceNodeClaims(
                config = RunConfig(
                    runId = runId,
                    objective = "Resolve exact evidence.",
                    groupCount = 2,
                    groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                    scoringDimensions = listOf(
                        ScoringDimension("summary", "Summary", "Generic summary only.", 1.0)
                    ),
                    openRouterApiKey = "test"
                ),
                gradingResult = GradingResult(
                    roundIndex = 1,
                    scores = listOf(
                        RoundScore(groupA.value, 1, emptyList(), 76.0),
                        RoundScore(groupB.value, 1, emptyList(), 93.0)
                    ),
                    winnerId = groupB.value,
                    nodeGradeBundles = bundles
                ),
                round = 1
            )

            val event = eventDeferred.await()
            assertEquals(groupB.value, event.groupId)
            assertEquals(packets.getValue(groupB).packetHash, event.evidencePacketHash)
            assertEquals(bundles[1].bundleHash, event.scoreBundleHash)
            assertEquals(migration.atlas.atlasHash, event.atlasHash)
            assertEquals(migration.atlas.nodes.getValue(nodeId).payloadHash, event.nodePayloadHash)
            assertNotNull(event.captureDecisionHash)
            assertNotNull(event.yieldScheduleHash)
            val schedules = captureRepository.yieldSchedulesForActivation(runId, groupB.value, 2)
            assertEquals(1, schedules.size)
            assertEquals(event.yieldScheduleHash, schedules.single().scheduleHash)
            assertTrue(
                captureRepository.yieldSchedulesForActivation(runId, groupB.value, 1).isEmpty()
            )
            val persisted = factory.get(runId, groupB.value).eventLogDao()
                .getLatestByType(runId, groupB.value, EventTypes.NODE_CLAIMED, 1)
                .single()
            assertTrue(persisted.payloadJson.contains(event.captureDecisionHash!!))
            assertTrue(persisted.payloadJson.contains(event.scoreBundleHash!!))
        }

    @Test
    fun `production sealing includes inline Critic promotions outside pending snapshot only for the same round`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("production-packet-sealing-api-").toFile()
            )
            val migration = migration(includeFutureStage = true)
            AtlasTransactionRepository(factory).initialize(migration)
            val service = service(factory)
            setPrivate(service, "activeWorkerAtlasId", atlasId)
            val goodDb = factory.get(runId, groupA.value)
            val goodSubmission = promotedSubmission("submission-good", groupA)
            goodDb.stagingDao().insert(goodSubmission)
            goodDb.stagingDao().insert(
                promotedSubmission("round-summary", groupA).copy(wipTaskRef = null)
            )
            recordCanonicalPromotion(factory, goodSubmission, groupA)
            WorkerExecutionRepository(goodDb).commit(
                contribution(
                    id = "contribution-good",
                    group = groupA,
                    cost = 3.5,
                    artifactId = goodSubmission.id
                )
            )
            val priorRoundSubmission = promotedSubmission("submission-prior-round", groupA).copy(
                generation = 0,
                submittedAtMs = 0,
                reviewedAtMs = 1
            )
            goodDb.stagingDao().insert(priorRoundSubmission)
            recordCanonicalPromotion(factory, priorRoundSubmission, groupA)
            WorkerExecutionRepository(goodDb).commit(
                contribution(
                    id = "contribution-prior-round",
                    group = groupA,
                    cost = 2.0,
                    round = 0,
                    artifactId = priorRoundSubmission.id
                )
            )
            val config = RunConfig(
                runId = runId,
                objective = "Resolve exact evidence.",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                scoringDimensions = listOf(
                    ScoringDimension("summary", "Summary", "Generic summary only.", 1.0)
                ),
                openRouterApiKey = "test"
            )

            val packets = service.sealCriticAcceptedNodeEvidence(
                config,
                groupA.value,
                round = 1
            )

            assertEquals(1, packets.size)
            assertEquals(listOf("submission-good"), packets.single().acceptedArtifactIds)
            assertEquals(3.5, packets.single().cost.totalCredits)
            assertTrue(packets.single().hasValidHash())

            val failClosedDb = factory.get(runId, groupB.value)
            val uncommittedSubmission = promotedSubmission("submission-uncommitted", groupB)
            failClosedDb.stagingDao().insert(uncommittedSubmission)
            recordCanonicalPromotion(factory, uncommittedSubmission, groupB)
            assertTrue(
                service.sealCriticAcceptedNodeEvidence(
                    config,
                    groupB.value,
                    round = 1
                ).isEmpty()
            )

            val missingCostSubmission = promotedSubmission("submission-missing-cost", groupB)
            failClosedDb.stagingDao().insert(missingCostSubmission)
            recordCanonicalPromotion(factory, missingCostSubmission, groupB)
            WorkerExecutionRepository(failClosedDb).commit(
                contribution(
                    id = "contribution-missing-cost",
                    group = groupB,
                    cost = null,
                    artifactId = missingCostSubmission.id
                )
            )
            assertTrue(
                service.sealCriticAcceptedNodeEvidence(
                    config,
                    groupB.value,
                    round = 1
                ).isEmpty()
            )
        }

    @Test
    fun `applied yield records artifact lineage and later exact score delta`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("production-reward-lineage-api-").toFile()
        )
        val migration = migration()
        AtlasTransactionRepository(factory).initialize(migration)
        val captureRepository = NodeCaptureRepository(factory)
        val firstPacket = captureRepository.appendEvidencePacket(
            packet(migration.atlas, groupA, round = 1)
        )
        val firstBundle = bundle(migration.atlas, firstPacket, score = 0.76, round = 1)
        val service = service(factory)
        setPrivate(service, "activeWorkerAtlasId", atlasId)
        setPrivate(
            service,
            "activeYieldConstraintPolicy",
            YieldConstraintPolicy(
                policyId = "lineage-policy",
                policyHash = "",
                perGroupRoundCap = 5.0,
                totalRoundCap = 5.0,
                maximumActiveYieldFraction = 1.0,
                repeatOwnershipDiminishingFactor = 0.8,
                mandatoryFrontierBonus = 0.0,
                independentValidationCredit = 0.0
            ).withComputedHash()
        )
        val config = RunConfig(
            runId = runId,
            objective = "Resolve exact evidence.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            scoringDimensions = listOf(
                ScoringDimension("summary", "Summary", "Generic summary only.", 1.0)
            ),
            openRouterApiKey = "test"
        )

        service.publishGradedResourceNodeClaims(
            config,
            GradingResult(
                roundIndex = 1,
                scores = emptyList(),
                winnerId = groupA.value,
                nodeGradeBundles = listOf(firstBundle)
            ),
            round = 1
        )
        val schedule = captureRepository
            .yieldSchedulesForActivation(runId, groupA.value, 2)
            .single()
        assertEquals(mapOf(groupA.value to 2.0), service.applyDueNodeYields(
            runId,
            listOf(groupA.value),
            round = 2
        ))
        val application = assertNotNull(
            captureRepository.yieldApplicationForSchedule(runId, schedule.scheduleId)
        )
        val promoted = promotedSubmission("reward-artifact", groupA)
        factory.get(runId, groupA.value).stagingDao().insert(promoted)
        service.appendRewardArtifactLineage(runId, groupA.value, round = 2, promoted)

        val beforeObservation = captureRepository.rewardUseEventsForApplication(
            runId,
            application.applicationHash
        )
        assertEquals(2, beforeObservation.size)
        val spend = beforeObservation.single { it.amountSpent > 0.0 }
        val artifact = beforeObservation.single { it.downstreamArtifactIds.isNotEmpty() }
        assertEquals(spend.eventHash, artifact.parentEventHash)
        assertEquals(0.0, artifact.amountSpent)
        assertTrue("reward-artifact" in artifact.downstreamArtifactIds)
        assertTrue(beforeObservation.none { it.observedScoreDelta != null })

        val laterPacket = captureRepository.appendEvidencePacket(
            packet(
                migration.atlas,
                groupA,
                round = 3,
                artifactIds = listOf("reward-artifact", "unrelated-artifact")
            )
        )
        service.publishGradedResourceNodeClaims(
            config,
            GradingResult(
                roundIndex = 3,
                scores = emptyList(),
                winnerId = groupA.value,
                nodeGradeBundles = listOf(
                    bundle(migration.atlas, laterPacket, score = 0.91, round = 3)
                )
            ),
            round = 3
        )

        val lineage = captureRepository.rewardUseEventsForApplication(
            runId,
            application.applicationHash
        )
        assertEquals(3, lineage.size)
        val observation = lineage.single { it.observedScoreDelta != null }
        assertEquals(0.15, assertNotNull(observation.observedScoreDelta), absoluteTolerance = 1e-9)
        assertEquals(3, observation.observationRound)
        assertEquals(artifact.eventHash, observation.parentEventHash)
        assertEquals(0.0, observation.amountSpent)
    }

    @Test
    fun `InvalidVerdictNoMutationTest disclosure leak pressure capture yields and fitness remain unchanged`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("invalid-exact-round-authority-api-").toFile()
            )
            val migration = migration()
            val atlasRepository = AtlasTransactionRepository(factory)
            atlasRepository.initialize(migration)
            val captureRepository = NodeCaptureRepository(factory)
            val packets = listOf(groupA, groupB).associateWith { group ->
                captureRepository.appendEvidencePacket(packet(migration.atlas, group))
            }
            val validSiblingBundles = listOf(
                bundle(migration.atlas, packets.getValue(groupA), score = 0.81),
                bundle(migration.atlas, packets.getValue(groupB), score = 0.94)
            )
            val service = service(factory)
            val coordinator = GroupCoordinator(
                groupIds = listOf(groupA.value, groupB.value),
                dimensions = listOf(
                    ScoringDimension("summary", "Summary", "Generic summary only.", 1.0)
                )
            )
            val disclosureEngine = ProgressiveDisclosureEngine(
                stages = DisclosureAtlasProjectionAdapter.fromProjection(
                    migration.projections.disclosure
                ),
                stageAuthority = AtlasDisclosureStageAuthority(
                    runId = runId,
                    atlasId = atlasId,
                    repository = atlasRepository
                )
            ).also { engine ->
                engine.registerGroup(groupA.value)
                engine.registerGroup(groupB.value)
            }
            val invalidVerdict = ExactGraderBundleResolver().resolve(
                runId = runId,
                round = 1,
                expectedGraderGroupIds = setOf("grader-a", "grader-b"),
                expectedTargetGroupIds = setOf(groupA, groupB),
                bundles = emptyList()
            )
            assertEquals(ExactGraderRoundStatus.INVALID, invalidVerdict.status)
            setPrivate(service, "activeWorkerAtlasId", atlasId)
            setPrivate(service, "activeCoordinator", coordinator)
            setPrivate(service, "activeDisclosureEngine", disclosureEngine)
            setPrivate(
                service,
                "activeYieldConstraintPolicy",
                YieldConstraintPolicy(
                    policyId = "invalid-round-policy",
                    policyHash = "",
                    perGroupRoundCap = 5.0,
                    totalRoundCap = 8.0,
                    maximumActiveYieldFraction = 1.0,
                    repeatOwnershipDiminishingFactor = 0.8,
                    mandatoryFrontierBonus = 0.1,
                    independentValidationCredit = 0.5
                ).withComputedHash()
            )
            mapField<Int, List<NodeGradeBundle>>(service, "nodeGradeBundlesByRound")[1] =
                validSiblingBundles
            mapField<Int, ExactGraderRoundVerdict>(service, "exactGraderVerdictsByRound")[1] =
                invalidVerdict
            val config = RunConfig(
                runId = runId,
                objective = "Resolve exact evidence.",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                scoringDimensions = listOf(
                    ScoringDimension("summary", "Summary", "Generic summary only.", 1.0)
                ),
                openRouterApiKey = "test"
            )
            val rejectedSubmission = promotedSubmission("fitness-candidate", groupA).copy(
                status = "rejected",
                rejectionReason = "Unsupported",
                promotedArtifactRef = null,
                promotedArtifactHash = null
            )
            val groupDatabase = factory.get(runId, groupA.value)
            groupDatabase.stagingDao().insert(rejectedSubmission)
            val rejectedContribution = contribution(
                id = "fitness-candidate-contribution",
                group = groupA,
                cost = 1.0,
                artifactId = rejectedSubmission.id
            )
            WorkerExecutionRepository(groupDatabase).commit(rejectedContribution)
            ContributionGraphRepository(
                groupDatabase,
                factory.getAtlas(runId)
            ).apply {
                recordContributionOrigin(rejectedContribution)
                recordCriticDecision(
                    CriticDecisionRecord(
                        decisionId = "fitness-candidate-decision",
                        runId = runId,
                        groupId = groupA,
                        round = 1,
                        submissionId = rejectedSubmission.id,
                        submissionContentHash = AtlasHashing.hashText(
                            rejectedSubmission.content
                        ),
                        resolvedReview = CriticReview(
                            submissionId = rejectedSubmission.id,
                            outcome = CriticOutcome.REJECT,
                            criticAgentId = requireNotNull(rejectedSubmission.criticAgentId),
                            rejectionReason = rejectedSubmission.rejectionReason
                        ),
                        resultAuthority = null,
                        decidedAt = Instant.fromEpochMilliseconds(
                            requireNotNull(rejectedSubmission.reviewedAtMs)
                        )
                    ).withComputedHash()
                )
            }
            val gradingResult = GradingResult(
                roundIndex = 1,
                scores = listOf(
                    RoundScore(groupA.value, 1, emptyList(), 81.0),
                    RoundScore(groupB.value, 1, emptyList(), 94.0)
                ),
                winnerId = groupB.value,
                nodeGradeBundles = validSiblingBundles
            )

            val before = authoritySnapshot(
                factory = factory,
                atlasRepository = atlasRepository,
                captureRepository = captureRepository,
                disclosureEngine = disclosureEngine,
                coordinator = coordinator,
                round = 1
            )
            assertTrue(before.causalFitnessHashes.values.all { it.isEmpty() })

            service::class.declaredFunctions
                .first { it.name == "processAuthoritativeRoundMutationBoundary" }
                .apply { isAccessible = true }
                .callSuspend(
                    service,
                    config,
                    listOf(groupA.value, groupB.value),
                    gradingResult,
                    1,
                    migration.atlas
                )

            val after = authoritySnapshot(
                factory = factory,
                atlasRepository = atlasRepository,
                captureRepository = captureRepository,
                disclosureEngine = disclosureEngine,
                coordinator = coordinator,
                round = 1
            )
            assertEquals(before.projections.disclosure, after.projections.disclosure)
            assertEquals(before.engineStageIndices, after.engineStageIndices)
            assertEquals(before.engineLeakMultipliers, after.engineLeakMultipliers)
            assertEquals(before.leakCeilings, after.leakCeilings)
            assertEquals(before.projections.resources, after.projections.resources)
            assertEquals(before.projections.deliverables, after.projections.deliverables)
            assertEquals(before.executionStates, after.executionStates)
            assertEquals(before.gradeBundleHashes, after.gradeBundleHashes)
            assertEquals(before.captureDecisionHashes, after.captureDecisionHashes)
            assertEquals(before.yieldScheduleHashes, after.yieldScheduleHashes)
            assertEquals(before.yieldApplicationHashes, after.yieldApplicationHashes)
            assertEquals(before.causalFitnessHashes, after.causalFitnessHashes)
            assertEquals(before.rewardObservationHashes, after.rewardObservationHashes)
            assertEquals(
                invalidVerdict,
                mapField<Int, ExactGraderRoundVerdict>(
                    service,
                    "exactGraderVerdictsByRound"
                ).getValue(1)
            )
            assertTrue(
                captureRepository.gradeBundlesForRun(runId).none { bundle ->
                    bundle.bundleHash in validSiblingBundles.map(NodeGradeBundle::bundleHash)
                }
            )
        }

    private fun service(factory: SubstrateDatabaseFactory) = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ -> error("No lifecycle in focused test") },
        groupCoordinatorProvider = { groups, dimensions -> GroupCoordinator(groups, dimensions) },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = factory,
        metaPlannerRunner = MetaPlannerRunner()
    )

    private fun setPrivate(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> mapField(target: Any, name: String): MutableMap<K, V> =
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(target) as MutableMap<K, V>

    private suspend fun authoritySnapshot(
        factory: SubstrateDatabaseFactory,
        atlasRepository: AtlasTransactionRepository,
        captureRepository: NodeCaptureRepository,
        disclosureEngine: ProgressiveDisclosureEngine,
        coordinator: GroupCoordinator,
        round: Int
    ): AuthoritySnapshot {
        val groupIds = listOf(groupA.value, groupB.value)
        return AuthoritySnapshot(
            projections = requireNotNull(atlasRepository.currentProjections(runId, atlasId)),
            engineStageIndices = groupIds.associateWith {
                disclosureEngine.currentStage(it).stageIndex
            },
            engineLeakMultipliers = groupIds.associateWith(
                disclosureEngine::leakMultiplierFor
            ),
            leakCeilings = groupIds.associateWith(coordinator.leakOracle::ceilingFor),
            executionStates = atlasRepository.currentStates(runId, atlasId),
            gradeBundleHashes = captureRepository.gradeBundlesForRun(runId)
                .map(NodeGradeBundle::bundleHash),
            captureDecisionHashes = captureRepository.captureDecisionsForRun(runId)
                .map { it.decisionHash },
            yieldScheduleHashes = groupIds.flatMap { groupId ->
                captureRepository.yieldSchedulesForActivation(runId, groupId, round + 1)
                    .map { it.scheduleHash }
            },
            yieldApplicationHashes = captureRepository.yieldApplicationsForRun(runId)
                .map { it.applicationHash },
            causalFitnessHashes = groupIds.flatMap { groupId ->
                FitnessPlane.entries.map { plane ->
                    "$groupId:${plane.name}" to CausalFitnessRepository(
                        factory.get(runId, groupId)
                    ).forPlane(runId, groupId, round, plane).map { it.recordHash }
                }
            }.toMap(),
            rewardObservationHashes = captureRepository.rewardUseEventsForRun(runId)
                .map { it.eventHash }
        )
    }

    private data class AuthoritySnapshot(
        val projections: AtlasProjectionSet,
        val engineStageIndices: Map<String, Int>,
        val engineLeakMultipliers: Map<String, Double>,
        val leakCeilings: Map<String, Int>,
        val executionStates: Map<NodeId, NodeExecutionState>,
        val gradeBundleHashes: List<String>,
        val captureDecisionHashes: List<String>,
        val yieldScheduleHashes: List<String>,
        val yieldApplicationHashes: List<String>,
        val causalFitnessHashes: Map<String, List<String>>,
        val rewardObservationHashes: List<String>
    )

    private fun migration(includeFutureStage: Boolean = false): PreparedAtlasMigration {
        val receiver = ReceiverContract(
            rawObjective = "Resolve exact evidence.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Choose",
            whyNeeded = "Reduce uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
            finalDeliverableContract = FinalOutputSpec("result.json", "application/json"),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "verified",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Establish exact evidence.",
            atomicQuestion = "What evidence resolves the limitation?",
            proofContract = ProofContract(
                ProofMethod.SOURCE_CORROBORATION,
                setOf("Correct")
            ),
            deliverableContract = NodeDeliverableContract(
                setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0)
        ).withComputedPayloadHash()
        val futureNode = LimitationNode(
            id = futureNodeId,
            kind = NodeKind.DELIVERY,
            layer = 1,
            parentIds = setOf(nodeId),
            prerequisiteIds = setOf(nodeId),
            receiverWhyRef = "delivery",
            limitationStatement = "The accepted evidence is not yet integrated.",
            capabilityTarget = "Integrate the evidence into a receiver-facing result.",
            atomicQuestion = "How should the accepted evidence determine the result?",
            proofContract = ProofContract(
                ProofMethod.RECEIVER_VALIDATION,
                setOf("The result is supported by the accepted evidence")
            ),
            deliverableContract = NodeDeliverableContract(
                setOf(ArtifactType.FINAL_DELIVERABLE)
            ),
            revealPolicy = RevealPolicy("stage-1", RevealMode.STAGE_PASS),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, credits = 1.0)
        ).withComputedPayloadHash()
        val nodes = if (includeFutureStage) {
            linkedMapOf(nodeId to node, futureNodeId to futureNode)
        } else {
            mapOf(nodeId to node)
        }
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Initial",
            nodeIds = setOf(nodeId),
            mandatoryNodeIds = setOf(nodeId),
            evidenceCoverageThreshold = 0.8,
            integrationReadinessThreshold = 0.7,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload("stage-0", setOf(nodeId), nodes)
        )
        val stages = if (includeFutureStage) {
            listOf(
                stage,
                DisclosureStage(
                    stageId = "stage-1",
                    stageIndex = 1,
                    title = "Integration",
                    nodeIds = setOf(futureNodeId),
                    mandatoryNodeIds = setOf(futureNodeId),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-1",
                        setOf(futureNodeId),
                        nodes
                    )
                )
            )
        } else {
            listOf(stage)
        }
        val atlas = NodeAtlas(
            atlasId = atlasId,
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = stages
        ).withComputedHash()
        val groups = listOf(groupA.value, groupB.value)
        return PreparedAtlasMigration(
            receiverContract = receiver,
            atlas = atlas,
            executionStates = nodes.keys.map { candidateNodeId ->
                NodeExecutionState(
                    candidateNodeId,
                    0,
                    revealedToGroups = if (candidateNodeId == nodeId) {
                        setOf(groupA, groupB)
                    } else {
                        emptySet()
                    }
                )
            },
            projections = AtlasProjectionSet(
                wip = emptyList(),
                deliverables = groups.map { groupId ->
                    DeliverableSkeletonTree(
                        runId = runId,
                        groupId = groupId,
                        rootNodeId = nodeId.value,
                        nodes = listOf(DeliverableSkeletonNode(nodeId = nodeId.value, title = "Evidence")),
                        createdAtMs = 1,
                        updatedAtMs = 1
                    )
                },
                resources = ResourceProjection(
                    runId,
                    listOf(
                        ResourceProjectionNode(
                            "resource-1",
                            nodeId,
                            "Evidence",
                            "WEB_CACHE",
                            1.0
                        )
                    )
                ),
                disclosure = DisclosureProjection(
                    runId,
                    stages,
                    groups.associateWith { 0 }
                ),
                mappings = groups.map { groupId ->
                    LegacyNodeMapping(
                        LegacyNodeSource.DELIVERABLE,
                        nodeId.value,
                        groupId,
                        nodeId
                    )
                } + LegacyNodeMapping(
                    LegacyNodeSource.RESOURCE,
                    "resource-1",
                    runId,
                    nodeId
                )
            )
        )
    }

    private fun packet(
        atlas: NodeAtlas,
        group: AtlasGroupId,
        round: Int = 1,
        artifactIds: List<String> = listOf("artifact-${group.value}")
    ) = NodeEvidencePacket(
        packetId = "packet-${group.value}-$round",
        runId = runId,
        round = round,
        groupId = group,
        nodeId = nodeId,
        atlasVersion = atlas.version,
        atlasHash = atlas.atlasHash,
        nodePayloadHash = atlas.nodes.getValue(nodeId).payloadHash,
        acceptedArtifactIds = artifactIds,
        provenanceRootHash = "provenance-${group.value}",
        cost = NodeEvidenceCostSummary(totalCredits = 1.0),
        sealedAtEpochMillis = 10
    ).withComputedHash()

    private fun promotedSubmission(id: String, group: AtlasGroupId) =
        StagingSubmissionEntity(
            id = id,
            agentId = "agent-${group.value}",
            role = "Researcher",
            type = "DOC",
            content = "Exact accepted evidence for ${group.value}.",
            wipTaskRef = nodeId.value,
            budgetSignature = "signed",
            status = "promoted",
            criticAgentId = "critic-1",
            initialTrustScore = 0.9,
            generation = 1,
            groupId = group.value,
            runId = runId,
            submittedAtMs = 1,
            reviewedAtMs = 2,
            promotedArtifactRef = "kb:$id",
            promotedArtifactHash = AtlasHashing.hashText(
                "Exact accepted evidence for ${group.value}."
            )
        )

    private fun contribution(
        id: String,
        group: AtlasGroupId,
        cost: Double?,
        round: Int = 1,
        artifactId: String
    ) =
        ContributionCommit(
            commitId = id,
            runId = runId,
            groupId = group,
            round = round,
            agentId = AtlasAgentId("agent-${group.value}"),
            nodeId = nodeId,
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = listOf(artifactId),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = emptyList(),
            changedStateSummary = "Produced exact accepted evidence.",
            evidenceSummary = "Critic-promoted evidence.",
            confidence = 0.9,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = cost?.let { NodeEvidenceCostSummary(totalCredits = it) },
            dedupeHash = "",
            committedAt = Instant.parse("2026-07-11T12:00:00Z")
        ).let { preliminary ->
            preliminary.copy(dedupeHash = AtlasHashing.hashContributionDedupe(preliminary))
        }

    private suspend fun recordCanonicalPromotion(
        factory: SubstrateDatabaseFactory,
        submission: StagingSubmissionEntity,
        group: AtlasGroupId
    ) {
        val database = factory.get(runId, group.value)
        val artifactId = requireNotNull(submission.promotedArtifactRef)
        val contentHash = requireNotNull(submission.promotedArtifactHash)
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = artifactId.removePrefix("kb:"),
                content = submission.content,
                sourceAgentId = submission.agentId,
                generation = submission.generation,
                domain = "evidence",
                groupId = group.value,
                runId = runId,
                createdAtMs = requireNotNull(submission.reviewedAtMs)
            )
        )
        ContributionGraphRepository(database, factory.getAtlas(runId)).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "decision:${submission.id}",
                runId = runId,
                groupId = group,
                round = submission.generation,
                submissionId = submission.id,
                submissionContentHash = AtlasHashing.hashText(submission.content),
                resolvedReview = CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = requireNotNull(submission.criticAgentId),
                    initialTrustScore = requireNotNull(submission.initialTrustScore)
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = artifactId,
                    authorityHash = contentHash
                ),
                decidedAt = Instant.fromEpochMilliseconds(requireNotNull(submission.reviewedAtMs))
            ).withComputedHash()
        )
    }

    private fun bundle(
        atlas: NodeAtlas,
        packet: NodeEvidencePacket,
        score: Double,
        round: Int = 1
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(nodeId)
        return NodeGradeBundle(
            bundleId = "bundle-${packet.groupId.value}-$round",
            runId = runId,
            round = round,
            groupId = packet.groupId,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = packet.packetId,
            evidencePacketHash = packet.packetHash,
            criteriaHash = AtlasHashing.hashNodeGradingCriteria(node),
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            scoreVector = NodeScoreVector(
                score,
                score,
                score,
                score,
                score,
                score,
                score,
                score,
                hardFailure = false,
                confidence = score
            ),
            normalizedScore = score,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            requiredQuorum = 2,
            graderIds = setOf("grader-1", "grader-2"),
            completedGraderIds = setOf("grader-1", "grader-2"),
            graderResponseHashes = setOf(
                "response-${packet.groupId.value}-1",
                "response-${packet.groupId.value}-2"
            )
        ).withComputedHash()
    }
}
