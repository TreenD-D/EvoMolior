package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exact comparison of the live grader verdict with the isolated child-JVM replay. */
@Serializable
data class ScoreReplayObservationV1(
    val round: Int,
    val liveVerdictSha256: String,
    val replayVerdictSha256: String,
    val maximumAbsoluteError: Double,
    val exactMatch: Boolean,
) {
    init {
        require(round >= 0) { "Score replay round cannot be negative" }
        require(SHA_256_REGEX.matches(liveVerdictSha256)) {
            "Live score verdict hash is invalid"
        }
        require(SHA_256_REGEX.matches(replayVerdictSha256)) {
            "Replayed score verdict hash is invalid"
        }
        require(maximumAbsoluteError.isFinite() && maximumAbsoluteError in 0.0..1.0) {
            "Score replay error must be finite and normalized"
        }
        require(exactMatch == (maximumAbsoluteError == 0.0)) {
            "Score replay exact-match flag must agree with the measured error"
        }
    }

    private companion object {
        val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

/** Ordered run-level score-replay measurement authority. */
@Serializable
data class ScoreReplayAuthorityV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val observations: List<ScoreReplayObservationV1>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Score replay schema version must be $SCHEMA_VERSION"
        }
        require(runId.isNotBlank()) { "Score replay run ID must not be blank" }
        require(
            observations == observations.sortedBy(ScoreReplayObservationV1::round) &&
                observations.map(ScoreReplayObservationV1::round).distinct().size ==
                observations.size
        ) {
            "Score replay observations must be uniquely ordered by round"
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "score-replay-authority-v1"
    }
}

/** Pure exact comparison used for persisted score-replay measurement. */
object ScoreReplayAuthority {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    /**
     * Compares one in-process verdict with the isolated child-JVM replay.
     *
     * Structural authority drift is the maximum normalized error (`1.0`). When structure matches,
     * the result is the largest absolute difference across every numeric score component.
     */
    fun compare(
        round: Int,
        live: ExactGraderRoundVerdict,
        replay: ExactGraderRoundVerdict,
    ): ScoreReplayObservationV1 {
        val error = maximumAbsoluteError(live, replay)
        return ScoreReplayObservationV1(
            round = round,
            liveVerdictSha256 = hash(live),
            replayVerdictSha256 = hash(replay),
            maximumAbsoluteError = error,
            exactMatch = error == 0.0,
        )
    }

    private fun maximumAbsoluteError(
        live: ExactGraderRoundVerdict,
        replay: ExactGraderRoundVerdict,
    ): Double {
        if (
            live.status != replay.status ||
            live.acceptedBundleHashes != replay.acceptedBundleHashes ||
            live.targetSnapshotHashes != replay.targetSnapshotHashes ||
            live.contestedCriticalRequirements != replay.contestedCriticalRequirements ||
            live.reason != replay.reason ||
            live.nodeScores.keys != replay.nodeScores.keys ||
            live.groupScores.keys != replay.groupScores.keys
        ) {
            return 1.0
        }
        var maximum = nullableDifference(
            live.maximumNodeDisagreement,
            replay.maximumNodeDisagreement,
        ) ?: return 1.0
        live.nodeScores.forEach { (groupId, liveNodes) ->
            val replayNodes = replay.nodeScores.getValue(groupId)
            if (liveNodes.keys != replayNodes.keys) return 1.0
            liveNodes.forEach { (nodeId, liveVector) ->
                val replayVector = replayNodes.getValue(nodeId)
                if (liveVector.hardFailure != replayVector.hardFailure) return 1.0
                maximum = maxOf(
                    maximum,
                    abs(liveVector.correctness - replayVector.correctness),
                    abs(liveVector.evidenceQuality - replayVector.evidenceQuality),
                    abs(liveVector.reproducibility - replayVector.reproducibility),
                    abs(liveVector.robustness - replayVector.robustness),
                    abs(liveVector.integrationUtility - replayVector.integrationUtility),
                    abs(liveVector.receiverRelevance - replayVector.receiverRelevance),
                    abs(liveVector.novelty - replayVector.novelty),
                    abs(liveVector.efficiency - replayVector.efficiency),
                    abs(liveVector.confidence - replayVector.confidence),
                )
            }
        }
        live.groupScores.forEach { (groupId, liveScores) ->
            val replayScores = replay.groupScores.getValue(groupId)
            if (liveScores.keys != replayScores.keys) return 1.0
            liveScores.forEach { (dimensionId, liveValue) ->
                val difference = nullableDifference(
                    liveValue,
                    replayScores.getValue(dimensionId),
                ) ?: return 1.0
                maximum = maxOf(maximum, difference)
            }
        }
        return maximum
    }

    private fun nullableDifference(left: Double?, right: Double?): Double? = when {
        left == null && right == null -> 0.0
        left == null || right == null -> null
        else -> abs(left - right)
    }

    private fun hash(verdict: ExactGraderRoundVerdict): String =
        sha256Hex(
            json.encodeToString(ExactGraderRoundVerdict.serializer(), verdict)
                .toByteArray(Charsets.UTF_8),
        )
}

/**
 * Atomic control-file store for score replay observations.
 *
 * @param databaseFactory Run-root resolver.
 */
class ScoreReplayAuthorityV1Store(
    private val databaseFactory: SubstrateDatabaseFactory,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /** Appends one immutable round observation and atomically replaces the ordered document. */
    fun append(
        runId: String,
        observation: ScoreReplayObservationV1,
    ): ScoreReplayAuthorityV1 {
        val existing = read(runId)
        existing?.observations
            ?.firstOrNull { it.round == observation.round }
            ?.let { persisted ->
                require(persisted == observation) {
                    "Score replay round ${observation.round} is already bound to different authority"
                }
                return existing
            }
        val authority = ScoreReplayAuthorityV1(
            runId = runId,
            observations = (existing?.observations.orEmpty() + observation)
                .sortedBy(ScoreReplayObservationV1::round),
        )
        val file = file(runId)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            json.encodeToString(ScoreReplayAuthorityV1.serializer(), authority),
        )
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return authority
    }

    /** Reads and validates the current score replay authority, when present. */
    fun read(runId: String): ScoreReplayAuthorityV1? {
        val file = file(runId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<ScoreReplayAuthorityV1>(file.readText())
        }.getOrElse { error ->
            throw IllegalStateException("Score replay authority is unreadable", error)
        }.also { authority ->
            require(authority.runId == runId) {
                "Score replay authority belongs to runId=${authority.runId}, expected $runId"
            }
        }
    }

    /** Returns the stable control-file path for [runId]. */
    fun file(runId: String): File =
        File(databaseFactory.runControlDir(runId), FILE_NAME)

    companion object {
        const val FILE_NAME: String = "score-replay-authority-v1.json"
    }
}
