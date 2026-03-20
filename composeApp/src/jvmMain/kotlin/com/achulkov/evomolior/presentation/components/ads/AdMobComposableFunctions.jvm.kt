package com.achulkov.evomolior.presentation.components.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun NativeAdmobBanner(modifier: Modifier) {
    println("Ads is not supported on JVM platform")
}

@Composable
actual fun rememberNativeInterstitialAdDisplayer(): FullScreenAdDisplayer {
    return NoImplFullScreenAdDisplayer
}

@Composable
actual fun rememberNativeRewardedAdDisplayer(onRewarded: (AdsRewardItem) -> Unit): FullScreenAdDisplayer {
    return NoImplFullScreenAdDisplayer
}