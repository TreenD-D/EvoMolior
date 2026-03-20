@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import com.android.build.gradle.BaseExtension
import extensions.isMultiplatformModule
import extensions.kotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

pluginManager.apply("org.jetbrains.kotlin.multiplatform")
pluginManager.apply("com.android.library")

plugins {

}

apply(from = rootProject.file("gradle/scripts/refactorPackage.gradle.kts"))


kotlinMultiplatform {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate {
        common {
            group("mobile") {
                group("ios")
                withAndroidTarget()
            }

            group("nonMobile") {
                withJs()
                withWasmJs()
                withJvm()
            }
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()
    wasmJs {
        browser()
    }
    js(IR) {
        nodejs()
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies { }
    }
}

configure<BaseExtension> {
    compileSdkVersion(36)
    defaultConfig {
        minSdk = 21
        val proguardFilename = "consumer-rules.pro"
        if (layout.projectDirectory.file(proguardFilename).asFile.exists()) {
            consumerProguardFile(proguardFilename)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all(Test::useJUnitPlatform)
    }

    if (isMultiplatformModule()) {
        sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
}