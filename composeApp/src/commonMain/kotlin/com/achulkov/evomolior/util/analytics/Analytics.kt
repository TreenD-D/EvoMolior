package com.achulkov.evomolior.util.analytics

interface Analytics {
    fun logEvent(event: String, params: Map<String, Any>? = emptyMap())
    fun setEnabled(enabled: Boolean = true)

    companion object {
        const val EVENT_SCREEN_VIEW = "screen_view"
        const val PARAM_SCREEN_NAME = "screen_name"
        const val EVENT_PAYWALL_DISMISSED = "paywall_dismissed"
        const val PARAM_NB_PAYWALL_DISMISSED = "nb_paywall_dismissed"


    }
}

fun Analytics.logScreenView(screenName: String, params: Map<String, Any>? = emptyMap()) {
    logEvent(
        event = Analytics.EVENT_SCREEN_VIEW,
        params = mapOf(Analytics.PARAM_SCREEN_NAME to screenName) + (params ?: emptyMap())
    )
}