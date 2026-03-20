package com.achulkov.evokammer.api.di

import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.EvoKammerIpcServer
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.api.service.RunArchiveService
import com.achulkov.evokammer.competition.di.GraderGroupManagerProvider
import com.achulkov.evokammer.competition.di.GroupCoordinatorProvider
import com.achulkov.evokammer.orchestration.di.LifecycleManagerProvider
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import org.koin.dsl.module

/**
 * Koin DI module for :evokammer:api.
 *
 * Provides:
 * - [EvoKammerEngineService] (bound to [EvoKammerRunService]) — in-process engine singleton
 * - [EvoKammerIpcServer] — Ktor WebSocket/HTTP IPC bridge for the Desktop UI
 *
 * Depends on [LifecycleManagerProvider] and [GroupCoordinatorProvider] from the
 * orchestration and competition modules respectively.
 *
 * The IPC server is NOT started automatically — call [EvoKammerIpcServer.start] in
 * the application startup sequence (see `AppInitializer.jvm.kt` in composeApp).
 */
val evokammerApiModule = module {
    single<EvoKammerEngineService> {
        EvoKammerRunService(
            lifecycleManagerProvider = get<LifecycleManagerProvider>(),
            groupCoordinatorProvider = get<GroupCoordinatorProvider>(),
            graderGroupManagerProvider = get<GraderGroupManagerProvider>(),
            substrateFactory = get<SubstrateDatabaseFactory>()
        )
    }

    single { EvoKammerIpcServer(engineService = get<EvoKammerEngineService>()) }

    single { RunArchiveService(dbFactory = get<SubstrateDatabaseFactory>()) }
}
