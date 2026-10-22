package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.deliverable.ArcArtifactBackbone
import com.achulkov.evomolior.core.deliverable.ArcArtifactBranchLineage
import com.achulkov.evomolior.core.deliverable.ArcSolverFamily

/**
 * Materializes the common ARC backbone and one deterministic divergent solver branch.
 *
 * @param manager Persistent artifact workspace manager.
 */
class ArcArtifactBranchCompiler(
    private val manager: ArtifactWorkspaceManager,
) {
    /**
     * Writes the common modules and one group-specific solver-family module.
     *
     * @param handle Group artifact workspace.
     * @param round Round receiving the branch.
     * @param family Solver family assigned to the group.
     * @return Hash-bound branch lineage.
     */
    fun materialize(
        handle: ArtifactWorkspaceHandle,
        round: Int,
        family: ArcSolverFamily,
    ): ArcArtifactBranchLineage {
        require(round >= 0) { "ARC branch round must not be negative" }
        val backbone = ArcArtifactBackbone.canonical()
        backbone.modules.forEach { (path, source) ->
            manager.writeSource(handle, path, source)
        }
        manager.writeSource(handle, "src/solver_families/${family.familyId}.py", family.source)
        return ArcArtifactBranchLineage(
            runId = handle.runId,
            groupId = handle.groupId,
            round = round,
            backboneHash = backbone.backboneHash,
            solverFamily = family,
            branchHash = family.sourceHash,
        ).withComputedHash()
    }

    companion object {
        /** Assigns distinct branch families in stable group order. */
        fun familyForGroupIndex(index: Int): ArcSolverFamily {
            require(index >= 0) { "ARC group index must not be negative" }
            return ArcSolverFamily.entries[index % ArcSolverFamily.entries.size]
        }
    }
}
