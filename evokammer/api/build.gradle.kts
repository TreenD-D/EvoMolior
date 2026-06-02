/**
 * :evokammer:api
 *
 * The engine service layer — bridges the IPC contract with the full engine implementation.
 * Contains:
 * - Service interfaces ([EvoKammerEngineService])
 * - Run configuration ([RunConfig])
 * - Serialisable event types ([EvoKammerEvent])
 * - Ktor WebSocket IPC server ([EvoKammerIpcServer])
 * - Real engine service implementation ([EvoKammerRunService])
 * - Koin DI wiring ([ApiModule])
 */
plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":evokammer:core"))
    // Engine modules — api is the top-level glue layer
    api(project(":evokammer:competition"))
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

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register<JavaExec>("scanRunLogSignatures") {
    group = "verification"
    description = "Scans a run log artifact for known failure signatures."

    val runLogPath = providers.gradleProperty("runLogPath")
    inputs.property("runLogPath", runLogPath)

    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.achulkov.evokammer.api.service.RunLogSignatureScannerMainKt")
    args(runLogPath.orNull ?: error("Provide -PrunLogPath=/path/to/log"))
}
