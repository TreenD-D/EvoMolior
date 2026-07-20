/**
 * :engine:orchestration
 *
 * Lifecycle Manager, Evolutionary Selector, Epigenetic Inheritance Engine,
 * Genesis Council, Inspector/Auditor, Meta-Planner, and engine entry point.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:core"))
    api(project(":engine:substrate"))
    api(project(":engine:agents"))
    api(project(":engine:sandbox"))
    // NOTE: does NOT depend on :engine:api to avoid a circular dependency
    // (api → competition → orchestration)

    // Koog (for Council agents, Meta-Planner, Auditor — LLM agents used in orchestration)
    implementation(libs.koog)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.napier)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
