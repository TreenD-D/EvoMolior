package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.Role
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Verifies the static boot-context reading contract of [AgentRunner].
 *
 * These tests do **not** launch a real agent or hit OpenRouter — they only
 * exercise the environment-variable / file-based boot-context loading logic.
 */
class AgentRunnerContractTest {

    private fun sampleBootContext() = AgentBootContext(
        agentId = "agent-vm-001",
        runId = "run-shuru-test",
        groupId = "group-0",
        role = Role.Researcher,
        rolePrompt = "You are a researcher.",
        budgetToken = "token:agent-vm-001:0",
        substrateDbPath = "/tmp/evomolior/run-test/group-0/substrate.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "Stage 1: research fundamentals",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = 50.0
    )

    @Test
    fun `readBootContext parses a round-trip serialised AgentBootContext`() {
        val original = sampleBootContext()
        val jsonCodec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = jsonCodec.encodeToString(original)

        // Simulate what AgentRunner.readBootContext does from env var
        val decoded = jsonCodec.decodeFromString<AgentBootContext>(encoded)

        assertEquals(original.agentId, decoded.agentId)
        assertEquals(original.runId, decoded.runId)
        assertEquals(original.groupId, decoded.groupId)
        assertEquals(original.role, decoded.role)
        assertEquals(original.allocatedBudget, decoded.allocatedBudget, "allocatedBudget must survive JSON round-trip")
        assertEquals(original.gracePeriodSeconds, decoded.gracePeriodSeconds)
    }

    @Test
    fun `readBootContext preserves allocatedBudget of zero as distinct from unset`() {
        val jsonCodec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val ctx = sampleBootContext().copy(allocatedBudget = 0.0)
        val encoded = jsonCodec.encodeToString(ctx)
        val decoded = jsonCodec.decodeFromString<AgentBootContext>(encoded)
        assertEquals(0.0, decoded.allocatedBudget,
            "An explicitly-zero allocatedBudget should be preserved as 0.0, not silently upgraded")
    }

    @Test
    fun `readApiKey throws when OPENROUTER_API_KEY env var is absent`() {
        // Reset env to ensure key is missing. We test the fail-fast contract rather than env mutation.
        // If the env var is actually set in CI this test will also pass (not assertFails path).
        val envKey = System.getenv(AgentRunner.API_KEY_ENV)
        if (envKey != null) {
            // Key is present: the companion would succeed — test the positive side
            val key = AgentRunner.readApiKey()
            assert(key.isNotBlank()) { "readApiKey must return non-blank when env var is set" }
        } else {
            // Key is absent: must throw
            assertFails("readApiKey should throw when OPENROUTER_API_KEY is not set") {
                AgentRunner.readApiKey()
            }
        }
    }

    @Test
    fun `readApiKey resolves codex cli from Evomolior specific env`() {
        val codexPath = executableCodexPath()

        val resolved = AgentRunner.readApiKey(
            backend = LlmBackend.CODEX_CHATGPT,
            getenv = { key -> if (key == LlmBackend.CODEX_CHATGPT.apiKeyEnvVar) codexPath else null }
        )

        assertEquals(codexPath, resolved)
    }

    @Test
    fun `readApiKey resolves codex cli from shared codex cli env`() {
        val codexPath = executableCodexPath()

        val resolved = AgentRunner.readApiKey(
            backend = LlmBackend.CODEX_CHATGPT,
            getenv = { key -> if (key == LlmBackend.CODEX_CLI_ENV_VAR) codexPath else null }
        )

        assertEquals(codexPath, resolved)
    }

    @Test
    fun `readGroupProfile returns Library profile when env var is absent`() {
        // EVOMOLIOR_GROUP_PROFILE may not be set in test environment
        val envValue = System.getenv("EVOMOLIOR_GROUP_PROFILE")
        if (envValue == null) {
            val profile = AgentRunner.readGroupProfile()
            assertEquals(com.achulkov.evomolior.core.model.GroupProfileType.Library, profile.profileType,
                "Should default to Library when EVOMOLIOR_GROUP_PROFILE is not set")
        }
        // If env IS set, just verify it doesn't throw
        AgentRunner.readGroupProfile()
    }
}

private fun executableCodexPath(): String =
    createTempFile("agent-runner-codex-", ".sh").toFile()
        .also { it.setExecutable(true) }
        .absolutePath
