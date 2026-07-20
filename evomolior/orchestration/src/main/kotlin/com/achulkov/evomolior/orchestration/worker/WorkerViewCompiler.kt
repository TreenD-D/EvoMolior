package com.achulkov.evomolior.orchestration.worker

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.GroupRevealPayload
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.atlas.toolValidationNodeIds
import com.achulkov.evomolior.core.contract.AtlasSkeletonNodeView
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.GroupFeedbackPacket
import com.achulkov.evomolior.core.contract.NodeBid
import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.WorkerArtifactRef
import com.achulkov.evomolior.core.contract.WorkerDeathNoteRef
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.contract.VisibleShadowNodeCard
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.core.council.CouncilDeterministicIds

/** Compiles redacted, hash-bound ordinary-worker context from sealed Atlas authority. */
class WorkerViewCompiler {
    /**
     * Builds one group/agent view without copying the receiver contract or hidden node payloads.
     *
     * @param atlas Current sealed Atlas.
     * @param states Current execution state keyed by node.
     * @param groupId Group receiving this view.
     * @param round Current round.
     * @param assignedNodeId Optional node assigned to this agent.
     * @param feedback Current-stage redacted feedback.
     * @param allowedKnowledgeRefs Bounded group-readable artifacts.
     * @param availableToolCards Bounded promoted tool descriptions.
     * @param predecessorNotes Bounded predecessor note references.
     * @param visibleShadowNodes Identity-redacted accepted discoveries available for testing.
     * @param outlierMandate Outlier-only novelty mandate.
     * @return Deterministically hashed worker view.
     */
    fun compile(
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>,
        groupId: AtlasGroupId,
        round: Int,
        assignedNodeId: NodeId? = null,
        feedback: GroupFeedbackPacket? = null,
        allowedKnowledgeRefs: List<WorkerArtifactRef> = emptyList(),
        availableToolCards: List<WorkerToolCard> = emptyList(),
        predecessorNotes: List<WorkerDeathNoteRef> = emptyList(),
        visibleShadowNodes: List<VisibleShadowNodeCard> = emptyList(),
        outlierMandate: String? = null
    ): WorkerView {
        require(atlas.atlasHash.isNotBlank() && atlas.validate().isValid) {
            "Worker views require a valid sealed Atlas"
        }
        require(states.keys == atlas.nodes.keys) {
            "Worker views require exactly one execution state for every Atlas node"
        }
        require(states.values.all { it.nodeId in atlas.nodes && it.round <= round }) {
            "Worker view execution states must belong to this Atlas and not come from a future round"
        }
        validateDisclosureLineage(atlas, states)

        val revealedNodeIds = states.values
            .filter { groupId in it.revealedToGroups }
            .map(NodeExecutionState::nodeId)
            .toSet()
        val revealedNodes = revealedNodeIds
            .sortedBy(NodeId::value)
            .map { nodeId ->
                val node = atlas.nodes.getValue(nodeId)
                require(node.hasValidPayloadHash()) {
                    "Revealed node ${nodeId.value} failed its hidden-payload commitment"
                }
                node.toRevealedNodeCard()
            }
        require(assignedNodeId == null || assignedNodeId in revealedNodeIds) {
            "Assigned node must be revealed to the worker group"
        }
        require(outlierMandate == null || assignedNodeId == null) {
            "Outlier mandate cannot carry an ordinary assigned node"
        }
        val toolCardExpectationPairs = atlas.nodes.values
            .mapNotNull { sourceNode ->
                sourceNode.toolOpportunity?.let { opportunity ->
                    val purpose = when {
                        assignedNodeId != null &&
                            assignedNodeId in atlas.toolValidationNodeIds(sourceNode.id) ->
                            ToolExecutionPurpose.VALIDATION_TEST
                        assignedNodeId != null &&
                            assignedNodeId in opportunity.expectedReuseNodeIds ->
                            ToolExecutionPurpose.AGENT_USE
                        else -> null
                    } ?: return@let null
                    opportunity.opportunityId to ToolCardExpectation(
                        commitment = ToolOpportunityCommitment(
                            runId = atlas.runId,
                            atlasHash = atlas.atlasHash,
                            nodeId = sourceNode.id,
                            opportunity = opportunity
                        ).withComputedHash(),
                        purpose = purpose
                    )
                }
            }
        require(
            toolCardExpectationPairs.map(Pair<String, ToolCardExpectation>::first)
                .distinct().size == toolCardExpectationPairs.size
        ) {
            "Worker tool-card opportunity IDs must be unique in the sealed Atlas"
        }
        val toolCardExpectations = toolCardExpectationPairs.toMap()
        require(availableToolCards.all { card ->
            val expectation = toolCardExpectations[card.opportunityId]
            expectation != null &&
                card.requiredForNodeId == assignedNodeId &&
                card.executionPurpose == expectation.purpose &&
                AtlasHashing.constantTimeEquals(
                    card.opportunityCommitmentHash,
                    expectation.commitment.commitmentHash
                )
        }) {
            "Available tool cards must match Council-sealed tool use for the assigned node"
        }

        val stage = atlas.stageDefinitions
            .filter { definition -> definition.nodeIds.any(revealedNodeIds::contains) }
            .maxByOrNull { it.stageIndex }
            ?: atlas.stageDefinitions.minByOrNull { it.stageIndex }
            ?: error("Worker views require at least one disclosure stage")
        require(feedback == null || feedback.stageId == stage.stageId) {
            "Worker feedback must be scoped to the current visible stage"
        }

        val skeletonNodes = atlas.nodes.mapValues { (nodeId, node) ->
            val state = states.getValue(nodeId)
            AtlasSkeletonNodeView(
                nodeId = nodeId,
                kind = node.kind,
                layer = node.layer,
                parentIds = node.parentIds,
                prerequisiteIds = node.prerequisiteIds,
                alternativeToIds = node.alternativeToIds,
                stageId = node.revealPolicy.stageId,
                payloadHash = node.payloadHash,
                status = node.status,
                congestionLevel = maxOf(
                    node.rewardSpec.congestionLevel,
                    state.leases.count { it.state == LeaseState.ACTIVE }
                )
            )
        }
        val assignedNode = assignedNodeId?.let { atlas.nodes.getValue(it).toRevealedNodeCard() }
        val assignedToolSkeleton = assignedNode?.toolOpportunity?.let { opportunity ->
            ToolProjectSkeletonCompiler.compile(
                ToolOpportunityCommitment(
                    runId = atlas.runId,
                    atlasHash = atlas.atlasHash,
                    nodeId = assignedNode.nodeId,
                    opportunity = opportunity
                ).withComputedHash()
            )
        }

        return WorkerView(
            runId = atlas.runId,
            groupId = groupId,
            round = round,
            stageId = stage.stageId,
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = atlas.atlasId,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                rootNodeIds = atlas.rootNodeIds,
                nodes = skeletonNodes
            ),
            revealedNodes = revealedNodes,
            assignedNode = assignedNode,
            groupFeedback = feedback,
            assignedToolSkeleton = assignedToolSkeleton,
            allowedKnowledgeRefs = allowedKnowledgeRefs.sortedBy(WorkerArtifactRef::artifactId),
            availableToolCards = availableToolCards.sortedBy(WorkerToolCard::toolId),
            predecessorNotes = predecessorNotes.sortedBy(WorkerDeathNoteRef::noteId),
            visibleShadowNodes = visibleShadowNodes.sortedBy(VisibleShadowNodeCard::proposalId),
            outlierMandate = outlierMandate
        ).withComputedHash()
    }

    private fun validateDisclosureLineage(
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>
    ) {
        val authorizations = states.values
            .flatMap(NodeExecutionState::disclosureLineage)
            .map { it.authorization }
            .distinctBy { it.authorizationId }
        authorizations.forEach { authorization ->
            require(authorization.hasValidHash()) {
                "Worker view disclosure lineage contains an invalid authorization"
            }
            val stage = atlas.stageDefinitions.singleOrNull {
                it.stageId == authorization.toStageId
            } ?: error("Worker view disclosure lineage references an unknown stage")
            val coveredNodes = stage.nodeIds.filterTo(linkedSetOf()) { nodeId ->
                states.getValue(nodeId).disclosureLineage.any {
                    it.authorization.authorizationId == authorization.authorizationId
                }
            }
            require(coveredNodes == stage.nodeIds &&
                stage.nodeIds.all { authorization.groupId in states.getValue(it).revealedToGroups }
            ) {
                "Worker view disclosure lineage is incomplete for ${stage.stageId}"
            }
            val replayed = GroupRevealPayload(
                runId = atlas.runId,
                atlasId = atlas.atlasId,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                groupId = authorization.groupId,
                round = authorization.round,
                stageId = stage.stageId,
                stageIndex = stage.stageIndex,
                stagePayloadHash = authorization.stagePayloadHash,
                revealedNodes = stage.nodeIds.sortedBy(NodeId::value)
                    .map { atlas.nodes.getValue(it).toRevealedNodeCard() }
            ).withComputedHash()
            require(replayed.hasValidHashes(atlas) &&
                AtlasHashing.constantTimeEquals(
                    replayed.payloadHash,
                    authorization.deliveredPayloadHash
                )
            ) {
                "Worker view disclosure payload replay failed for ${stage.stageId}"
            }
        }
    }

    private data class ToolCardExpectation(
        val commitment: ToolOpportunityCommitment,
        val purpose: ToolExecutionPurpose
    )
}

/**
 * Deterministically allocates distinct node portfolios from each group's revealed frontier.
 *
 * Quality grading is untouched. Congestion affects only selection priority and the economic
 * diversity metric.
 */
class NodePortfolioAllocator {
    /**
     * Allocates one bounded portfolio per group.
     *
     * @param atlas Current sealed Atlas.
     * @param states Current execution state.
     * @param workerViews Group worker views for the same round.
     * @param maximumNodesPerGroup Portfolio size cap.
     * @param priorityNodeIdsByGroup Revealed pending expected-reuse nodes to select first.
     * @return Portfolios keyed by group ID.
     */
    fun allocate(
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>,
        workerViews: List<WorkerView>,
        maximumNodesPerGroup: Int,
        priorityNodeIdsByGroup: Map<AtlasGroupId, Set<NodeId>> = emptyMap()
    ): Map<AtlasGroupId, NodePortfolio> {
        require(maximumNodesPerGroup > 0) { "maximumNodesPerGroup must be positive" }
        require(workerViews.isNotEmpty()) { "At least one worker view is required" }
        require(workerViews.map { it.round }.distinct().size == 1) {
            "Portfolio allocation requires views from one round"
        }
        val orderedViews = workerViews.sortedBy { it.groupId.value }
        require(priorityNodeIdsByGroup.keys.all { priorityGroup ->
            orderedViews.any { it.groupId == priorityGroup }
        }) {
            "Expected-reuse priorities must belong to a supplied worker group"
        }
        require(priorityNodeIdsByGroup.values.flatten().all(atlas.nodes::containsKey)) {
            "Expected-reuse priorities must reference Atlas nodes"
        }
        val revealedByGroup = orderedViews.associate { view ->
            require(view.hasValidHash()) { "Worker view ${view.groupId.value} has an invalid hash" }
            require(view.visibleAtlasSkeleton.atlasHash == atlas.atlasHash) {
                "Worker view does not belong to the supplied Atlas"
            }
            view.groupId to view.revealedNodes.associateBy(RevealedNodeCard::nodeId)
        }
        val plannedCounts = mutableMapOf<NodeId, Int>()
        val selectedByGroup = orderedViews.associate { view ->
            view.groupId to mutableListOf<RevealedNodeCard>()
        }
        do {
            var allocatedInPass = false
            fun isEligible(
                view: WorkerView,
                card: RevealedNodeCard,
                selectedIds: Set<NodeId>
            ): Boolean =
                card.nodeId !in selectedIds &&
                    needsPassingGrade(view.groupId, card.nodeId, states) &&
                    prerequisitesSatisfied(card.nodeId, atlas, states) &&
                    replicationSlotAvailable(card.nodeId, atlas, states, plannedCounts)

            orderedViews.sortedWith(
                compareByDescending<WorkerView> { view ->
                    val selectedIds = selectedByGroup.getValue(view.groupId)
                        .mapTo(hashSetOf(), RevealedNodeCard::nodeId)
                    revealedByGroup.getValue(view.groupId).values.any { card ->
                        card.nodeId in priorityNodeIdsByGroup[view.groupId].orEmpty() &&
                            isEligible(view, card, selectedIds)
                    }
                }.thenBy { it.groupId.value }
            ).forEach { view ->
                val selected = selectedByGroup.getValue(view.groupId)
                if (selected.size >= maximumNodesPerGroup) return@forEach
                val selectedIds = selected.mapTo(hashSetOf(), RevealedNodeCard::nodeId)
                val priorityNodeIds = priorityNodeIdsByGroup[view.groupId].orEmpty()
                val candidate = revealedByGroup.getValue(view.groupId).values
                    .asSequence()
                    .filter { card -> isEligible(view, card, selectedIds) }
                    .sortedWith(
                        compareByDescending<RevealedNodeCard> {
                            it.nodeId in priorityNodeIds
                        }
                            .thenBy { plannedCounts[it.nodeId] ?: 0 }
                            .thenBy {
                                states.getValue(it.nodeId).leases.count { lease ->
                                    lease.state == LeaseState.ACTIVE
                                }
                            }
                            .thenByDescending { atlas.nodes.getValue(it.nodeId).rewardSpec.credits }
                            .thenBy { it.nodeId.value }
                    )
                    .firstOrNull()
                if (candidate != null) {
                    selected += candidate
                    plannedCounts.merge(candidate.nodeId, 1, Int::plus)
                    allocatedInPass = true
                }
            }
        } while (allocatedInPass)

        return orderedViews.associate { view ->
            val candidates = selectedByGroup.getValue(view.groupId)
            val bids = candidates.map { card ->
                NodeBid(
                    bidId = CouncilDeterministicIds.create(
                        "node-bid",
                        atlas.runId,
                        view.groupId.value,
                        view.round.toString(),
                        card.nodeId.value
                    ),
                    groupId = view.groupId,
                    nodeId = card.nodeId,
                    intendedArtifactType = card.deliverableContract.acceptedArtifactTypes
                        .sortedBy { it.name }
                        .first(),
                    estimatedCredits = card.rewardSpec.credits,
                    expectedProofMethod = card.proofContract.method,
                    reason = if (
                        card.nodeId in priorityNodeIdsByGroup[view.groupId].orEmpty()
                    ) {
                        "Selected from the revealed frontier to satisfy pending Council-sealed promoted-tool reuse."
                    } else {
                        "Selected from the revealed frontier under deterministic congestion and prerequisite rules."
                    },
                    round = view.round
                )
            }
            val distinctNodeCount = bids.map(NodeBid::nodeId).distinct().size
            val diversityScore = if (bids.isEmpty()) 0.0 else distinctNodeCount.toDouble() / bids.size
            val portfolio = NodePortfolio(
                portfolioId = CouncilDeterministicIds.create(
                    "node-portfolio",
                    atlas.runId,
                    view.groupId.value,
                    view.round.toString(),
                    bids.joinToString(",") { it.nodeId.value }
                ),
                runId = atlas.runId,
                atlasId = atlas.atlasId,
                atlasHash = atlas.atlasHash,
                groupId = view.groupId,
                round = view.round,
                bids = bids,
                diversityScore = diversityScore
            ).withComputedHash()
            view.groupId to portfolio
        }
    }

    private fun needsPassingGrade(
        groupId: AtlasGroupId,
        nodeId: NodeId,
        states: Map<NodeId, NodeExecutionState>
    ): Boolean = states.getValue(nodeId).grades.none { grade ->
        grade.groupId == groupId && grade.verdict == GradeVerdict.VALID_PASS
    }

    private fun prerequisitesSatisfied(
        nodeId: NodeId,
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>
    ): Boolean = atlas.nodes.getValue(nodeId).prerequisiteIds.all { prerequisiteId ->
        states.getValue(prerequisiteId).grades.any { grade ->
            grade.verdict == GradeVerdict.VALID_PASS
        }
    }

    private fun replicationSlotAvailable(
        nodeId: NodeId,
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>,
        plannedCounts: Map<NodeId, Int>
    ): Boolean {
        val node = atlas.nodes.getValue(nodeId)
        val activeCount = states.getValue(nodeId).leases.count { it.state == LeaseState.ACTIVE }
        val plannedCount = plannedCounts[nodeId] ?: 0
        val allowed = when (node.replicationPolicy.mode) {
            ReplicationMode.REQUIRED, ReplicationMode.ADVERSARIAL ->
                node.replicationPolicy.requiredCount
            ReplicationMode.NONE, ReplicationMode.OPTIONAL -> 1
        }
        return activeCount + plannedCount < allowed
    }
}
