package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScoreReplayAuthorityV1Test {
    @Test
    fun `identical child replay has zero error and persists idempotently`() {
        val verdict = verdict()
        val observation = ScoreReplayAuthority.compare(0, verdict, verdict)
        val store = ScoreReplayAuthorityV1Store(
            SubstrateDatabaseFactory(createTempDirectory("score-replay-").toFile()),
        )

        assertEquals(0.0, observation.maximumAbsoluteError)
        assertEquals(true, observation.exactMatch)
        assertEquals(
            store.append("run-1", observation),
            store.append("run-1", observation),
        )
    }

    @Test
    fun `numeric and structural replay drift are measured and immutable`() {
        val live = verdict()
        val numericReplay = live.copy(
            nodeScores = live.nodeScores.mapValues { (_, nodes) ->
                nodes.mapValues { (_, vector) -> vector.copy(correctness = 0.75) }
            },
        )
        val numeric = ScoreReplayAuthority.compare(0, live, numericReplay)
        val structural = ScoreReplayAuthority.compare(
            1,
            live,
            live.copy(acceptedBundleHashes = setOf("different-bundle")),
        )
        val store = ScoreReplayAuthorityV1Store(
            SubstrateDatabaseFactory(createTempDirectory("score-replay-drift-").toFile()),
        )
        store.append("run-1", numeric)

        assertEquals(0.25, numeric.maximumAbsoluteError)
        assertEquals(false, numeric.exactMatch)
        assertEquals(1.0, structural.maximumAbsoluteError)
        assertFailsWith<IllegalArgumentException> {
            store.append("run-1", numeric.copy(maximumAbsoluteError = 0.5))
        }
    }

    private fun verdict() = ExactGraderRoundVerdict(
        status = ExactGraderRoundStatus.VALID,
        acceptedBundleHashes = setOf("bundle-a"),
        targetSnapshotHashes = mapOf(AtlasGroupId("group-0") to "snapshot-a"),
        nodeScores = mapOf(
            AtlasGroupId("group-0") to mapOf(
                NodeId("node-0") to NodeScoreVector(
                    correctness = 1.0,
                    evidenceQuality = 1.0,
                    reproducibility = 1.0,
                    robustness = 1.0,
                    integrationUtility = 1.0,
                    receiverRelevance = 1.0,
                    novelty = 1.0,
                    efficiency = 1.0,
                    hardFailure = false,
                    confidence = 1.0,
                ),
            ),
        ),
        groupScores = mapOf(
            AtlasGroupId("group-0") to mapOf("quality" to 1.0),
        ),
        maximumNodeDisagreement = 0.0,
    )
}
