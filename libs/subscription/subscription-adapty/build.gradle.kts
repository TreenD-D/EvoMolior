plugins {
    id("configure-kmp-library-module")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

android {
    namespace = "com.measify.kappmaker.subscription.adapty"
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
            api(libs.adapty)
            api(libs.adapty.ui)
        }
    }
}
