package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Everything that determines what a CLI stage is asked to produce.
 *
 * Completed-stage reuse compares the SHA-256 of this record, so changing the prompt, any input
 * byte, the output contract, or run/model configuration can never return an earlier run's
 * output as the result of a different request.
 */
@Serializable
internal data class CliStageRequestIdentity(
    val version: Int = 1,
    val stageId: String,
    val runId: String,
    val prompt: String,
    val inputFiles: List<CliStageInputFileIdentity>,
    val llmBackend: LlmBackend,
    val fast: Boolean,
    val rounds: Int,
    val groupCount: Int?,
    val maxAgentsPerGroup: Int?,
    val roundDurationMinutes: Int?,
    val budgetOverride: Double?,
    val modelOverrides: Map<String, String>,
    val finalOutputSpec: CliPipelineFinalOutputSpec?,
    val enforceProviderMonetaryCostCap: Boolean,
    val councilExecutionPurpose: CouncilExecutionPurpose,
    val acceptanceProfile: AcceptanceProfile,
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode,
    val codexReasoningEffort: CodexReasoningEffort,
    val codexRuntimePreset: CodexRuntimePreset?,
    val quantitativeToolObligation: QuantitativeToolObligation?,
    val autoCompletionPolicy: RunAutoCompletionPolicy?,
    val ordinaryAgentTtlSeconds: Int? = null,
    val graderGroupCount: Int? = null
)

/**
 * Content identity of one input file.
 *
 * @param path Path relative to the stage input directory, with `/` separators.
 * @param sha256 Lowercase hex SHA-256 of the exact file bytes.
 */
@Serializable
internal data class CliStageInputFileIdentity(val path: String, val sha256: String)

private val fingerprintJson = Json { encodeDefaults = true }

/**
 * Computes the request fingerprint for [stage] executed with [llmBackend].
 *
 * @param stage Resolved stage plan, including its prepared input directory.
 * @param llmBackend Provider backend selected for the whole pipeline.
 * @return Lowercase hex SHA-256 of the canonical [CliStageRequestIdentity].
 */
internal fun cliStageRequestFingerprint(stage: CliStagePlan, llmBackend: LlmBackend): String {
    val identity = CliStageRequestIdentity(
        stageId = stage.id,
        runId = stage.runId,
        prompt = stage.prompt,
        inputFiles = inputFileIdentities(stage.inputDir),
        llmBackend = llmBackend,
        fast = stage.fast,
        rounds = stage.rounds,
        groupCount = stage.groupCount,
        maxAgentsPerGroup = stage.maxAgentsPerGroup,
        roundDurationMinutes = stage.roundDurationMinutes,
        budgetOverride = stage.budgetOverride,
        modelOverrides = stage.modelOverrides.toSortedMap(),
        finalOutputSpec = stage.finalOutputSpec,
        enforceProviderMonetaryCostCap = stage.enforceProviderMonetaryCostCap,
        councilExecutionPurpose = stage.councilExecutionPurpose,
        acceptanceProfile = stage.acceptanceProfile,
        acceptanceDiagnosticMode = stage.acceptanceDiagnosticMode,
        codexReasoningEffort = stage.codexReasoningEffort,
        codexRuntimePreset = stage.codexRuntimePreset,
        quantitativeToolObligation = stage.quantitativeToolObligation,
        autoCompletionPolicy = stage.autoCompletionPolicy,
        ordinaryAgentTtlSeconds = stage.ordinaryAgentTtlSeconds,
        graderGroupCount = stage.graderGroupCount
    )
    val canonical = fingerprintJson.encodeToString(CliStageRequestIdentity.serializer(), identity)
    return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
}

/** Hashes every regular file under [inputDir], matching the files a run loads as input. */
private fun inputFileIdentities(inputDir: Path): List<CliStageInputFileIdentity> {
    if (!Files.isDirectory(inputDir)) return emptyList()
    val root = inputDir.toAbsolutePath().normalize()
    return Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile)
            .map { file ->
                CliStageInputFileIdentity(
                    path = root.relativize(file).joinToString("/"),
                    sha256 = fileSha256Hex(file)
                )
            }
            .toList()
    }.sortedBy { it.path }
}

private fun fileSha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
