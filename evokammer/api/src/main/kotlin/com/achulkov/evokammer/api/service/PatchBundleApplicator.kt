package com.achulkov.evokammer.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipInputStream

private val patchBundleJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Structured patch bundle emitted by fully mutable overseer runs.
 */
@Serializable
internal data class MachineReadablePatchBundle(
    val summary: String = "",
    val changedFiles: List<MachineReadablePatchBundleChangedFile> = emptyList(),
    val documentation: String = "",
    val verification: List<String> = emptyList(),
    val notes: String = ""
)

/**
 * One file-level edit in a patch bundle.
 */
@Serializable
internal data class MachineReadablePatchBundleChangedFile(
    val path: String = "",
    val status: String = "modified",
    val diff: String = "",
    val rationale: String = "",
    val content: String? = null
)

/**
 * Durable report for a patch bundle application into a mutable repo copy.
 */
@Serializable
internal data class PatchBundleApplicationReport(
    val runId: String? = null,
    val bundlePath: String,
    val repoRoot: String,
    val summary: String,
    val changedFiles: List<String>,
    val appliedAt: String = Instant.now().toString(),
    val reportPath: String
)

/**
 * Applies a machine-readable patch bundle to the mutable repo copy.
 */
internal object PatchBundleApplicator {
    /**
     * Extracts and applies a patch bundle from a completed run's results bundle.
     *
     * @param resultsBundle Completed run `results.zip` bytes.
     * @param repoRoot Mutable repo copy to rewrite.
     * @param expectedRelativePath Relative output path under `outputs/`.
     * @param runId Optional run identifier for reporting.
     */
    fun applyIfPresent(
        resultsBundle: ByteArray,
        repoRoot: Path,
        expectedRelativePath: String,
        runId: String? = null
    ): PatchBundleApplicationReport? {
        val patchJson = extractPatchBundleJson(resultsBundle, expectedRelativePath) ?: return null
        return applyPatchBundleJson(
            patchBundleJson = patchJson,
            repoRoot = repoRoot,
            expectedRelativePath = expectedRelativePath,
            runId = runId
        )
    }

    /**
     * Extracts the raw patch bundle JSON from a results bundle.
     *
     * @param resultsBundle Completed run `results.zip` bytes.
     * @param expectedRelativePath Relative output path under `outputs/`.
     */
    fun extractPatchBundleJson(
        resultsBundle: ByteArray,
        expectedRelativePath: String
    ): String? {
        val normalizedExpected = expectedRelativePath.trim().trimStart('/')
        val expectedEntry = if (normalizedExpected.startsWith("outputs/")) {
            normalizedExpected
        } else {
            "outputs/$normalizedExpected"
        }

        ZipInputStream(ByteArrayInputStream(resultsBundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == expectedEntry) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }

        return null
    }

    /**
     * Applies a patch bundle JSON payload into the repo copy.
     *
     * The application is staged in memory first so a malformed bundle does not partially rewrite
     * the workspace.
     */
    fun applyPatchBundleJson(
        patchBundleJson: String,
        repoRoot: Path,
        expectedRelativePath: String,
        runId: String? = null
    ): PatchBundleApplicationReport {
        val bundle = patchBundleJson.decodeToPatchBundle()
        val repoRootNormalized = repoRoot.toAbsolutePath().normalize()
        val stagedWrites = linkedMapOf<Path, String>()
        val stagedDeletes = linkedSetOf<Path>()
        val changedFiles = linkedSetOf<String>()

        bundle.changedFiles.forEach { change ->
            val target = resolveTargetPath(repoRootNormalized, change.path)
            when {
                change.status.equals("deleted", ignoreCase = true) -> {
                    stagedDeletes.add(target)
                    changedFiles.add(repoRootNormalized.relativize(target).invariantSeparatorsPath)
                }
                !change.content.isNullOrBlank() -> {
                    stagedWrites[target] = normalizeLineEndings(change.content)
                    changedFiles.add(repoRootNormalized.relativize(target).invariantSeparatorsPath)
                }
                change.diff.isNotBlank() -> {
                    val original = runCatching {
                        if (Files.exists(target)) {
                            Files.readString(target)
                        } else {
                            ""
                        }
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "Failed to read ${repoRootNormalized.relativize(target)}: ${error.message}",
                            error
                        )
                    }
                    val updated = applyDiffToContent(
                        originalContent = normalizeLineEndings(original),
                        diff = change.diff,
                        targetPath = repoRootNormalized.relativize(target).invariantSeparatorsPath
                    )
                    stagedWrites[target] = updated
                    changedFiles.add(repoRootNormalized.relativize(target).invariantSeparatorsPath)
                }
                else -> {
                    throw IllegalStateException(
                        "Patch bundle change '${change.path}' does not include diff or content"
                    )
                }
            }
        }

        stagedDeletes.forEach { target ->
            Files.deleteIfExists(target)
        }
        stagedWrites.forEach { (target, content) ->
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }

        val report = PatchBundleApplicationReport(
            runId = runId,
            bundlePath = expectedRelativePath,
            repoRoot = repoRootNormalized.toString(),
            summary = bundle.summary.ifBlank { "Applied patch bundle" },
            changedFiles = changedFiles.toList(),
            reportPath = repoRootNormalized
                .resolve(".evokammer-self-improve")
                .resolve("applied-patch-bundles")
                .resolve("${runId ?: "patch-bundle"}-${Instant.now().toEpochMilli()}.json")
                .toString()
        )
        writeReport(report)
        return report
    }

    private fun applyDiffToContent(
        originalContent: String,
        diff: String,
        targetPath: String
    ): String {
        var updated = normalizeLineEndings(originalContent)
        val hunks = parseHunks(diff)
        if (hunks.isEmpty()) {
            throw IllegalStateException("Patch bundle diff for $targetPath did not contain any hunks")
        }

        hunks.forEach { hunk ->
            updated = applyHunk(updated, hunk, targetPath)
        }
        return updated
    }

    private fun applyHunk(
        source: String,
        hunk: PatchHunk,
        targetPath: String
    ): String {
        val sourceNormalized = normalizeLineEndings(source)
        val removalBlock = hunk.lines.asSequence()
            .filterNot { it.line == "\\ No newline at end of file" }
            .filter { it.lineType != '+' }
            .map { it.line }
            .joinToString("\n")
            .trimEnd()
        val additionBlock = hunk.lines.asSequence()
            .filterNot { it.line == "\\ No newline at end of file" }
            .filter { it.lineType != '-' }
            .map { it.line }
            .joinToString("\n")
            .trimEnd()

        if (removalBlock.isBlank()) {
            return if (sourceNormalized.isBlank()) {
                additionBlock
            } else {
                listOf(sourceNormalized.trimEnd(), additionBlock).filter { it.isNotBlank() }.joinToString("\n")
            }
        }

        val searchIndex = sourceNormalized.indexOf(removalBlock)
        if (searchIndex < 0) {
            throw IllegalStateException(
                "Could not locate patch hunk in $targetPath. Expected block:\n$removalBlock"
            )
        }

        return buildString {
            append(sourceNormalized.substring(0, searchIndex))
            append(additionBlock)
            append(sourceNormalized.substring(searchIndex + removalBlock.length))
        }
    }

    private fun parseHunks(diff: String): List<PatchHunk> {
        val hunks = mutableListOf<PatchHunk>()
        var currentHeader: String? = null
        var currentLines = mutableListOf<PatchLine>()

        diff.lineSequence().forEach { rawLine ->
            when {
                rawLine.startsWith("--- ") || rawLine.startsWith("+++ ") -> Unit
                rawLine.startsWith("@@") -> {
                    if (currentHeader != null || currentLines.isNotEmpty()) {
                        hunks += PatchHunk(currentHeader.orEmpty(), currentLines.toList())
                        currentLines = mutableListOf()
                    }
                    currentHeader = rawLine
                }
                currentHeader != null -> {
                    when {
                        rawLine.startsWith("+") && rawLine != "+++" -> currentLines += PatchLine('+', rawLine.drop(1))
                        rawLine.startsWith("-") && rawLine != "---" -> currentLines += PatchLine('-', rawLine.drop(1))
                        rawLine.startsWith(" ") -> currentLines += PatchLine(' ', rawLine.drop(1))
                        rawLine == "\\ No newline at end of file" -> currentLines += PatchLine('\\', rawLine)
                        rawLine.isBlank() -> currentLines += PatchLine(' ', "")
                        else -> currentLines += PatchLine(' ', rawLine)
                    }
                }
            }
        }

        if (currentHeader != null || currentLines.isNotEmpty()) {
            hunks += PatchHunk(currentHeader.orEmpty(), currentLines.toList())
        }

        return hunks
    }

    private fun String.decodeToPatchBundle(): MachineReadablePatchBundle =
        patchBundleJson.decodeFromString(MachineReadablePatchBundle.serializer(), this)

    private fun resolveTargetPath(repoRoot: Path, relativePath: String): Path {
        val normalized = relativePath.trim().trimStart('/').removePrefix("a/").removePrefix("b/")
        require(normalized.isNotBlank()) { "Patch bundle file path cannot be blank" }
        val target = repoRoot.resolve(normalized).normalize()
        require(target.startsWith(repoRoot)) {
            "Patch bundle path '$relativePath' escapes the repo root"
        }
        return target
    }

    private fun writeReport(report: PatchBundleApplicationReport) {
        val reportFile = Path.of(report.reportPath)
        Files.createDirectories(reportFile.parent)
        Files.writeString(
            reportFile,
            patchBundleJson.encodeToString(PatchBundleApplicationReport.serializer(), report)
        )
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")

    private val Path.invariantSeparatorsPath: String
        get() = toString().replace(File.separatorChar, '/')

    private data class PatchHunk(val header: String, val lines: List<PatchLine>)

    private data class PatchLine(val lineType: Char, val line: String)
}
