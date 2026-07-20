package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
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
 * - `EVOMOLIOR_BOOT_CONTEXT`  — JSON-encoded [AgentBootContext] (or use `/boot/context.json`)
 * - `EVOMOLIOR_LLM_BACKEND`   — `openrouter` or `qwen-cloud` (default: openrouter)
 * - `OPENROUTER_API_KEY`      — OpenRouter API key for OpenRouter calls
 * - `QWEN_API_KEY`            — Qwen Cloud API key for qwen-cloud calls
 * - `EVOMOLIOR_GROUP_PROFILE` — Group profile name (Library / Factory / Commune / Monastery / Mercenary)
 * - `EVOMOLIOR_DATA_DIR`      — Path to the data root for Room DB files (default: `~/.evomolior/data`)
 */
fun main() {
    // Initialise logging
    Napier.base(DebugAntilog())

    Napier.i("AgentRunnerMain: starting process pid=${ProcessHandle.current().pid()}")

    val bootContext = AgentRunner.readBootContext()
    val llmBackend = AgentRunner.readLlmBackend()
    val apiKey = AgentRunner.readApiKey(llmBackend)
    val groupProfile = AgentRunner.readGroupProfile()

    val dataDir = System.getenv("EVOMOLIOR_DATA_DIR")
        ?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".evomolior/data")

    val substrateDatabaseFactory = SubstrateDatabaseFactory(dataDir)

    val runner = AgentRunner(
        bootContext = bootContext,
        substrateDatabaseFactory = substrateDatabaseFactory,
        openRouterApiKey = apiKey,
        llmBackend = llmBackend,
        groupProfile = groupProfile
    )

    runBlocking { runner.run() }

    Napier.i("AgentRunnerMain: process exiting normally for agent=${bootContext.agentId}")
}
