package com.achulkov.evomolior.orchestration.worker

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.toolValidationNodeIds
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository

/**
 * Exact same-group promoted-tool authority available to validation and expected-reuse nodes.
 *
 * @param toolCardsByNodeId Exact active promoted versions scoped to each sealed consumer node.
 * @param pendingNodeIds Expected-reuse targets which still lack successful exact-version
 * `AGENT_USE`.
 */
data class ExpectedToolReuseAuthority(
    val toolCardsByNodeId: Map<NodeId, List<WorkerToolCard>>,
    val pendingNodeIds: Set<NodeId>
)

/**
 * Resolves active promoted versions against their immutable Council commitments.
 *
 * Registry rows without Atlas lifecycle authority are ignored. Any partially matching Atlas-bound
 * row fails closed rather than widening worker context or scheduling authority.
 */
class ExpectedToolReuseAuthorityResolver {
    /** Returns exact cards and pending expected targets for one isolated worker group. */
    suspend fun resolve(
        database: EvoMoliorDatabase,
        atlas: NodeAtlas,
        groupId: AtlasGroupId
    ): ExpectedToolReuseAuthority {
        val repository = ToolInfrastructureRepository(database)
        val resolved = database.toolRegistryDao()
            .getActive(atlas.runId, groupId.value, domain = null)
            .sortedBy { it.id }
            .mapNotNull { registryEntry ->
                val history = repository.lifecycle(
                    com.achulkov.evomolior.core.atlas.ToolVersionId(registryEntry.id)
                )
                if (history.isEmpty()) return@mapNotNull null
                require(history.all { it.hasValidHash() }) {
                    "Active tool ${registryEntry.id} has invalid lifecycle authority"
                }
                require(
                    ToolLifecyclePolicy.allows(null, history.first()) &&
                        history.zipWithNext().all { (previous, next) ->
                            ToolLifecyclePolicy.allows(previous, next)
                        }
                ) {
                    "Active tool ${registryEntry.id} has a broken lifecycle chain"
                }
                val first = history.first()
                require(
                    first.runId == atlas.runId &&
                        first.groupId == groupId &&
                        first.toolVersionId.value == registryEntry.id &&
                        registryEntry.runId == atlas.runId &&
                        registryEntry.groupId == groupId.value &&
                        history.any { it.state == ToolLifecycleState.PROMOTED } &&
                        history.last().state !in TERMINAL_INACTIVE_STATES
                ) {
                    "Active tool ${registryEntry.id} lacks same-group promoted authority"
                }
                val persistedCommitment = requireNotNull(
                    repository.opportunity(first.opportunityId)
                ) {
                    "Active tool ${registryEntry.id} lacks sealed opportunity authority"
                }
                val atlasNode = requireNotNull(atlas.nodes[persistedCommitment.nodeId]) {
                    "Active tool ${registryEntry.id} references an unknown Atlas origin node"
                }
                val atlasOpportunity = requireNotNull(atlasNode.toolOpportunity) {
                    "Active tool ${registryEntry.id} references an Atlas node without an opportunity"
                }
                val exactCommitment = ToolOpportunityCommitment(
                    runId = atlas.runId,
                    atlasHash = atlas.atlasHash,
                    nodeId = atlasNode.id,
                    opportunity = atlasOpportunity
                ).withComputedHash()
                require(
                    persistedCommitment == exactCommitment &&
                        first.opportunityId == atlasOpportunity.opportunityId &&
                        first.originNodeId == atlasNode.id
                ) {
                    "Active tool ${registryEntry.id} does not match its exact sealed Atlas opportunity"
                }
                ResolvedPromotedTool(
                    cardTemplate = WorkerToolCard(
                        toolId = registryEntry.id,
                        name = registryEntry.name,
                        version = registryEntry.version,
                        opportunityId = atlasOpportunity.opportunityId,
                        opportunityCommitmentHash = exactCommitment.commitmentHash,
                        requiredForNodeId = atlasOpportunity.expectedReuseNodeIds
                            .minBy(NodeId::value),
                        executionPurpose = ToolExecutionPurpose.AGENT_USE,
                        description = atlasOpportunity.repeatedQuestionPattern,
                        inputSchemaHash = AtlasHashing.hashText(registryEntry.inputSchemaJson),
                        outputSchemaHash = AtlasHashing.hashText(registryEntry.outputSchemaJson)
                    ),
                    validationNodeIds = atlas.toolValidationNodeIds(atlasNode.id),
                    expectedNodeIds = atlasOpportunity.expectedReuseNodeIds,
                    completedNodeIds = repository.executions(first.toolVersionId)
                        .filter {
                            it.purpose == ToolExecutionPurpose.AGENT_USE &&
                                it.result == ToolExecutionResult.SUCCEEDED &&
                                it.opportunityId == first.opportunityId
                        }
                        .mapTo(linkedSetOf()) { it.nodeId }
                )
            }
        require(
            resolved.groupBy { it.cardTemplate.opportunityId }.values.all { it.size == 1 }
        ) {
            "A Council opportunity must expose exactly one active promoted version"
        }

        val cardsByNode = resolved
            .flatMap { tool ->
                tool.validationNodeIds.map { nodeId ->
                    nodeId to tool.cardTemplate.copy(
                        requiredForNodeId = nodeId,
                        executionPurpose = ToolExecutionPurpose.VALIDATION_TEST
                    )
                } + tool.expectedNodeIds.map { nodeId ->
                    nodeId to tool.cardTemplate.copy(
                        requiredForNodeId = nodeId,
                        executionPurpose = ToolExecutionPurpose.AGENT_USE
                    )
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, cards) -> cards.sortedBy(WorkerToolCard::toolId) }
        val pendingNodeIds = resolved.flatMapTo(linkedSetOf()) { tool ->
            tool.expectedNodeIds - tool.completedNodeIds
        }
        return ExpectedToolReuseAuthority(cardsByNode, pendingNodeIds)
    }

    private data class ResolvedPromotedTool(
        val cardTemplate: WorkerToolCard,
        val validationNodeIds: Set<NodeId>,
        val expectedNodeIds: Set<NodeId>,
        val completedNodeIds: Set<NodeId>
    )

    private companion object {
        val TERMINAL_INACTIVE_STATES = setOf(
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED,
            ToolLifecycleState.RETIRED
        )
    }
}
