package com.achulkov.evomolior.competition.di

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.coordinator.MultiGroupCoordinator
import com.achulkov.evomolior.competition.coordinator.RingTopology
import com.achulkov.evomolior.competition.disclosure.CheckInScheduleEntry
import com.achulkov.evomolior.competition.disclosure.DisclosureStage
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.disclosure.DisclosureStageAuthority
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.ScoringEngine
import com.achulkov.evomolior.core.model.ScoringDimension
import org.koin.dsl.module

/**
 * Koin DI module for :engine:competition.
 *
 * Most competition objects are run-scoped (created once per run by the engine service) and are NOT
 * Koin singletons — they require run-specific parameters only available after Genesis Council output.
 *
 * This module exposes factory lambdas (type-aliased providers) for each major subsystem.
 */
val evomoliorCompetitionModule = module {

    /**
     * Factory for [GroupCoordinator] — created at run-start with group IDs and scoring dimensions.
     */
    single<GroupCoordinatorProvider> {
        { groupIds, dimensions ->
            GroupCoordinator(
                groupIds = groupIds,
                dimensions = dimensions,
                leakConfig = LeakOracleConfig()
            )
        }
    }
    /** Factory for plan-aligned [MultiGroupCoordinator]. */
    single<MultiGroupCoordinatorProvider> {
        { groupIds, dimensions ->
            MultiGroupCoordinator(
                topology = RingTopology(groupIds),
                dimensions = dimensions,
                leakConfig = LeakOracleConfig()
            )
        }
    }

    /**
     * Factory for [ScoringEngine] — wraps [MetaJudge] + [BlindSubmissionGate].
     * Created per run; `groupIds` must match those used for [GroupCoordinator].
     */
    single<ScoringEngineProvider> {
        { groupIds ->
            val judge = MetaJudge()
            val gate = BlindSubmissionGate(groupIds = groupIds)
            ScoringEngine(metaJudge = judge, blindGate = gate)
        }
    }
    /**
     * Factory for [GraderGroupManager] — one manager per run, containing many grader groups.
     */
    single<GraderGroupManagerProvider> {
        { dimensions ->
            GraderGroupManager(dimensions = dimensions, metaJudge = MetaJudge())
        }
    }

    /**
     * Factory for [ProgressiveDisclosureEngine] — created per run from the run's stage list.
     */
    single<DisclosureEngineProvider> {
        { stages, authority ->
            ProgressiveDisclosureEngine(stages = stages, stageAuthority = authority)
        }
    }
}

// ── Type aliases for provider lambdas ──────────────────────────────────────────

/** Creates a [GroupCoordinator] from run-specific params. */
typealias GroupCoordinatorProvider = (
    groupIds: List<String>,
    dimensions: List<ScoringDimension>
) -> GroupCoordinator
/** Creates a [MultiGroupCoordinator] from run-specific params. */
typealias MultiGroupCoordinatorProvider = (
    groupIds: List<String>,
    dimensions: List<ScoringDimension>
) -> MultiGroupCoordinator

/** Creates a [ScoringEngine] for a specific set of group IDs. */
typealias ScoringEngineProvider = (
    groupIds: List<String>
) -> ScoringEngine
/** Creates a [GraderGroupManager] for run-scoped grading orchestration. */
typealias GraderGroupManagerProvider = (
    dimensions: List<ScoringDimension>
) -> GraderGroupManager

/** Creates a [ProgressiveDisclosureEngine] from the run's stage list. */
typealias DisclosureEngineProvider = (
    stages: List<DisclosureStage>,
    authority: DisclosureStageAuthority
) -> ProgressiveDisclosureEngine

/** Alias for a list of [CheckInScheduleEntry]s describing the check-in curriculum. */
typealias CheckInSchedule = List<CheckInScheduleEntry>
