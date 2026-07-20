rootProject.name = "EvoMolior"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    //Added For compose hot reload support
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}


include(":composeApp")
include(":designsystem")
include(":libs:subscription:subscription-api")
include(":libs:subscription:subscription-revenuecat")
include(":libs:subscription:subscription-adapty")

include(":libs:auth:auth-api")
include(":libs:auth:auth-firebase")

// EvoMolior engine modules (JVM-only)
include(":engine:core")
project(":engine:core").projectDir = file("evomolior/core")
include(":engine:substrate")
project(":engine:substrate").projectDir = file("evomolior/substrate")
include(":engine:sandbox")
project(":engine:sandbox").projectDir = file("evomolior/sandbox")
include(":engine:agents")
project(":engine:agents").projectDir = file("evomolior/agents")
include(":engine:api")
project(":engine:api").projectDir = file("evomolior/api")
include(":engine:orchestration")
project(":engine:orchestration").projectDir = file("evomolior/orchestration")
include(":engine:competition")
project(":engine:competition").projectDir = file("evomolior/competition")
include(":engine:runtime")
project(":engine:runtime").projectDir = file("evomolior/runtime")
include(":engine:cli")
project(":engine:cli").projectDir = file("evomolior/cli")
