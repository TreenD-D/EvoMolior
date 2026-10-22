package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.contract.WorkerView

/**
 * Active lease authority used to mint one lifecycle-local tool capability.
 *
 * @param leaseId Exact active Atlas lease identity.
 * @param nodeId Exact leased node.
 * @param capability Exact run/group/round/worker-view scope.
 */
data class ToolOpportunityLeaseAuthority(
    val leaseId: String,
    val nodeId: String,
    val capability: ToolOpportunityBindingCapability
) {
    init {
        require(leaseId.isNotBlank() && nodeId.isNotBlank()) {
            "Tool opportunity lease authority must not be blank"
        }
    }
}

/**
 * Compiles exact worker-view and active-lease authority into short model-facing handles.
 *
 * The current worker contract carries at most one assigned tool opportunity, so its only handle is
 * `T1`. The catalog still rejects duplicates and is shaped for bounded multi-binding evolution.
 */
object ToolOpportunityBindingCatalogCompiler {
    /**
     * Compiles one capability-bound catalog.
     *
     * @param view Exact hashed worker view.
     * @param lease Exact active lease for the assigned node.
     * @param commitment Historical origin-version opportunity commitment.
     * @return Deterministically hashed short-handle catalog.
     */
    fun compile(
        view: WorkerView,
        lease: ToolOpportunityLeaseAuthority,
        commitment: ToolOpportunityCommitment
    ): ToolOpportunityBindingCatalog {
        require(view.hasValidHash()) { "Tool binding catalog requires a valid worker view" }
        require(
            lease.capability.runId == view.runId &&
                lease.capability.groupId == view.groupId.value &&
                lease.capability.round == view.round &&
                lease.capability.workerViewHash == view.viewHash
        ) { "Tool binding lease capability does not match the worker view" }
        val node = requireNotNull(view.assignedNode) {
            "Tool binding catalog requires one assigned node"
        }
        val opportunity = requireNotNull(node.toolOpportunity) {
            "Tool binding catalog requires one assigned tool opportunity"
        }
        val skeleton = requireNotNull(view.assignedToolSkeleton) {
            "Tool binding catalog requires one generated project skeleton"
        }
        require(lease.nodeId == node.nodeId.value) {
            "Tool binding lease does not authorize the assigned node"
        }
        require(
            commitment.hasValidHash() &&
                commitment.runId == view.runId &&
                commitment.nodeId == node.nodeId &&
                commitment.opportunity == opportunity &&
                commitment.commitmentHash == skeleton.opportunityCommitmentHash &&
                commitment.commitmentHash ==
                view.assignedToolOpportunityCommitmentHash
        ) { "Tool binding catalog lost historical opportunity authority" }
        require(skeleton.hasValidHash()) {
            "Tool binding catalog requires an exact generated skeleton hash"
        }
        val binding = ToolOpportunityBinding(
            handle = "T1",
            nodeId = node.nodeId.value,
            opportunityId = opportunity.opportunityId,
            opportunityHash = commitment.commitmentHash,
            inputSchemaHash = AtlasHashing.hashText(skeleton.inputSchemaJson),
            outputSchemaHash = AtlasHashing.hashText(skeleton.outputSchemaJson),
            // No public case authority is currently supplied by Council compilation. Authors may
            // add bounded semantic cases; private promotion IDs remain entirely separate.
            publicTestIds = emptyList(),
            runtimeKind = opportunity.preferredImplementationMode.runtimeKind,
            projectSkeletonHash = skeleton.skeletonHash
        )
        val catalogSeed = listOf(
            view.runId,
            view.groupId.value,
            view.round.toString(),
            view.viewHash,
            lease.leaseId,
            commitment.commitmentHash
        ).joinToString("|")
        return ToolOpportunityBindingCatalog(
            catalogId = "tool-catalog-${AtlasHashing.hashText(catalogSeed).take(24)}",
            runId = view.runId,
            groupId = view.groupId.value,
            round = view.round,
            workerViewHash = view.viewHash,
            bindings = listOf(binding)
        ).withComputedHash()
    }

    private val ToolImplementationMode.runtimeKind: ToolRuntimeKind
        get() = when (this) {
            ToolImplementationMode.SHELL -> ToolRuntimeKind.SHELL
            ToolImplementationMode.PYTHON -> ToolRuntimeKind.PYTHON
            ToolImplementationMode.FORMULA_DSL -> ToolRuntimeKind.FORMULA_DSL
            ToolImplementationMode.RULE_DSL -> ToolRuntimeKind.RULE_DSL
            ToolImplementationMode.SIMULATION_DSL -> ToolRuntimeKind.SIMULATION_DSL
        }
}
