@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import com.github.gmazzo.buildconfig.BuildConfigExtension
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.util.Properties

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

//Custom scripts
apply(from = rootProject.file("gradle/scripts/refactorPackage.gradle.kts"))
apply(from = rootProject.file("gradle/scripts/generateNewScreen.gradle.kts"))


kotlin {
    task("testClasses")
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

            group("nonWeb") {
                group("ios")
                withAndroidTarget()
                withJvm()
            }
        }
    }

    androidTarget {
        //https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(libs.kmpnotifier)
        }
    }

    jvm()

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static(project.projectDir.path)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.designsystem)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material.icons)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.koin.core)
            api(libs.kmpnotifier)
            implementation(libs.kmpauth.google)
            implementation(libs.napier)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            implementation(libs.lifecyle.runtime)
            implementation(libs.uuid)
            implementation(libs.multiplatformSettings.noargs)
            implementation(libs.coil.compose)
            implementation(libs.coil.ktor)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.navigation.compose)
            implementation(projects.libs.auth.authFirebase)
            implementation(libs.koog)

            val subscriptionProvider =
                project.findProperty("SUBSCRIPTION_PROVIDER")?.toString()?.uppercase()
                    ?: "REVENUECAT"
            when (subscriptionProvider) {
                "REVENUECAT" -> implementation(projects.libs.subscription.subscriptionRevenuecat)
                "ADAPTY" -> implementation(projects.libs.subscription.subscriptionAdapty)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation(libs.androidx.activityCompose)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
            implementation(libs.firebase.config)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.koin.android)
            implementation(libs.google.admob)
            implementation(libs.android.inappreview)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            // EvoMolior control panel — engine IPC bridge + events
            implementation(project(":engine:api"))
            implementation(project(":engine:core"))
            // EvoMolior engine modules — wired in-process for the JVM desktop target
            implementation(project(":engine:substrate"))
            implementation(project(":engine:agents"))
            implementation(project(":engine:orchestration"))
            implementation(project(":engine:competition"))
            implementation(project(":engine:sandbox"))
        }

        val nonWebMain by getting {
            dependencies {
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.achulkov.evomolior.generated.resources"
}

android {
    namespace = "com.achulkov.evomolior"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36

        applicationId = "com.achulkov.evomolior"
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile =
        rootProject.file("distribution/android/keystore/keystore.properties")
    val isSigningKeyExists = keystorePropertiesFile.exists()
    val keystoreProperties = Properties()
    if (isSigningKeyExists) keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }

    signingConfigs {
        if (isSigningKeyExists)
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["keystorePassword"] as String?
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
            }
    }

    buildTypes {
        val debug by getting {
            isMinifyEnabled = false
            isDebuggable = true

            // This values is provided by Google to test ads in debug mode
            resValue("string", "admobAppId", "ca-app-pub-3940256099942544~3347511713")
        }

        val release by getting {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (isSigningKeyExists) "release" else "debug")

            resValue(
                "string",
                "admobAppId",
                getRequiredProperty(
                    "ADMOB_APP_ID_ANDROID",
                    "ca-app-pub-3940256099942544~3347511713"
                )
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }

}

//https://developer.android.com/develop/ui/compose/testing#setup
dependencies {
    androidTestImplementation(libs.androidx.uitest.junit4)
    debugImplementation(libs.androidx.uitest.testManifest)
    //temporary fix: https://youtrack.jetbrains.com/issue/CMP-5864
    androidTestImplementation("androidx.test:monitor") {
        version { strictly("1.6.1") }
    }

    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "com.achulkov.evomolior.MainKt"
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            // Corretto 17.0.7 on macOS has been crashing in HotSpot compiler threads for this app,
            // so we prefer the slower interpreted mode over a JIT-backed launcher until the runtime is upgraded.
            jvmArgs += "-Xint"
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.achulkov.evomolior"
            packageVersion = "1.0.0"
        }
    }
}

buildConfig {
    // BuildConfig configuration here.
    // https://github.com/gmazzo/gradle-buildconfig-plugin#usage-in-kts
    packageName("com.achulkov.evomolior.common")
    buildConfigField(
        "GOOGLE_WEB_CLIENT_ID",
        getRequiredProperty(key = "GOOGLE_WEB_CLIENT_ID", defaultValue = "testValue")
    )

    // Adapty or RevenueCat Api key
    buildConfigField(
        "SUBSCRIPTION_PROVIDER_ANDROID_API_KEY",
        getRequiredProperty(
            key = "SUBSCRIPTION_PROVIDER_ANDROID_API_KEY",
            defaultValue = "testValue"
        )
    )
    buildConfigField(
        "SUBSCRIPTION_PROVIDER_IOS_API_KEY",
        getRequiredProperty(key = "SUBSCRIPTION_PROVIDER_IOS_API_KEY", defaultValue = "testValue")
    )

    setupAdmobAdsIds()

    // OpenRouter API key — injected from local.properties at build time; empty string if absent
    buildConfigField(
        "OPENROUTER_API_KEY",
        getRequiredProperty(key = "OPENROUTER_API_KEY", defaultValue = "")
    )
}

private fun BuildConfigExtension.setupAdmobAdsIds() {
    //Android Admob ids
    buildConfigField(
        name = "ADMOB_APP_ID_ANDROID",
        value = getRequiredProperty(key = "ADMOB_APP_ID_ANDROID", defaultValue = "")
    )
    buildConfigField(
        name = "ADMOB_BANNER_AD_ID_ANDROID",
        value = getRequiredProperty(key = "ADMOB_BANNER_AD_ID_ANDROID", defaultValue = "")
    )
    buildConfigField(
        name = "ADMOB_INTERSTITIAL_AD_ID_ANDROID",
        value = getRequiredProperty(key = "ADMOB_INTERSTITIAL_AD_ID_ANDROID", defaultValue = "")
    )
    buildConfigField(
        name = "ADMOB_REWARDED_AD_ID_ANDROID",
        value = getRequiredProperty(key = "ADMOB_REWARDED_AD_ID_ANDROID", defaultValue = "")
    )

    // ios Admob ids
    buildConfigField(
        name = "ADMOB_BANNER_AD_ID_IOS",
        value = getRequiredProperty(key = "ADMOB_BANNER_AD_ID_IOS", defaultValue = "")
    )
    buildConfigField(
        name = "ADMOB_INTERSTITIAL_AD_ID_IOS",
        value = getRequiredProperty(key = "ADMOB_INTERSTITIAL_AD_ID_IOS", defaultValue = "")
    )
    buildConfigField(
        name = "ADMOB_REWARDED_AD_ID_IOS",
        value = getRequiredProperty(key = "ADMOB_REWARDED_AD_ID_IOS", defaultValue = "")
    )
}

fun getRequiredProperty(
    key: String,
    defaultValue: String? = null,
    errorMessage: String = "Make sure you added `$key` in local.properties"
): String {
    val propertyValue: String? = gradleLocalProperties(rootDir, providers).getProperty(key)
    if (propertyValue.isNullOrEmpty() && defaultValue == null) {
        throw IllegalArgumentException(errorMessage)
    }
    return propertyValue ?: defaultValue ?: ""
}
