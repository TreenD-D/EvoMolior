package com.achulkov.evomolior.util

import com.achulkov.evomolior.api.di.evomoliorApiModule
import com.achulkov.evomolior.api.service.EvoMoliorIpcServer
import com.achulkov.evomolior.competition.di.evomoliorCompetitionModule
import com.achulkov.evomolior.core.di.evomoliorCoreModule
import com.achulkov.evomolior.orchestration.di.evomoliorOrchestrationModule
import com.achulkov.evomolior.sandbox.di.sandboxBindingsModule
import com.achulkov.evomolior.substrate.di.evomoliorSubstrateModule
import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.data.source.featureflag.NoImplFeatureFlagManager
import com.achulkov.evomolior.data.source.local.DatabaseProvider
import com.achulkov.evomolior.data.source.local.DatabaseProviderImpl
import com.achulkov.evomolior.data.source.local.nonWebModule
import com.achulkov.evomolior.presentation.components.ads.AdsManager
import com.achulkov.evomolior.presentation.components.ads.NoImplAdsManager
import com.achulkov.evomolior.util.analytics.Analytics
import com.achulkov.evomolior.util.analytics.NoImplAnalytics
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

internal actual val platformModule: Module = module {
    includes(nonWebModule)
    singleOf(::DatabaseProviderImpl) bind DatabaseProvider::class
    factoryOf(::AppUtilImpl) bind AppUtil::class
    single { NoImplFeatureFlagManager } bind FeatureFlagManager::class
    single { NoImplAnalytics } bind Analytics::class
    single { NoImplAdsManager } bind AdsManager::class

    // EvoMolior engine modules (JVM-only)
    includes(
        evomoliorCoreModule,
        evomoliorSubstrateModule,
        sandboxBindingsModule(),
        evomoliorOrchestrationModule,
        evomoliorCompetitionModule,
        evomoliorApiModule
    )
}

internal actual fun onApplicationStartPlatformSpecific() {
    NotifierManager.initialize(NotificationPlatformConfiguration.Desktop())
    // EvoMolior IPC server is started lazily when first needed — accessed via Koin
}

internal actual val isAndroid: Boolean
    get() = false
internal actual val isDebug: Boolean
    get() = System.getProperty("app.debug") == "true"
        || System.getenv("APP_DEBUG") == "true"

actual val defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.IO
