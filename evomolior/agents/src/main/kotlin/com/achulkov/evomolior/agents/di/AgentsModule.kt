package com.achulkov.evomolior.agents.di

import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.sandbox.network.AllowlistProxy
import org.koin.dsl.module

/**
 * Koin DI module for :engine:agents.
 *
 * Provides:
 * - [RolePromptBuilder] — assembles role-specific system prompts
 * - [EvoMoliorAgentFactory] factory lambda — creates per-(run, group) agent factory instances.
 *   Consumers inject the factory lambda and call it with run-scoped dependencies.
 *
 * Note: [EvoMoliorAgentFactory] is NOT a singleton — it is scoped per run/group.
 * The factory lambda is provided here so the LifecycleManager can create instances on demand.
 */
val evomoliorAgentsModule = module {
    // RolePromptBuilder is an object (singleton) — no Koin binding needed, imported directly

    /**
     * Factory lambda that creates an [EvoMoliorAgentFactory] for a specific (run, group).
     *
     * Usage:
     * ```kotlin
     * val makeFactory: EvoMoliorAgentFactoryProvider = get()
     * val factory = makeFactory(apiKey, db, toolSubSandbox, budgetConfig)
     * ```
     */
    single<EvoMoliorAgentFactoryProvider> {
        { apiKey, db, toolSubSandbox, budgetConfig ->
            EvoMoliorAgentFactory(
                openRouterApiKey = apiKey,
                db = db,
                toolSubSandbox = toolSubSandbox,
                budgetConfig = budgetConfig,
                constitutionalEnforcer = get<ConstitutionalEnforcer>(),
                allowlistProxy = get<AllowlistProxy>()
            )
        }
    }
}

/** Type alias for the agent factory provider lambda. */
typealias EvoMoliorAgentFactoryProvider = (
    apiKey: String,
    db: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
    toolSubSandbox: ToolSubSandbox,
    budgetConfig: com.achulkov.evomolior.core.budget.BudgetConfig
) -> EvoMoliorAgentFactory
