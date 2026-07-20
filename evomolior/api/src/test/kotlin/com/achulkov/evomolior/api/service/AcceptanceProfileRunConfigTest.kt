package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceProfileRunConfigTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `normal config JSON remains profile-free while acceptance profiles propagate`() {
        val normal = config()
        val encoded = json.encodeToString(RunConfig.serializer(), normal)
        assertFalse(encoded.contains("acceptanceProfile"))
        assertEquals(normal, json.decodeFromString(RunConfig.serializer(), encoded))

        val smoke = acceptanceConfig(AcceptanceProfile.RUNTIME_USABILITY_SMOKE)
        val envelope = CouncilExecutionEnvelopeFactory.create(smoke)
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, envelope.acceptanceProfile)

        val persisted = PersistedRunState.fromRunConfig(
            config = smoke,
            groupIds = listOf("worker-0", "worker-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        )
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, persisted.acceptanceProfile)
        assertEquals(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            persisted.toRunConfig("/tmp/codex").acceptanceProfile
        )
        assertFailsWith<IllegalArgumentException> {
            persisted.requireCompatibleRecoveryIntent(
                smoke.copy(acceptanceProfile = AcceptanceProfile.NORMAL)
            )
        }

        val persistedJson = json.encodeToString(PersistedRunState.serializer(), persisted)
        assertTrue(persistedJson.contains("\"acceptanceProfile\":\"runtime-usability-smoke\""))
    }

    @Test
    fun `acceptance config rejects incompatible purpose horizon completion and output`() {
        assertFailsWith<IllegalArgumentException> {
            config(rounds = 1).copy(
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.RUNTIME_USABILITY_SMOKE).copy(rounds = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(rounds = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                autoCompletionPolicy = RunAutoCompletionPolicy(2)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                finalOutputSpec = null
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "result.bin",
                    mediaType = "application/octet-stream"
                )
            )
        }
    }

    private fun acceptanceConfig(profile: AcceptanceProfile): RunConfig = config(
        rounds = when (profile) {
            AcceptanceProfile.NORMAL -> 2
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> 1
            AcceptanceProfile.APPENDIX_B_FULL -> 6
        }
    ).copy(
        llmBackend = LlmBackend.CODEX_CHATGPT,
        councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
        acceptanceProfile = profile,
        finalOutputSpec = FinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        )
    )

    private fun config(rounds: Int = 2): RunConfig = RunConfig(
        runId = "acceptance-profile-config",
        objective = "Produce a receiver-usable result.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = rounds,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver quality",
                weight = 1.0
            )
        ),
        openRouterApiKey = "test-key"
    )
}
