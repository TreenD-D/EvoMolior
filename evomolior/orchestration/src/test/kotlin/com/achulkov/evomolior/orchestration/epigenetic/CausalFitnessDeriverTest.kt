@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.epigenetic

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.FitnessComponentKind
import com.achulkov.evomolior.core.contract.FitnessPlane
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ControlInterventionOutcome
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.ControlInterventionTargetKind
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.SummaryPrimaryContentAssessment
import com.achulkov.evomolior.core.contract.SummaryValidationRecord
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.CausalFitnessRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class CausalFitnessDeriverTest {
    @Test
    fun `assessed control and validated summary derive role-local exact fitness`() = runTest {
        val runId = "run-control-deriver"
        val groupId = AtlasGroupId("group-control-deriver")
        val database = SubstrateDatabaseFactory(
            createTempDirectory("control-fitness-deriver-").toFile()
        ).get(runId, groupId.value)
        try {
            val graph = ContributionGraphRepository(database)
            val event = EventLogEntryEntity(
                id = "event-1",
                timestampMs = 1_000,
                eventType = "CONSTITUTIONAL_VIOLATION",
                agentId = "worker-1",
                groupId = groupId.value,
                runId = runId,
                generation = 1,
                payloadJson = """{"outcome":"CONFIRMED_VIOLATION"}"""
            )
            database.eventLogDao().insert(event)
            val submission = StagingSubmissionEntity(
                id = "submission-control-result",
                agentId = "auditor-1",
                role = Role.Auditor.name,
                type = "DOC",
                content = "Audited and quarantined exact unsafe output.",
                wipTaskRef = null,
                budgetSignature = "signed",
                status = "promoted",
                promotedArtifactRef = "kb:control-result",
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                submittedAtMs = 2_000
            )
            database.stagingDao().insert(submission)
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "control-result",
                    content = submission.content,
                    sourceAgentId = submission.agentId,
                    generation = 1,
                    domain = "control",
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = 2_000
                )
            )
            val resultAuthority = ContributionAuthorityRef(
                ContributionAuthorityKind.CANONICAL_ARTIFACT,
                "kb:control-result",
                AtlasHashing.hashText(submission.content)
            )
            graph.recordCriticDecision(
                CriticDecisionRecord(
                    decisionId = "decision-control-result",
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
                    resultAuthority = resultAuthority,
                    decidedAt = Instant.fromEpochMilliseconds(2_500)
                ).withComputedHash()
            )
            val observation = graph.controlObservation(event.id)
            val intervention = ControlInterventionRecord(
                interventionId = "control-intervention-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                controllerAgentId = "auditor-1",
                controllerRole = Role.Auditor,
                targetKind = ControlInterventionTargetKind.AGENT,
                targetId = "worker-1",
                observations = listOf(observation),
                outcome = ControlInterventionOutcome.TRUE_POSITIVE,
                avoidedLossAuthorities = listOf(resultAuthority),
                occurredAt = Instant.fromEpochMilliseconds(3_000)
            ).withComputedHash()
            graph.recordControlIntervention(intervention)
            val finalSubmission = StagingSubmissionEntity(
                id = "summary-final-submission",
                agentId = "archivist-1",
                role = Role.Archivist.name,
                type = "DOC",
                content = "Validated final summary",
                wipTaskRef = null,
                budgetSignature = "signed",
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                submittedAtMs = 3_500
            )
            database.stagingDao().insert(finalSubmission)
            val finalReference = FinalDeliverableReferenceRecord(
                referenceId = "final-reference-control-test",
                runId = runId,
                groupId = groupId,
                round = 1,
                relativePath = "final-answer.md",
                mediaType = "text/markdown",
                contentHash = AtlasHashing.hashText(finalSubmission.content),
                writerAgentId = finalSubmission.agentId,
                writerSubmissionId = finalSubmission.id,
                nodeId = null,
                sourceAuthority = resultAuthority,
                referencedAt = Instant.fromEpochMilliseconds(3_500)
            ).withComputedHash()
            graph.recordFinalDeliverableReference(finalReference)
            val summaryValidation = SummaryValidationRecord(
                validationId = "summary-validation-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                summarizerAgentId = finalSubmission.agentId,
                summarizerRole = Role.Archivist,
                finalReferences = listOf(
                    ContributionAuthorityRef(
                        ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                        finalReference.referenceId,
                        finalReference.recordHash
                    )
                ),
                validatorContractHash = "validator-contract-hash",
                fidelityChecksPassed = 2,
                fidelityChecksTotal = 2,
                retrievalReferencesValidated = 1,
                retrievalReferencesTotal = 1,
                sourceContentBytes = 200,
                outputContentBytes = 100,
                primaryContentAssessment = SummaryPrimaryContentAssessment.ABSENT,
                validatedAt = Instant.fromEpochMilliseconds(4_000)
            ).withComputedHash()
            graph.recordSummaryValidation(summaryValidation)
            val deriver = CausalFitnessDeriver(
                database,
                graph,
                CausalFitnessRepository(database)
            )

            val records = deriver.deriveGeneration(runId, groupId.value, 1)
            val control = records.single { it.plane == FitnessPlane.CONTROL }

            assertEquals(Role.Auditor, control.role)
            assertEquals(2.0, control.total)
            assertEquals(
                setOf(
                    FitnessComponentKind.TRUE_POSITIVE_INTERVENTION,
                    FitnessComponentKind.AVOIDED_LOSS
                ),
                control.components.mapTo(linkedSetOf()) { it.kind }
            )
            val summary = records.single { it.plane == FitnessPlane.SUMMARY }
            assertEquals(Role.Archivist, summary.role)
            assertEquals(2.5, summary.total)
            assertEquals(
                setOf(
                    FitnessComponentKind.SUMMARY_FIDELITY,
                    FitnessComponentKind.SUMMARY_RETRIEVAL_VALUE,
                    FitnessComponentKind.SUMMARY_COMPRESSION
                ),
                summary.components.mapTo(linkedSetOf()) { it.kind }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `rejected write is exact negative worker fitness and not Legacy eligible`() = runTest {
        val runId = "run-causal-deriver"
        val groupId = AtlasGroupId("group-causal-deriver")
        val database = SubstrateDatabaseFactory(
            createTempDirectory("causal-fitness-deriver-").toFile()
        ).get(runId, groupId.value)
        try {
            val graph = ContributionGraphRepository(database)
            val staging = StagingSubmissionEntity(
                id = "submission-1",
                agentId = "agent-1",
                role = "Researcher",
                type = "DOC",
                content = "Unsupported write",
                wipTaskRef = "node-1",
                budgetSignature = "signed",
                status = "rejected",
                rejectionReason = "Unsupported",
                criticAgentId = "critic-1",
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                submittedAtMs = 1_000,
                reviewedAtMs = 2_000
            )
            database.stagingDao().insert(staging)
            val draft = ContributionCommit(
                commitId = "commit-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                agentId = AtlasAgentId("agent-1"),
                nodeId = NodeId("node-1"),
                commitType = CommitType.POSITIVE_RESULT,
                artifactIds = listOf(staging.id),
                claimIds = emptyList(),
                toolRunIds = emptyList(),
                testsRun = emptyList(),
                changedStateSummary = "Staged a write.",
                evidenceSummary = "submission-1",
                confidence = 0.4,
                unresolvedRisks = listOf("Unsupported"),
                successorRecommendations = emptyList(),
                dedupeHash = "",
                committedAt = Instant.fromEpochMilliseconds(1_000)
            )
            val commit = draft.copy(dedupeHash = AtlasHashing.hashContributionDedupe(draft))
            WorkerExecutionRepository(database).commit(commit)
            graph.recordContributionOrigin(commit)
            graph.recordCriticDecision(
                CriticDecisionRecord(
                    decisionId = "decision-1",
                    runId = runId,
                    groupId = groupId,
                    round = 1,
                    submissionId = staging.id,
                    submissionContentHash = AtlasHashing.hashText(staging.content),
                    resolvedReview = CriticReview(
                        submissionId = staging.id,
                        outcome = CriticOutcome.REJECT,
                        criticAgentId = "critic-1",
                        rejectionReason = "Unsupported"
                    ),
                    resultAuthority = null,
                    decidedAt = Instant.fromEpochMilliseconds(2_000)
                ).withComputedHash()
            )
            val repository = CausalFitnessRepository(database)
            val deriver = CausalFitnessDeriver(database, graph, repository)

            val records = deriver.deriveGeneration(runId, groupId.value, 1)

            assertEquals(1, records.size)
            val record = records.single()
            assertEquals(FitnessPlane.WORKER, record.plane)
            assertEquals(-1.0, record.total)
            assertEquals(
                listOf(FitnessComponentKind.REJECTED_OR_MALFORMED_WRITE),
                record.components.map { it.kind }
            )
            assertFalse(record.legacyEligible)
            assertEquals(emptyList(), deriver.topPercentileByPlane(records))
        } finally {
            database.close()
        }
    }
}
