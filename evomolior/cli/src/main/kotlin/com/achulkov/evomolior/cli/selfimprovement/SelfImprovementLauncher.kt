package com.achulkov.evomolior.cli.selfimprovement

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.bufferedWriter

private val launchJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Launch mode for the isolated workspace.
 */
enum class SelfImprovementWorkspaceMode {
    AUTO,
    WORKTREE,
    COPY
}

/**
 * Structured launch specification for an isolated self-improvement run.
 *
 * @param sourceRepoDir Source repository checkout to isolate.
 * @param workspaceRootDir Parent directory where the isolated workspace should be created.
 * @param workspaceName Stable child directory name for the isolated workspace.
 * @param mode Preferred isolation mode.
 * @param command Command and arguments to execute inside the isolated workspace.
 * @param environment Environment variables for the child process.
 * @param keepWorkspace Whether the isolated workspace should be retained after the launch.
 */
@Serializable
data class SelfImprovementLaunchSpec(
    val sourceRepoDir: String,
    val workspaceRootDir: String,
    val workspaceName: String,
    val mode: SelfImprovementWorkspaceMode = SelfImprovementWorkspaceMode.AUTO,
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val keepWorkspace: Boolean = true
) {
    init {
        require(sourceRepoDir.isNotBlank()) { "sourceRepoDir cannot be blank" }
        require(workspaceRootDir.isNotBlank()) { "workspaceRootDir cannot be blank" }
        require(workspaceName.isNotBlank()) { "workspaceName cannot be blank" }
        require(command.isNotEmpty()) { "command cannot be empty" }
    }
}

/**
 * Durable manifest for an isolated self-improvement launch.
 *
 * @param sourceRepoDir Source repository path.
 * @param workspaceDir Isolated workspace path.
 * @param mode Isolation mode that was selected.
 * @param command Command executed in the isolated workspace.
 * @param launchedAt ISO-8601 launch timestamp.
 * @param completedAt ISO-8601 completion timestamp, if available.
 * @param exitCode Child process exit code, if available.
 * @param logFilePath Durable command log path.
 * @param status Launch status.
 */
@Serializable
data class SelfImprovementLaunchManifest(
    val sourceRepoDir: String,
    val workspaceDir: String,
    val mode: SelfImprovementWorkspaceMode,
    val command: List<String>,
    val launchedAt: String,
    val completedAt: String? = null,
    val exitCode: Int? = null,
    val logFilePath: String,
    val status: SelfImprovementLaunchStatus
)

/**
 * Launch status for a self-improvement run.
 */
@Serializable
enum class SelfImprovementLaunchStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Final report emitted by the launcher.
 *
 * @param manifestPath Path to the persisted manifest.
 * @param workspaceDir Path to the isolated workspace that was prepared.
 * @param logFilePath Path to the child-process log.
 * @param exitCode Child-process exit code.
 */
@Serializable
data class SelfImprovementLaunchReport(
    val manifestPath: String,
    val workspaceDir: String,
    val logFilePath: String,
    val exitCode: Int
)

/**
 * Creates an isolated workspace and runs a command inside it.
 *
 * The launcher prefers `git worktree` when available, but falls back to a filesystem copy when
 * the source checkout is not a Git repository or worktree creation fails.
 *
 * @param workspacePreparer Strategy used to prepare the isolated workspace.
 * @param processRunner Strategy used to execute the child process.
 * @param clock Source of timestamps for manifests and logs.
 */
class SelfImprovementLauncher(
    private val workspacePreparer: WorkspacePreparer = GitAwareWorkspacePreparer(),
    private val processRunner: ChildProcessRunner = DefaultChildProcessRunner(),
    private val clock: () -> Instant = Instant::now
) {
    /**
     * Launches the command inside an isolated workspace and returns the durable report.
     *
     * @param spec Launch specification.
     */
    fun launch(spec: SelfImprovementLaunchSpec): SelfImprovementLaunchReport {
        val sourceRepoDir = Path.of(spec.sourceRepoDir).toAbsolutePath().normalize()
        val workspaceRootDir = Path.of(spec.workspaceRootDir).toAbsolutePath().normalize()
        require(Files.exists(sourceRepoDir) && Files.isDirectory(sourceRepoDir)) {
            "Source repository must exist and be a directory: $sourceRepoDir"
        }
        if (Files.exists(workspaceRootDir)) {
            require(Files.isDirectory(workspaceRootDir)) {
                "Workspace root must be a directory: $workspaceRootDir"
            }
        }

        val workspaceDir = workspaceRootDir.resolve(spec.workspaceName).toAbsolutePath().normalize()
        require(!workspaceDir.startsWith(sourceRepoDir)) {
            "Workspace directory must not live inside the source repository: $workspaceDir"
        }
        require(!sourceRepoDir.startsWith(workspaceDir)) {
            "Source repository must not be inside the workspace directory: $workspaceDir"
        }
        require(!Files.exists(workspaceDir)) {
            "Workspace directory already exists: $workspaceDir"
        }

        Files.createDirectories(workspaceRootDir)
        Files.createDirectories(workspaceDir.parent)
        val preparedMode = workspacePreparer.prepare(
            sourceRepoDir = sourceRepoDir,
            workspaceDir = workspaceDir,
            preferredMode = spec.mode
        )

        val launchDir = workspaceDir.resolve(".evomolior-self-improve")
        Files.createDirectories(launchDir)
        val logFile = launchDir.resolve("launch.log")
        val manifestFile = launchDir.resolve("launch-manifest.json")
        val launchedAt = clock().toString()

        val runningManifest = SelfImprovementLaunchManifest(
            sourceRepoDir = sourceRepoDir.toString(),
            workspaceDir = workspaceDir.toString(),
            mode = preparedMode,
            command = spec.command,
            launchedAt = launchedAt,
            logFilePath = logFile.toString(),
            status = SelfImprovementLaunchStatus.RUNNING
        )
        writeManifest(manifestFile, runningManifest)

        val result = runCatching {
            processRunner.run(
                command = spec.command,
                workingDirectory = workspaceDir,
                environment = spec.environment,
                logFile = logFile
            )
        }

        val completedAt = clock().toString()
        val finalManifest = result.fold(
            onSuccess = { execution ->
                SelfImprovementLaunchManifest(
                    sourceRepoDir = sourceRepoDir.toString(),
                    workspaceDir = workspaceDir.toString(),
                    mode = preparedMode,
                    command = spec.command,
                    launchedAt = launchedAt,
                    completedAt = completedAt,
                    exitCode = execution.exitCode,
                    logFilePath = logFile.toString(),
                    status = if (execution.exitCode == 0) {
                        SelfImprovementLaunchStatus.COMPLETED
                    } else {
                        SelfImprovementLaunchStatus.FAILED
                    }
                )
            },
            onFailure = { error ->
                Files.newBufferedWriter(
                    logFile,
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                ).use { writer ->
                    writer.appendLine()
                    writer.appendLine("[launcher-error] ${error::class.simpleName}: ${error.message}")
                }
                SelfImprovementLaunchManifest(
                    sourceRepoDir = sourceRepoDir.toString(),
                    workspaceDir = workspaceDir.toString(),
                    mode = preparedMode,
                    command = spec.command,
                    launchedAt = launchedAt,
                    completedAt = completedAt,
                    exitCode = -1,
                    logFilePath = logFile.toString(),
                    status = SelfImprovementLaunchStatus.FAILED
                )
            }
        )

        writeManifest(manifestFile, finalManifest)
        if (!spec.keepWorkspace && finalManifest.exitCode == 0) {
            workspaceDir.toFile().deleteRecursively()
        }

        return SelfImprovementLaunchReport(
            manifestPath = manifestFile.toString(),
            workspaceDir = workspaceDir.toString(),
            logFilePath = logFile.toString(),
            exitCode = finalManifest.exitCode ?: -1
        )
    }

    private fun writeManifest(manifestFile: Path, manifest: SelfImprovementLaunchManifest) {
        manifestFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(launchJson.encodeToString(SelfImprovementLaunchManifest.serializer(), manifest))
            writer.newLine()
        }
    }
}

/**
 * Prepares an isolated workspace from a source repository.
 */
interface WorkspacePreparer {
    /**
     * Prepares the isolated workspace.
     *
     * @param sourceRepoDir Source repository path.
     * @param workspaceDir Destination workspace path.
     * @param preferredMode Preferred isolation mode.
     * @return The actual mode that was used.
     */
    fun prepare(
        sourceRepoDir: Path,
        workspaceDir: Path,
        preferredMode: SelfImprovementWorkspaceMode
    ): SelfImprovementWorkspaceMode
}

/**
 * Runs the child process inside the prepared workspace.
 */
interface ChildProcessRunner {
    /**
     * Runs a command inside a workspace.
     *
     * @param command Command and arguments to execute.
     * @param workingDirectory Workspace root.
     * @param environment Environment variables for the child process.
     * @param logFile Durable combined stdout/stderr log path.
     * @return Child process result.
     */
    fun run(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        logFile: Path
    ): ChildProcessResult
}

/**
 * Child process result.
 *
 * @param exitCode Process exit code.
 */
data class ChildProcessResult(
    val exitCode: Int
)

private class DefaultChildProcessRunner : ChildProcessRunner {
    override fun run(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        logFile: Path
    ): ChildProcessResult {
        Files.createDirectories(logFile.parent)
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .apply { environment().putAll(environment) }
            .start()
        val exitCode = process.waitFor()
        return ChildProcessResult(exitCode = exitCode)
    }
}

private class GitAwareWorkspacePreparer : WorkspacePreparer {
    override fun prepare(
        sourceRepoDir: Path,
        workspaceDir: Path,
        preferredMode: SelfImprovementWorkspaceMode
    ): SelfImprovementWorkspaceMode {
        return when (preferredMode) {
            SelfImprovementWorkspaceMode.WORKTREE -> {
                if (tryPrepareGitWorktree(sourceRepoDir, workspaceDir)) SelfImprovementWorkspaceMode.WORKTREE
                else {
                    cleanupPartialWorkspace(workspaceDir)
                    copyRepository(sourceRepoDir, workspaceDir)
                    SelfImprovementWorkspaceMode.COPY
                }
            }
            SelfImprovementWorkspaceMode.COPY -> {
                copyRepository(sourceRepoDir, workspaceDir)
                SelfImprovementWorkspaceMode.COPY
            }
            SelfImprovementWorkspaceMode.AUTO -> {
                if (looksLikeGitRepository(sourceRepoDir) && tryPrepareGitWorktree(sourceRepoDir, workspaceDir)) {
                    SelfImprovementWorkspaceMode.WORKTREE
                } else {
                    cleanupPartialWorkspace(workspaceDir)
                    copyRepository(sourceRepoDir, workspaceDir)
                    SelfImprovementWorkspaceMode.COPY
                }
            }
        }
    }

    private fun looksLikeGitRepository(sourceRepoDir: Path): Boolean =
        Files.exists(sourceRepoDir.resolve(".git"))

    private fun tryPrepareGitWorktree(sourceRepoDir: Path, workspaceDir: Path): Boolean {
        return runCatching {
            Files.createDirectories(workspaceDir.parent)
            val process = ProcessBuilder(
                "git",
                "-C",
                sourceRepoDir.toString(),
                "worktree",
                "add",
                "--detach",
                workspaceDir.toString(),
                "HEAD"
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workspaceDir.parent.resolve("worktree.log").toFile()))
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        }.getOrDefault(false)
    }

    private fun copyRepository(sourceRepoDir: Path, workspaceDir: Path) {
        Files.createDirectories(workspaceDir)
        Files.walkFileTree(
            sourceRepoDir,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (dir != sourceRepoDir && shouldSkip(sourceRepoDir.relativize(dir))) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    val relative = sourceRepoDir.relativize(dir)
                    val target = workspaceDir.resolve(relative.toString())
                    Files.createDirectories(target)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    val relative = sourceRepoDir.relativize(file)
                    if (shouldSkip(relative)) return java.nio.file.FileVisitResult.CONTINUE
                    val target = workspaceDir.resolve(relative.toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            }
        )
    }

    private fun cleanupPartialWorkspace(workspaceDir: Path) {
        if (Files.exists(workspaceDir)) {
            workspaceDir.toFile().deleteRecursively()
        }
    }

    private fun shouldSkip(relativePath: Path): Boolean {
        val normalized = relativePath.toString().replace(File.separatorChar, '/')
        return normalized == ".git" ||
            normalized.startsWith(".git/") ||
            normalized == ".gradle" ||
            normalized.startsWith(".gradle/") ||
            normalized == "build" ||
            normalized.startsWith("build/") ||
            normalized.contains("/build/") ||
            normalized == "out" ||
            normalized.startsWith("out/") ||
            normalized.contains("/out/") ||
            normalized == ".idea" ||
            normalized.startsWith(".idea/") ||
            normalized == "documentation/tmp" ||
            normalized.startsWith("documentation/tmp/") ||
            normalized.contains("/documentation/tmp/") ||
            normalized.endsWith(".iml")
    }
}
