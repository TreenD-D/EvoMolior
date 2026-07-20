import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import org.gradle.api.Action
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
}

var appendixBAcceptanceLockChannel: FileChannel? = null
var appendixBAcceptanceLock: FileLock? = null

/**
 * Holds the Appendix B acceptance lock for the complete protected Gradle task graph. The OS lock
 * is authoritative; owner text is bounded, untrusted diagnostics only.
 */
gradle.taskGraph.whenReady(object : Action<TaskExecutionGraph> {
    override fun execute(graph: TaskExecutionGraph) {
        val protectedTasks = graph.allTasks.filter {
            it is KotlinCompile || it is JavaCompile || it is Test
        }
        if (protectedTasks.isNotEmpty()) {
            val lockPath = rootProject.layout.projectDirectory
                .dir(".evomolior-locks")
                .file("appendix-b-acceptance.lock")
                .asFile
                .toPath()
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
            val channel = FileChannel.open(
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
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    val diagnostics = runCatching {
                        channel.position(0)
                        val buffer = java.nio.ByteBuffer.allocate(512)
                        channel.read(buffer)
                        StandardCharsets.UTF_8
                            .decode(java.nio.ByteBuffer.wrap(buffer.array(), 0, buffer.position()))
                            .toString()
                            .replace(Regex("[\\r\\n\\t]+"), " ")
                            .take(512)
                    }.getOrDefault("")
                    error(
                        "Appendix B acceptance lock is held at $lockPath; refusing to execute " +
                            "${protectedTasks.size} KotlinCompile/JavaCompile/Test task(s). " +
                            "Lock-holder diagnostics (untrusted, max 512 chars): " +
                            diagnostics.ifBlank { "<unavailable>" },
                    )
                }
                appendixBAcceptanceLockChannel = channel
                appendixBAcceptanceLock = lock
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
})

gradle.buildFinished {
    runCatching { appendixBAcceptanceLock?.release() }
    runCatching { appendixBAcceptanceLockChannel?.close() }
    appendixBAcceptanceLock = null
    appendixBAcceptanceLockChannel = null
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
