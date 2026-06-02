package com.achulkov.evomolior.util

import com.achulkov.evokammer.api.di.evokammerApiModule
import com.achulkov.evokammer.api.service.EvoKammerIpcServer
import com.achulkov.evokammer.competition.di.evokammerCompetitionModule
import com.achulkov.evokammer.core.di.evokammerCoreModule
import com.achulkov.evokammer.orchestration.di.evokammerOrchestrationModule
import com.achulkov.evokammer.sandbox.di.sandboxBindingsModule
import com.achulkov.evokammer.substrate.di.evokammerSubstrateModule
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

    // EvoKammer engine modules (JVM-only)
    includes(
        evokammerCoreModule,
        evokammerSubstrateModule,
        sandboxBindingsModule(),
        evokammerOrchestrationModule,
        evokammerCompetitionModule,
        evokammerApiModule
    )
}

internal actual fun onApplicationStartPlatformSpecific() {
    NotifierManager.initialize(NotificationPlatformConfiguration.Desktop())
    // EvoKammer IPC server is started lazily when first needed — accessed via Koin
}

internal actual val isAndroid: Boolean
    get() = false
internal actual val isDebug: Boolean
    get() = System.getProperty("app.debug") == "true"
        || System.getenv("APP_DEBUG") == "true"

actual val defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.IO
