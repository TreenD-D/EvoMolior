package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import kotlinx.serialization.Serializable

/** Outlier-only tool for publishing one falsifiable novelty vector. */
class ProposeNoveltyVectorTool(
    private val repository: OutlierNoveltyRepository,
    private val bootContext: AgentBootContext,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<ProposeNoveltyVectorTool.Args>(
    argsType = typeToken<Args>(),
    name = "propose_novelty_vector",
    description = """
        Propose one connected, falsifiable novelty vector against the currently revealed Atlas.
        Outliers must use visible node IDs only. This does not claim ordinary work or reveal
        hidden receiver/Atlas content. noveltyType is a typed enum; use one of:
        ${NoveltyType.entries.joinToString(", ") { it.name }}.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val noveltyType: NoveltyType,
        val affectedVisibleNodeIds: List<String>,
        val missingLayerOrAssumption: String,
        val proposedVector: String,
        val whyCurrentMapMayMissIt: String,
        val falsifiablePrediction: String,
        val minimalTestOrArtifact: String,
        val expectedReceiverImpact: String? = null,
        val expectedCost: Double,
        val conflictWithExistingAssumptions: List<String> = emptyList()
    )

    override suspend fun execute(args: Args): String {
        if (bootContext.role != Role.Outlier) {
            return "Error: propose_novelty_vector is available only to Outliers."
        }
        if (bootContext.wipTaskClaim != null ||
            bootContext.assignedLease != null ||
            bootContext.legacyProtocol != null
        ) {
            return "Error: Outlier novelty authority cannot coexist with task, lease, or Legacy."
        }
        val view = bootContext.workerView
            ?: return "Error: Outlier novelty requires a redacted WorkerView."
        if (!view.hasValidHash() || view.outlierMandate.isNullOrBlank()) {
            return "Error: Outlier WorkerView authority is invalid."
        }
        val affected = args.affectedVisibleNodeIds
            .mapNotNull { it.trim().takeIf(String::isNotBlank)?.let(::NodeId) }
            .toSet()
        val revealedIds = view.revealedNodes.mapTo(linkedSetOf()) { it.nodeId }
        if (affected.isEmpty() || !revealedIds.containsAll(affected)) {
            return "Error: Novelty must connect only to currently revealed node IDs."
        }
        val fingerprint = AtlasHashing.hashText(
            listOf(
                args.noveltyType.name,
                args.missingLayerOrAssumption,
                args.proposedVector,
                args.falsifiablePrediction,
                args.minimalTestOrArtifact
            ).joinToString("|") { value ->
                value.lowercase().trim().replace(Regex("\\s+"), " ")
            }
        )
        val proposalId = CouncilDeterministicIds.create(
            "novelty-vector",
            bootContext.runId,
            bootContext.groupId,
            bootContext.agentId,
            view.round.toString(),
            fingerprint
        )
        val proposal = runCatching {
            NoveltyVectorProposal(
                proposalId = proposalId,
                runId = bootContext.runId,
                atlasHash = view.visibleAtlasSkeleton.atlasHash,
                groupId = AtlasGroupId(bootContext.groupId),
                round = view.round,
                proposerAgentId = AtlasAgentId(bootContext.agentId),
                noveltyType = args.noveltyType,
                affectedVisibleNodeIds = affected,
                missingLayerOrAssumption = args.missingLayerOrAssumption.trim(),
                proposedVector = args.proposedVector.trim(),
                whyCurrentMapMayMissIt = args.whyCurrentMapMayMissIt.trim(),
                falsifiablePrediction = args.falsifiablePrediction.trim(),
                minimalTestOrArtifact = args.minimalTestOrArtifact.trim(),
                expectedReceiverImpact = args.expectedReceiverImpact?.trim(),
                expectedCost = args.expectedCost,
                conflictWithExistingAssumptions =
                    args.conflictWithExistingAssumptions.map(String::trim).filter(String::isNotBlank),
                noveltyFingerprint = fingerprint
            ).withComputedHash()
        }.getOrElse { error ->
            return "Error: Novelty proposal is invalid: ${error.message}"
        }
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.STAGING_WRITE,
                agentId = bootContext.agentId,
                detail = "NOVELTY_VECTOR:${proposal.proposalHash}"
            )
        )
        return runCatching {
            repository.appendInitialProposal(
                proposal = proposal,
                initialEvent = ShadowNodeLifecycleEvent(
                        eventId = "${proposal.proposalId}:1:PROPOSED",
                        proposalId = proposal.proposalId,
                        proposalHash = proposal.proposalHash,
                        runId = proposal.runId,
                        sequence = 1,
                        previousEventHash = null,
                        fromState = null,
                        toState = ShadowNodeState.PROPOSED,
                        round = proposal.round
                    )
                    .withComputedHash()
            )
            "Novelty proposal committed (ID: ${proposal.proposalId}, hash: ${proposal.proposalHash})."
        }.getOrElse { error ->
            "Error: Novelty proposal commit rejected: ${error.message}"
        }
    }
}
