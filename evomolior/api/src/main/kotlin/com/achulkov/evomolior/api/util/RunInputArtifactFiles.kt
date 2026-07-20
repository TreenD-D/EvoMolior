package com.achulkov.evomolior.api.util

import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Converts regular files into the transport shape used for run-start context uploads.
 *
 * @param bundleBasePath Optional root used to preserve relative paths for a file bundle.
 */
fun File.toRunInputArtifactUpload(bundleBasePath: Path? = null): RunInputArtifactUpload {
    val bytes = readBytes()
    val normalizedPath = toPath().toAbsolutePath().normalize()
    val relativePath = bundleBasePath
        ?.let { base ->
            runCatching { base.relativize(normalizedPath).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
        }
        ?: normalizedPath.fileName?.toString()
        ?: name
    val mimeType = detectMimeType(this)
    val kind = detectArtifactKind(mimeType, this)
    val textPreview = if (kind == RunInputArtifactKind.TEXT) buildTextPreview(bytes) else null

    return RunInputArtifactUpload(
        relativePath = relativePath,
        sourceName = name,
        mimeType = mimeType,
        contentBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
        kind = kind,
        textPreview = textPreview
    )
}

/**
 * Resolves the most specific shared ancestor path for a list of files.
 *
 * @return The common ancestor directory, or null when the input is empty.
 */
fun List<File>.bundleBasePath(): Path? {
    if (isEmpty()) return null
    val normalizedPaths = map { it.toPath().toAbsolutePath().normalize() }
    val parents = normalizedPaths.map { it.parent ?: it }
    return parents.commonAncestorPath()
}

/**
 * Finds the common ancestor path for a list of paths.
 *
 * @return The deepest shared ancestor, or null when the input is empty.
 */
fun List<Path>.commonAncestorPath(): Path? {
    if (isEmpty()) return null
    if (size == 1) return first()

    val normalized = map { it.normalize() }
    val root = normalized.firstOrNull()?.root ?: return null
    val segmentLists = normalized.map { path ->
        path.mapNotNull { segment -> segment.toString().takeIf { it.isNotBlank() } }
    }
    val commonSegments = segmentLists.reduce { acc, segments ->
        acc.zip(segments).takeWhile { (a, b) -> a == b }.map { it.first }
    }

    return when {
        commonSegments.isEmpty() -> root
        else -> root.resolve(commonSegments.joinToString(File.separator))
    }
}

/**
 * Loads all regular files from a directory tree into upload payloads.
 *
 * @param inputRoot Root directory containing input context files.
 * @return Stable, path-sorted list of upload payloads.
 */
fun loadRunInputArtifacts(inputRoot: Path): List<RunInputArtifactUpload> {
    require(Files.isDirectory(inputRoot)) { "Input path must be a directory: $inputRoot" }
    val normalizedRoot = inputRoot.toAbsolutePath().normalize()

    return Files.walk(normalizedRoot).use { stream ->
        stream.filter(Files::isRegularFile)
            .sorted()
            .map { file -> file.toFile().toRunInputArtifactUpload(normalizedRoot) }
            .toList()
    }
}

private fun detectMimeType(file: File): String {
    val probed = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
    if (!probed.isNullOrBlank()) return probed

    return when (file.extension.lowercase()) {
        "txt", "md", "markdown", "json", "yaml", "yml", "csv", "tsv", "kt", "kts", "java", "py", "js", "tsx", "jsx", "html", "htm", "xml", "properties", "log" -> "text/plain"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}

private fun detectArtifactKind(mimeType: String, file: File): RunInputArtifactKind {
    if (mimeType.startsWith("text/")) return RunInputArtifactKind.TEXT
    if (mimeType in setOf("application/json", "application/xml", "text/xml", "application/yaml", "text/yaml")) {
        return RunInputArtifactKind.TEXT
    }
    return when (file.extension.lowercase()) {
        "txt", "md", "markdown", "json", "yaml", "yml", "csv", "tsv", "kt", "kts", "java", "py", "js", "tsx", "jsx", "html", "htm", "xml", "properties", "log" -> RunInputArtifactKind.TEXT
        "png", "jpg", "jpeg", "gif", "webp", "pdf" -> RunInputArtifactKind.IMAGE
        else -> RunInputArtifactKind.BINARY
    }
}

private fun buildTextPreview(bytes: ByteArray): String {
    val text = bytes.toString(Charsets.UTF_8)
    val snippet = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(6)
        .joinToString(" ")
        .ifBlank { text.replace(Regex("\\s+"), " ").trim() }
        .take(512)

    return snippet.ifBlank {
        "Text artifact (${bytes.size} bytes). Use read_run_artifact for the full file."
    }
}
