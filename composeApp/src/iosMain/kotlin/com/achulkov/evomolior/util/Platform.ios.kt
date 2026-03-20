package com.achulkov.evomolior.util

import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.data.source.local.DatabaseProvider
import com.achulkov.evomolior.data.source.local.DatabaseProviderImpl
import com.achulkov.evomolior.data.source.local.nonWebModule
import com.achulkov.evomolior.presentation.components.ads.AdsManager
import com.achulkov.evomolior.presentation.components.ads.IosAdsDisplayer
import com.achulkov.evomolior.util.analytics.Analytics
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.configuration.NotificationPlatformConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.experimental.ExperimentalNativeApi

internal actual val platformModule: Module = module {
    includes(nonWebModule)
    singleOf(::DatabaseProviderImpl) bind DatabaseProvider::class
    factoryOf(::AppUtilImpl) bind AppUtil::class
}

internal fun swiftLibDependenciesModule(factory: SwiftLibDependencyFactory): Module = module {
    single { factory.provideFeatureFlagManagerImpl() } bind FeatureFlagManager::class
    single { factory.provideFirebaseAnalyticsImpl() } bind Analytics::class
    single { factory.provideAdsManagerImpl() } bind AdsManager::class
    single { factory.provideIosAdsDisplayer() } bind IosAdsDisplayer::class
}

internal actual fun onApplicationStartPlatformSpecific() {
    NotifierManager.initialize(NotificationPlatformConfiguration.Ios())

}

internal actual val isAndroid = false

@OptIn(ExperimentalNativeApi::class)
internal actual val isDebug = Platform.isDebugBinary

actual val defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.IO

