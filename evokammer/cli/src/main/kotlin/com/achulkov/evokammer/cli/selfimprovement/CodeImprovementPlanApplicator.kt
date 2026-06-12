package com.achulkov.evokammer.cli.selfimprovement

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val codeImprovementJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Applies a generated code-improvement plan into a repo copy.
 *
 * The current implementation is intentionally narrow: it supports the structured
 * bootstrap plan emitted by the fast pipeline and rewrites the targeted Kotlin file
 * in the repo copy before the next stage starts.
 */
internal object CodeImprovementPlanApplicator {
    /**
     * Applies the plan if the file exists and returns the application report.
     *
     * @param planFile JSON plan emitted by the fast pipeline.
     * @param repoRoot Repo copy that should receive the edits.
     * @return Application report, or null when the plan file is absent.
     */
    fun applyIfPresent(planFile: Path, repoRoot: Path): CodeImprovementApplicationReport? {
        if (!Files.exists(planFile)) return null

        val plan = codeImprovementJson.decodeFromString(CodeImprovementPlan.serializer(), Files.readString(planFile))
        return apply(planFile, repoRoot, plan)
    }

    private fun apply(
        planFile: Path,
        repoRoot: Path,
        plan: CodeImprovementPlan
    ): CodeImprovementApplicationReport {
        val changedFiles = linkedSetOf<Path>()

        plan.proposedChanges.forEach { change ->
            val details = change.implementationDetails ?: return@forEach
            val targetFile = resolveTargetFile(repoRoot, details.location, plan.affectedFiles)
                ?: error("Could not resolve plan target '${details.location}' in repo root $repoRoot")

            val original = Files.readString(targetFile)
            var updated = original

            details.changes.forEach { instruction ->
                val snippet = instruction.codeSnippet?.trim().orEmpty()
                if (snippet.isBlank()) return@forEach
                updated = applyInstruction(
                    fileContent = updated,
                    targetFile = targetFile,
                    method = details.method,
                    snippet = snippet
                )
            }

            if (updated != original) {
                Files.writeString(targetFile, updated)
                changedFiles.add(targetFile)
                Napier.i("CodeImprovementPlanApplicator: applied ${change.changeId} to ${repoRoot.relativize(targetFile)}")
            }
        }

        val report = CodeImprovementApplicationReport(
            planPath = planFile.toString(),
            repoRoot = repoRoot.toString(),
            changedFiles = changedFiles.map { repoRoot.relativize(it).invariantSeparatorsPath },
            reportPath = repoRoot.resolve(".evokammer-self-improve/applied-code-improvement-report.json").toString()
        )
        writeReport(report)
        return report
    }

    private fun applyInstruction(
        fileContent: String,
        targetFile: Path,
        method: String,
        snippet: String
    ): String {
        val functionName = normalizeFunctionName(method)
            ?: extractFunctionName(snippet)

        if (functionName != null) {
            val replaced = replaceFunctionBlock(fileContent, functionName, snippet)
            if (replaced != null) return replaced
        }

        if (snippet.contains("loadContextFile") && !fileContent.contains("loadContextFile")) {
            return insertBeforeBootstrapContextAnchor(fileContent, snippet)
        }

        if (fileContent.contains(snippet)) return fileContent
        return appendBeforeBootstrapContextAnchor(fileContent, snippet)
    }

    private fun replaceFunctionBlock(
        source: String,
        functionName: String,
        replacement: String
    ): String? {
        val signatureIndex = source.indexOfFunctionSignature(functionName) ?: return null
        val nextDeclarationIndex = source.indexOfNextTopLevelDeclaration(signatureIndex + 1)
            ?: return null
        return buildString {
            append(source.substring(0, signatureIndex))
            append(replacement.trim())
            appendLine()
            append(source.substring(nextDeclarationIndex))
        }
    }

    private fun insertBeforeBootstrapContextAnchor(source: String, snippet: String): String {
        val anchor = source.indexOf("\n    private fun loadBootstrapContext(")
            .takeIf { it >= 0 }
            ?: source.indexOf("\n}\n\n/**\n * Produces a structured overseer proposal")
                .takeIf { it >= 0 }
            ?: source.length

        return buildString {
            append(source.substring(0, anchor))
            if (anchor > 0 && source[anchor - 1] != '\n') {
                appendLine()
            }
            appendLine(snippet.trim())
            append(source.substring(anchor))
        }
    }

    private fun appendBeforeBootstrapContextAnchor(source: String, snippet: String): String {
        return insertBeforeBootstrapContextAnchor(source, snippet)
    }

    private fun resolveTargetFile(
        repoRoot: Path,
        location: String,
        affectedFiles: List<String>
    ): Path? {
        val candidates = buildList {
            add(location)
            addAll(affectedFiles)
        }.map { it.trim() }.filter { it.isNotBlank() }

        candidates.forEach { candidate ->
            val direct = repoRoot.resolve(candidate).normalize()
            if (Files.exists(direct)) return direct
        }

        val needleNames = candidates.map { Path.of(it).fileName?.toString().orEmpty() }
            .filter { it.isNotBlank() }
            .toSet()

        return Files.walk(repoRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { file ->
                    val relative = repoRoot.relativize(file).invariantSeparatorsPath
                    needleNames.any { needle ->
                        relative.endsWith(needle) || file.fileName.toString() == needle
                    }
                }
                .findFirst()
                .orElse(null)
        }
    }

    private val Path.invariantSeparatorsPath: String
        get() = toString().replace(File.separatorChar, '/')

    private fun normalizeFunctionName(method: String): String? {
        val trimmed = method.trim()
        if (trimmed.isBlank()) return null
        return trimmed
            .removeSuffix("()")
            .substringBefore('(')
            .trim()
            .ifBlank { null }
    }

    private fun extractFunctionName(snippet: String): String? {
        return snippet.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(" fun ") || it.startsWith("fun ") }
            ?.substringAfter("fun ")
            ?.substringBefore("(")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun String.indexOfFunctionSignature(functionName: String): Int? {
        val direct = indexOf("fun $functionName(")
        if (direct >= 0) return direct
        val spaced = indexOf("fun $functionName (")
        if (spaced >= 0) return spaced
        val privateDirect = indexOf("private fun $functionName(")
        if (privateDirect >= 0) return privateDirect
        return indexOf("private fun $functionName (").takeIf { it >= 0 }
    }

    private fun String.indexOfNextTopLevelDeclaration(fromIndex: Int): Int? {
        val declarations = listOf(
            "\nfun ",
            "\nprivate fun ",
            "\ninternal fun ",
            "\n    private fun ",
            "\n    internal fun ",
            "\n    fun ",
            "\n}"
        )
        val next = declarations.mapNotNull { token ->
            indexOf(token, fromIndex).takeIf { it >= 0 }
        }.minOrNull()
        return next
    }

    private fun writeReport(report: CodeImprovementApplicationReport) {
        val reportFile = Path.of(report.reportPath)
        Files.createDirectories(reportFile.parent)
        Files.writeString(reportFile, codeImprovementJson.encodeToString(CodeImprovementApplicationReport.serializer(), report))
    }
}
