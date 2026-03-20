plugins {
    id("configure-kmp-library-module")
}

android {
    namespace = "com.measify.kappmaker.auth.firebase"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.libs.auth.authApi)
            implementation(libs.kotlinx.coroutines.core)
        }
        mobileMain.dependencies {
            api(libs.kmpauth.firebase)
        }
    }
}
