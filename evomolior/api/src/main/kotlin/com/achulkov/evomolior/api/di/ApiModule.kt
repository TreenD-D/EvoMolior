package com.achulkov.evomolior.api.di

import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.EvoMoliorIpcServer
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.api.service.RunArchiveService
import com.achulkov.evomolior.competition.di.GraderGroupManagerProvider
import com.achulkov.evomolior.competition.di.GroupCoordinatorProvider
import com.achulkov.evomolior.orchestration.di.LifecycleManagerProvider
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import org.koin.dsl.module

/**
 * Koin DI module for :engine:api.
 *
 * Provides:
 * - [EvoMoliorEngineService] (bound to [EvoMoliorRunService]) — in-process engine singleton
 * - [EvoMoliorIpcServer] — Ktor WebSocket/HTTP IPC bridge for the Desktop UI
 *
 * Depends on [LifecycleManagerProvider] and [GroupCoordinatorProvider] from the
 * orchestration and competition modules respectively.
 *
 * The IPC server is NOT started automatically — call [EvoMoliorIpcServer.start] in
 * the application startup sequence (see `AppInitializer.jvm.kt` in composeApp).
 */
val evomoliorApiModule = module {
    single<EvoMoliorEngineService> {
        EvoMoliorRunService(
            lifecycleManagerProvider = get<LifecycleManagerProvider>(),
            groupCoordinatorProvider = get<GroupCoordinatorProvider>(),
            graderGroupManagerProvider = get<GraderGroupManagerProvider>(),
            substrateFactory = get<SubstrateDatabaseFactory>(),
            metaPlannerRunner = get<MetaPlannerRunner>()
        )
    }

    single { EvoMoliorIpcServer(engineService = get<EvoMoliorEngineService>()) }

    single { RunArchiveService(dbFactory = get<SubstrateDatabaseFactory>()) }
}
