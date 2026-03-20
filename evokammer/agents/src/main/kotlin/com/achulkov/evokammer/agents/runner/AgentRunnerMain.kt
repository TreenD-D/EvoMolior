package com.achulkov.evokammer.agents.runner

import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Main entry point for the agent process running **inside** a Shuru microVM.
 *
 * Reads the boot context + API key from environment variables, bootstraps the
 * [AgentRunner], and blocks until the agent exits.
 *
 * Environment variables:
 * - `EVOKAMMER_BOOT_CONTEXT`  — JSON-encoded [AgentBootContext] (or use `/boot/context.json`)
 * - `OPENROUTER_API_KEY`      — OpenRouter API key for LLM calls
 * - `EVOKAMMER_GROUP_PROFILE` — Group profile name (Library / Factory / Commune / Monastery / Mercenary)
 * - `EVOKAMMER_DATA_DIR`      — Path to the data root for Room DB files (default: `~/.evokammer/data`)
 */
fun main() {
    // Initialise logging
    Napier.base(DebugAntilog())

    Napier.i("AgentRunnerMain: starting process pid=${ProcessHandle.current().pid()}")

    val bootContext = AgentRunner.readBootContext()
    val apiKey = AgentRunner.readApiKey()
    val groupProfile = AgentRunner.readGroupProfile()

    val dataDir = System.getenv("EVOKAMMER_DATA_DIR")
        ?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".evokammer/data")

    val substrateDatabaseFactory = SubstrateDatabaseFactory(dataDir)

    val runner = AgentRunner(
        bootContext = bootContext,
        substrateDatabaseFactory = substrateDatabaseFactory,
        openRouterApiKey = apiKey,
        groupProfile = groupProfile
    )

    runBlocking { runner.run() }

    Napier.i("AgentRunnerMain: process exiting normally for agent=${bootContext.agentId}")
}
