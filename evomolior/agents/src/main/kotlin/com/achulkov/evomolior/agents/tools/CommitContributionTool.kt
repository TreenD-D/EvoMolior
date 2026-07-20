@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** Persists one typed, immutable contribution selection before a mortal worker exits. */
class CommitContributionTool(
    private val repository: WorkerExecutionRepository,
    private val bootContext: AgentBootContext,
    private val budgetLedger: BudgetLedger? = null,
    private val now: () -> kotlin.time.Instant = { Clock.System.now() },
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
    private val toolInfrastructureRepository: ToolInfrastructureRepository? = null,
    private val contributionGraphRepository: ContributionGraphRepository? = null
) : SimpleTool<CommitContributionTool.Args>(
    argsType = typeToken<Args>(),
    name = "commit_contribution",
    description = """
        Commit the durable outcome selected for this lifecycle.
        Use POSITIVE_RESULT, REUSABLE_TOOL, VALIDATION_RESULT, INTEGRATION_RESULT,
        FALSIFIED_HYPOTHESIS, STRUCTURED_DEAD_END, NOVELTY_PROPOSAL, or NO_CONTRIBUTION.
        This records lineage but does not grade, capture, or close the Atlas node.
        When independently testing an accepted shadow discovery, include its exact proposal ID in
        referencedNoveltyProposalIds and include durable test or artifact evidence.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val commitType: String,
        val nodeId: String? = null,
        val artifactIds: List<String> = emptyList(),
        val claimIds: List<String> = emptyList(),
        val citedArtifactIds: List<String> = emptyList(),
        val toolRunIds: List<String> = emptyList(),
        val testsRun: List<String> = emptyList(),
        val referencedNoveltyProposalIds: List<String> = emptyList(),
        val changedStateSummary: String,
        val evidenceSummary: String = "",
        val confidence: Double,
        val unresolvedRisks: List<String> = emptyList(),
        val successorRecommendations: List<String> = emptyList()
    )

    override suspend fun execute(args: Args): String {
        val type = runCatching { CommitType.valueOf(args.commitType) }.getOrElse {
            return "Error: Invalid contribution type '${args.commitType}'."
        }
        if (bootContext.role == Role.Outlier && type !in OUTLIER_COMMIT_TYPES) {
            return "Error: Outliers may commit only NOVELTY_PROPOSAL, STRUCTURED_DEAD_END, or NO_CONTRIBUTION."
        }
        val assignedNodeId = bootContext.assignedLease?.nodeId
        val requestedNodeId = args.nodeId?.takeIf(String::isNotBlank)?.let(::NodeId)
        val resolvedNodeId = requestedNodeId ?: assignedNodeId
        if (assignedNodeId != null && resolvedNodeId != assignedNodeId) {
            return "Error: Contribution node must match the assigned Atlas lease."
        }
        if (assignedNodeId == null && requestedNodeId != null && bootContext.role != Role.Outlier) {
            return "Error: An unassigned worker cannot claim an Atlas node."
        }

        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = bootContext.agentId,
                detail = "CONTRIBUTION_COMMIT:${type.name}"
            )
        )
        val committedAt = now()
        val round = bootContext.workerView?.round ?: bootContext.generation
        val automaticToolRunIds = if (
            resolvedNodeId != null && toolInfrastructureRepository != null
        ) {
            toolInfrastructureRepository.executionsForAgentNode(
                runId = bootContext.runId,
                groupId = bootContext.groupId,
                agentId = bootContext.agentId,
                round = round,
                nodeId = resolvedNodeId.value
            ).filter {
                it.purpose in CONTRIBUTION_TOOL_EXECUTION_PURPOSES &&
                    it.result == ToolExecutionResult.SUCCEEDED
            }.map { it.executionId }
        } else {
            emptyList()
        }
        val draft = ContributionCommit(
            commitId = "",
            runId = bootContext.runId,
            groupId = AtlasGroupId(bootContext.groupId),
            round = round,
            agentId = AtlasAgentId(bootContext.agentId),
            nodeId = resolvedNodeId,
            commitType = type,
            artifactIds = args.artifactIds.distinct().sorted(),
            claimIds = args.claimIds.distinct().sorted(),
            citedArtifactIds = args.citedArtifactIds.distinct().sorted(),
            toolRunIds = (args.toolRunIds + automaticToolRunIds).distinct().sorted(),
            testsRun = args.testsRun.distinct().sorted(),
            referencedNoveltyProposalIds =
                args.referencedNoveltyProposalIds.map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted(),
            changedStateSummary = args.changedStateSummary.trim(),
            evidenceSummary = args.evidenceSummary.trim(),
            confidence = args.confidence,
            unresolvedRisks = args.unresolvedRisks.distinct(),
            successorRecommendations = args.successorRecommendations.distinct(),
            costSummary = budgetLedger?.toEvidenceCostSummary(),
            dedupeHash = "",
            committedAt = committedAt
        )
        val dedupeHash = AtlasHashing.hashContributionDedupe(draft)
        val contribution = draft.copy(
            commitId = CouncilDeterministicIds.create(
                "contribution-commit",
                bootContext.runId,
                bootContext.groupId,
                bootContext.agentId,
                dedupeHash
            ),
            dedupeHash = dedupeHash
        )
        val persisted = runCatching { repository.commit(contribution) }.getOrElse { error ->
            return "Error: Contribution commit rejected: ${error.message}"
        }
        runCatching {
            contributionGraphRepository?.recordContributionOrigin(persisted)
            contributionGraphRepository?.recordContributionCitations(persisted)
        }.getOrElse { error ->
            return "Error: Contribution lineage rejected: ${error.message}"
        }
        return "Contribution committed (ID: ${persisted.commitId}, type: ${persisted.commitType.name})."
    }

    private fun BudgetLedger.toEvidenceCostSummary(): NodeEvidenceCostSummary {
        val deductions = actionLog
        return NodeEvidenceCostSummary(
            totalCredits = deductions.sumOf { it.cost },
            llmTokens = deductions
                .filter { it.actionType == com.achulkov.evomolior.core.model.ActionType.LLM_CALL }
                .sumOf { it.tokenCount.toLong() },
            toolExecutions = deductions.count {
                it.actionType in TOOL_ACTION_TYPES
            },
            sourceFetches = deductions.count {
                it.actionType == com.achulkov.evomolior.core.model.ActionType.WEB_FETCH
            },
            resourceCosts = deductions.groupBy { it.actionType.name }
                .mapValues { (_, entries) -> entries.sumOf { it.cost } }
        )
    }

    private companion object {
        val TOOL_ACTION_TYPES = setOf(
            com.achulkov.evomolior.core.model.ActionType.TOOL_EXEC_SUCCESS,
            com.achulkov.evomolior.core.model.ActionType.TOOL_EXEC_FAILURE,
            com.achulkov.evomolior.core.model.ActionType.TOOL_EXEC_RETRY
        )
        val OUTLIER_COMMIT_TYPES = setOf(
            CommitType.NOVELTY_PROPOSAL,
            CommitType.STRUCTURED_DEAD_END,
            CommitType.NO_CONTRIBUTION
        )
        val CONTRIBUTION_TOOL_EXECUTION_PURPOSES = setOf(
            ToolExecutionPurpose.VALIDATION_TEST,
            ToolExecutionPurpose.AGENT_USE
        )
    }
}
