package com.achulkov.evomolior.util

import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.presentation.components.ads.AdsManager
import com.achulkov.evomolior.presentation.components.ads.IosAdsDisplayer
import com.achulkov.evomolior.util.analytics.Analytics

/**
This factory is used to help to use swift libraries in KMP. Actual implementations are provided in swift.
 */
interface SwiftLibDependencyFactory {
    fun provideFeatureFlagManagerImpl(): FeatureFlagManager
    fun provideFirebaseAnalyticsImpl(): Analytics
    fun provideAdsManagerImpl(): AdsManager
    fun provideIosAdsDisplayer(): IosAdsDisplayer
}