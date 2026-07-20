@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlin.time.Instant

internal class ContributionGraphTestHarness(
    private val database: EvoMoliorDatabase,
    private val runId: String,
    private val groupId: AtlasGroupId
) {
    suspend fun persistStagedEdge(
        repository: ContributionGraphRepository
    ): ContributionEdge {
        val submission = StagingSubmissionEntity(
            id = "submission-1",
            agentId = "agent-1",
            role = RoleName,
            type = "DOC",
            content = "Exact staged evidence",
            wipTaskRef = "node-1",
            budgetSignature = "signed",
            generation = 1,
            groupId = groupId.value,
            runId = runId,
            submittedAtMs = 1_000
        )
        database.stagingDao().insert(submission)
        val draft = ContributionCommit(
            commitId = "commit-1",
            runId = runId,
            groupId = groupId,
            round = 1,
            agentId = AtlasAgentId("agent-1"),
            nodeId = NodeId("node-1"),
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = listOf(submission.id),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = emptyList(),
            changedStateSummary = "Staged exact evidence.",
            evidenceSummary = "submission-1",
            confidence = 0.8,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            dedupeHash = "",
            committedAt = Instant.fromEpochMilliseconds(1_000)
        )
        val commit = draft.copy(dedupeHash = AtlasHashing.hashContributionDedupe(draft))
        WorkerExecutionRepository(database).commit(commit)
        return repository.recordContributionOrigin(commit)
            .single { it.target.authorityId == submission.id }
    }

    private companion object {
        const val RoleName = "Researcher"
    }
}
