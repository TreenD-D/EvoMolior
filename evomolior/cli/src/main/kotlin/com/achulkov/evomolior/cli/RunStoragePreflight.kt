package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.config.LlmBackend
import io.github.aakira.napier.Napier
import java.nio.file.Files
import java.nio.file.Path

/** Raised when an ordinary run would start on a volume without enough free space. */
internal class CliStoragePreflightException(message: String) : IllegalStateException(message)

/**
 * Free-space admission for ordinary CLI runs.
 *
 * A run writes Room/SQLite substrates and archives under its output directory, scratch files under
 * the JVM temp directory, and Codex prompt/stdout files under the Codex temp directory. The
 * October 2026 mixed research run lost its fourth round and final writer when the JVM temp volume
 * filled mid-run, after roughly 100 minutes of provider work. Checking every distinct volume up
 * front turns that late, expensive failure into an immediate, explained rejection.
 */
internal object RunStoragePreflight {
    /** Environment override in MiB; `0` disables the check. */
    const val MIN_FREE_ENV = "EVOMOLIOR_MIN_FREE_DISK_MB"

    /** Codex temp authority shared with the Codex client and acceptance admission. */
    private const val CODEX_TMPDIR_ENV = "EVOMOLIOR_CODEX_TMPDIR"

    /** Default floor: generous relative to observed run footprints (~70 MiB of run data). */
    const val DEFAULT_MIN_FREE_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * One write location the run depends on.
     *
     * @param label Operator-facing name of the location.
     * @param path Configured path; it may not exist yet.
     */
    data class Location(val label: String, val path: Path)

    /**
     * Free-space observation for one distinct volume.
     *
     * @param labels Locations that resolve onto this volume.
     * @param probePath Existing path used to query the volume.
     * @param usableBytes Bytes available to this process.
     */
    data class VolumeStatus(val labels: List<String>, val probePath: Path, val usableBytes: Long)

    /**
     * Resolves the required floor from [env].
     *
     * @param env Process environment.
     * @return Required usable bytes per volume; `0` when disabled.
     */
    fun requiredFreeBytes(env: Map<String, String> = System.getenv()): Long {
        val raw = env[MIN_FREE_ENV]?.trim()?.takeIf(String::isNotEmpty) ?: return DEFAULT_MIN_FREE_BYTES
        val mebibytes = raw.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw CliUsageException("$MIN_FREE_ENV must be a non-negative integer number of MiB, got '$raw'")
        return mebibytes * 1024 * 1024
    }

    /**
     * Lists the locations a run started with [options] writes to.
     *
     * @param options Parsed CLI options.
     * @param env Process environment.
     * @param systemTemp JVM temp directory.
     */
    fun locations(
        options: CliOptions,
        env: Map<String, String> = System.getenv(),
        systemTemp: Path = Path.of(System.getProperty("java.io.tmpdir"))
    ): List<Location> = buildList {
        add(Location("run output (-output)", options.outputDir))
        add(Location("JVM temp (java.io.tmpdir)", systemTemp))
        if (options.llmBackend == LlmBackend.CODEX_CHATGPT) {
            val codexTemp = env[CODEX_TMPDIR_ENV]?.trim()?.takeIf(String::isNotEmpty)?.let(Path::of)
            add(Location("Codex temp ($CODEX_TMPDIR_ENV)", codexTemp ?: systemTemp))
        }
    }

    /**
     * Measures each distinct volume and rejects the run when any is below [requiredFreeBytes].
     *
     * @param locations Locations from [locations].
     * @param requiredFreeBytes Floor from [requiredFreeBytes]; `0` skips measurement.
     * @param volumeKey Identity of the volume holding an existing path.
     * @param usableSpace Usable bytes on the volume holding an existing path.
     * @return Observed volumes, for logging.
     * @throws CliStoragePreflightException when any volume is below the floor.
     */
    fun verify(
        locations: List<Location>,
        requiredFreeBytes: Long,
        volumeKey: (Path) -> Any = { Files.getFileStore(it) },
        usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace }
    ): List<VolumeStatus> {
        if (requiredFreeBytes <= 0) return emptyList()
        val byVolume = linkedMapOf<Any, MutableList<Pair<Location, Path>>>()
        locations.forEach { location ->
            val probe = nearestExistingPath(location.path) ?: return@forEach
            byVolume.getOrPut(volumeKey(probe)) { mutableListOf() } += location to probe
        }
        val statuses = byVolume.values.map { entries ->
            val probe = entries.first().second
            VolumeStatus(entries.map { it.first.label }, probe, usableSpace(probe))
        }
        val insufficient = statuses.filter { it.usableBytes < requiredFreeBytes }
        if (insufficient.isNotEmpty()) {
            throw CliStoragePreflightException(
                buildString {
                    append("Insufficient free disk space to start the run: ")
                    append(
                        insufficient.joinToString("; ") { status ->
                            "${status.labels.joinToString(" + ")} at ${status.probePath} has " +
                                "${formatBytes(status.usableBytes)} usable"
                        }
                    )
                    append(". At least ${formatBytes(requiredFreeBytes)} is required per volume. ")
                    append("Free space, or point -output, TMPDIR (the launcher binds java.io.tmpdir to it) ")
                    append("and $CODEX_TMPDIR_ENV at a volume with room. ")
                    append("$MIN_FREE_ENV=<MiB> changes the floor; 0 disables this check.")
                }
            )
        }
        return statuses
    }

    /**
     * Logs a warning when a volume has dropped below the floor during a run. Never throws:
     * mid-run space pressure is surfaced for the operator, not converted into a new failure.
     *
     * @param locations Locations from [locations].
     * @param requiredFreeBytes Floor from [requiredFreeBytes].
     * @param context Short description of the run boundary.
     */
    fun warnIfLow(locations: List<Location>, requiredFreeBytes: Long, context: String) {
        runCatching { verify(locations, requiredFreeBytes) }
            .onFailure { error ->
                Napier.w("EvoMolior CLI storage pressure at $context: ${error.message}")
            }
    }

    /** Returns [path] or its closest existing ancestor, without creating anything. */
    private fun nearestExistingPath(path: Path): Path? {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current)) {
            current = current.parent
        }
        return current
    }

    /** Formats [bytes] in binary units for operator messages. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GiB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1L shl 20).toDouble())
        else -> "$bytes bytes"
    }
}
