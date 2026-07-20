package com.achulkov.evomolior.competition.atlas

import com.achulkov.evomolior.competition.disclosure.DisclosureStageAuthority
import com.achulkov.evomolior.competition.disclosure.StageAdvance
import com.achulkov.evomolior.competition.resourcemap.ResourceOwnershipAuthority
import com.achulkov.evomolior.competition.resourcemap.ResourceOwnershipChange
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.DisclosureLineageEntry
import com.achulkov.evomolior.substrate.atlas.AtlasExecutionCommitRequest
import com.achulkov.evomolior.substrate.atlas.AtlasMutationType
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionException
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource

/**
 * Atlas-backed resource ownership authority used by the competition engine.
 */
class LegacyAtlasResourceOwnershipAuthority(
    private val runId: String,
    private val atlasId: String,
    private val repository: AtlasTransactionRepository,
    private val legacyMode:
        com.achulkov.evomolior.competition.resourcemap.LegacyResourceCaptureMode
) : ResourceOwnershipAuthority {

    /** Commits ownership before the ResourceMap projection changes. */
    override suspend fun commit(changes: List<ResourceOwnershipChange>) {
        require(
            legacyMode ==
                com.achulkov.evomolior.competition.resourcemap.LegacyResourceCaptureMode
                    .TEST_COMPATIBILITY_ONLY
        )
        if (changes.isEmpty()) return
        val atlas = requireNotNull(repository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val states = repository.currentStates(runId, atlasId)
        val projections = requireNotNull(repository.currentProjections(runId, atlasId)) {
            "Atlas $atlasId has no committed projections"
        }
        val resourceMappings = projections.mappings
            .filter { it.source == LegacyNodeSource.RESOURCE && it.scopeId == runId }
            .associate { it.sourceNodeId to it.atlasNodeId }
        val changesByNode = changes.groupBy { change ->
            resourceMappings[change.nodeId]
                ?: throw AtlasTransactionException(
                    com.achulkov.evomolior.substrate.atlas.AtlasTransactionErrorCode.INVALID_PROJECTION,
                    "Resource ${change.nodeId} has no Atlas mapping"
                )
        }
        val nextOwnersByNode = changesByNode.mapValues { (nodeId, nodeChanges) ->
            val newOwners = nodeChanges.map(ResourceOwnershipChange::newOwnerId).distinct()
            require(newOwners.size == 1) { "Conflicting ownership changes for ${nodeId.value}" }
            val currentOwner = states.getValue(nodeId).ownerGroupId?.value
            require(nodeChanges.all { it.previousOwnerId == currentOwner }) {
                "Stale resource ownership projection for ${nodeId.value}"
            }
            newOwners.single()
        }
        val changedStates = nextOwnersByNode.map { (nodeId, newOwner) ->
            states.getValue(nodeId).copy(
                ownerGroupId = newOwner?.let(::AtlasGroupId),
                captureState = if (newOwner == null) CaptureState.UNOWNED else CaptureState.CAPTURED
            )
        }
        val nextResourceProjection = projections.resources.copy(
            nodes = projections.resources.nodes.map { resource ->
                val mappedNodeId = resourceMappings[resource.resourceNodeId]
                if (mappedNodeId != null && nextOwnersByNode.containsKey(mappedNodeId)) {
                    resource.copy(claimedByGroupId = nextOwnersByNode[mappedNodeId])
                } else {
                    resource
                }
            }
        )
        repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = runId,
                atlasId = atlasId,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = changedStates.associate { state ->
                    state.nodeId to AtlasHashing.hashExecutionState(states.getValue(state.nodeId))
                },
                mutationType = AtlasMutationType.OWNERSHIP.name,
                changedStates = changedStates,
                projections = projections.copy(resources = nextResourceProjection)
            )
        )
    }
}

/**
 * Atlas-backed progressive-disclosure stage authority.
 */
class AtlasDisclosureStageAuthority(
    private val runId: String,
    private val atlasId: String,
    private val repository: AtlasTransactionRepository
) : DisclosureStageAuthority {

    /** Commits reveal state before the local group stage advances. */
    override suspend fun commit(advance: StageAdvance) {
        val atlas = requireNotNull(repository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val states = repository.currentStates(runId, atlasId)
        val projections = requireNotNull(repository.currentProjections(runId, atlasId)) {
            "Atlas $atlasId has no committed projections"
        }
        val currentStage = projections.disclosure.groupStageIndices[advance.groupId]
        require(currentStage == advance.fromStage) {
            "Stale disclosure stage for ${advance.groupId}: expected ${advance.fromStage}, found $currentStage"
        }
        val decision = requireNotNull(advance.decision) {
            "Atlas disclosure requires an exact mandatory-node decision"
        }
        val payload = requireNotNull(advance.revealPayload) {
            "Atlas disclosure requires an exact group-local payload"
        }
        val authorization = requireNotNull(advance.revealAuthorization) {
            "Atlas disclosure requires an exact reveal authorization"
        }
        require(
            decision.hasValidHash() &&
                decision.groupId.value == advance.groupId &&
                decision.fromStageIndex == advance.fromStage &&
                decision.toStageIndex == advance.toStage &&
                payload.groupId.value == advance.groupId &&
                payload.stageIndex == advance.toStage &&
                payload.hasValidHashes(atlas) &&
                authorization.hasValidHash() &&
                authorization.decisionHash == decision.decisionHash &&
                authorization.deliveredPayloadHash == payload.payloadHash &&
                authorization.stagePayloadHash == payload.stagePayloadHash
        ) {
            "Disclosure advance authority or payload commitment is invalid"
        }
        val nextStageIndices = projections.disclosure.groupStageIndices +
            (advance.groupId to advance.toStage)
        val changedStates = atlas.nodes.keys.mapNotNull { nodeId ->
            val stage = atlas.stageDefinitions.first { nodeId in it.nodeIds }
            val current = states.getValue(nodeId)
            val nextGroups = nextStageIndices
                .filterValues { stageIndex -> stageIndex >= stage.stageIndex }
                .keys
                .mapTo(linkedSetOf(), ::AtlasGroupId)
            val lineage = if (nodeId in payload.revealedNodes.mapTo(linkedSetOf()) { it.nodeId }) {
                current.disclosureLineage + DisclosureLineageEntry(authorization)
            } else {
                current.disclosureLineage
            }
            current.copy(
                revealedToGroups = nextGroups,
                disclosureLineage = lineage
            ).takeIf { it != current }
        }
        require(changedStates.isNotEmpty()) {
            "Disclosure advance produced no group-local state change"
        }

        repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = runId,
                atlasId = atlasId,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = changedStates.associate { state ->
                    state.nodeId to AtlasHashing.hashExecutionState(states.getValue(state.nodeId))
                },
                mutationType = AtlasMutationType.DISCLOSURE.name,
                changedStates = changedStates,
                projections = projections.copy(
                    disclosure = projections.disclosure.copy(
                        groupStageIndices = nextStageIndices
                    )
                )
            )
        )
    }
}
