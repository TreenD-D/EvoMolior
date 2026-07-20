package com.achulkov.evomolior.orchestration.di

import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.orchestration.epigenetic.CrossoverEngine
import com.achulkov.evomolior.orchestration.epigenetic.EpigeneticManager
import com.achulkov.evomolior.orchestration.epigenetic.FitnessScorer
import com.achulkov.evomolior.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evomolior.orchestration.inspector.InspectorRunner
import com.achulkov.evomolior.orchestration.kg.KGConsistencyChecker
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.selector.EvolutionarySelector
import com.achulkov.evomolior.orchestration.watchdog.Watchdog
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import org.koin.dsl.module

/**
 * Koin DI module for :engine:orchestration.
 *
 * Provides:
 * - [Watchdog] — monitors agents for stasis and budget overruns (singleton)
 * - [EpigeneticManager] — applies transient per-group constraints (singleton)
 * - [MetaPlannerRunner] — triggers MetaPlanner agents on planning stasis (singleton)
 * - [InspectorRunner] — runs passive inspector loops per group (singleton)
 * - [EvolutionarySelectorProvider] — factory lambda for creating run-scoped [EvolutionarySelector]s
 * - [LifecycleManagerProvider] — factory lambda for creating run-scoped [LifecycleManager]s
 *
 * Note: [EvolutionarySelector] and [LifecycleManager] are run+group scoped and require
 * runtime parameters (runId, groupId, profile). They are provided via factory lambdas
 * rather than Koin singletons.
 */
val evomoliorOrchestrationModule = module {
    single { Watchdog() }
    single { EpigeneticManager() }
    single { MetaPlannerRunner() }
    single { InspectorRunner(watchdog = get()) }

    // Phase 2: Epigenetic Inheritance Engine components
    single { CrossoverEngine() }

    /**
     * Factory lambda for [FitnessScorer] (run+group scoped — needs DB DAOs).
     *
     * Usage: `val factory: FitnessScorerProvider = get(); val scorer = factory(runId, groupId)`
     */
    single<FitnessScorerProvider> {
        val dbFactory: SubstrateDatabaseFactory = get()
        val provider: FitnessScorerProvider = { runId, groupId ->
            val db = dbFactory.get(runId, groupId)
            FitnessScorer(
                eventLogDao = db.eventLogDao(),
                knowledgeBaseDao = db.knowledgeBaseDao(),
                stagingDao = db.stagingDao(),
                toolRegistryDao = db.toolRegistryDao(),
                toolInfrastructureRepository =
                    com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository(db)
            )
        }
        provider
    }

    /**
     * Factory lambda for [LegacyProtocolExtractor] (run+group scoped — needs DB DAOs).
     */
    single<LegacyProtocolExtractorProvider> {
        val dbFactory: SubstrateDatabaseFactory = get()
        val provider: LegacyProtocolExtractorProvider = { runId, groupId ->
            val db = dbFactory.get(runId, groupId)
            LegacyProtocolExtractor(
                legacyProtocolDao = db.legacyProtocolDao(),
                stagingDao = db.stagingDao(),
                eventLogDao = db.eventLogDao()
            )
        }
        provider
    }

    /**
     * Factory lambda for [KGConsistencyChecker] (Phase 3 — run+group scoped).
     */
    single<KGConsistencyCheckerProvider> {
        val dbFactory: SubstrateDatabaseFactory = get()
        val provider: KGConsistencyCheckerProvider = { runId, groupId ->
            val db = dbFactory.get(runId, groupId)
            KGConsistencyChecker(knowledgeGraphFactDao = db.knowledgeGraphFactDao())
        }
        provider
    }

    /**
     * Factory lambda that creates an [EvolutionarySelector] for a specific (runId, groupId, profile).
     *
     * Usage:
     * ```kotlin
     * val factory: EvolutionarySelectorProvider = get()
     * val selector = factory(runId, groupId, profile)
     * ```
     */
    single<EvolutionarySelectorProvider> {
        val dbFactory: SubstrateDatabaseFactory = get()
        val provider: EvolutionarySelectorProvider = { runId, groupId, profile ->
            EvolutionarySelector(
                db = dbFactory.get(runId, groupId),
                profile = profile,
                runId = runId,
                groupId = groupId
            )
        }
        provider
    }

    /**
     * Factory lambda that creates a [LifecycleManager] for a specific (runId, groupId, profile).
     *
     * This Koin-provided factory runs agents in **Shuru VM mode** (no in-process runner).
     * For the desktop JVM target, [LifecycleManager] is created directly in `main.kt`
     * with a `localAgentRunner` that uses [com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory].
     *
     * Usage:
     * ```kotlin
     * val factory: LifecycleManagerProvider = get()
     * val lm = factory(runId, groupId, openRouterApiKey, agentJarPath, groupProfile)
     * ```
     */
    single<LifecycleManagerProvider> {
        val shuruManager: ShuruVmManager = get()
        val dbFactory: SubstrateDatabaseFactory = get()
        val provider: LifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath, _ ->
            // `groupProfile` is accepted but unused in Shuru VM mode — the profile is
            // propagated to agents via their boot context's budgetToken / allocation.
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = shuruManager,
                substrateDatabaseFactory = dbFactory,
                agentJarPath = agentJarPath,
                openRouterApiKey = openRouterApiKey
                // localAgentRunner = null → Shuru VM mode
            )
        }
        provider
    }
}

/**
 * Type alias for the [EvolutionarySelector] provider lambda.
 */
typealias EvolutionarySelectorProvider = (
    runId: String,
    groupId: String,
    profile: GroupProfile
) -> EvolutionarySelector

/**
 * Type alias for the [LifecycleManager] provider lambda.
 *
 * Parameters: `(runId, groupId, openRouterApiKey, agentJarPath, groupProfile) -> LifecycleManager`
 * The `groupProfile` carries budget and cost multiplier configuration for the group.
 */
typealias LifecycleManagerProvider = (
    runId: String,
    groupId: String,
    openRouterApiKey: String,
    agentJarPath: String,
    groupProfile: GroupProfile
) -> LifecycleManager

/** Type alias for the [FitnessScorer] provider lambda. */
typealias FitnessScorerProvider = (runId: String, groupId: String) -> FitnessScorer

/** Type alias for the [LegacyProtocolExtractor] provider lambda. */
typealias LegacyProtocolExtractorProvider = (runId: String, groupId: String) -> LegacyProtocolExtractor

/** Type alias for the [KGConsistencyChecker] provider lambda. */
typealias KGConsistencyCheckerProvider = (runId: String, groupId: String) -> KGConsistencyChecker
