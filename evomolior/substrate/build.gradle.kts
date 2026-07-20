/**
 * :engine:substrate
 *
 * Room/SQLite persistence for all EvoMolior substrate layers.
 * Per-group isolated database files for Tier 1 isolation enforcement.
 */
plugins {
    id("configure-jvm-library-module")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

dependencies {
    api(project(":engine:core"))
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

val kspCacheMainDir = layout.buildDirectory.dir("kspCaches/main")
val kspSymbolsCacheFile = layout.buildDirectory.file("kspCaches/main/symbols")
val prepareKspCacheDirs by tasks.registering(Exec::class) {
    val cachePath = kspCacheMainDir.get().asFile.absolutePath
    val symbolsPath = kspSymbolsCacheFile.get().asFile.absolutePath
    commandLine("sh", "-c", "mkdir -p '$cachePath'; if [ ! -e '$symbolsPath' ]; then printf '{}' > '$symbolsPath'; fi")
}

tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(prepareKspCacheDirs)
}
