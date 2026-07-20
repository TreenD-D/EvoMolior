@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkKind
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkTask
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class BenchmarkScoreAuthorityCliTest {
    @Test
    fun `society benchmark scoring appends exact final reference contribution`() =
        kotlinx.coroutines.test.runTest {
            val dataRoot = createTempDirectory("benchmark-score-authority-").toFile()
            val runId = "benchmark-score-run"
            val groupId = "group-0"
            val outputPath = "tier0-prediction.json"
            val factory = SubstrateDatabaseFactory(dataRoot)
            val database = factory.get(runId, groupId)
            val repository = ContributionGraphRepository(
                database,
                factory.getAtlas(runId)
            )
            val sourceContent = "Canonical benchmark source."
            val sourceHash = AtlasHashing.hashText(sourceContent)
            database.stagingDao().insert(
                staging(
                    id = "source-submission",
                    agentId = "source-agent",
                    content = sourceContent,
                    status = "promoted",
                    promotedArtifactRef = "kb:source-artifact",
                    promotedArtifactHash = sourceHash
                )
            )
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "source-artifact",
                    content = sourceContent,
                    sourceAgentId = "source-agent",
                    generation = 0,
                    domain = "benchmark",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 2L
                )
            )
            repository.recordCriticDecision(
                CriticDecisionRecord(
                    decisionId = "source-decision",
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = 0,
                    submissionId = "source-submission",
                    submissionContentHash = sourceHash,
                    resolvedReview = CriticReview(
                        submissionId = "source-submission",
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = "critic",
                        initialTrustScore = 1.0
                    ),
                    resultAuthority = ContributionAuthorityRef(
                        ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        "kb:source-artifact",
                        sourceHash
                    ),
                    decidedAt = Instant.fromEpochMilliseconds(2L)
                ).withComputedHash()
            )
            database.stagingDao().insert(
                staging(
                    id = "writer-submission",
                    agentId = "writer-agent",
                    content = """{"task_id":"task-a","final_answer":"answer"}"""
                )
            )
            val finalReference = repository.recordFinalDeliverableReference(
                FinalDeliverableReferenceRecord(
                    referenceId = "final-reference",
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = 1,
                    relativePath = outputPath,
                    mediaType = "application/json",
                    contentHash = AtlasHashing.hashText("answer"),
                    writerAgentId = "writer-agent",
                    writerSubmissionId = "writer-submission",
                    nodeId = null,
                    sourceAuthority = ContributionAuthorityRef(
                        ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        "kb:source-artifact",
                        sourceHash
                    ),
                    referencedAt = Instant.fromEpochMilliseconds(3L)
                ).withComputedHash()
            )
            factory.close(runId, groupId)
            factory.close(runId, "atlas-authority")

            val task = Tier0BenchmarkTask(
                id = "task-a",
                benchmark = Tier0BenchmarkKind.FRAMES,
                question = "What is the answer?",
                acceptedAnswers = listOf("answer")
            )
            val contribution = assertNotNull(
                BenchmarkCliRunner().recordBenchmarkScoreAuthority(
                    dataRoot = dataRoot,
                    options = options(dataRoot.toPath()),
                    task = task,
                    runId = runId,
                    outputPath = outputPath,
                    exactCorrect = true
                )
            )

            assertEquals(finalReference.referenceId, contribution.finalReference.authorityId)
            assertEquals(finalReference.recordHash, contribution.finalReference.authorityHash)
            assertEquals(1.0, contribution.scoreContribution)
            val verificationFactory = SubstrateDatabaseFactory(dataRoot)
            assertEquals(
                contribution,
                ContributionGraphRepository(
                    verificationFactory.get(runId, groupId),
                    verificationFactory.getAtlas(runId)
                ).benchmarkScoreContribution(contribution.contributionId)
            )
        }

    private fun staging(
        id: String,
        agentId: String,
        content: String,
        status: String = "pending",
        promotedArtifactRef: String? = null,
        promotedArtifactHash: String? = null
    ) = StagingSubmissionEntity(
        id = id,
        agentId = agentId,
        role = "Builder",
        type = "DOC",
        content = content,
        wipTaskRef = null,
        budgetSignature = "test",
        status = status,
        criticAgentId = promotedArtifactRef?.let { "critic" },
        initialTrustScore = promotedArtifactRef?.let { 1.0 },
        generation = 0,
        groupId = "group-0",
        runId = "benchmark-score-run",
        submittedAtMs = 1L,
        reviewedAtMs = promotedArtifactRef?.let { 2L },
        promotedArtifactRef = promotedArtifactRef,
        promotedArtifactHash = promotedArtifactHash
    )

    private fun options(outputDir: java.nio.file.Path) = BenchmarkCliOptions(
        benchmark = Tier0BenchmarkKind.FRAMES,
        scholarQaBenchVariant = ScholarQaBenchVariant.QASA,
        limit = 1,
        offset = 0,
        outputDir = outputDir,
        apiKey = "test",
        llmBackend = LlmBackend.QWEN_CLOUD,
        fast = true,
        groupCount = 1,
        rounds = 1,
        roundDurationMinutes = 1,
        maxAgentsPerGroup = 1,
        budgetOverride = 10.0,
        taskTimeoutMinutes = 1,
        costOverrideUsdPerTask = 1.0,
        tasksJsonl = null,
        modelOverrides = emptyMap()
    )
}
