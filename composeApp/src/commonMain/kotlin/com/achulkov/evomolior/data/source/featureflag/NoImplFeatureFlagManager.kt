package com.achulkov.evomolior.data.source.featureflag

object NoImplFeatureFlagManager : FeatureFlagManager {

    override fun syncsFlagsAsync() {
        println("Implementation is not available in this platform")
    }

    override fun getBoolean(key: String): Boolean {
        return FeatureFlagManager.DEFAULT_VALUES[key] as? Boolean ?: false
    }

    override fun getString(key: String): String {
        return FeatureFlagManager.DEFAULT_VALUES[key] as? String ?: ""
    }

    override fun getLong(key: String): Long {
        return FeatureFlagManager.DEFAULT_VALUES[key] as? Long ?: 0L
    }

    override fun getDouble(key: String): Double {
        return FeatureFlagManager.DEFAULT_VALUES[key] as? Double ?: 0.0
    }
}