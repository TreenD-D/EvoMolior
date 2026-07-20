@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.ResearchClaimSupportCoverageContract
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.RewardUseLineageEvent
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.NodeBid
import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfiler
import com.achulkov.evomolior.core.kg.KgFactAttemptAuthority
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.kg.KgFactAttemptRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class RepositoryRunMetricCollectorTest {
    @Test
    fun `repository collector exports a complete sparse run without dashboard inference`() = runTest {
        val factory = factory()
        val result = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(context(completedThroughRound = -1, graderGroupIds = emptyList()))

        assertEquals(21, result.metrics.observations.size)
        assertEquals(
            MetricAvailability.NOT_APPLICABLE,
            result.metrics.observations.single {
                it.metricId == "claim_grounding_coverage"
            }.status,
        )
        assertEquals(
            MetricAvailability.NOT_APPLICABLE,
            result.metrics.observations.single {
                it.metricId == "score_replay_error"
            }.status,
        )
    }

    @Test
    fun `persisted portfolios produce exact micro jaccard overlap`() = runTest {
        val factory = factory()
        val groupZero = WorkerExecutionRepository(factory.get("run-1", "group-0"))
        val groupOne = WorkerExecutionRepository(factory.get("run-1", "group-1"))
        groupZero.appendNodePortfolio(
            portfolio("group-0", listOf("node-a", "node-b")),
        )
        groupOne.appendNodePortfolio(
            portfolio("group-1", listOf("node-b", "node-c")),
        )

        val observation = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(context()).metrics.observations.single {
            it.metricId == "group_frontier_overlap"
        }

        assertEquals(MetricAvailability.AVAILABLE, observation.status)
        assertEquals(1.0, observation.numerator)
        assertEquals(3.0, observation.denominator)
        assertEquals(1.0 / 3.0, observation.value)
        assertEquals(2, observation.evidenceRefs.size)
    }

    @Test
    fun `typed KG attempts preserve accepted and rejected denominator authority`() = runTest {
        val factory = factory()
        val repository = KgFactAttemptRepository(factory.get("run-1", "group-0"))
        repository.append(
            KgFactAttemptAuthority.classify(
                runId = "run-1",
                groupId = "group-0",
                submissionId = "fact-accepted",
                agentId = "agent-0",
                generation = 0,
                content = "SUBJECT: A\nRELATION: CONCERNS\nOBJECT: B",
                allowedRelations = listOf("CONCERNS"),
                recordedAt = Instant.parse("2026-07-19T10:00:00Z"),
            ),
        )
        repository.append(
            KgFactAttemptAuthority.classify(
                runId = "run-1",
                groupId = "group-0",
                submissionId = "fact-rejected",
                agentId = "agent-1",
                generation = 0,
                content = "SUBJECT: A\nRELATION: UNKNOWN\nOBJECT: B",
                allowedRelations = listOf("CONCERNS"),
                recordedAt = Instant.parse("2026-07-19T10:01:00Z"),
            ),
        )

        val observation = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(context()).metrics.observations.single {
            it.metricId == "kg_syntactic_acceptance"
        }

        assertEquals(MetricAvailability.AVAILABLE, observation.status)
        assertEquals(1.0, observation.numerator)
        assertEquals(2.0, observation.denominator)
        assertEquals(0.5, observation.value)
    }

    @Test
    fun `declared claim coverage distinguishes missing infrastructure from zero eligibility`() = runTest {
        val factory = factory()
        val outputSpec = FinalOutputSpec(
            relativePath = "result.md",
            mediaType = "text/markdown",
            validation = FinalOutputValidationContract(
                researchClaimSupportCoverage = ResearchClaimSupportCoverageContract(
                    minimumCoverage = 0.5,
                ),
            ),
        )
        val missing = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(context(finalOutputSpec = outputSpec))
        val measured = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(
            context(
                finalOutputSpec = outputSpec,
                claimLedgerJsonl = """
                    {"claim":"one","supporting_sources":["source-a"],"used_in_final_answer":true}
                    {"claim":"two","supporting_sources":[],"used_in_final_answer":true}
                """.trimIndent(),
            ),
        )

        assertEquals(
            setOf("claim_grounding_coverage", "score_replay_error"),
            missing.missingMeasurementInfrastructureMetricIds,
        )
        val observation = measured.metrics.observations.single {
            it.metricId == "claim_grounding_coverage"
        }
        assertEquals(MetricAvailability.AVAILABLE, observation.status)
        assertEquals(0.5, observation.value)
        assertTrue("claim_grounding_coverage" !in measured.missingMeasurementInfrastructureMetricIds)
    }

    @Test
    fun `persisted child replay produces exact zero replay error`() = runTest {
        val factory = factory()
        ScoreReplayAuthorityV1Store(factory).append(
            runId = "run-1",
            observation = ScoreReplayObservationV1(
                round = 0,
                liveVerdictSha256 = "a".repeat(64),
                replayVerdictSha256 = "a".repeat(64),
                maximumAbsoluteError = 0.0,
                exactMatch = true,
            ),
        )

        val result = RunMetricsV1Exporter(
            RepositoryRunMetricCollector(factory),
        ).export(context())
        val observation = result.metrics.observations.single {
            it.metricId == "score_replay_error"
        }

        assertEquals(MetricAvailability.AVAILABLE, observation.status)
        assertEquals(0.0, observation.value)
        assertTrue("score_replay_error" !in result.missingMeasurementInfrastructureMetricIds)
    }

    @Test
    fun `evaluation independence exports stable label and family provider counts`() = runTest {
        val factory = factory()
        val profile = EvaluationIndependenceProfiler.profile(
            workerFamilies = setOf("worker-family"),
            graderFamilies = setOf("grader-family"),
            providers = setOf("provider-a", "provider-b"),
            sharedPromptTemplateFraction = 0.25,
        )
        GraderAuthorityRepository(factory).appendIndependenceProfile("run-1", profile)

        val observation = assertIs<RunMetricCollectionResult.Available>(
            RepositoryRunMetricCollector(factory).collect(
                "evaluation_independence",
                context(),
            ),
        )

        assertEquals(2.0, observation.numerator)
        assertEquals(2.0, observation.denominator)
        assertEquals(1.0, observation.value)
        assertEquals(
            "model-families-per-provider;label=${profile.independenceLabel.name}",
            observation.unit,
        )
    }

    @Test
    fun `reward usefulness requires an exact accepted downstream artifact`() {
        val acceptedSpend = rewardEvent("spend-accepted", amountSpent = 1.0)
        val acceptedChild = rewardEvent(
            id = "child-accepted",
            parentEventHash = acceptedSpend.eventHash,
            downstreamArtifactIds = setOf("accepted-artifact"),
        )
        val scoreOnlySpend = rewardEvent("spend-score-only", amountSpent = 1.0)
        val scoreOnlyChild = rewardEvent(
            id = "child-score-only",
            parentEventHash = scoreOnlySpend.eventHash,
            observedScoreDelta = 0.5,
            observationRound = 2,
        )
        val unacceptedSpend = rewardEvent("spend-unaccepted", amountSpent = 1.0)
        val unacceptedChild = rewardEvent(
            id = "child-unaccepted",
            parentEventHash = unacceptedSpend.eventHash,
            downstreamArtifactIds = setOf("not-accepted"),
        )

        assertEquals(
            1 to 3,
            rewardUsefulnessCounts(
                listOf(
                    acceptedSpend,
                    acceptedChild,
                    scoreOnlySpend,
                    scoreOnlyChild,
                    unacceptedSpend,
                    unacceptedChild,
                ),
                setOf("accepted-artifact"),
            ),
        )
    }

    @Test
    fun `node to final lineage requires every declared component and accepted node ancestry`() = runTest {
        val factory = factory()
        val nodeId = NodeId("node-final")
        val atlas = initializeTestWorkerAtlas(
            factory = factory,
            runId = "run-1",
            nodeIds = setOf(nodeId),
            groupId = "group-0",
            requiredSections = setOf("answer", "evidence"),
            capturedNodeIds = setOf(nodeId),
        )
        val node = atlas.nodes.getValue(nodeId)
        val capture = NodeCaptureDecision(
            decisionId = "capture-final",
            runId = "run-1",
            round = 0,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            candidateBundleHashes = emptySet(),
            resultingOwnerGroupIds = setOf(AtlasGroupId("group-0")),
            resultingCaptureState = CaptureState.CAPTURED,
            decisionType = NodeCaptureDecisionType.CAPTURED,
            winningScore = 0.9,
            yieldEligible = true,
            reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE,
        ).withComputedHash()
        NodeCaptureRepository(factory).appendCaptureDecision(capture)
        val groupDatabase = factory.get("run-1", "group-0")
        groupDatabase.stagingDao().insert(
            StagingSubmissionEntity(
                id = "final-writer-submission",
                agentId = "writer",
                role = "Builder",
                type = "DOC",
                content = """{"answer":"measured"}""",
                budgetSignature = "signed",
                status = "promoted",
                generation = 1,
                groupId = "group-0",
                runId = "run-1",
                submittedAtMs = 1L,
            ),
        )
        val repository = ContributionGraphRepository(
            groupDatabase,
            factory.getAtlas("run-1"),
        )
        suspend fun appendComponent(
            componentId: String,
            mappedToAcceptedNode: Boolean,
        ) {
            repository.recordFinalDeliverableReference(
                FinalDeliverableReferenceRecord(
                    referenceId = "final-reference-$componentId-marker",
                    runId = "run-1",
                    groupId = AtlasGroupId("group-0"),
                    round = 0,
                    relativePath = "result.json",
                    mediaType = "application/json",
                    contentHash = "output-hash",
                    writerAgentId = "writer",
                    writerSubmissionId = "final-writer-submission",
                    nodeId = null,
                    sourceAuthority = ContributionAuthorityRef(
                        ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                        capture.decisionId,
                        capture.decisionHash,
                    ),
                    referencedAt = Instant.parse("2026-07-19T10:00:00Z"),
                    componentId = componentId,
                ).withComputedHash(),
            )
            if (mappedToAcceptedNode) {
                repository.recordFinalDeliverableReference(
                    FinalDeliverableReferenceRecord(
                        referenceId = "final-reference-$componentId-node",
                        runId = "run-1",
                        groupId = AtlasGroupId("group-0"),
                        round = 0,
                        relativePath = "result.json",
                        mediaType = "application/json",
                        contentHash = "output-hash",
                        writerAgentId = "writer",
                        writerSubmissionId = "final-writer-submission",
                        nodeId = nodeId.value,
                        sourceAuthority = ContributionAuthorityRef(
                            ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                            capture.decisionId,
                            capture.decisionHash,
                        ),
                        referencedAt = Instant.parse("2026-07-19T10:00:00Z"),
                        componentId = componentId,
                    ).withComputedHash(),
                )
            }
        }
        appendComponent("answer", mappedToAcceptedNode = true)
        val metricContext = context(
            workerGroupIds = listOf("group-0"),
            atlasId = atlas.atlasId,
        )

        val incomplete = assertIs<RunMetricCollectionResult.Unavailable>(
            RepositoryRunMetricCollector(factory).collect(
                "node_to_final_lineage",
                metricContext,
            ),
        )
        assertEquals(
            RunMetricUnavailableCause.MISSING_MEASUREMENT_INFRASTRUCTURE,
            incomplete.cause,
        )

        appendComponent("evidence", mappedToAcceptedNode = false)
        val partiallyBacked = assertIs<RunMetricCollectionResult.Available>(
            RepositoryRunMetricCollector(factory).collect(
                "node_to_final_lineage",
                metricContext,
            ),
        )
        assertEquals(1.0, partiallyBacked.numerator)
        assertEquals(2.0, partiallyBacked.denominator)
        assertEquals(0.5, partiallyBacked.value)

        appendComponent("evidence", mappedToAcceptedNode = true)
        val complete = assertIs<RunMetricCollectionResult.Available>(
            RepositoryRunMetricCollector(factory).collect(
                "node_to_final_lineage",
                metricContext,
            ),
        )
        assertEquals(2.0, complete.numerator)
        assertEquals(2.0, complete.denominator)
        assertEquals(1.0, complete.value)

        NodeCaptureRepository(factory).appendCaptureDecision(
            capture.copy(
                decisionId = "capture-final-current",
                round = 1,
                decisionType = NodeCaptureDecisionType.RECAPTURED,
                decisionHash = "",
            ).withComputedHash(),
        )
        val stalePersistedAncestry = assertIs<RunMetricCollectionResult.Available>(
            RepositoryRunMetricCollector(factory).collect(
                "node_to_final_lineage",
                metricContext,
            ),
        )
        assertEquals(0.0, stalePersistedAncestry.numerator)
        assertEquals(2.0, stalePersistedAncestry.denominator)
        assertEquals(0.0, stalePersistedAncestry.value)
    }

    private fun factory() = SubstrateDatabaseFactory(
        createTempDirectory("repository-run-metrics-").toFile(),
    )

    private fun context(
        completedThroughRound: Int = 0,
        graderGroupIds: List<String> = listOf("grader-group-0", "grader-group-1"),
        finalOutputSpec: FinalOutputSpec? = null,
        claimLedgerJsonl: String? = null,
        workerGroupIds: List<String> = listOf("group-0", "group-1"),
        atlasId: String = "atlas:run-1",
        completionRecord: RunCompletionRecord? = null,
    ) = RunMetricsExportContext(
        runId = "run-1",
        workerGroupIds = workerGroupIds,
        graderGroupIds = graderGroupIds,
        atlasId = atlasId,
        completedThroughRound = completedThroughRound,
        acceptanceProfile = AcceptanceProfile.NORMAL,
        finalOutputSpec = finalOutputSpec,
        completionRecord = completionRecord,
        claimLedgerJsonl = claimLedgerJsonl,
    )

    private fun rewardEvent(
        id: String,
        amountSpent: Double = 0.0,
        parentEventHash: String? = null,
        downstreamArtifactIds: Set<String> = emptySet(),
        observedScoreDelta: Double? = null,
        observationRound: Int? = null,
    ) = RewardUseLineageEvent(
        eventId = id,
        runId = "run-1",
        groupId = AtlasGroupId("group-0"),
        nodeId = NodeId("node-reward"),
        captureDecisionHash = "capture-hash",
        yieldScheduleHash = "schedule-hash",
        yieldApplicationHash = "application-hash",
        rewardType = InstrumentalRewardType.SPAWN_CREDITS,
        amountSpent = amountSpent,
        spentRound = 1,
        downstreamArtifactIds = downstreamArtifactIds,
        observedScoreDelta = observedScoreDelta,
        observationRound = observationRound,
        parentEventHash = parentEventHash,
    ).withComputedHash()

    private fun portfolio(
        groupId: String,
        nodeIds: List<String>,
    ): NodePortfolio {
        val group = AtlasGroupId(groupId)
        val bids = nodeIds.map { nodeId ->
            NodeBid(
                bidId = "bid:$groupId:$nodeId",
                groupId = group,
                nodeId = NodeId(nodeId),
                intendedArtifactType = ArtifactType.CLAIM_SET,
                estimatedCredits = 1.0,
                expectedProofMethod = ProofMethod.SOURCE_CORROBORATION,
                reason = "Metric fixture",
                round = 0,
            )
        }
        return NodePortfolio(
            portfolioId = "portfolio:$groupId:0",
            runId = "run-1",
            atlasId = "atlas:run-1",
            atlasHash = "atlas-hash",
            groupId = group,
            round = 0,
            bids = bids,
            diversityScore = 1.0,
        ).withComputedHash()
    }
}
