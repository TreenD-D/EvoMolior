package com.achulkov.evokammer.substrate.di

import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import org.koin.dsl.module
import java.io.File

/**
 * Koin DI module for :evokammer:substrate.
 *
 * Provides:
 * - [SubstrateDatabaseFactory] — creates per-(runId, groupId) Room DB instances.
 *   The data root directory defaults to `{user.home}/.evokammer/data`.
 *
 * Consumers should override the `dataRootDir` binding if a custom path is needed.
 */
val evokammerSubstrateModule = module {
    single {
        val dataDir = getKoinOptional<File>("dataRootDir")
            ?: File(System.getProperty("user.home"), ".evokammer/data")
        SubstrateDatabaseFactory(dataDir)
    }
}

/** Helper to retrieve an optional Koin qualifier-named instance without throwing. */
private inline fun <reified T> org.koin.core.scope.Scope.getKoinOptional(qualifier: String): T? {
    return try {
        get(org.koin.core.qualifier.named(qualifier))
    } catch (_: Exception) {
        null
    }
}
