package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.source.SourceReportInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/** Loads only hash-verified immutable inputs for advisory source checks; never uses derived previews. */
object ReportSourceInputs {
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_SOURCE_BYTES = 16 * 1024 * 1024

    /**
     * Reads bounded, strict UTF-8 sources from the authorized run-input mount.
     *
     * Missing, oversized, changed, or unsafe inputs remain explicit unavailable entries. Hashes
     * are checked before decoding or returning any source content.
     *
     * @param inputArtifacts Immutable uploaded-input metadata authorized for this caller.
     * @param artifactsMountPath Authorized mount root; null means source text is unavailable.
     * @return Verified inputs and explicit limitations in metadata order.
     */
    suspend fun load(
        inputArtifacts: List<RunInputArtifact>,
        artifactsMountPath: String?
    ): List<SourceReportInput> = withContext(Dispatchers.IO) {
        val root = artifactsMountPath?.let { mount ->
            runCatching { Path.of(mount).toRealPath() }.getOrNull()
        }
        var bytesRead = 0
        inputArtifacts.map { artifact ->
            currentCoroutineContext().ensureActive()
            fun unavailable(reason: String) = SourceReportInput(
                sourceName = artifact.sourceName,
                relativePath = artifact.relativePath,
                text = null,
                unavailableReason = reason
            )
            if (root == null) return@map unavailable("Run-input mount is unavailable.")
            if (artifact.sizeBytes < 0 || artifact.sizeBytes > MAX_SOURCE_BYTES) {
                return@map unavailable("Source exceeds the 2 MiB per-file verification limit or has invalid size.")
            }
            val remaining = MAX_TOTAL_SOURCE_BYTES - bytesRead
            if (artifact.sizeBytes > remaining || remaining <= 0) {
                return@map unavailable("Run inputs exceed the 16 MiB total verification limit.")
            }
            try {
                val relative = Path.of(artifact.relativePath)
                require(!relative.isAbsolute && relative.toString().isNotBlank() &&
                    relative.none { it.toString() == ".." }) {
                    "Input path must remain inside its authorized mount."
                }
                val source = root.resolve(relative).normalize()
                require(source.startsWith(root) && source != root) {
                    "Input path escapes its authorized mount."
                }
                requireSafePath(root, source)
                val limit = minOf(MAX_SOURCE_BYTES, remaining)
                val bytes = Files.newInputStream(source, READ, NOFOLLOW_LINKS).use {
                    it.readNBytes(limit + 1)
                }
                bytesRead += bytes.size
                require(bytes.size <= limit) { "Source exceeds its bounded verification allowance." }
                requireSafePath(root, source)
                require(bytes.size.toLong() == artifact.sizeBytes) { "Source size differs from immutable metadata." }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                require(hash == artifact.sha256) { "Source SHA-256 differs from immutable metadata." }
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                SourceReportInput(artifact.sourceName, artifact.relativePath, text)
            } catch (error: Exception) {
                // Failure details intentionally omit host paths and any unverified file content.
                val reason = if (error is IllegalArgumentException) {
                    error.message ?: "Source verification failed."
                } else {
                    "Source is unreadable or is not strict UTF-8."
                }
                unavailable(reason)
            }
        }
    }

    private fun requireSafePath(root: Path, source: Path) {
        var current = root
        root.relativize(source).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Symbolic links are not source authority." }
        }
        require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "Source is not a regular file." }
        require(source.toRealPath().startsWith(root)) { "Source resolves outside its authorized mount." }
    }
}
