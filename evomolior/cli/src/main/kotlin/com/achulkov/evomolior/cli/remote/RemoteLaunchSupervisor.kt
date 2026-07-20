package com.achulkov.evomolior.cli.remote

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.system.exitProcess

private const val SUPERVISOR_MAIN_CLASS =
    "com.achulkov.evomolior.cli.remote.RemoteLaunchSupervisorKt"
private const val SUPERVISOR_AUTHORIZATION_TIMEOUT_MILLIS = 30_000L
private const val SUPERVISOR_POLL_MILLIS = 25L
private const val AUTHORIZATION_FILE = ".launch-authorized"
internal const val SUPERVISOR_CHILD_START_FAILURE_EXIT = 71
internal const val SUPERVISOR_AUTHORIZATION_TIMEOUT_EXIT = 75
internal const val SUPERVISOR_AUTHORIZATION_REJECTED_EXIT = 77

internal data class PendingRemoteProcess(
    val process: Process,
    private val authorizationFile: Path,
    private val authorizationToken: String
) {
    fun authorize() {
        val temporary = authorizationFile.resolveSibling("${authorizationFile.fileName}.tmp")
        temporary.deleteIfExists()
        Files.writeString(
            temporary,
            authorizationToken,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        runCatching {
            Files.move(
                temporary,
                authorizationFile,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temporary, authorizationFile)
        }
    }
}

internal class RemoteProcessSupervisor(
    private val javaExecutable: String = defaultJavaExecutable(),
    private val classpath: String = System.getProperty("java.class.path"),
    private val tokenSupplier: () -> String = ::newSupervisorToken
) {
    fun start(
        command: List<String>,
        workingDirectory: Path,
        logPath: Path
    ): PendingRemoteProcess {
        require(command.isNotEmpty()) { "supervised command cannot be empty" }
        val authorizationFile = workingDirectory.resolve(AUTHORIZATION_FILE)
        authorizationFile.deleteIfExists()
        val token = tokenSupplier()
        val supervisorCommand = listOf(
            javaExecutable,
            "-cp",
            classpath,
            SUPERVISOR_MAIN_CLASS,
            authorizationFile.toString(),
            token,
            SUPERVISOR_AUTHORIZATION_TIMEOUT_MILLIS.toString()
        ) + command
        val process = ProcessBuilder(supervisorCommand)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
            .start()
        return PendingRemoteProcess(process, authorizationFile, token)
    }
}

internal fun runRemoteLaunchSupervisor(args: Array<String>): Int {
    if (args.size < 4) return 64
    val authorizationFile = runCatching { Path.of(args[0]).toAbsolutePath().normalize() }
        .getOrNull()
        ?: return 64
    val expectedToken = args[1]
    val timeoutMillis = args[2].toLongOrNull()?.takeIf { it in 1..300_000 } ?: return 64
    val command = args.drop(3)
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (!Files.exists(authorizationFile, LinkOption.NOFOLLOW_LINKS)) {
        if (System.nanoTime() >= deadline) return SUPERVISOR_AUTHORIZATION_TIMEOUT_EXIT
        Thread.sleep(SUPERVISOR_POLL_MILLIS)
    }
    if (
        !Files.isRegularFile(authorizationFile, LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(authorizationFile) ||
        Files.size(authorizationFile) > 256
    ) {
        return SUPERVISOR_AUTHORIZATION_REJECTED_EXIT
    }
    val token = runCatching {
        Files.readString(authorizationFile, StandardCharsets.UTF_8)
    }.getOrNull() ?: return SUPERVISOR_AUTHORIZATION_REJECTED_EXIT
    authorizationFile.deleteIfExists()
    if (!constantTimeTokenEquals(token, expectedToken)) return SUPERVISOR_AUTHORIZATION_REJECTED_EXIT
    return runCatching {
        ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor()
    }.getOrElse {
        SUPERVISOR_CHILD_START_FAILURE_EXIT
    }
}

/**
 * Runs the hidden dashboard launch supervisor entry point.
 *
 * @param args Authorization-file, token, timeout, and child-command arguments.
 */
fun main(args: Array<String>) {
    exitProcess(runRemoteLaunchSupervisor(args))
}

private fun defaultJavaExecutable(): String {
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "java.exe"
    } else {
        "java"
    }
    return Path.of(System.getProperty("java.home"), "bin", executable).toString()
}

private fun newSupervisorToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun constantTimeTokenEquals(left: String, right: String): Boolean =
    java.security.MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8)
    )
