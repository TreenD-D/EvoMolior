package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.contract.RunInputArtifact
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Koog tool: read uploaded run-start context artifacts.
 *
 * The artifacts are mounted read-only into the agent environment and mirrored in the boot
 * context as metadata so the agent can inspect the initial documents without touching the UI.
 *
 * @param artifactsMountPath Read-only host path mounted into the VM as `/artifacts`.
 * @param inputArtifacts Canonical artifact metadata from the run bootstrap snapshot.
 */
class ReadRunArtifactTool(
    private val artifactsMountPath: String?,
    private val inputArtifacts: List<RunInputArtifact>
) : SimpleTool<ReadRunArtifactTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_run_artifact",
    description = """
        Read uploaded run-start context artifacts.
        Use listOnly=true or omit relativePath to enumerate available inputs and compact summaries.
        Use relativePath to read a specific text or preview a binary artifact.
        For source citations, set includeLineNumbers=true and cite the displayed L numbers.
        Artifact metadata headers are not part of the source file's line numbering.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val relativePath: String? = null,
        val listOnly: Boolean = false,
        val includeLineNumbers: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        val path = args.relativePath?.trim()?.takeIf { it.isNotBlank() }
        val artifact = path?.let { rel -> inputArtifacts.firstOrNull { it.relativePath == rel } }

        if (args.listOnly || path == null) {
            return listArtifacts()
        }

        val storageRoot = artifactsMountPath?.let { Path.of(it) }
            ?: return "No run-artifacts mount is available for this agent."
        val resolvedPath = storageRoot.resolve(path).normalize()
        if (!resolvedPath.startsWith(storageRoot.normalize())) {
            return "Error: relativePath escapes the artifacts root."
        }
        if (!Files.exists(resolvedPath)) {
            return "Error: artifact '$path' does not exist."
        }

        val metadata = artifact
        val preview = metadata?.textPreview?.takeIf { it.isNotBlank() }
        val sourceText = runCatching {
            Files.readString(resolvedPath)
        }.getOrNull()
        val content = sourceText ?: run {
            preview ?: runCatching {
                val bytes = Files.readAllBytes(resolvedPath)
                "Binary artifact (${bytes.size} bytes). Use the archive download to retrieve the raw file."
            }.getOrDefault("Unable to read artifact '$path'.")
        }

        return buildString {
            appendLine("Artifact: ${metadata?.sourceName ?: path}")
            appendLine("Path: ${metadata?.relativePath ?: path}")
            metadata?.let {
                appendLine("Kind: ${it.kind}")
                appendLine("MIME: ${it.mimeType}")
                appendLine("Size: ${it.sizeBytes}")
                appendLine("SHA-256: ${it.sha256}")
            }
            appendLine()
            if (args.includeLineNumbers && sourceText != null) {
                appendLine("Source lines (L1 is the first file line; metadata above is excluded):")
                sourceText.lineSequence().forEachIndexed { index, line ->
                    appendLine("L${index + 1}: $line")
                }
            } else {
                appendLine(content)
            }
        }
    }

    private fun listArtifacts(): String = buildString {
        if (inputArtifacts.isEmpty()) {
            append("No uploaded run-start artifacts are available.")
            return@buildString
        }
        appendLine("Available run-start artifacts:")
        inputArtifacts.sortedBy { it.relativePath }.forEach { artifact ->
            val preview = artifact.textPreview?.takeIf { it.isNotBlank() }?.let {
                val compact = it.replace(Regex("\\s+"), " ").trim()
                compact.take(160)
            }
            append("- ${artifact.relativePath} [${artifact.kind}, ${artifact.mimeType}, ${artifact.sizeBytes} bytes]")
            if (preview != null) {
                append(" — ")
                append(preview)
            }
            appendLine()
        }
    }
}
