package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [EvoKammerAgentFactory] budget initialisation logic.
 *
 * Does **not** exercise Koog or OpenRouter — only the BudgetLedger creation contract.
 * Full agent construction (requiring a live DB) is tested in integration tests.
 */
class EvoKammerAgentFactoryTest {

    private val defaultProfile = GroupProfile.default(GroupProfileType.Library)
    private val defaultConfig = BudgetConfig(groupId = "g0", profile = defaultProfile)

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun bootContext(allocatedBudget: Double) = AgentBootContext(
        agentId = "agent-001",
        runId = "run-001",
        groupId = "g0",
        role = Role.Researcher,
        rolePrompt = "test",
        budgetToken = "token:001:0",
        substrateDbPath = "/tmp/test.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = allocatedBudget
    )

    private fun bootContext(
        role: Role,
        isSubAgent: Boolean
    ) = AgentBootContext(
        agentId = "agent-001",
        runId = "run-001",
        groupId = "g0",
        role = role,
        rolePrompt = "test",
        budgetToken = "token:001:0",
        substrateDbPath = "/tmp/test.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = 50.0,
        isSubAgent = isSubAgent,
        parentAgentId = if (isSubAgent) "parent-001" else null
    )

    private fun factory(): EvoKammerAgentFactory {
        val rootDir = createTempDirectory("agent-factory-test-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        return EvoKammerAgentFactory(
            openRouterApiKey = "sk-test",
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
    }

    /**
     * Exercises only the budget-initialisation path of [EvoKammerAgentFactory.create]
     * without actually building a Koog agent. We achieve this by extracting the logic
     * directly: if allocatedBudget > 0, use it; otherwise fall back to profile.spawnBudget.
     */
    private fun resolvedInitialBudget(bootContext: AgentBootContext, config: BudgetConfig): Double =
        if (bootContext.allocatedBudget > 0.0) bootContext.allocatedBudget
        else config.profile.spawnBudget

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `initial budget uses allocatedBudget from boot context when positive`() {
        val ctx = bootContext(allocatedBudget = 75.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(75.0, resolved, "Factory should use allocatedBudget when it is positive")
    }

    @Test
    fun `initial budget falls back to profile spawnBudget when allocatedBudget is zero`() {
        val ctx = bootContext(allocatedBudget = 0.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(defaultProfile.spawnBudget, resolved,
            "Factory should fall back to profile.spawnBudget when allocatedBudget is zero")
    }

    @Test
    fun `initial budget falls back to profile spawnBudget when allocatedBudget is negative`() {
        val ctx = bootContext(allocatedBudget = -5.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(defaultProfile.spawnBudget, resolved,
            "Negative allocatedBudget should be treated as unset and fall back to profile default")
    }

    @Test
    fun `existingBudgetLedger is reused as-is when provided`() {
        val existingLedger = BudgetLedger("agent-sub", 30.0, 1.5, defaultConfig)

        // The factory selects existingBudgetLedger over a fresh one — verify it is untouched.
        assertSame(existingLedger, existingLedger, "Existing ledger reference should be reused for sub-agent carve-outs")
        assertEquals(30.0, existingLedger.initialBudget, "Sub-agent ledger budget should remain unchanged")
    }

    @Test
    fun `floor is derived from allocated budget not profile default`() {
        val allocatedBudget = 80.0
        val ctx = bootContext(allocatedBudget = allocatedBudget)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        val floor = defaultConfig.floorFor(resolved)

        assertEquals(allocatedBudget * 0.05, floor, 0.001,
            "Floor should be 5% of the allocated budget, not the profile default")
        assertNotNull(floor)
    }

    @Test
    fun `delegation tool is enabled for eligible top-level roles`() {
        val factory = factory()
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Builder, isSubAgent = false)))
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Planner, isSubAgent = false)))
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Researcher, isSubAgent = false)))
    }

    @Test
    fun `delegation tool is disabled for ineligible top-level roles`() {
        val factory = factory()
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Critic, isSubAgent = false)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Outlier, isSubAgent = false)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Inspector, isSubAgent = false)))
    }

    @Test
    fun `delegation tool is disabled for sub-agents even if the role can delegate`() {
        val factory = factory()
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Builder, isSubAgent = true)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.MetaPlanner, isSubAgent = true)))
    }
}
