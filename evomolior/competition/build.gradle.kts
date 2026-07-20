/**
 * :engine:competition
 *
 * Multi-group coordination, Leak Oracle, Dispatch Relay, Resource Map Engine,
 * Scoring Engine, Grader Groups, Progressive Disclosure Engine.
 *
 * Depends only on :engine:core (domain types + GradingResult) and
 * :engine:orchestration for lifecycle hooks.
 * Does NOT depend on :engine:api to avoid circular dependencies.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:core"))
    implementation(project(":engine:agents"))
    api(project(":engine:substrate"))
    api(project(":engine:orchestration"))

    // Koog (for grader agents, privacy transform agent)
    implementation(libs.koog)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.napier)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
