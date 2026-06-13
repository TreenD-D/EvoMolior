package com.achulkov.evokammer.cli

import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementWorkspaceMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parsed arguments for the self-improvement launcher command.
 *
 * @param sourceRepoDir Repository checkout to isolate (or use as baseline for the loop).
 * @param workspaceRootDir Parent directory for the isolated workspace.
 * @param workspaceName Child workspace directory name.
 * @param mode Isolation mode.
 * @param command Command and arguments to run inside the isolated workspace.
 * @param environment Extra environment variables for the child process.
 * @param keepWorkspace Whether to keep the workspace after a successful run.
 * @param overseerCycle When true, runs the full recursive self-improvement loop instead
 *   of just launching a command in an isolated workspace.
 * @param overseerExecuteExperiments When true, overseer-cycle mode launches scheduled
 *   experiment runs instead of stopping after scheduling.
 * @param overseerRunAllCycles When true, overseer-cycle mode continues for the full
 *   requested cycle count even if a cycle makes no progress.
 * @param overseerCycles Maximum number of overseer cycles to run (default 3).
 */
internal data class SelfImprovementCliOptions(
    val sourceRepoDir: Path,
    val workspaceRootDir: Path,
    val workspaceName: String,
    val mode: SelfImprovementWorkspaceMode,
    val command: List<String>,
    val environment: Map<String, String>,
    val keepWorkspace: Boolean,
    val overseerCycle: Boolean = false,
    val overseerExecuteExperiments: Boolean = false,
    val overseerRunAllCycles: Boolean = false,
    val overseerCycles: Int = 3
)

internal object SelfImprovementCliArguments {
    fun parse(args: Array<String>): SelfImprovementCliOptions? {
        if (args.any { it == "-h" || it == "--help" }) {
            return null
        }
        if (args.isEmpty()) {
            throw SelfImprovementCliUsageException(usage())
        }

        var sourceRepoDir: Path? = null
        var workspaceRootDir: Path? = null
        var workspaceName: String? = null
        var mode = SelfImprovementWorkspaceMode.AUTO
        var keepWorkspace = true
        var command: String? = null
        val commandArgs = mutableListOf<String>()
        val environment = linkedMapOf<String, String>()
        var overseerCycle = false
        var overseerExecuteExperiments = false
        var overseerRunAllCycles = false
        var overseerCycles = 3

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "-source", "--source" -> {
                    val value = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                    sourceRepoDir = Path.of(value)
                }
                "-workspace-root", "--workspace-root" -> {
                    val value = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                    workspaceRootDir = Path.of(value)
                }
                "-workspace-name", "--workspace-name" -> {
                    workspaceName = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                }
                "-mode", "--mode" -> {
                    val value = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                    mode = runCatching { SelfImprovementWorkspaceMode.valueOf(value.uppercase()) }
                        .getOrElse {
                            throw SelfImprovementCliUsageException("Unknown workspace mode: $value")
                        }
                }
                "-command", "--command" -> {
                    command = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                }
                "-arg", "--arg" -> {
                    commandArgs += args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                }
                "-env", "--env" -> {
                    val mapping = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                    val separator = mapping.indexOf('=')
                    if (separator <= 0 || separator == mapping.lastIndex) {
                        throw SelfImprovementCliUsageException("Environment overrides must use KEY=VALUE syntax, got: $mapping")
                    }
                    val key = mapping.substring(0, separator).trim()
                    val value = mapping.substring(separator + 1)
                    require(key.isNotBlank()) { "Environment variable name cannot be blank" }
                    environment[key] = value
                }
                "-keep-workspace", "--keep-workspace" -> keepWorkspace = true
                "-cleanup", "--cleanup" -> keepWorkspace = false
                "-overseer-cycle", "--overseer-cycle" -> overseerCycle = true
                "-overseer-execute", "--overseer-execute" -> overseerExecuteExperiments = true
                "-overseer-run-all-cycles", "--overseer-run-all-cycles" -> overseerRunAllCycles = true
                "-overseer-cycles", "--overseer-cycles" -> {
                    val value = args.getOrNull(++index) ?: throw SelfImprovementCliUsageException("Missing value for $arg")
                    overseerCycles = value.toIntOrNull()
                        ?: throw SelfImprovementCliUsageException("Invalid cycle count: $value")
                    require(overseerCycles > 0) { "Cycle count must be positive" }
                }
                else -> throw SelfImprovementCliUsageException("Unknown argument: $arg")
            }
            index++
        }

        val resolvedSourceRepoDir = sourceRepoDir
            ?: throw SelfImprovementCliUsageException("Missing required -source directory")
        val resolvedWorkspaceRootDir = workspaceRootDir
            ?: throw SelfImprovementCliUsageException("Missing required -workspace-root directory")
        val resolvedWorkspaceName = workspaceName
            ?: throw SelfImprovementCliUsageException("Missing required -workspace-name")

        if (!Files.exists(resolvedSourceRepoDir) || !Files.isDirectory(resolvedSourceRepoDir)) {
            throw SelfImprovementCliUsageException("Source repository must be an existing directory: $resolvedSourceRepoDir")
        }
        if (Files.exists(resolvedWorkspaceRootDir) && !Files.isDirectory(resolvedWorkspaceRootDir)) {
            throw SelfImprovementCliUsageException("Workspace root must be a directory: $resolvedWorkspaceRootDir")
        }

        val resolvedCommand = if (overseerCycle) {
            // Command is optional in overseer-cycle mode
            command?.let { listOf(it) + commandArgs } ?: emptyList()
        } else {
            command?.let { listOf(it) + commandArgs }
                ?: throw SelfImprovementCliUsageException("Missing required -command executable")
        }

        return SelfImprovementCliOptions(
            sourceRepoDir = resolvedSourceRepoDir.toAbsolutePath().normalize(),
            workspaceRootDir = resolvedWorkspaceRootDir.toAbsolutePath().normalize(),
            workspaceName = resolvedWorkspaceName,
            mode = mode,
            command = resolvedCommand,
            environment = environment,
            keepWorkspace = keepWorkspace,
            overseerCycle = overseerCycle,
            overseerExecuteExperiments = overseerExecuteExperiments,
            overseerRunAllCycles = overseerRunAllCycles,
            overseerCycles = overseerCycles
        )
    }

    fun usage(): String = """
        EvoKammer self-improvement launcher

        Modes:
          (default) Workspace isolation — create an isolated workspace and run a command inside it.
          --overseer-cycle  — run the full recursive self-improvement loop with hypothesis
                              proposal, experiment execution, and result evaluation.

        Workspace mode (default):
          Required:
            -source /path/to/repository
            -workspace-root /path/to/output-root (created if missing)
            -workspace-name isolated-workspace-name
            -command executable

          Optional:
            -mode auto|worktree|copy
            -arg additional command argument (repeatable)
            -env KEY=VALUE (repeatable)
            -cleanup

          Example:
            evokammer self-improve \
              -source /path/to/EvoMolior \
              -workspace-root /tmp/evokammer-self-improve \
              -workspace-name experiment-001 \
              -mode auto \
              -command ./gradlew \
              -arg test \
              -arg --no-daemon

        Overseer cycle mode:
          Required:
            -source /path/to/repository
            -workspace-root /path/to/output-root (created if missing)
            -workspace-name cycle-output-name
            -env OPENROUTER_API_KEY=sk-...

          Optional:
            -overseer-cycle           (enable this mode)
            -overseer-execute         (launch scheduled experiment runs)
            -overseer-run-all-cycles  (do not stop early when a cycle makes no progress)
            -overseer-cycles N        (max cycles, default 3)

          Example:
            evokammer self-improve \
              -source /path/to/EvoMolior \
              -workspace-root /tmp/evokammer-self-improve \
              -workspace-name cycle-001 \
              -overseer-cycle \
              -overseer-cycles 5 \
              -env OPENROUTER_API_KEY=sk-...
    """.trimIndent()
}

internal class SelfImprovementCliUsageException(message: String) : IllegalArgumentException(message)
