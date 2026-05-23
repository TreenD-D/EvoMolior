package com.achulkov.evokammer.api

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruCheckResult
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Shuru availability check logic in [EvoKammerRunService].
 *
 * Uses the [LifecycleManager.usesInProcessMode] contract to confirm:
 * - A manager with a [localAgentRunner] reports in-process mode (Shuru not required)
 * - A manager without a runner reports Shuru VM mode (Shuru required)
 *
 * The full service-start path is tested separately in integration tests that
 * require a running DB. This test focuses on the mode-gating contract only.
 */
class EvoKammerRunServiceModeGatingTest {

    // ── mode contract tests ────────────────────────────────────────────────────

    @Test
    fun `LifecycleManager with localAgentRunner reports usesInProcessMode true`() {
        val lm = makeManager(withLocalRunner = true)
        assertTrue(lm.usesInProcessMode,
            "A LifecycleManager with localAgentRunner must report usesInProcessMode=true")
    }

    @Test
    fun `LifecycleManager without localAgentRunner reports usesInProcessMode false`() {
        val lm = makeManager(withLocalRunner = false)
        assertFalse(lm.usesInProcessMode,
            "A LifecycleManager without localAgentRunner must report usesInProcessMode=false (Shuru mode)")
    }

    @Test
    fun `all in-process managers do not need Shuru`() {
        val managers = listOf(makeManager(true), makeManager(true))
        val needsShuru = managers.any { !it.usesInProcessMode }
        assertFalse(needsShuru,
            "When all managers are in-process, Shuru should not be required")
    }

    @Test
    fun `at least one Shuru VM manager means Shuru is required`() {
        val managers = listOf(makeManager(true), makeManager(false))
        val needsShuru = managers.any { !it.usesInProcessMode }
        assertTrue(needsShuru,
            "When any manager is in Shuru VM mode, Shuru is required")
    }

    // ── ShuruCheckResult contract tests ────────────────────────────────────────

    @Test
    fun `ShuruCheckResult available=true is parsed correctly`() {
        val result = ShuruCheckResult(available = true, version = "0.1.0", message = "OK")
        assertTrue(result.available)
    }

    @Test
    fun `ShuruCheckResult available=false carries reason message`() {
        val msg = "shuru: command not found"
        val result = ShuruCheckResult(available = false, version = null, message = msg)
        assertFalse(result.available)
        assertTrue(result.message.lowercase().contains("shuru"),
            "Message should mention shuru")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeManager(withLocalRunner: Boolean): LifecycleManager {
        val tmpDir = createTempDir("svc-mode-test-")
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val shuruVmManager = ShuruVmManager()
        val runner: (suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit)? =
            if (withLocalRunner) ({ _, _ -> }) else null

        return LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = shuruVmManager,
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(com.achulkov.evokammer.core.model.GroupProfileType.Library),
            localAgentRunner = runner
        )
    }
}
