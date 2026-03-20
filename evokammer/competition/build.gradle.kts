/**
 * :evokammer:competition
 *
 * Multi-group coordination, Leak Oracle, Dispatch Relay, Resource Map Engine,
 * Scoring Engine, Grader Groups, Progressive Disclosure Engine.
 *
 * Depends only on :evokammer:core (domain types + GradingResult) and
 * :evokammer:orchestration for lifecycle hooks.
 * Does NOT depend on :evokammer:api to avoid circular dependencies.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":evokammer:core"))
    api(project(":evokammer:substrate"))
    api(project(":evokammer:orchestration"))

    // Koog (for grader agents, privacy transform agent)
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
