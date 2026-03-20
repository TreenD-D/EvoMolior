plugins {
    id("configure-kmp-library-module")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

android {
    namespace = "com.measify.kappmaker.subscription.api"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
