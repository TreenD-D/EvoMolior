package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasValidation

/**
 * Deterministically evolves a separately sealed Grader Atlas with one worker Atlas amendment.
 */
object GraderAtlasEvolutionCompiler {
    /**
     * Produces the exact next evaluator authority while preserving the existing grader frontier.
     *
     * @param currentAtlas Current sealed Grader Atlas.
     * @param currentWorkerAtlas Current sealed worker Atlas governed by [currentAtlas].
     * @param nextWorkerAtlas Valid child worker Atlas to govern in the next round.
     * @return Hash-sealed child Grader Atlas with complete worker-node coverage.
     */
    fun evolve(
        currentAtlas: GraderAtlas,
        currentWorkerAtlas: NodeAtlas,
        nextWorkerAtlas: NodeAtlas
    ): GraderAtlas {
        require(
            currentAtlas.runId == currentWorkerAtlas.runId &&
                currentAtlas.version == currentWorkerAtlas.version &&
                currentAtlas.workerAtlasHash == currentWorkerAtlas.atlasHash
        ) {
            "Current Grader Atlas does not govern the supplied worker Atlas"
        }
        require(
            nextWorkerAtlas.runId == currentWorkerAtlas.runId &&
                nextWorkerAtlas.atlasId == currentWorkerAtlas.atlasId &&
                nextWorkerAtlas.version == currentWorkerAtlas.version + 1 &&
                nextWorkerAtlas.parentAtlasHash == currentWorkerAtlas.atlasHash
        ) {
            "Next worker Atlas is not an exact child of the current worker Atlas"
        }
        val nextWorkerNodeIds = nextWorkerAtlas.nodes.keys
        val nextAtlas = currentAtlas.copy(
            version = nextWorkerAtlas.version,
            workerAtlasHash = nextWorkerAtlas.atlasHash,
            nodes = currentAtlas.nodes.mapValues { (_, node) ->
                node.copy(
                    mappedWorkerNodeIds = nextWorkerNodeIds,
                    payloadHash = ""
                ).withComputedHash()
            },
            atlasHash = ""
        ).withComputedHash()
        require(
            GraderAtlasValidation.validateEvolution(
                currentAtlas,
                nextAtlas,
                currentWorkerAtlas,
                nextWorkerAtlas
            ).isEmpty()
        ) {
            GraderAtlasValidation.validateEvolution(
                currentAtlas,
                nextAtlas,
                currentWorkerAtlas,
                nextWorkerAtlas
            ).joinToString()
        }
        return nextAtlas
    }
}
