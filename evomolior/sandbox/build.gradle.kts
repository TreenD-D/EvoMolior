/**
 * :engine:sandbox
 *
 * Shuru microVM management, constitutional enforcement, tool sub-sandbox,
 * and web content sanitisation. This is the ONLY module that invokes Shuru CLI.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    // Ktor client for HTTP (AllowlistProxy + WebFetchClient)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.napier)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
