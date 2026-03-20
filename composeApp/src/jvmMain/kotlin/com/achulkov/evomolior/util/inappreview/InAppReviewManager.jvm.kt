package com.achulkov.evomolior.util.inappreview

import androidx.compose.runtime.Composable

@Composable
actual fun rememberInAppReviewManager(): InAppReviewManager {
    return NoImplInAppReviewManager
}