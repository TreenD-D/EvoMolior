package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** Exact persisted bytes and commitment for one run-metrics document. */
data class RunMetricsV1Artifact(
    val file: File,
    val bytes: ByteArray,
    val sha256: String,
    val metrics: RunMetricsV1,
)

/**
 * Persists and recovers the immutable `control/run-metrics-v1.json` publication authority.
 *
 * @param databaseFactory Run-root resolver.
 */
class RunMetricsV1ArtifactStore(
    private val databaseFactory: SubstrateDatabaseFactory,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /**
     * Writes [metrics] once and refuses to replace different bytes.
     *
     * @param metrics Complete canonical metric document.
     * @return Exact durable artifact.
     */
    fun persist(metrics: RunMetricsV1): RunMetricsV1Artifact {
        return persist(compile(metrics))
    }

    /** Compiles exact canonical artifact bytes without writing publication authority. */
    fun compile(metrics: RunMetricsV1): RunMetricsV1Artifact {
        val file = file(metrics.runId)
        val bytes = json.encodeToString(RunMetricsV1.serializer(), metrics)
            .toByteArray(Charsets.UTF_8)
        return decode(file, bytes, metrics.runId)
    }

    /** Persists one already compiled artifact once and verifies exact byte identity. */
    fun persist(artifact: RunMetricsV1Artifact): RunMetricsV1Artifact {
        val file = file(artifact.metrics.runId)
        require(
            artifact.file == file &&
                artifact.sha256 == sha256Hex(artifact.bytes)
        ) { "Compiled run metrics artifact identity is invalid" }
        val bytes = artifact.bytes
        if (file.isFile) {
            val existing = Files.readAllBytes(file.toPath())
            require(existing.contentEquals(bytes)) {
                "Run metrics authority already exists with different bytes"
            }
            return decode(file, existing, artifact.metrics.runId)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return decode(file, bytes, artifact.metrics.runId)
    }

    /**
     * Reads an already exported artifact and verifies its exact expected commitment.
     *
     * @param runId Expected run identity.
     * @param expectedSha256 Publication-event commitment.
     * @return Exact durable artifact.
     */
    fun readRequired(
        runId: String,
        expectedSha256: String? = null,
    ): RunMetricsV1Artifact {
        val file = file(runId)
        check(file.isFile) {
            "Publication state requires missing control/$FILE_NAME"
        }
        val artifact = decode(file, Files.readAllBytes(file.toPath()), runId)
        if (expectedSha256 != null) {
            check(artifact.sha256 == expectedSha256) {
                "Run metrics authority hash mismatch: " +
                    "expected=$expectedSha256 actual=${artifact.sha256}"
            }
        }
        return artifact
    }

    /** Returns the canonical metrics control file for [runId]. */
    fun file(runId: String): File =
        File(databaseFactory.runControlDir(runId), FILE_NAME)

    private fun decode(
        file: File,
        bytes: ByteArray,
        expectedRunId: String,
    ): RunMetricsV1Artifact {
        val metrics = runCatching {
            json.decodeFromString<RunMetricsV1>(bytes.toString(Charsets.UTF_8))
        }.getOrElse { error ->
            throw IllegalStateException("Run metrics authority is unreadable", error)
        }
        require(metrics.runId == expectedRunId) {
            "Run metrics authority belongs to runId=${metrics.runId}, expected $expectedRunId"
        }
        return RunMetricsV1Artifact(
            file = file,
            bytes = bytes,
            sha256 = sha256Hex(bytes),
            metrics = metrics,
        )
    }

    companion object {
        /** Stable control and ZIP-root filename. */
        const val FILE_NAME: String = "run-metrics-v1.json"
    }
}
