/**
 * :evokammer:substrate
 *
 * Room/SQLite persistence for all EvoKammer substrate layers.
 * Per-group isolated database files for Tier 1 isolation enforcement.
 */
plugins {
    id("configure-jvm-library-module")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

dependencies {
    api(project(":evokammer:core"))
    api(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.koin.core)

    ksp(libs.room.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

room {
    schemaDirectory("$projectDir/schemas")
}
