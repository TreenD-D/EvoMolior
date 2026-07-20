package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.grader.GraderAtlas

/**
 * Immutable worker/Grader Atlas pair governing every authority decision in one round.
 *
 * @param workerAtlas Round-start worker Atlas snapshot.
 * @param graderAtlas Separately sealed evaluator Atlas governing [workerAtlas].
 */
internal data class RoundAtlasAuthority(
    val workerAtlas: NodeAtlas,
    val graderAtlas: GraderAtlas
) {
    init {
        require(
            workerAtlas.runId == graderAtlas.runId &&
                workerAtlas.version == graderAtlas.version &&
                workerAtlas.atlasHash == graderAtlas.workerAtlasHash
        ) {
            "Round authority requires a matched worker and Grader Atlas pair"
        }
    }
}
