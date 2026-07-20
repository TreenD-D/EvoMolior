package com.achulkov.evomolior.competition.outlier

import com.achulkov.evomolior.core.atlas.AmendmentDecision
import com.achulkov.evomolior.core.atlas.AmendmentType
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAmendment
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.HiddenMapAnticipation
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEdge
import com.achulkov.evomolior.core.atlas.NodeEdgeKind
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyScreeningResult
import com.achulkov.evomolior.core.atlas.NoveltyScreeningStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict

/** Deterministic duplicate, leakage, and hidden-map anticipation screening. */
class OutlierNoveltyScreener(
    private val revealedDuplicateThreshold: Double = 0.82,
    private val hiddenAnticipationThreshold: Double = 0.55,
    private val directAnticipationThreshold: Double = 0.78,
    private val maximumUnjustifiedCost: Double = 100.0
) {
    /**
     * Screens one proposal without returning hidden node identity or content.
     */
    fun screen(
        atlas: NodeAtlas,
        states: Map<NodeId, NodeExecutionState>,
        proposal: NoveltyVectorProposal,
        canonEntries: Map<String, String> = emptyMap()
    ): NoveltyScreeningResult {
        require(proposal.hasValidHash() && proposal.atlasHash == atlas.atlasHash) {
            "Novelty screening requires a valid proposal against the exact Atlas"
        }
        val revealedNodeIds = states.values
            .filter { proposal.groupId in it.revealedToGroups }
            .mapTo(linkedSetOf(), NodeExecutionState::nodeId)
        val disconnected = proposal.affectedVisibleNodeIds.any { it !in revealedNodeIds }
        val proposalText = proposal.semanticText()
        val revealedDuplicates = revealedNodeIds.filterTo(linkedSetOf()) { nodeId ->
            similarity(proposalText, atlas.nodes.getValue(nodeId).semanticText()) >=
                revealedDuplicateThreshold
        }
        val canonDuplicates = canonEntries.filterValues { content ->
            similarity(proposalText, content) >= revealedDuplicateThreshold
        }.keys
        val hiddenNodes = atlas.nodes.values.filter { it.id !in revealedNodeIds }
        val hiddenVerbatimLeak = hiddenNodes.any { node ->
            listOf(
                node.receiverWhyRef,
                node.limitationStatement,
                node.capabilityTarget,
                node.atomicQuestion
            ).filter { it.length >= 20 }.any { proposalText.contains(it, ignoreCase = true) }
        }
        val maximumHiddenSimilarity = hiddenNodes.maxOfOrNull { node ->
            similarity(proposalText, node.semanticText())
        } ?: 0.0
        val anticipation = when {
            hiddenVerbatimLeak -> HiddenMapAnticipation.NONE
            maximumHiddenSimilarity >= directAnticipationThreshold -> HiddenMapAnticipation.DIRECT
            maximumHiddenSimilarity >= hiddenAnticipationThreshold -> HiddenMapAnticipation.PARTIAL
            else -> HiddenMapAnticipation.NONE
        }
        val status = when {
            disconnected -> NoveltyScreeningStatus.DISCONNECTED
            proposal.falsifiablePrediction.isBlank() || proposal.minimalTestOrArtifact.isBlank() ->
                NoveltyScreeningStatus.UNTESTABLE
            revealedDuplicates.isNotEmpty() -> NoveltyScreeningStatus.DUPLICATE_REVEALED
            canonDuplicates.isNotEmpty() -> NoveltyScreeningStatus.DUPLICATE_CANON
            hiddenVerbatimLeak -> NoveltyScreeningStatus.HIDDEN_INFORMATION_DEPENDENCY
            proposal.expectedCost > maximumUnjustifiedCost &&
                proposal.expectedReceiverImpact.isNullOrBlank() ->
                NoveltyScreeningStatus.COST_UNJUSTIFIED
            else -> NoveltyScreeningStatus.UNIQUE
        }
        val hiddenCommitment = AtlasHashing.hashText(
            listOf(
                proposal.proposalHash,
                anticipation.name,
                maximumHiddenSimilarity.toString(),
                hiddenNodes.map(LimitationNode::payloadHash).sorted().joinToString("|")
            ).joinToString(":")
        )
        return NoveltyScreeningResult(
            screeningId = "${proposal.proposalId}:screen",
            proposalHash = proposal.proposalHash,
            status = status,
            revealedDuplicateNodeIds = revealedDuplicates,
            canonDuplicateArtifactIds = canonDuplicates,
            hiddenMapAnticipation = anticipation,
            sealedHiddenComparisonHash = hiddenCommitment,
            reason = status.reason()
        ).withComputedHash()
    }

    private fun NoveltyVectorProposal.semanticText(): String = listOf(
        missingLayerOrAssumption,
        proposedVector,
        whyCurrentMapMayMissIt,
        falsifiablePrediction,
        minimalTestOrArtifact,
        expectedReceiverImpact.orEmpty(),
        conflictWithExistingAssumptions.joinToString(" ")
    ).joinToString(" ")

    private fun LimitationNode.semanticText(): String = listOf(
        limitationStatement,
        capabilityTarget,
        atomicQuestion,
        proofContract.acceptanceCriteria.joinToString(" ")
    ).joinToString(" ")

    private fun similarity(left: String, right: String): Double {
        val leftTokens = tokens(left)
        val rightTokens = tokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        return leftTokens.intersect(rightTokens).size.toDouble() /
            leftTokens.union(rightTokens).size
    }

    private fun tokens(value: String): Set<String> = value
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .asSequence()
        .filter { it.length >= 3 }
        .toSet()

    private fun NoveltyScreeningStatus.reason(): String = when (this) {
        NoveltyScreeningStatus.UNIQUE -> "Connected, testable, non-duplicate proposal"
        NoveltyScreeningStatus.DUPLICATE_REVEALED -> "Proposal duplicates a revealed Atlas node"
        NoveltyScreeningStatus.DUPLICATE_CANON -> "Proposal duplicates existing group canon"
        NoveltyScreeningStatus.DISCONNECTED -> "Proposal is disconnected from the visible map"
        NoveltyScreeningStatus.UNTESTABLE -> "Proposal has no falsifiable minimal test"
        NoveltyScreeningStatus.COST_UNJUSTIFIED -> "Expected cost lacks receiver-impact justification"
        NoveltyScreeningStatus.HIDDEN_INFORMATION_DEPENDENCY ->
            "Proposal depends on verbatim hidden Atlas material"
    }
}

/** Exact independent-grader quorum resolver for shadow-node proposals. */
class ShadowNodeGraderResolver(
    private val acceptanceThreshold: Double = 0.6,
    private val minimumCostDiscipline: Double = 0.4
) {
    /** Resolves one proposal using exactly the expected independent grader groups. */
    fun resolve(
        proposal: NoveltyVectorProposal,
        screening: NoveltyScreeningResult,
        expectedGraderGroupIds: Set<String>,
        assessments: List<NoveltyGraderAssessment>
    ): ShadowNodeGraderVerdict {
        require(expectedGraderGroupIds.isNotEmpty()) { "Novelty grading requires grader quorum" }
        require(screening.hasValidHash() && screening.proposalHash == proposal.proposalHash) {
            "Novelty grading requires the exact screening result"
        }
        if (screening.status != NoveltyScreeningStatus.UNIQUE) {
            return verdict(
                proposal,
                ShadowNodeDecision.REJECT,
                null,
                emptySet(),
                expectedGraderGroupIds.size,
                screening.reason
            )
        }
        val matching = assessments.filter { it.proposalHash == proposal.proposalHash }
        val byGrader = matching.groupBy(NoveltyGraderAssessment::graderGroupId)
        val exact = expectedGraderGroupIds.mapNotNull { graderId ->
            byGrader[graderId]?.singleOrNull()
        }
        if (byGrader.keys != expectedGraderGroupIds ||
            exact.size != expectedGraderGroupIds.size ||
            exact.any { !it.hasValidHash() || it.status == NoveltyAssessmentStatus.INVALID }
        ) {
            return verdict(
                proposal,
                ShadowNodeDecision.INVALID,
                null,
                emptySet(),
                expectedGraderGroupIds.size,
                "Exact novelty grader cardinality or authority failed"
            )
        }
        if (exact.any { it.status == NoveltyAssessmentStatus.ABSTAINED }) {
            return verdict(
                proposal,
                ShadowNodeDecision.DEFER,
                null,
                exact.mapTo(linkedSetOf(), NoveltyGraderAssessment::assessmentHash),
                expectedGraderGroupIds.size,
                "At least one independent grader abstained"
            )
        }
        val vectors = exact.map { requireNotNull(it.vector) }
        val aggregate = average(vectors)
        val minimumMaterialScore = minOf(
            aggregate.novelty,
            aggregate.receiverRelevance,
            aggregate.testability,
            aggregate.plausibility,
            aggregate.expectedImpact
        )
        val decision = if (minimumMaterialScore >= acceptanceThreshold &&
            aggregate.costDiscipline >= minimumCostDiscipline
        ) {
            ShadowNodeDecision.ACCEPT
        } else {
            ShadowNodeDecision.REJECT
        }
        return verdict(
            proposal,
            decision,
            aggregate,
            exact.mapTo(linkedSetOf(), NoveltyGraderAssessment::assessmentHash),
            expectedGraderGroupIds.size,
            if (decision == ShadowNodeDecision.ACCEPT) {
                "Independent graders accepted novelty, relevance, testability, impact, and cost"
            } else {
                "Proposal did not meet the frozen novelty quality floors"
            }
        )
    }

    private fun average(values: List<NoveltyGraderVector>) = NoveltyGraderVector(
        novelty = values.map(NoveltyGraderVector::novelty).average(),
        receiverRelevance = values.map(NoveltyGraderVector::receiverRelevance).average(),
        testability = values.map(NoveltyGraderVector::testability).average(),
        plausibility = values.map(NoveltyGraderVector::plausibility).average(),
        expectedImpact = values.map(NoveltyGraderVector::expectedImpact).average(),
        costDiscipline = values.map(NoveltyGraderVector::costDiscipline).average(),
        downstreamAdoption = values.mapNotNull(NoveltyGraderVector::downstreamAdoption)
            .takeIf { it.isNotEmpty() }
            ?.average()
    )

    private fun verdict(
        proposal: NoveltyVectorProposal,
        decision: ShadowNodeDecision,
        vector: NoveltyGraderVector?,
        hashes: Set<String>,
        quorum: Int,
        reason: String
    ) = ShadowNodeGraderVerdict(
        proposalHash = proposal.proposalHash,
        decision = decision,
        aggregateVector = vector,
        assessmentHashes = hashes,
        requiredQuorum = quorum,
        reason = reason
    ).withComputedHash()
}

data class CompiledNoveltyAmendment(
    val amendment: AtlasAmendment,
    val nextAtlas: NodeAtlas,
    val addedNode: LimitationNode
)

/** Compiles an accepted shadow node into a next-round Atlas version. */
class NoveltyAmendmentCompiler {
    fun compile(
        atlas: NodeAtlas,
        proposal: NoveltyVectorProposal,
        screening: NoveltyScreeningResult,
        verdict: ShadowNodeGraderVerdict,
        effectiveRound: Int
    ): CompiledNoveltyAmendment {
        require(proposal.hasValidHash() &&
            screening.hasValidHash() &&
            verdict.hasValidHash() &&
            screening.proposalHash == proposal.proposalHash &&
            verdict.proposalHash == proposal.proposalHash &&
            screening.status == NoveltyScreeningStatus.UNIQUE &&
            verdict.decision == ShadowNodeDecision.ACCEPT &&
            effectiveRound > proposal.round
        ) {
            "Only a valid accepted proposal can amend a later Atlas round"
        }
        require(proposal.affectedVisibleNodeIds.all(atlas.nodes::containsKey)) {
            "Novelty proposal references an unknown Atlas node"
        }
        val affected = proposal.affectedVisibleNodeIds.sortedBy(NodeId::value)
        val targetStage = atlas.stageDefinitions
            .filter { stage -> stage.nodeIds.any(proposal.affectedVisibleNodeIds::contains) }
            .maxBy { it.stageIndex }
        val template = atlas.nodes.getValue(affected.first())
        val nodeId = NodeId(
            "shadow-" + AtlasHashing.hashText(
                "${atlas.atlasHash}:${proposal.proposalHash}"
            ).take(24)
        )
        val node = LimitationNode(
            id = nodeId,
            kind = proposal.noveltyType.nodeKind(),
            layer = targetStage.stageIndex,
            parentIds = proposal.affectedVisibleNodeIds,
            prerequisiteIds = proposal.affectedVisibleNodeIds,
            receiverWhyRef = "outlier-proposal:${proposal.proposalHash}",
            limitationStatement = proposal.missingLayerOrAssumption,
            capabilityTarget = proposal.proposedVector,
            atomicQuestion = proposal.falsifiablePrediction,
            proofContract = ProofContract(
                method = ProofMethod.ADVERSARIAL_REVIEW,
                acceptanceCriteria = setOf(proposal.minimalTestOrArtifact),
                failureIsUseful = true
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(proposal.noveltyType.artifactType()),
                requiredFields = setOf("prediction", "test", "result"),
                finalDeliverableRelationship = proposal.expectedReceiverImpact.orEmpty()
            ),
            revealPolicy = RevealPolicy(
                stageId = targetStage.stageId,
                mode = RevealMode.ACCEPTED_AMENDMENT,
                prerequisiteNodeIds = proposal.affectedVisibleNodeIds,
                minimumRound = effectiveRound
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(
                minimumGrade = template.capturePolicy.minimumGrade,
                recaptureMargin = template.capturePolicy.recaptureMargin,
                nearTieMargin = template.capturePolicy.nearTieMargin,
                maximumConsecutiveRounds = template.capturePolicy.maximumConsecutiveRounds
            ),
            rewardSpec = template.rewardSpec,
            payloadHash = ""
        ).withComputedPayloadHash()
        val nextNodes = atlas.nodes + (node.id to node)
        val nextStages = atlas.stageDefinitions.map { stage ->
            if (stage.stageId == targetStage.stageId) {
                val ids = stage.nodeIds + node.id
                stage.copy(
                    nodeIds = ids,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        stage.stageId,
                        ids,
                        nextNodes
                    )
                )
            } else {
                stage
            }
        }
        val amendmentId = "amendment-${proposal.proposalHash.take(24)}"
        val amendment = AtlasAmendment(
            amendmentId = amendmentId,
            parentAtlasHash = atlas.atlasHash,
            proposedBy = proposal.proposerAgentId,
            proposalType = proposal.noveltyType.amendmentType(),
            addedNodes = listOf(node),
            addedEdges = proposal.affectedVisibleNodeIds.flatMap { affectedNodeId ->
                listOf(
                    NodeEdge(affectedNodeId, node.id, NodeEdgeKind.PARENT),
                    NodeEdge(affectedNodeId, node.id, NodeEdgeKind.PREREQUISITE)
                )
            },
            justificationArtifactIds = listOf(proposal.proposalId),
            graderDecision = AmendmentDecision.ACCEPTED,
            effectiveRound = effectiveRound
        )
        val nextAtlas = atlas.copy(
            version = atlas.version + 1,
            nodes = nextNodes,
            stageDefinitions = nextStages,
            atlasHash = "",
            parentAtlasHash = atlas.atlasHash,
            amendmentIds = atlas.amendmentIds + amendmentId
        ).withComputedHash()
        nextAtlas.validate(atlas).requireValid()
        return CompiledNoveltyAmendment(amendment, nextAtlas, node)
    }

    private fun NoveltyType.nodeKind(): NodeKind = when (this) {
        NoveltyType.TOOL_OR_SIMULATION_LAYER -> NodeKind.TOOL
        NoveltyType.ALTERNATIVE_REPRESENTATION -> NodeKind.MODEL
        NoveltyType.HIDDEN_FAILURE_MODE, NoveltyType.ADVERSARIAL_ATTACK -> NodeKind.VALIDATION
        NoveltyType.NEW_EVIDENCE_SOURCE_CLASS -> NodeKind.EVIDENCE
        NoveltyType.MISSING_INTEGRATION_DEPENDENCY -> NodeKind.INTEGRATION
        else -> NodeKind.HYPOTHESIS
    }

    private fun NoveltyType.artifactType(): ArtifactType = when (nodeKind()) {
        NodeKind.TOOL -> ArtifactType.TOOL
        NodeKind.MODEL -> ArtifactType.MODEL
        NodeKind.VALIDATION -> ArtifactType.VALIDATION_REPORT
        NodeKind.EVIDENCE -> ArtifactType.EVIDENCE_TABLE
        NodeKind.INTEGRATION -> ArtifactType.DELIVERABLE_FRAGMENT
        else -> ArtifactType.HYPOTHESIS
    }

    private fun NoveltyType.amendmentType(): AmendmentType = when (this) {
        NoveltyType.TOOL_OR_SIMULATION_LAYER -> AmendmentType.TOOL_OPPORTUNITY
        NoveltyType.HIDDEN_FAILURE_MODE, NoveltyType.ADVERSARIAL_ATTACK ->
            AmendmentType.VALIDATION
        NoveltyType.ALTERNATIVE_MECHANISM,
        NoveltyType.ALTERNATIVE_REPRESENTATION,
        NoveltyType.CROSS_DOMAIN_ANALOGY,
        NoveltyType.SIMPLIFICATION -> AmendmentType.ALTERNATIVE
        else -> AmendmentType.MISSING_LAYER
    }
}
