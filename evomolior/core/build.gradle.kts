/**
 * :engine:core
 *
 * Pure domain module — no external dependencies except Kotlin stdlib,
 * kotlinx-serialization, kotlinx-coroutines, and kotlinx-datetime.
 * Zero Room, Koog, Ktor, or Android dependencies allowed here.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.uuid)
    implementation(libs.koin.core)
    implementation(libs.re2j)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
