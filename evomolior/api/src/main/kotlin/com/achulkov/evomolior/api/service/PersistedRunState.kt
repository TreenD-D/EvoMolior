package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable, non-secret recovery snapshot for a persisted run.
 *
 * The snapshot is stored on disk outside Room so crash-resume support can evolve without
 * triggering destructive DB migrations on existing runs.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersistedRunState(
    val runId: String,
    val objective: String,
    val groupCount: Int,
    val groupProfiles: List<GroupProfileType>,
    val rounds: Int,
    val roundDurationMinutes: Int,
    val maxAgentsPerGroup: Int,
    val scoringDimensions: List<ScoringDimension>,
    val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val codexRuntimePreset: CodexRuntimePreset? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quantitativeToolObligation: QuantitativeToolObligation? = null,
    val autoCompletionPolicy: RunAutoCompletionPolicy? = null,
    val modelOverrides: Map<String, String> = emptyMap(),
    val finalOutputSpec: FinalOutputSpec? = null,
    val isTestRun: Boolean = false,
    val budgetOverride: Double? = null,
    val continuationFromRunId: String? = null,
    val groupIds: List<String>,
    val graderGroupIds: List<String>,
    val allowedHosts: List<String> = emptyList(),
    val nextRoundIndex: Int = 0,
    val activeRoundIndex: Int? = null,
    val activeCouncilManifestSchemaVersion: String? = null,
    val activeCouncilManifestHash: String? = null,
    val autoCompletionCandidateWinnerId: String? = null,
    val autoCompletionAcceptedRoundStreak: Int = 0,
    val status: PersistedRunStatus = PersistedRunStatus.BOOTSTRAPPED,
    val bootstrappedAtMs: Long = System.currentTimeMillis(),
    val lastUpdatedAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(rounds > 0) { "Persisted run rounds must be positive" }
        require(nextRoundIndex in 0..rounds) {
            "Persisted nextRoundIndex=$nextRoundIndex is outside 0..$rounds"
        }
        require(activeRoundIndex == null || activeRoundIndex in 0 until rounds) {
            "Persisted activeRoundIndex=$activeRoundIndex is outside 0 until $rounds"
        }
    }

    /**
     * Fails closed when a caller attempts to resume with different execution intent.
     *
     * The runtime credential may be supplied again, but timeout purpose and convergence policy
     * remain governed by the persisted run-state authority.
     */
    fun requireCompatibleRecoveryIntent(config: RunConfig) {
        require(config.runId == runId) {
            "Recovery config runId=${config.runId} does not match persisted runId=$runId"
        }
        require(config.councilExecutionPurpose == councilExecutionPurpose) {
            "Recovery Council execution purpose=${config.councilExecutionPurpose.id} does not " +
                "match persisted purpose=${councilExecutionPurpose.id}"
        }
        require(config.acceptanceProfile == acceptanceProfile) {
            "Recovery acceptance profile=${config.acceptanceProfile.id} does not match " +
                "persisted profile=${acceptanceProfile.id}"
        }
        require(config.acceptanceDiagnosticMode == acceptanceDiagnosticMode) {
            "Recovery acceptance diagnostic mode=${config.acceptanceDiagnosticMode} does not " +
                "match persisted mode=$acceptanceDiagnosticMode"
        }
        require(config.codexReasoningEffort == codexReasoningEffort) {
            "Recovery Codex reasoning effort=${config.codexReasoningEffort.configValue} does not " +
                "match persisted effort=${codexReasoningEffort.configValue}"
        }
        require(config.codexRuntimePreset == codexRuntimePreset) {
            "Recovery Codex preset must match the persisted run"
        }
        require(config.quantitativeToolObligation == quantitativeToolObligation) {
            "Recovery quantitative-tool obligation=${config.quantitativeToolObligation} does not " +
                "match persisted obligation=$quantitativeToolObligation"
        }
        require(config.autoCompletionPolicy == autoCompletionPolicy) {
            "Recovery auto-completion policy=${config.autoCompletionPolicy} does not match " +
                "persisted policy=$autoCompletionPolicy"
        }
    }

    /**
     * Rebuilds the runtime launch config, requiring the caller to provide the API key rather
     * than persisting secrets to disk.
     */
    fun toRunConfig(openRouterApiKey: String): RunConfig = RunConfig(
        runId = runId,
        objective = objective,
        groupCount = groupCount,
        groupProfiles = groupProfiles,
        rounds = rounds,
        roundDurationMinutes = roundDurationMinutes,
        maxAgentsPerGroup = maxAgentsPerGroup,
        scoringDimensions = scoringDimensions,
        openRouterApiKey = openRouterApiKey,
        llmBackend = llmBackend,
        councilExecutionPurpose = councilExecutionPurpose,
        acceptanceProfile = acceptanceProfile,
        acceptanceDiagnosticMode = acceptanceDiagnosticMode,
        codexReasoningEffort = codexReasoningEffort,
        codexRuntimePreset = codexRuntimePreset,
        quantitativeToolObligation = quantitativeToolObligation,
        autoCompletionPolicy = autoCompletionPolicy,
        modelOverrides = modelOverrides,
        contextArtifacts = emptyList(),
        continuationFromRunId = continuationFromRunId,
        finalOutputSpec = finalOutputSpec,
        isTestRun = isTestRun,
        budgetOverride = budgetOverride
    )

    companion object {
        fun fromRunConfig(
            config: RunConfig,
            groupIds: List<String>,
            graderGroupIds: List<String>,
            allowedHosts: Set<String>,
            nextRoundIndex: Int = 0,
            activeRoundIndex: Int? = null,
            status: PersistedRunStatus = PersistedRunStatus.BOOTSTRAPPED,
            bootstrappedAtMs: Long = System.currentTimeMillis(),
            groupProfiles: List<GroupProfileType> = config.groupProfiles
        ): PersistedRunState {
            require(groupProfiles.size == groupIds.size) {
                "groupProfiles size must match the persisted worker group IDs"
            }
            return PersistedRunState(
                runId = config.runId,
                objective = config.objective,
                groupCount = config.groupCount,
                groupProfiles = groupProfiles,
                rounds = config.rounds,
                roundDurationMinutes = config.roundDurationMinutes,
                maxAgentsPerGroup = config.maxAgentsPerGroup,
                scoringDimensions = config.scoringDimensions,
                llmBackend = config.llmBackend,
                councilExecutionPurpose = config.councilExecutionPurpose,
                acceptanceProfile = config.acceptanceProfile,
                acceptanceDiagnosticMode = config.acceptanceDiagnosticMode,
                codexReasoningEffort = config.codexReasoningEffort,
                codexRuntimePreset = config.codexRuntimePreset,
                quantitativeToolObligation = config.quantitativeToolObligation,
                autoCompletionPolicy = config.autoCompletionPolicy,
                modelOverrides = config.modelOverrides,
                finalOutputSpec = config.finalOutputSpec,
                isTestRun = config.isTestRun,
                budgetOverride = config.budgetOverride,
                continuationFromRunId = config.continuationFromRunId,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                allowedHosts = allowedHosts.toList().sorted(),
                nextRoundIndex = nextRoundIndex,
                activeRoundIndex = activeRoundIndex,
                status = status,
                bootstrappedAtMs = bootstrappedAtMs,
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }
}

@Serializable
enum class PersistedRunStatus {
    BOOTSTRAPPING,
    BOOTSTRAPPED,
    RUNNING,
    PAUSED,
    COMPLETED,
    RECOVERED,
    ABORTED,
    FAILED
}

object PersistedRunStateStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    const val STATE_FILE_NAME: String = "persisted-run-state.json"

    fun resolveStateFile(dataRootDir: File, runId: String): File =
        File(dataRootDir, "evomolior/runs/$runId/control/$STATE_FILE_NAME")

    fun read(dataRootDir: File, runId: String): PersistedRunState? {
        return read(resolveStateFile(dataRootDir, runId))
    }

    fun writeForDataRoot(dataRootDir: File, state: PersistedRunState) {
        write(resolveStateFile(dataRootDir, state.runId), state)
    }

    fun read(file: File): PersistedRunState? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(PersistedRunState.serializer(), file.readText())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Persisted run state is unreadable: ${file.absolutePath}",
                error
            )
        }
    }

    fun write(file: File, state: PersistedRunState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(PersistedRunState.serializer(), state))
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }
}
