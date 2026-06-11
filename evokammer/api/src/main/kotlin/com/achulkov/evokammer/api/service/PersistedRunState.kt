package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
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
    val status: PersistedRunStatus = PersistedRunStatus.BOOTSTRAPPED,
    val bootstrappedAtMs: Long = System.currentTimeMillis(),
    val lastUpdatedAtMs: Long = System.currentTimeMillis()
) {
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
            bootstrappedAtMs: Long = System.currentTimeMillis()
        ): PersistedRunState = PersistedRunState(
            runId = config.runId,
            objective = config.objective,
            groupCount = config.groupCount,
            groupProfiles = config.groupProfiles,
            rounds = config.rounds,
            roundDurationMinutes = config.roundDurationMinutes,
            maxAgentsPerGroup = config.maxAgentsPerGroup,
            scoringDimensions = config.scoringDimensions,
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

@Serializable
enum class PersistedRunStatus {
    BOOTSTRAPPING,
    BOOTSTRAPPED,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED,
    FAILED
}

object PersistedRunStateStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    const val STATE_FILE_NAME: String = "persisted-run-state.json"

    fun resolveStateFile(dataRootDir: File, runId: String): File =
        File(dataRootDir, "evokammer/runs/$runId/control/$STATE_FILE_NAME")

    fun read(dataRootDir: File, runId: String): PersistedRunState? {
        return read(resolveStateFile(dataRootDir, runId))
    }

    fun writeForDataRoot(dataRootDir: File, state: PersistedRunState) {
        write(resolveStateFile(dataRootDir, state.runId), state)
    }

    fun read(file: File): PersistedRunState? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(PersistedRunState.serializer(), file.readText())
        }.getOrNull()
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
