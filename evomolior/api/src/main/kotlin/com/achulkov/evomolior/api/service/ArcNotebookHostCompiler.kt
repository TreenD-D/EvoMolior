package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Host-owned deterministic compiler for the ARC receiver notebook.
 *
 * The provider may supply cell source, but it never controls notebook identity, execution state,
 * metadata, or serialization. This boundary is deliberately independent of winner authority so a
 * provisional artifact can be compiled and inspected before strict acceptance is resolved.
 */
object ArcNotebookHostCompiler {
    private const val PROFILE_ID = "arc-agi-2-notebook-v2"
    private const val NBFORMAT = 4
    private const val NBFORMAT_MINOR = 5
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    /**
     * Compiles the deterministic ARC recovery scaffold.
     *
     * @param outputSpec ARC notebook receiver output contract.
     * @param inputHashes Immutable input commitments embedded in notebook metadata.
     * @param sourceGroupId Non-accepted source group, when one is known.
     * @return A clean, host-compiled notebook output.
     */
    fun compileScaffold(
        outputSpec: com.achulkov.evomolior.api.model.FinalOutputSpec,
        inputHashes: Map<String, String> = emptyMap(),
        sourceGroupId: String? = null,
    ): GeneratedOutputFile {
        require(ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
            "ARC host compiler requires the submission.ipynb receiver target"
        }
        return compile(
            outputSpec = outputSpec,
            sourceNotebook = ArcAgi2NotebookRecoveryFallback.build(
                outputSpec = outputSpec,
                sourceGroupId = sourceGroupId ?: "scaffold",
            ).content,
            inputHashes = inputHashes,
            sourceGroupId = sourceGroupId,
        )
    }

    /**
     * Recompiles provider-authored notebook source with host-owned IDs and metadata.
     *
     * Cell order and source are the only provider-owned notebook properties retained. Every code
     * cell is made clean and unexecuted, which prevents stale execution outputs from becoming
     * receiver evidence.
     *
     * @param outputSpec ARC notebook receiver output contract.
     * @param sourceNotebook Provider-authored notebook JSON.
     * @param inputHashes Immutable input commitments embedded in notebook metadata.
     * @param sourceGroupId Non-accepted source group, when one is known.
     * @return A clean, deterministic notebook output.
     */
    fun compile(
        outputSpec: com.achulkov.evomolior.api.model.FinalOutputSpec,
        sourceNotebook: String,
        inputHashes: Map<String, String> = emptyMap(),
        sourceGroupId: String? = null,
    ): GeneratedOutputFile {
        require(ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
            "ARC host compiler requires the submission.ipynb receiver target"
        }
        val root = json.parseToJsonElement(sourceNotebook).jsonObject
        require(root["nbformat"]?.strictInt() == NBFORMAT) {
            "Provider notebook must use nbformat 4 before host compilation"
        }
        val cells = root["cells"] as? JsonArray
            ?: error("Provider notebook must contain a cells array")
        require(cells.isNotEmpty()) { "Provider notebook must contain at least one cell" }

        val compiledCells = buildJsonArray {
            cells.forEachIndexed { index, element ->
                add(compileCell(index, element, inputHashes))
            }
        }
        val metadata = buildJsonObject {
            put("language_info", buildJsonObject { put("name", JsonPrimitive("python")) })
            put(
                "evomolior",
                buildJsonObject {
                    put("profile_id", JsonPrimitive(PROFILE_ID))
                    put(
                        "input_hashes",
                        buildJsonObject {
                            inputHashes.toSortedMap().forEach { (key, value) ->
                                put(key, JsonPrimitive(value))
                            }
                        },
                    )
                    sourceGroupId?.let { put("source_group_id", JsonPrimitive(it)) }
                    put("host_compiled", JsonPrimitive(true))
                },
            )
        }
        val compiled = buildJsonObject {
            put("cells", compiledCells)
            put("metadata", metadata)
            put("nbformat", JsonPrimitive(NBFORMAT))
            put("nbformat_minor", JsonPrimitive(NBFORMAT_MINOR))
        }
        val content = json.encodeToString(JsonElement.serializer(), compiled)
        val output = GeneratedOutputFile(
            relativePath = outputSpec.relativePath,
            mediaType = outputSpec.mediaType,
            content = content,
            sourceGroupId = sourceGroupId,
        )
        val contractErrors = FinalOutputContractValidator.validate(output, outputSpec)
        require(contractErrors.isEmpty()) {
            "Host-compiled ARC notebook violates its receiver contract: " +
                contractErrors.joinToString("; ")
        }
        return output
    }

    private fun compileCell(
        index: Int,
        element: JsonElement,
        inputHashes: Map<String, String>,
    ): JsonObject {
        val cell = element as? JsonObject
            ?: error("Provider notebook cell $index must be an object")
        val cellType = cell["cell_type"]?.stringValue()
            ?: error("Provider notebook cell $index has no cell_type")
        require(cellType in setOf("code", "markdown", "raw")) {
            "Provider notebook cell $index has unsupported cell_type=$cellType"
        }
        val source = cell["source"]?.sourceText()
            ?: error("Provider notebook cell $index source must be textual")
        val handle = "cell-${index.toString().padStart(3, '0')}"
        val immutableMetadata = buildJsonObject {
            put("evomolior_cell_handle", JsonPrimitive(handle))
            put("evomolior_profile_id", JsonPrimitive(PROFILE_ID))
            put(
                "evomolior_input_hash",
                JsonPrimitive(
                    AtlasHashing.hashText(
                        inputHashes.toSortedMap().entries.joinToString("|") {
                            "${it.key}=${it.value}"
                        },
                    ),
                ),
            )
        }
        return buildJsonObject {
            put("cell_type", JsonPrimitive(cellType))
            put("id", JsonPrimitive(deterministicCellId(handle, source)))
            put("metadata", immutableMetadata)
            put("source", JsonPrimitive(source))
            if (cellType == "code") {
                put("execution_count", JsonNull)
                put("outputs", buildJsonArray {})
            }
        }
    }

    private fun deterministicCellId(handle: String, source: String): String =
        "$PROFILE_ID-${handle}-${AtlasHashing.hashText(source).take(12)}"

    private fun JsonElement.strictInt(): Int? =
        (this as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.toIntOrNull()

    private fun JsonElement.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonElement.sourceText(): String? = when (this) {
        is JsonPrimitive -> takeIf(JsonPrimitive::isString)?.content
        is JsonArray -> takeIf { all { it is JsonPrimitive && it.isString } }
            ?.joinToString("") { (it as JsonPrimitive).content }
        else -> null
    }
}

/**
 * Stages used by runtime-owned ARC artifact validation.
 */
enum class ArcNotebookValidationStage {
    STRUCTURE,
    STATIC,
    SMOKE,
    FULL_VISIBLE,
}

/**
 * Hash-bound result of one ARC notebook validation stage.
 *
 * @param stage Validation stage.
 * @param passed Whether the stage passed.
 * @param violations Public deterministic violations.
 * @param evidenceHash Hash of the exact validation evidence.
 */
data class ArcNotebookValidationReport(
    val stage: ArcNotebookValidationStage,
    val passed: Boolean,
    val violations: List<String>,
    val evidenceHash: String,
)

/**
 * Runtime-owned structural and static ARC notebook validator.
 *
 * Structural checks delegate to the canonical receiver contract. Static checks add deterministic
 * source-policy checks and Python syntax compilation when `python3` is available.
 */
object ArcNotebookHostValidator {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    /**
     * Validates notebook structure and required receiver signals.
     *
     * @param output Candidate notebook.
     * @param outputSpec Canonical ARC output contract.
     * @return Hash-bound structural report.
     */
    fun validateStructure(
        output: GeneratedOutputFile,
        outputSpec: com.achulkov.evomolior.api.model.FinalOutputSpec,
    ): ArcNotebookValidationReport {
        val violations = FinalOutputContractValidator.validate(output, outputSpec)
        return report(ArcNotebookValidationStage.STRUCTURE, violations)
    }

    /**
     * Validates source-policy and Python syntax constraints without executing receiver inputs.
     *
     * @param output Candidate notebook.
     * @param outputSpec Canonical ARC output contract.
     * @return Hash-bound static report.
     */
    fun validateStatic(
        output: GeneratedOutputFile,
        outputSpec: com.achulkov.evomolior.api.model.FinalOutputSpec,
    ): ArcNotebookValidationReport {
        val violations = FinalOutputContractValidator.validate(output, outputSpec).toMutableList()
        val code = codeSource(output.content)
        val forbidden = listOf(
            "import requests",
            "import urllib",
            "urllib.request",
            "http://",
            "https://",
            "pip install",
            "kagglehub",
        )
        forbidden.filter { code.contains(it, ignoreCase = true) }.forEach {
            violations += "static policy forbids code signal `$it`"
        }
        pythonSyntaxViolations(code)?.let(violations::add)
        return report(ArcNotebookValidationStage.STATIC, violations.distinct())
    }

    /**
     * Executes a notebook script in a bounded local sandbox.
     *
     * @param output Candidate notebook.
     * @param workspace Sandbox workspace containing local ARC inputs.
     * @param timeoutMs Execution ceiling.
     * @param stage Runtime validation stage.
     * @return Hash-bound execution report.
     */
    suspend fun validateExecution(
        output: GeneratedOutputFile,
        workspace: File,
        timeoutMs: Long = 8_000L,
        stage: ArcNotebookValidationStage = ArcNotebookValidationStage.SMOKE,
    ): ArcNotebookValidationReport = withContext(Dispatchers.IO) {
        require(stage == ArcNotebookValidationStage.SMOKE || stage == ArcNotebookValidationStage.FULL_VISIBLE) {
            "Execution validation requires SMOKE or FULL_VISIBLE stage"
        }
        workspace.mkdirs()
        val sandboxRoot = File(workspace, ".execution-sandbox").also { it.mkdirs() }
        val executionDirectory = File(sandboxRoot, "host-compiled-notebook").also { it.mkdirs() }
        workspace.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .filterNot { it.name == "host-compiled-notebook.py" || it.name == "host-compiled-notebook" }
            .forEach { it.copyTo(File(executionDirectory, it.name), overwrite = true) }
        File(workspace, "starting-point").takeIf(File::isDirectory)?.copyRecursively(
            File(executionDirectory, "starting-point"),
            overwrite = true,
        )
        val script = File(workspace, "host-compiled-notebook.py")
        script.writeText(codeSource(output.content))
        val runner = File(workspace, "host-compiled-notebook")
        runner.writeText("#!/bin/sh\nexec python3 ${shellQuote(script.absolutePath)}\n")
        require(runner.setExecutable(true)) { "Could not make ARC notebook runner executable" }
        val result = ToolSubSandbox(
            workspaceDir = sandboxRoot,
            timeoutMs = timeoutMs,
        ).execute(runner.absolutePath, "{}")
        val violations = buildList {
            if (result.timedOut) add("notebook execution timed out")
            if (result.exitCode != 0) add("notebook execution exited with ${result.exitCode}")
            if (result.stderr.isNotBlank()) {
                add("notebook execution wrote stderr: ${result.stderr.take(512)}")
            }
        }
        report(stage, violations)
    }

    private fun report(
        stage: ArcNotebookValidationStage,
        violations: List<String>,
    ): ArcNotebookValidationReport {
        val normalized = violations.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        return ArcNotebookValidationReport(
            stage = stage,
            passed = normalized.isEmpty(),
            violations = normalized,
            evidenceHash = AtlasHashing.hashText(
                stage.name + "|" + normalized.joinToString("\n"),
            ),
        )
    }

    private fun codeSource(content: String): String {
        val root = json.parseToJsonElement(content).jsonObject
        return root["cells"]
            ?.jsonArray
            ?.mapNotNull { cell ->
                val objectCell = cell as? JsonObject ?: return@mapNotNull null
                if (objectCell["cell_type"]?.hostStringValue() != "code") return@mapNotNull null
                objectCell["source"]?.hostSourceText()
            }
            ?.joinToString("\n")
            .orEmpty()
    }

    private fun pythonSyntaxViolations(code: String): String? {
        val directory = Files.createTempDirectory("evomolior-arc-static-").toFile()
        return try {
            val script = File(directory, "notebook.py")
            script.writeText(code)
            val process = ProcessBuilder("python3", "-m", "py_compile", script.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0) null else "python syntax compilation failed: ${output.take(512)}"
        } catch (missingPython: java.io.IOException) {
            "python3 is unavailable for static syntax validation"
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

private fun JsonElement.hostStringValue(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonElement.hostSourceText(): String? = when (this) {
    is JsonPrimitive -> takeIf(JsonPrimitive::isString)?.content
    is JsonArray -> takeIf { all { it is JsonPrimitive && it.isString } }
        ?.joinToString("") { (it as JsonPrimitive).content }
    else -> null
}
