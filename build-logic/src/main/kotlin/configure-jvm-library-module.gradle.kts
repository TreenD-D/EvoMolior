/**
 * Convention plugin for pure JVM library modules (no Android/iOS/JS targets).
 * Used by all :evokammer:* submodules.
 *
 * Applies:
 * - org.jetbrains.kotlin.jvm
 * - org.jetbrains.kotlin.plugin.serialization
 *
 * Configures:
 * - JVM toolchain 17
 * - JVM target 17
 * - JUnit Platform for tests
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

pluginManager.apply("org.jetbrains.kotlin.jvm")
pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
