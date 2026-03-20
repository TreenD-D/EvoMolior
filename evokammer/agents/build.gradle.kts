/**
 * :evokammer:agents
 *
 * Koog integration layer: agent factory, all EvoKammer tools, agent runner loop,
 * Death Note protocol, and sub-agent delegation. The ONLY module using Koog directly.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":evokammer:core"))
    api(project(":evokammer:substrate"))

    // Koog agent framework (JetBrains)
    implementation(libs.koog.agents)
    implementation(libs.koog.agents.core)
    implementation(libs.koog.agents.tools)
    implementation(libs.koog.prompt.executor.llms.all)

    implementation(project(":evokammer:sandbox"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.napier)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
