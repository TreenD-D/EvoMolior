import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose).apply(false)
    alias(libs.plugins.composeHotReload).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.google.services).apply(false)
    alias(libs.plugins.firebase.crashlytics).apply(false)
    alias(libs.plugins.buildConfig).apply(false)
    alias(libs.plugins.kotlinx.serialization).apply(false)
    alias(libs.plugins.room).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.kotlin.android).apply(false)
}

allprojects {
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Test>().configureEach {
        System.getProperty("java.io.tmpdir")
            ?.takeIf(String::isNotBlank)
            ?.let { systemProperty("java.io.tmpdir", it) }
    }
}

/**
 * Owns the cross-process acceptance-distribution lock for the lifetime of its consuming tasks.
 * Ordinary builds never instantiate this service. Gradle releases it on success or failure.
 */
abstract class AcceptanceDistributionLock : BuildService<AcceptanceDistributionLock.Parameters>, AutoCloseable {
    /** Filesystem authority supplied by the repository build. */
    interface Parameters : BuildServiceParameters {
        val lockFile: RegularFileProperty
    }

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Acquires the OS lock once, keeping untrusted owner diagnostics bounded. */
    @Synchronized
    fun acquire() {
        if (lock != null) return
        val lockPath = parameters.lockFile.get().asFile.toPath()
        Files.createDirectories(lockPath.parent)
        runCatching {
            Files.setPosixFilePermissions(
                lockPath.parent,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        val opened = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        try {
            runCatching {
                Files.setPosixFilePermissions(
                    lockPath,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                )
            }
            val acquired = try {
                opened.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquired == null) {
                val diagnostics = runCatching {
                    val buffer = java.nio.ByteBuffer.allocate(512)
                    opened.read(buffer)
                    StandardCharsets.UTF_8
                        .decode(java.nio.ByteBuffer.wrap(buffer.array(), 0, buffer.position()))
                        .toString()
                        .replace(Regex("[\\r\\n\\t]+"), " ")
                        .take(512)
                }.getOrDefault("")
                error(
                    "Appendix B acceptance lock is held at $lockPath; refusing acceptance " +
                        "distribution work. Lock-holder diagnostics (untrusted, max 512 chars): " +
                        diagnostics.ifBlank { "<unavailable>" },
                )
            }
            channel = opened
            lock = acquired
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }

    /** Releases the OS lock even when an acceptance task fails. */
    @Synchronized
    override fun close() {
        try {
            lock?.release()
        } finally {
            channel?.close()
            lock = null
            channel = null
        }
    }
}

val acceptanceDistributionLock = gradle.sharedServices.registerIfAbsent(
    "acceptanceDistributionLock",
    AcceptanceDistributionLock::class,
) {
    parameters.lockFile.set(layout.projectDirectory.file(".evomolior-locks/appendix-b-acceptance.lock"))
    maxParallelUsages.set(1)
}

allprojects {
    val lockService = acceptanceDistributionLock
    tasks.configureEach {
        if (path in setOf(
                ":engine:cli:prepareAcceptanceDistribution",
                ":engine:cli:assembleAcceptanceDistribution",
                ":engine:cli:verifyAcceptanceDistribution",
            )
        ) {
            usesService(lockService)
            doFirst { lockService.get().acquire() }
        }
    }
}

val remoteCliContainerScript = layout.projectDirectory.file("scripts/remote_cli_container.sh")

tasks.register<Exec>("buildCliContainer") {
    group = "deployment"
    description = "Builds the EvoMolior CLI dashboard container image. Configure EVOMOLIOR_CONTAINER_IMAGE/TAG via env or local.properties."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "build")
}

tasks.register<Exec>("pushCliContainer") {
    group = "deployment"
    description = "Pushes the EvoMolior CLI dashboard container image to the configured registry."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "push")
}

tasks.register<Exec>("deployCliContainer") {
    group = "deployment"
    description = "Deploys the configured EvoMolior CLI dashboard image to the remote Docker host over SSH."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "deploy")
}

tasks.register<Exec>("deployDirectCliContainer") {
    group = "deployment"
    description = "Streams an existing local CLI dashboard image to the remote Docker host over SSH, without a registry."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "deploy-direct")
}

tasks.register<Exec>("releaseCliContainer") {
    group = "deployment"
    description = "Builds, pushes, and deploys the EvoMolior CLI dashboard image."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "release")
}

tasks.register<Exec>("releaseDirectCliContainer") {
    group = "deployment"
    description = "Builds for the remote Docker platform, streams the image over SSH, and deploys without a registry."
    commandLine(remoteCliContainerScript.asFile.absolutePath, "release-direct")
}
