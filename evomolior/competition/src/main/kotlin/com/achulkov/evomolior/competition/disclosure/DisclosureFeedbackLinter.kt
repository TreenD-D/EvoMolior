package com.achulkov.evomolior.competition.disclosure

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.DisclosureFeedbackSplit
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.GroupFeedbackPacket

/** Rejects worker feedback containing material outside one group's revealed Atlas boundary. */
class DisclosureFeedbackLinter {
    /**
     * Validates a three-way rationale split and returns its hash-bound form.
     */
    fun validateAndSeal(
        atlas: NodeAtlas,
        groupId: AtlasGroupId,
        currentStageIndex: Int,
        revealedNodeIds: Set<NodeId>,
        split: DisclosureFeedbackSplit
    ): DisclosureFeedbackSplit {
        val feedback = split.workerFeedback
        if (feedback != null) {
            val stage = atlas.stageDefinitions.single { it.stageIndex == currentStageIndex }
            require(feedback.stageId == stage.stageId) {
                "Worker feedback must target the group's current stage"
            }
            require(feedback.failedVisibleNodeIds.all { it in revealedNodeIds && it in stage.nodeIds }) {
                "Worker feedback references an unrevealed or non-current node"
            }
            val workerText = (feedback.evidenceGaps + feedback.actionableNextSteps).joinToString("\n")
            val forbiddenTerms = atlas.nodes.values
                .filter { it.id !in revealedNodeIds }
                .flatMap { node ->
                    listOf(
                        node.id.value,
                        node.receiverWhyRef,
                        node.limitationStatement,
                        node.capabilityTarget,
                        node.atomicQuestion
                    )
                }
                .filter { it.isNotBlank() }
            require(forbiddenTerms.none { term -> workerText.contains(term, ignoreCase = true) }) {
                "Worker feedback leaks unrevealed Atlas material"
            }
            require(!workerText.contains("hidden test", ignoreCase = true) &&
                !workerText.contains("grader-private", ignoreCase = true) &&
                !workerText.contains("receiver contract", ignoreCase = true) &&
                !workerText.contains("other group", ignoreCase = true)
            ) {
                "Worker feedback leaks a forbidden visibility class"
            }
            val expectedHash = AtlasHashing.hashGroupFeedbackPacket(feedback)
            require(
                feedback.feedbackHash.isBlank() ||
                    AtlasHashing.constantTimeEquals(feedback.feedbackHash, expectedHash)
            ) {
                "Worker feedback hash is invalid"
            }
        }
        val sealed = split.copy(
            workerFeedback = feedback?.copy(
                feedbackHash = AtlasHashing.hashGroupFeedbackPacket(feedback)
            ),
            splitHash = ""
        ).withComputedHash()
        require(sealed.hasValidHash()) {
            "Disclosure feedback split hash is invalid for ${groupId.value}"
        }
        return sealed
    }
}
