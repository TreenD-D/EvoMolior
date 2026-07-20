package com.achulkov.evomolior.competition.atlas

import com.achulkov.evomolior.competition.scoring.NodeCaptureResolution
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGrade
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.contract.DeliverableNodeGradeHistoryEntry
import com.achulkov.evomolior.substrate.atlas.AtlasCaptureCommitRequest
import com.achulkov.evomolior.substrate.atlas.AtlasCommitResult
import com.achulkov.evomolior.substrate.atlas.AtlasExecutionCommitRequest
import com.achulkov.evomolior.substrate.atlas.AtlasMutationType
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository

/**
 * Commits capture decisions through the sole Atlas transaction authority.
 *
 * Valid resolutions append exact packet and grade identities, ownership, capture state, the
 * replay decision, and compatibility projections in one SQLite transaction. Decisions with no
 * state mutation are retained as audit records without touching ownership.
 */
class AtlasNodeCaptureAuthority(
    private val runId: String,
    private val atlasId: String,
    private val atlasRepository: AtlasTransactionRepository,
    private val captureRepository: NodeCaptureRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * Commits a replay result.
     *
     * @return Atlas commit for a state-changing decision, or null for audit-only outcomes.
     */
    suspend fun commit(resolution: NodeCaptureResolution): AtlasCommitResult? {
        val nextState = resolution.nextState
        if (nextState == null) {
            captureRepository.appendCaptureDecision(resolution.decision)
            return null
        }
        require(resolution.decision.runId == runId) {
            "Capture resolution belongs to a different run"
        }
        val atlas = requireNotNull(atlasRepository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val states = atlasRepository.currentStates(runId, atlasId)
        val current = states.getValue(resolution.decision.nodeId)
        require(
            AtlasHashing.constantTimeEquals(
                resolution.expectedStateHash,
                AtlasHashing.hashExecutionState(current)
            )
        ) {
            "Capture resolution is stale for ${current.nodeId.value}"
        }
        val projections = requireNotNull(atlasRepository.currentProjections(runId, atlasId)) {
            "Atlas $atlasId has no committed projections"
        }
        val nextProjections = projectCapture(
            projections = projections,
            currentState = current,
            nextState = nextState
        )
        return atlasRepository.commitCapture(
            AtlasCaptureCommitRequest(
                execution = AtlasExecutionCommitRequest(
                    runId = runId,
                    atlasId = atlasId,
                    expectedAtlasHash = atlas.atlasHash,
                    expectedStateHashes = mapOf(
                        current.nodeId to resolution.expectedStateHash
                    ),
                    mutationType = AtlasMutationType.CAPTURE.name,
                    changedStates = listOf(nextState),
                    projections = nextProjections
                ),
                decision = resolution.decision
            )
        )
    }

    private fun projectCapture(
        projections: AtlasProjectionSet,
        currentState: NodeExecutionState,
        nextState: NodeExecutionState
    ): AtlasProjectionSet {
        val resourceNodeIds = projections.mappings
            .filter {
                it.source == LegacyNodeSource.RESOURCE &&
                    it.scopeId == runId &&
                    it.atlasNodeId == nextState.nodeId
            }
            .mapTo(mutableSetOf()) { it.sourceNodeId }
        val nextResources = projections.resources.copy(
            nodes = projections.resources.nodes.map { resource ->
                if (resource.resourceNodeId in resourceNodeIds) {
                    resource.copy(claimedByGroupId = nextState.ownerGroupId?.value)
                } else {
                    resource
                }
            }
        )

        val existingBundleHashes = currentState.grades.mapTo(mutableSetOf()) {
            it.scoreBundleHash
        }
        val newGrades = nextState.grades.filterNot {
            it.scoreBundleHash in existingBundleHashes
        }
        val timestamp = now()
        val nextDeliverables = projections.deliverables.map { tree ->
            val sourceNodeId = projections.mappings.firstOrNull {
                it.source == LegacyNodeSource.DELIVERABLE &&
                    it.scopeId == tree.groupId &&
                    it.atlasNodeId == nextState.nodeId
            }?.sourceNodeId
            if (sourceNodeId == null) {
                tree
            } else {
                val groupGrades = newGrades.filter { it.groupId.value == tree.groupId }
                if (groupGrades.isEmpty()) {
                    tree
                } else {
                    tree.copy(
                        nodes = tree.nodes.map { node ->
                            if (node.nodeId == sourceNodeId) {
                                node.copy(
                                    gradingHistory = node.gradingHistory +
                                        groupGrades.map { it.toProjectionHistory(timestamp) }
                                )
                            } else {
                                node
                            }
                        },
                        version = tree.version + 1,
                        updatedAtMs = timestamp
                    )
                }
            }
        }
        return projections.copy(
            resources = nextResources,
            deliverables = nextDeliverables
        )
    }

    private fun NodeGrade.toProjectionHistory(timestamp: Long) =
        DeliverableNodeGradeHistoryEntry(
            checkinId = gradeId,
            dimensionScores = scoreVector?.asDimensionScores().orEmpty(),
            graderIds = graderIds.sorted(),
            rubricVersion = "node-grade:$scoreBundleHash",
            passedThreshold = verdict == GradeVerdict.VALID_PASS,
            recordedAtMs = timestamp
        )

    private fun NodeScoreVector.asDimensionScores(): Map<String, Double> = linkedMapOf(
        "correctness" to correctness,
        "evidenceQuality" to evidenceQuality,
        "reproducibility" to reproducibility,
        "robustness" to robustness,
        "integrationUtility" to integrationUtility,
        "receiverRelevance" to receiverRelevance,
        "novelty" to novelty,
        "efficiency" to efficiency,
        "confidence" to confidence
    )
}
