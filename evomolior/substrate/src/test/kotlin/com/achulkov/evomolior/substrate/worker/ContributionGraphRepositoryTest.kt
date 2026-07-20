@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ControlInterventionOutcome
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.ControlInterventionTargetKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.ContributionPlane
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.DownstreamUseKind
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.core.contract.IntegrationNodeRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.ReceiverScoreContributionRecord
import com.achulkov.evomolior.core.contract.BenchmarkScoreContributionRecord
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ContributionGraphRepositoryTest {
    private val runId = "run-contribution-graph"
    private val groupId = AtlasGroupId("group-contribution-graph")

    @Test
    fun `append verifies existing authorities hashes and immutable lineage`() = runTest {
        val database = database()
        try {
            val workerRepository = WorkerExecutionRepository(database)
            val graphRepository = ContributionGraphRepository(database)
            val commit = workerRepository.commit(commit())
            val submission = submission()
            database.stagingDao().insert(submission)
            val edge = edge(commit, submission).withComputedHash()

            assertEquals(edge, graphRepository.append(edge))
            assertEquals(edge, graphRepository.byId(edge.edgeId))
            assertEquals(listOf(edge), graphRepository.forRun(runId, groupId.value))
            assertEquals(
                listOf(edge),
                graphRepository.fromAuthority(runId, groupId.value, edge.source)
            )
            assertEquals(
                listOf(edge),
                graphRepository.toAuthority(runId, groupId.value, edge.target)
            )

            assertFailsWith<IllegalArgumentException> {
                graphRepository.append(
                    edge.copy(
                        edgeId = "edge-tampered",
                        target = edge.target.copy(authorityHash = "wrong"),
                        edgeHash = ""
                    ).withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                graphRepository.append(
                    edge.copy(
                        edgeId = "edge-missing",
                        target = edge.target.copy(authorityId = "missing-submission"),
                        edgeHash = ""
                    ).withComputedHash()
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `critic decision and review lineage commit atomically`() = runTest {
        val database = database()
        try {
            val graphRepository = ContributionGraphRepository(database)
            val submission = submission()
            database.stagingDao().insert(submission)
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "artifact-1",
                    content = submission.content,
                    sourceAgentId = submission.agentId,
                    generation = submission.generation,
                    domain = "test",
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = 2_000
                )
            )
            val decision = CriticDecisionRecord(
                decisionId = "decision-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                submissionId = submission.id,
                submissionContentHash = AtlasHashing.hashText(submission.content),
                resolvedReview = CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic-1",
                    initialTrustScore = 0.8
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:artifact-1",
                    authorityHash = AtlasHashing.hashText(submission.content)
                ),
                decidedAt = Instant.parse("2026-07-12T08:02:00Z")
            ).withComputedHash()

            assertEquals(decision, graphRepository.recordCriticDecision(decision))
            assertEquals(decision, graphRepository.criticDecision(decision.decisionId))
            assertEquals(
                setOf(submission.id, "kb:artifact-1"),
                graphRepository.acceptedArtifactAuthorityIdsForRun(runId, groupId.value)
            )
            assertEquals(
                setOf(
                    ContributionRelation.REVIEWED_AS,
                    ContributionRelation.PROMOTED_TO
                ),
                graphRepository.forRun(runId, groupId.value).mapTo(linkedSetOf()) { it.relation }
            )

            val invalidDecision = decision.copy(
                decisionId = "decision-invalid",
                submissionId = "missing-submission",
                resolvedReview = decision.resolvedReview.copy(submissionId = "missing-submission"),
                decisionHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                graphRepository.recordCriticDecision(invalidDecision)
            }
            assertEquals(null, graphRepository.criticDecision("decision-invalid"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `ControlEventDeduplicationTest blocked request observations append as two events and duplicate control intervention is idempotent`() = runTest {
        val database = database()
        try {
            val repository = ContributionGraphRepository(database)
            val firstEvent = EventLogEntryEntity(
                id = "control-event-1",
                timestampMs = 1_000,
                eventType = EventTypes.CONSTITUTIONAL_VIOLATION,
                agentId = "worker-1",
                groupId = groupId.value,
                runId = runId,
                generation = 1,
                payloadJson = """{"blocked":true,"request":"blocked-request-1"}"""
            )
            val secondEvent = EventLogEntryEntity(
                id = "control-event-2",
                timestampMs = 1_001,
                eventType = EventTypes.CONSTITUTIONAL_VIOLATION,
                agentId = "worker-2",
                groupId = groupId.value,
                runId = runId,
                generation = 1,
                payloadJson = """{"blocked":true,"request":"blocked-request-2"}"""
            )

            database.eventLogDao().insert(firstEvent)
            database.eventLogDao().insert(secondEvent)
            database.eventLogDao().insert(firstEvent)

            val persistedEvents = database.eventLogDao().getByGeneration(runId, groupId.value, 1)
            assertEquals(2, persistedEvents.size)
            assertEquals(
                listOf("control-event-1", "control-event-2"),
                persistedEvents.map { it.id }
            )

            val observationOne = repository.controlObservation(firstEvent.id)
            val observationTwo = repository.controlObservation(secondEvent.id)
            assertEquals(firstEvent.id, observationOne.eventId)
            assertEquals(secondEvent.id, observationTwo.eventId)
            assertEquals(2, database.eventLogDao().getByGeneration(runId, groupId.value, 1).size)

            val intervention = ControlInterventionRecord(
                interventionId = "control-intervention-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                controllerAgentId = "auditor-1",
                controllerRole = Role.Auditor,
                targetKind = ControlInterventionTargetKind.AGENT,
                targetId = "worker-1",
                observations = listOf(observationOne, observationTwo),
                outcome = ControlInterventionOutcome.NO_ACTION_REQUIRED,
                occurredAt = Instant.parse("2026-07-12T08:05:00Z")
            ).withComputedHash()

            assertEquals(intervention, repository.recordControlIntervention(intervention))
            assertEquals(intervention, repository.recordControlIntervention(intervention))
            assertEquals(intervention, repository.controlIntervention(intervention.interventionId))
            assertEquals(
                listOf(intervention),
                repository.controlInterventionsThroughRound(runId, groupId.value, 1)
            )
            assertEquals(2, database.eventLogDao().getByGeneration(runId, groupId.value, 1).size)

            val mutatedDuplicate = intervention.copy(
                targetId = "worker-2",
                recordHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.recordControlIntervention(mutatedDuplicate)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `contribution origin records authored and exact staged edges idempotently`() = runTest {
        val database = database()
        try {
            val workerRepository = WorkerExecutionRepository(database)
            val graphRepository = ContributionGraphRepository(database)
            val submission = submission()
            database.stagingDao().insert(submission)
            val commit = workerRepository.commit(commit())

            val first = graphRepository.recordContributionOrigin(commit)
            val repeated = graphRepository.recordContributionOrigin(commit)

            assertEquals(first, repeated)
            assertEquals(
                setOf(ContributionRelation.AUTHORED, ContributionRelation.STAGED_AS),
                first.mapTo(linkedSetOf()) { it.relation }
            )
            assertEquals(2, graphRepository.forRun(runId, groupId.value).size)

            val otherAgentSubmission = submission.copy(
                id = "submission-other-agent",
                agentId = "agent-other"
            )
            database.stagingDao().insert(otherAgentSubmission)
            val invalidCommitDraft = commit().copy(
                commitId = "commit-invalid-owner",
                artifactIds = listOf(otherAgentSubmission.id),
                dedupeHash = ""
            )
            val invalidCommit = invalidCommitDraft.copy(
                dedupeHash = AtlasHashing.hashContributionDedupe(invalidCommitDraft)
            )
            workerRepository.commit(invalidCommit)
            assertFailsWith<IllegalArgumentException> {
                graphRepository.recordContributionOrigin(invalidCommit)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `downstream use persists exact authority and canonical lineage atomically`() = runTest {
        val database = database()
        try {
            val repository = ContributionGraphRepository(database)
            val submission = submission()
            database.stagingDao().insert(submission)
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "artifact-1",
                    content = submission.content,
                    sourceAgentId = submission.agentId,
                    generation = submission.generation,
                    domain = "test",
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = 2_000
                )
            )
            val decision = CriticDecisionRecord(
                decisionId = "decision-downstream",
                runId = runId,
                groupId = groupId,
                round = 1,
                submissionId = submission.id,
                submissionContentHash = AtlasHashing.hashText(submission.content),
                resolvedReview = CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic-1",
                    initialTrustScore = 0.8
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:artifact-1",
                    authorityHash = AtlasHashing.hashText(submission.content)
                ),
                decidedAt = Instant.parse("2026-07-12T08:02:00Z")
            ).withComputedHash()
            repository.recordCriticDecision(decision)
            val source = requireNotNull(
                repository.canonicalAuthority(runId, groupId.value, "kb:artifact-1")
            )
            val record = DownstreamUseRecord(
                useId = "downstream-read-1",
                runId = runId,
                groupId = groupId,
                round = 2,
                agentId = "agent-reader",
                nodeId = "node-2",
                kind = DownstreamUseKind.READ,
                sourceAuthority = source,
                contextHash = "read-context-hash",
                occurredAt = Instant.parse("2026-07-12T08:03:00Z")
            ).withComputedHash()

            assertEquals(record, repository.recordDownstreamUse(record))
            assertEquals(record, repository.downstreamUse(record.useId))
            val edge = repository.forRun(runId, groupId.value)
                .single { it.relation == ContributionRelation.READ_DOWNSTREAM }
            assertEquals(record.useId, edge.target.authorityId)
            assertEquals(record.recordHash, edge.target.authorityHash)

            val citationDraft = commit().copy(
                commitId = "commit-citation",
                artifactIds = emptyList(),
                citedArtifactIds = listOf("kb:artifact-1"),
                dedupeHash = "",
                committedAt = Instant.parse("2026-07-12T08:04:00Z")
            )
            val citationCommit = citationDraft.copy(
                dedupeHash = AtlasHashing.hashContributionDedupe(citationDraft)
            )
            WorkerExecutionRepository(database).commit(citationCommit)
            val citation = repository.recordContributionCitations(citationCommit).single()

            assertEquals(DownstreamUseKind.CITATION, citation.kind)
            assertEquals(decision.resultAuthority, citation.sourceAuthority)
            assertEquals(citationCommit.dedupeHash, citation.contextHash)
            val citationEdge = repository.forRun(runId, groupId.value)
                .single { it.relation == ContributionRelation.CITED_DOWNSTREAM }
            assertEquals(decision.resultAuthority, citationEdge.source)
            assertEquals(citation.recordHash, citationEdge.target.authorityHash)
        } finally {
            database.close()
        }
    }

    @Test
    fun `integration node binds exact accepted input and capture authority`() = runTest {
        val database = database()
        try {
            val repository = ContributionGraphRepository(database)
            val submission = submission()
            database.stagingDao().insert(submission)
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "artifact-integration",
                    content = submission.content,
                    sourceAgentId = submission.agentId,
                    generation = submission.generation,
                    domain = "test",
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = 2_000
                )
            )
            val source = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                authorityId = "kb:artifact-integration",
                authorityHash = AtlasHashing.hashText(submission.content)
            )
            repository.recordCriticDecision(
                CriticDecisionRecord(
                    decisionId = "decision-integration-source",
                    runId = runId,
                    groupId = groupId,
                    round = 1,
                    submissionId = submission.id,
                    submissionContentHash = AtlasHashing.hashText(submission.content),
                    resolvedReview = CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = "critic-1",
                        initialTrustScore = 0.8
                    ),
                    resultAuthority = source,
                    decidedAt = Instant.parse("2026-07-12T08:02:00Z")
                ).withComputedHash()
            )
            val capture = NodeCaptureDecision(
                decisionId = "capture-integration",
                runId = runId,
                round = 2,
                nodeId = NodeId("node-integration"),
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                nodePayloadHash = "node-payload-hash",
                capturePolicyHash = "capture-policy-hash",
                candidateBundleHashes = setOf("bundle-hash"),
                authoritativeBundleHashes = setOf("bundle-hash"),
                resultingOwnerGroupIds = setOf(groupId),
                resultingCaptureState = CaptureState.CAPTURED,
                decisionType = NodeCaptureDecisionType.CAPTURED,
                winningScore = 0.9,
                yieldEligible = true,
                reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE
            ).withComputedHash()
            database.nodeCaptureDao().insertCaptureDecision(
                NodeCaptureDecisionEntity(
                    decisionId = capture.decisionId,
                    runId = capture.runId,
                    round = capture.round,
                    nodeId = capture.nodeId.value,
                    atlasVersion = capture.atlasVersion,
                    atlasHash = capture.atlasHash,
                    nodePayloadHash = capture.nodePayloadHash,
                    decisionType = capture.decisionType.name,
                    resultingCaptureState = capture.resultingCaptureState.name,
                    yieldEligible = capture.yieldEligible,
                    decisionHash = capture.decisionHash,
                    contentJson = Json.encodeToString(capture),
                    createdAtMs = 3_000
                )
            )
            val integration = IntegrationNodeRecord(
                integrationId = "integration-1",
                runId = runId,
                groupId = groupId,
                round = 2,
                nodeId = capture.nodeId.value,
                atlasVersion = capture.atlasVersion,
                atlasHash = capture.atlasHash,
                nodePayloadHash = capture.nodePayloadHash,
                captureDecision = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                    authorityId = capture.decisionId,
                    authorityHash = capture.decisionHash
                ),
                sourceAuthorities = listOf(source),
                integratedAt = Instant.parse("2026-07-12T08:04:00Z")
            ).withComputedHash()

            assertEquals(integration, repository.recordIntegrationNode(integration))
            assertEquals(integration, repository.integrationNode(integration.integrationId))
            assertEquals(
                setOf(source.authorityId, capture.decisionId),
                repository.toAuthority(
                    runId,
                    groupId.value,
                    ContributionAuthorityRef(
                        ContributionAuthorityKind.INTEGRATION_NODE,
                        integration.integrationId,
                        integration.recordHash
                    )
                ).mapTo(linkedSetOf()) { it.source.authorityId }
            )
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "final-submission-1",
                    agentId = "writer-1",
                    role = "Archivist",
                    type = "DOC",
                    content = "Final output",
                    wipTaskRef = null,
                    budgetSignature = "signed",
                    generation = 2,
                    groupId = groupId.value,
                    runId = runId,
                    submittedAtMs = 4_500
                )
            )

            val finalReference = FinalDeliverableReferenceRecord(
                referenceId = "final-reference-1",
                runId = runId,
                groupId = groupId,
                round = 2,
                relativePath = "answer.html",
                mediaType = "text/html",
                contentHash = "output-hash",
                writerAgentId = "writer-1",
                writerSubmissionId = "final-submission-1",
                nodeId = null,
                sourceAuthority = ContributionAuthorityRef(
                    ContributionAuthorityKind.INTEGRATION_NODE,
                    integration.integrationId,
                    integration.recordHash
                ),
                referencedAt = Instant.parse("2026-07-12T08:05:00Z")
            ).withComputedHash()
            assertEquals(
                finalReference,
                repository.recordFinalDeliverableReference(finalReference)
            )
            assertEquals(
                finalReference,
                repository.finalDeliverableReference(finalReference.referenceId)
            )

            val finalRef = ContributionAuthorityRef(
                ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                finalReference.referenceId,
                finalReference.recordHash
            )
            val receiverScore = ReceiverScoreContributionRecord(
                contributionId = "receiver-score-1",
                runId = runId,
                groupId = groupId,
                round = 2,
                finalReference = finalRef,
                scoringContractId = "receiver-contract",
                scoringContractVersion = 1,
                scoringContractHash = "receiver-contract-hash",
                criterionId = "actionability",
                scoreContribution = 8.0,
                maximumContribution = 10.0,
                scoredAt = Instant.parse("2026-07-12T08:06:00Z")
            ).withComputedHash()
            val benchmarkScore = BenchmarkScoreContributionRecord(
                contributionId = "benchmark-score-1",
                runId = runId,
                groupId = groupId,
                round = 2,
                finalReference = finalRef,
                benchmarkId = "benchmark-1",
                benchmarkVersion = 1,
                benchmarkContractHash = "benchmark-contract-hash",
                componentId = "exactness",
                scoreContribution = 1.0,
                maximumContribution = 1.0,
                scoredAt = Instant.parse("2026-07-12T08:07:00Z")
            ).withComputedHash()

            assertEquals(
                receiverScore,
                repository.recordReceiverScoreContribution(receiverScore)
            )
            assertEquals(
                receiverScore,
                repository.receiverScoreContribution(receiverScore.contributionId)
            )
            assertEquals(
                benchmarkScore,
                repository.recordBenchmarkScoreContribution(benchmarkScore)
            )
            assertEquals(
                benchmarkScore,
                repository.benchmarkScoreContribution(benchmarkScore.contributionId)
            )
            assertEquals(
                setOf(
                    ContributionRelation.CONTRIBUTED_TO_RECEIVER_SCORE,
                    ContributionRelation.CONTRIBUTED_TO_BENCHMARK_SCORE
                ),
                repository.fromAuthority(runId, groupId.value, finalRef)
                    .mapTo(linkedSetOf()) { it.relation }
            )
        } finally {
            database.close()
        }
    }

    private fun database() = SubstrateDatabaseFactory(
        createTempDirectory("contribution-graph-repository-").toFile()
    ).get(runId, groupId.value)

    private fun commit(): ContributionCommit {
        val commit = ContributionCommit(
            commitId = "commit-1",
            runId = runId,
            groupId = groupId,
            round = 1,
            agentId = AtlasAgentId("agent-1"),
            nodeId = NodeId("node-1"),
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = listOf("submission-1"),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = emptyList(),
            changedStateSummary = "Staged one exact artifact.",
            evidenceSummary = "The staging record is the durable handoff.",
            confidence = 0.8,
            unresolvedRisks = emptyList(),
            successorRecommendations = listOf("Review the staged artifact."),
            dedupeHash = "",
            committedAt = Instant.parse("2026-07-12T08:00:00Z")
        )
        return commit.copy(dedupeHash = AtlasHashing.hashContributionDedupe(commit))
    }

    private fun submission() = StagingSubmissionEntity(
        id = "submission-1",
        agentId = "agent-1",
        role = "Researcher",
        type = "DOC",
        content = "Exact staged content",
        wipTaskRef = "node-1",
        budgetSignature = "budget-signature",
        generation = 1,
        groupId = groupId.value,
        runId = runId,
        submittedAtMs = 1_000
    )

    private fun edge(
        commit: ContributionCommit,
        submission: StagingSubmissionEntity
    ) = ContributionEdge(
        edgeId = "edge-1",
        runId = runId,
        groupId = groupId,
        round = 1,
        plane = ContributionPlane.WORKER,
        relation = ContributionRelation.STAGED_AS,
        source = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
            authorityId = commit.commitId,
            authorityHash = commit.dedupeHash
        ),
        target = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.STAGING_SUBMISSION,
            authorityId = submission.id,
            authorityHash = AtlasHashing.hashText(submission.content)
        ),
        occurredAt = Instant.parse("2026-07-12T08:01:00Z")
    )
}
