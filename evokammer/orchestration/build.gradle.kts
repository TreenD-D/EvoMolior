/**
 * :evokammer:orchestration
 *
 * Lifecycle Manager, Evolutionary Selector, Epigenetic Inheritance Engine,
 * Genesis Council, Inspector/Auditor, Meta-Planner, and engine entry point.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":evokammer:core"))
    api(project(":evokammer:substrate"))
    api(project(":evokammer:agents"))
    api(project(":evokammer:sandbox"))
    // NOTE: does NOT depend on :evokammer:api to avoid a circular dependency
    // (api → competition → orchestration)

    // Koog (for Council agents, Meta-Planner, Auditor — LLM agents used in orchestration)
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.core)
    implementation(libs.koog.agents.tools)
    implementation(libs.koog.prompt.executor.llms.all)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.napier)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
