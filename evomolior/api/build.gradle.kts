/**
 * :engine:api
 *
 * The engine service layer — bridges the IPC contract with the full engine implementation.
 * Contains:
 * - Service interfaces ([EvoMoliorEngineService])
 * - Run configuration ([RunConfig])
 * - Serialisable event types ([EvoMoliorEvent])
 * - Ktor WebSocket IPC server ([EvoMoliorIpcServer])
 * - Real engine service implementation ([EvoMoliorRunService])
 * - Koin DI wiring ([ApiModule])
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:core"))
    // Engine modules — api is the top-level glue layer
    api(project(":engine:competition"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // Ktor server for WebSocket IPC bridge
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.koin.core)
    implementation(libs.napier)
    implementation(libs.commons.csv)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koog)
}

tasks.register<JavaExec>("scanRunLogSignatures") {
    group = "verification"
    description = "Scans a run log artifact for known failure signatures."

    val runLogPath = providers.gradleProperty("runLogPath")
    inputs.property("runLogPath", runLogPath)

    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.achulkov.evomolior.api.service.RunLogSignatureScannerMainKt")
    args(runLogPath.orNull ?: error("Provide -PrunLogPath=/path/to/log"))
}
