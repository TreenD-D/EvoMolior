package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceContent
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceKind
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository

internal const val MAX_EXACT_NODE_EVIDENCE_REFERENCES = 64
internal const val MAX_EXACT_NODE_EVIDENCE_ITEM_BYTES = 64 * 1024
internal const val MAX_EXACT_NODE_EVIDENCE_BATCH_BYTES = 512 * 1024

/**
 * Resolves immutable node assignments into complete, hash-checked model evaluation context.
 *
 * The resolver never truncates or samples packet evidence. A packet that cannot fit the explicit
 * complete-evidence bounds is rejected before either interactive or structured grading.
 */
internal class ExactNodeEvaluationContextResolver(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val atlasRepository: AtlasTransactionRepository =
        AtlasTransactionRepository(substrateFactory),
    private val graderAuthorityRepository: GraderAuthorityRepository =
        GraderAuthorityRepository(substrateFactory)
) {
    /**
     * Resolves every assignment for one target and one independent grader civilisation.
     *
     * @param runId Owning run.
     * @param roundIndex Governed round.
     * @param graderGroupId Receiving grader civilisation.
     * @param assignments Exact target assignments.
     * @return Deterministically ordered complete contexts.
     */
    suspend fun resolve(
        runId: String,
        roundIndex: Int,
        graderGroupId: String,
        assignments: List<NodeGradingAssignment>
    ): List<ExactNodeEvaluationContext> {
        if (assignments.isEmpty()) return emptyList()
        require(assignments.map(NodeGradingAssignment::assignmentId).toSet().size ==
            assignments.size
        ) {
            "Exact node evaluation assignments must be unique"
        }

        val workerAtlas = requireNotNull(atlasRepository.currentAtlas(runId)) {
            "Exact node evaluation requires the sealed worker Atlas"
        }
        workerAtlas.validate().requireValid()
        val freeze = requireNotNull(
            graderAuthorityRepository.criteriaFreezeForRound(
                runId,
                roundIndex,
                graderGroupId
            )
        ) {
            "Exact node evaluation requires a competitive criteria freeze"
        }
        require(freeze.hasValidHash()) {
            "Exact node evaluation criteria freeze is invalid"
        }
        val roundRequirements = graderAuthorityRepository.requirementsForRound(
            runId,
            roundIndex,
            graderGroupId
        )
        val frozenRequirements = roundRequirements.filter {
            it.requirementHash in freeze.requirementHashes
        }
        require(
            frozenRequirements.mapTo(linkedSetOf()) { it.requirementHash } ==
                freeze.requirementHashes &&
                frozenRequirements.all {
                    it.hasValidHash() &&
                        it.runId == runId &&
                        it.round == roundIndex &&
                        it.graderGroupId == graderGroupId &&
                        it.graderAtlasHash == freeze.graderAtlasHash &&
                        it.mappedWorkerNodeIds.size == 1
                }
        ) {
            "Exact node evaluation cannot resolve complete atomic frozen requirements"
        }

        var batchEvidenceBytes = 0
        return assignments.sortedBy(NodeGradingAssignment::assignmentId).map { assignment ->
            val packet = assignment.packet
            require(
                assignment.hasValidHash() &&
                    packet.runId == runId &&
                    packet.round == roundIndex &&
                    packet.atlasVersion == workerAtlas.version &&
                    packet.atlasHash == workerAtlas.atlasHash
            ) {
                "Exact node evaluation assignment does not match the sealed run authority"
            }
            require(
                assignment.graderCriteriaFreezeHashes[graderGroupId] == freeze.freezeHash
            ) {
                "Exact node evaluation assignment does not bind this grader criteria freeze"
            }
            require(packet.groupId in freeze.targetSnapshotHashes) {
                "Exact node evaluation target is absent from the criteria freeze"
            }
            val node = requireNotNull(workerAtlas.nodes[packet.nodeId]) {
                "Exact node evaluation packet references an unknown worker node"
            }
            require(
                node.payloadHash == packet.nodePayloadHash &&
                    node.hasValidPayloadHash() &&
                    node.capturePolicy == assignment.capturePolicy &&
                    assignment.criteriaHash == AtlasHashing.hashNodeGradingAuthority(
                        node,
                        assignment.graderCriteriaFreezeHashes
                    )
            ) {
                "Exact node evaluation node or criteria commitment is invalid"
            }
            val mappedRequirements = frozenRequirements
                .filter { it.mappedWorkerNodeIds == setOf(packet.nodeId) }
                .sortedBy { it.requirementId.value }
            require(mappedRequirements.isNotEmpty()) {
                "Exact node evaluation has no frozen node-local requirement"
            }

            val artifactIds = packet.acceptedArtifactIds.toSet()
            val claimIds = packet.acceptedClaimIds.toSet()
            require(
                artifactIds.size == packet.acceptedArtifactIds.size &&
                    claimIds.size == packet.acceptedClaimIds.size &&
                    artifactIds.intersect(claimIds).isEmpty()
            ) {
                "Exact node evaluation packet references are ambiguous or duplicated"
            }
            val referenceIds = packet.acceptedArtifactIds + packet.acceptedClaimIds
            require(
                referenceIds.isNotEmpty() &&
                    referenceIds.size <= MAX_EXACT_NODE_EVIDENCE_REFERENCES
            ) {
                "Exact node evaluation evidence reference count exceeds the complete-content bound"
            }
            val stagingRows = substrateFactory
                .get(runId, packet.groupId.value)
                .stagingDao()
                .getByIds(referenceIds)
            require(
                stagingRows.size == referenceIds.size &&
                    stagingRows.map(StagingSubmissionEntity::id).toSet() ==
                    referenceIds.toSet()
            ) {
                "Exact node evaluation is missing packet-bound staging content"
            }
            val rowsById = stagingRows.associateBy(StagingSubmissionEntity::id)
            val evidence = referenceIds.map { referenceId ->
                val row = rowsById.getValue(referenceId)
                validatePromotedEvidence(
                    row = row,
                    runId = runId,
                    groupId = packet.groupId.value,
                    roundIndex = roundIndex,
                    nodeId = packet.nodeId.value
                )
                val contentBytes = row.content.encodeToByteArray().size
                require(contentBytes <= MAX_EXACT_NODE_EVIDENCE_ITEM_BYTES) {
                    "Exact node evaluation evidence item exceeds the complete-content bound"
                }
                batchEvidenceBytes += contentBytes
                require(batchEvidenceBytes <= MAX_EXACT_NODE_EVIDENCE_BATCH_BYTES) {
                    "Exact node evaluation evidence batch exceeds the complete-content bound"
                }
                ExactNodeEvidenceContent(
                    referenceId = row.id,
                    kind = if (row.id in artifactIds) {
                        ExactNodeEvidenceKind.ARTIFACT
                    } else {
                        ExactNodeEvidenceKind.CLAIM
                    },
                    submissionType = row.type,
                    content = row.content,
                    promotedArtifactRef = requireNotNull(row.promotedArtifactRef),
                    promotedArtifactHash = requireNotNull(row.promotedArtifactHash)
                )
            }
            ExactNodeEvaluationContext(
                graderGroupId = graderGroupId,
                assignment = assignment,
                nodeContract = node,
                criteriaFreeze = freeze,
                mappedRequirements = mappedRequirements,
                evidence = evidence
            ).withComputedHash().also {
                require(it.hasValidHash()) {
                    "Exact node evaluation context commitment is invalid"
                }
            }
        }
    }

    private fun validatePromotedEvidence(
        row: StagingSubmissionEntity,
        runId: String,
        groupId: String,
        roundIndex: Int,
        nodeId: String
    ) {
        require(
            row.runId == runId &&
                row.groupId == groupId &&
                row.generation == roundIndex &&
                row.wipTaskRef == nodeId &&
                row.status == "promoted" &&
                !row.promotedArtifactRef.isNullOrBlank() &&
                !row.promotedArtifactHash.isNullOrBlank() &&
                row.reviewedAtMs != null &&
                AtlasHashing.constantTimeEquals(
                    requireNotNull(row.promotedArtifactHash),
                    AtlasHashing.hashText(row.content)
                )
        ) {
            "Exact node evaluation staging content is not valid Critic-promoted node evidence"
        }
    }
}
