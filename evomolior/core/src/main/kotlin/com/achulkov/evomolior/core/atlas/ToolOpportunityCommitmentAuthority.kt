package com.achulkov.evomolior.core.atlas

/**
 * Canonical origin-version commitments for every tool opportunity in an Atlas lineage.
 *
 * Existing nodes retain the commitment of the first sealed Atlas version containing them.
 * Opportunities introduced by an append-only amendment bind to that child version.
 *
 * @param byOpportunityId Commitments keyed by stable Council opportunity ID.
 * @param byNodeId Commitments keyed by the Atlas node carrying the opportunity.
 */
data class ToolOpportunityCommitmentAuthority(
    val byOpportunityId: Map<String, ToolOpportunityCommitment>,
    val byNodeId: Map<NodeId, ToolOpportunityCommitment>
) {
    init {
        require(byOpportunityId.values.toSet() == byNodeId.values.toSet()) {
            "Tool opportunity commitment indexes must contain the same authority"
        }
    }

    companion object {
        /**
         * Resolves origin commitments from complete ascending immutable Atlas history.
         *
         * @param history Complete Atlas history beginning at version one.
         * @return Canonical origin commitments.
         */
        fun resolve(history: List<NodeAtlas>): ToolOpportunityCommitmentAuthority {
            require(history.isNotEmpty()) { "Tool opportunity authority requires Atlas history" }
            require(history.first().version == 1) {
                "Tool opportunity authority history must begin at Atlas version one"
            }

            val byOpportunityId = linkedMapOf<String, ToolOpportunityCommitment>()
            val byNodeId = linkedMapOf<NodeId, ToolOpportunityCommitment>()
            var previous: NodeAtlas? = null
            history.forEach { atlas ->
                atlas.validate(previous).requireValid()
                previous?.let { parent ->
                    require(parent.nodes.all { (nodeId, node) -> atlas.nodes[nodeId] == node }) {
                        "Tool opportunity authority requires append-only immutable Atlas nodes"
                    }
                }

                val opportunityNodes = atlas.nodes.values
                    .filter { it.toolOpportunity != null }
                require(
                    opportunityNodes.map { it.toolOpportunity!!.opportunityId }
                        .distinct().size == opportunityNodes.size
                ) {
                    "Tool opportunity IDs must be unique in every sealed Atlas version"
                }

                atlas.nodes.values
                    .filter { it.id !in byNodeId }
                    .sortedBy { it.id.value }
                    .forEach { node ->
                        val opportunity = node.toolOpportunity ?: return@forEach
                        require(opportunity.opportunityId !in byOpportunityId) {
                            "Tool opportunity ID ${opportunity.opportunityId} is duplicated"
                        }
                        val commitment = ToolOpportunityCommitment(
                            runId = atlas.runId,
                            atlasHash = atlas.atlasHash,
                            nodeId = node.id,
                            opportunity = opportunity
                        ).withComputedHash()
                        byOpportunityId[opportunity.opportunityId] = commitment
                        byNodeId[node.id] = commitment
                    }
                previous = atlas
            }
            return ToolOpportunityCommitmentAuthority(
                byOpportunityId = byOpportunityId.toMap(),
                byNodeId = byNodeId.toMap()
            )
        }

        /**
         * Builds current-version commitments for isolated, non-versioned callers.
         *
         * Production versioned flows should use [resolve] with complete history.
         *
         * @param atlas Current sealed Atlas.
         * @return Commitments bound to the supplied version.
         */
        fun current(atlas: NodeAtlas): ToolOpportunityCommitmentAuthority {
            require(atlas.atlasHash.isNotBlank() && atlas.validate().isValid) {
                "Current tool opportunity authority requires a valid sealed Atlas"
            }
            val commitments = atlas.nodes.values
                .mapNotNull { node ->
                    node.toolOpportunity?.let { opportunity ->
                        ToolOpportunityCommitment(
                            runId = atlas.runId,
                            atlasHash = atlas.atlasHash,
                            nodeId = node.id,
                            opportunity = opportunity
                        ).withComputedHash()
                    }
                }
            require(
                commitments.map { it.opportunity.opportunityId }.distinct().size ==
                    commitments.size
            ) {
                "Tool opportunity IDs must be unique in the sealed Atlas"
            }
            return ToolOpportunityCommitmentAuthority(
                byOpportunityId = commitments.associateBy { it.opportunity.opportunityId },
                byNodeId = commitments.associateBy(ToolOpportunityCommitment::nodeId)
            )
        }
    }
}
