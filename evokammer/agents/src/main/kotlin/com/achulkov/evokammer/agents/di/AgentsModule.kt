package com.achulkov.evokammer.agents.di

import com.achulkov.evokammer.agents.factory.EvoKammerAgentFactory
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import org.koin.dsl.module

/**
 * Koin DI module for :evokammer:agents.
 *
 * Provides:
 * - [RolePromptBuilder] — assembles role-specific system prompts
 * - [EvoKammerAgentFactory] factory lambda — creates per-(run, group) agent factory instances.
 *   Consumers inject the factory lambda and call it with run-scoped dependencies.
 *
 * Note: [EvoKammerAgentFactory] is NOT a singleton — it is scoped per run/group.
 * The factory lambda is provided here so the LifecycleManager can create instances on demand.
 */
val evokammerAgentsModule = module {
    // RolePromptBuilder is an object (singleton) — no Koin binding needed, imported directly

    /**
     * Factory lambda that creates an [EvoKammerAgentFactory] for a specific (run, group).
     *
     * Usage:
     * ```kotlin
     * val makeFactory: EvoKammerAgentFactoryProvider = get()
     * val factory = makeFactory(apiKey, db, toolSubSandbox, budgetConfig)
     * ```
     */
    single<EvoKammerAgentFactoryProvider> {
        { apiKey, db, toolSubSandbox, budgetConfig ->
            EvoKammerAgentFactory(
                openRouterApiKey = apiKey,
                db = db,
                toolSubSandbox = toolSubSandbox,
                budgetConfig = budgetConfig
            )
        }
    }
}

/** Type alias for the agent factory provider lambda. */
typealias EvoKammerAgentFactoryProvider = (
    apiKey: String,
    db: com.achulkov.evokammer.substrate.db.EvoKammerDatabase,
    toolSubSandbox: ToolSubSandbox,
    budgetConfig: com.achulkov.evokammer.core.budget.BudgetConfig
) -> EvoKammerAgentFactory
