/**
 * :engine:agents
 *
 * Koog integration layer: agent factory, all EvoMolior tools, agent runner loop,
 * Death Note protocol, and sub-agent delegation. The ONLY module using Koog directly.
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:core"))
    api(project(":engine:substrate"))

    // Koog agent framework (JetBrains)
    implementation(libs.koog)
    implementation(libs.koog.prompt.executor.openrouter.client)
    implementation(libs.koog.prompt.executor.dashscope.client)

    implementation(project(":engine:sandbox"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.napier)
    implementation(libs.json.schema.validator)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
