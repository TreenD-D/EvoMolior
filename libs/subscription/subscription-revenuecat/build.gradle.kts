plugins {
    id("configure-kmp-library-module")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

android {
    namespace = "com.measify.kappmaker.subscription.revenuecat"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.libs.subscription.subscriptionApi)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
        }
        mobileMain.dependencies {
            api(libs.revenuecat.core)
            api(libs.revenuecat.ui)
        }
    }
}
