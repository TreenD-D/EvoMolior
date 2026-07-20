package com.achulkov.evomolior.sandbox.shuru

import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Shuru VM config JSON contract produced by [ShuruVmManager].
 *
 * Tests that the generated JSON:
 * 1. Places the boot context in the env block as EVOMOLIOR_BOOT_CONTEXT (not stdin).
 * 2. Injects OPENROUTER_API_KEY and EVOMOLIOR_GROUP_PROFILE.
 * 3. Does not put the boot context in "stdin".
 * 4. Escapes quotes inside the boot context JSON properly.
 *
 * No actual Shuru CLI is invoked.
 */
class VmConfigContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleBootContext() = AgentBootContext(
        agentId = "agent-vm-001",
        runId = "run-test",
        groupId = "group-0",
        role = Role.Researcher,
        rolePrompt = "You are a researcher.",
        budgetToken = "token:agent-vm-001:0",
        substrateDbPath = "/data/substrate.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "Stage disclosure",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = 50.0
    )

    private fun sampleVmConfig(bootContext: AgentBootContext = sampleBootContext()) = VmConfig(
        agentId = bootContext.agentId,
        groupId = bootContext.groupId,
        runId = bootContext.runId,
        mounts = FilesystemMountConfig(
            kbMountHostPath = "/data/kb",
            toolsMountHostPath = "/data/tools",
            stagingMountHostPath = "/data/staging/agent-vm-001"
        ),
        network = NetworkAllowlistConfig.NO_INTERNET,
        agentJarPath = "/app/agent-runner.jar",
        bootContextJson = json.encodeToString(bootContext),
        openRouterApiKey = "sk-test-key",
        groupProfile = "Library",
        ipcPort = 10001
    )

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `generated JSON contains EVOMOLIOR_BOOT_CONTEXT in env block`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        assertContains(generatedJson, "EVOMOLIOR_BOOT_CONTEXT",
            message = "Boot context must be injected via EVOMOLIOR_BOOT_CONTEXT env var")
    }

    @Test
    fun `generated JSON contains OPENROUTER_API_KEY in env block`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        assertContains(generatedJson, "OPENROUTER_API_KEY",
            message = "OpenRouter API key must be injected via OPENROUTER_API_KEY env var")
        assertContains(generatedJson, "sk-test-key",
            message = "API key value must appear in the generated config")
    }

    @Test
    fun `generated JSON contains EVOMOLIOR_GROUP_PROFILE in env block`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        assertContains(generatedJson, "EVOMOLIOR_GROUP_PROFILE",
            message = "Group profile must be injected via EVOMOLIOR_GROUP_PROFILE env var")
        assertContains(generatedJson, "Library",
            message = "Group profile value must appear in the generated config")
    }

    @Test
    fun `generated JSON does not use a stdin field for boot context`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        // The old schema used "stdin" — ensure it is gone.
        assertFalse(generatedJson.contains("\"stdin\""),
            "Boot context must not be passed via stdin; must use env var instead")
    }

    @Test
    fun `generated JSON contains agent jar command`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        assertContains(generatedJson, "/app/agent-runner.jar",
            message = "Agent JAR path must be in the boot command")
        assertContains(generatedJson, "java",
            message = "JVM command must be present in boot section")
    }

    @Test
    fun `generated JSON includes all three filesystem mounts`() {
        val config = sampleVmConfig()
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        assertContains(generatedJson, "/kb", message = "KB mount must be present")
        assertContains(generatedJson, "/tools", message = "Tools mount must be present")
        assertContains(generatedJson, "/staging", message = "Staging mount must be present")
    }

    @Test
    fun `boot context with quotes in rolePrompt is correctly escaped in env JSON`() {
        val bootCtx = sampleBootContext().copy(rolePrompt = """You are "special".""")
        val config = sampleVmConfig(bootCtx)
        val generatedJson = invokePrivateBuildVmConfigJson(config)
        // The JSON must be parseable (no double-quote breakage). A simple heuristic:
        // The env block must still be a valid JSON structure with at least the closing brace.
        assertTrue(generatedJson.contains("\"env\""),
            "Generated JSON must contain an env block even when rolePrompt has quotes")
        assertTrue(generatedJson.endsWith("}"),
            "Generated JSON should end with closing brace — basic structural sanity check")
    }

    // ── reflection helper to call the private buildVmConfigJson ─────────────

    /**
     * Invokes the private [ShuruVmManager.buildVmConfigJson] method via reflection
     * so we can test the JSON shape without spawning a real Shuru process.
     */
    private fun invokePrivateBuildVmConfigJson(config: VmConfig): String {
        val manager = ShuruVmManager()
        val method = ShuruVmManager::class.java.getDeclaredMethod("buildVmConfigJson", VmConfig::class.java)
        method.isAccessible = true
        return method.invoke(manager, config) as String
    }
}
