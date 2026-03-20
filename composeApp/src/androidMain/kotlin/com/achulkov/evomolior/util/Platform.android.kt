package com.achulkov.evomolior.util

import com.google.firebase.analytics.analytics
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.achulkov.evomolior.BuildConfig
import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManagerImpl
import com.achulkov.evomolior.data.source.local.DatabaseProvider
import com.achulkov.evomolior.data.source.local.DatabaseProviderImpl
import com.achulkov.evomolior.data.source.local.nonWebModule
import com.achulkov.evomolior.presentation.components.ads.AdsManager
import com.achulkov.evomolior.presentation.components.ads.AdsManagerImpl
import com.achulkov.evomolior.util.analytics.Analytics
import com.achulkov.evomolior.util.analytics.FirebaseAnalyticsImpl
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
    single<FeatureFlagManagerImpl> {
        val remoteConfig = Firebase.remoteConfig.apply {
            setConfigSettingsAsync(remoteConfigSettings {
                // set minimumFetchIntervalInSeconds to 0 to get fresh updates in debug mode for testing
                if (BuildConfig.DEBUG) minimumFetchIntervalInSeconds = 3600
            })
            setDefaultsAsync(FeatureFlagManager.DEFAULT_VALUES)
        }
        FeatureFlagManagerImpl(remoteConfig = remoteConfig)
    } bind FeatureFlagManager::class
    single { FirebaseAnalyticsImpl(firebaseAnalytics = Firebase.analytics) } bind Analytics::class
    singleOf(::AdsManagerImpl) bind AdsManager::class
}

internal actual fun onApplicationStartPlatformSpecific() {
    NotifierManager.initialize(
        configuration = NotificationPlatformConfiguration.Android(
            notificationIconResId = android.R.drawable.ic_menu_compass
        )
    )

}

internal actual val isAndroid = true
internal actual val isDebug = BuildConfig.DEBUG
actual val defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.IO