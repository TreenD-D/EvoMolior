package com.achulkov.evomolior.util

import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * Opens a native file picker and converts the selected files into run-start context uploads.
 */
fun pickRunInputArtifacts(): List<RunInputArtifactUpload> {
    val chooser = JFileChooser().apply {
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "Add Run Context Files"
    }

    val result = chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return emptyList()

    val selectedFiles: List<File> = chooser.selectedFiles.toList().takeIf { it.isNotEmpty() }
        ?: chooser.selectedFile?.let { listOf(it) }
        ?: emptyList()

    val bundleBasePath = selectedFiles.bundleBasePath()
    return selectedFiles.map { it.toRunInputArtifactUpload(bundleBasePath) }
}

internal fun List<File>.bundleBasePath(): Path? {
    if (isEmpty()) return null
    val normalizedPaths = map { it.toPath().toAbsolutePath().normalize() }
    val parents = normalizedPaths.map { it.parent ?: it }
    return parents.commonAncestorPath()
}

internal fun List<Path>.commonAncestorPath(): Path? {
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

internal fun File.toRunInputArtifactUpload(bundleBasePath: Path?): RunInputArtifactUpload {
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
