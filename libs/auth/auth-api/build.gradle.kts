plugins {
    id("configure-kmp-library-module")
}

android {
    namespace = "com.measify.kappmaker.auth.api"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
