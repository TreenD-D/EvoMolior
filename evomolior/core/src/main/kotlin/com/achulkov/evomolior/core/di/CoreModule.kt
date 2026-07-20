package com.achulkov.evomolior.core.di

import org.koin.dsl.module

/**
 * Koin DI module for :engine:core.
 *
 * Note: Core only provides domain models and contracts — there are no stateful singletons.
 * [com.achulkov.evomolior.core.budget.BudgetConfig] and
 * [com.achulkov.evomolior.core.budget.BudgetLedger] are created per-group by the
 * LifecycleManager (not managed by Koin, as they are run-scoped).
 */
val evomoliorCoreModule = module {
    // No singletons needed in core — all types are value objects or run-scoped instances
}
