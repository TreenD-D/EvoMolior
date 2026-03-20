package com.achulkov.evomolior.util

import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.data.source.featureflag.NoImplFeatureFlagManager
import com.achulkov.evomolior.data.source.local.CreditTransactionLocalDataSource
import com.achulkov.evomolior.data.source.local.ExampleLocalDataSource
import com.achulkov.evomolior.data.source.local.impl.InMemoryCreditTransactionLocalDataSource
import com.achulkov.evomolior.data.source.local.impl.InMemoryExampleLocalDataSource
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

private val localDataSourceModule = module {
    // Impl
    singleOf(::InMemoryExampleLocalDataSource) bind ExampleLocalDataSource::class
    singleOf(::InMemoryCreditTransactionLocalDataSource) bind CreditTransactionLocalDataSource::class
}

internal actual val platformModule: Module = module {
    includes(localDataSourceModule)
    factoryOf(::AppUtilImpl) bind AppUtil::class
    single { NoImplFeatureFlagManager } bind FeatureFlagManager::class
    single { NoImplAnalytics } bind Analytics::class
    single { NoImplAdsManager } bind AdsManager::class
}

internal actual fun onApplicationStartPlatformSpecific() {
    NotifierManager.initialize(NotificationPlatformConfiguration.Desktop())
}

internal actual val isAndroid: Boolean get() = false
internal actual val isDebug: Boolean get() = false

actual val defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.Default
