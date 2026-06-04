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

// EvoKammer engine modules (JVM-only)
include(":evokammer:core")
include(":evokammer:substrate")
include(":evokammer:sandbox")
include(":evokammer:agents")
include(":evokammer:api")
include(":evokammer:orchestration")
include(":evokammer:competition")
include(":evokammer:runtime")
include(":evokammer:cli")
