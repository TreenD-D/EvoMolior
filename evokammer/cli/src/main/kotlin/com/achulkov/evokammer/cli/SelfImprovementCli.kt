package com.achulkov.evokammer.cli

import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLaunchReport
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLaunchSpec
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLauncher
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLoopRunner
import kotlinx.serialization.encodeToString
import kotlin.system.exitProcess

/**
 * Runs the self-improvement CLI entrypoint.
 *
 * Supports two modes:
 * 1. **Workspace isolation** (default): Creates an isolated workspace and runs a command inside it.
 * 2. **Overseer cycle** (`--overseer-cycle`): Runs the full recursive self-improvement loop,
 *    including hypothesis proposal, experiment execution, and result evaluation.
 *
 * @param args Raw command-line arguments after the `self-improve` subcommand.
 */
fun runSelfImprovementCli(args: Array<String>) {
    try {
        val options = SelfImprovementCliArguments.parse(args) ?: run {
            println(SelfImprovementCliArguments.usage())
            exitProcess(0)
        }

        if (options.overseerCycle) {
            runOverseerCycle(options)
        } else {
            runWorkspaceIsolation(options)
        }
    } catch (e: SelfImprovementCliUsageException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(SelfImprovementCliArguments.usage())
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("EvoKammer self-improvement launcher failed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun runWorkspaceIsolation(options: SelfImprovementCliOptions) {
    val launcher = SelfImprovementLauncher()
    val report = launcher.launch(
        SelfImprovementLaunchSpec(
            sourceRepoDir = options.sourceRepoDir.toString(),
            workspaceRootDir = options.workspaceRootDir.toString(),
            workspaceName = options.workspaceName,
            mode = options.mode,
            command = options.command,
            environment = options.environment,
            keepWorkspace = options.keepWorkspace
        )
    )
    println(cliJson.encodeToString(SelfImprovementLaunchReport.serializer(), report))
    if (report.exitCode != 0) {
        exitProcess(report.exitCode)
    }
}

private fun runOverseerCycle(options: SelfImprovementCliOptions) {
    val apiKey = options.environment["OPENROUTER_API_KEY"]
        ?: System.getenv("OPENROUTER_API_KEY")
        ?: throw SelfImprovementCliUsageException(
            "OPENROUTER_API_KEY must be set via -env OPENROUTER_API_KEY=... or the environment"
        )

    val outputRoot = options.workspaceRootDir.resolve(options.workspaceName)

    val runner = SelfImprovementLoopRunner(
        workspaceRoot = outputRoot,
        openRouterApiKey = apiKey
    )

    val reports = runner.runCycle(cycleLimit = options.overseerCycles)

    val reportJson = cliJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(SelfImprovementLoopRunner.CycleReport.serializer()),
        reports
    )
    println(reportJson)

    val hasErrors = reports.any { it.errors.isNotEmpty() }
    if (hasErrors) {
        System.err.println("One or more cycles reported errors. Check the output for details.")
    }
}
