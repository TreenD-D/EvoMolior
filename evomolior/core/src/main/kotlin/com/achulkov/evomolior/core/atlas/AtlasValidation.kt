package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable

/**
 * Result of deterministic Atlas validation.
 *
 * @param errors All validation failures.
 */
@Serializable
data class AtlasValidationResult(val errors: List<AtlasValidationError>) {
    /** Whether the Atlas satisfies every checked invariant. */
    val isValid: Boolean get() = errors.isEmpty()

    /**
     * Throws when validation failed.
     *
     * @throws IllegalArgumentException when [errors] is not empty.
     */
    fun requireValid() {
        require(isValid) { errors.joinToString(separator = "; ") { "${it.code}: ${it.message}" } }
    }
}

/**
 * One machine-readable Atlas validation failure.
 *
 * @param code Stable error category.
 * @param message Diagnostic description.
 * @param nodeId Related node, when applicable.
 */
@Serializable
data class AtlasValidationError(
    val code: AtlasValidationErrorCode,
    val message: String,
    val nodeId: NodeId? = null
)

/** Stable categories emitted by [AtlasValidator]. */
@Serializable
enum class AtlasValidationErrorCode {
    INVALID_VERSION,
    INVALID_ATLAS_HASH,
    INVALID_PAYLOAD_HASH,
    NODE_KEY_MISMATCH,
    UNKNOWN_NODE_REFERENCE,
    INVALID_ROOT,
    DEPENDENCY_CYCLE,
    INVALID_NODE_ATOMICITY,
    TOOL_PURPOSE_OVERLAP,
    INVALID_STAGE,
    INVALID_DISCLOSURE_PAYLOAD_HASH,
    INVALID_PARENT_LINEAGE
}

/** Validates Atlas integrity, graph topology, disclosure membership, and version lineage. */
object AtlasValidator {
    /**
     * Validates an Atlas and optional immediate parent.
     *
     * @param atlas Atlas to validate.
     * @param parent Immediate previous version, if one is expected.
     * @return Complete validation result.
     */
    fun validate(atlas: NodeAtlas, parent: NodeAtlas? = null): AtlasValidationResult {
        val errors = buildList {
            if (atlas.version < 1) add(error(AtlasValidationErrorCode.INVALID_VERSION, "version must be at least 1"))
            if (!AtlasHashing.constantTimeEquals(atlas.atlasHash, AtlasHashing.hashAtlas(atlas))) {
                add(error(AtlasValidationErrorCode.INVALID_ATLAS_HASH, "atlasHash does not match canonical Atlas content"))
            }
            atlas.nodes.forEach { (key, node) ->
                if (key != node.id) {
                    add(error(AtlasValidationErrorCode.NODE_KEY_MISMATCH, "Map key does not match node ID", node.id))
                }
                if (!node.hasValidPayloadHash()) {
                    add(error(AtlasValidationErrorCode.INVALID_PAYLOAD_HASH, "payloadHash does not match hidden payload", node.id))
                }
                node.references().forEach { referenced ->
                    if (referenced !in atlas.nodes) {
                        add(error(AtlasValidationErrorCode.UNKNOWN_NODE_REFERENCE, "Unknown node reference ${referenced.value}", node.id))
                    }
                }
            }
            atlas.rootNodeIds.forEach { root ->
                val node = atlas.nodes[root]
                if (node == null || node.parentIds.isNotEmpty()) {
                    add(error(AtlasValidationErrorCode.INVALID_ROOT, "Root must exist and have no parents", root))
                }
            }
            detectCycle(atlas)?.let { cycle ->
                add(error(AtlasValidationErrorCode.DEPENDENCY_CYCLE, "Dependency graph contains a cycle at ${cycle.value}", cycle))
            }
            validateNodeAtomicity(atlas).forEach(::add)
            validateToolPurposeTopology(atlas).forEach(::add)
            validateStages(atlas).forEach(::add)
            validateLineage(atlas, parent).forEach(::add)
        }
        return AtlasValidationResult(errors)
    }

    private fun validateStages(atlas: NodeAtlas): List<AtlasValidationError> = buildList {
        val stageMembership = atlas.stageDefinitions
            .flatMap { stage -> stage.nodeIds.map { nodeId -> nodeId to stage.stageId } }
            .groupBy({ it.first }, { it.second })
        atlas.nodes.forEach { (nodeId, node) ->
            val memberships = stageMembership[nodeId].orEmpty()
            if (memberships.size != 1 || memberships.singleOrNull() != node.revealPolicy.stageId) {
                add(
                    error(
                        AtlasValidationErrorCode.INVALID_STAGE,
                        "Node must belong to exactly its declared reveal stage",
                        nodeId
                    )
                )
            }
        }
        atlas.stageDefinitions.forEachIndexed { index, stage ->
            if (stage.stageIndex != index) {
                add(error(AtlasValidationErrorCode.INVALID_STAGE, "Stage indices must be contiguous from zero"))
            }
            if (!atlas.nodes.keys.containsAll(stage.nodeIds) || !stage.nodeIds.containsAll(stage.mandatoryNodeIds)) {
                add(error(AtlasValidationErrorCode.INVALID_STAGE, "Stage nodes must exist and mandatory nodes must belong to the stage"))
            } else {
                val expectedHash = AtlasHashing.hashDisclosurePayload(stage.stageId, stage.nodeIds, atlas.nodes)
                if (!AtlasHashing.constantTimeEquals(stage.revealPayloadHash, expectedHash)) {
                    add(
                        error(
                            AtlasValidationErrorCode.INVALID_DISCLOSURE_PAYLOAD_HASH,
                            "Stage revealPayloadHash does not match its node payload commitments"
                        )
                    )
                }
            }
        }
    }

    private fun validateNodeAtomicity(atlas: NodeAtlas): List<AtlasValidationError> = buildList {
        val parentNodeIds = atlas.nodes.values.flatMapTo(mutableSetOf()) { it.parentIds }
        atlas.nodes.values.forEach { node ->
            if (node.layer < 0 ||
                node.receiverWhyRef.isBlank() ||
                node.limitationStatement.isBlank() ||
                node.capabilityTarget.isBlank() ||
                node.atomicQuestion.isBlank()
            ) {
                add(error(AtlasValidationErrorCode.INVALID_NODE_ATOMICITY, "Node obligation and question fields must be explicit", node.id))
            }
            val isLeaf = node.id !in parentNodeIds
            if (isLeaf && (
                    node.proofContract.acceptanceCriteria.isEmpty() ||
                        node.deliverableContract.acceptedArtifactTypes.isEmpty() ||
                        !node.proofContract.failureIsUseful
                    )
            ) {
                add(error(AtlasValidationErrorCode.INVALID_NODE_ATOMICITY, "Leaf must expose proof, artifact, and useful-failure contracts", node.id))
            }
            if (node.replicationPolicy.requiredCount < 1 ||
                node.capturePolicy.minimumGrade !in 0.0..1.0 ||
                node.capturePolicy.recaptureMargin !in 0.0..1.0 ||
                node.capturePolicy.nearTieMargin?.let { it !in 0.0..1.0 } == true ||
                node.rewardSpec.credits < 0.0 ||
                node.rewardSpec.congestionLevel < 0
            ) {
                add(error(AtlasValidationErrorCode.INVALID_NODE_ATOMICITY, "Node policy bounds are invalid", node.id))
            }
        }
    }

    private fun validateToolPurposeTopology(
        atlas: NodeAtlas
    ): List<AtlasValidationError> = buildList {
        atlas.nodes.values.forEach { node ->
            val opportunity = node.toolOpportunity ?: return@forEach
            val overlap = atlas.toolValidationNodeIds(node.id)
                .intersect(opportunity.expectedReuseNodeIds)
            if (overlap.isNotEmpty()) {
                add(
                    error(
                        AtlasValidationErrorCode.TOOL_PURPOSE_OVERLAP,
                        "Tool validation and expected-reuse authority must use disjoint nodes: " +
                            overlap.sortedBy(NodeId::value).joinToString { it.value },
                        node.id
                    )
                )
            }
        }
    }

    private fun validateLineage(atlas: NodeAtlas, parent: NodeAtlas?): List<AtlasValidationError> = buildList {
        if (parent == null) {
            if (atlas.version == 1 && atlas.parentAtlasHash != null) {
                add(error(AtlasValidationErrorCode.INVALID_PARENT_LINEAGE, "First version cannot have a parent hash"))
            }
        } else {
            if (atlas.atlasId != parent.atlasId || atlas.runId != parent.runId) {
                add(error(AtlasValidationErrorCode.INVALID_PARENT_LINEAGE, "Parent must have the same Atlas and run IDs"))
            }
            if (atlas.version != parent.version + 1) {
                add(error(AtlasValidationErrorCode.INVALID_PARENT_LINEAGE, "Version must increment exactly by one"))
            }
            if (atlas.parentAtlasHash != parent.atlasHash) {
                add(error(AtlasValidationErrorCode.INVALID_PARENT_LINEAGE, "parentAtlasHash must equal the parent Atlas hash"))
            }
        }
    }

    private fun detectCycle(atlas: NodeAtlas): NodeId? {
        val states = mutableMapOf<NodeId, VisitState>()
        fun visit(id: NodeId): NodeId? {
            when (states[id]) {
                VisitState.VISITING -> return id
                VisitState.VISITED -> return null
                null -> Unit
            }
            states[id] = VisitState.VISITING
            val dependencies = atlas.nodes[id]?.let { it.parentIds + it.prerequisiteIds } ?: emptySet()
            dependencies.forEach { dependency -> visit(dependency)?.let { return it } }
            states[id] = VisitState.VISITED
            return null
        }
        return atlas.nodes.keys.firstNotNullOfOrNull(::visit)
    }

    private fun LimitationNode.references(): Set<NodeId> =
        parentIds + prerequisiteIds + alternativeToIds + revealPolicy.prerequisiteNodeIds

    private fun error(
        code: AtlasValidationErrorCode,
        message: String,
        nodeId: NodeId? = null
    ) = AtlasValidationError(code, message, nodeId)

    private enum class VisitState { VISITING, VISITED }
}
