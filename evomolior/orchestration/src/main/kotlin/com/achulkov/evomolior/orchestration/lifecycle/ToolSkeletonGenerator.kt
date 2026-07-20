package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolProjectSkeleton
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler

/** Generates deterministic, bounded one-function tool projects from sealed opportunities. */
object ToolSkeletonGenerator {
    /**
     * Generates the exact implementation and test-harness skeleton for [commitment].
     *
     * @param commitment Valid sealed opportunity provenance.
     * @return Deterministically hashed project skeleton.
     */
    fun generate(commitment: ToolOpportunityCommitment): ToolProjectSkeleton =
        ToolProjectSkeletonCompiler.compile(commitment)
}
